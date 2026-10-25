//! Foreground LAN sessions. UI and scripts use the same authenticated state machine.
use crate::{
    crypto::{self, Established},
    files::{Engine, Saved},
    profile::{self, Message, Peer, Profile},
    wire::*,
};
use anyhow::{Context, Result, ensure};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use std::{
    collections::HashMap,
    io::Write,
    net::{IpAddr, Shutdown, TcpListener, TcpStream, ToSocketAddrs},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc::{self, Receiver, SyncSender},
    },
    thread,
    time::{Duration, Instant},
};

#[derive(Debug, Clone)]
pub enum Command {
    Text(String),
    File(PathBuf),
    Files(Vec<PathBuf>),
    Pause(String),
    Cancel(String),
    Resume(String),
    AvatarChanged,
    Close,
}
#[derive(Debug, Clone, Copy)]
pub enum Consent {
    Once,
    Remember,
    Reject,
}
#[derive(Debug)]
pub enum Event {
    Consent {
        peer: Peer,
        safety: String,
        reply: SyncSender<Consent>,
    },
    FileOffer {
        peer: String,
        info: Info,
        reply: SyncSender<bool>,
    },
    FileOfferClosed {
        peer: String,
        id: String,
    },
    Connected(Peer, SyncSender<Command>, bool),
    Ready(String),
    Message(Message),
    Saved {
        peer: String,
        id: String,
    },
    File(Saved),
    Avatar {
        peer: String,
        profile: Option<crate::avatar::Profile>,
        failure: Option<u8>,
    },
    Disconnected(String, String),
    Error(String),
}
pub struct Runtime {
    pub events: Receiver<Event>,
    pub port: u16,
    pub stopped: Arc<AtomicBool>,
    store: Arc<Mutex<Profile>>,
    directory: PathBuf,
    tx: SyncSender<Event>,
    active: Arc<AtomicUsize>,
    leased: Arc<AtomicBool>,
    sockets: Arc<Mutex<HashMap<uuid::Uuid, TcpStream>>>,
}
impl Runtime {
    pub fn client(store: Arc<Mutex<Profile>>, directory: PathBuf) -> Self {
        let (tx, events) = mpsc::sync_channel(256);
        Self {
            events,
            port: 0,
            stopped: Arc::new(AtomicBool::new(false)),
            store,
            directory,
            tx,
            active: Arc::new(AtomicUsize::new(0)),
            leased: Arc::new(AtomicBool::new(false)),
            sockets: Arc::new(Mutex::new(HashMap::new())),
        }
    }
    pub fn start(store: Arc<Mutex<Profile>>, bind: &str, directory: PathBuf) -> Result<Self> {
        let listener = TcpListener::bind(bind)?;
        let port = listener.local_addr()?.port();
        listener.set_nonblocking(true)?;
        let (tx, events) = mpsc::sync_channel(256);
        let stopped = Arc::new(AtomicBool::new(false));
        let active = Arc::new(AtomicUsize::new(0));
        let leased = Arc::new(AtomicBool::new(false));
        let sockets = Arc::new(Mutex::new(HashMap::new()));
        let runtime = Self {
            events,
            port,
            stopped: stopped.clone(),
            store: store.clone(),
            directory: directory.clone(),
            tx: tx.clone(),
            active: active.clone(),
            leased: leased.clone(),
            sockets: sockets.clone(),
        };
        thread::spawn(move || {
            while !stopped.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((s, _)) => spawn(
                        s,
                        false,
                        None,
                        store.clone(),
                        directory.clone(),
                        tx.clone(),
                        stopped.clone(),
                        active.clone(),
                        leased.clone(),
                        sockets.clone(),
                    ),
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(200))
                    }
                    Err(e) => {
                        let _ = tx.send(Event::Error(format!("listener: {e}")));
                        break;
                    }
                }
            }
        });
        Ok(runtime)
    }
    pub fn connect(&self, endpoint: &str, expected: Option<String>) -> Result<()> {
        let addresses: Vec<_> = endpoint
            .to_socket_addrs()?
            .filter(|a| local(a.ip()))
            .collect();
        ensure!(
            !addresses.is_empty(),
            "endpoint must resolve to a LAN, link-local, or loopback address with a port"
        );
        let (tx, store, directory, stop, active, leased, sockets) = (
            self.tx.clone(),
            self.store.clone(),
            self.directory.clone(),
            self.stopped.clone(),
            self.active.clone(),
            self.leased.clone(),
            self.sockets.clone(),
        );
        thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(10);
            let mut last = "connection timed out".to_string();
            for a in addresses {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    break;
                }
                match TcpStream::connect_timeout(&a, remaining.min(Duration::from_secs(3))) {
                    Ok(s) => {
                        spawn(
                            s, true, expected, store, directory, tx, stop, active, leased, sockets,
                        );
                        return;
                    }
                    Err(e) => last = e.to_string(),
                }
            }
            let _ = tx.send(Event::Error(last));
        });
        Ok(())
    }
}
impl Drop for Runtime {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Relaxed);
        for stream in self.sockets.lock().unwrap().values() {
            let _ = stream.shutdown(Shutdown::Both);
        }
        let deadline = Instant::now() + Duration::from_secs(15);
        while self.active.load(Ordering::Relaxed) > 0 && Instant::now() < deadline {
            while self.events.try_recv().is_ok() {}
            thread::sleep(Duration::from_millis(10));
        }
    }
}
fn local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(a) => a.is_private() || a.is_loopback() || a.is_link_local(),
        IpAddr::V6(a) => a.is_loopback() || a.is_unique_local() || a.is_unicast_link_local(),
    }
}
#[allow(clippy::too_many_arguments)]
fn spawn(
    stream: TcpStream,
    initiator: bool,
    expected: Option<String>,
    store: Arc<Mutex<Profile>>,
    directory: PathBuf,
    events: SyncSender<Event>,
    stop: Arc<AtomicBool>,
    active: Arc<AtomicUsize>,
    leased: Arc<AtomicBool>,
    sockets: Arc<Mutex<HashMap<uuid::Uuid, TcpStream>>>,
) {
    if active.fetch_add(1, Ordering::Relaxed) >= 8 {
        active.fetch_sub(1, Ordering::Relaxed);
        return;
    }
    let token = uuid::Uuid::new_v4();
    if let Ok(clone) = stream.try_clone() {
        sockets.lock().unwrap().insert(token, clone);
    }
    thread::spawn(move || {
        let identity = store.lock().unwrap().identity.clone();
        match crypto::establish(stream, initiator, &identity) {
            Ok(mut established) => {
                let id = established.peer.id.clone();
                if leased
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
                    .is_err()
                {
                    let _ = events.send(Event::Error(
                        "single active session is busy; disconnect it before switching".into(),
                    ));
                    let _ = established.stream.shutdown(Shutdown::Both);
                    sockets.lock().unwrap().remove(&token);
                    active.fetch_sub(1, Ordering::Relaxed);
                    return;
                }
                if let Err(e) = session(
                    &mut established,
                    initiator,
                    expected,
                    store,
                    directory,
                    &events,
                    stop,
                ) {
                    let _ = events.send(Event::Error(format!("session {id}: {e:#}")));
                }
                leased.store(false, Ordering::Release);
                let _ = established.stream.shutdown(Shutdown::Both);
            }
            Err(e) => {
                let _ = events.send(Event::Error(format!("NIM4 handshake rejected: {e:#}")));
            }
        }
        sockets.lock().unwrap().remove(&token);
        active.fetch_sub(1, Ordering::Relaxed);
    });
}
fn session(
    est: &mut Established,
    initiator: bool,
    expected: Option<String>,
    store: Arc<Mutex<Profile>>,
    directory: PathBuf,
    events: &SyncSender<Event>,
    stop: Arc<AtomicBool>,
) -> Result<()> {
    if let Some(id) = expected {
        ensure!(
            id == est.peer.id,
            "endpoint returned a different device identity"
        );
    }
    let known = store.lock().unwrap().peer(&est.peer.id)?;
    if let Some(p) = &known {
        ensure!(
            p.root == est.peer.root,
            "IDENTITY CHANGED: compare safety codes; revoke/forget explicitly before accepting the replacement"
        );
        est.peer.verified = p.verified;
        est.peer.remembered = p.remembered;
    }
    let addr = est.stream.peer_addr()?;
    est.peer.endpoint = addr.to_string();
    if !est.peer.remembered {
        let public = store.lock().unwrap().identity.public.clone();
        let safety = profile::safety(&public, &STANDARD.decode(&est.peer.root)?);
        let (tx, rx) = mpsc::sync_channel(1);
        events.send(Event::Consent {
            peer: est.peer.clone(),
            safety,
            reply: tx,
        })?;
        match rx
            .recv_timeout(Duration::from_secs(50))
            .context("connection consent timed out")?
        {
            Consent::Reject => anyhow::bail!("connection rejected"),
            Consent::Remember => est.peer.remembered = true,
            Consent::Once => {}
        }
    }
    // An inbound TCP source port is ephemeral and cannot be used for reconnect.
    if !initiator {
        est.peer.endpoint = known
            .as_ref()
            .map(|p| p.endpoint.clone())
            .unwrap_or_default();
    }
    store.lock().unwrap().save_peer(&est.peer)?;
    let (commands_tx, commands) = mpsc::sync_channel(64);
    events.send(Event::Connected(est.peer.clone(), commands_tx, initiator))?;
    let mut stream = est.stream.try_clone()?;
    let send = &mut est.send;
    let mut writer = |f: Frame| -> Result<()> {
        stream.write_all(&send.encrypt(3, &f.encode()?)?)?;
        Ok(())
    };
    writer(Frame::control(READY))?;
    let (read_tx, read_rx) = mpsc::sync_channel(64);
    let mut read_stream = est.stream.try_clone()?;
    let mut cipher = std::mem::replace(&mut est.receive, crypto::Cipher::new([0; 32]));
    thread::spawn(move || {
        loop {
            let result = (|| {
                let (h, b) = crypto::read_envelope(&mut read_stream, 3, MAX_PLAIN + 16)?;
                ensure!(b.len() >= 16, "short encrypted record");
                Frame::decode(&cipher.decrypt(&h, &b)?)
            })();
            let failed = result.is_err();
            if read_tx.send(result).is_err() || failed {
                break;
            }
        }
    });
    let mut files = Engine::new(
        store.clone(),
        est.peer.id.clone(),
        est.peer.root.clone(),
        est.generation.clone(),
        directory,
    );
    let mut avatars = if est.peer.capabilities & 4 != 0 {
        match crate::avatar::Sync::new(&store.lock().unwrap(), &est.peer.root) {
            Ok(sync) => Some(sync),
            Err(_) => {
                events.send(Event::Avatar {
                    peer: est.peer.id.clone(),
                    profile: None,
                    failure: Some(6),
                })?;
                None
            }
        }
    } else {
        None
    };
    let config = crate::config::Config::load(&store.lock().unwrap())?;
    let mut file_offer = None::<(Packet, Receiver<bool>, Instant)>;
    let mut ready = false;
    let start = Instant::now();
    let mut last_rx = Instant::now();
    let mut last_ping = Instant::now();
    let mut pending = HashMap::<String, ()>::new();
    let result = (|| -> Result<()> {
        while !stop.load(Ordering::Relaxed) {
            match read_rx.recv_timeout(if ready && files.can_pump() {
                Duration::ZERO
            } else {
                Duration::from_millis(50)
            }) {
                Ok(result) => {
                    let f = result?;
                    last_rx = Instant::now();
                    match f.kind {
                        READY => {
                            ensure!(!ready, "duplicate ready");
                            ready = true;
                            if let Some(avatars) = &mut avatars {
                                avatars.local_changed(&store.lock().unwrap(), &mut writer)?;
                            }
                            events.send(Event::Ready(est.peer.id.clone()))?;
                        }
                        BYE => break,
                        PING => writer(Frame::control(PONG))?,
                        PONG => {}
                        TEXT => {
                            ensure!(ready, "text before mutual consent");
                            let m = Message {
                                id: f.id,
                                peer: est.peer.id.clone(),
                                outgoing: false,
                                body: f.body,
                                time: f.time,
                                state: "saved".into(),
                            };
                            let p = store.lock().unwrap();
                            if let Some(old) = p.message(&m.peer, &m.id)? {
                                ensure!(
                                    !old.outgoing && old.body == m.body && old.time == m.time,
                                    "message ID reused with different content"
                                );
                            } else {
                                p.save_message(&m)?;
                                events.send(Event::Message(m.clone()))?;
                            }
                            drop(p);
                            let mut ack = Frame::control(ACK);
                            ack.id = m.id;
                            writer(ack)?;
                        }
                        ACK => {
                            ensure!(
                                ready && pending.remove(&f.id).is_some(),
                                "unexpected saved receipt"
                            );
                            store.lock().unwrap().receipt(&est.peer.id, &f.id)?;
                            events.send(Event::Saved {
                                peer: est.peer.id.clone(),
                                id: f.id,
                            })?;
                        }
                        FILE => {
                            ensure!(ready, "file before mutual consent");
                            let packet = Packet::decode(&f.data)?;
                            ensure!(
                                packet.connection == est.generation,
                                "stale file connection generation"
                            );
                            let awaiting = file_offer.as_ref().is_some_and(|(offer, _, _)| {
                                packet.sender
                                    && packet.id == offer.id
                                    && packet.source == offer.source
                            });
                            if awaiting && matches!(packet.kind, CANCEL | PAUSE) {
                                file_offer.take();
                                events.send(Event::FileOfferClosed {
                                    peer: est.peer.id.clone(),
                                    id: packet.id,
                                })?;
                            } else if awaiting && packet.kind == STATUS {
                            } else if packet.kind == OFFER
                                && !(est.peer.remembered && config.auto_receive_trusted)
                            {
                                if file_offer.is_some() {
                                    let mut r = packet.reply(CANCEL);
                                    r.reason = "busy".into();
                                    writer(r.frame()?)?;
                                } else {
                                    let (tx, rx) = mpsc::sync_channel(1);
                                    events.send(Event::FileOffer {
                                        peer: est.peer.id.clone(),
                                        info: packet.info.clone().unwrap(),
                                        reply: tx,
                                    })?;
                                    file_offer = Some((packet, rx, Instant::now()));
                                }
                            } else {
                                files.handle(packet, &mut writer)?;
                            }
                        }
                        crate::avatar::FAMILY => {
                            ensure!(
                                ready && est.peer.capabilities & 4 != 0,
                                "avatar before mutual consent/capability"
                            );
                            let packet = crate::avatar::Packet::decode(&f.data)?;
                            if let Some(avatars) = &mut avatars {
                                avatars.handle(packet, &store.lock().unwrap(), &mut writer)?;
                            }
                        }
                        _ => anyhow::bail!("unsupported frame"),
                    }
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => anyhow::bail!("reader stopped"),
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            for _ in 0..8 {
                let Ok(command) = commands.try_recv() else {
                    break;
                };
                if matches!(command, Command::Close) {
                    writer(Frame::control(BYE))?;
                    return Ok(());
                }
                let outcome = (|| -> Result<()> {
                    ensure!(ready, "waiting for peer consent");
                    match command {
                        Command::Text(body) => {
                            text_valid(&body)?;
                            let f = Frame {
                                kind: TEXT,
                                time: profile::now(),
                                id: uuid::Uuid::new_v4().to_string(),
                                body,
                                data: vec![],
                            };
                            ensure!(pending.len() < 128, "too many unconfirmed messages");
                            let m = Message {
                                id: f.id.clone(),
                                peer: est.peer.id.clone(),
                                outgoing: true,
                                body: f.body.clone(),
                                time: f.time,
                                state: "pending".into(),
                            };
                            store.lock().unwrap().save_message(&m)?;
                            events.send(Event::Message(m))?;
                            pending.insert(f.id.clone(), ());
                            writer(f)?;
                        }
                        Command::File(path) => {
                            files.offer(&path, &mut writer)?;
                        }
                        Command::Files(paths) => {
                            files.offer_batch(&paths, &mut writer)?;
                        }
                        Command::Pause(id) => files.pause(&id, false, &mut writer)?,
                        Command::Cancel(id) => files.pause(&id, true, &mut writer)?,
                        Command::Resume(id) => files.resume(&id, &mut writer)?,
                        Command::AvatarChanged => {
                            if let Some(avatars) = &mut avatars {
                                avatars.local_changed(&store.lock().unwrap(), &mut writer)?;
                            }
                        }
                        Command::Close => {}
                    }
                    Ok(())
                })();
                if let Err(e) = outcome {
                    events.send(Event::Error(format!("command failed: {e:#}")))?;
                }
            }
            if let Some((packet, reply, since)) = file_offer.take() {
                match reply.try_recv() {
                    Ok(true) => files.handle(packet, &mut writer)?,
                    Ok(false) | Err(mpsc::TryRecvError::Disconnected) => {
                        let mut r = packet.reply(CANCEL);
                        r.reason = "rejected".into();
                        writer(r.frame()?)?;
                    }
                    Err(mpsc::TryRecvError::Empty) if since.elapsed() > Duration::from_secs(50) => {
                        let mut r = packet.reply(CANCEL);
                        r.reason = "rejected".into();
                        writer(r.frame()?)?;
                    }
                    Err(mpsc::TryRecvError::Empty) => file_offer = Some((packet, reply, since)),
                }
            }
            if ready {
                files.pump(&mut writer)?;
                if let Some(avatars) = &mut avatars {
                    avatars.expire(&mut writer)?;
                    for profile in avatars.changed.drain(..) {
                        events.send(Event::Avatar {
                            peer: est.peer.id.clone(),
                            profile: Some(profile),
                            failure: None,
                        })?;
                    }
                    for code in avatars.failures.drain(..) {
                        events.send(Event::Avatar {
                            peer: est.peer.id.clone(),
                            profile: None,
                            failure: Some(code),
                        })?;
                    }
                }
            }
            for notice in files.notices.drain(..) {
                events.send(Event::File(notice))?;
            }
            if !ready {
                ensure!(
                    start.elapsed() < Duration::from_secs(60),
                    "peer consent timed out"
                );
            }
            ensure!(
                last_rx.elapsed() < Duration::from_secs(45),
                "peer idle timeout"
            );
            if last_rx.elapsed() > Duration::from_secs(10)
                && last_ping.elapsed() > Duration::from_secs(5)
            {
                writer(Frame::control(PING))?;
                last_ping = Instant::now();
            }
        }
        Ok(())
    })();
    files.disconnect();
    for id in pending.keys() {
        let p = store.lock().unwrap();
        if let Some(mut m) = p.message(&est.peer.id, id)? {
            m.state = "unconfirmed".into();
            p.db.execute(
                "UPDATE messages SET value=? WHERE peer=? AND id=?",
                rusqlite::params![serde_json::to_string(&m)?, m.peer, m.id],
            )?;
        }
    }
    events.send(Event::Disconnected(
        est.peer.id.clone(),
        result
            .as_ref()
            .err()
            .map(|e| format!("{e:#}"))
            .unwrap_or_else(|| "closed".into()),
    ))?;
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pair() -> (
        tempfile::TempDir,
        Arc<Mutex<Profile>>,
        Arc<Mutex<Profile>>,
        Runtime,
        Runtime,
    ) {
        let dir = tempfile::tempdir().unwrap();
        let a = Arc::new(Mutex::new(
            Profile::open(dir.path().join("a"), None).unwrap(),
        ));
        let b = Arc::new(Mutex::new(
            Profile::open(dir.path().join("b"), None).unwrap(),
        ));
        let ra = Runtime::start(a.clone(), "127.0.0.1:0", dir.path().join("ra")).unwrap();
        let rb = Runtime::start(b.clone(), "127.0.0.1:0", dir.path().join("rb")).unwrap();
        (dir, a, b, ra, rb)
    }
    #[test]
    #[ignore = "requires local sockets; run test-interop.sh"]
    fn consent_text_receipts_and_changed_pin() {
        let (_dir, a, b, ra, rb) = pair();
        ra.connect(&format!("127.0.0.1:{}", rb.port), None).unwrap();
        let deadline = Instant::now() + Duration::from_secs(8);
        let mut approvals = 0;
        let mut sender = None;
        let mut ready = 0;
        while Instant::now() < deadline && ready < 2 {
            for r in [&ra, &rb] {
                while let Ok(e) = r.events.try_recv() {
                    match e {
                        Event::Consent { reply, .. } => {
                            approvals += 1;
                            reply.send(Consent::Once).unwrap();
                        }
                        Event::Connected(_, tx, true) => sender = Some(tx),
                        Event::Ready(_) => ready += 1,
                        Event::Error(e) => panic!("{e}"),
                        _ => {}
                    }
                }
            }
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(approvals, 2);
        assert_eq!(ready, 2);
        sender
            .as_ref()
            .unwrap()
            .send(Command::Text("中文👩‍💻\n".repeat(20)))
            .unwrap();
        let mut saved = false;
        while Instant::now() < deadline {
            if let Ok(e) = ra.events.recv_timeout(Duration::from_millis(50)) {
                match e {
                    Event::Saved { .. } => {
                        saved = true;
                        break;
                    }
                    Event::Error(e) => panic!("{e}"),
                    _ => {}
                }
            }
        }
        assert!(saved);
        assert_eq!(b.lock().unwrap().history(None).unwrap()[0].state, "saved");
        assert_eq!(a.lock().unwrap().history(None).unwrap()[0].state, "saved");
        sender.unwrap().send(Command::Close).unwrap();
        let id = b.lock().unwrap().identity.id.clone();
        let mut p = a.lock().unwrap().peer(&id).unwrap().unwrap();
        p.root = STANDARD.encode(a.lock().unwrap().identity.public.clone());
        a.lock().unwrap().save_peer(&p).unwrap();
        thread::sleep(Duration::from_millis(100));
        ra.connect(&format!("127.0.0.1:{}", rb.port), Some(id))
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut refused = false;
        while Instant::now() < deadline {
            if let Ok(Event::Error(e)) = ra.events.recv_timeout(Duration::from_millis(50))
                && e.contains("IDENTITY CHANGED")
            {
                refused = true;
                break;
            }
        }
        assert!(refused, "changed root was not rejected");
    }
}

#[cfg(test)]
mod stress_tests {
    use super::*;
    use sha2::{Digest, Sha256};
    use std::io::Read;
    #[test]
    #[ignore = "explicit large-file stress; NEARBYIM_STRESS_BYTES selects 1 GiB/10 GiB"]
    fn large_file_stress() {
        let size = std::env::var("NEARBYIM_STRESS_BYTES")
            .ok()
            .map(|s| s.parse::<u64>().unwrap())
            .unwrap_or(1024 * 1024 * 1024);
        assert!(size <= MAX_FILE);
        let dir = tempfile::tempdir().unwrap();
        let a = Arc::new(Mutex::new(
            Profile::open(dir.path().join("a"), None).unwrap(),
        ));
        let b = Arc::new(Mutex::new(
            Profile::open(dir.path().join("b"), None).unwrap(),
        ));
        for p in [&a, &b] {
            let p = p.lock().unwrap();
            let c = crate::config::Config {
                auto_receive_trusted: true,
                ..Default::default()
            };
            c.save(&p).unwrap();
        }
        let ra = Runtime::client(a.clone(), dir.path().join("ra"));
        let rb = Runtime::start(b.clone(), "127.0.0.1:0", dir.path().join("received")).unwrap();
        ra.connect(&format!("127.0.0.1:{}", rb.port), None).unwrap();
        let deadline = Instant::now() + Duration::from_secs(600);
        let mut sender = None;
        let mut ready = 0;
        while Instant::now() < deadline && ready < 2 {
            for r in [&ra, &rb] {
                while let Ok(e) = r.events.try_recv() {
                    match e {
                        Event::Consent { reply, .. } => reply.send(Consent::Remember).unwrap(),
                        Event::Connected(_, tx, true) => sender = Some(tx),
                        Event::Ready(_) => ready += 1,
                        Event::Error(e) => panic!("{e}"),
                        _ => {}
                    }
                }
            }
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(ready, 2);
        let source = dir.path().join("source.bin");
        std::fs::File::create(&source)
            .unwrap()
            .set_len(size)
            .unwrap();
        sender.unwrap().send(Command::File(source)).unwrap();
        let start = Instant::now();
        let mut sent = false;
        let mut received = None;
        let mut reported = 0;
        while Instant::now() < deadline && !(sent && received.is_some()) {
            for r in [&ra, &rb] {
                while let Ok(e) = r.events.try_recv() {
                    match e {
                        Event::File(s) => {
                            if s.offset >= reported + 256 * BLOCK {
                                reported = s.offset;
                                println!("stress: {} / {} bytes", s.offset, size);
                            }
                            if s.state == "saved" {
                                sent = true;
                            }
                            if s.state == "received" {
                                received = Some(s);
                            }
                        }
                        Event::Error(e) => panic!("{e}"),
                        Event::Disconnected(_, why) => panic!("premature disconnect: {why}"),
                        _ => {}
                    }
                }
            }
            thread::sleep(Duration::from_millis(1));
        }
        assert!(
            sent && received.is_some(),
            "large transfer did not complete"
        );
        let s = received.unwrap();
        let mut file = std::fs::File::open(s.destination).unwrap();
        assert_eq!(file.metadata().unwrap().len(), size);
        let mut hash = Sha256::new();
        let mut n = 0;
        let mut buffer = [0; CHUNK];
        loop {
            let count = file.read(&mut buffer).unwrap();
            if count == 0 {
                break;
            }
            assert!(buffer[..count].iter().all(|b| *b == 0));
            hash.update(&buffer[..count]);
            n += count as u64;
        }
        assert_eq!(n, size);
        assert_eq!(hex::encode(hash.finalize()), s.hash);
        println!(
            "PASS {}-byte NIM4 streaming file + independent readback/hash; elapsed {:.3}s; synthetic loopback, not device throughput",
            size,
            start.elapsed().as_secs_f64()
        );
    }
}

#[cfg(test)]
mod offer_tests {
    use super::*;
    #[test]
    #[ignore = "requires local sockets; run test-interop.sh"]
    fn cancel_before_receive_permission_keeps_chat_usable() {
        let d = tempfile::tempdir().unwrap();
        let a = Arc::new(Mutex::new(Profile::open(d.path().join("a"), None).unwrap()));
        let b = Arc::new(Mutex::new(Profile::open(d.path().join("b"), None).unwrap()));
        let ra = Runtime::client(a, d.path().join("ra"));
        let rb = Runtime::start(b, "127.0.0.1:0", d.path().join("rb")).unwrap();
        ra.connect(&format!("127.0.0.1:{}", rb.port), None).unwrap();
        let until = Instant::now() + Duration::from_secs(15);
        let mut ready = 0;
        let mut sender = None;
        while Instant::now() < until && ready < 2 {
            for r in [&ra, &rb] {
                while let Ok(e) = r.events.try_recv() {
                    match e {
                        Event::Consent { reply, .. } => reply.send(Consent::Once).unwrap(),
                        Event::Connected(_, tx, true) => sender = Some(tx),
                        Event::Ready(_) => ready += 1,
                        Event::Error(e) => panic!("{e}"),
                        _ => {}
                    }
                }
            }
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(ready, 2);
        let source = d.path().join("source");
        std::fs::write(&source, b"data").unwrap();
        let sender = sender.unwrap();
        sender.send(Command::File(source)).unwrap();
        let mut pending = None;
        while Instant::now() < until {
            if let Ok(Event::FileOffer { info, reply, .. }) =
                rb.events.recv_timeout(Duration::from_millis(20))
            {
                pending = Some((info.id, reply));
                break;
            }
        }
        let (id, _permission) = pending.expect("missing receive permission prompt");
        sender.send(Command::Cancel(id.clone())).unwrap();
        let mut closed = false;
        while Instant::now() < until {
            if let Ok(e) = rb.events.recv_timeout(Duration::from_millis(20)) {
                match e {
                    Event::FileOfferClosed { id: actual, .. } => {
                        assert_eq!(actual, id);
                        closed = true;
                        break;
                    }
                    Event::Error(e) | Event::Disconnected(_, e) => panic!("{e}"),
                    _ => {}
                }
            }
        }
        assert!(closed);
        sender
            .send(Command::Text("chat after canceled offer".into()))
            .unwrap();
        let mut saved = false;
        while Instant::now() < until {
            if let Ok(e) = ra.events.recv_timeout(Duration::from_millis(20)) {
                match e {
                    Event::Saved { .. } => {
                        saved = true;
                        break;
                    }
                    Event::Error(e) | Event::Disconnected(_, e) => panic!("{e}"),
                    _ => {}
                }
            }
        }
        assert!(saved, "file cancellation broke the chat");
    }
}

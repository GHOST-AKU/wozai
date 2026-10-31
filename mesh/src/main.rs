use anyhow::{Context, Result, bail};
use nearby_mesh::{
    core::{Core, Limits},
    manager::Manager,
    protocol::{self, Identity},
    store::Store,
    transport::{Link, MAX_CONNECTIONS, MAX_TX_QUEUE, Wire},
};
use serde_json::{Value, json};
use std::{
    collections::BTreeSet,
    io::{self, BufRead, Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    path::PathBuf,
    sync::mpsc,
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn emit(value: Value) {
    println!("{value}");
}
fn auth_bytes(first: &str, second: &str, signer: &str, receiver: &str) -> protocol::Check<Vec<u8>> {
    let mut bytes = b"NearbyMesh/neighbor/v1\0".to_vec();
    bytes.extend(protocol::decode::<16>(first)?);
    bytes.extend(protocol::decode::<16>(second)?);
    bytes.extend(protocol::decode::<32>(signer)?);
    bytes.extend(protocol::decode::<32>(receiver)?);
    Ok(bytes)
}
fn new_link(stream: TcpStream, outbound: bool, identity: &Identity) -> Result<Link> {
    let mut link = Link::new(stream, outbound, protocol::nonce())?;
    link.queue(
        &Wire::Hello {
            version: 1,
            key: identity.key(),
            challenge: link.challenge.clone(),
        },
        None,
        u64::MAX,
        false,
    )?;
    Ok(link)
}
#[derive(Default)]
struct Counters {
    received: u64,
    duplicates: u64,
    rejected: u64,
    handed: u64,
    bytes_rx: u64,
    bytes_tx: u64,
    peak_connections: usize,
    peak_pending: usize,
    peak_tx: usize,
}
fn diagnostic(
    core: &Core,
    links: &[Link],
    manager: &Manager,
    counters: &Counters,
    event: &str,
) -> Value {
    json!({"event":event,"relay":core.relay,"pending":core.pending_len(),"seen":core.seen.len(),"connections":links.len(),"candidates":manager.len(),
        "received":counters.received,"duplicates":counters.duplicates,"rejected":counters.rejected,"handed_to_neighbor_unconfirmed":counters.handed,
        "bytes_rx":counters.bytes_rx+links.iter().map(|l|l.bytes_rx).sum::<u64>(),"bytes_tx":counters.bytes_tx+links.iter().map(|l|l.bytes_tx).sum::<u64>(),
        "peak_connections":counters.peak_connections,"peak_pending":counters.peak_pending,"peak_tx_frames_per_link":counters.peak_tx,
        "limits":{"connections":8,"candidates":16,"seen":1024,"pending":64,"tx_per_link":8,"frame_bytes":32768},
        "delivery":"best_effort_no_global_ack","public_plaintext":true,"hardware_validation":"pending"})
}
fn command_rx() -> mpsc::Receiver<Value> {
    let (tx, rx) = mpsc::sync_channel(16);
    thread::spawn(move || {
        let stdin = io::stdin();
        let mut input = stdin.lock();
        loop {
            let mut bytes = Vec::new();
            match Read::by_ref(&mut input)
                .take(16385)
                .read_until(b'\n', &mut bytes)
            {
                Ok(0) => {
                    let _ = tx.send(json!({"cmd":"quit"}));
                    break;
                }
                Ok(_) if bytes.len() > 16384 || !bytes.ends_with(b"\n") => {
                    let _ = tx.send(json!({"cmd":"control_error"}));
                    break;
                }
                Ok(_) => {
                    let value = serde_json::from_slice(&bytes)
                        .unwrap_or_else(|_| json!({"cmd":"control_error"}));
                    if tx.send(value).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    rx
}
fn process_wire(
    wire: Wire,
    link: &mut Link,
    identity: &Identity,
    core: &mut Core,
    store: &Store,
    counters: &mut Counters,
) -> Result<()> {
    let key = identity.key();
    match wire {
        Wire::Hello {
            version,
            key: peer,
            challenge,
        } => {
            if version != 1 || link.hello.is_some() || peer == key {
                bail!("invalid_hello");
            }
            protocol::decode::<32>(&peer).map_err(anyhow::Error::msg)?;
            protocol::decode::<16>(&challenge).map_err(anyhow::Error::msg)?;
            let bytes =
                auth_bytes(&link.challenge, &challenge, &key, &peer).map_err(anyhow::Error::msg)?;
            link.queue(
                &Wire::Auth {
                    signature: identity.sign(&bytes),
                },
                None,
                u64::MAX,
                false,
            )?;
            link.hello = Some((peer, challenge));
        }
        Wire::Auth { signature } => {
            if link.authenticated {
                bail!("duplicate_auth");
            }
            let (peer, challenge) = link.hello.as_ref().context("auth_before_hello")?;
            protocol::verify(
                peer,
                &auth_bytes(challenge, &link.challenge, peer, &key).map_err(anyhow::Error::msg)?,
                &signature,
            )
            .map_err(anyhow::Error::msg)?;
            link.peer = Some(peer.clone());
            link.authenticated = true;
            emit(
                json!({"event":"link_authenticated","peer":peer,"address":link.address.to_string(),"public_identity_only":true}),
            );
        }
        Wire::Data { packet } => {
            let peer = link.peer.as_ref().context("data_before_auth")?;
            let id = packet.message.id.clone();
            let body = packet.message.body.clone();
            let path = packet.path();
            match core.receive(packet, peer, now()) {
                Ok(true) => {
                    store.checkpoint(core)?;
                    counters.received += 1;
                    emit(json!({"event":"message","id":id,"body":body,"path":path}));
                }
                Ok(false) => {
                    counters.duplicates += 1;
                    emit(json!({"event":"duplicate","id":id}));
                }
                Err(reason) => {
                    counters.rejected += 1;
                    emit(json!({"event":"rejected","reason":reason}));
                }
            }
        }
        Wire::Ping => {
            if !link.authenticated {
                bail!("ping_before_auth");
            }
        }
    }
    Ok(())
}
fn run(
    state: PathBuf,
    listen: SocketAddr,
    peers: Vec<SocketAddr>,
    relay: bool,
    discover: bool,
) -> Result<()> {
    let store = Store::open(&state)?;
    let identity = store.identity()?;
    let key = identity.key();
    let snapshot = store.load()?;
    if snapshot.saved_at > now().saturating_add(30) {
        bail!("clock moved backwards since saved state");
    }
    let mut core = Core::new(key.clone(), relay, Limits::default());
    core.restore_seen(snapshot.seen, now().max(snapshot.saved_at))
        .map_err(anyhow::Error::msg)?;
    let listener = TcpListener::bind(listen)?;
    listener.set_nonblocking(true)?;
    let mut manager = Manager::default();
    for peer in peers {
        if !manager.add(peer) {
            bail!("candidate_limit");
        }
    }
    let mut discovery = if discover {
        Some(nearby_mesh::discovery::Discovery::start(
            listener.local_addr()?.port(),
        )?)
    } else {
        None
    };
    let mut discovery_restart = Instant::now();
    let mut links: Vec<Link> = Vec::new();
    let commands = command_rx();
    let mut counters = Counters::default();
    let mut last_save = Instant::now();
    emit(
        json!({"event":"ready","key":key,"address":listener.local_addr()?.to_string(),"pid":std::process::id(),"relay":relay,"protocol":"nearby-mesh-lab-v1","public_plaintext":true}),
    );
    if snapshot.pending_lost > 0 {
        emit(
            json!({"event":"failed_restart","count":snapshot.pending_lost,"reason":"in_memory_queues_lost"}),
        );
    }
    loop {
        let previous_peers: BTreeSet<String> = links
            .iter()
            .filter(|l| l.auth_sent)
            .filter_map(|l| l.peer.clone())
            .collect();
        let expired = core.tick(now());
        if !expired.is_empty() {
            store.checkpoint(&core)?;
            for id in expired {
                emit(json!({"event":"expired","id":id,"state":"expired_unconfirmed"}));
            }
        }
        for _ in 0..8 {
            let Ok(command) = commands.try_recv() else {
                break;
            };
            match command.get("cmd").and_then(Value::as_str).unwrap_or("") {
                "quit" => {
                    store.save(&core.seen, 0, core.clock_floor())?;
                    return Ok(());
                }
                "send" => {
                    let text = command.get("text").and_then(Value::as_str).unwrap_or("");
                    let ttl = command.get("ttl").and_then(Value::as_u64).unwrap_or(600);
                    let hops = command.get("hops").and_then(Value::as_u64).unwrap_or(3);
                    let result = if hops > 255 {
                        Err("hop_bound")
                    } else {
                        core.originate(&identity, text, now(), ttl, hops as u8)
                    };
                    match result {
                        Ok(id) => {
                            store.checkpoint(&core)?;
                            emit(
                                json!({"event":"queued","id":id,"state":"pending","request":command.get("request")}),
                            );
                        }
                        Err(reason) => emit(json!({"event":"error","reason":reason})),
                    }
                }
                "relay" => {
                    if let Some(enabled) = command.get("enabled").and_then(Value::as_bool) {
                        core.relay = enabled;
                        emit(
                            json!({"event":"relay","enabled":enabled,"authorization":"explicit_local_command"}),
                        );
                    } else {
                        emit(json!({"event":"error","reason":"relay_boolean_required"}));
                    }
                }
                "neighbors" => emit(
                    json!({"event":"neighbors","peers":links.iter().filter(|l|l.auth_sent).filter_map(|l|l.peer.clone()).collect::<Vec<_>>()}),
                ),
                "status" | "diagnostics" => emit(diagnostic(
                    &core,
                    &links,
                    &manager,
                    &counters,
                    command["cmd"].as_str().unwrap(),
                )),
                _ => emit(json!({"event":"error","reason":"control_input"})),
            }
        }
        if let Some(worker) = discovery.as_mut() {
            for address in worker.poll() {
                if address != listener.local_addr()? {
                    manager.add_discovered(address);
                }
            }
            if worker.ended() && discovery_restart.elapsed() > Duration::from_secs(65) {
                discovery = Some(nearby_mesh::discovery::Discovery::start(
                    listener.local_addr()?.port(),
                )?);
                discovery_restart = Instant::now();
            }
        }
        let active = links
            .iter()
            .filter(|l| l.outbound)
            .map(|l| l.address)
            .collect::<Vec<_>>();
        let active_keys = links
            .iter()
            .filter_map(|l| l.peer.clone())
            .collect::<Vec<_>>();
        for stream in manager.poll(
            &active,
            &active_keys,
            MAX_CONNECTIONS.saturating_sub(links.len()),
        ) {
            if links.len() < MAX_CONNECTIONS {
                match new_link(stream, true, &identity) {
                    Ok(link) => links.push(link),
                    Err(_) => counters.rejected += 1,
                }
            }
        }
        for _ in 0..8 {
            match listener.accept() {
                Ok((stream, _)) => {
                    if links.len() < MAX_CONNECTIONS {
                        match new_link(stream, false, &identity) {
                            Ok(link) => links.push(link),
                            Err(_) => counters.rejected += 1,
                        }
                    } else {
                        counters.rejected += 1;
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::ConnectionAborted | io::ErrorKind::ConnectionReset
                    ) =>
                {
                    counters.rejected += 1;
                }
                Err(e) => return Err(e.into()),
            }
        }
        let mut dead = BTreeSet::new();
        for (i, link) in links.iter_mut().enumerate() {
            if link.timed_out() {
                dead.insert(i);
                continue;
            }
            match link.read() {
                Ok(frames) => {
                    for wire in frames {
                        if let Err(error) =
                            process_wire(wire, link, &identity, &mut core, &store, &mut counters)
                        {
                            // A disk failure must not turn accepted-but-unpersisted messages into a replay window.
                            if error.downcast_ref::<io::Error>().is_some() {
                                return Err(error);
                            }
                            counters.rejected += 1;
                            dead.insert(i);
                            break;
                        }
                    }
                }
                Err(_) => {
                    counters.rejected += 1;
                    dead.insert(i);
                }
            }
        }
        for link in &links {
            if link.outbound {
                if let Some(peer) = link.peer.as_ref() {
                    manager.authenticated(link.address, peer.clone());
                }
            }
        }
        // Both ends choose the same direction when simultaneous dials create two links.
        for i in 0..links.len() {
            if !links[i].auth_sent {
                continue;
            }
            let Some(peer) = links[i].peer.as_ref() else {
                continue;
            };
            for j in 0..i {
                if !dead.contains(&j) && links[j].auth_sent && links[j].peer.as_ref() == Some(peer)
                {
                    let prefer_outbound = key < *peer;
                    if links[i].outbound == prefer_outbound && links[j].outbound != prefer_outbound
                    {
                        dead.insert(j);
                    } else {
                        dead.insert(i);
                    }
                }
            }
        }
        for (i, link) in links.iter_mut().enumerate() {
            if dead.contains(&i) {
                continue;
            }
            if let Some(peer) = link.peer.clone().filter(|_| link.auth_sent) {
                while link.tx.len() < MAX_TX_QUEUE {
                    let Some(packet) = core.next_for(&identity, &peer, now()) else {
                        break;
                    };
                    let id = packet.message.id.clone();
                    let expires = packet.message.expires;
                    let transit = packet.message.origin != key;
                    let path = packet.path();
                    link.queue(&Wire::Data { packet }, Some(id.clone()), expires, transit)?;
                    emit(json!({"event":"hop_queued","id":id,"path":path,"transport":"tcp"}));
                }
                if link.last_ping.elapsed() > Duration::from_secs(3) && link.tx.len() < MAX_TX_QUEUE
                {
                    link.queue(&Wire::Ping, None, u64::MAX, false)?;
                    link.last_ping = Instant::now();
                }
            }
            counters.peak_tx = counters.peak_tx.max(link.tx.len());
            match link.write(now(), core.relay) {
                Ok(handed) => {
                    for id in handed {
                        counters.handed += 1;
                        emit(
                            json!({"event":"handed","id":id,"state":"handed_to_neighbor_unconfirmed"}),
                        );
                    }
                }
                Err(_) => {
                    dead.insert(i);
                }
            }
        }
        for i in dead.into_iter().rev() {
            let link = links.remove(i);
            counters.bytes_rx += link.bytes_rx;
            counters.bytes_tx += link.bytes_tx;
        }
        let current_peers: BTreeSet<String> = links
            .iter()
            .filter(|l| l.auth_sent)
            .filter_map(|l| l.peer.clone())
            .collect();
        for peer in previous_peers.difference(&current_peers) {
            core.disconnected(peer);
            emit(json!({"event":"neighbor_down","peer":peer}));
        }
        for peer in current_peers.difference(&previous_peers) {
            emit(json!({"event":"neighbor_up","peer":peer,"public_identity_only":true}));
        }
        counters.peak_connections = counters.peak_connections.max(links.len());
        counters.peak_pending = counters.peak_pending.max(core.pending_len());
        if last_save.elapsed() > Duration::from_secs(10) {
            store.checkpoint(&core)?;
            last_save = Instant::now();
        }
        thread::sleep(Duration::from_millis(5));
    }
}
fn main() -> Result<()> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    if args.is_empty() || args[0] == "--help" {
        writeln!(
            io::stdout(),
            "mesh-lab keygen --state DIR\nmesh-lab run --state DIR --listen IP:PORT [--peer IP:PORT] [--relay] [--discover]\nstdin NDJSON: send(text,ttl=600,hops=3), neighbors, status, diagnostics, relay(enabled), quit\nPublic plaintext; signed pseudonyms do not establish NIM4 trust. Relay defaults OFF."
        )?;
        return Ok(());
    }
    if args[0] == "discovery-worker" {
        return nearby_mesh::discovery::worker(
            args.get(1).context("missing discovery port")?.parse()?,
        );
    }
    let mut state = None;
    let mut listen = "127.0.0.1:39770".parse()?;
    let mut peers = Vec::new();
    let mut relay = false;
    let mut discover = false;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--relay" => {
                relay = true;
                i += 1;
                continue;
            }
            "--discover" => {
                discover = true;
                i += 1;
                continue;
            }
            "--state" => state = Some(PathBuf::from(args.get(i + 1).context("missing state")?)),
            "--listen" => listen = args.get(i + 1).context("missing listen address")?.parse()?,
            "--peer" => {
                if peers.len() >= 16 {
                    bail!("candidate_limit");
                }
                peers.push(args.get(i + 1).context("missing peer address")?.parse()?);
            }
            _ => bail!("unknown argument"),
        }
        i += 2;
    }
    let state = state.context("--state is required")?;
    match args[0].as_str() {
        "keygen" => {
            let store = Store::open(&state)?;
            emit(json!({"key":store.identity()?.key()}));
            Ok(())
        }
        "run" => run(state, listen, peers, relay, discover),
        _ => bail!("unknown command"),
    }
}

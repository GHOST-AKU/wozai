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
fn send_budget(command: &Value, field: &str, default: u64) -> protocol::Check<u64> {
    match command.get(field) {
        None => Ok(default),
        Some(value) => value.as_u64().ok_or("budget_type"),
    }
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
enum Ingress {
    None,
    Message {
        id: String,
        body: String,
        path: Vec<String>,
    },
}
fn process_wire(
    wire: Wire,
    link: &mut Link,
    identity: &Identity,
    core: &mut Core,
    counters: &mut Counters,
) -> Result<Ingress> {
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
                    return Ok(Ingress::Message { id, body, path });
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
    Ok(Ingress::None)
}
fn write_link(link: &mut Link, core: &mut Core, wall_time: u64) -> Result<Vec<String>> {
    let time = core.observe_time(wall_time);
    link.write(time, core.relay)
}
fn prune_duplicate_links(links: &[Link], key: &str, dead: &mut BTreeSet<usize>) {
    for i in 0..links.len() {
        if dead.contains(&i) || !links[i].auth_sent {
            continue;
        }
        let Some(peer) = links[i].peer.as_ref() else {
            continue;
        };
        for j in 0..i {
            if !dead.contains(&j) && links[j].auth_sent && links[j].peer.as_ref() == Some(peer) {
                let prefer_outbound = key < peer.as_str();
                if links[i].outbound == prefer_outbound && links[j].outbound != prefer_outbound {
                    dead.insert(j);
                } else {
                    dead.insert(i);
                    break;
                }
            }
        }
    }
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
                    let budgets = send_budget(&command, "ttl", 600)
                        .and_then(|ttl| send_budget(&command, "hops", 3).map(|hops| (ttl, hops)));
                    let result = match budgets {
                        Ok((_, hops)) if hops > 255 => Err("hop_bound"),
                        Ok((ttl, hops)) => core.originate(&identity, text, now(), ttl, hops as u8),
                        Err(reason) => Err(reason),
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
                        match process_wire(wire, link, &identity, &mut core, &mut counters) {
                            Ok(Ingress::Message { id, body, path }) => {
                                // All local durability failures are fatal before display or forwarding.
                                store.checkpoint(&core)?;
                                counters.received += 1;
                                emit(json!({"event":"message","id":id,"body":body,"path":path}));
                            }
                            Ok(Ingress::None) => {}
                            Err(_) => {
                                counters.rejected += 1;
                                dead.insert(i);
                                break;
                            }
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
        prune_duplicate_links(&links, &key, &mut dead);
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
            let written = write_link(link, &mut core, now());
            let cancelled = link.take_cancelled();
            if let Some(peer) = link.peer.as_ref() {
                for id in cancelled {
                    core.cancel_offer(&id, peer);
                }
            }
            match written {
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
            if let Some(peer) = link.peer.as_ref() {
                for id in link.pending_ids() {
                    core.cancel_offer(&id, peer);
                }
            }
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

#[cfg(test)]
mod tests {
    use super::*;
    fn link(outbound: bool) -> (Link, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (stream, _) = listener.accept().unwrap();
        let mut link = Link::new(stream, outbound, protocol::nonce()).unwrap();
        link.authenticated = true;
        link.auth_sent = true;
        link.peer = Some("ff".repeat(32));
        (link, client)
    }
    #[test]
    fn full_tx_queue_observes_fresh_time_and_cannot_revive_after_rollback() {
        let a = Identity::from_seed([1; 32]);
        let b = Identity::from_seed([2; 32]);
        let mut core = Core::new(a.key(), false, Limits::default());
        let id = core.originate(&a, "expires", 1000, 1, 3).unwrap();
        let packet = core.next_for(&a, &b.key(), 1000).unwrap();
        let (mut link, _remote) = link(true);
        for _ in 0..MAX_TX_QUEUE {
            link.queue(
                &Wire::Data {
                    packet: packet.clone(),
                },
                Some(id.clone()),
                1001,
                false,
            )
            .unwrap();
        }
        assert!(write_link(&mut link, &mut core, 1002).unwrap().is_empty());
        for cancelled in link.take_cancelled() {
            core.cancel_offer(&cancelled, &b.key());
        }
        assert_eq!(core.clock_floor(), 1002);
        assert!(core.next_for(&a, &b.key(), 1000).is_none());
    }
    #[test]
    fn malformed_optional_send_budgets_do_not_become_defaults() {
        for value in [
            json!(-1),
            json!(1.5),
            json!(true),
            json!("30"),
            Value::Null,
            json!([]),
        ] {
            let command = json!({"ttl":value,"hops":value});
            assert!(send_budget(&command, "ttl", 600).is_err());
            assert!(send_budget(&command, "hops", 3).is_err());
        }
        assert_eq!(send_budget(&json!({}), "ttl", 600), Ok(600));
        assert_eq!(send_budget(&json!({"ttl":30}), "ttl", 600), Ok(30));
    }
    #[test]
    fn dead_preferred_link_cannot_remove_healthy_neighbor_connection() {
        let (healthy, _one) = link(false);
        let (dead_preferred, _two) = link(true);
        let links = vec![healthy, dead_preferred];
        let mut dead = BTreeSet::from([1]);
        prune_duplicate_links(&links, &"00".repeat(32), &mut dead);
        assert_eq!(dead, BTreeSet::from([1]));
    }
}

use nearby_mesh::embedded::Engine;
use serde_json::{Value, json};
use std::{
    net::TcpStream,
    thread,
    time::{Duration, Instant},
};

fn wait(engine: &Engine, kind: &str) -> Value {
    let end = Instant::now() + Duration::from_secs(5);
    while Instant::now() < end {
        if let Some(event) = engine.event(Duration::from_millis(50)).unwrap() {
            if event["event"] == kind {
                return event;
            }
        }
    }
    panic!("missing event {kind}")
}

#[test]
fn embedded_core_keeps_identity_releases_state_and_rejects_bad_control() {
    let dir = tempfile::tempdir().unwrap();
    let engine = Engine::start(dir.path()).unwrap();
    let ready = wait(&engine, "ready");
    assert_eq!(ready["relay"], false);
    assert!(ready["address"].as_str().unwrap().starts_with("127.0.0.1:"));
    assert!(Engine::start(dir.path()).is_err());
    assert!(engine.command(b"{not json").is_err());
    assert!(engine.command(&vec![b'x'; 16385]).is_err());
    assert!(engine.command(br#"{"cmd":"quit"}"#).is_err());
    engine.close();
    assert!(engine.command(br#"{"cmd":"status"}"#).is_err());
    let restarted = Engine::start(dir.path()).unwrap();
    assert_eq!(wait(&restarted, "ready")["key"], ready["key"]);
    restarted.close();
}

// Real stream bridges between three independent embedded instances, not a second routing model.
fn bridge(a: &str, b: &str) -> (TcpStream, TcpStream) {
    let first = TcpStream::connect(a).unwrap();
    let second = TcpStream::connect(b).unwrap();
    let mut read_a = first.try_clone().unwrap();
    let mut write_b = second.try_clone().unwrap();
    thread::spawn(move || {
        let _ = std::io::copy(&mut read_a, &mut write_b);
    });
    let mut read_b = second.try_clone().unwrap();
    let mut write_a = first.try_clone().unwrap();
    thread::spawn(move || {
        let _ = std::io::copy(&mut read_b, &mut write_a);
    });
    (first, second)
}

#[test]
fn three_embedded_cores_relay_bidirectionally_and_observe_relay_off() {
    let dirs: Vec<_> = (0..3).map(|_| tempfile::tempdir().unwrap()).collect();
    let nodes: Vec<_> = dirs
        .iter()
        .map(|d| Engine::start(d.path()).unwrap())
        .collect();
    let addresses: Vec<_> = nodes
        .iter()
        .map(|n| wait(n, "ready")["address"].as_str().unwrap().to_owned())
        .collect();
    nodes[1]
        .command(br#"{"cmd":"relay","enabled":true}"#)
        .unwrap();
    wait(&nodes[1], "relay");
    let ab = bridge(&addresses[0], &addresses[1]);
    let bc = bridge(&addresses[1], &addresses[2]);
    wait(&nodes[0], "neighbor_up");
    wait(&nodes[2], "neighbor_up");
    for (from, to) in [(0, 2), (2, 0)] {
        nodes[from]
            .command(
                serde_json::to_string(
                    &json!({"cmd":"send","text":"Apple 公共 Mesh 🌐","ttl":30,"hops":4}),
                )
                .unwrap()
                .as_bytes(),
            )
            .unwrap();
        let queued = wait(&nodes[from], "queued");
        let received = wait(&nodes[to], "message");
        assert_eq!(received["id"], queued["id"]);
        assert_eq!(received["path"].as_array().unwrap().len(), 3);
    }
    nodes[1]
        .command(br#"{"cmd":"relay","enabled":false}"#)
        .unwrap();
    wait(&nodes[1], "relay");
    nodes[0]
        .command(br#"{"cmd":"send","text":"blocked","ttl":1,"hops":4}"#)
        .unwrap();
    let id = wait(&nodes[0], "queued")["id"].clone();
    let until = Instant::now() + Duration::from_millis(1500);
    while Instant::now() < until {
        if let Some(e) = nodes[2].event(Duration::from_millis(25)).unwrap() {
            assert!(!(e["event"] == "message" && e["id"] == id));
        }
    }
    for stream in [&ab.0, &ab.1, &bc.0, &bc.1] {
        stream.shutdown(std::net::Shutdown::Both).unwrap();
    }
    for node in &nodes {
        node.close();
    }
}

#[test]
fn stalled_event_consumer_stops_instead_of_growing_unbounded() {
    let dir = tempfile::tempdir().unwrap();
    let engine = Engine::start(dir.path()).unwrap();
    wait(&engine, "ready");
    let end = Instant::now() + Duration::from_secs(5);
    while engine.state() == 1 && Instant::now() < end {
        let _ = engine.command(br#"{"cmd":"status"}"#);
        thread::sleep(Duration::from_millis(1));
    }
    assert_eq!(engine.state(), -2, "event backpressure must fail closed");
    engine.close();
}

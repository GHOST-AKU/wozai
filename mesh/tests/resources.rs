use nearby_mesh::{
    protocol::{Envelope, Identity, MAX_FRAME, Packet},
    store::Store,
    transport::{Link, MAX_TX_QUEUE, Wire},
};
use std::{
    io::Write,
    net::{TcpListener, TcpStream},
    time::Duration,
};
fn pair() -> (Link, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let remote = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let (local, _) = listener.accept().unwrap();
    (Link::new(local, false, "00".repeat(16)).unwrap(), remote)
}
#[test]
fn tx_queue_is_hard_bounded_even_when_peer_never_reads() {
    let (mut link, _slow) = pair();
    for _ in 0..MAX_TX_QUEUE {
        link.queue(&Wire::Ping, None, u64::MAX, false).unwrap();
    }
    assert!(link.queue(&Wire::Ping, None, u64::MAX, false).is_err());
    assert_eq!(link.tx.len(), MAX_TX_QUEUE);
}
#[test]
fn fragmented_header_and_payload_are_reassembled() {
    let (mut link, mut remote) = pair();
    let body = serde_json::to_vec(&Wire::Ping).unwrap();
    let mut frame = (body.len() as u32).to_be_bytes().to_vec();
    frame.extend(body);
    for (i, byte) in frame.iter().enumerate() {
        remote.write_all(&[*byte]).unwrap();
        let frames = link.read().unwrap();
        if i + 1 == frame.len() {
            assert_eq!(frames.len(), 1);
        } else {
            assert!(frames.is_empty());
        }
    }
}
#[test]
fn oversized_header_is_rejected_before_payload_allocation() {
    let (mut link, mut remote) = pair();
    remote
        .write_all(&((MAX_FRAME + 1) as u32).to_be_bytes())
        .unwrap();
    assert!(link.read().is_err());
    assert_eq!(link.bytes_rx, 4);
}
#[test]
fn ingress_frame_rate_is_bounded() {
    let (mut link, mut remote) = pair();
    let body = serde_json::to_vec(&Wire::Ping).unwrap();
    let mut frame = (body.len() as u32).to_be_bytes().to_vec();
    frame.extend(body);
    for _ in 0..33 {
        remote.write_all(&frame).unwrap();
    }
    let mut rejected = false;
    for _ in 0..8 {
        if link.read().is_err() {
            rejected = true;
            break;
        }
    }
    assert!(rejected);
}
#[test]
fn corrupt_or_oversized_durable_dedupe_is_not_silently_discarded() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    std::fs::write(dir.path().join("seen.json"), "{}").unwrap();
    assert!(store.load().is_err());
    std::fs::write(dir.path().join("seen.json"), vec![0; 131073]).unwrap();
    assert!(store.load().is_err());
    assert!(Store::open(dir.path()).is_err());
}
#[test]
fn identity_is_stable_and_seed_is_private_on_unix() {
    let dir = tempfile::tempdir().unwrap();
    let key = {
        let store = Store::open(dir.path()).unwrap();
        store.identity().unwrap().key()
    };
    let store = Store::open(dir.path()).unwrap();
    assert_eq!(key, store.identity().unwrap().key());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(dir.path().join("identity.seed"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }
}
#[test]
fn incomplete_handshake_has_a_deadline() {
    let (mut link, _) = pair();
    link.born = std::time::Instant::now() - Duration::from_secs(4);
    assert!(link.timed_out());
}
#[test]
fn checkpoint_retains_core_clock_floor_after_wall_clock_rollback() {
    use nearby_mesh::{
        core::{Core, Limits},
        protocol::Identity,
    };
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let mut core = Core::new(Identity::from_seed([1; 32]).key(), false, Limits::default());
    core.tick(1600);
    core.tick(1500);
    store.checkpoint(&core).unwrap();
    assert_eq!(store.load().unwrap().saved_at, 1600);
}
#[test]
fn discovery_cannot_dial_off_subnet_advertised_addresses() {
    use nearby_mesh::discovery::on_link;
    let local = "10.77.1.1".parse().unwrap();
    let mask = "255.255.255.252".parse().unwrap();
    assert!(on_link("10.77.1.2".parse().unwrap(), local, mask));
    for candidate in [
        "8.8.8.8",
        "10.77.2.2",
        "127.0.0.1",
        "224.0.0.1",
        "0.0.0.0",
        "10.77.1.1",
        "10.77.1.0",
        "10.77.1.3",
    ] {
        assert!(
            !on_link(candidate.parse().unwrap(), local, mask),
            "{candidate}"
        );
    }
}
#[test]
fn complete_frames_before_peer_eof_are_not_discarded() {
    let (mut link, mut remote) = pair();
    let body = serde_json::to_vec(&Wire::Ping).unwrap();
    remote
        .write_all(&(body.len() as u32).to_be_bytes())
        .unwrap();
    remote.write_all(&body).unwrap();
    remote.shutdown(std::net::Shutdown::Write).unwrap();
    assert_eq!(link.read().unwrap().len(), 1);
    assert!(link.read().is_err());
}

fn outgoing_data(body: &str, expires_after: u64) -> (Wire, String) {
    let identity = Identity::from_seed([23; 32]);
    let message = Envelope::new(&identity, body, 1000, expires_after, 3).unwrap();
    let id = message.id.clone();
    (
        Wire::Data {
            packet: Packet {
                message,
                route: vec![],
            },
        },
        id,
    )
}

#[test]
fn relay_revocation_reports_unsent_ids_for_retry_on_the_same_connection() {
    let (mut link, _remote) = pair();
    let (wire, id) = outgoing_data("relay cancellation", 100);
    link.queue(&wire, Some(id.clone()), 1100, true).unwrap();
    assert_eq!(link.pending_ids(), vec![id.clone()]);

    assert!(link.write(1000, false).unwrap().is_empty());
    assert!(link.pending_ids().is_empty());
    assert_eq!(link.take_cancelled(), vec![id.clone()]);
    assert!(link.take_cancelled().is_empty());
    assert_eq!(link.bytes_tx, 0);

    link.queue(&wire, Some(id.clone()), 1100, true).unwrap();
    assert_eq!(link.write(1000, true).unwrap(), vec![id]);
    assert!(link.take_cancelled().is_empty());
    assert!(link.pending_ids().is_empty());
}

#[test]
fn queued_frames_expire_using_the_effective_clock_after_wall_clock_rollback() {
    use nearby_mesh::core::{Core, Limits};
    let (mut link, _remote) = pair();
    let (wire, id) = outgoing_data("expired before rollback", 100);
    link.queue(&wire, Some(id.clone()), 1100, false).unwrap();
    let mut core = Core::new(
        Identity::from_seed([23; 32]).key(),
        false,
        Limits::default(),
    );
    core.tick(1101);
    core.tick(900);

    assert!(link.write(core.clock_floor(), false).unwrap().is_empty());
    assert_eq!(link.take_cancelled(), vec![id]);
    assert!(link.pending_ids().is_empty());
    assert_eq!(link.bytes_tx, 0);
}

#[test]
fn cancellation_notifications_remain_bounded_until_drained() {
    let (mut link, _remote) = pair();
    let (wire, id) = outgoing_data("bounded cancellation", 100);
    for _ in 0..MAX_TX_QUEUE {
        link.queue(&wire, Some(id.clone()), 1100, true).unwrap();
    }
    assert!(link.write(1000, false).unwrap().is_empty());
    for _ in 0..MAX_TX_QUEUE {
        link.queue(&wire, Some(id.clone()), 1100, true).unwrap();
    }
    assert!(link.write(1000, false).is_err());
    assert_eq!(link.pending_ids().len(), MAX_TX_QUEUE);
    assert_eq!(link.take_cancelled(), vec![id.clone(); MAX_TX_QUEUE]);
    assert!(link.write(1000, false).unwrap().is_empty());
    assert_eq!(link.take_cancelled(), vec![id; MAX_TX_QUEUE]);
}

#[cfg(unix)]
#[test]
fn revoking_a_partially_written_frame_requires_closing_the_link() {
    use std::{io::Read, os::fd::AsRawFd};
    let (mut link, mut remote) = pair();
    let send_buffer: libc::c_int = 1024 * 1024;
    assert_eq!(
        unsafe {
            libc::setsockopt(
                link.stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_SNDBUF,
                (&send_buffer as *const libc::c_int).cast(),
                std::mem::size_of_val(&send_buffer) as libc::socklen_t,
            )
        },
        0
    );
    let (wire, id) = outgoing_data(&"\0".repeat(2048), 100);
    for _ in 0..MAX_TX_QUEUE {
        link.queue(&wire, Some(id.clone()), 1100, true).unwrap();
    }
    let handed = link.write(1000, true).unwrap();
    assert!(!handed.is_empty());
    assert_eq!(link.bytes_tx, 65536);
    assert_eq!(
        link.write(1000, false).unwrap_err().to_string(),
        "partial_frame_cancelled"
    );
    assert!(!link.pending_ids().is_empty());
    assert!(link.take_cancelled().is_empty());

    drop(link);
    remote
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let mut bytes = Vec::new();
    remote.read_to_end(&mut bytes).unwrap();
    assert_eq!(bytes.len(), 65536);
}

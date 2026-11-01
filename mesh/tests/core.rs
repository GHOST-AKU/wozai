use nearby_mesh::protocol::*;
fn ids() -> (Identity, Identity, Identity, Identity) {
    (
        Identity::from_seed([1; 32]),
        Identity::from_seed([2; 32]),
        Identity::from_seed([3; 32]),
        Identity::from_seed([4; 32]),
    )
}
fn message(a: &Identity, hops: u8) -> Packet {
    Packet {
        message: Envelope::new(a, "offline 🌍", 1000, 600, hops).unwrap(),
        route: vec![],
    }
}
#[test]
fn valid_bidirectional_signed_routes() {
    let (a, b, c, _) = ids();
    let ab = message(&a, 3).append(&a, &b.key()).unwrap();
    assert!(ab.validate(&b.key(), &a.key(), 1001).is_ok());
    let bc = ab.append(&b, &c.key()).unwrap();
    assert!(bc.validate(&c.key(), &b.key(), 1002).is_ok());
    let cb = message(&c, 3).append(&c, &b.key()).unwrap();
    let ba = cb.append(&b, &a.key()).unwrap();
    assert!(ba.validate(&a.key(), &b.key(), 1002).is_ok());
}
#[test]
fn immutable_fields_and_signatures_cannot_be_changed() {
    let (a, b, _, _) = ids();
    let good = message(&a, 3).append(&a, &b.key()).unwrap();
    for field in [
        "body",
        "expires",
        "max_hops",
        "created",
        "id",
        "origin",
        "nonce",
        "signature",
        "version",
        "kind",
    ] {
        let mut value = serde_json::to_value(&good).unwrap();
        let old = &value["message"][field];
        value["message"][field] = if old.is_string() {
            serde_json::json!("00")
        } else {
            serde_json::json!(99)
        };
        let bad: Packet = serde_json::from_value(value).unwrap();
        assert!(bad.validate(&b.key(), &a.key(), 1001).is_err(), "{field}");
    }
}
#[test]
fn relay_cannot_reset_or_remove_hops() {
    let (a, b, c, d) = ids();
    let ab = message(&a, 2).append(&a, &b.key()).unwrap();
    let bc = ab.append(&b, &c.key()).unwrap();
    assert!(bc.append(&c, &d.key()).is_err());
    let mut reset = bc.clone();
    reset.route.clear();
    assert!(reset.append(&c, &d.key()).is_err());
    let mut removed = bc.clone();
    removed.route.remove(0);
    assert!(removed.validate(&c.key(), &b.key(), 1001).is_err());
    let mut rewired = ab.clone();
    rewired.route[0].to = c.key();
    assert!(rewired.validate(&c.key(), &a.key(), 1001).is_err());
    assert!(bc.validate(&c.key(), &a.key(), 1001).is_err());
    assert!(ab.append(&b, &a.key()).is_err());
}
#[test]
fn expiry_future_and_text_bounds() {
    let (a, _, _, _) = ids();
    assert!(Envelope::new(&a, &"x".repeat(MAX_BODY), 1000, 600, 8).is_ok());
    for text in [
        String::new(),
        "x".repeat(MAX_BODY + 1),
        "🌍".repeat(MAX_BODY / 4 + 1),
    ] {
        assert!(Envelope::new(&a, &text, 1000, 600, 3).is_err());
    }
    for (ttl, hops) in [(0, 3), (601, 3), (600, 0), (600, 9)] {
        assert!(Envelope::new(&a, "x", 1000, ttl, hops).is_err());
    }
    assert!(Envelope::new(&a, "x", u64::MAX, 1, 3).is_err());
    let m = message(&a, 3).message;
    assert!(m.validate(969).is_err());
    assert!(m.validate(970).is_ok());
    assert!(m.validate(1600).is_err());
}
#[test]
fn unique_ids_strict_shape_and_frame_bound() {
    let (a, b, c, d) = ids();
    assert_ne!(message(&a, 3).message.id, message(&a, 3).message.id);
    let p = Packet {
        message: Envelope::new(&a, &"\u{0001}".repeat(MAX_BODY), 1000, 600, 8).unwrap(),
        route: vec![],
    }
    .append(&a, &b.key())
    .unwrap()
    .append(&b, &c.key())
    .unwrap()
    .append(&c, &d.key())
    .unwrap();
    assert!(serde_json::to_vec(&p).unwrap().len() < MAX_FRAME);
    let mut v = serde_json::to_value(&p).unwrap();
    v["unsigned_expiry"] = serde_json::json!(999999);
    assert!(serde_json::from_value::<Packet>(v).is_err());
}

use nearby_mesh::core::{Core, Limits};
#[test]
fn bounded_core_relay_dedupe_expiry_and_reconnect() {
    let (a, b, c, _) = ids();
    let mut core = Core::new(b.key(), true, Limits::default());
    let packet = message(&a, 3).append(&a, &b.key()).unwrap();
    assert!(core.receive(packet.clone(), &a.key(), 1001).unwrap());
    assert!(!core.receive(packet, &a.key(), 1001).unwrap());
    assert_eq!(core.pending_len(), 1);
    assert!(core.next_for(&b, &a.key(), 1001).is_none());
    let first = core.next_for(&b, &c.key(), 1001).unwrap();
    assert_eq!(first.path(), vec![a.key(), b.key(), c.key()]);
    assert!(core.next_for(&b, &c.key(), 1001).is_none());
    core.disconnected(&c.key());
    assert!(core.next_for(&b, &c.key(), 1001).is_some());
    core.relay = false;
    core.disconnected(&c.key());
    assert!(core.next_for(&b, &c.key(), 1001).is_none());
    let expired = core.tick(1600);
    assert_eq!(expired.len(), 1);
    assert_eq!(core.pending_len(), 0);
}
#[test]
fn dedupe_capacity_fails_closed_and_restores_without_body() {
    let (a, b, _, _) = ids();
    let limits = Limits {
        seen: 2,
        pending: 2,
        ..Limits::default()
    };
    let mut core = Core::new(b.key(), false, limits.clone());
    let packet = message(&a, 3).append(&a, &b.key()).unwrap();
    assert!(core.receive(packet.clone(), &a.key(), 1001).unwrap());
    assert!(
        core.receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001)
            .unwrap()
    );
    assert_eq!(
        core.receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001),
        Err("seen_full")
    );
    let snapshot = core.seen.clone();
    let mut restarted = Core::new(b.key(), false, limits);
    restarted.restore_seen(snapshot, 1001).unwrap();
    assert!(!restarted.receive(packet, &a.key(), 1001).unwrap());
    assert_eq!(restarted.pending_len(), 0);
    restarted.tick(1600);
    assert!(restarted.seen.is_empty());
}
#[test]
fn queue_and_origin_flood_are_bounded() {
    let (a, b, _, _) = ids();
    let mut queue = Core::new(
        b.key(),
        true,
        Limits {
            pending: 1,
            ..Limits::default()
        },
    );
    queue
        .receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001)
        .unwrap();
    assert_eq!(
        queue.receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001),
        Err("queue_full")
    );
    let mut rate = Core::new(
        b.key(),
        false,
        Limits {
            per_origin: 2,
            ..Limits::default()
        },
    );
    for _ in 0..2 {
        rate.receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001)
            .unwrap();
    }
    assert_eq!(
        rate.receive(message(&a, 3).append(&a, &b.key()).unwrap(), &a.key(), 1001),
        Err("origin_rate")
    );
    assert_eq!(rate.seen.len(), 2);
}
#[test]
fn local_messages_work_without_relay_and_hop_exhaustion_stops_forwarding() {
    let (a, b, c, _) = ids();
    let mut origin = Core::new(a.key(), false, Limits::default());
    let id = origin.originate(&a, "hello", 1000, 1, 1).unwrap();
    let ab = origin.next_for(&a, &b.key(), 1000).unwrap();
    assert_eq!(id, ab.message.id);
    let mut relay = Core::new(b.key(), true, Limits::default());
    relay.receive(ab, &a.key(), 1000).unwrap();
    assert!(relay.next_for(&b, &c.key(), 1000).is_none());
    assert_eq!(origin.tick(1001), vec![id]);
}
#[test]
fn retry_and_neighbor_churn_cannot_grow_per_message_state() {
    let (a, b, _, _) = ids();
    let mut core = Core::new(a.key(), false, Limits::default());
    core.originate(&a, "bounded", 1000, 600, 3).unwrap();
    for _ in 0..3 {
        assert!(core.next_for(&a, &b.key(), 1001).is_some());
        core.disconnected(&b.key());
    }
    assert!(core.next_for(&a, &b.key(), 1001).is_none());
    for n in 5..12 {
        assert!(
            core.next_for(&a, &Identity::from_seed([n; 32]).key(), 1001)
                .is_some()
        );
    }
    assert!(
        core.next_for(&a, &Identity::from_seed([99; 32]).key(), 1001)
            .is_none()
    );
}
#[test]
fn backwards_clock_does_not_revive_expired_message() {
    let (a, b, _, _) = ids();
    let mut core = Core::new(b.key(), false, Limits::default());
    let p = message(&a, 3).append(&a, &b.key()).unwrap();
    core.tick(1600);
    assert_eq!(core.receive(p, &a.key(), 1000), Err("expired"));
}

#[test]
fn exhausted_received_routes_do_not_block_forward_queue_or_local_messages() {
    let (a, b, c, _) = ids();
    let mut core = Core::new(
        b.key(),
        true,
        Limits {
            pending: 1,
            ..Limits::default()
        },
    );
    let terminal = message(&a, 1).append(&a, &b.key()).unwrap();
    assert!(core.receive(terminal.clone(), &a.key(), 1001).unwrap());
    assert!(!core.receive(terminal, &a.key(), 1001).unwrap());
    assert!(core.next_for(&b, &c.key(), 1001).is_none());
    let local = core.originate(&b, "local remains available", 1001, 600, 3);
    assert!(
        local.is_ok(),
        "terminal receipt consumed forwarding capacity: {local:?}"
    );
    assert_eq!(core.pending_len(), 1);
    assert_eq!(core.seen.len(), 2);
    let forwarded = core.next_for(&b, &c.key(), 1001).unwrap();
    assert_eq!(forwarded.message.id, local.unwrap());
    assert!(forwarded.validate(&c.key(), &b.key(), 1001).is_ok());
}

#[test]
fn relay_skips_visited_neighbors_and_keeps_valid_successors() {
    let (a, b, c, d) = ids();
    let mut core = Core::new(c.key(), true, Limits::default());
    let received = message(&a, 3)
        .append(&a, &b.key())
        .unwrap()
        .append(&b, &c.key())
        .unwrap();
    assert!(core.receive(received, &b.key(), 1001).unwrap());
    for visited in [&a, &b, &c] {
        assert!(core.next_for(&c, &visited.key(), 1001).is_none());
    }
    let forwarded = core.next_for(&c, &d.key(), 1001).unwrap();
    assert_eq!(forwarded.path(), vec![a.key(), b.key(), c.key(), d.key()]);
    assert!(forwarded.validate(&d.key(), &c.key(), 1001).is_ok());
}

#[test]
fn rejected_expired_receive_advances_clock_floor() {
    let (a, b, _, _) = ids();
    let mut core = Core::new(b.key(), false, Limits::default());
    let packet = message(&a, 3).append(&a, &b.key()).unwrap();
    core.tick(1599);
    assert_eq!(core.receive(packet.clone(), &a.key(), 1600), Err("expired"));
    assert_eq!(core.receive(packet, &a.key(), 1001), Err("expired"));
    assert_eq!(core.clock_floor(), 1600);
}

#[test]
fn skipped_expired_send_and_rejected_origination_advance_clock_floor() {
    let (a, b, _, _) = ids();
    let mut send = Core::new(a.key(), false, Limits::default());
    send.originate(&a, "expires", 1000, 600, 3).unwrap();
    send.tick(1599);
    assert!(send.next_for(&a, &b.key(), 1600).is_none());
    assert!(send.next_for(&a, &b.key(), 1001).is_none());
    assert_eq!(send.clock_floor(), 1600);

    let mut origin = Core::new(a.key(), false, Limits::default());
    origin.originate(&a, "expires", 1000, 600, 3).unwrap();
    assert_eq!(origin.originate(&a, "", 1600, 600, 3), Err("body_bound"));
    assert!(origin.next_for(&a, &b.key(), 1001).is_none());
    assert_eq!(origin.clock_floor(), 1600);
}

#[test]
fn admission_preserves_expired_message_for_next_tick() {
    let (a, _, _, _) = ids();
    let mut core = Core::new(a.key(), false, Limits::default());
    let expired = core.originate(&a, "first", 1000, 1, 3).unwrap();
    core.originate(&a, "second", 1001, 600, 3).unwrap();
    assert_eq!(core.pending_len(), 1);
    assert_eq!(core.tick(1001), vec![expired]);
    assert!(core.tick(1001).is_empty());
}

#[test]
fn deferred_expiry_events_are_bounded_and_never_evicted() {
    let (a, _, _, _) = ids();
    let mut core = Core::new(
        a.key(),
        false,
        Limits {
            pending: 1,
            ..Limits::default()
        },
    );
    let first = core.originate(&a, "first", 1000, 1, 3).unwrap();
    let second = core.originate(&a, "second", 1001, 1, 3).unwrap();
    assert_eq!(core.originate(&a, "third", 1002, 600, 3), Err("queue_full"));
    let mut expected = vec![first, second];
    expected.sort();
    assert_eq!(core.tick(1002), expected);
    assert_eq!(core.pending_len(), 0);
    assert!(
        core.originate(&a, "after events drain", 1002, 600, 3)
            .is_ok()
    );
    assert!(core.tick(1002).is_empty());
}

#[test]
fn cancelling_one_offer_preserves_other_peers_and_retry_budget() {
    let (a, b, c, _) = ids();
    let mut core = Core::new(a.key(), false, Limits::default());
    let id = core.originate(&a, "bounded retries", 1000, 600, 3).unwrap();
    assert!(core.next_for(&a, &b.key(), 1001).is_some());
    assert!(core.next_for(&a, &c.key(), 1001).is_some());
    for _ in 0..2 {
        core.cancel_offer(&id, &b.key());
        assert!(core.next_for(&a, &b.key(), 1001).is_some());
        assert!(core.next_for(&a, &c.key(), 1001).is_none());
    }
    core.cancel_offer(&id, &b.key());
    assert!(core.next_for(&a, &b.key(), 1001).is_none());
    assert!(core.next_for(&a, &c.key(), 1001).is_none());
}

#[test]
fn restoring_seen_does_not_consume_pending_expiry_events() {
    let (a, _, _, _) = ids();
    let mut core = Core::new(a.key(), false, Limits::default());
    let expired = core.originate(&a, "expires", 1000, 1, 3).unwrap();
    core.originate(&a, "survives", 1001, 600, 3).unwrap();
    core.restore_seen(core.seen.clone(), 1001).unwrap();
    assert_eq!(core.tick(1001), vec![expired]);
}

use nearby_mesh::{
    core::{Core, Limits},
    protocol::{Envelope, Identity, Packet},
};

fn terminal_packet(origin: &Identity, receiver: &Identity, now: u64) -> Packet {
    Packet {
        message: Envelope::new(origin, "bounded public message", now, 600, 1).unwrap(),
        route: vec![],
    }
    .append(origin, &receiver.key())
    .unwrap()
}

#[test]
fn distinct_origins_cannot_bypass_global_buckets_or_message_admission_budget() {
    let receiver = Identity::from_seed([255; 32]);
    let origins: Vec<_> = (0..65)
        .map(|seed| Identity::from_seed([seed; 32]))
        .collect();
    let mut core = Core::new(
        receiver.key(),
        true,
        Limits {
            seen: 129,
            pending: 1,
            ..Limits::default()
        },
    );
    let first = terminal_packet(&origins[0], &receiver, 1000);
    assert!(
        core.receive(first.clone(), &origins[0].key(), 1000)
            .unwrap()
    );
    for origin in &origins[1..64] {
        assert!(
            core.receive(
                terminal_packet(origin, &receiver, 1000),
                &origin.key(),
                1000
            )
            .unwrap()
        );
    }
    let excess_origin = terminal_packet(&origins[64], &receiver, 1000);
    assert_eq!(
        core.receive(excess_origin.clone(), &origins[64].key(), 1000),
        Err("global_rate")
    );
    // Filling the origin buckets still permits existing origins within the total budget.
    for origin in &origins[..64] {
        assert!(
            core.receive(
                terminal_packet(origin, &receiver, 1000),
                &origin.key(),
                1000
            )
            .unwrap()
        );
    }
    assert_eq!(
        core.receive(
            terminal_packet(&origins[0], &receiver, 1000),
            &origins[0].key(),
            1000
        ),
        Err("global_rate")
    );
    assert_eq!(core.seen.len(), 128);
    assert_eq!(core.pending_len(), 0);
    assert!(!core.receive(first, &origins[0].key(), 1000).unwrap());

    // A refused ID can be admitted after the natural-minute window rolls over.
    assert!(
        core.receive(excess_origin, &origins[64].key(), 1020)
            .unwrap()
    );
}

#[test]
fn distinct_origins_cannot_evict_unexpired_dedupe_entries_at_capacity() {
    let receiver = Identity::from_seed([255; 32]);
    let origins: Vec<_> = (1..=4)
        .map(|seed| Identity::from_seed([seed; 32]))
        .collect();
    let mut core = Core::new(
        receiver.key(),
        true,
        Limits {
            seen: 3,
            pending: 1,
            ..Limits::default()
        },
    );
    let first = terminal_packet(&origins[0], &receiver, 1000);
    assert!(
        core.receive(first.clone(), &origins[0].key(), 1000)
            .unwrap()
    );
    for origin in &origins[1..3] {
        assert!(
            core.receive(
                terminal_packet(origin, &receiver, 1001),
                &origin.key(),
                1001
            )
            .unwrap()
        );
    }
    let refused = terminal_packet(&origins[3], &receiver, 1001);
    assert_eq!(
        core.receive(refused.clone(), &origins[3].key(), 1001),
        Err("seen_full")
    );
    assert!(
        !core
            .receive(first.clone(), &origins[0].key(), 1001)
            .unwrap()
    );
    assert!(core.tick(1600).is_empty());
    assert_eq!(core.receive(first, &origins[0].key(), 1600), Err("expired"));
    assert!(core.receive(refused, &origins[3].key(), 1600).unwrap());
}

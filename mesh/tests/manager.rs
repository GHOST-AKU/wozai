use nearby_mesh::manager::{MAX_CANDIDATES, Manager};

#[test]
fn repeated_discovery_and_new_candidates_share_one_bounded_registry() {
    let mut manager = Manager::default();
    for port in 10000..10000 + MAX_CANDIDATES as u16 {
        let candidate = format!("127.0.0.1:{port}").parse().unwrap();
        for _ in 0..100 {
            assert!(manager.add_discovered(candidate));
        }
    }
    assert_eq!(manager.len(), MAX_CANDIDATES);
    assert!(!manager.add_discovered("127.0.0.1:20000".parse().unwrap()));
    assert!(!manager.add("127.0.0.1:20001".parse().unwrap()));
    assert_eq!(manager.len(), MAX_CANDIDATES);
}

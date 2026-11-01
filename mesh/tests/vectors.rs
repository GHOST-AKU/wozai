use nearby_mesh::protocol::{Envelope, Identity, Packet};
use serde_json::Value;

#[test]
fn independent_fixed_envelope_and_two_hop_vector() {
    let fixture: Value = serde_json::from_str(include_str!("vectors/public-v1.json")).unwrap();
    let packet: Packet = serde_json::from_value(fixture["packet"].clone()).unwrap();
    let a = Identity::from_seed([1; 32]);
    let b = Identity::from_seed([2; 32]);
    let c = Identity::from_seed([3; 32]);
    assert_eq!(packet.message.origin, a.key());
    assert!(packet.validate(&c.key(), &b.key(), 1001).is_ok());
    let local = Packet {
        message: packet.message.clone(),
        route: vec![],
    }
    .append(&a, &b.key())
    .unwrap()
    .append(&b, &c.key())
    .unwrap();
    assert_eq!(serde_json::to_value(local).unwrap(), fixture["packet"]);
    let canonical = hex::decode(fixture["canonical_hex"].as_str().unwrap()).unwrap();
    assert_eq!(
        packet.message.id,
        hex::encode(nearby_mesh::protocol::hash(&canonical))
    );
    assert_eq!(a.sign(&canonical), packet.message.signature);
    // Correct-length signature mutations exercise cryptographic rejection, not just parser bounds.
    for offset in [0, 31, 63] {
        let mut changed = packet.clone();
        let mut signature = hex::decode(&changed.message.signature).unwrap();
        signature[offset] ^= 1;
        changed.message.signature = hex::encode(signature);
        assert!(changed.message.validate(1001).is_err());
        assert!(changed.validate(&c.key(), &b.key(), 1001).is_err());
    }
    // Mutating only the final receipt keeps the origin and earlier chain intact.
    let mut changed = packet.clone();
    let last = changed.route.last_mut().unwrap();
    let mut signature = hex::decode(&last.signature).unwrap();
    signature[31] ^= 1;
    last.signature = hex::encode(signature);
    assert!(changed.message.validate(1001).is_ok());
    assert!(changed.validate(&c.key(), &b.key(), 1001).is_err());
}

#[test]
fn generated_utf8_envelopes_roundtrip_without_normalizing_text() {
    let key = Identity::from_seed([7; 32]);
    for (text, hops, ttl) in [("e\u{301}", 1, 1), ("é", 3, 30), ("\0\n🌍", 8, 600)] {
        let message = Envelope::new(&key, text, 1000, ttl, hops).unwrap();
        let decoded: Envelope =
            serde_json::from_slice(&serde_json::to_vec(&message).unwrap()).unwrap();
        assert_eq!(decoded.body, text);
        assert!(decoded.validate(1000).is_ok());
    }
}

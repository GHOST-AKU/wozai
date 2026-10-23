//! Snow implements Noise XX; RustCrypto implements the NIM4 envelope's authenticated cipher.
//! NIM4 authenticates its ten-byte outer header, so Snow's transport API (empty AAD)
//! cannot be used directly. Split keys are used only after the completed XX handshake.
use crate::{
    profile::{Identity, Peer, validate_root},
    wire::*,
};
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use p256::ecdsa::{
    Signature,
    signature::{Signer, Verifier},
};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    net::TcpStream,
};
use zeroize::Zeroizing;
const SUITE: &str = "Noise_XX_25519_AESGCM_SHA256";
const PROLOGUE: &[u8] = b"wozai-nim4\0Noise_XX_25519_AESGCM_SHA256\0file-v2-32k\0";

pub struct Cipher {
    key: Zeroizing<[u8; 32]>,
    nonce: u64,
}
impl Cipher {
    pub fn new(key: [u8; 32]) -> Self {
        Self {
            key: Zeroizing::new(key),
            nonce: 0,
        }
    }
    fn nonce(&self) -> Result<[u8; 12]> {
        ensure!(self.nonce != u64::MAX, "Noise nonce exhausted");
        let mut n = [0; 12];
        n[4..].copy_from_slice(&self.nonce.to_be_bytes());
        Ok(n)
    }
    pub fn encrypt(&mut self, kind: u8, plain: &[u8]) -> Result<Vec<u8>> {
        let h = header(kind, plain.len() + 16);
        let n = self.nonce()?;
        let cipher = Aes256Gcm::new_from_slice(self.key.as_ref()).unwrap();
        let data = cipher
            .encrypt(
                Nonce::from_slice(&n),
                Payload {
                    msg: plain,
                    aad: &h,
                },
            )
            .map_err(|_| anyhow::anyhow!("record encryption failed"))?;
        self.nonce += 1;
        let mut b = h.to_vec();
        b.extend(data);
        Ok(b)
    }
    pub fn decrypt(&mut self, h: &[u8; 10], data: &[u8]) -> Result<Vec<u8>> {
        let n = self.nonce()?;
        let cipher = Aes256Gcm::new_from_slice(self.key.as_ref()).unwrap();
        let b = cipher
            .decrypt(Nonce::from_slice(&n), Payload { msg: data, aad: h })
            .map_err(|_| anyhow::anyhow!("record authentication failed"))?;
        self.nonce += 1;
        Ok(b)
    }
}
pub fn header(kind: u8, size: usize) -> [u8; 10] {
    let mut h = [0; 10];
    h[..4].copy_from_slice(&((size + 6) as u32).to_be_bytes());
    h[4..8].copy_from_slice(&MAGIC.to_be_bytes());
    h[8] = 4;
    h[9] = kind;
    h
}
pub fn read_envelope(r: &mut impl Read, kind: u8, max: usize) -> Result<([u8; 10], Vec<u8>)> {
    let mut h = [0; 10];
    r.read_exact(&mut h[..4])?;
    let n = u32::from_be_bytes(h[..4].try_into().unwrap()) as usize;
    ensure!((6..=max + 6).contains(&n), "invalid envelope size");
    r.read_exact(&mut h[4..])?;
    ensure!(
        h[4..8] == MAGIC.to_be_bytes() && h[8] == 4 && h[9] == kind,
        "unsupported envelope header"
    );
    let mut b = vec![0; n - 6];
    r.read_exact(&mut b)?;
    Ok((h, b))
}
fn send_handshake(s: &mut TcpStream, h: &mut snow::HandshakeState, payload: &[u8]) -> Result<()> {
    let mut out = [0; 2048];
    let n = h.write_message(payload, &mut out)?;
    s.write_all(&header(1, n))?;
    s.write_all(&out[..n])?;
    Ok(())
}
fn receive_handshake(s: &mut TcpStream, h: &mut snow::HandshakeState) -> Result<Vec<u8>> {
    let (_, b) = read_envelope(s, 1, 2048)?;
    let mut out = [0; 2048];
    let n = h.read_message(&b, &mut out)?;
    Ok(out[..n].to_vec())
}
fn binding(domain: &[u8], first: &[u8], second: &[u8], hash: &[u8]) -> Vec<u8> {
    let mut b = domain.to_vec();
    for p in [first, second, hash] {
        short(&mut b, p);
    }
    b
}
fn claim(i: &Identity, initiator: bool, static_public: &[u8]) -> Vec<u8> {
    let mut b = vec![4, (!initiator) as u8];
    short(&mut b, i.id.as_bytes());
    short(&mut b, i.name.as_bytes());
    short(&mut b, &i.public);
    b.extend(static_public);
    b.extend(3u32.to_be_bytes());
    u64be(&mut b, MAX_FILE);
    u32be(&mut b, MAX_PLAIN);
    b
}
fn parse_claim(b: &[u8], initiator: bool, local_id: &str, remote_static: &[u8]) -> Result<Peer> {
    let mut d = Decoder::new(b);
    ensure!(
        d.byte()? == 4 && d.byte()? == initiator as u8,
        "invalid peer role/version"
    );
    let id = String::from_utf8(d.short(36)?)?;
    ensure!(uuid(&id, true) && id != local_id, "invalid peer UUID");
    let name = String::from_utf8(d.short(128)?)?;
    nickname(&name)?;
    let root = d.short(256)?;
    validate_root(&root)?;
    ensure!(
        d.bytes(32)? == remote_static,
        "Noise static key does not match claim"
    );
    let caps = d.int()?;
    ensure!(
        caps & 3 == 3 && caps & !15 == 0 && d.long()? == MAX_FILE && d.int()? as usize == MAX_PLAIN,
        "unsupported NIM4 capabilities/limits"
    );
    d.done()?;
    Ok(Peer {
        id,
        name,
        root: STANDARD.encode(root),
        remembered: false,
        verified: false,
        endpoint: String::new(),
    })
}
pub struct Established {
    pub stream: TcpStream,
    pub send: Cipher,
    pub receive: Cipher,
    pub peer: Peer,
    pub generation: String,
}
pub fn establish(
    mut stream: TcpStream,
    initiator: bool,
    identity: &Identity,
) -> Result<Established> {
    // Windows accepted sockets may inherit the listener's nonblocking mode.
    // The bounded handshake/read_exact implementation requires blocking I/O.
    stream.set_nonblocking(false)?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(std::time::Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(std::time::Duration::from_secs(15)))?;
    let builder = snow::Builder::new(SUITE.parse()?);
    let kp = builder.generate_keypair()?;
    let private = Zeroizing::new(kp.private);
    let local = claim(identity, initiator, &kp.public);
    let builder = builder.local_private_key(&private)?.prologue(PROLOGUE)?;
    let mut h = if initiator {
        builder.build_initiator()?
    } else {
        builder.build_responder()?
    };
    let remote = if initiator {
        send_handshake(&mut stream, &mut h, &[])?;
        let r = receive_handshake(&mut stream, &mut h)?;
        send_handshake(&mut stream, &mut h, &local)?;
        r
    } else {
        ensure!(
            receive_handshake(&mut stream, &mut h)?.is_empty(),
            "nonempty initial Noise message"
        );
        send_handshake(&mut stream, &mut h, &local)?;
        receive_handshake(&mut stream, &mut h)?
    };
    ensure!(h.is_handshake_finished(), "incomplete Noise handshake");
    let peer = parse_claim(
        &remote,
        initiator,
        &identity.id,
        h.get_remote_static().context("missing remote static")?,
    )?;
    let (first, second) = if initiator {
        (&local, &remote)
    } else {
        (&remote, &local)
    };
    let proof = binding(
        b"wozai-root-proof-v4\0",
        first,
        second,
        h.get_handshake_hash(),
    );
    let generation = hex::encode(Sha256::digest(binding(
        b"wozai-connection-v4\0",
        first,
        second,
        h.get_handshake_hash(),
    )));
    let (a, b) = h.dangerously_get_raw_split();
    let (send_key, recv_key) = if initiator { (a, b) } else { (b, a) };
    let mut send = Cipher::new(send_key);
    let mut receive = Cipher::new(recv_key);
    drop(h);
    let mut own_proof = proof.clone();
    own_proof.push((!initiator) as u8);
    let sig: Signature = identity.key.sign(&own_proof);
    let encrypted = send.encrypt(2, sig.to_der().as_bytes())?;
    if initiator {
        stream.write_all(&encrypted)?;
    }
    let (head, bytes) = read_envelope(&mut stream, 2, 96)?;
    let signature = receive.decrypt(&head, &bytes)?;
    ensure!(signature.len() <= 80, "oversized root proof");
    let mut remote_proof = proof;
    remote_proof.push(initiator as u8);
    validate_root(&STANDARD.decode(&peer.root)?)?
        .verify(&remote_proof, &Signature::from_der(&signature)?)
        .context("invalid persistent root proof")?;
    if !initiator {
        stream.write_all(&encrypted)?;
    }
    stream.set_read_timeout(None)?;
    Ok(Established {
        stream,
        send,
        receive,
        peer,
        generation,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn aad_replay_and_nonce_exhaustion() {
        let mut a = Cipher::new([7; 32]);
        let mut b = Cipher::new([7; 32]);
        let v = a.encrypt(3, b"hello").unwrap();
        let h: [u8; 10] = v[..10].try_into().unwrap();
        assert_eq!(b.decrypt(&h, &v[10..]).unwrap(), b"hello");
        assert!(b.decrypt(&h, &v[10..]).is_err());
        let mut b = Cipher::new([7; 32]);
        let mut bad = h;
        bad[9] = 2;
        assert!(b.decrypt(&bad, &v[10..]).is_err());
        a.nonce = u64::MAX;
        assert!(a.encrypt(3, b"").is_err());
    }
    #[test]
    fn rejects_oversized_envelope_before_payload() {
        let mut input = std::io::Cursor::new(u32::MAX.to_be_bytes());
        assert!(read_envelope(&mut input, 3, MAX_PLAIN + 16).is_err());
    }
    #[test]
    fn public_noise_vector() {
        let v: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/noise-candidate/vectors-selected.json"
        ))
        .unwrap();
        let val = |s: &str| hex::decode(v[s].as_str().unwrap()).unwrap();
        let a = val("init_static");
        let b = val("resp_static");
        let ae = val("init_ephemeral");
        let be = val("resp_ephemeral");
        let p = val("init_prologue");
        let mut a = snow::Builder::new(SUITE.parse().unwrap())
            .local_private_key(&a)
            .unwrap()
            .fixed_ephemeral_key_for_testing_only(&ae)
            .prologue(&p)
            .unwrap()
            .build_initiator()
            .unwrap();
        let mut b = snow::Builder::new(SUITE.parse().unwrap())
            .local_private_key(&b)
            .unwrap()
            .fixed_ephemeral_key_for_testing_only(&be)
            .prologue(&p)
            .unwrap()
            .build_responder()
            .unwrap();
        let msgs = v["messages"].as_array().unwrap();
        let mut out = [0; 2048];
        let mut plain = [0; 2048];
        for (i, m) in msgs[..3].iter().enumerate() {
            let payload = hex::decode(m["payload"].as_str().unwrap()).unwrap();
            let (s, r) = if i % 2 == 0 {
                (&mut a, &mut b)
            } else {
                (&mut b, &mut a)
            };
            let n = s.write_message(&payload, &mut out).unwrap();
            assert_eq!(hex::encode(&out[..n]), m["ciphertext"].as_str().unwrap());
            let k = r.read_message(&out[..n], &mut plain).unwrap();
            assert_eq!(&plain[..k], payload);
        }
        assert_eq!(
            hex::encode(a.get_handshake_hash()),
            v["handshake_hash"].as_str().unwrap()
        );
        let mut a = a.into_transport_mode().unwrap();
        let mut b = b.into_transport_mode().unwrap();
        for (i, m) in msgs[3..].iter().enumerate() {
            let payload = hex::decode(m["payload"].as_str().unwrap()).unwrap();
            let (s, r) = if i % 2 == 0 {
                (&mut b, &mut a)
            } else {
                (&mut a, &mut b)
            };
            let n = s.write_message(&payload, &mut out).unwrap();
            assert_eq!(hex::encode(&out[..n]), m["ciphertext"].as_str().unwrap());
            let k = r.read_message(&out[..n], &mut plain).unwrap();
            assert_eq!(&plain[..k], payload);
        }
    }
}

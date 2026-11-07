//! Experimental public-message format. No NIM4 wire or key material is used.
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;

pub const MAX_BODY: usize = 2048;
pub const MAX_HOPS: usize = 8;
pub const MAX_FRAME: usize = 32768;
pub const MAX_LIFETIME: u64 = 600;
pub type Check<T> = Result<T, &'static str>;

pub struct Identity(SigningKey);
impl Identity {
    pub fn generate() -> Self {
        Self(SigningKey::generate(&mut OsRng))
    }
    pub fn from_seed(seed: [u8; 32]) -> Self {
        Self(SigningKey::from_bytes(&seed))
    }
    pub fn seed(&self) -> [u8; 32] {
        self.0.to_bytes()
    }
    pub fn key(&self) -> String {
        hex::encode(self.0.verifying_key().as_bytes())
    }
    pub fn sign(&self, bytes: &[u8]) -> String {
        hex::encode(self.0.sign(bytes).to_bytes())
    }
}
pub fn decode<const N: usize>(s: &str) -> Check<[u8; N]> {
    if s.len() != N * 2
        || s.bytes()
            .any(|c| !c.is_ascii_digit() && !(b'a'..=b'f').contains(&c))
    {
        return Err("hex_format");
    }
    let mut out = [0; N];
    hex::decode_to_slice(s, &mut out).map_err(|_| "hex_format")?;
    Ok(out)
}
pub fn verify(key: &str, bytes: &[u8], signature: &str) -> Check<()> {
    let public = VerifyingKey::from_bytes(&decode::<32>(key)?).map_err(|_| "public_key")?;
    public
        .verify_strict(bytes, &Signature::from_bytes(&decode::<64>(signature)?))
        .map_err(|_| "signature")
}
pub fn hash(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}
pub fn nonce() -> String {
    let mut n = [0; 16];
    OsRng.fill_bytes(&mut n);
    hex::encode(n)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Envelope {
    pub version: u8,
    pub kind: u8,
    pub id: String,
    pub origin: String,
    pub nonce: String,
    pub created: u64,
    pub expires: u64,
    pub max_hops: u8,
    pub body: String,
    pub signature: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Hop {
    pub to: String,
    pub signature: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Packet {
    pub message: Envelope,
    pub route: Vec<Hop>,
}
impl Envelope {
    fn canonical(&self) -> Check<Vec<u8>> {
        let mut b = b"NearbyMesh/public/v1\0".to_vec();
        b.extend([self.version, self.kind]);
        b.extend(decode::<32>(&self.origin)?);
        b.extend(decode::<16>(&self.nonce)?);
        b.extend(self.created.to_be_bytes());
        b.extend(self.expires.to_be_bytes());
        b.push(self.max_hops);
        b.extend((self.body.len() as u32).to_be_bytes());
        b.extend(self.body.as_bytes());
        Ok(b)
    }
    fn bounds(&self) -> Check<()> {
        if self.version != 1 || self.kind != 1 {
            return Err("version_or_kind");
        }
        if self.body.is_empty() || self.body.len() > MAX_BODY {
            return Err("body_bound");
        }
        if self.max_hops == 0 || self.max_hops as usize > MAX_HOPS {
            return Err("hop_bound");
        }
        if self.expires <= self.created || self.expires - self.created > MAX_LIFETIME {
            return Err("lifetime_bound");
        }
        Ok(())
    }
    pub fn new(
        identity: &Identity,
        body: &str,
        created: u64,
        ttl: u64,
        max_hops: u8,
    ) -> Check<Self> {
        let mut m = Self {
            version: 1,
            kind: 1,
            id: String::new(),
            origin: identity.key(),
            nonce: nonce(),
            created,
            expires: created.checked_add(ttl).ok_or("time_overflow")?,
            max_hops,
            body: body.into(),
            signature: String::new(),
        };
        m.bounds()?;
        let bytes = m.canonical()?;
        m.id = hex::encode(hash(&bytes));
        m.signature = identity.sign(&bytes);
        Ok(m)
    }
    pub fn validate(&self, now: u64) -> Check<()> {
        self.bounds()?;
        if self.created > now.saturating_add(30) {
            return Err("future_clock");
        }
        if self.expires <= now {
            return Err("expired");
        }
        let canonical = self.canonical()?;
        if decode::<32>(&self.id)? != hash(&canonical) {
            return Err("message_id");
        }
        verify(&self.origin, &canonical, &self.signature)
    }
}
impl Packet {
    fn root(&self) -> Check<[u8; 32]> {
        let mut b = b"NearbyMesh/route-root/v1\0".to_vec();
        b.extend(decode::<32>(&self.message.id)?);
        b.extend(decode::<64>(&self.message.signature)?);
        Ok(hash(&b))
    }
    fn hop_bytes(previous: &[u8; 32], to: &str) -> Check<Vec<u8>> {
        let mut b = b"NearbyMesh/hop/v1\0".to_vec();
        b.extend(previous);
        b.extend(decode::<32>(to)?);
        Ok(b)
    }
    fn chain(&self) -> Check<([u8; 32], String, BTreeSet<String>)> {
        if self.route.len() > self.message.max_hops as usize || self.route.len() > MAX_HOPS {
            return Err("hop_bound");
        }
        let mut previous = self.root()?;
        let mut from = self.message.origin.clone();
        let mut visited = BTreeSet::from([from.clone()]);
        for hop in &self.route {
            if !visited.insert(hop.to.clone()) {
                return Err("route_loop");
            }
            let bytes = Self::hop_bytes(&previous, &hop.to)?;
            verify(&from, &bytes, &hop.signature)?;
            let mut proof = bytes;
            proof.extend(decode::<64>(&hop.signature)?);
            previous = hash(&proof);
            from = hop.to.clone();
        }
        Ok((previous, from, visited))
    }
    pub fn append(&self, identity: &Identity, to: &str) -> Check<Self> {
        self.message.bounds()?;
        if self.route.len() >= self.message.max_hops as usize {
            return Err("hop_exhausted");
        }
        let (previous, from, visited) = self.chain()?;
        if from != identity.key() || visited.contains(to) {
            return Err("route_owner_or_loop");
        }
        let mut packet = self.clone();
        packet.route.push(Hop {
            to: to.into(),
            signature: identity.sign(&Self::hop_bytes(&previous, to)?),
        });
        Ok(packet)
    }
    pub fn validate(&self, receiver: &str, neighbor: &str, now: u64) -> Check<()> {
        self.message.validate(now)?;
        let (_, to, _) = self.chain()?;
        if self.route.is_empty() || to != receiver {
            return Err("route_receiver");
        }
        let from = if self.route.len() == 1 {
            &self.message.origin
        } else {
            &self.route[self.route.len() - 2].to
        };
        if from != neighbor {
            return Err("route_neighbor");
        }
        Ok(())
    }
    pub fn path(&self) -> Vec<String> {
        std::iter::once(self.message.origin.clone())
            .chain(self.route.iter().map(|h| h.to.clone()))
            .collect()
    }
}

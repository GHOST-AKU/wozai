//! Bounded best-effort flooding, independent of sockets and platform lifecycles.
use crate::protocol::*;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone)]
pub struct Limits {
    pub seen: usize,
    pub pending: usize,
    pub per_origin: usize,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            seen: 1024,
            pending: 64,
            per_origin: 16,
        }
    }
}
struct Pending {
    packet: Packet,
    offered: BTreeSet<String>,
    attempts: BTreeMap<String, u8>,
    local: bool,
}
pub struct Core {
    pub relay: bool,
    pub seen: BTreeMap<String, u64>,
    key: String,
    limits: Limits,
    pending: BTreeMap<String, Pending>,
    origins: BTreeMap<String, usize>,
    window: u64,
    admitted: usize,
    high_water: u64,
}
impl Core {
    pub fn new(key: String, relay: bool, limits: Limits) -> Self {
        Self {
            relay,
            seen: BTreeMap::new(),
            key,
            limits,
            pending: BTreeMap::new(),
            origins: BTreeMap::new(),
            window: 0,
            admitted: 0,
            high_water: 0,
        }
    }
    fn admit(&mut self, packet: Packet, local: bool, now: u64) -> Check<bool> {
        let now = now.max(self.high_water);
        self.tick(now);
        let id = &packet.message.id;
        if self.seen.contains_key(id) {
            return Ok(false);
        }
        if self.seen.len() >= self.limits.seen {
            return Err("seen_full");
        }
        let keep = local || self.relay;
        if keep && self.pending.len() >= self.limits.pending {
            return Err("queue_full");
        }
        let count = self
            .origins
            .get(&packet.message.origin)
            .copied()
            .unwrap_or(0);
        if count >= self.limits.per_origin {
            return Err("origin_rate");
        }
        if self.admitted >= 128
            || (!self.origins.contains_key(&packet.message.origin) && self.origins.len() >= 64)
        {
            return Err("global_rate");
        }
        self.admitted += 1;
        self.origins
            .insert(packet.message.origin.clone(), count + 1);
        self.seen.insert(id.clone(), packet.message.expires);
        if keep {
            self.pending.insert(
                id.clone(),
                Pending {
                    packet,
                    offered: BTreeSet::new(),
                    attempts: BTreeMap::new(),
                    local,
                },
            );
        }
        Ok(true)
    }
    pub fn receive(&mut self, packet: Packet, neighbor: &str, now: u64) -> Check<bool> {
        packet.validate(&self.key, neighbor, now.max(self.high_water))?;
        self.admit(packet, false, now)
    }
    pub fn originate(
        &mut self,
        identity: &Identity,
        body: &str,
        now: u64,
        ttl: u64,
        hops: u8,
    ) -> Check<String> {
        if identity.key() != self.key {
            return Err("local_identity");
        }
        let message = Envelope::new(identity, body, now.max(self.high_water), ttl, hops)?;
        let id = message.id.clone();
        self.admit(
            Packet {
                message,
                route: vec![],
            },
            true,
            now,
        )?;
        Ok(id)
    }
    pub fn next_for(&mut self, identity: &Identity, to: &str, now: u64) -> Option<Packet> {
        for pending in self.pending.values_mut() {
            if pending.packet.message.expires <= now.max(self.high_water)
                || (!pending.local && !self.relay)
                || pending.offered.contains(to)
            {
                continue;
            }
            if pending.attempts.get(to).copied().unwrap_or(0) >= 3
                || (!pending.attempts.contains_key(to) && pending.attempts.len() >= 8)
            {
                continue;
            }
            if let Ok(packet) = pending.packet.append(identity, to) {
                *pending.attempts.entry(to.into()).or_default() += 1;
                pending.offered.insert(to.into());
                return Some(packet);
            }
        }
        None
    }
    pub fn disconnected(&mut self, peer: &str) {
        for pending in self.pending.values_mut() {
            pending.offered.remove(peer);
        }
    }
    pub fn clock_floor(&self) -> u64 {
        self.high_water
    }
    pub fn pending_len(&self) -> usize {
        self.pending.len()
    }
    pub fn tick(&mut self, now: u64) -> Vec<String> {
        self.high_water = self.high_water.max(now);
        let now = self.high_water;
        self.seen.retain(|_, expires| *expires > now);
        let expired: Vec<_> = self
            .pending
            .iter()
            .filter(|(_, p)| p.packet.message.expires <= now)
            .map(|(id, _)| id.clone())
            .collect();
        for id in &expired {
            self.pending.remove(id);
        }
        if self.window != now / 60 {
            self.window = now / 60;
            self.origins.clear();
            self.admitted = 0;
        }
        expired
    }
    pub fn restore_seen(&mut self, seen: BTreeMap<String, u64>, now: u64) -> Check<()> {
        if seen.len() > self.limits.seen {
            return Err("seen_full");
        }
        for (id, expires) in &seen {
            decode::<32>(id)?;
            if *expires > now.saturating_add(MAX_LIFETIME + 30) {
                return Err("stored_expiry");
            }
        }
        self.seen = seen;
        self.tick(now);
        Ok(())
    }
}

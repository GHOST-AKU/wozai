use crate::wire::{EXTENDED_TEXT, LEGACY_TEXT};
use sha2::{Digest, Sha256};

/// PONG tokens are ignored by old NIM4 peers and bound to this authenticated generation.
pub struct TextCapacity {
    request: u64,
    response: u64,
    started: bool,
    replied: bool,
    pub incoming: usize,
    pub outgoing: usize,
}
impl TextCapacity {
    pub fn new(generation: &str) -> Self {
        let token = |kind: &str, prefix: u64| {
            Sha256::digest(format!("wozai-text-capability-v1\0{kind}\0{generation}").as_bytes())
                [..6]
                .iter()
                .fold(prefix, |value, byte| (value << 8) | u64::from(*byte))
        };
        Self {
            request: token("request", 0x574f),
            response: token("response", 0x5752),
            started: false,
            replied: false,
            incoming: LEGACY_TEXT,
            outgoing: LEGACY_TEXT,
        }
    }
    pub fn start(&mut self) -> Option<u64> {
        if self.started {
            return None;
        }
        self.started = true;
        Some(self.request)
    }
    pub fn receive(&mut self, time: u64) -> Option<u64> {
        if time == self.request && !self.replied {
            self.replied = true;
            self.incoming = EXTENDED_TEXT;
            return Some(self.response);
        }
        if time == self.response && self.started {
            self.outgoing = EXTENDED_TEXT;
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn vectors_and_generation_scoping() {
        let mut a = TextCapacity::new(&"a".repeat(64));
        let mut b = TextCapacity::new(&"a".repeat(64));
        assert_eq!(a.request, 0x574f57812e4e8cd3);
        assert_eq!(a.response, 0x575213fb17e249ae);
        a.start();
        let stale = TextCapacity::new(&"b".repeat(64));
        a.receive(stale.response);
        assert_eq!(a.outgoing, LEGACY_TEXT);
        assert_eq!(b.receive(a.request), Some(a.response));
        assert_eq!(b.incoming, EXTENDED_TEXT);
        assert_eq!(b.outgoing, LEGACY_TEXT);
        assert_eq!(b.receive(a.request), None);
        a.receive(b.response);
        assert_eq!(a.outgoing, EXTENDED_TEXT);
        assert_eq!(TextCapacity::new(&"a".repeat(64)).outgoing, LEGACY_TEXT);
    }
}

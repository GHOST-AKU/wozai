//! Candidate addresses are untrusted discovery hints, never NIM4 trust decisions.
use std::{
    collections::BTreeMap,
    net::{SocketAddr, TcpStream},
    sync::mpsc::{self, Receiver, SyncSender},
    thread,
    time::{Duration, Instant},
};
pub const MAX_CANDIDATES: usize = 16;
struct Candidate {
    next: Instant,
    failures: u32,
    pending: bool,
    peer: Option<String>,
    expires: Option<Instant>,
}
pub struct Manager {
    candidates: BTreeMap<SocketAddr, Candidate>,
    sender: SyncSender<(SocketAddr, std::io::Result<TcpStream>)>,
    receiver: Receiver<(SocketAddr, std::io::Result<TcpStream>)>,
}
impl Default for Manager {
    fn default() -> Self {
        let (sender, receiver) = mpsc::sync_channel(4);
        Self {
            candidates: BTreeMap::new(),
            sender,
            receiver,
        }
    }
}
impl Manager {
    pub fn add(&mut self, address: SocketAddr) -> bool {
        if self.candidates.contains_key(&address) {
            return true;
        }
        if self.candidates.len() >= MAX_CANDIDATES {
            return false;
        }
        self.candidates.insert(
            address,
            Candidate {
                next: Instant::now(),
                failures: 0,
                pending: false,
                peer: None,
                expires: None,
            },
        );
        true
    }
    pub fn add_discovered(&mut self, address: SocketAddr) -> bool {
        let existed = self.candidates.contains_key(&address);
        if !self.add(address) {
            return false;
        }
        let candidate = self
            .candidates
            .get_mut(&address)
            .expect("candidate inserted");
        if !existed || candidate.expires.is_some() {
            candidate.expires = Some(Instant::now() + Duration::from_secs(90));
        }
        true
    }
    pub fn authenticated(&mut self, address: SocketAddr, peer: String) {
        if let Some(candidate) = self.candidates.get_mut(&address) {
            candidate.peer = Some(peer);
        }
    }
    pub fn poll(
        &mut self,
        active: &[SocketAddr],
        active_keys: &[String],
        slots: usize,
    ) -> Vec<TcpStream> {
        let mut connected = Vec::new();
        while let Ok((address, result)) = self.receiver.try_recv() {
            if let Some(candidate) = self.candidates.get_mut(&address) {
                candidate.pending = false;
                match result {
                    Ok(stream) => {
                        candidate.failures = 0;
                        candidate.next = Instant::now() + Duration::from_secs(2);
                        connected.push(stream);
                    }
                    Err(_) => {
                        candidate.failures = candidate.failures.saturating_add(1);
                        candidate.next = Instant::now()
                            + Duration::from_millis(
                                (250u64 << candidate.failures.min(5)) + rand::random::<u8>() as u64,
                            );
                    }
                }
            }
        }
        self.candidates.retain(|address, candidate| {
            candidate.pending
                || active.contains(address)
                || candidate
                    .peer
                    .as_ref()
                    .is_some_and(|key| active_keys.contains(key))
                || candidate
                    .expires
                    .is_none_or(|deadline| deadline > Instant::now())
        });
        let pending = self.candidates.values().filter(|c| c.pending).count();
        let available = slots
            .saturating_sub(connected.len())
            .min(4usize.saturating_sub(pending));
        let mut started = 0;
        for (address, candidate) in &mut self.candidates {
            if started >= available {
                break;
            }
            if candidate.pending
                || candidate.next > Instant::now()
                || active.contains(address)
                || candidate
                    .peer
                    .as_ref()
                    .is_some_and(|key| active_keys.contains(key))
            {
                continue;
            }
            let address = *address;
            let sender = self.sender.clone();
            candidate.pending = true;
            started += 1;
            thread::spawn(move || {
                let result = TcpStream::connect_timeout(&address, Duration::from_millis(500));
                let _ = sender.send((address, result));
            });
        }
        connected
    }
    pub fn len(&self) -> usize {
        self.candidates.len()
    }
    pub fn is_empty(&self) -> bool {
        self.candidates.is_empty()
    }
}

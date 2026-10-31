//! Length-bounded, nonblocking TCP framing. Native adapters can carry the same frames.
use crate::protocol::{MAX_FRAME, Packet};
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    io::{ErrorKind, Read, Write},
    net::{SocketAddr, TcpStream},
    time::{Duration, Instant},
};

pub const MAX_CONNECTIONS: usize = 8;
pub const MAX_TX_QUEUE: usize = 8;
#[derive(Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Wire {
    Hello {
        version: u8,
        key: String,
        challenge: String,
    },
    Auth {
        signature: String,
    },
    Data {
        packet: Packet,
    },
    Ping,
}
pub struct Outbound {
    bytes: Vec<u8>,
    offset: usize,
    pub id: Option<String>,
    pub expires: u64,
    pub transit: bool,
    auth: bool,
}
pub struct Link {
    pub stream: TcpStream,
    pub address: SocketAddr,
    pub outbound: bool,
    pub peer: Option<String>,
    pub hello: Option<(String, String)>,
    pub authenticated: bool,
    pub auth_sent: bool,
    pub challenge: String,
    rx: Vec<u8>,
    expected: Option<usize>,
    closed: bool,
    pub tx: VecDeque<Outbound>,
    pub born: Instant,
    pub last_rx: Instant,
    pub last_ping: Instant,
    window: Instant,
    frames: usize,
    rx_budget: usize,
    pub bytes_rx: u64,
    pub bytes_tx: u64,
}
impl Link {
    pub fn new(stream: TcpStream, outbound: bool, challenge: String) -> Result<Self> {
        stream.set_nonblocking(true)?;
        stream.set_nodelay(true)?;
        Ok(Self {
            address: stream.peer_addr()?,
            stream,
            outbound,
            peer: None,
            hello: None,
            authenticated: false,
            auth_sent: false,
            challenge,
            rx: Vec::new(),
            expected: None,
            closed: false,
            tx: VecDeque::new(),
            born: Instant::now(),
            last_rx: Instant::now(),
            last_ping: Instant::now(),
            window: Instant::now(),
            frames: 0,
            rx_budget: 0,
            bytes_rx: 0,
            bytes_tx: 0,
        })
    }
    pub fn queue(
        &mut self,
        wire: &Wire,
        id: Option<String>,
        expires: u64,
        transit: bool,
    ) -> Result<()> {
        if self.tx.len() >= MAX_TX_QUEUE {
            bail!("tx_queue_full");
        }
        let body = serde_json::to_vec(wire)?;
        if body.is_empty() || body.len() > MAX_FRAME {
            bail!("frame_bound");
        }
        let mut bytes = (body.len() as u32).to_be_bytes().to_vec();
        bytes.extend(body);
        self.tx.push_back(Outbound {
            bytes,
            offset: 0,
            id,
            expires,
            transit,
            auth: matches!(wire, Wire::Auth { .. }),
        });
        Ok(())
    }
    pub fn read(&mut self) -> Result<Vec<Wire>> {
        if self.closed {
            bail!("connection_closed");
        }
        if self.window.elapsed() >= Duration::from_secs(1) {
            self.window = Instant::now();
            self.frames = 0;
            self.rx_budget = 0;
        }
        let mut out = Vec::new();
        for _ in 0..64 {
            let target = self.expected.unwrap_or(4);
            if self.rx.len() == target {
                if self.expected.is_none() {
                    let len = u32::from_be_bytes(self.rx[..4].try_into()?) as usize;
                    if len == 0 || len > MAX_FRAME {
                        bail!("frame_bound");
                    }
                    self.expected = Some(len);
                    self.rx.clear();
                } else {
                    self.frames += 1;
                    if self.frames > 32 {
                        bail!("frame_rate");
                    }
                    out.push(serde_json::from_slice(&self.rx)?);
                    self.expected = None;
                    self.rx.clear();
                    if out.len() >= 8 {
                        break;
                    }
                }
                continue;
            }
            let mut buffer = [0u8; 4096];
            let wanted = (target - self.rx.len()).min(buffer.len());
            match self.stream.read(&mut buffer[..wanted]) {
                Ok(0) if !out.is_empty() => {
                    self.closed = true;
                    break;
                }
                Ok(0) => bail!("connection_closed"),
                Ok(n) => {
                    self.rx.extend_from_slice(&buffer[..n]);
                    self.bytes_rx += n as u64;
                    self.rx_budget += n;
                    self.last_rx = Instant::now();
                    if self.rx_budget > 131072 {
                        bail!("byte_rate");
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(_) if !out.is_empty() => {
                    self.closed = true;
                    break;
                }
                Err(e) => return Err(e.into()),
            }
        }
        Ok(out)
    }
    pub fn write(&mut self, now: u64, relay: bool) -> Result<Vec<String>> {
        let mut handed = Vec::new();
        let mut budget = 65536;
        while let Some(front) = self.tx.front_mut() {
            if front.id.is_some() && (front.expires <= now || (front.transit && !relay)) {
                if front.offset > 0 {
                    bail!("partial_frame_cancelled");
                }
                self.tx.pop_front();
                continue;
            }
            let size = (front.bytes.len() - front.offset).min(budget);
            if size == 0 {
                break;
            }
            match self
                .stream
                .write(&front.bytes[front.offset..front.offset + size])
            {
                Ok(0) => bail!("write_closed"),
                Ok(n) => {
                    front.offset += n;
                    budget -= n;
                    self.bytes_tx += n as u64;
                    if front.offset == front.bytes.len() {
                        if let Some(frame) = self.tx.pop_front() {
                            if frame.auth {
                                self.auth_sent = true;
                            }
                            if let Some(id) = frame.id {
                                handed.push(id);
                            }
                        }
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(e) => return Err(e.into()),
            }
        }
        Ok(handed)
    }
    pub fn timed_out(&self) -> bool {
        ((!self.authenticated || !self.auth_sent) && self.born.elapsed() > Duration::from_secs(3))
            || self.last_rx.elapsed() > Duration::from_secs(15)
    }
}

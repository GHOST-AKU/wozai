//! Production NIM4 framing. Lengths are checked before allocation; no legacy fallback.
use anyhow::{Result, bail, ensure};
use std::io::{Cursor, Read};

pub const MAGIC: u32 = 0x4e494d34;
pub const MAX_PLAIN: usize = 48 * 1024;
pub const MAX_FILE: u64 = 10_737_418_240;
pub const BLOCK: u64 = 1024 * 1024;
pub const CHUNK: usize = 32768;
pub const READY: u8 = 2;
pub const TEXT: u8 = 3;
pub const ACK: u8 = 4;
pub const BYE: u8 = 5;
pub const PING: u8 = 6;
pub const PONG: u8 = 7;
pub const FILE: u8 = 16;

pub fn uuid(s: &str, lower: bool) -> bool {
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit() && (!lower || !b.is_ascii_uppercase())
            }
        })
}
pub fn java_blank(s: &str) -> bool {
    s.trim_matches(|c: char| c <= '\u{20}').is_empty()
}
pub fn nickname(s: &str) -> Result<()> {
    ensure!(
        !java_blank(s)
            && s.chars().count() <= 32
            && s.len() <= 128
            && !s.chars().any(char::is_control),
        "invalid nickname"
    );
    Ok(())
}
pub fn text_valid(s: &str) -> Result<()> {
    ensure!(
        !java_blank(s) && s.len() <= 8192,
        "text must contain 1–8192 UTF-8 bytes"
    );
    Ok(())
}
pub fn u32be(out: &mut Vec<u8>, n: usize) {
    out.extend((n as u32).to_be_bytes());
}
pub fn u64be(out: &mut Vec<u8>, n: u64) {
    out.extend(n.to_be_bytes());
}
pub fn string(out: &mut Vec<u8>, s: &str) {
    u32be(out, s.len());
    out.extend(s.as_bytes());
}
pub fn short(out: &mut Vec<u8>, b: &[u8]) {
    out.extend((b.len() as u16).to_be_bytes());
    out.extend(b);
}

pub struct Decoder<'a>(pub Cursor<&'a [u8]>);
impl<'a> Decoder<'a> {
    pub fn new(b: &'a [u8]) -> Self {
        Self(Cursor::new(b))
    }
    pub fn left(&self) -> usize {
        self.0.get_ref().len() - self.0.position() as usize
    }
    pub fn bytes(&mut self, n: usize) -> Result<Vec<u8>> {
        ensure!(n <= self.left(), "truncated packet");
        let mut b = vec![0; n];
        self.0.read_exact(&mut b)?;
        Ok(b)
    }
    pub fn byte(&mut self) -> Result<u8> {
        Ok(self.bytes(1)?[0])
    }
    pub fn boolean(&mut self) -> Result<bool> {
        match self.byte()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => bail!("invalid boolean"),
        }
    }
    pub fn int(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.bytes(4)?.try_into().unwrap()))
    }
    pub fn long(&mut self) -> Result<u64> {
        let n = u64::from_be_bytes(self.bytes(8)?.try_into().unwrap());
        ensure!(n <= i64::MAX as u64, "negative integer");
        Ok(n)
    }
    pub fn str(&mut self, max: usize) -> Result<String> {
        let n = self.int()? as usize;
        ensure!(n <= max, "oversized string");
        Ok(String::from_utf8(self.bytes(n)?)?)
    }
    pub fn short(&mut self, max: usize) -> Result<Vec<u8>> {
        let n = u16::from_be_bytes(self.bytes(2)?.try_into().unwrap()) as usize;
        ensure!(n <= max, "oversized field");
        self.bytes(n)
    }
    pub fn done(&self) -> Result<()> {
        ensure!(self.left() == 0, "trailing packet bytes");
        Ok(())
    }
}
#[derive(Clone, Debug)]
pub struct Frame {
    pub kind: u8,
    pub time: u64,
    pub id: String,
    pub body: String,
    pub data: Vec<u8>,
}
impl Frame {
    pub fn control(kind: u8) -> Self {
        Self {
            kind,
            time: 0,
            id: String::new(),
            body: String::new(),
            data: vec![],
        }
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(self.time <= i64::MAX as u64, "invalid timestamp");
        match self.kind {
            TEXT => {
                ensure!(uuid(&self.id, false), "invalid message ID");
                text_valid(&self.body)?;
            }
            ACK => ensure!(
                uuid(&self.id, false) && self.body.is_empty(),
                "invalid saved receipt"
            ),
            READY | BYE | PING | PONG => ensure!(
                self.id.is_empty() && self.body.is_empty(),
                "invalid control"
            ),
            FILE => {
                ensure!(
                    self.id.is_empty() && self.body.is_empty() && self.time == 0,
                    "invalid file metadata"
                );
                Packet::decode(&self.data)?;
            }
            _ => bail!("unsupported NIM4 record type {}", self.kind),
        }
        ensure!(
            self.kind == FILE || self.data.is_empty(),
            "unexpected binary data"
        );
        Ok(())
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut b = vec![];
        b.extend(MAGIC.to_be_bytes());
        b.extend([4, self.kind]);
        u64be(&mut b, self.time);
        string(&mut b, &self.id);
        string(&mut b, &self.body);
        if self.kind == FILE {
            u32be(&mut b, self.data.len());
            b.extend(&self.data);
        }
        ensure!(b.len() <= MAX_PLAIN, "record too large");
        Ok(b)
    }
    pub fn decode(b: &[u8]) -> Result<Self> {
        ensure!((22..=MAX_PLAIN).contains(&b.len()), "invalid record size");
        let mut d = Decoder::new(b);
        ensure!(
            d.int()? == MAGIC && d.byte()? == 4,
            "unsupported record version"
        );
        let mut f = Self {
            kind: d.byte()?,
            time: d.long()?,
            id: d.str(64)?,
            body: d.str(8192)?,
            data: vec![],
        };
        if f.kind == FILE {
            let n = d.int()? as usize;
            ensure!(n <= 40960, "oversized file packet");
            f.data = d.bytes(n)?;
        }
        d.done()?;
        f.validate()?;
        Ok(f)
    }
}
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct Info {
    pub id: String,
    pub name: String,
    pub mime: String,
    pub size: u64,
    pub time: u64,
}
impl Info {
    fn validate(&self) -> Result<()> {
        ensure!(
            uuid(&self.id, true)
                && !java_blank(&self.name)
                && self.name.chars().count() <= 255
                && self.name.len() <= 1024
                && !self.name.chars().any(char::is_control),
            "invalid filename"
        );
        ensure!(
            !self.mime.is_empty()
                && self.mime.len() <= 127
                && self
                    .mime
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"!#$&^_.+/-".contains(&b)),
            "invalid MIME type"
        );
        ensure!(
            self.size <= MAX_FILE && self.time <= i64::MAX as u64,
            "invalid file size/time"
        );
        Ok(())
    }
    fn encode(&self, b: &mut Vec<u8>) {
        string(b, &self.id);
        string(b, &self.name);
        string(b, &self.mime);
        u64be(b, self.size);
        u64be(b, self.time);
        b.push(0);
    }
    fn decode(d: &mut Decoder) -> Result<Self> {
        let i = Self {
            id: d.str(36)?,
            name: d.str(1024)?,
            mime: d.str(127)?,
            size: d.long()?,
            time: d.long()?,
        };
        ensure!(!d.boolean()?, "offer must not contain whole-file hash");
        i.validate()?;
        Ok(i)
    }
}
pub const OFFER: u8 = 0;
pub const ACCEPT: u8 = 1;
pub const DATA: u8 = 2;
pub const BLOCK_HASH: u8 = 3;
pub const CREDIT: u8 = 4;
pub const END: u8 = 5;
pub const COMPLETE: u8 = 6;
pub const CANCEL: u8 = 7;
pub const RESUME: u8 = 8;
pub const STATUS: u8 = 9;
pub const PAUSE: u8 = 10;
#[derive(Clone, Debug)]
pub struct Packet {
    pub kind: u8,
    pub sender: bool,
    pub id: String,
    pub source: String,
    pub connection: String,
    pub size: u64,
    pub offset: u64,
    pub window: u64,
    pub verified: u64,
    pub durable: u64,
    pub reason: String,
    pub info: Option<Info>,
    pub hash: Option<[u8; 32]>,
    pub data: Vec<u8>,
}
impl Packet {
    pub fn control(kind: u8, sender: bool, info: &Info, source: &str, connection: &str) -> Self {
        Self {
            kind,
            sender,
            id: info.id.clone(),
            source: source.into(),
            connection: connection.into(),
            size: info.size,
            offset: 0,
            window: 0,
            verified: 0,
            durable: 0,
            reason: String::new(),
            info: None,
            hash: None,
            data: vec![],
        }
    }
    pub fn reply(&self, kind: u8) -> Self {
        Self {
            kind,
            sender: !self.sender,
            id: self.id.clone(),
            source: self.source.clone(),
            connection: self.connection.clone(),
            size: self.size,
            offset: 0,
            window: 0,
            verified: 0,
            durable: 0,
            reason: String::new(),
            info: None,
            hash: None,
            data: vec![],
        }
    }
    pub fn validate(&self) -> Result<()> {
        let generation = uuid(&self.connection, true)
            || (self.connection.len() == 64
                && self
                    .connection
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()));
        ensure!(
            self.kind <= PAUSE && uuid(&self.id, true) && uuid(&self.source, true) && generation,
            "invalid transfer identity"
        );
        ensure!(
            self.size <= MAX_FILE
                && self.offset <= self.size
                && self.data.len() <= CHUNK
                && self.data.len() as u64 <= self.size - self.offset
                && self.window <= 8 * BLOCK,
            "invalid transfer range"
        );
        ensure!(
            self.durable <= self.verified
                && self.verified <= self.offset
                && (self.verified.is_multiple_of(BLOCK) || self.verified == self.size)
                && (self.durable.is_multiple_of(BLOCK) || self.durable == self.size),
            "invalid progress"
        );
        ensure!(
            matches!(self.kind, CREDIT | RESUME) || (self.verified == 0 && self.durable == 0),
            "unexpected progress"
        );
        ensure!(
            matches!(self.kind, BLOCK_HASH | END | COMPLETE | RESUME) == self.hash.is_some(),
            "invalid hash presence"
        );
        ensure!(
            (self.kind == DATA) != self.data.is_empty()
                && (self.kind == OFFER) == self.info.is_some(),
            "invalid payload presence"
        );
        ensure!(
            [
                "", "canceled", "failed", "busy", "paused", "rejected", "tooLarge"
            ]
            .contains(&self.reason.as_str())
                && (self.kind == CANCEL || self.reason.is_empty()),
            "invalid reason"
        );
        ensure!(
            matches!(self.kind, ACCEPT | CREDIT | RESUME) || self.window == 0,
            "unexpected window"
        );
        ensure!(
            self.kind != OFFER || self.offset == 0,
            "invalid offer offset"
        );
        ensure!(
            !matches!(self.kind, END | COMPLETE) || self.offset == self.size,
            "invalid completion offset"
        );
        ensure!(
            !matches!(self.kind, OFFER | DATA | BLOCK_HASH | END) || self.sender,
            "invalid sender direction"
        );
        ensure!(
            !matches!(self.kind, ACCEPT | CREDIT | COMPLETE) || !self.sender,
            "invalid receiver direction"
        );
        if let Some(i) = &self.info {
            i.validate()?;
            ensure!(
                i.id == self.id && i.size == self.size,
                "changed offer metadata"
            );
        }
        Ok(())
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut b = vec![0; 4];
        b.extend(0x46543033u32.to_be_bytes());
        b.extend([self.kind, self.sender as u8]);
        string(&mut b, &self.id);
        string(&mut b, &self.source);
        string(&mut b, &self.connection);
        for n in [
            self.size,
            self.offset,
            self.window,
            self.verified,
            self.durable,
        ] {
            u64be(&mut b, n);
        }
        string(&mut b, &self.reason);
        b.push(self.info.is_some() as u8);
        if let Some(i) = &self.info {
            i.encode(&mut b);
        }
        b.push(self.hash.is_some() as u8);
        if let Some(h) = self.hash {
            b.extend(h);
        }
        u32be(&mut b, self.data.len());
        b.extend(&self.data);
        let n = (b.len() - 4) as u32;
        b[..4].copy_from_slice(&n.to_be_bytes());
        ensure!(b.len() <= 40960, "file packet too large");
        Ok(b)
    }
    pub fn decode(b: &[u8]) -> Result<Self> {
        ensure!(b.len() <= 40960, "file packet too large");
        let mut d = Decoder::new(b);
        ensure!(
            d.int()? as usize == d.left() && d.int()? == 0x46543033,
            "invalid file framing"
        );
        let mut p = Self {
            kind: d.byte()?,
            sender: d.boolean()?,
            id: d.str(36)?,
            source: d.str(36)?,
            connection: d.str(128)?,
            size: d.long()?,
            offset: d.long()?,
            window: d.long()?,
            verified: d.long()?,
            durable: d.long()?,
            reason: d.str(16)?,
            info: None,
            hash: None,
            data: vec![],
        };
        if d.boolean()? {
            p.info = Some(Info::decode(&mut d)?);
        }
        if d.boolean()? {
            p.hash = Some(d.bytes(32)?.try_into().unwrap());
        }
        let n = d.int()? as usize;
        ensure!(n <= CHUNK, "oversized data");
        p.data = d.bytes(n)?;
        d.done()?;
        p.validate()?;
        Ok(p)
    }
    pub fn frame(&self) -> Result<Frame> {
        let mut f = Frame::control(FILE);
        f.data = self.encode()?;
        Ok(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn utf8_boundary_contract() {
        for n in [8191, 8192] {
            assert!(text_valid(&"a".repeat(n)).is_ok());
        }
        for s in [String::new(), " \t\n".into(), "a".repeat(8193)] {
            assert!(text_valid(&s).is_err());
        }
        assert!(text_valid("\u{a0}\u{2003}").is_ok());
    }
    #[test]
    fn reject_hostile_frames() {
        for n in 0..22 {
            assert!(Frame::decode(&vec![0; n]).is_err());
        }
        let mut f = Frame::control(TEXT);
        f.id = "ABCDEF01-1234-1234-1234-123456789012".into();
        f.body = "中".repeat(2730);
        assert!(f.encode().is_ok());
        f.body.push('中');
        assert!(f.encode().is_err());
        f.body = "hello".into();
        let mut b = f.encode().unwrap();
        b.push(0);
        assert!(Frame::decode(&b).is_err());
        b[4] = 3;
        assert!(Frame::decode(&b).is_err());
    }
    #[test]
    fn packet_roundtrip_and_bounds() {
        let i = Info {
            id: uuid::Uuid::new_v4().to_string(),
            name: "资料📁.txt".into(),
            mime: "text/plain".into(),
            size: 10,
            time: 0,
        };
        let mut p = Packet::control(
            OFFER,
            true,
            &i,
            &uuid::Uuid::new_v4().to_string(),
            &"a".repeat(64),
        );
        p.info = Some(i);
        let b = p.encode().unwrap();
        assert_eq!(Packet::decode(&b).unwrap().info.unwrap().name, "资料📁.txt");
        let mut b = b;
        b[9] = 2;
        assert!(Packet::decode(&b).is_err());
        p.offset = 1;
        assert!(p.encode().is_err());
    }
}

//! Bounded production avatar-v1 records; independent from ordinary attachment tasks.
use crate::profile::Profile as Store;
use crate::wire::{self, Decoder, Frame, short, u32be, u64be};
use anyhow::{Result, ensure};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    io::{Cursor, Read},
    path::Path,
    time::{Duration, Instant},
};

pub const MAX_BYTES: usize = 128 * 1024;
pub const CHUNK: usize = 4096;
pub const WINDOW: usize = 4;
pub const FAMILY: u8 = 17;
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Profile {
    pub revision: u64,
    pub hash: String,
    pub format: String,
    pub width: u16,
    pub height: u16,
    pub size: u32,
}
impl Profile {
    pub fn validate(&self) -> Result<()> {
        ensure!(self.revision <= i64::MAX as u64, "invalid avatar revision");
        if self.hash.is_empty() {
            ensure!(
                self.format.is_empty() && self.width == 0 && self.height == 0 && self.size == 0,
                "invalid default avatar"
            );
        } else {
            ensure!(
                hash(&self.hash)
                    && self.format == "png"
                    && (1..=256).contains(&self.width)
                    && self.height == self.width
                    && self.size > 0
                    && self.size as usize <= MAX_BYTES,
                "invalid avatar profile"
            );
        }
        Ok(())
    }
}
fn hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Packet {
    pub kind: u8,
    pub revision: u64,
    pub hash: String,
    pub id: String,
    pub offset: u32,
    pub code: Option<u8>,
    pub profile: Option<Profile>,
    pub data: Vec<u8>,
}
impl Packet {
    pub fn profile(profile: Profile) -> Self {
        Self {
            kind: 0,
            revision: profile.revision,
            hash: profile.hash.clone(),
            id: String::new(),
            offset: 0,
            code: None,
            profile: Some(profile),
            data: vec![],
        }
    }
    pub fn control(kind: u8, id: &str, profile: &Profile) -> Self {
        Self {
            kind,
            revision: profile.revision,
            hash: profile.hash.clone(),
            id: id.into(),
            offset: 0,
            code: None,
            profile: None,
            data: vec![],
        }
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.kind <= 5 && self.revision <= i64::MAX as u64 && self.offset as usize <= MAX_BYTES,
            "invalid avatar packet"
        );
        if self.kind == 0 {
            let p = self
                .profile
                .as_ref()
                .ok_or_else(|| anyhow::anyhow!("avatar profile missing"))?;
            p.validate()?;
            ensure!(
                p.revision == self.revision
                    && p.hash == self.hash
                    && self.id.is_empty()
                    && self.offset == 0
                    && self.data.is_empty()
                    && self.code.is_none(),
                "invalid profile update"
            );
            return Ok(());
        }
        ensure!(
            self.profile.is_none() && wire::uuid(&self.id, true) && hash(&self.hash),
            "invalid avatar task identity"
        );
        if self.kind == 2 {
            ensure!(
                !self.data.is_empty()
                    && self.data.len() <= CHUNK
                    && self.data.len() <= MAX_BYTES - self.offset as usize
                    && self.code.is_none(),
                "invalid avatar chunk"
            );
        } else {
            ensure!(self.data.is_empty(), "unexpected avatar payload");
        }
        ensure!(
            matches!(self.kind, 2 | 3) || self.offset == 0,
            "unexpected avatar offset"
        );
        match self.kind {
            4 => ensure!(
                self.code.is_some_and(|c| [0, 4, 5, 6, 7].contains(&c)),
                "invalid avatar result"
            ),
            5 => ensure!(
                self.code.is_some_and(|c| [1, 2, 3].contains(&c)),
                "invalid avatar cancel"
            ),
            _ => ensure!(self.code.is_none(), "unexpected avatar code"),
        }
        Ok(())
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut b = vec![0; 4];
        b.extend(0x41563131u32.to_be_bytes());
        b.extend([1, self.kind]);
        u64be(&mut b, self.revision);
        short(&mut b, self.hash.as_bytes());
        short(&mut b, self.id.as_bytes());
        u32be(&mut b, self.offset as usize);
        b.extend([self.code.unwrap_or(255), self.profile.is_some() as u8]);
        if let Some(p) = &self.profile {
            short(&mut b, p.format.as_bytes());
            b.extend(p.width.to_be_bytes());
            b.extend(p.height.to_be_bytes());
            u32be(&mut b, p.size as usize);
        }
        u32be(&mut b, self.data.len());
        b.extend(&self.data);
        let length = (b.len() - 4) as u32;
        b[..4].copy_from_slice(&length.to_be_bytes());
        ensure!(b.len() <= 4608, "avatar record too large");
        Ok(b)
    }
    pub fn decode(b: &[u8]) -> Result<Self> {
        ensure!((28..=4608).contains(&b.len()), "invalid avatar record size");
        let mut d = Decoder::new(b);
        ensure!(
            d.int()? as usize == d.left() && d.int()? == 0x41563131 && d.byte()? == 1,
            "invalid avatar record header"
        );
        let mut p = Self {
            kind: d.byte()?,
            revision: d.long()?,
            hash: String::from_utf8(d.short(64)?)?,
            id: String::from_utf8(d.short(36)?)?,
            offset: d.int()?,
            code: None,
            profile: None,
            data: vec![],
        };
        let code = d.byte()?;
        ensure!(code == 255 || code <= 7, "unknown avatar result");
        if code != 255 {
            p.code = Some(code);
        }
        if d.boolean()? {
            p.profile = Some(Profile {
                revision: p.revision,
                hash: p.hash.clone(),
                format: String::from_utf8(d.short(3)?)?,
                width: u16::from_be_bytes(d.bytes(2)?.try_into().unwrap()),
                height: u16::from_be_bytes(d.bytes(2)?.try_into().unwrap()),
                size: d.int()?,
            });
        }
        let n = d.int()? as usize;
        ensure!(n <= CHUNK, "avatar chunk too large");
        p.data = d.bytes(n)?;
        d.done()?;
        p.validate()?;
        Ok(p)
    }
    pub fn frame(&self) -> Result<Frame> {
        let mut frame = Frame::control(FAMILY);
        frame.data = self.encode()?;
        Ok(frame)
    }
}

/// Validate complete static PNG structure and exact bounded zlib scanlines before adoption.
pub fn validate_png(bytes: &[u8]) -> Result<(u16, u16)> {
    ensure!(
        (57..=MAX_BYTES).contains(&bytes.len()) && bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
        "invalid avatar PNG size/signature"
    );
    fn number(b: &[u8], at: usize) -> usize {
        u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as usize
    }
    let (mut at, mut chunks, mut width, mut height, mut depth, mut color, mut interlace) =
        (8, 0, 0, 0, 0, 0, 0);
    let (mut header, mut palette, mut data, mut ended_data, mut end) =
        (false, false, false, false, false);
    let mut compressed = Vec::new();
    while at < bytes.len() {
        chunks += 1;
        ensure!(
            chunks <= 4096 && bytes.len() - at >= 12,
            "incomplete PNG chunk"
        );
        let n = number(bytes, at);
        ensure!(n <= bytes.len() - at - 12, "PNG chunk exceeds bounds");
        let kind = &bytes[at + 4..at + 8];
        let body = at + 8;
        ensure!(
            kind.iter().all(u8::is_ascii_alphabetic) && kind[2] & 32 == 0,
            "invalid PNG chunk type"
        );
        ensure!(
            crc32fast::hash(&bytes[at + 4..body + n]) == number(bytes, body + n) as u32,
            "PNG CRC mismatch"
        );
        ensure!(header || kind == b"IHDR", "PNG header must be first");
        match kind {
            b"IHDR" => {
                ensure!(!header && n == 13, "invalid PNG header");
                width = number(bytes, body);
                height = number(bytes, body + 4);
                depth = bytes[body + 8] as usize;
                color = bytes[body + 9];
                interlace = bytes[body + 12];
                let valid_depth = match color {
                    0 => [1, 2, 4, 8, 16].contains(&depth),
                    2 | 4 | 6 => [8, 16].contains(&depth),
                    3 => [1, 2, 4, 8].contains(&depth),
                    _ => false,
                };
                ensure!(
                    (1..=256).contains(&width)
                        && height == width
                        && valid_depth
                        && bytes[body + 10] == 0
                        && bytes[body + 11] == 0
                        && interlace <= 1,
                    "invalid PNG dimensions/format"
                );
                header = true;
            }
            b"PLTE" => {
                ensure!(
                    !palette
                        && !data
                        && color != 0
                        && color != 4
                        && (3..=768).contains(&n)
                        && n.is_multiple_of(3)
                        && (color != 3 || n / 3 <= 1 << depth),
                    "invalid PNG palette"
                );
                palette = true;
            }
            b"IDAT" => {
                ensure!(
                    !ended_data && (color != 3 || palette),
                    "invalid PNG data order"
                );
                compressed.extend_from_slice(&bytes[body..body + n]);
                data = true;
            }
            b"IEND" => {
                ensure!(
                    data && n == 0 && at + 12 == bytes.len(),
                    "incomplete/trailing PNG data"
                );
                end = true;
            }
            b"acTL" | b"fcTL" | b"fdAT" => anyhow::bail!("animated avatars are unsupported"),
            _ => ensure!(kind[0] & 32 != 0, "unknown critical PNG chunk"),
        }
        if data && kind != b"IDAT" {
            ended_data = true;
        }
        at += n + 12;
        if end {
            break;
        }
    }
    ensure!(end && !compressed.is_empty(), "incomplete PNG");
    let channels = match color {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => unreachable!(),
    };
    let mut rows = Vec::new();
    let (x, y, dx, dy) = (
        [0, 4, 0, 2, 0, 1, 0],
        [0, 0, 4, 0, 2, 0, 1],
        [8, 8, 4, 4, 2, 2, 1],
        [8, 8, 8, 4, 4, 2, 2],
    );
    for pass in 0..if interlace == 0 { 1 } else { 7 } {
        let span = |n: usize, start: usize, step: usize| {
            if n <= start {
                0
            } else {
                (n - start).div_ceil(step)
            }
        };
        let (w, h) = if interlace == 0 {
            (width, height)
        } else {
            (
                span(width, x[pass], dx[pass]),
                span(height, y[pass], dy[pass]),
            )
        };
        if w > 0 && h > 0 {
            for _ in 0..h {
                rows.push(1 + (w * channels * depth).div_ceil(8));
            }
        }
    }
    let length: usize = rows.iter().sum();
    let mut inflated = vec![0; length + 1];
    let mut decoder = flate2::Decompress::new(true);
    let status = decoder.decompress(&compressed, &mut inflated, flate2::FlushDecompress::Finish)?;
    ensure!(
        status == flate2::Status::StreamEnd
            && decoder.total_in() == compressed.len() as u64
            && decoder.total_out() == length as u64,
        "invalid/trailing PNG compressed pixels"
    );
    let mut offset = 0;
    for row in rows {
        ensure!(inflated[offset] <= 4, "invalid PNG row filter");
        offset += row;
    }
    Ok((width as u16, height as u16))
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub profile: Profile,
    pub png: Vec<u8>,
}
pub fn own_root(store: &Store) -> String {
    STANDARD.encode(&store.identity.public)
}
fn validate_image(profile: &Profile, png: &[u8]) -> Result<()> {
    profile.validate()?;
    if profile.hash.is_empty() {
        ensure!(png.is_empty(), "default avatar has content");
    } else {
        ensure!(
            png.len() == profile.size as usize && hex::encode(Sha256::digest(png)) == profile.hash,
            "avatar content/hash mismatch"
        );
        ensure!(
            validate_png(png)? == (profile.width, profile.height),
            "avatar dimensions mismatch"
        );
    }
    Ok(())
}
pub fn snapshot(store: &Store, root: &str) -> Result<Snapshot> {
    crate::profile::validate_root(&STANDARD.decode(root)?)?;
    // Bound corrupt database values before SQLite copies their contents into Rust memory.
    let row:Option<(Option<String>,Option<Vec<u8>>)> = store.db.query_row(
        "SELECT CASE WHEN length(value)<=1024 THEN value END, CASE WHEN length(png)<=131072 THEN png END FROM avatars WHERE root=?",
        [root], |r| Ok((r.get(0)?,r.get(1)?))).optional()?;
    let Some((profile, png)) = row else {
        return Ok(Snapshot {
            profile: Profile::default(),
            png: vec![],
        });
    };
    let profile: Profile = serde_json::from_str(
        &profile.ok_or_else(|| anyhow::anyhow!("avatar metadata exceeds bounds"))?,
    )?;
    let png = png.ok_or_else(|| anyhow::anyhow!("avatar cache exceeds bounds"))?;
    validate_image(&profile, &png)?;
    Ok(Snapshot { profile, png })
}
fn adopt(store: &Store, root: &str, profile: Profile, png: Vec<u8>) -> Result<Snapshot> {
    validate_image(&profile, &png)?;
    let current = snapshot(store, root)?;
    ensure!(
        profile.revision >= current.profile.revision
            && (profile.revision != current.profile.revision || profile == current.profile),
        "conflicting persisted avatar revision"
    );
    let tx = store.db.unchecked_transaction()?;
    tx.execute("INSERT INTO avatars VALUES(?,?,?,?) ON CONFLICT(root) DO UPDATE SET value=excluded.value,png=excluded.png,updated=excluded.updated", params![root,serde_json::to_string(&profile)?,png,crate::profile::now()])?;
    // A bounded root-key cache is independent from editable names and device UUIDs.
    tx.execute("DELETE FROM avatars WHERE root IN (SELECT root FROM avatars WHERE root<>? ORDER BY updated DESC,root LIMIT -1 OFFSET 63)",[own_root(store)])?;
    tx.commit()?;
    Ok(Snapshot { profile, png })
}
pub fn save_local(store: &Store, png: Vec<u8>) -> Result<Snapshot> {
    let root = own_root(store);
    let current = snapshot(store, &root)?;
    let revision = crate::profile::now().max(
        current
            .profile
            .revision
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("avatar revision exhausted"))?,
    );
    let profile = if png.is_empty() {
        Profile {
            revision,
            ..Profile::default()
        }
    } else {
        let (width, height) = validate_png(&png)?;
        Profile {
            revision,
            hash: hex::encode(Sha256::digest(&png)),
            format: "png".into(),
            width,
            height,
            size: png.len() as u32,
        }
    };
    adopt(store, &root, profile, png)
}
/// Explicit local selection: orient, center-crop and shrink with bounded decoding.
pub fn prepare_image(path: &Path) -> Result<Vec<u8>> {
    let file = std::fs::File::open(path)?;
    ensure!(
        file.metadata()?.is_file() && file.metadata()?.len() <= 32 * 1024 * 1024,
        "avatar source must be a regular file of at most 32 MiB"
    );
    let mut bytes = Vec::new();
    file.take(32 * 1024 * 1024 + 1).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() <= 32 * 1024 * 1024,
        "avatar source grew beyond 32 MiB"
    );
    let mut reader = image::ImageReader::new(Cursor::new(&bytes)).with_guessed_format()?;
    ensure!(
        matches!(
            reader.format(),
            Some(image::ImageFormat::Png | image::ImageFormat::Jpeg | image::ImageFormat::WebP)
        ),
        "choose a PNG, JPEG or WebP image"
    );
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(16384);
    limits.max_image_height = Some(16384);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    use image::ImageDecoder;
    let mut decoder = reader.into_decoder()?;
    let orientation = decoder.orientation()?;
    let (w, h) = decoder.dimensions();
    ensure!(
        w as u64 * h as u64 <= 32 * 1024 * 1024,
        "avatar source exceeds 32 megapixels"
    );
    let mut image = image::DynamicImage::from_decoder(decoder)?;
    image.apply_orientation(orientation);
    let side = image.width().min(image.height());
    let square = image.crop_imm(
        (image.width() - side) / 2,
        (image.height() - side) / 2,
        side,
        side,
    );
    for edge in [256, 192, 128, 96, 64] {
        let resized = square
            .resize_exact(edge, edge, image::imageops::FilterType::Lanczos3)
            .to_rgba8();
        let mut out = Cursor::new(Vec::new());
        resized.write_to(&mut out, image::ImageFormat::Png)?;
        let png = out.into_inner();
        if png.len() <= MAX_BYTES {
            validate_png(&png)?;
            return Ok(png);
        }
    }
    anyhow::bail!("cannot encode avatar within 128 KiB")
}

pub fn export(store: &Store, root: &str, path: &Path) -> Result<Profile> {
    use std::io::Write;
    let saved = snapshot(store, root)?;
    ensure!(
        !saved.png.is_empty(),
        "this identity uses the default avatar"
    );
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?;
    let result = (|| -> Result<()> {
        file.write_all(&saved.png)?;
        file.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        drop(file);
        let _ = std::fs::remove_file(path);
    }
    result?;
    Ok(saved.profile)
}

struct Incoming {
    id: String,
    profile: Profile,
    png: Vec<u8>,
    progress: Instant,
}
struct Outgoing {
    id: String,
    snapshot: Snapshot,
    sent: usize,
    ack: usize,
    pending: VecDeque<usize>,
    progress: Instant,
}
struct Ended {
    id: String,
    profile: Profile,
    receiving: bool,
    offset: usize,
    sent: usize,
    remaining: usize,
}
pub struct Sync {
    root: String,
    latest: Profile,
    incoming: Option<Incoming>,
    outgoing: Option<Outgoing>,
    ended: VecDeque<Ended>,
    pub changed: Vec<Profile>,
    pub failures: Vec<u8>,
}
impl Sync {
    pub fn new(store: &Store, root: &str) -> Result<Self> {
        ensure!(
            root != own_root(store),
            "local and remote avatar roots must differ"
        );
        crate::profile::validate_root(&STANDARD.decode(root)?)?;
        let (latest, failures) = match snapshot(store, root) {
            Ok(s) => (s.profile, vec![]),
            Err(_) => (Profile::default(), vec![6]),
        };
        Ok(Self {
            root: root.into(),
            latest,
            incoming: None,
            outgoing: None,
            ended: VecDeque::new(),
            changed: vec![],
            failures,
        })
    }
    fn remember(&mut self, done: Ended) {
        self.ended.push_back(done);
        while self.ended.len() > 16 {
            self.ended.pop_front();
        }
    }
    fn incoming_done(
        &mut self,
        kind: u8,
        code: u8,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<()> {
        let t = self
            .incoming
            .take()
            .ok_or_else(|| anyhow::anyhow!("missing avatar task"))?;
        let mut p = Packet::control(kind, &t.id, &t.profile);
        p.code = Some(code);
        self.remember(Ended {
            id: t.id,
            profile: t.profile,
            receiving: true,
            offset: t.png.len(),
            sent: 0,
            remaining: WINDOW,
        });
        if kind == 4 && code != 0 {
            self.failures.push(code);
        }
        send(p.frame()?)
    }
    fn outgoing_done(
        &mut self,
        notify: bool,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<()> {
        if let Some(t) = self.outgoing.take() {
            let mut p = Packet::control(5, &t.id, &t.snapshot.profile);
            p.code = Some(2);
            self.remember(Ended {
                id: t.id,
                profile: t.snapshot.profile,
                receiving: false,
                offset: t.ack,
                sent: t.sent,
                remaining: WINDOW,
            });
            if notify {
                send(p.frame()?)?;
            }
        }
        Ok(())
    }
    pub fn local_changed(
        &mut self,
        store: &Store,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<()> {
        match snapshot(store, &own_root(store)) {
            Ok(current) => {
                if self
                    .outgoing
                    .as_ref()
                    .is_some_and(|t| t.snapshot.profile != current.profile)
                {
                    self.outgoing_done(true, send)?;
                }
                send(Packet::profile(current.profile).frame()?)
            }
            Err(_) => {
                self.failures.push(6);
                Ok(())
            }
        }
    }
    fn pump(&mut self, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        if let Some(t) = &mut self.outgoing {
            while t.pending.len() < WINDOW && t.sent < t.snapshot.png.len() {
                let end = (t.sent + CHUNK).min(t.snapshot.png.len());
                let mut p = Packet::control(2, &t.id, &t.snapshot.profile);
                p.offset = t.sent as u32;
                p.data = t.snapshot.png[t.sent..end].to_vec();
                send(p.frame()?)?;
                t.sent = end;
                t.pending.push_back(end);
            }
        }
        Ok(())
    }
    fn matches(p: &Packet, id: &str, profile: &Profile) -> bool {
        p.id == id && p.revision == profile.revision && p.hash == profile.hash
    }
    fn discard_ended(&mut self, p: &Packet) -> Result<()> {
        let t = self
            .ended
            .iter_mut()
            .find(|t| Self::matches(p, &t.id, &t.profile))
            .ok_or_else(|| anyhow::anyhow!("unknown avatar task"))?;
        if p.kind == 2 {
            ensure!(
                t.receiving
                    && t.remaining > 0
                    && p.offset as usize == t.offset
                    && p.data.len() <= t.profile.size as usize - t.offset,
                "excess canceled avatar window"
            );
            t.offset += p.data.len();
            t.remaining -= 1;
        } else if p.kind == 3 {
            ensure!(
                !t.receiving && p.offset as usize > t.offset && p.offset as usize <= t.sent,
                "invalid canceled avatar acknowledgement"
            );
            t.offset = p.offset as usize;
        }
        Ok(())
    }
    pub fn handle(
        &mut self,
        p: Packet,
        store: &Store,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<()> {
        p.validate()?;
        match p.kind {
            0 => {
                let profile = p.profile.unwrap();
                if profile.revision < self.latest.revision {
                    return Ok(());
                }
                ensure!(
                    profile.revision != self.latest.revision || profile == self.latest,
                    "conflicting avatar revision"
                );
                if self.incoming.as_ref().is_some_and(|t| t.profile == profile) {
                    return Ok(());
                }
                if self.incoming.is_some() {
                    self.incoming_done(5, 2, send)?;
                }
                self.latest = profile.clone();
                let saved = match snapshot(store, &self.root) {
                    Ok(s) => s,
                    Err(_) => {
                        self.failures.push(6);
                        return Ok(());
                    }
                };
                if saved.profile == profile {
                    return Ok(());
                }
                if profile.hash.is_empty()
                    || (profile.hash == saved.profile.hash
                        && profile.size == saved.profile.size
                        && profile.width == saved.profile.width
                        && profile.height == saved.profile.height
                        && profile.format == saved.profile.format)
                {
                    let png = if profile.hash.is_empty() {
                        vec![]
                    } else {
                        saved.png
                    };
                    match adopt(store, &self.root, profile.clone(), png) {
                        Ok(_) => self.changed.push(profile),
                        Err(_) => self.failures.push(6),
                    }
                    return Ok(());
                }
                let id = uuid::Uuid::new_v4().to_string();
                self.incoming = Some(Incoming {
                    id: id.clone(),
                    profile: profile.clone(),
                    png: Vec::with_capacity(profile.size as usize),
                    progress: Instant::now(),
                });
                send(Packet::control(1, &id, &profile).frame()?)?;
            }
            1 => {
                ensure!(
                    !self.ended.iter().any(|t| t.id == p.id)
                        && !self.incoming.as_ref().is_some_and(|t| t.id == p.id)
                        && !self.outgoing.as_ref().is_some_and(|t| t.id == p.id),
                    "reused avatar task identity"
                );
                let current = snapshot(store, &own_root(store));
                let code = if self.outgoing.is_some() {
                    Some((4, 4))
                } else {
                    match &current {
                        Err(_) => Some((4, 6)),
                        Ok(s) if s.profile.revision != p.revision || s.profile.hash != p.hash => {
                            Some((5, 2))
                        }
                        _ => None,
                    }
                };
                if let Some((kind, code)) = code {
                    let profile = Profile {
                        revision: p.revision,
                        hash: p.hash.clone(),
                        ..Profile::default()
                    };
                    let mut r = p.clone();
                    r.kind = kind;
                    r.code = Some(code);
                    send(r.frame()?)?;
                    self.remember(Ended {
                        id: p.id,
                        profile,
                        receiving: false,
                        offset: 0,
                        sent: 0,
                        remaining: 0,
                    });
                    if kind == 5 {
                        self.local_changed(store, send)?;
                    }
                    return Ok(());
                }
                self.outgoing = Some(Outgoing {
                    id: p.id,
                    snapshot: current?,
                    sent: 0,
                    ack: 0,
                    pending: VecDeque::new(),
                    progress: Instant::now(),
                });
                self.pump(send)?;
            }
            2 => {
                if !self
                    .incoming
                    .as_ref()
                    .is_some_and(|t| Self::matches(&p, &t.id, &t.profile))
                {
                    return self.discard_ended(&p);
                }
                let t = self.incoming.as_mut().unwrap();
                ensure!(
                    p.offset as usize == t.png.len()
                        && p.data.len() <= t.profile.size as usize - t.png.len(),
                    "out of order/excess avatar chunk"
                );
                t.png.extend(&p.data);
                t.progress = Instant::now();
                let mut ack = Packet::control(3, &t.id, &t.profile);
                ack.offset = t.png.len() as u32;
                send(ack.frame()?)?;
                if t.png.len() == t.profile.size as usize {
                    let code = if hex::encode(Sha256::digest(&t.png)) != t.profile.hash {
                        7
                    } else if validate_image(&t.profile, &t.png).is_err() {
                        5
                    } else if adopt(store, &self.root, t.profile.clone(), t.png.clone()).is_err() {
                        6
                    } else {
                        self.changed.push(t.profile.clone());
                        0
                    };
                    self.incoming_done(4, code, send)?;
                }
            }
            3 => {
                if !self
                    .outgoing
                    .as_ref()
                    .is_some_and(|t| Self::matches(&p, &t.id, &t.snapshot.profile))
                {
                    return self.discard_ended(&p);
                }
                let t = self.outgoing.as_mut().unwrap();
                let offset = p.offset as usize;
                ensure!(
                    offset > t.ack && t.pending.contains(&offset),
                    "unexpected avatar acknowledgement"
                );
                while t.pending.front().is_some_and(|n| *n <= offset) {
                    t.pending.pop_front();
                }
                t.ack = offset;
                t.progress = Instant::now();
                self.pump(send)?;
            }
            4 => {
                if self
                    .outgoing
                    .as_ref()
                    .is_some_and(|t| Self::matches(&p, &t.id, &t.snapshot.profile))
                {
                    let t = self.outgoing.as_ref().unwrap();
                    ensure!(
                        p.code != Some(0) || (t.sent == t.snapshot.png.len() && t.ack == t.sent),
                        "avatar success before complete acknowledgement"
                    );
                    if p.code != Some(0) {
                        self.failures.push(p.code.unwrap());
                    }
                    self.outgoing_done(false, send)?;
                } else if p.code != Some(0)
                    && self
                        .incoming
                        .as_ref()
                        .is_some_and(|t| Self::matches(&p, &t.id, &t.profile))
                {
                    let t = self.incoming.take().unwrap();
                    self.remember(Ended {
                        id: t.id,
                        profile: t.profile,
                        receiving: true,
                        offset: t.png.len(),
                        sent: 0,
                        remaining: WINDOW,
                    });
                    self.failures.push(p.code.unwrap());
                } else {
                    return self.discard_ended(&p);
                }
            }
            5 => {
                if self
                    .incoming
                    .as_ref()
                    .is_some_and(|t| Self::matches(&p, &t.id, &t.profile))
                {
                    let t = self.incoming.take().unwrap();
                    self.remember(Ended {
                        id: t.id,
                        profile: t.profile,
                        receiving: true,
                        offset: t.png.len(),
                        sent: 0,
                        remaining: WINDOW,
                    });
                } else if self
                    .outgoing
                    .as_ref()
                    .is_some_and(|t| Self::matches(&p, &t.id, &t.snapshot.profile))
                {
                    self.outgoing_done(false, send)?;
                } else {
                    return self.discard_ended(&p);
                }
            }
            _ => unreachable!(),
        }
        Ok(())
    }
    pub fn expire(&mut self, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        if self
            .incoming
            .as_ref()
            .is_some_and(|t| t.progress.elapsed() > Duration::from_secs(30))
        {
            self.incoming_done(5, 3, send)?;
            self.failures.push(3);
        }
        if self
            .outgoing
            .as_ref()
            .is_some_and(|t| t.progress.elapsed() > Duration::from_secs(30))
        {
            let t = self.outgoing.take().unwrap();
            let mut p = Packet::control(5, &t.id, &t.snapshot.profile);
            p.code = Some(3);
            send(p.frame()?)?;
            self.remember(Ended {
                id: t.id,
                profile: t.snapshot.profile,
                receiving: false,
                offset: t.ack,
                sent: t.sent,
                remaining: WINDOW,
            });
            self.failures.push(3);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::Profile as Store;
    use std::{collections::VecDeque, io::Cursor};
    fn png(seed: u8) -> Vec<u8> {
        let image =
            image::RgbaImage::from_fn(96, 96, |x, y| image::Rgba([x as u8, y as u8, seed, 255]));
        let mut bytes = Cursor::new(Vec::new());
        image.write_to(&mut bytes, image::ImageFormat::Png).unwrap();
        bytes.into_inner()
    }
    #[test]
    fn complete_png_validation_rejects_crc_and_trailing_data() {
        let good = png(7);
        assert_eq!(validate_png(&good).unwrap(), (96, 96));
        let mut bad = good.clone();
        bad[30] ^= 1;
        assert!(validate_png(&bad).is_err());
        let mut bad = good.clone();
        bad.extend([0]);
        assert!(validate_png(&bad).is_err());
        for n in [0, 8, 33, good.len() - 1] {
            assert!(validate_png(&good[..n]).is_err());
        }
    }
    #[test]
    fn bidirectional_sync_persists_only_verified_identity_bound_images() {
        let a_dir = tempfile::tempdir().unwrap();
        let b_dir = tempfile::tempdir().unwrap();
        let a = Store::open(a_dir.path().join("a"), None).unwrap();
        let b = Store::open(b_dir.path().join("b"), None).unwrap();
        let a_root = own_root(&a);
        let b_root = own_root(&b);
        let a_png = png(3);
        let b_png = png(6);
        save_local(&a, a_png.clone()).unwrap();
        save_local(&b, b_png.clone()).unwrap();
        let mut sa = Sync::new(&a, &b_root).unwrap();
        let mut sb = Sync::new(&b, &a_root).unwrap();
        let mut a_to_b = VecDeque::new();
        let mut b_to_a = VecDeque::new();
        sa.local_changed(&a, &mut |f| {
            a_to_b.push_back(f);
            Ok(())
        })
        .unwrap();
        sb.local_changed(&b, &mut |f| {
            b_to_a.push_back(f);
            Ok(())
        })
        .unwrap();
        for _ in 0..256 {
            if let Some(f) = a_to_b.pop_front() {
                sb.handle(Packet::decode(&f.data).unwrap(), &b, &mut |f| {
                    b_to_a.push_back(f);
                    Ok(())
                })
                .unwrap();
            }
            if let Some(f) = b_to_a.pop_front() {
                sa.handle(Packet::decode(&f.data).unwrap(), &a, &mut |f| {
                    a_to_b.push_back(f);
                    Ok(())
                })
                .unwrap();
            }
            if a_to_b.is_empty() && b_to_a.is_empty() {
                break;
            }
        }
        assert!(a_to_b.is_empty() && b_to_a.is_empty());
        assert_eq!(snapshot(&a, &b_root).unwrap().png, b_png);
        assert_eq!(snapshot(&b, &a_root).unwrap().png, a_png);
        assert!(
            snapshot(&a, &a_root).unwrap().profile.hash
                != snapshot(&a, &b_root).unwrap().profile.hash
        );
        let revision = snapshot(&a, &a_root).unwrap().profile.revision;
        save_local(&a, vec![]).unwrap();
        assert!(snapshot(&a, &a_root).unwrap().profile.revision > revision);
        sa.local_changed(&a, &mut |f| {
            a_to_b.push_back(f);
            Ok(())
        })
        .unwrap();
        sb.handle(
            Packet::decode(&a_to_b.pop_front().unwrap().data).unwrap(),
            &b,
            &mut |_| Ok(()),
        )
        .unwrap();
        assert!(snapshot(&b, &a_root).unwrap().png.is_empty());
        let conflict = Profile {
            hash: hex::encode([3; 32]),
            format: "png".into(),
            width: 96,
            height: 96,
            size: 100,
            revision: snapshot(&b, &a_root).unwrap().profile.revision,
        };
        assert!(
            sb.handle(Packet::profile(conflict), &b, &mut |_| Ok(()))
                .is_err()
        );
        let a_revision = snapshot(&a, &a_root).unwrap().profile;
        drop(a);
        let a = Store::open(a_dir.path().join("a"), None).unwrap();
        assert_eq!(snapshot(&a, &a_root).unwrap().profile, a_revision);
        assert_eq!(snapshot(&a, &b_root).unwrap().png, b_png);
    }
    #[test]
    fn corrupted_avatar_finishes_task_without_changing_previous_cache() {
        let dir = tempfile::tempdir().unwrap();
        let a = Store::open(dir.path().join("a"), None).unwrap();
        let b = Store::open(dir.path().join("b"), None).unwrap();
        let root = own_root(&b);
        let bytes = png(8);
        let profile = Profile {
            revision: 1,
            hash: hex::encode(sha2::Sha256::digest(&bytes)),
            format: "png".into(),
            width: 96,
            height: 96,
            size: bytes.len() as u32,
        };
        let mut sync = Sync::new(&a, &root).unwrap();
        let mut frames = vec![];
        sync.handle(Packet::profile(profile.clone()), &a, &mut |f| {
            frames.push(f);
            Ok(())
        })
        .unwrap();
        let request = Packet::decode(&frames.pop().unwrap().data).unwrap();
        let mut corrupt = bytes;
        corrupt[40] ^= 1;
        for (i, data) in corrupt.chunks(CHUNK).enumerate() {
            let mut p = Packet::control(2, &request.id, &profile);
            p.offset = (i * CHUNK) as u32;
            p.data = data.to_vec();
            sync.handle(p, &a, &mut |f| {
                frames.push(f);
                Ok(())
            })
            .unwrap();
        }
        assert!(snapshot(&a, &root).unwrap().png.is_empty());
        assert_eq!(
            Packet::decode(&frames.last().unwrap().data).unwrap().code,
            Some(7)
        );
        assert!(
            sync.handle(Packet::control(3, &request.id, &profile), &a, &mut |_| Ok(
                ()
            ))
            .is_err()
        );
    }
}

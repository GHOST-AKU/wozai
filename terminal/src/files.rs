//! Streaming attachment-v2 with bounded windows and durable, identity-bound checkpoints.
use crate::{
    profile::{self, Profile},
    wire::*,
};
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
const WINDOW: u64 = 2 * BLOCK;
#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Saved {
    pub peer: String,
    pub root: String,
    pub outgoing: bool,
    pub info: Info,
    pub source: String,
    pub path: PathBuf,
    pub destination: PathBuf,
    pub offset: u64,
    pub hash: String,
    pub state: String,
    pub modified: u64,
    #[serde(default)]
    pub manual_pause: bool,
    #[serde(default)]
    pub failure: String,
}
impl Saved {
    pub fn key(&self) -> String {
        format!(
            "{}:{}:{}:{}",
            profile::fingerprint(self.root.as_bytes()),
            self.outgoing,
            self.info.id,
            self.source
        )
    }
}
struct Task {
    saved: Saved,
    file: File,
    whole: Sha256,
    block: Sha256,
    sent: u64,
    verified: u64,
    credit: u64,
    window: u64,
    active: bool,
}
pub struct Engine {
    store: Arc<Mutex<Profile>>,
    peer: String,
    root: String,
    connection: String,
    directory: PathBuf,
    incoming: Option<Task>,
    outgoing: Option<Task>,
    pub notices: Vec<Saved>,
}
impl Engine {
    pub fn new(
        store: Arc<Mutex<Profile>>,
        peer: String,
        root: String,
        connection: String,
        directory: PathBuf,
    ) -> Self {
        Self {
            store,
            peer,
            root,
            connection,
            directory,
            incoming: None,
            outgoing: None,
            notices: vec![],
        }
    }
    fn save(&mut self, s: &Saved) -> Result<()> {
        self.store.lock().unwrap().transfer(&s.key(), s)?;
        self.notices.push(s.clone());
        Ok(())
    }
    fn lookup(&self, p: &Packet, outgoing: bool) -> Result<Option<Saved>> {
        Ok(self
            .store
            .lock()
            .unwrap()
            .transfers::<Saved>()?
            .into_iter()
            .find(|s| {
                s.peer == self.peer
                    && s.root == self.root
                    && s.outgoing == outgoing
                    && s.info.id == p.id
                    && s.source == p.source
            }))
    }
    fn packet(&self, s: &Saved, kind: u8) -> Packet {
        Packet::control(kind, s.outgoing, &s.info, &s.source, &self.connection)
    }
    pub fn offer(
        &mut self,
        path: &Path,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<String> {
        ensure!(
            self.outgoing.is_none() || !self.outgoing.as_ref().unwrap().active,
            "a file is already being sent"
        );
        let path = path.canonicalize()?;
        let m = fs::metadata(&path)?;
        ensure!(
            m.is_file() && m.len() <= MAX_FILE,
            "source must be a regular file of at most 10 GiB"
        );
        let name = path
            .file_name()
            .context("missing filename")?
            .to_str()
            .context("filename must be UTF-8")?
            .to_string();
        let info = Info {
            id: uuid::Uuid::new_v4().to_string(),
            name,
            mime: "application/octet-stream".into(),
            size: m.len(),
            time: profile::now(),
        };
        let s = Saved {
            peer: self.peer.clone(),
            root: self.root.clone(),
            outgoing: true,
            info,
            source: uuid::Uuid::new_v4().to_string(),
            path: path.clone(),
            destination: path,
            offset: 0,
            hash: hex::encode(Sha256::digest([])),
            state: "offered".into(),
            modified: modified(&m),
            manual_pause: false,
            failure: String::new(),
        };
        let mut p = self.packet(&s, OFFER);
        p.info = Some(s.info.clone());
        p.validate()?;
        let file = File::open(&s.path)?;
        self.save(&s)?;
        let id = s.info.id.clone();
        self.outgoing = Some(Task {
            saved: s,
            file,
            whole: Sha256::new(),
            block: Sha256::new(),
            sent: 0,
            verified: 0,
            credit: 0,
            window: 0,
            active: false,
        });
        send(p.frame()?)?;
        Ok(id)
    }
    pub fn resume(&mut self, id: &str, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        let mut s = self
            .store
            .lock()
            .unwrap()
            .transfers::<Saved>()?
            .into_iter()
            .find(|s| s.peer == self.peer && s.root == self.root && s.info.id == id)
            .context("no checkpoint for this peer and transfer")?;
        ensure!(
            !["canceled", "failed"].contains(&s.state.as_str()),
            "transfer cannot be resumed"
        );
        if ["saved", "received"].contains(&s.state.as_str()) {
            self.notices.push(s);
            return Ok(());
        }
        s.manual_pause = false;
        self.save(&s)?;
        let p = self.packet(&s, STATUS);
        if s.outgoing {
            self.restore_sender(s)?;
        }
        send(p.frame()?)
    }
    fn restore_sender(&mut self, s: Saved) -> Result<()> {
        ensure!(
            self.outgoing
                .as_ref()
                .is_none_or(|t| matches!(t.saved.state.as_str(), "paused" | "canceled")
                    || t.saved.info.id == s.info.id),
            "another send is active"
        );
        let file = File::open(&s.path)?;
        let m = file.metadata()?;
        ensure!(
            m.is_file() && m.len() == s.info.size && modified(&m) == s.modified,
            "source changed; refusing resume"
        );
        self.outgoing = Some(Task {
            sent: 0,
            verified: 0,
            credit: 0,
            window: 0,
            active: false,
            saved: s,
            file,
            whole: Sha256::new(),
            block: Sha256::new(),
        });
        Ok(())
    }
    pub fn pause(
        &mut self,
        id: &str,
        cancel: bool,
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<()> {
        let mut found = false;
        for outgoing in [true, false] {
            let slot = if outgoing {
                &mut self.outgoing
            } else {
                &mut self.incoming
            };
            if slot.as_ref().is_some_and(|t| t.saved.info.id == id) {
                let mut t = slot.take().unwrap();
                found = true;
                t.active = false;
                t.saved.state = if cancel { "canceled" } else { "paused" }.into();
                t.saved.manual_pause = !cancel;
                if !outgoing {
                    t.file.set_len(t.verified)?;
                    t.file.sync_all()?;
                    t.saved.offset = t.verified;
                    t.whole = hash_prefix(&mut t.file, t.verified)?;
                    t.saved.hash = hex::encode(t.whole.clone().finalize());
                }
                let mut p = self.packet(&t.saved, if cancel { CANCEL } else { PAUSE });
                if cancel {
                    p.reason = "canceled".into();
                }
                self.save(&t.saved)?;
                send(p.frame()?)?;
                if cancel && !outgoing {
                    drop(t.file);
                    fs::remove_file(&t.saved.path)?;
                } else if outgoing {
                    self.outgoing = Some(t);
                } else {
                    self.incoming = Some(t);
                }
            }
        }
        if !found && cancel {
            let mut saved = self
                .store
                .lock()
                .unwrap()
                .transfers::<Saved>()?
                .into_iter()
                .find(|s| s.peer == self.peer && s.root == self.root && s.info.id == id)
                .context("unknown transfer")?;
            ensure!(
                !["saved", "received"].contains(&saved.state.as_str()),
                "completed transfer cannot be canceled"
            );
            saved.state = "canceled".into();
            let mut p = self.packet(&saved, CANCEL);
            p.reason = "canceled".into();
            self.save(&saved)?;
            send(p.frame()?)?;
            found = true;
        }
        ensure!(found, "transfer is not active on this connection");
        Ok(())
    }
    pub fn handle(&mut self, p: Packet, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        ensure!(
            p.connection == self.connection,
            "stale file connection generation"
        );
        if p.kind == OFFER {
            if self.incoming.as_ref().is_some_and(|t| t.active) {
                return reject(&p, "busy", send);
            }
            if self.lookup(&p, false)?.is_some() {
                return reject(&p, "rejected", send);
            }
            let info = p.info.clone().unwrap();
            let prepared = (|| -> Result<_> {
                fs::create_dir_all(&self.directory)?;
                let directory = self.directory.canonicalize()?;
                ensure!(
                    fs2::available_space(&directory)? > info.size.saturating_add(16 * BLOCK),
                    "insufficient receive disk space"
                );
                let destination = directory.join(format!("{}-{}", info.id, safe_name(&info.name)));
                let path = directory.join(format!(".nearbyim-{}-{}.part", info.id, p.source));
                let file = profile::private_open(&path, true)?;
                Ok((file, path, destination))
            })();
            let (file, path, destination) = match prepared {
                Ok(value) => value,
                Err(error) => {
                    let saved = Saved {
                        peer: self.peer.clone(),
                        root: self.root.clone(),
                        outgoing: false,
                        info,
                        source: p.source.clone(),
                        path: PathBuf::new(),
                        destination: self.directory.clone(),
                        offset: 0,
                        hash: String::new(),
                        state: "failed".into(),
                        modified: 0,
                        manual_pause: false,
                        failure: format!("{error:#}"),
                    };
                    self.save(&saved)?;
                    return reject(&p, "failed", send);
                }
            };
            let s = Saved {
                peer: self.peer.clone(),
                root: self.root.clone(),
                outgoing: false,
                info,
                source: p.source.clone(),
                path,
                destination,
                offset: 0,
                hash: hex::encode(Sha256::digest([])),
                state: "receiving".into(),
                modified: 0,
                manual_pause: false,
                failure: String::new(),
            };
            self.save(&s)?;
            self.incoming = Some(Task {
                saved: s,
                file,
                whole: Sha256::new(),
                block: Sha256::new(),
                sent: 0,
                verified: 0,
                credit: 0,
                window: WINDOW,
                active: true,
            });
            let mut r = p.reply(ACCEPT);
            r.window = WINDOW;
            return send(r.frame()?);
        }
        if p.kind == STATUS {
            let s = self.lookup(&p, !p.sender)?;
            let Some(mut s) = s else {
                return reject(&p, "rejected", send);
            };
            if ["canceled", "failed"].contains(&s.state.as_str()) {
                return reject(&p, "canceled", send);
            }
            if s.manual_pause {
                return send(p.reply(PAUSE).frame()?);
            }
            if !p.sender {
                self.restore_sender(s.clone())?;
                return send(self.packet(&s, STATUS).frame()?);
            }
            if s.state == "received" || s.destination.exists() {
                ensure!(
                    s.offset == s.info.size,
                    "unexpected destination conflicts with partial transfer"
                );
                let mut f = File::open(&s.destination)?;
                ensure!(f.metadata()?.len() == s.info.size, "completed file changed");
                let h = hash_prefix(&mut f, s.info.size)?;
                ensure!(
                    hex::encode(h.clone().finalize()) == s.hash,
                    "completed file hash changed"
                );
                s.state = "received".into();
                self.save(&s)?;
                let mut r = p.reply(COMPLETE);
                r.offset = s.info.size;
                r.hash = Some(h.finalize().into());
                return send(r.frame()?);
            }
            ensure!(
                self.incoming
                    .as_ref()
                    .is_none_or(|t| !t.active || t.saved.info.id == s.info.id),
                "another receive is active"
            );
            let mut file = open_existing(&s.path)?;
            ensure!(file.metadata()?.len() >= s.offset, "partial file truncated");
            let whole = hash_prefix(&mut file, s.offset)?;
            ensure!(
                hex::encode(whole.clone().finalize()) == s.hash,
                "partial file hash mismatch"
            );
            file.set_len(s.offset)?;
            file.sync_all()?;
            s.state = "receiving".into();
            self.save(&s)?;
            let mut r = p.reply(RESUME);
            r.offset = s.offset;
            r.verified = s.offset;
            r.durable = s.offset;
            r.window = WINDOW;
            r.hash = Some(whole.clone().finalize().into());
            self.incoming = Some(Task {
                sent: s.offset,
                verified: s.offset,
                credit: s.offset,
                window: WINDOW,
                active: true,
                saved: s,
                file,
                whole,
                block: Sha256::new(),
            });
            return send(r.frame()?);
        }
        let outgoing = !p.sender;
        let slot = if outgoing {
            &mut self.outgoing
        } else {
            &mut self.incoming
        };
        let Some(mut t) = slot.take() else {
            if p.kind == CANCEL {
                if let Some(mut saved) = self.lookup(&p, outgoing)?
                    && !["saved", "received"].contains(&saved.state.as_str())
                {
                    saved.state = "canceled".into();
                    self.save(&saved)?;
                }
                return Ok(());
            }
            if self
                .lookup(&p, outgoing)?
                .is_some_and(|s| s.state == "canceled")
            {
                return Ok(());
            }
            anyhow::bail!("unknown file task");
        };
        ensure!(
            t.saved.info.id == p.id && t.saved.source == p.source && t.saved.info.size == p.size,
            "file task identity/size changed"
        );
        match p.kind {
            ACCEPT => {
                ensure!(
                    outgoing && t.saved.state == "offered" && p.offset == 0 && p.window > 0,
                    "invalid file accept"
                );
                t.window = p.window;
                t.active = true;
                t.saved.state = "sending".into();
            }
            RESUME => {
                ensure!(
                    outgoing && p.window > 0 && p.offset == p.verified && p.offset == p.durable,
                    "invalid resume"
                );
                t.whole = hash_prefix(&mut t.file, p.offset)?;
                ensure!(
                    Some(<[u8; 32]>::from(t.whole.clone().finalize())) == p.hash,
                    "source prefix does not match receiver"
                );
                t.sent = p.offset;
                t.credit = p.offset;
                t.verified = p.offset;
                t.window = p.window;
                t.active = true;
                t.saved.offset = p.offset;
                t.saved.state = "sending".into();
                t.block = Sha256::new();
            }
            CREDIT => {
                ensure!(
                    outgoing
                        && p.offset >= t.credit
                        && p.offset <= t.sent
                        && p.verified >= t.verified
                        && p.window > 0,
                    "invalid credit"
                );
                t.credit = p.offset;
                t.window = p.window;
                t.verified = p.verified;
                t.saved.offset = p.durable;
            }
            DATA => {
                if !t.active {
                    self.incoming = Some(t);
                    return Ok(());
                }
                ensure!(
                    !outgoing
                        && p.offset == t.sent
                        && p.data.len() as u64 <= BLOCK - (t.sent - t.verified)
                        && p.data.len() as u64 <= WINDOW - (t.sent - t.credit),
                    "out-of-order/excess file data"
                );
                t.file.write_all(&p.data)?;
                t.whole.update(&p.data);
                t.block.update(&p.data);
                t.sent += p.data.len() as u64;
                if t.sent - t.credit >= 128 * 1024 || t.sent == p.size {
                    let mut r = p.reply(CREDIT);
                    r.offset = t.sent;
                    r.verified = t.verified;
                    r.durable = t.saved.offset;
                    r.window = WINDOW;
                    send(r.frame()?)?;
                    t.credit = t.sent;
                }
            }
            BLOCK_HASH => {
                if !t.active {
                    self.incoming = Some(t);
                    return Ok(());
                }
                ensure!(
                    !outgoing
                        && p.offset == t.sent
                        && t.sent > t.verified
                        && (t.sent - t.verified == BLOCK || t.sent == p.size),
                    "invalid block boundary"
                );
                let hash: [u8; 32] = std::mem::take(&mut t.block).finalize().into();
                ensure!(Some(hash) == p.hash, "file block hash mismatch");
                t.verified = t.sent;
                t.file.sync_all()?;
                t.saved.offset = t.verified;
                t.saved.hash = hex::encode(t.whole.clone().finalize());
                self.save(&t.saved)?;
                let mut r = p.reply(CREDIT);
                r.offset = t.sent;
                r.verified = t.verified;
                r.durable = t.saved.offset;
                r.window = WINDOW;
                send(r.frame()?)?;
                t.credit = t.sent;
            }
            END => {
                if !t.active {
                    self.incoming = Some(t);
                    return Ok(());
                }
                ensure!(
                    !outgoing && t.sent == p.size && t.verified == p.size,
                    "premature file end"
                );
                let hash: [u8; 32] = t.whole.clone().finalize().into();
                ensure!(Some(hash) == p.hash, "whole-file hash mismatch");
                t.file.sync_all()?;
                drop(t.file);
                // A same-filesystem hard link publishes without replacing an existing name.
                fs::hard_link(&t.saved.path, &t.saved.destination)
                    .context("cannot publish received file without overwrite")?;
                profile::sync_dir(t.saved.destination.parent().unwrap())?;
                t.saved.offset = p.size;
                t.saved.hash = hex::encode(hash);
                t.saved.state = "received".into();
                self.save(&t.saved)?;
                fs::remove_file(&t.saved.path)?;
                profile::sync_dir(t.saved.path.parent().unwrap())?;
                let mut r = p.reply(COMPLETE);
                r.offset = p.size;
                r.hash = Some(hash);
                send(r.frame()?)?;
                return Ok(());
            }
            COMPLETE => {
                ensure!(outgoing, "invalid saved file receipt direction");
                if t.saved.state != "awaiting_saved" {
                    t.whole = hash_prefix(&mut t.file, t.saved.info.size)?;
                }
                ensure!(
                    p.hash == Some(t.whole.clone().finalize().into()),
                    "invalid saved file receipt"
                );
                t.saved.offset = p.size;
                t.saved.hash = hex::encode(p.hash.unwrap());
                t.saved.state = "saved".into();
                self.save(&t.saved)?;
                return Ok(());
            }
            CANCEL | PAUSE => {
                t.active = false;
                t.saved.state = if p.kind == PAUSE {
                    "paused"
                } else if p.reason == "failed" || p.reason == "tooLarge" {
                    "failed"
                } else {
                    "canceled"
                }
                .into();
                if !outgoing {
                    t.file.set_len(t.verified)?;
                    t.file.sync_all()?;
                    t.saved.offset = t.verified;
                    let h = hash_prefix(&mut t.file, t.verified)?;
                    t.whole = h;
                    t.saved.hash = hex::encode(t.whole.clone().finalize());
                }
                self.save(&t.saved)?;
            }
            _ => anyhow::bail!("unsupported file transition"),
        }
        if outgoing {
            self.outgoing = Some(t);
        } else {
            self.incoming = Some(t);
        }
        Ok(())
    }
    pub fn can_pump(&self) -> bool {
        self.outgoing.as_ref().is_some_and(|t| {
            t.active && (t.sent == t.saved.info.size || t.sent < t.credit.saturating_add(t.window))
        })
    }
    pub fn pump(&mut self, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        let Some(mut t) = self.outgoing.take() else {
            return Ok(());
        };
        if t.active {
            for _ in 0..4 {
                if t.sent == t.saved.info.size {
                    break;
                }
                let n = CHUNK
                    .min((t.saved.info.size - t.sent) as usize)
                    .min((BLOCK - t.sent % BLOCK) as usize);
                if t.sent + n as u64 > t.credit.saturating_add(t.window) {
                    break;
                }
                let mut data = vec![0; n];
                t.file.read_exact(&mut data)?;
                t.whole.update(&data);
                t.block.update(&data);
                let mut p = self.packet(&t.saved, DATA);
                p.offset = t.sent;
                p.data = data;
                send(p.frame()?)?;
                t.sent += n as u64;
                if t.sent % BLOCK == 0 || t.sent == t.saved.info.size {
                    let mut p = self.packet(&t.saved, BLOCK_HASH);
                    p.offset = t.sent;
                    p.hash = Some(std::mem::take(&mut t.block).finalize().into());
                    send(p.frame()?)?;
                }
            }
            if t.sent == t.saved.info.size {
                let m = t.file.metadata()?;
                ensure!(
                    m.len() == t.saved.info.size && modified(&m) == t.saved.modified,
                    "source changed during transfer"
                );
                let mut p = self.packet(&t.saved, END);
                p.offset = t.sent;
                p.hash = Some(t.whole.clone().finalize().into());
                t.saved.hash = hex::encode(p.hash.unwrap());
                t.saved.state = "awaiting_saved".into();
                t.active = false;
                self.save(&t.saved)?;
                send(p.frame()?)?;
            }
            let mut notice = t.saved.clone();
            notice.offset = t.sent;
            self.notices.push(notice);
        }
        self.outgoing = Some(t);
        Ok(())
    }
    pub fn disconnect(&mut self) {
        for mut t in [self.incoming.take(), self.outgoing.take()]
            .into_iter()
            .flatten()
        {
            if ["saved", "received", "canceled"].contains(&t.saved.state.as_str()) {
                continue;
            }
            t.saved.state = "paused".into();
            if !t.saved.outgoing {
                // Only previously forced and committed bytes survive reconnect.
                let _ = t.file.set_len(t.saved.offset);
                let _ = t.file.sync_all();
            }
            let _ = self.save(&t.saved);
        }
    }
}
fn reject(p: &Packet, reason: &str, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
    let mut r = p.reply(CANCEL);
    r.reason = reason.into();
    send(r.frame()?)
}
fn modified(m: &fs::Metadata) -> u64 {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_nanos() as u64)
}
fn open_existing(path: &Path) -> Result<File> {
    ensure!(
        fs::symlink_metadata(path)?.is_file(),
        "partial file must be regular"
    );
    let mut o = fs::OpenOptions::new();
    o.read(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.custom_flags(libc::O_NOFOLLOW);
    }
    Ok(o.open(path)?)
}
fn hash_prefix(file: &mut File, n: u64) -> Result<Sha256> {
    file.seek(SeekFrom::Start(0))?;
    let mut h = Sha256::new();
    let mut left = n;
    let mut b = [0; CHUNK];
    while left > 0 {
        let count = (left as usize).min(b.len());
        file.read_exact(&mut b[..count])?;
        h.update(&b[..count]);
        left -= count as u64;
    }
    Ok(h)
}
fn safe_name(name: &str) -> String {
    let clean:String=name.chars().filter(|c|!matches!(*c,'\u{061c}'|'\u{200e}'|'\u{200f}'|'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')).map(|c|if c.is_control()||"/\\:*?\"<>|".contains(c) {'_'}else{c}).collect();
    let mut clean = clean.trim_end_matches(['.', ' ']).to_string();
    while clean.len() > 180 {
        clean.pop();
    }
    if clean.is_empty() {
        "attachment".into()
    } else {
        clean
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_cannot_escape_directory() {
        assert!(!safe_name("../../bad\u{202e}.exe").contains('/'));
        assert!(!safe_name("file\u{202e}.exe").contains('\u{202e}'));
    }
}

#[cfg(test)]
mod recovery_tests {
    use super::*;
    use std::collections::VecDeque;
    struct Pair {
        a: Engine,
        b: Engine,
        ab: VecDeque<Frame>,
        ba: VecDeque<Frame>,
        _dir: tempfile::TempDir,
        content: Vec<u8>,
        source: PathBuf,
    }
    impl Pair {
        fn new(size: usize) -> Self {
            let dir = tempfile::tempdir().unwrap();
            let a = Arc::new(Mutex::new(
                Profile::open(dir.path().join("a"), None).unwrap(),
            ));
            let b = Arc::new(Mutex::new(
                Profile::open(dir.path().join("b"), None).unwrap(),
            ));
            let content: Vec<u8> = (0..size).map(|n| (n * 37) as u8).collect();
            let source = dir.path().join("source");
            fs::write(&source, &content).unwrap();
            let connection = "a".repeat(64);
            Self {
                a: Engine::new(
                    a,
                    "peer-b".into(),
                    "root-b".into(),
                    connection.clone(),
                    dir.path().join("ra"),
                ),
                b: Engine::new(
                    b,
                    "peer-a".into(),
                    "root-a".into(),
                    connection,
                    dir.path().join("rb"),
                ),
                ab: VecDeque::new(),
                ba: VecDeque::new(),
                _dir: dir,
                content,
                source,
            }
        }
        fn offer(&mut self) -> String {
            self.a
                .offer(&self.source, &mut |f| {
                    self.ab.push_back(f);
                    Ok(())
                })
                .unwrap()
        }
        fn tick(&mut self) {
            while let Some(f) = self.ab.pop_front() {
                self.b
                    .handle(Packet::decode(&f.data).unwrap(), &mut |f| {
                        self.ba.push_back(f);
                        Ok(())
                    })
                    .unwrap();
            }
            while let Some(f) = self.ba.pop_front() {
                self.a
                    .handle(Packet::decode(&f.data).unwrap(), &mut |f| {
                        self.ab.push_back(f);
                        Ok(())
                    })
                    .unwrap();
            }
            self.a
                .pump(&mut |f| {
                    self.ab.push_back(f);
                    Ok(())
                })
                .unwrap();
        }
        fn finish(&mut self) {
            for _ in 0..500 {
                self.tick();
                if self.a.outgoing.is_none() {
                    break;
                }
            }
            assert!(self.a.outgoing.is_none(), "send did not complete");
            let s = self
                .b
                .store
                .lock()
                .unwrap()
                .transfers::<Saved>()
                .unwrap()
                .into_iter()
                .find(|s| s.state == "received")
                .unwrap();
            assert_eq!(fs::read(s.destination).unwrap(), self.content);
        }
        fn reconnect(&mut self) {
            self.a.disconnect();
            self.b.disconnect();
            self.ab.clear();
            self.ba.clear();
            self.a.connection = "b".repeat(64);
            self.b.connection = "b".repeat(64);
        }
    }
    #[test]
    fn stream_empty_and_multiple_blocks() {
        for size in [
            0,
            1,
            CHUNK,
            CHUNK + 1,
            BLOCK as usize,
            3 * BLOCK as usize + 17,
        ] {
            let mut p = Pair::new(size);
            p.offer();
            p.finish();
        }
    }
    #[test]
    fn disconnect_resume_and_corrupt_prefix() {
        let mut p = Pair::new(4 * BLOCK as usize + 17);
        let id = p.offer();
        for _ in 0..11 {
            p.tick();
        }
        assert!(p.b.incoming.as_ref().unwrap().saved.offset >= BLOCK);
        p.reconnect();
        p.a.resume(&id, &mut |f| {
            p.ab.push_back(f);
            Ok(())
        })
        .unwrap();
        p.finish();
        let mut p = Pair::new(4 * BLOCK as usize);
        let id = p.offer();
        for _ in 0..11 {
            p.tick();
        }
        p.reconnect();
        let saved =
            p.b.store
                .lock()
                .unwrap()
                .transfers::<Saved>()
                .unwrap()
                .remove(0);
        let mut f = fs::OpenOptions::new().write(true).open(saved.path).unwrap();
        f.write_all(b"bad").unwrap();
        p.a.resume(&id, &mut |f| {
            p.ab.push_back(f);
            Ok(())
        })
        .unwrap();
        let packet = Packet::decode(&p.ab.pop_front().unwrap().data).unwrap();
        assert!(p.b.handle(packet, &mut |_| Ok(())).is_err());
    }
    #[test]
    fn lost_saved_receipt_and_cancel_tombstone() {
        let mut p = Pair::new(CHUNK);
        let id = p.offer();
        for _ in 0..20 {
            p.tick();
            if p.b.incoming.is_none()
                && p.b
                    .store
                    .lock()
                    .unwrap()
                    .transfers::<Saved>()
                    .unwrap()
                    .iter()
                    .any(|s| s.state == "received")
            {
                break;
            }
        }
        p.reconnect();
        p.a.resume(&id, &mut |f| {
            p.ab.push_back(f);
            Ok(())
        })
        .unwrap();
        p.finish();
        let mut p = Pair::new(4 * BLOCK as usize);
        let id = p.offer();
        for _ in 0..5 {
            p.tick();
        }
        p.a.pause(&id, true, &mut |f| {
            p.ab.push_back(f);
            Ok(())
        })
        .unwrap();
        while let Some(f) = p.ab.pop_front() {
            p.b.handle(Packet::decode(&f.data).unwrap(), &mut |_| Ok(()))
                .unwrap();
        }
        assert!(p.a.resume(&id, &mut |_| Ok(())).is_err());
    }
    #[test]
    fn changed_root_cannot_resume() {
        let mut p = Pair::new(4 * BLOCK as usize);
        let id = p.offer();
        for _ in 0..11 {
            p.tick();
        }
        p.reconnect();
        p.a.root = "different-root".into();
        assert!(p.a.resume(&id, &mut |_| Ok(())).is_err());
    }
}

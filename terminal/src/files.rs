//! Streaming attachment-v2 with bounded windows and durable, identity-bound checkpoints.
use crate::{
    profile::{self, Profile},
    wire::*,
};
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
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
    pub queued: bool,
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
    queued: VecDeque<Saved>,
    queue_loaded: bool,
    reserved: Option<Saved>,
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
            queued: VecDeque::new(),
            queue_loaded: false,
            reserved: None,
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
        Ok(self.offer_batch(&[path.to_path_buf()], send)?.remove(0))
    }
    pub fn offer_batch(
        &mut self,
        paths: &[PathBuf],
        send: &mut impl FnMut(Frame) -> Result<()>,
    ) -> Result<Vec<String>> {
        self.load_queue()?;
        ensure!(
            !paths.is_empty() && paths.len() <= 256 && paths.len() <= 256 - self.queued.len(),
            "select 1-256 files within queue capacity"
        );
        let selected = paths
            .iter()
            .map(|path| self.prepare_source(path))
            .collect::<Result<Vec<_>>>()?;
        let ids = selected.iter().map(|s| s.info.id.clone()).collect();
        self.store
            .lock()
            .unwrap()
            .transfer_batch(&selected.iter().map(|s| (s.key(), s)).collect::<Vec<_>>())?;
        self.notices.extend(selected.iter().cloned());
        self.queued.extend(selected);
        self.start_next(send)?;
        Ok(ids)
    }
    fn prepare_source(&self, path: &Path) -> Result<Saved> {
        let path = path.canonicalize()?;
        let file = open_regular(&path, false)?;
        let m = file.metadata()?;
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
            mime: content_mime(&path)?.into(),
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
            state: "queued".into(),
            modified: modified(&m),
            manual_pause: false,
            queued: true,
            failure: String::new(),
        };
        let mut p = self.packet(&s, OFFER);
        p.info = Some(s.info.clone());
        p.validate()?;
        Ok(s)
    }
    fn load_queue(&mut self) -> Result<()> {
        if !self.queue_loaded {
            let entries = self
                .store
                .lock()
                .unwrap()
                .queued_transfers::<Saved>(&self.peer, &self.root)?;
            ensure!(entries.len() <= 256, "stored file queue exceeds its limit");
            self.queued = entries.into();
            if self.outgoing.is_none() {
                self.reserved = self
                    .store
                    .lock()
                    .unwrap()
                    .pending_sender(&self.peer, &self.root)?;
            }
            self.queue_loaded = true;
        }
        Ok(())
    }
    fn start_next(&mut self, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        if self
            .outgoing
            .as_ref()
            .is_some_and(|t| matches!(t.saved.state.as_str(), "canceled" | "failed" | "saved"))
        {
            self.outgoing = None;
        }
        if self.outgoing.is_some() || self.reserved.is_some() {
            return Ok(());
        }
        while let Some(mut s) = self.queued.pop_front() {
            let prepared = (|| -> Result<File> {
                let file = open_regular(&s.path, false)?;
                let m = file.metadata()?;
                ensure!(
                    m.is_file() && m.len() == s.info.size && modified(&m) == s.modified,
                    "queued source changed"
                );
                Ok(file)
            })();
            let file = match prepared {
                Ok(file) => file,
                Err(error) => {
                    s.state = "failed".into();
                    s.failure = format!("{error:#}");
                    self.save(&s)?;
                    continue;
                }
            };
            s.state = "offered".into();
            s.queued = false;
            self.save(&s)?;
            let mut p = self.packet(&s, OFFER);
            p.info = Some(s.info.clone());
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
            break;
        }
        Ok(())
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
        if s.queued {
            self.load_queue()?;
            if !self.queued.iter().any(|q| q.info.id == s.info.id) {
                ensure!(self.queued.len() < 256, "file queue is full");
                s.state = "queued".into();
                self.save(&s)?;
                self.queued.push_back(s);
            }
            return self.start_next(send);
        }
        self.save(&s)?;
        let p = self.packet(&s, STATUS);
        if s.outgoing {
            self.restore_sender(s)?;
        }
        send(p.frame()?)
    }
    fn restore_sender(&mut self, s: Saved) -> Result<()> {
        ensure!(
            self.reserved
                .as_ref()
                .is_none_or(|r| r.info.id == s.info.id),
            "resume or cancel the earlier paused transfer first"
        );
        ensure!(
            self.outgoing
                .as_ref()
                .is_none_or(|t| matches!(t.saved.state.as_str(), "paused" | "canceled")
                    || t.saved.info.id == s.info.id),
            "another send is active"
        );
        let file = open_regular(&s.path, false)?;
        let m = file.metadata()?;
        ensure!(
            m.is_file() && m.len() == s.info.size && modified(&m) == s.modified,
            "source changed; refusing resume"
        );
        self.reserved = None;
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
        self.load_queue()?;
        if let Some(index) = self.queued.iter().position(|s| s.info.id == id) {
            let mut saved = self.queued.remove(index).unwrap();
            saved.state = if cancel { "canceled" } else { "paused" }.into();
            saved.manual_pause = !cancel;
            self.save(&saved)?;
            return Ok(());
        }
        let mut found = false;
        for outgoing in [true, false] {
            let slot = if outgoing {
                &mut self.outgoing
            } else {
                &mut self.incoming
            };
            if slot.as_ref().is_some_and(|t| t.saved.info.id == id) {
                ensure!(
                    cancel || slot.as_ref().unwrap().saved.state != "offered",
                    "wait for file acceptance before pausing; cancel remains available"
                );
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
            if self.reserved.as_ref().is_some_and(|r| r.info.id == id) {
                self.reserved = None;
            }
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
                        queued: false,
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
                queued: false,
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
                let mut f = open_regular(&s.destination, false)?;
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
            if self.lookup(&p, outgoing)?.is_some_and(|s| {
                s.state == "canceled"
                    || (matches!(s.state.as_str(), "saved" | "received")
                        && matches!(p.kind, PAUSE | CANCEL))
            }) {
                return Ok(());
            }
            anyhow::bail!("unknown file task");
        };
        ensure!(
            t.saved.info.id == p.id && t.saved.source == p.source && t.saved.info.size == p.size,
            "file task identity/size changed"
        );
        if t.saved.state == "canceled" {
            if outgoing {
                self.outgoing = Some(t);
            } else {
                self.incoming = Some(t);
            }
            return Ok(());
        }
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
                t.saved.offset = p.size;
                t.saved.hash = hex::encode(hash);
                t.saved.state = "publishing".into();
                self.save(&t.saved)?;
                drop(t.file);
                crate::publish::exclusive(&t.saved.path, &t.saved.destination)
                    .context("cannot publish received file without overwrite")?;
                profile::sync_dir(t.saved.destination.parent().unwrap())?;
                t.saved.offset = p.size;
                t.saved.hash = hex::encode(hash);
                t.saved.state = "received".into();
                self.save(&t.saved)?;
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
        (self.outgoing.is_none() && self.reserved.is_none() && !self.queued.is_empty())
            || self.outgoing.as_ref().is_some_and(|t| {
                t.active
                    && (t.sent == t.saved.info.size || t.sent < t.credit.saturating_add(t.window))
            })
    }
    pub fn pump(&mut self, send: &mut impl FnMut(Frame) -> Result<()>) -> Result<()> {
        self.load_queue()?;
        self.start_next(send)?;
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
fn content_mime(path: &Path) -> Result<&'static str> {
    let mut bytes = Vec::with_capacity(16);
    File::open(path)?.take(16).read_to_end(&mut bytes)?;
    Ok(if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        "image/png"
    } else if bytes.starts_with(b"\xff\xd8\xff") {
        "image/jpeg"
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        "image/gif"
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "image/webp"
    } else if bytes.len() >= 12
        && &bytes[4..8] == b"ftyp"
        && [&b"heic"[..], &b"heix"[..], &b"mif1"[..]].contains(&&bytes[8..12])
    {
        "image/heic"
    } else if bytes.len() >= 12 && &bytes[4..8] == b"ftyp" && &bytes[8..12] == b"avif" {
        "image/avif"
    } else {
        "application/octet-stream"
    })
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
    open_regular(path, true)
}
fn open_regular(path: &Path, writing: bool) -> Result<File> {
    ensure!(
        fs::symlink_metadata(path)?.is_file(),
        "file must be regular"
    );
    let mut o = fs::OpenOptions::new();
    o.read(true).write(writing);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let file = o.open(path)?;
    ensure!(file.metadata()?.is_file(), "file is no longer regular");
    Ok(file)
}
pub fn export(record: &Saved, destination: &Path) -> Result<()> {
    ensure!(
        ["saved", "received"].contains(&record.state.as_str()),
        "only completed files can be exported"
    );
    let source = if record.outgoing {
        &record.path
    } else {
        &record.destination
    };
    let mut input = open_regular(source, false)?;
    ensure!(
        input.metadata()?.len() == record.info.size,
        "completed file size changed"
    );
    let parent = destination
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."))
        .canonicalize()?;
    let destination = parent.join(
        destination
            .file_name()
            .context("choose an export filename")?,
    );
    ensure!(
        !destination.try_exists()?,
        "export destination already exists"
    );
    let part = parent.join(format!(".nearbyim-export-{}.part", uuid::Uuid::new_v4()));
    let mut output = profile::private_open(&part, true)?;
    let result = (|| -> Result<()> {
        let mut hash = Sha256::new();
        let mut bytes = [0; CHUNK];
        let mut left = record.info.size;
        while left > 0 {
            let n = left.min(CHUNK as u64) as usize;
            input.read_exact(&mut bytes[..n])?;
            output.write_all(&bytes[..n])?;
            hash.update(&bytes[..n]);
            left -= n as u64;
        }
        ensure!(
            input.metadata()?.len() == record.info.size
                && hex::encode(hash.finalize()) == record.hash,
            "completed file hash changed"
        );
        output.sync_all()?;
        drop(output);
        crate::publish::exclusive(&part, &destination)?;
        profile::sync_dir(&parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&part);
    }
    result
}
pub fn reveal(record: &Saved) -> Result<()> {
    use std::process::{Command, Stdio};
    ensure!(
        ["saved", "received"].contains(&record.state.as_str()),
        "only completed files can be revealed"
    );
    let source = if record.outgoing {
        &record.path
    } else {
        &record.destination
    };
    let _ = open_regular(source, false)?;
    let path = source.canonicalize()?;
    #[cfg(windows)]
    let mut command = {
        let mut c = Command::new("explorer.exe");
        c.arg(format!("/select,{}", path.display()));
        c
    };
    #[cfg(target_os = "macos")]
    let mut command = {
        let mut c = Command::new("open");
        c.arg("-R").arg(&path);
        c
    };
    #[cfg(not(any(windows, target_os = "macos")))]
    let mut command = {
        let mut c = Command::new("xdg-open");
        c.arg(path.parent().context("file has no parent directory")?);
        c
    };
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .context("OS file manager unavailable; use files export or the saved location")?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
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
    #[test]
    fn image_offers_preserve_content_type_without_trusting_extensions() {
        for (name, bytes, expected) in [
            ("photo", b"\x89PNG\r\n\x1a\nrest".as_slice(), "image/png"),
            ("photo.data", b"\xff\xd8\xffrest".as_slice(), "image/jpeg"),
            (
                "fake.png",
                b"plain text".as_slice(),
                "application/octet-stream",
            ),
            (
                "truncated.png",
                b"\x89PNG".as_slice(),
                "application/octet-stream",
            ),
        ] {
            let mut pair = Pair::new(0);
            let path = pair._dir.path().join(name);
            fs::write(&path, bytes).unwrap();
            pair.a
                .offer(&path, &mut |frame| {
                    pair.ab.push_back(frame);
                    Ok(())
                })
                .unwrap();
            let packet = Packet::decode(&pair.ab.pop_front().unwrap().data).unwrap();
            assert_eq!(packet.info.unwrap().mime, expected, "{name}");
        }
    }
    #[test]
    fn queued_files_keep_both_tasks_and_bytes() {
        let mut pair = Pair::new(CHUNK * 3 + 7);
        let first = pair.offer();
        let second_path = pair._dir.path().join("second.bin");
        let second_bytes = vec![93; CHUNK + 11];
        fs::write(&second_path, &second_bytes).unwrap();
        let second = pair
            .a
            .offer(&second_path, &mut |f| {
                pair.ab.push_back(f);
                Ok(())
            })
            .unwrap();
        for _ in 0..500 {
            pair.tick();
        }
        let received = pair.b.store.lock().unwrap().transfers::<Saved>().unwrap();
        for (id, bytes) in [(&first, &pair.content), (&second, &second_bytes)] {
            let saved = received
                .iter()
                .find(|s| &s.info.id == id && s.state == "received")
                .expect("both queued files must be saved");
            assert_eq!(fs::read(&saved.destination).unwrap(), *bytes);
        }
        let sent = pair.a.store.lock().unwrap().transfers::<Saved>().unwrap();
        assert_eq!(sent.iter().filter(|s| s.state == "saved").count(), 2);
    }
    #[test]
    fn waiting_queue_survives_a_real_profile_restart() {
        let mut pair = Pair::new(CHUNK + 17);
        let first = pair.offer();
        let second_path = pair._dir.path().join("second.bin");
        fs::write(&second_path, [93; 19]).unwrap();
        let second = pair.a.offer(&second_path, &mut |_| Ok(())).unwrap();
        pair.a.pause(&first, true, &mut |_| Ok(())).unwrap();
        pair.ab.clear();
        let path = pair.a.store.lock().unwrap().path.clone();
        drop(pair.a);
        pair.a = Engine::new(
            Arc::new(Mutex::new(Profile::open(path, None).unwrap())),
            "peer-b".into(),
            "root-b".into(),
            "a".repeat(64),
            pair._dir.path().join("ra"),
        );
        for _ in 0..100 {
            pair.tick();
        }
        let records = pair.b.store.lock().unwrap().transfers::<Saved>().unwrap();
        let saved = records
            .iter()
            .find(|s| s.info.id == second && s.state == "received")
            .expect("waiting selections must survive restarting the database/profile");
        assert_eq!(fs::read(&saved.destination).unwrap(), [93; 19]);
    }
    #[test]
    fn invalid_batch_and_second_database_write_leave_no_partial_queue() {
        let mut pair = Pair::new(19);
        let second = pair._dir.path().join("second.bin");
        fs::write(&second, [7; 23]).unwrap();
        let missing = pair._dir.path().join("missing.bin");
        assert!(
            pair.a
                .offer_batch(&[pair.source.clone(), missing], &mut |_| panic!(
                    "invalid batch emitted OFFER"
                ))
                .is_err()
        );
        assert!(
            pair.a
                .store
                .lock()
                .unwrap()
                .transfers::<Saved>()
                .unwrap()
                .is_empty()
        );
        pair.a.store.lock().unwrap().db.execute_batch("CREATE TRIGGER reject_second BEFORE INSERT ON transfers WHEN json_extract(NEW.value,'$.info.name')='second.bin' BEGIN SELECT RAISE(ABORT,'test storage failure'); END;").unwrap();
        assert!(
            pair.a
                .offer_batch(&[pair.source.clone(), second.clone()], &mut |_| panic!(
                    "failed transaction emitted OFFER"
                ))
                .is_err()
        );
        assert!(
            pair.a
                .store
                .lock()
                .unwrap()
                .transfers::<Saved>()
                .unwrap()
                .is_empty()
        );
        assert!(pair.a.queued.is_empty());
        pair.a
            .store
            .lock()
            .unwrap()
            .db
            .execute_batch("DROP TRIGGER reject_second")
            .unwrap();
        assert_eq!(
            pair.a
                .offer_batch(&[pair.source.clone(), second], &mut |_| Ok(()))
                .unwrap()
                .len(),
            2
        );
    }
    #[test]
    fn profile_restart_keeps_a_paused_active_transfer_ahead_of_waiting_files() {
        let mut pair = Pair::new(2 * BLOCK as usize + 17);
        let first = pair.offer();
        let second = pair.a.offer(&pair.source, &mut |_| Ok(())).unwrap();
        pair.tick();
        pair.tick();
        pair.a
            .pause(&first, false, &mut |f| {
                pair.ab.push_back(f);
                Ok(())
            })
            .unwrap();
        pair.tick();
        pair.a.disconnect();
        pair.b.disconnect();
        pair.ab.clear();
        pair.ba.clear();
        let path = pair.a.store.lock().unwrap().path.clone();
        drop(pair.a);
        pair.a = Engine::new(
            Arc::new(Mutex::new(Profile::open(path, None).unwrap())),
            "peer-b".into(),
            "root-b".into(),
            "b".repeat(64),
            pair._dir.path().join("ra"),
        );
        pair.b.connection = "b".repeat(64);
        pair.a
            .pump(&mut |_| panic!("waiting file bypassed the paused active selection"))
            .unwrap();
        pair.a
            .resume(&first, &mut |f| {
                pair.ab.push_back(f);
                Ok(())
            })
            .unwrap();
        for _ in 0..500 {
            pair.tick();
        }
        let received = pair.b.store.lock().unwrap().transfers::<Saved>().unwrap();
        for id in [first, second] {
            let saved = received
                .iter()
                .find(|s| s.info.id == id && s.state == "received")
                .expect("resuming the first transfer must release the persisted second selection");
            assert_eq!(fs::read(&saved.destination).unwrap(), pair.content);
        }
    }
    #[test]
    fn completed_file_export_checks_hash_and_does_not_overwrite() {
        let mut pair = Pair::new(CHUNK + 17);
        let id = pair.offer();
        pair.finish();
        let saved = pair
            .b
            .store
            .lock()
            .unwrap()
            .transfers::<Saved>()
            .unwrap()
            .into_iter()
            .find(|s| s.info.id == id)
            .unwrap();
        let destination = pair._dir.path().join("exported.bin");
        export(&saved, &destination).unwrap();
        assert_eq!(fs::read(&destination).unwrap(), pair.content);
        assert!(export(&saved, &destination).is_err());
        assert_eq!(fs::read(&destination).unwrap(), pair.content);
        fs::write(&saved.destination, vec![88; pair.content.len()]).unwrap();
        let corrupt = pair._dir.path().join("corrupt.bin");
        assert!(export(&saved, &corrupt).is_err());
        assert!(!corrupt.exists());
        assert!(!fs::read_dir(pair._dir.path()).unwrap().any(|e| {
            e.unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".nearbyim-export-")
        }));
    }
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
    fn late_receipt_cannot_revive_canceled_task() {
        let mut p = Pair::new(CHUNK);
        let id = p.offer();
        p.tick();
        // Deliver data/END, retaining the receiver's saved receipt in flight.
        while let Some(f) = p.ab.pop_front() {
            p.b.handle(Packet::decode(&f.data).unwrap(), &mut |f| {
                p.ba.push_back(f);
                Ok(())
            })
            .unwrap();
        }
        assert!(p.b.incoming.is_none());
        p.a.pause(&id, true, &mut |_| Ok(())).unwrap();
        while let Some(f) = p.ba.pop_front() {
            p.a.handle(Packet::decode(&f.data).unwrap(), &mut |_| Ok(()))
                .unwrap();
        }
        assert_eq!(
            p.a.store.lock().unwrap().transfers::<Saved>().unwrap()[0].state,
            "canceled"
        );
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

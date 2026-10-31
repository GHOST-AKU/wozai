//! Private, locked state. Dedupe metadata is durable; message bodies are not.
use crate::protocol::Identity;
use anyhow::{Context, Result, bail};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub seen: BTreeMap<String, u64>,
    pub pending_lost: usize,
    pub saved_at: u64,
}
pub struct Store {
    dir: PathBuf,
    _lock: File,
}
fn private_open(path: &Path, create_new: bool) -> Result<File> {
    if fs::symlink_metadata(path).is_ok_and(|m| m.file_type().is_symlink()) {
        bail!("state symlink rejected");
    }
    let mut o = OpenOptions::new();
    o.read(true).write(true);
    if create_new {
        o.create_new(true);
    } else {
        o.create(true).truncate(false);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    Ok(o.open(path)?)
}
impl Store {
    pub fn open(dir: &Path) -> Result<Self> {
        if fs::symlink_metadata(dir).is_ok_and(|m| m.file_type().is_symlink()) {
            bail!("state directory symlink rejected");
        }
        fs::create_dir_all(dir)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(dir, fs::Permissions::from_mode(0o700))?;
        }
        let lock = private_open(&dir.join("lock"), false)?;
        lock.try_lock_exclusive()
            .context("state locked by another process")?;
        Ok(Self {
            dir: dir.into(),
            _lock: lock,
        })
    }
    pub fn identity(&self) -> Result<Identity> {
        let path = self.dir.join("identity.seed");
        if path.exists() {
            let f = private_open(&path, false)?;
            let mut bytes = Vec::new();
            f.take(33).read_to_end(&mut bytes)?;
            if bytes.len() != 32 {
                bail!("invalid identity seed length");
            }
            let seed: [u8; 32] = bytes.as_slice().try_into()?;
            Ok(Identity::from_seed(seed))
        } else {
            let id = Identity::generate();
            let mut f = private_open(&path, true)?;
            f.write_all(&id.seed())?;
            f.sync_all()?;
            self.sync_dir()?;
            Ok(id)
        }
    }
    pub fn load(&self) -> Result<Snapshot> {
        let path = self.dir.join("seen.json");
        if !path.exists() {
            return Ok(Snapshot::default());
        }
        let mut bytes = Vec::new();
        private_open(&path, false)?
            .take(131073)
            .read_to_end(&mut bytes)?;
        if bytes.len() > 131072 {
            bail!("state size bound");
        }
        serde_json::from_slice(&bytes)
            .context("invalid dedupe state; refusing to forget replay protection")
    }
    pub fn checkpoint(&self, core: &crate::core::Core) -> Result<()> {
        self.save(&core.seen, core.pending_len(), core.clock_floor())
    }
    pub fn save(&self, seen: &BTreeMap<String, u64>, pending: usize, now: u64) -> Result<()> {
        let bytes = serde_json::to_vec(&Snapshot {
            seen: seen.clone(),
            pending_lost: pending,
            saved_at: now,
        })?;
        if bytes.len() > 131072 {
            bail!("state size bound");
        }
        let tmp = self.dir.join("seen.tmp");
        let mut file = private_open(&tmp, false)?;
        file.set_len(0)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        fs::rename(tmp, self.dir.join("seen.json"))?;
        self.sync_dir()
    }
    fn sync_dir(&self) -> Result<()> {
        #[cfg(unix)]
        File::open(&self.dir)?.sync_all()?;
        Ok(())
    }
}

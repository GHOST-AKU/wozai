//! A terminal-only profile. Corrupt or missing established identities are fatal.
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use fs2::FileExt;
use p256::{
    ecdsa::SigningKey,
    pkcs8::{DecodePublicKey, EncodePublicKey},
};
use rand::rngs::OsRng;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use zeroize::Zeroizing;

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
#[derive(Clone)]
pub struct Identity {
    pub id: String,
    pub name: String,
    pub key: SigningKey,
    pub public: Vec<u8>,
}
#[derive(Serialize, Deserialize)]
struct IdentityFile {
    schema: u8,
    id: String,
    name: String,
    protected_key: String,
}
#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Peer {
    pub id: String,
    pub name: String,
    pub root: String,
    pub remembered: bool,
    pub verified: bool,
    pub endpoint: String,
    #[serde(default)]
    pub capabilities: u32,
}
#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct Message {
    pub id: String,
    pub peer: String,
    pub outgoing: bool,
    pub body: String,
    pub time: u64,
    pub state: String,
}

pub struct Profile {
    pub path: PathBuf,
    pub identity: Identity,
    pub db: Connection,
    _lock: File,
}
impl Profile {
    pub fn open(path: PathBuf, name: Option<&str>) -> Result<Self> {
        if !path.exists() {
            private_dir(&path)?;
        }
        ensure!(
            fs::symlink_metadata(&path)?.is_dir(),
            "profile must be a real directory"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            let m = fs::metadata(&path)?;
            ensure!(
                m.uid() == unsafe { libc::geteuid() } && m.mode() & 0o077 == 0,
                "profile must belong to this user with mode 0700"
            );
        }
        ensure!(
            !path.join("identity.properties").exists()
                && !path.join("noise-static-v4.properties").exists(),
            "this is a GUI profile; choose a separate Terminal directory"
        );
        let lockpath = path.join("profile.lock");
        let lock = private_open(&lockpath, false)?;
        lock.try_lock_exclusive()
            .context("profile is busy; close the other nearbyim process or use --profile")?;
        let identitypath = path.join("identity.json");
        let identity = if identitypath.exists() {
            ensure!(
                fs::symlink_metadata(&identitypath)?.is_file(),
                "identity must be a regular file"
            );
            let bytes = read_private(&identitypath, 4096)?;
            let saved: IdentityFile = serde_json::from_slice(&bytes)
                .context("identity is corrupt; refusing to replace it")?;
            ensure!(
                saved.schema == 1 && crate::wire::uuid(&saved.id, true),
                "invalid stored identity"
            );
            crate::wire::nickname(&saved.name)?;
            let secret = Zeroizing::new(unprotect(&STANDARD.decode(saved.protected_key)?)?);
            let key = SigningKey::from_slice(&secret)
                .context("invalid identity private key; refusing rotation")?;
            let public = key.verifying_key().to_public_key_der()?.as_bytes().to_vec();
            let i = Identity {
                id: saved.id,
                name: name.unwrap_or(&saved.name).into(),
                key,
                public,
            };
            crate::wire::nickname(&i.name)?;
            i
        } else {
            ensure!(
                !path.join("history.sqlite3").exists(),
                "identity missing from an established profile; restore its backup"
            );
            let key = SigningKey::random(&mut OsRng);
            let public = key.verifying_key().to_public_key_der()?.as_bytes().to_vec();
            let i = Identity {
                id: uuid::Uuid::new_v4().to_string(),
                name: name.unwrap_or("NearbyIM Terminal").into(),
                key,
                public,
            };
            crate::wire::nickname(&i.name)?;
            let saved = IdentityFile {
                schema: 1,
                id: i.id.clone(),
                name: i.name.clone(),
                protected_key: STANDARD.encode(protect(&i.key.to_bytes())?),
            };
            atomic_private(&identitypath, &serde_json::to_vec(&saved)?)?;
            i
        };
        let dbpath = path.join("history.sqlite3");
        if !dbpath.exists() {
            private_open(&dbpath, true)?;
        }
        ensure!(
            fs::symlink_metadata(&dbpath)?.is_file(),
            "history must be a regular file"
        );
        let db = Connection::open(dbpath)?;
        db.busy_timeout(std::time::Duration::from_secs(5))?;
        let version: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            version <= 1,
            "unsupported history schema; refusing migration"
        );
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS peers(id TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS messages(peer TEXT NOT NULL,id TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(peer,id));
            CREATE TABLE IF NOT EXISTS drafts(peer TEXT PRIMARY KEY,body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS transfers(key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS avatars(root TEXT PRIMARY KEY,value TEXT NOT NULL,png BLOB NOT NULL,updated INTEGER NOT NULL);
            UPDATE messages SET value=json_set(value,'$.state','unconfirmed') WHERE json_extract(value,'$.state')='pending';
            UPDATE transfers SET value=json_set(value,'$.state','paused') WHERE json_extract(value,'$.state') NOT IN ('saved','received','failed','canceled','paused'); PRAGMA user_version=1;")?;
        Ok(Self {
            path,
            identity,
            db,
            _lock: lock,
        })
    }
    pub fn peer(&self, id: &str) -> Result<Option<Peer>> {
        let v: Option<String> = self
            .db
            .query_row("SELECT value FROM peers WHERE id=?", [id], |r| r.get(0))
            .optional()?;
        v.map(|s| Ok(serde_json::from_str(&s)?)).transpose()
    }
    pub fn peers(&self) -> Result<Vec<Peer>> {
        let mut q = self.db.prepare("SELECT value FROM peers ORDER BY id")?;
        let rows = q.query_map([], |r| r.get::<_, String>(0))?;
        rows.map(|s| Ok(serde_json::from_str(&s?)?)).collect()
    }
    pub fn save_peer(&self, p: &Peer) -> Result<()> {
        self.db.execute(
            "INSERT INTO peers VALUES(?,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value",
            params![p.id, serde_json::to_string(p)?],
        )?;
        Ok(())
    }
    pub fn save_message(&self, m: &Message) -> Result<bool> {
        Ok(self.db.execute(
            "INSERT OR IGNORE INTO messages VALUES(?,?,?)",
            params![m.peer, m.id, serde_json::to_string(m)?],
        )? != 0)
    }
    pub fn message(&self, peer: &str, id: &str) -> Result<Option<Message>> {
        let s: Option<String> = self
            .db
            .query_row(
                "SELECT value FROM messages WHERE peer=? AND id=?",
                params![peer, id],
                |r| r.get(0),
            )
            .optional()?;
        s.map(|v| Ok(serde_json::from_str(&v)?)).transpose()
    }
    pub fn receipt(&self, peer: &str, id: &str) -> Result<()> {
        if let Some(mut m) = self.message(peer, id)? {
            ensure!(m.outgoing, "receipt for incoming text");
            m.state = "saved".into();
            self.db.execute(
                "UPDATE messages SET value=? WHERE peer=? AND id=?",
                params![serde_json::to_string(&m)?, peer, id],
            )?;
        } else {
            anyhow::bail!("receipt for unknown text");
        }
        Ok(())
    }
    pub fn history(&self, peer: Option<&str>) -> Result<Vec<Message>> {
        Ok(self.history_page(peer, None, 500)?.0)
    }
    pub fn history_page(
        &self,
        peer: Option<&str>,
        before: Option<i64>,
        limit: usize,
    ) -> Result<(Vec<Message>, Option<i64>, bool)> {
        ensure!((1..=500).contains(&limit), "history limit must be 1–500");
        let mut q=self.db.prepare("SELECT rowid,value FROM messages WHERE (?1 IS NULL OR peer=?1) AND (?2 IS NULL OR rowid<?2) ORDER BY rowid DESC LIMIT ?3")?;
        let mut rows = q
            .query_map(params![peer, before, limit + 1], |r| {
                Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let more = rows.len() > limit;
        rows.truncate(limit);
        rows.reverse();
        let cursor = rows.first().map(|r| r.0);
        let messages = rows
            .into_iter()
            .map(|(_, v)| Ok(serde_json::from_str(&v)?))
            .collect::<Result<Vec<_>>>()?;
        Ok((messages, cursor, more))
    }
    pub fn draft(&self, peer: &str) -> Result<String> {
        Ok(self
            .db
            .query_row("SELECT body FROM drafts WHERE peer=?", [peer], |r| r.get(0))
            .optional()?
            .unwrap_or_default())
    }
    pub fn save_draft(&self, peer: &str, body: &str) -> Result<()> {
        self.db.execute(
            "INSERT INTO drafts VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET body=excluded.body",
            params![peer, body],
        )?;
        Ok(())
    }
    pub fn transfer<T: Serialize>(&self, key: &str, value: &T) -> Result<()> {
        self.db.execute(
            "INSERT INTO transfers VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, serde_json::to_string(value)?],
        )?;
        Ok(())
    }
    pub fn queued_transfers<T: for<'a> Deserialize<'a>>(
        &self,
        peer: &str,
        root: &str,
    ) -> Result<Vec<T>> {
        let mut q = self.db.prepare("SELECT value FROM transfers WHERE json_extract(value,'$.state')='queued' AND json_extract(value,'$.peer')=? AND json_extract(value,'$.root')=? AND json_extract(value,'$.outgoing')=1 ORDER BY rowid LIMIT 257")?;
        q.query_map(params![peer, root], |r| r.get::<_, String>(0))?
            .map(|s| Ok(serde_json::from_str(&s?)?))
            .collect()
    }
    pub fn transfers<T: for<'a> Deserialize<'a>>(&self) -> Result<Vec<T>> {
        let mut q = self.db.prepare("SELECT value FROM transfers")?;
        q.query_map([], |r| r.get::<_, String>(0))?
            .map(|s| Ok(serde_json::from_str(&s?)?))
            .collect()
    }
}
pub fn fingerprint(root: &[u8]) -> String {
    hex::encode(Sha256::digest(root))
}
pub fn safety(a: &[u8], b: &[u8]) -> String {
    let (a, b) = if a <= b { (a, b) } else { (b, a) };
    let mut h = Sha256::new();
    h.update(b"wozai/identity-verification/v1\0");
    for x in [a, b] {
        h.update((x.len() as u32).to_be_bytes());
        h.update(x);
    }
    hex::encode(h.finalize())
}
pub fn validate_root(root: &[u8]) -> Result<p256::ecdsa::VerifyingKey> {
    let key = p256::PublicKey::from_public_key_der(root)?;
    ensure!(
        key.to_public_key_der()?.as_bytes() == root,
        "noncanonical root key"
    );
    Ok(key.into())
}
pub fn private_dir(path: &Path) -> Result<()> {
    let mut b = fs::DirBuilder::new();
    b.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        b.mode(0o700);
    }
    b.create(path)?;
    Ok(())
}
pub fn private_open(path: &Path, exclusive: bool) -> Result<File> {
    let mut o = OpenOptions::new();
    o.read(true).write(true);
    if exclusive {
        o.create_new(true);
    } else {
        o.create(true).truncate(false);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600).custom_flags(libc::O_NOFOLLOW);
    }
    let f = o.open(path)?;
    ensure!(f.metadata()?.is_file(), "not a regular file");
    Ok(f)
}
fn read_private(path: &Path, max: u64) -> Result<Vec<u8>> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let m = fs::metadata(path)?;
        ensure!(
            m.mode() & 0o077 == 0 && m.uid() == unsafe { libc::geteuid() },
            "private identity permissions are too broad"
        );
    }
    ensure!(fs::metadata(path)?.len() <= max, "identity too large");
    Ok(fs::read(path)?)
}
pub fn sync_dir(path: &Path) -> Result<()> {
    #[cfg(unix)]
    File::open(path)?.sync_all()?;
    let _ = path;
    Ok(())
}
pub fn atomic_private(path: &Path, data: &[u8]) -> Result<()> {
    use std::io::Write;
    let tmp = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let mut f = private_open(&tmp, true)?;
    f.write_all(data)?;
    f.sync_all()?;
    drop(f);
    fs::rename(&tmp, path)?;
    sync_dir(path.parent().unwrap())?;
    Ok(())
}
#[cfg(not(windows))]
fn protect(b: &[u8]) -> Result<Vec<u8>> {
    Ok(b.to_vec())
}
#[cfg(not(windows))]
fn unprotect(b: &[u8]) -> Result<Vec<u8>> {
    Ok(b.to_vec())
}
#[cfg(windows)]
fn protect(b: &[u8]) -> Result<Vec<u8>> {
    dpapi(b, true)
}
#[cfg(windows)]
fn unprotect(b: &[u8]) -> Result<Vec<u8>> {
    dpapi(b, false)
}
#[cfg(windows)]
fn dpapi(b: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    use windows_sys::Win32::{Foundation::LocalFree, Security::Cryptography::*};
    let input = CRYPT_INTEGER_BLOB {
        cbData: b.len() as u32,
        pbData: b.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    unsafe {
        let ok = if encrypt {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        ensure!(ok != 0, "Windows DPAPI identity protection failed");
        let value = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        for i in 0..output.cbData as usize {
            output.pbData.add(i).write_volatile(0);
        }
        LocalFree(output.pbData as _);
        Ok(value)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identity_persists_and_corruption_fails_closed() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("p");
        let a = Profile::open(p.clone(), None).unwrap();
        let root = a.identity.public.clone();
        assert!(Profile::open(p.clone(), None).is_err());
        drop(a);
        let a = Profile::open(p.clone(), None).unwrap();
        assert_eq!(a.identity.public, root);
        drop(a);
        fs::write(p.join("identity.json"), b"bad").unwrap();
        assert!(Profile::open(p, None).is_err());
    }
}

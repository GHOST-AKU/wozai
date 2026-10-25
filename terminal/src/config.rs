//! Explicit, versioned settings. CLI overrides do not silently change persistent policy.
use crate::profile::{self, Profile};
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
#[derive(Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub schema: u8,
    pub nickname: String,
    pub receive_dir: PathBuf,
    pub lan_listen: bool,
    pub auto_receive_trusted: bool,
    pub listen: String,
    pub theme: String,
    pub language: String,
    pub color: String,
    pub command_key: String,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            schema: 1,
            nickname: "NearbyIM Terminal".into(),
            receive_dir: dirs::download_dir()
                .unwrap_or_else(|| dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")))
                .join("NearbyIM Terminal"),
            lan_listen: false,
            auto_receive_trusted: false,
            listen: "0.0.0.0:47832".into(),
            theme: "midnight".into(),
            language: "en".into(),
            color: "auto".into(),
            command_key: "k".into(),
        }
    }
}
impl Config {
    pub fn load(p: &Profile) -> Result<Self> {
        let path = p.path.join("config.json");
        let cfg = if path.exists() {
            ensure!(std::fs::metadata(&path)?.len() <= 8192, "config too large");
            serde_json::from_slice(&std::fs::read(path)?)?
        } else {
            Self {
                nickname: p.identity.name.clone(),
                ..Self::default()
            }
        };
        cfg.validate()?;
        Ok(cfg)
    }
    pub fn save(&self, p: &Profile) -> Result<()> {
        self.validate()?;
        profile::atomic_private(
            &p.path.join("config.json"),
            &serde_json::to_vec_pretty(self)?,
        )
    }
    fn validate(&self) -> Result<()> {
        ensure!(self.schema == 1, "unsupported settings version");
        crate::wire::nickname(&self.nickname)?;
        ensure!(
            !self.receive_dir.as_os_str().is_empty(),
            "receive directory must not be empty"
        );
        ensure!(
            ["midnight", "paper", "sage", "mono", "system"].contains(&self.theme.as_str()),
            "unknown theme"
        );
        ensure!(
            ["auto", "truecolor", "256", "16", "none"].contains(&self.color.as_str()),
            "unknown color mode"
        );
        ensure!(
            ["en", "zh-CN"].contains(&self.language.as_str()),
            "language must be en or zh-CN"
        );
        ensure!(
            ["k", "p"].contains(&self.command_key.as_str()),
            "command_key must be k or p"
        );
        let _: std::net::SocketAddr = self.listen.parse()?;
        Ok(())
    }
    pub fn set(&mut self, key: &str, value: &str) -> Result<()> {
        match key {
            "nickname" => self.nickname = value.into(),
            "receive_dir" => self.receive_dir = value.into(),
            "lan_listen" => self.lan_listen = value.parse()?,
            "auto_receive_trusted" => self.auto_receive_trusted = value.parse()?,
            "listen" => self.listen = value.into(),
            "theme" => self.theme = value.into(),
            "language" => self.language = value.into(),
            "color" => self.color = value.into(),
            "command_key" => self.command_key = value.into(),
            _ => anyhow::bail!("unknown setting"),
        };
        self.validate()
    }
}

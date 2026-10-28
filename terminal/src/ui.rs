//! The terminal view owns no protocol logic; commands go through the shared session core.
use crate::{
    config::Config,
    discovery::{Discovery, Found},
    files::Saved,
    network::{Command, Consent, Event, Runtime},
    profile::{Message, Peer, Profile},
    safe,
};
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use crossterm::{
    event::{
        self, DisableBracketedPaste, EnableBracketedPaste, Event as InputEvent, KeyCode,
        KeyEventKind, KeyModifiers,
    },
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use ratatui::{
    prelude::*,
    widgets::{Block, Borders, Clear, List, ListItem, ListState, Padding, Paragraph, Wrap},
};
use std::{
    collections::{BTreeMap, HashMap, VecDeque},
    io::stdout,
    sync::{Arc, Mutex, mpsc::SyncSender},
    time::{Duration, Instant},
};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

#[derive(Default)]
pub struct Editor {
    pub text: String,
    pub cursor: usize,
    rejected: bool,
}
impl Editor {
    fn insert(&mut self, s: &str) {
        if self.text.len().saturating_add(s.len()) > crate::wire::DRAFT_BYTES {
            self.rejected = true;
            return;
        }
        let s: String = s
            .chars()
            .filter(|c| !c.is_control() || matches!(c, '\n' | '\t'))
            .collect();
        self.rejected = false;
        if self.text.len().saturating_add(s.len()) <= crate::wire::DRAFT_BYTES {
            self.text.insert_str(self.cursor, &s);
            self.cursor += s.len();
        } else {
            self.rejected = true;
        }
    }
    fn left(&mut self) {
        self.cursor = self.text[..self.cursor]
            .grapheme_indices(true)
            .next_back()
            .map_or(0, |(i, _)| i);
    }
    fn right(&mut self) {
        if let Some(s) = self.text[self.cursor..].graphemes(true).next() {
            self.cursor += s.len();
        } else {
            self.rejected = true;
        }
    }
    fn backspace(&mut self) {
        let old = self.cursor;
        self.left();
        self.text.replace_range(self.cursor..old, "");
    }
    fn delete(&mut self) {
        let old = self.cursor;
        self.right();
        self.text.replace_range(old..self.cursor, "");
        self.cursor = old;
    }
}
struct Guard;
impl Drop for Guard {
    fn drop(&mut self) {
        let _ = disable_raw_mode();
        let _ = execute!(
            stdout(),
            DisableBracketedPaste,
            crossterm::cursor::Show,
            crossterm::style::ResetColor,
            LeaveAlternateScreen
        );
    }
}
struct Rate {
    start: Instant,
    initial: u64,
    last_at: Instant,
    last_bytes: u64,
    instant: Option<f64>,
}
impl Rate {
    fn update(&mut self, bytes: u64) {
        let seconds = self.last_at.elapsed().as_secs_f64();
        if bytes < self.last_bytes {
            self.start = Instant::now();
            self.initial = bytes;
            self.instant = None;
        } else if seconds >= 0.25 {
            self.instant = Some((bytes - self.last_bytes) as f64 / seconds);
            self.last_at = Instant::now();
            self.last_bytes = bytes;
        }
    }
    fn display(&self, bytes: u64, total: u64, language: &str) -> String {
        let seconds = self.start.elapsed().as_secs_f64();
        if seconds < 1.0 || bytes <= self.initial {
            return crate::i18n::text(language, "file.speed.wait");
        }
        let average = (bytes - self.initial) as f64 / seconds;
        crate::i18n::format(
            language,
            "file.speed",
            &[
                (
                    "speed",
                    &format!("{:.1}", self.instant.unwrap_or(average) / 1048576.0),
                ),
                ("average", &format!("{:.1}", average / 1048576.0)),
                (
                    "eta",
                    &(((total.saturating_sub(bytes)) as f64 / average).ceil() as u64).to_string(),
                ),
            ],
        )
    }
}
struct FilePrompt {
    id: String,
    name: String,
    size: u64,
    peer: String,
    reply: SyncSender<bool>,
    created: Instant,
}
struct Prompt {
    peer: Peer,
    code: String,
    reply: SyncSender<Consent>,
    created: Instant,
}
struct App {
    store: Arc<Mutex<Profile>>,
    peers: BTreeMap<String, Peer>,
    nearby: BTreeMap<String, Found>,
    connections: HashMap<String, SyncSender<Command>>,
    ready: std::collections::HashSet<String>,
    text_limits: HashMap<String, usize>,
    selected: usize,
    selection: Option<String>,
    focus: u8,
    editor: Editor,
    palette: bool,
    chat_draft: Option<Editor>,
    status: String,
    search: String,
    messages: Vec<Message>,
    history_before: Option<i64>,
    files: BTreeMap<String, Saved>,
    rates: HashMap<String, Rate>,
    prompts: VecDeque<Prompt>,
    scroll: u16,
    last_message_width: u16,
    no_color: bool,
    ascii: bool,
    theme: crate::Theme,
    cfg: Config,
    view: String,
    scan_request: bool,
    listen_request: bool,
    file_prompts: VecDeque<FilePrompt>,
    quit_confirm: bool,
    attach: Option<Vec<std::path::PathBuf>>,
}
impl App {
    fn t(&self, key: &str) -> String {
        crate::i18n::text(&self.cfg.language, key)
    }
    fn fmt(&self, key: &str, values: &[(&str, &str)]) -> String {
        crate::i18n::format(&self.cfg.language, key, values)
    }
    fn shortcuts(&self) -> String {
        if self.cfg.command_key == "p" {
            "Ctrl+P".into()
        } else {
            "Ctrl+K".into()
        }
    }
    fn state(&self, code: &str) -> String {
        let key = format!("state.{code}");
        if crate::i18n::has(&key) {
            self.t(&key)
        } else {
            safe(code)
        }
    }
    fn ids(&self) -> Vec<String> {
        let mut ids: Vec<_> = self
            .peers
            .keys()
            .chain(self.nearby.keys())
            .cloned()
            .collect();
        ids.sort();
        ids.dedup();
        ids
    }
    fn current(&self) -> Option<String> {
        self.selection.clone()
    }
    fn command(&self, c: Command) -> Result<()> {
        let id = self.current().context("select a peer first")?;
        self.connections
            .get(&id)
            .context("connect to this peer first")?
            .try_send(c)?;
        Ok(())
    }
    fn switch(&mut self, n: usize) -> Result<()> {
        if self.palette {
            self.palette = false;
            self.editor = self.chat_draft.take().unwrap_or_default();
        }
        if let Some(id) = self.current() {
            self.store
                .lock()
                .unwrap()
                .save_draft(&id, &self.editor.text)?;
        }
        self.selected = n;
        self.selection = self.ids().get(n).cloned();
        self.editor = Editor::default();
        if let Some(id) = self.current() {
            self.editor.text = self.store.lock().unwrap().draft(&id)?;
            self.editor.cursor = self.editor.text.len();
            let page = self
                .store
                .lock()
                .unwrap()
                .history_page(Some(&id), None, 500)?;
            self.messages = page.0;
            self.history_before = page.1;
        }
        self.scroll = 0;
        Ok(())
    }
    fn connect(&self, runtime: &Runtime, id: &str) -> Result<()> {
        ensure!(
            self.connections.is_empty(),
            "An active session exists. Use disconnect to pause files and close it before switching peers."
        );
        if let Some(f) = self.nearby.get(id) {
            return runtime.connect(&f.endpoint, Some(id.into()));
        }
        if let Some(p) = self.peers.get(id) {
            ensure!(
                !p.endpoint.is_empty(),
                "peer has no known listening address; use :connect host:port"
            );
            return runtime.connect(&p.endpoint, Some(id.into()));
        }
        runtime.connect(id, None)
    }
    fn execute(&mut self, runtime: &Runtime, line: &str) -> Result<bool> {
        let (verb, arg) = line.trim().split_once(' ').unwrap_or((line.trim(), ""));
        let arg = arg.trim();
        match verb {
            "quit" | "q" => {
                if self.has_active_files() {
                    self.quit_confirm = true;
                    return Ok(false);
                }
                return Ok(true);
            }
            "scan" | "nearby" => {
                self.scan_request = true;
                self.view = "nearby".into();
                self.focus = 0;
            }
            "chats" => {
                self.view = "chats".into();
                self.focus = if self.current().is_some() { 2 } else { 0 };
            }
            "transfers" | "security" | "settings" => {
                self.view = verb.into();
                self.focus = 1;
            }
            "status" => {
                self.view = "status".into();
                self.scroll = 0;
                self.focus = 1;
                return Ok(false);
            }
            "set" => {
                let (key, value) = arg.split_once(' ').context("usage: set KEY VALUE")?;
                let mut updated = self.cfg.clone();
                updated.set(key, value)?;
                updated.save(&self.store.lock().unwrap())?;
                self.cfg = updated;
                if key == "language" {
                    self.scroll = 0;
                }
                self.status = self.t("status.settings");
                return Ok(false);
            }
            "listen" => {
                ensure!(
                    self.connections.is_empty(),
                    "disconnect the active session first"
                );
                self.cfg.lan_listen = match arg {
                    "on" => true,
                    "off" => false,
                    _ => anyhow::bail!("usage: listen on|off"),
                };
                self.cfg.save(&self.store.lock().unwrap())?;
                self.listen_request = true;
            }
            "disconnect" => {
                for tx in self.connections.values() {
                    tx.try_send(Command::Close)?;
                }
            }
            "older" | "latest" => {
                let id = self.current().context("select a chat")?;
                let page = self.store.lock().unwrap().history_page(
                    Some(&id),
                    if verb == "older" {
                        self.history_before
                    } else {
                        None
                    },
                    500,
                )?;
                if !page.0.is_empty() {
                    self.messages = page.0;
                    self.history_before = page.1;
                }
                self.scroll = 0;
                self.view = "chats".into();
                self.status = self.t("status.history");
                return Ok(false);
            }
            "connect" => self.connect(runtime, arg)?,
            "file" => {
                ensure!(!arg.is_empty(), "usage: file PATH");
                let path = std::path::PathBuf::from(arg).canonicalize()?;
                ensure!(std::fs::metadata(&path)?.is_file(), "choose a regular file");
                self.attach = Some(vec![path]);
            }
            "files" => {
                let paths: Vec<_> = arg
                    .lines()
                    .filter(|s| !s.is_empty())
                    .map(std::path::PathBuf::from)
                    .collect();
                ensure!(
                    !paths.is_empty() && paths.len() <= 256,
                    "usage: files PATH (one path per line; Ctrl+J adds a line)"
                );
                for path in &paths {
                    let metadata = std::fs::metadata(path)?;
                    ensure!(
                        metadata.is_file() && metadata.len() <= crate::wire::MAX_FILE,
                        "choose regular files of at most 10 GiB"
                    );
                }
                self.attach = Some(paths);
            }
            "pause" => self.command(Command::Pause(arg.into()))?,
            "reveal" => {
                let saved = self
                    .files
                    .values()
                    .find(|s| s.info.id == arg)
                    .context("unknown transfer ID")?;
                crate::files::reveal(saved)?;
                self.status = self.t("status.reveal");
                return Ok(false);
            }
            "export" => {
                let (id, path) = arg
                    .split_once(' ')
                    .context("usage: export TRANSFER_ID PATH")?;
                let saved = self
                    .files
                    .values()
                    .find(|s| s.info.id == id)
                    .context("unknown transfer ID")?;
                crate::files::export(saved, std::path::Path::new(path))?;
                self.status = self.t("status.export");
                return Ok(false);
            }
            "cancel" => self.command(Command::Cancel(arg.into()))?,
            "resume" => self.command(Command::Resume(arg.into()))?,
            "qr" => {
                let id = self.current().context("select a peer first")?;
                let peer = self
                    .peers
                    .get(&id)
                    .context("connect to authenticate this identity first")?;
                let p = self.store.lock().unwrap();
                let payload = crate::verification::payload(
                    &p.identity.public,
                    &STANDARD.decode(&peer.root)?,
                )?;
                if !arg.is_empty() {
                    crate::verification::export(&payload, std::path::Path::new(arg))?;
                    self.status = self.t("status.qr");
                } else {
                    self.view = "qr".into();
                    self.scroll = 0;
                }
                return Ok(false);
            }
            "verify-qr" => {
                let id = self.current().context("select a peer first")?;
                let peer = self
                    .peers
                    .get_mut(&id)
                    .context("connect to authenticate this identity first")?;
                let values = crate::verification::read_image(std::path::Path::new(arg))?;
                let p = self.store.lock().unwrap();
                ensure!(
                    values.len() == 1
                        && crate::verification::matches(
                            &values[0],
                            &p.identity.public,
                            &STANDARD.decode(&peer.root)?
                        )?,
                    "QR safety code does not match; trust unchanged"
                );
                peer.verified = true;
                p.save_peer(peer)?;
                self.status = self.t("status.verified_qr");
                return Ok(false);
            }
            "avatar" => {
                let (operation, path) = arg.split_once(' ').unwrap_or((arg, ""));
                let p = self.store.lock().unwrap();
                match operation {
                    "set" => {
                        crate::avatar::save_local(
                            &p,
                            crate::avatar::prepare_image(std::path::Path::new(path))?,
                        )?;
                    }
                    "reset" => {
                        crate::avatar::save_local(&p, vec![])?;
                    }
                    "export" => {
                        let root = self
                            .current()
                            .and_then(|id| self.peers.get(&id))
                            .map(|p| p.root.clone())
                            .unwrap_or_else(|| crate::avatar::own_root(&p));
                        crate::avatar::export(&p, &root, std::path::Path::new(path))?;
                    }
                    "show" => {
                        let root = self
                            .current()
                            .and_then(|id| self.peers.get(&id))
                            .map(|p| p.root.clone())
                            .unwrap_or_else(|| crate::avatar::own_root(&p));
                        self.status =
                            serde_json::to_string(&crate::avatar::snapshot(&p, &root)?.profile)?;
                        return Ok(false);
                    }
                    _ => anyhow::bail!("usage: avatar set PATH | reset | show | export PATH"),
                }
                drop(p);
                if matches!(operation, "set" | "reset") {
                    for tx in self.connections.values() {
                        tx.try_send(Command::AvatarChanged)?;
                    }
                }
                self.status = self.t("status.avatar");
                return Ok(false);
            }
            "search" => {
                self.search = arg.into();
                self.view = "chats".into();
                self.focus = 1;
            }
            "peer" => {
                let ids = self.ids();
                self.switch(
                    ids.iter()
                        .position(|id| id == arg)
                        .context("unknown peer ID")?,
                )?;
            }
            "verify" | "unverify" => {
                let id = self.current().context("select a peer first")?;
                let p = self.peers.get_mut(&id).context("connect first")?;
                if verb == "verify" {
                    let own = self.store.lock().unwrap().identity.public.clone();
                    let code = crate::profile::safety(&own, &STANDARD.decode(&p.root)?);
                    ensure!(
                        arg.split_whitespace().collect::<String>() == code,
                        "full safety code does not match; compare it out of band first"
                    );
                    p.verified = true;
                } else {
                    p.verified = false;
                }
                self.store.lock().unwrap().save_peer(p)?;
            }
            "code" => {
                let id = self.current().context("select a peer")?;
                let p = self.peers.get(&id).context("connect first")?;
                let own = self.store.lock().unwrap().identity.public.clone();
                self.status = crate::profile::safety(&own, &STANDARD.decode(&p.root)?);
                return Ok(false);
            }
            "help" | "" => {
                self.view = "help".into();
                self.focus = 1;
                self.scroll = 0;
                self.status = self.t("help.navigation");
                return Ok(false);
            }
            _ => anyhow::bail!("unknown command; Ctrl+P then help"),
        }
        self.status = self.fmt("status.requested", &[("command", verb)]);
        Ok(false)
    }
    fn has_active_files(&self) -> bool {
        self.files.values().any(|s| {
            !matches!(
                s.state.as_str(),
                "saved" | "received" | "canceled" | "failed" | "paused"
            )
        })
    }
    fn network(&mut self, e: Event) -> Result<()> {
        match e {
            Event::FileOfferClosed { peer, id } => {
                self.file_prompts.retain(|p| p.peer != peer || p.id != id)
            }
            Event::FileOffer { peer, info, reply } => self.file_prompts.push_back(FilePrompt {
                id: info.id.clone(),
                peer,
                name: info.name,
                size: info.size,
                reply,
                created: Instant::now(),
            }),
            Event::Consent {
                peer,
                safety,
                reply,
            } => self.prompts.push_back(Prompt {
                peer,
                code: safety,
                reply,
                created: Instant::now(),
            }),
            Event::Connected(p, tx, outgoing) => {
                let id = p.id.clone();
                self.connections.insert(p.id.clone(), tx);
                self.peers.insert(p.id.clone(), p);
                if self.selection.is_none() || outgoing {
                    let index = self.ids().iter().position(|i| i == &id).unwrap();
                    self.switch(index)?;
                    self.focus = 2;
                }
            }
            Event::TextLimit(id, limit) => {
                self.text_limits.insert(id, limit);
            }
            Event::Ready(id) => {
                self.ready.insert(id);
                self.status = self.t("status.ready");
            }
            Event::Message(m) => {
                if self.scroll > 0 && self.current().as_ref() == Some(&m.peer) {
                    let width = self.last_message_width.max(1) as usize;
                    let rows = safe(&m.body)
                        .split('\n')
                        .map(|l| UnicodeWidthStr::width(l).max(1).div_ceil(width))
                        .sum::<usize>()
                        + 2;
                    self.scroll = self
                        .scroll
                        .saturating_add(rows.min(u16::MAX as usize) as u16);
                }
                self.messages.push(m);
                if self.messages.len() > 500 {
                    self.messages.remove(0);
                }
            }
            Event::Saved { peer, id } => {
                if let Some(m) = self
                    .messages
                    .iter_mut()
                    .find(|m| m.peer == peer && m.id == id)
                {
                    m.state = "saved".into();
                }
            }
            Event::File(s) => {
                self.rates
                    .entry(s.info.id.clone())
                    .or_insert_with(|| Rate {
                        start: Instant::now(),
                        initial: s.offset,
                        last_at: Instant::now(),
                        last_bytes: s.offset,
                        instant: None,
                    })
                    .update(s.offset);
                self.files.insert(s.info.id.clone(), s);
            }
            Event::Disconnected(id, why) => {
                self.text_limits.remove(&id);
                self.connections.remove(&id);
                self.ready.remove(&id);
                self.prompts.retain(|p| p.peer.id != id);
                self.file_prompts.retain(|p| p.peer != id);
                for m in self
                    .messages
                    .iter_mut()
                    .filter(|m| m.peer == id && m.state == "pending")
                {
                    m.state = "unconfirmed".into();
                }
                for s in self.files.values_mut().filter(|s| {
                    s.peer == id
                        && !["saved", "received", "canceled", "failed"].contains(&s.state.as_str())
                }) {
                    s.state = "paused".into();
                }
                self.status = crate::i18n::diagnostic(&self.cfg.language, &why);
            }
            Event::Avatar {
                peer,
                profile,
                failure,
            } => {
                self.status = if let Some(code) = failure {
                    self.fmt(
                        "human.avatar_failed",
                        &[("peer", &peer), ("code", &code.to_string())],
                    )
                } else {
                    self.fmt(
                        "human.avatar_saved",
                        &[
                            ("peer", &peer),
                            (
                                "revision",
                                &profile.map(|p| p.revision).unwrap_or(0).to_string(),
                            ),
                        ],
                    )
                };
            }
            Event::Error(e) => self.status = crate::i18n::diagnostic(&self.cfg.language, &e),
        }
        Ok(())
    }
    fn message_width(&self, width: u16) -> u16 {
        let sidebar = if width >= 72 {
            (width / 4).clamp(22, 28)
        } else {
            0
        };
        let details = if width >= 120
            && self
                .current()
                .is_some_and(|id| self.peers.contains_key(&id))
        {
            26
        } else {
            0
        };
        width.saturating_sub(sidebar + details + 2).max(1)
    }
    fn page(&mut self, up: bool) {
        // Chat scroll is distance from the bottom; document pages start at the top.
        let increase = if matches!(self.view.as_str(), "chats" | "nearby") {
            up
        } else {
            !up
        };
        self.scroll = if increase {
            self.scroll.saturating_add(10)
        } else {
            self.scroll.saturating_sub(10)
        };
    }
    fn draw_composer(
        &self,
        f: &mut ratatui::Frame,
        area: Rect,
        base: Style,
        accent: Style,
        muted: Style,
    ) {
        let input_width = area.width.saturating_sub(2).max(1) as usize;
        let (composer, cursor_row, cursor_col) = editor_lines(&self.editor, input_width);
        let input_height = area.height.saturating_sub(2).max(1) as usize;
        let input_scroll = cursor_row.saturating_sub(input_height - 1);
        let limit = self
            .current()
            .and_then(|id| self.text_limits.get(&id).copied())
            .unwrap_or(crate::wire::LEGACY_TEXT);
        let warning = if self.editor.rejected {
            self.t("composer.rejected")
        } else if self.editor.text.len() > limit {
            self.t("composer.too_long")
        } else {
            String::new()
        };
        let title = self.fmt(
            "composer.title",
            &[
                (
                    "label",
                    &self.t(if self.palette {
                        "label.command"
                    } else {
                        "label.input"
                    }),
                ),
                ("used", &self.editor.text.len().to_string()),
                ("limit", &limit.to_string()),
                ("warning", &warning),
            ],
        );
        f.render_widget(
            Paragraph::new(composer)
                .scroll((input_scroll.min(u16::MAX as usize) as u16, 0))
                .block(panel(self.ascii, title).border_style(if self.focus == 2 {
                    accent
                } else {
                    muted
                }))
                .style(base),
            area,
        );
        if self.focus == 2
            && self.prompts.is_empty()
            && self.file_prompts.is_empty()
            && self.attach.is_none()
            && !self.quit_confirm
            && !self.palette
        {
            f.set_cursor_position((
                area.x + 1 + cursor_col.min(input_width - 1) as u16,
                area.y + 1 + (cursor_row - input_scroll) as u16,
            ));
        }
    }
    fn draw(&self, f: &mut ratatui::Frame) {
        let area = f.area();
        if area.width < 45 || area.height < 16 {
            f.render_widget(
                Paragraph::new(self.t("window.small")).wrap(Wrap { trim: false }),
                area,
            );
            return;
        }
        let (foreground, background, accent_rgb, muted_rgb) = match self.theme {
            crate::Theme::Paper => ((38, 55, 46), (244, 242, 236), (63, 96, 83), (100, 120, 109)),
            crate::Theme::Sage => (
                (225, 241, 226),
                (24, 48, 37),
                (193, 221, 172),
                (171, 197, 168),
            ),
            _ => (
                (226, 235, 227),
                (17, 26, 23),
                (156, 209, 169),
                (146, 171, 152),
            ),
        };
        let base = if self.no_color || matches!(self.theme, crate::Theme::System) {
            Style::default()
        } else {
            Style::default()
                .fg(color_for(foreground, &self.cfg.color))
                .bg(color_for(background, &self.cfg.color))
        };
        let accent = if self.no_color {
            Style::default().bold()
        } else {
            base.fg(color_for(accent_rgb, &self.cfg.color)).bold()
        };
        let secondary = color_for(muted_rgb, &self.cfg.color);
        let muted = if self.no_color || matches!(self.theme, crate::Theme::System) {
            Style::default()
        } else {
            base.fg(match secondary {
                Color::Rgb(..) | Color::Indexed(_) => secondary,
                _ => Color::DarkGray,
            })
        };
        let selection_style = if self.no_color || matches!(self.theme, crate::Theme::System) {
            Style::default().add_modifier(Modifier::REVERSED)
        } else {
            let shade = color_for(
                if matches!(self.theme, crate::Theme::Paper) {
                    (229, 232, 224)
                } else {
                    (39, 68, 53)
                },
                &self.cfg.color,
            );
            base.fg(color_for(foreground, &self.cfg.color))
                .bg(match shade {
                    Color::Rgb(..) | Color::Indexed(_) => shade,
                    _ => Color::DarkGray,
                })
                .bold()
        };
        f.render_widget(Block::default().style(base), area);
        let status_lines = self
            .status
            .lines()
            .map(|line| {
                UnicodeWidthStr::width(line)
                    .max(1)
                    .div_ceil(area.width as usize)
            })
            .sum::<usize>();
        let rows = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(1),
            Constraint::Min(4),
            Constraint::Length(status_lines.clamp(1, 3) as u16),
            Constraint::Length(1),
        ])
        .split(area);
        let own = self.store.lock().unwrap().identity.name.clone();
        f.render_widget(
            Paragraph::new(if own == "NearbyIM Terminal" {
                "NearbyIM Terminal".into()
            } else {
                self.fmt("header.brand", &[("name", &safe(&own))])
            })
            .style(base.bold()),
            rows[0],
        );
        let nav = [
            ("chats", "1"),
            ("nearby", "2"),
            ("transfers", "3"),
            ("help", "?"),
        ]
        .into_iter()
        .flat_map(|(view, key)| {
            let label = self.t(&format!("view.{view}"));
            let text = if self.focus == 2 && !self.palette {
                format!(" {label} ")
            } else {
                format!(" {key} {label} ")
            };
            [
                Span::styled(
                    text,
                    if self.view == view {
                        accent.add_modifier(Modifier::UNDERLINED)
                    } else {
                        muted
                    },
                ),
                Span::raw("  "),
            ]
        })
        .collect::<Vec<_>>();
        f.render_widget(Paragraph::new(Line::from(nav)), rows[1]);
        let center = rows[2];
        let ids = self.ids();
        let selected = self.current();
        let peer = selected.as_ref().and_then(|id| self.peers.get(id));
        if matches!(self.view.as_str(), "chats" | "nearby") && ids.is_empty() {
            let welcome = Text::from(vec![
                Line::styled(self.t("empty.title"), base.bold()),
                Line::raw(""),
                Line::styled(self.t("empty.subtitle"), muted),
                Line::raw(""),
                Line::styled(self.t("empty.scan"), accent),
                Line::styled(self.t("empty.connect"), accent),
                Line::raw(""),
                Line::styled(self.t("empty.note"), muted),
            ]);
            let editing = self.focus == 2 || (!self.editor.text.is_empty() && !self.palette);
            let space = Layout::vertical([
                Constraint::Min(4),
                Constraint::Length(if editing { 5 } else { 0 }),
            ])
            .split(center);
            let welcome = if editing {
                Text::from(vec![
                    Line::styled(self.t("empty.scan"), accent),
                    Line::styled(self.t("empty.connect"), accent),
                ])
            } else {
                welcome
            };
            f.render_widget(
                Paragraph::new(welcome)
                    .alignment(Alignment::Center)
                    .wrap(Wrap { trim: false }),
                centered(space[0], 64, if editing { 3 } else { 11 }),
            );
            if editing {
                self.draw_composer(f, space[1], base, accent, muted);
            }
        } else if matches!(self.view.as_str(), "chats" | "nearby") {
            let wide = center.width >= 72;
            let triple = center.width >= 120 && peer.is_some();
            let mut constraints = vec![];
            if wide {
                constraints.push(Constraint::Length((center.width / 4).clamp(22, 28)));
                constraints.push(Constraint::Min(30));
                if triple {
                    constraints.push(Constraint::Length(26));
                }
            } else {
                constraints.push(Constraint::Percentage(100));
            }
            let cols = Layout::horizontal(constraints).split(center);
            if wide || self.focus == 0 {
                let items = ids
                    .iter()
                    .map(|id| {
                        let name = self
                            .peers
                            .get(id)
                            .map(|p| p.name.as_str())
                            .or_else(|| self.nearby.get(id).map(|p| p.name.as_str()))
                            .unwrap_or(id);
                        let state = if self.ready.contains(id) {
                            "peer.connected"
                        } else if self.nearby.contains_key(id) {
                            "peer.seen"
                        } else {
                            "peer.offline"
                        };
                        ListItem::new(vec![
                            Line::styled(
                                fit(&safe(name), cols[0].width.saturating_sub(4) as usize),
                                base.bold(),
                            ),
                            Line::styled(
                                format!("{} · {}", id.get(..8).unwrap_or(id), self.t(state)),
                                muted,
                            ),
                        ])
                    })
                    .collect::<Vec<_>>();
                let mut state = ListState::default().with_selected(
                    selected
                        .as_ref()
                        .and_then(|id| ids.iter().position(|p| p == id)),
                );
                let block = panel(
                    self.ascii,
                    self.t(if self.focus == 0 {
                        "label.peers_focused"
                    } else {
                        "label.peers"
                    }),
                )
                .borders(Borders::RIGHT)
                .padding(Padding::new(1, 1, 1, 0))
                .border_style(muted);
                f.render_stateful_widget(
                    List::new(items)
                        .block(block)
                        .highlight_style(selection_style)
                        .highlight_symbol(if self.ascii { "> " } else { "› " }),
                    cols[0],
                    &mut state,
                );
            }
            if wide || self.focus != 0 {
                let chat = if wide { cols[1] } else { cols[0] };
                let chatrows = Layout::vertical([
                    Constraint::Length(3),
                    Constraint::Min(1),
                    Constraint::Length(5),
                ])
                .split(chat);
                let ready = selected.as_ref().is_some_and(|id| self.ready.contains(id));
                let status = if ready {
                    self.fmt(
                        "chat.secure",
                        &[(
                            "identity",
                            &self.t(if peer.is_some_and(|p| p.verified) {
                                "identity.verified"
                            } else {
                                "identity.unverified"
                            }),
                        )],
                    )
                } else {
                    self.t("chat.offline")
                };
                let name = peer
                    .map(|p| safe(&p.name))
                    .or_else(|| {
                        selected
                            .as_ref()
                            .and_then(|id| self.nearby.get(id))
                            .map(|p| safe(&p.name))
                    })
                    .unwrap_or_else(|| self.t("label.chat"));
                f.render_widget(
                    Paragraph::new(vec![
                        Line::styled(
                            fit(&name, chat.width.saturating_sub(2) as usize),
                            base.bold(),
                        ),
                        Line::styled(status, muted),
                    ])
                    .block(Block::default().padding(Padding::horizontal(1))),
                    chatrows[0],
                );
                let mut lines = vec![];
                for m in self.messages.iter().filter(|m| {
                    Some(&m.peer) == selected.as_ref()
                        && (self.search.is_empty() || m.body.contains(&self.search))
                }) {
                    let who = self.t(if m.outgoing { "chat.you" } else { "chat.peer" });
                    let timestamp = crate::iso(m.time);
                    lines.push(Line::styled(
                        format!(
                            "{}  {} UTC · {}",
                            who,
                            timestamp.get(11..16).unwrap_or(&timestamp),
                            self.state(&m.state)
                        ),
                        muted,
                    ));
                    for line in safe(&m.body).split('\n') {
                        lines.push(Line::styled(line.to_string(), base));
                    }
                    lines.push(Line::raw(""));
                }
                for s in self
                    .files
                    .values()
                    .filter(|s| Some(&s.peer) == selected.as_ref())
                {
                    lines.push(Line::raw(self.fmt(
                        "file.summary",
                        &[
                            ("name", &safe(&s.info.name)),
                            ("done", &s.offset.to_string()),
                            ("total", &s.info.size.to_string()),
                            ("state", &self.state(&s.state)),
                            ("id", &s.info.id),
                        ],
                    )));
                    if let Some(rate) = self.rates.get(&s.info.id)
                        && !["received", "saved", "paused", "canceled", "failed"]
                            .contains(&s.state.as_str())
                    {
                        lines.push(Line::styled(
                            rate.display(s.offset, s.info.size, &self.cfg.language),
                            muted,
                        ));
                    }
                    lines.push(Line::raw(""));
                }
                if lines.is_empty() {
                    lines.push(Line::styled(self.t("chat.empty"), muted));
                }
                let text = Text::from(lines);
                let width = chatrows[1].width.saturating_sub(2).max(1) as usize;
                let count = text
                    .lines
                    .iter()
                    .map(|l| l.width().max(1).div_ceil(width))
                    .sum::<usize>();
                let offset = count
                    .saturating_sub(chatrows[1].height as usize)
                    .saturating_sub(self.scroll as usize)
                    .min(u16::MAX as usize) as u16;
                f.render_widget(
                    Paragraph::new(text)
                        .wrap(Wrap { trim: false })
                        .scroll((offset, 0))
                        .block(Block::default().padding(Padding::horizontal(1))),
                    chatrows[1],
                );
                self.draw_composer(f, chatrows[2], base, accent, muted);
                if triple {
                    let info = peer
                        .map(|p| {
                            self.fmt(
                                "details.body",
                                &[
                                    ("id", &p.id),
                                    (
                                        "fingerprint",
                                        &crate::profile::fingerprint(
                                            &STANDARD.decode(&p.root).unwrap_or_default(),
                                        ),
                                    ),
                                    (
                                        "trust",
                                        &self.t(if p.verified {
                                            "identity.verified"
                                        } else {
                                            "identity.unverified"
                                        }),
                                    ),
                                ],
                            )
                        })
                        .unwrap_or_default();
                    f.render_widget(
                        Paragraph::new(info)
                            .wrap(Wrap { trim: false })
                            .style(muted)
                            .block(
                                panel(self.ascii, self.t("label.details"))
                                    .borders(Borders::LEFT)
                                    .padding(Padding::new(1, 0, 1, 0))
                                    .border_style(muted),
                            ),
                        cols[2],
                    );
                }
            }
        } else {
            let body = match self.view.as_str() {
                "transfers" => self
                    .files
                    .values()
                    .map(|s| {
                        self.fmt(
                            "transfers.item",
                            &[
                                ("name", &safe(&s.info.name)),
                                ("state", &self.state(&s.state)),
                                ("done", &s.offset.to_string()),
                                ("total", &s.info.size.to_string()),
                                ("id", &s.info.id),
                                ("path", &safe(&s.destination.display().to_string())),
                            ],
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
                "security" => self
                    .peers
                    .values()
                    .map(|p| {
                        self.fmt(
                            "security.item",
                            &[
                                ("name", &safe(&p.name)),
                                ("id", &p.id),
                                (
                                    "remembered",
                                    &self.t(if p.remembered {
                                        "bool.true"
                                    } else {
                                        "bool.false"
                                    }),
                                ),
                                (
                                    "verified",
                                    &self.t(if p.verified {
                                        "bool.true"
                                    } else {
                                        "bool.false"
                                    }),
                                ),
                                (
                                    "fingerprint",
                                    &crate::profile::fingerprint(
                                        &STANDARD.decode(&p.root).unwrap_or_default(),
                                    ),
                                ),
                            ],
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
                "settings" => serde_json::to_string_pretty(&self.cfg).unwrap_or_default(),
                "status" => self.status.clone(),
                "qr" => self
                    .current()
                    .and_then(|id| self.peers.get(&id))
                    .and_then(|p| {
                        let own = self.store.lock().unwrap();
                        let payload = crate::verification::payload(
                            &own.identity.public,
                            &STANDARD.decode(&p.root).ok()?,
                        )
                        .ok()?;
                        let text = crate::verification::terminal(&payload, self.ascii).ok()?;
                        if text.lines().next()?.width() > center.width.saturating_sub(2) as usize
                            || text.lines().count() > center.height.saturating_sub(4) as usize
                        {
                            Some(self.fmt("qr.small", &[("payload", &payload)]))
                        } else {
                            Some(self.fmt("qr.compare", &[("qr", &text)]))
                        }
                    })
                    .unwrap_or_else(|| self.t("qr.none")),
                _ => self.fmt("help.body", &[("commands", &self.shortcuts())]),
            };
            f.render_widget(
                Paragraph::new(safe(&body))
                    .style(if self.view == "qr" {
                        Style::default().fg(Color::Black).bg(Color::White)
                    } else {
                        base
                    })
                    .wrap(Wrap { trim: false })
                    .scroll((self.scroll, 0))
                    .block(
                        panel(self.ascii, self.t(&format!("view.{}", self.view)))
                            .borders(Borders::TOP)
                            .border_style(muted)
                            .padding(Padding::horizontal(1)),
                    ),
                center,
            );
        }
        f.render_widget(
            Paragraph::new(safe(&self.status))
                .style(muted)
                .wrap(Wrap { trim: false }),
            rows[3],
        );
        let hint = if status_lines > 3 {
            self.fmt("footer.status", &[("commands", &self.shortcuts())])
        } else if self.focus == 2 && !self.palette && !ids.is_empty() {
            self.fmt("footer.compose", &[("commands", &self.shortcuts())])
        } else {
            self.fmt(
                if area.width < 100 {
                    "footer.short"
                } else {
                    "footer.keys"
                },
                &[("commands", &self.shortcuts())],
            )
        };
        f.render_widget(Paragraph::new(hint).style(muted), rows[4]);
        if self.palette {
            let p = centered(area, 80, 8);
            f.render_widget(Clear, p);
            let (text, row, col) =
                editor_lines(&self.editor, p.width.saturating_sub(2).max(1) as usize);
            let scroll = row.saturating_sub(3);
            f.render_widget(
                Paragraph::new(text)
                    .scroll((scroll as u16, 0))
                    .style(base)
                    .block(panel(self.ascii, self.t("palette.title")).border_style(accent)),
                p,
            );
            if self.prompts.is_empty() && self.file_prompts.is_empty() {
                f.set_cursor_position((
                    p.x + 1 + col.min(p.width.saturating_sub(3) as usize) as u16,
                    p.y + 1 + (row - scroll) as u16,
                ));
            }
        }
        if self.quit_confirm {
            draw_dialog(
                f,
                centered(area, 70, 9),
                self.t("label.exit"),
                self.t("exit.body"),
                self.t("exit.actions"),
                self.ascii,
                base,
                accent,
            );
        }
        if let Some(paths) = &self.attach {
            let size = paths
                .iter()
                .map(|path| std::fs::metadata(path).map_or(0, |m| m.len()))
                .sum::<u64>();
            let names = paths
                .iter()
                .take(4)
                .map(|p| p.display().to_string())
                .collect::<Vec<_>>()
                .join("\n");
            let body = self.fmt(
                "attach.body",
                &[
                    ("count", &paths.len().to_string()),
                    ("names", &safe(&names)),
                    ("size", &size.to_string()),
                    ("peer", &self.current().unwrap_or_default()),
                ],
            );
            draw_dialog(
                f,
                centered(area, 75, 13),
                self.t("label.attach"),
                body,
                self.t("attach.actions"),
                self.ascii,
                base,
                accent,
            );
        }
        if let Some(pending) = self.file_prompts.front() {
            let body = self.fmt(
                "receive.body",
                &[
                    ("name", &safe(&pending.name)),
                    ("size", &pending.size.to_string()),
                    ("peer", &pending.peer),
                    ("path", &safe(&self.cfg.receive_dir.display().to_string())),
                ],
            );
            draw_dialog(
                f,
                centered(area, 75, 13),
                self.t("label.receive"),
                body,
                self.t("receive.actions"),
                self.ascii,
                base,
                accent,
            );
        }
        if let Some(prompt) = self.prompts.front() {
            let popup = centered(area, 80, 16);
            let code = prompt
                .code
                .as_bytes()
                .chunks(8)
                .map(|b| std::str::from_utf8(b).unwrap())
                .collect::<Vec<_>>()
                .join(" ");
            let body = self.fmt(
                "consent.body",
                &[
                    (
                        "name",
                        &fit(
                            &safe(&prompt.peer.name),
                            popup.width.saturating_sub(2) as usize,
                        ),
                    ),
                    ("id", &prompt.peer.id),
                    ("code", &code),
                    (
                        "trust",
                        &self.t(if prompt.peer.verified {
                            "identity.verified"
                        } else {
                            "identity.unverified"
                        }),
                    ),
                ],
            );
            draw_dialog(
                f,
                popup,
                self.t("label.consent"),
                body,
                self.t("consent.actions"),
                self.ascii,
                base,
                accent,
            );
        }
    }
}
// Truncate display-only names on complete grapheme boundaries, never message text.
fn fit(value: &str, width: usize) -> String {
    if value.width() <= width {
        return value.into();
    }
    let mut result = String::new();
    let mut used = 0;
    for grapheme in value.graphemes(true) {
        let w = grapheme.width();
        if used + w > width.saturating_sub(1) {
            break;
        }
        result.push_str(grapheme);
        used += w;
    }
    if width > 0 {
        result.push('…');
    }
    result
}
#[allow(clippy::too_many_arguments)]
fn draw_dialog(
    f: &mut ratatui::Frame,
    area: Rect,
    title: String,
    body: String,
    actions: String,
    ascii: bool,
    style: Style,
    accent: Style,
) {
    f.render_widget(Clear, area);
    let block = panel(ascii, title).style(style).border_style(accent);
    let inner = block.inner(area);
    f.render_widget(block, area);
    let rows = Layout::vertical([Constraint::Min(1), Constraint::Length(2)]).split(inner);
    f.render_widget(
        Paragraph::new(body).wrap(Wrap { trim: false }).style(style),
        rows[0],
    );
    f.render_widget(
        Paragraph::new(actions)
            .wrap(Wrap { trim: false })
            .style(accent),
        rows[1],
    );
}
fn color_for(rgb: (u8, u8, u8), mode: &str) -> Color {
    let truecolor = mode == "truecolor"
        || (mode == "auto"
            && std::env::var("COLORTERM").is_ok_and(|v| v == "truecolor" || v == "24bit"));
    if truecolor {
        return Color::Rgb(rgb.0, rgb.1, rgb.2);
    }
    if mode == "256" || (mode == "auto" && std::env::var("TERM").is_ok_and(|v| v.contains("256"))) {
        let q = |v: u8| ((v as u16 * 5 + 127) / 255) as u8;
        return Color::Indexed(16 + 36 * q(rgb.0) + 6 * q(rgb.1) + q(rgb.2));
    }
    if (rgb.0 as u16 + rgb.1 as u16 + rgb.2 as u16) < 220 {
        Color::Black
    } else if rgb.1 > rgb.0.saturating_add(15) {
        Color::Green
    } else {
        Color::White
    }
}
fn panel(ascii: bool, title: impl Into<Line<'static>>) -> Block<'static> {
    let block = Block::bordered().title(title);
    if ascii {
        block.border_set(ratatui::symbols::border::Set {
            top_left: "+",
            top_right: "+",
            bottom_left: "+",
            bottom_right: "+",
            vertical_left: "|",
            vertical_right: "|",
            horizontal_top: "-",
            horizontal_bottom: "-",
        })
    } else {
        block.border_type(ratatui::widgets::BorderType::Rounded)
    }
}
fn editor_lines(editor: &Editor, width: usize) -> (Text<'static>, usize, usize) {
    let mut lines = vec![String::new()];
    let mut col = 0;
    let (mut row, mut cursor_col) = (0, 0);
    for (index, g) in editor.text.grapheme_indices(true) {
        let display = if g == "\t" {
            "    ".to_string()
        } else {
            safe(g)
        };
        let w = UnicodeWidthStr::width(display.as_str()).min(width);
        if g != "\n" && col + w > width {
            lines.push(String::new());
            col = 0;
        }
        if index == editor.cursor {
            row = lines.len() - 1;
            cursor_col = col;
        }
        if g == "\n" {
            lines.push(String::new());
            col = 0;
        } else {
            lines.last_mut().unwrap().push_str(&display);
            col += w;
        }
    }
    if editor.cursor == editor.text.len() {
        if col >= width {
            lines.push(String::new());
            col = 0;
        }
        row = lines.len() - 1;
        cursor_col = col;
    }
    (
        Text::from(lines.into_iter().map(Line::raw).collect::<Vec<_>>()),
        row,
        cursor_col,
    )
}
fn centered(area: Rect, width: u16, height: u16) -> Rect {
    let w = width.min(area.width);
    let h = height.min(area.height);
    Rect::new(
        area.x + (area.width - w) / 2,
        area.y + (area.height - h) / 2,
        w,
        h,
    )
}
pub struct Options {
    pub no_color: bool,
    pub ascii: bool,
    pub theme: crate::Theme,
    pub cfg: Config,
    pub target: Option<String>,
}
pub fn run(
    store: Arc<Mutex<Profile>>,
    mut runtime: Runtime,
    mut discovery: Option<Discovery>,
    options: Options,
) -> Result<()> {
    let Options {
        no_color,
        ascii,
        theme,
        cfg,
        target,
    } = options;
    let peers = store
        .lock()
        .unwrap()
        .peers()?
        .into_iter()
        .map(|p| (p.id.clone(), p))
        .collect();
    let messages = store.lock().unwrap().history(None)?;
    let files = store
        .lock()
        .unwrap()
        .transfers::<Saved>()?
        .into_iter()
        .map(|s| (s.info.id.clone(), s))
        .collect();
    let mut app = App {
        store,
        peers,
        nearby: BTreeMap::new(),
        connections: HashMap::new(),
        ready: Default::default(),
        text_limits: Default::default(),
        selected: 0,
        selection: None,
        focus: 0,
        editor: Editor::default(),
        palette: false,
        chat_draft: None,
        status: crate::i18n::format(
            &cfg.language,
            if runtime.port == 0 {
                "status.listener_off"
            } else {
                "status.listener_on"
            },
            &[
                (
                    "commands",
                    if cfg.command_key == "p" {
                        "Ctrl+P"
                    } else {
                        "Ctrl+K"
                    },
                ),
                ("port", &runtime.port.to_string()),
            ],
        ),
        search: String::new(),
        messages,
        history_before: None,
        files,
        rates: HashMap::new(),
        prompts: VecDeque::new(),
        scroll: 0,
        last_message_width: 60,
        no_color,
        ascii,
        theme,
        cfg,
        view: "chats".into(),
        scan_request: false,
        listen_request: false,
        file_prompts: VecDeque::new(),
        quit_confirm: false,
        attach: None,
    };
    app.selection = app.ids().first().cloned();
    if let Some(id) = app.current() {
        app.editor.text = app.store.lock().unwrap().draft(&id)?;
        app.editor.cursor = app.editor.text.len();
        let page = app
            .store
            .lock()
            .unwrap()
            .history_page(Some(&id), None, 500)?;
        app.messages = page.0;
        app.history_before = page.1;
    }
    enable_raw_mode()?;
    let _guard = Guard;
    execute!(stdout(), EnterAlternateScreen, EnableBracketedPaste)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
    terminal.clear()?;
    let original = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let _ = disable_raw_mode();
        let _ = execute!(
            stdout(),
            DisableBracketedPaste,
            crossterm::cursor::Show,
            crossterm::style::ResetColor,
            LeaveAlternateScreen
        );
        original(info);
    }));
    let mut advertising = if runtime.port > 0 {
        Some(Discovery::advertise(
            &app.store.lock().unwrap().identity,
            runtime.port,
        )?)
    } else {
        None
    };
    let mut scan_deadline = None::<Instant>;
    let mut target = target;
    if let Some(id) = target.as_ref() {
        if id.contains(':') {
            app.connect(&runtime, id)?;
            target = None;
        } else {
            app.scan_request = true;
        }
    }
    let mut dirty = true;
    let outcome = (|| -> Result<()> {
        loop {
            if app.listen_request {
                app.listen_request = false;
                advertising.take();
                runtime = if app.cfg.lan_listen {
                    Runtime::start(
                        app.store.clone(),
                        &app.cfg.listen,
                        app.cfg.receive_dir.clone(),
                    )?
                } else {
                    Runtime::client(app.store.clone(), app.cfg.receive_dir.clone())
                };
                if runtime.port > 0 {
                    advertising = Some(Discovery::advertise(
                        &app.store.lock().unwrap().identity,
                        runtime.port,
                    )?);
                }
                dirty = true;
            }
            if app.scan_request {
                app.scan_request = false;
                app.nearby.clear();
                discovery = Some(Discovery::start(&app.store.lock().unwrap().identity, 0)?);
                scan_deadline = Some(Instant::now() + Duration::from_secs(8));
                app.status = app.t("status.scan");
                dirty = true;
            }
            if scan_deadline.is_some_and(|d| Instant::now() >= d) {
                discovery.take();
                scan_deadline = None;
                app.status = app.t("status.scan_done");
                dirty = true;
            }
            app.file_prompts
                .retain(|p| p.created.elapsed() < Duration::from_secs(49));
            let previous = app.prompts.len();
            app.prompts
                .retain(|p| p.created.elapsed() < Duration::from_secs(49));
            if previous != app.prompts.len() {
                dirty = true;
            }
            for _ in 0..128 {
                match runtime.events.try_recv() {
                    Ok(e) => {
                        app.network(e)?;
                        dirty = true;
                    }
                    Err(_) => break,
                }
            }
            if let Some(d) = &discovery {
                for _ in 0..64 {
                    match d.events.try_recv() {
                        Ok(f) => {
                            let id = f.id.clone();
                            if app.nearby.len() < 256 || app.nearby.contains_key(&id) {
                                app.nearby.insert(id.clone(), f);
                            }
                            if app.selection.is_none() {
                                app.switch(0)?;
                            }
                            if target.as_ref() == Some(&id) {
                                if let Err(e) = app.connect(&runtime, &id) {
                                    app.status =
                                        crate::i18n::diagnostic(&app.cfg.language, &e.to_string());
                                }
                                target = None;
                            }
                            dirty = true;
                        }
                        Err(_) => break,
                    }
                }
            }
            if dirty {
                let width = terminal.size()?.width;
                app.last_message_width = app.message_width(width);
                terminal.draw(|f| app.draw(f))?;
                dirty = false;
            }
            if !event::poll(Duration::from_millis(50))? {
                continue;
            }
            dirty = true;
            match event::read()? {
                InputEvent::Key(k) if k.kind != KeyEventKind::Release => {
                    if k.modifiers.contains(KeyModifiers::CONTROL) && k.code == KeyCode::Char('c') {
                        if app.has_active_files() {
                            app.quit_confirm = true;
                            continue;
                        }
                        break;
                    }
                    if app.quit_confirm {
                        match k.code {
                            KeyCode::Char('y') => break,
                            KeyCode::Char('n') | KeyCode::Esc => app.quit_confirm = false,
                            _ => {}
                        }
                        continue;
                    }
                    if app.attach.is_some() {
                        match k.code {
                            KeyCode::Char('y') => {
                                let paths = app.attach.take().unwrap();
                                if let Err(e) = app.command(Command::Files(paths)) {
                                    app.status =
                                        crate::i18n::diagnostic(&app.cfg.language, &e.to_string());
                                }
                            }
                            KeyCode::Char('n') | KeyCode::Esc => {
                                app.attach = None;
                            }
                            _ => {}
                        }
                        continue;
                    }
                    if let Some(prompt) = app.file_prompts.front() {
                        if matches!(
                            k.code,
                            KeyCode::Char('y') | KeyCode::Char('n') | KeyCode::Esc
                        ) {
                            let _ = prompt.reply.try_send(k.code == KeyCode::Char('y'));
                            app.file_prompts.pop_front();
                        }
                        continue;
                    }
                    if let Some(prompt) = app.prompts.front() {
                        let size = terminal.size()?;
                        if size.width < 45 || size.height < 16 {
                            continue;
                        }
                        let c = match k.code {
                            KeyCode::Char('1') => Some(Consent::Once),
                            KeyCode::Char('2') => Some(Consent::Remember),
                            KeyCode::Char('3') | KeyCode::Esc => Some(Consent::Reject),
                            _ => None,
                        };
                        if let Some(c) = c {
                            let _ = prompt.reply.try_send(c);
                            app.prompts.pop_front();
                        }
                        continue;
                    }
                    let result = (|| -> Result<bool> {
                        if k.modifiers.contains(KeyModifiers::CONTROL) {
                            match k.code {
                                KeyCode::Char(c)
                                    if c == app.cfg.command_key.chars().next().unwrap_or('k')
                                        || c == 'p' =>
                                {
                                    if !app.palette {
                                        app.chat_draft = Some(std::mem::take(&mut app.editor));
                                    }
                                    app.palette = true;
                                    app.editor = Editor::default();
                                    app.focus = 2;
                                }
                                KeyCode::Char('f') => {
                                    if !app.palette {
                                        app.chat_draft = Some(std::mem::take(&mut app.editor));
                                    }
                                    app.palette = true;
                                    app.editor = Editor {
                                        rejected: false,
                                        text: "search ".into(),
                                        cursor: 7,
                                    };
                                    app.focus = 2;
                                }
                                KeyCode::Char('j') => {
                                    app.focus = 2;
                                    app.editor.insert("\n");
                                }
                                KeyCode::Char('l') => {
                                    app.focus = 2;
                                    app.view = "chats".into();
                                }
                                KeyCode::Char('u') => app.editor = Editor::default(),
                                _ => {}
                            }
                            return Ok(false);
                        }
                        match k.code {
                            KeyCode::Tab => {
                                app.focus = if app.ids().is_empty() {
                                    if app.focus == 0 { 2 } else { 0 }
                                } else {
                                    (app.focus + 1) % 3
                                }
                            }
                            KeyCode::BackTab => {
                                app.focus = if app.ids().is_empty() {
                                    if app.focus == 0 { 2 } else { 0 }
                                } else {
                                    (app.focus + 2) % 3
                                }
                            }
                            KeyCode::Esc => {
                                if scan_deadline.is_some() {
                                    discovery.take();
                                    scan_deadline = None;
                                    app.status = app.t("status.scan_canceled");
                                }
                                if app.palette {
                                    app.palette = false;
                                    app.editor = app.chat_draft.take().unwrap_or_default();
                                } else {
                                    app.focus = 0;
                                    app.view = "chats".into();
                                }
                            }
                            KeyCode::Char('1' | '2' | '3') if app.focus != 2 && !app.palette => {
                                let action = match k.code {
                                    KeyCode::Char('1') => "chats",
                                    KeyCode::Char('2') => "scan",
                                    _ => "transfers",
                                };
                                app.execute(&runtime, action)?;
                            }
                            KeyCode::Char('s' | 'c')
                                if app.ids().is_empty()
                                    && app.focus != 2
                                    && !app.palette
                                    && matches!(app.view.as_str(), "chats" | "nearby") =>
                            {
                                if k.code == KeyCode::Char('s') {
                                    app.execute(&runtime, "scan")?;
                                } else {
                                    app.chat_draft = Some(std::mem::take(&mut app.editor));
                                    app.palette = true;
                                    app.editor = Editor {
                                        rejected: false,
                                        text: "connect ".into(),
                                        cursor: 8,
                                    };
                                    app.focus = 2;
                                }
                            }
                            KeyCode::Char('?') if app.focus != 2 => {
                                app.view = "help".into();
                            }
                            KeyCode::Char('/') if app.focus != 2 => {
                                app.chat_draft = Some(std::mem::take(&mut app.editor));
                                app.palette = true;
                                app.editor = Editor {
                                    rejected: false,
                                    text: "search ".into(),
                                    cursor: 7,
                                };
                                app.focus = 2;
                            }
                            KeyCode::Char('a') if app.focus != 2 => {
                                app.chat_draft = Some(std::mem::take(&mut app.editor));
                                app.palette = true;
                                app.editor = Editor {
                                    rejected: false,
                                    text: "file ".into(),
                                    cursor: 5,
                                };
                                app.focus = 2;
                            }
                            KeyCode::Char('q') if app.focus != 2 => {
                                if app.has_active_files() {
                                    app.quit_confirm = true;
                                } else {
                                    return Ok(true);
                                }
                            }
                            KeyCode::Up | KeyCode::Char('k') if app.focus == 0 => {
                                app.switch(app.selected.saturating_sub(1))?
                            }
                            KeyCode::Down | KeyCode::Char('j') if app.focus == 0 => app.switch(
                                (app.selected + 1).min(app.ids().len().saturating_sub(1)),
                            )?,
                            KeyCode::Enter if app.focus == 0 => {
                                if let Some(id) = app.current() {
                                    if !app.ready.contains(&id) {
                                        app.connect(&runtime, &id)?;
                                    }
                                    app.focus = 2;
                                    app.view = "chats".into();
                                }
                            }
                            KeyCode::PageUp => app.page(true),
                            KeyCode::PageDown => app.page(false),
                            KeyCode::Enter if k.modifiers.contains(KeyModifiers::ALT) => {
                                app.editor.insert("\n")
                            }
                            KeyCode::Enter => {
                                let text = app.editor.text.clone();
                                if app.palette {
                                    app.palette = false;
                                    app.editor = app.chat_draft.take().unwrap_or_default();
                                    if app.execute(&runtime, &text)? {
                                        return Ok(true);
                                    }
                                } else {
                                    crate::wire::text_valid_limit(
                                        &text,
                                        app.current()
                                            .and_then(|id| app.text_limits.get(&id).copied())
                                            .unwrap_or(crate::wire::LEGACY_TEXT),
                                    )?;
                                    app.command(Command::Text(text))?;
                                    app.editor = Editor::default();
                                    if let Some(id) = app.current() {
                                        app.store.lock().unwrap().save_draft(&id, "")?;
                                    }
                                }
                            }
                            KeyCode::Char(c) if app.focus == 2 => app.editor.insert(&c.to_string()),
                            KeyCode::Left => app.editor.left(),
                            KeyCode::Right => app.editor.right(),
                            KeyCode::Backspace => app.editor.backspace(),
                            KeyCode::Delete => app.editor.delete(),
                            KeyCode::Home => app.editor.cursor = 0,
                            KeyCode::End => app.editor.cursor = app.editor.text.len(),
                            _ => {}
                        }
                        Ok(false)
                    })();
                    match result {
                        Ok(true) => break,
                        Ok(false) => {}
                        Err(e) => {
                            app.status =
                                crate::i18n::diagnostic(&app.cfg.language, &format!("{e:#}"))
                        }
                    }
                }
                InputEvent::Paste(s) => {
                    app.focus = 2;
                    app.editor.insert(&s);
                }
                _ => {}
            }
        }
        Ok(())
    })();
    if let Some(id) = app.current() {
        let draft = app.chat_draft.as_ref().unwrap_or(&app.editor);
        app.store.lock().unwrap().save_draft(&id, &draft.text)?;
    }
    for tx in app.connections.values() {
        let _ = tx.try_send(Command::Close);
    }
    outcome
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn edits_graphemes_not_code_units() {
        let mut e = Editor::default();
        e.insert("中👩‍💻e\u{301}");
        e.backspace();
        assert_eq!(e.text, "中👩‍💻");
        e.backspace();
        assert_eq!(e.text, "中");
        e.left();
        e.delete();
        assert!(e.text.is_empty());
    }
    #[test]
    fn paste_does_not_truncate_utf8_or_execute_commands() {
        let mut e = Editor::default();
        e.insert(&"中".repeat(2730));
        e.insert("中");
        assert_eq!(e.text.len(), 8193);
        let before = e.text.clone();
        e.insert(&"x".repeat(crate::wire::DRAFT_BYTES));
        assert!(e.rejected);
        assert_eq!(e.text, before);
        e = Editor::default();
        e.insert("\u{1b}[31m\nabc");
        assert!(!e.text.contains('\u{1b}'));
        assert!(e.text.contains('\n'));
    }
}

#[cfg(test)]
mod render_tests {
    use super::*;
    fn fixture() -> (tempfile::TempDir, App) {
        let d = tempfile::tempdir().unwrap();
        let store = Arc::new(Mutex::new(Profile::open(d.path().join("p"), None).unwrap()));
        let peer = Peer {
            capabilities: 3,
            id: uuid::Uuid::new_v4().to_string(),
            name: "中文👩‍💻 peer".into(),
            root: STANDARD.encode(store.lock().unwrap().identity.public.clone()),
            remembered: false,
            verified: false,
            endpoint: "127.0.0.1:1234".into(),
        };
        let mut peers = BTreeMap::new();
        peers.insert(peer.id.clone(), peer.clone());
        let app = App {
            store,
            peers,
            nearby: BTreeMap::new(),
            connections: HashMap::new(),
            ready: Default::default(),
            text_limits: Default::default(),
            selected: 0,
            selection: Some(peer.id.clone()),
            focus: 2,
            editor: Editor {
                rejected: false,
                text: "输入\n👩‍💻".into(),
                cursor: 0,
            },
            palette: false,
            chat_draft: None,
            status: "你好".into(),
            search: String::new(),
            history_before: None,
            messages: vec![Message {
                id: uuid::Uuid::new_v4().to_string(),
                peer: peer.id.clone(),
                outgoing: false,
                body: "中文👩‍💻\n".repeat(20),
                time: 1,
                state: "saved".into(),
            }],
            files: BTreeMap::new(),
            rates: HashMap::new(),
            prompts: VecDeque::new(),
            scroll: 0,
            last_message_width: 60,
            no_color: true,
            ascii: true,
            theme: crate::Theme::System,
            cfg: Config {
                language: "en".into(),
                ..Config::default()
            },
            view: "chats".into(),
            scan_request: false,
            listen_request: false,
            file_prompts: VecDeque::new(),
            quit_confirm: false,
            attach: None,
        };
        (d, app)
    }
    fn screen(app: &App, width: u16, height: u16) -> String {
        let mut terminal =
            Terminal::new(ratatui::backend::TestBackend::new(width, height)).unwrap();
        terminal.draw(|f| app.draw(f)).unwrap();
        let buffer = terminal.backend().buffer();
        let mut result = String::new();
        for y in 0..height {
            let mut x = 0;
            while x < width {
                let symbol = buffer[(x, y)].symbol();
                result.push_str(symbol);
                x += UnicodeWidthStr::width(symbol).max(1) as u16;
            }
            result.push('\n');
        }
        result
    }
    #[test]
    fn empty_screen_explains_how_to_connect_without_empty_details() {
        let (_d, mut app) = fixture();
        app.cfg.language = "zh-CN".into();
        app.peers.clear();
        app.selection = None;
        app.messages.clear();
        app.focus = 0;
        for width in [45, 80, 120] {
            let text = screen(&app, width, 24);
            assert!(text.contains("搜索附近设备"), "{text}");
            assert!(text.contains("connect HOST:PORT"), "{text}");
            assert!(
                !text.contains("设备详情"),
                "Empty state must not reserve a details column: {text}"
            );
        }
    }
    #[test]
    fn compact_consent_keeps_all_choices_and_full_code_visible() {
        let (_d, mut app) = fixture();
        app.cfg.language = "zh-CN".into();
        let (reply, _) = std::sync::mpsc::sync_channel(1);
        let mut peer = app.peers.values().next().unwrap().clone();
        peer.name = "中文👩‍💻".repeat(8);
        app.prompts.push_back(Prompt {
            peer,
            code: "abcd1234".repeat(8),
            reply,
            created: Instant::now(),
        });
        for (language, labels) in [
            ("en", ["1 Accept once", "2 Remember", "3 Reject"]),
            ("zh-CN", ["1 仅本次", "2 记住", "3 拒绝"]),
            ("zh-Hant", ["1 僅本次", "2 記住", "3 拒絕"]),
            ("ja", ["1 今回許可", "2 記憶", "3 拒否"]),
            ("ko", ["1 한 번 허용", "2 기억", "3 거부"]),
        ] {
            app.cfg.language = language.into();
            for (width, height) in [(45, 16), (45, 24), (80, 24), (120, 24)] {
                let text = screen(&app, width, height);
                for label in labels {
                    assert!(text.contains(label), "{text}");
                }
                assert_eq!(
                    text.matches("abcd1234").count(),
                    8,
                    "The complete code must remain visible: {text}"
                );
            }
        }
    }
    #[test]
    fn full_status_view_preserves_the_original_error_and_draft() {
        let (_d, mut app) = fixture();
        let runtime = Runtime::client(app.store.clone(), app.cfg.receive_dir.clone());
        app.status = "Network failure details ".repeat(100);
        let original = app.status.clone();
        let draft = app.editor.text.clone();
        app.execute(&runtime, "status").unwrap();
        assert_eq!(app.status, original);
        assert_eq!(app.editor.text, draft);
        assert_eq!(app.view, "status");
    }
    #[test]
    fn pasted_draft_remains_visible_without_a_selected_peer() {
        let (_d, mut app) = fixture();
        app.peers.clear();
        app.selection = None;
        app.messages.clear();
        app.editor.text = "Unsaved draft".into();
        app.focus = 2;
        assert!(screen(&app, 45, 24).contains("Unsaved draft"));
        assert_eq!(fit("👩‍💻中文", 3), "👩‍💻…");
    }
    #[test]
    fn selected_row_keeps_one_background_across_primary_and_secondary_text() {
        let (_d, mut app) = fixture();
        app.no_color = false;
        app.ascii = false;
        app.theme = crate::Theme::Midnight;
        app.cfg.color = "truecolor".into();
        let mut terminal = Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        terminal.draw(|f| app.draw(f)).unwrap();
        let buffer = terminal.backend().buffer();
        let (x, y) = (0..24)
            .flat_map(|y| (0..80).map(move |x| (x, y)))
            .find(|p| buffer[*p].symbol() == "›")
            .unwrap();
        let effective = |cell: &ratatui::buffer::Cell| {
            if cell.modifier.contains(Modifier::REVERSED) {
                cell.fg
            } else {
                cell.bg
            }
        };
        let selected = effective(&buffer[(x, y)]);
        for row in [y, y + 1] {
            let mut col = x;
            // Continuation cells of a wide glyph are not painted by the backend.
            while col < x + 15 {
                assert_eq!(effective(&buffer[(col, row)]), selected);
                col += buffer[(col, row)].symbol().width().max(1) as u16;
            }
        }
        assert_ne!(selected, effective(&buffer[(x, y + 3)]));
    }
    #[test]
    fn help_is_localized_and_language_switch_keeps_draft() {
        let (_d, mut app) = fixture();
        let runtime = Runtime::client(app.store.clone(), app.cfg.receive_dir.clone());
        let draft = app.editor.text.clone();
        app.execute(&runtime, "help").unwrap();
        assert!(screen(&app, 80, 30).contains("CHAT & DEVICES"));
        let first = screen(&app, 80, 24);
        app.page(false);
        assert_ne!(screen(&app, 80, 24), first);
        assert!(!screen(&app, 80, 24).contains("CHAT & DEVICES"));
        app.page(true);
        assert_eq!(screen(&app, 80, 24), first);
        app.execute(&runtime, "set language zh-CN").unwrap();
        for width in [45, 80, 120] {
            let text = screen(&app, width, 30);
            assert!(text.contains("聊天与设备"), "{text}");
            assert!(!text.contains("CHAT & DEVICES"), "{text}");
        }
        assert_eq!(app.editor.text, draft);
        assert_eq!(
            Config::load(&app.store.lock().unwrap()).unwrap().language,
            "zh-CN"
        );
    }
    #[test]
    #[ignore = "Export actual Ratatui cells only when a preview directory is requested"]
    fn export_preview() {
        let directory = std::path::PathBuf::from(
            std::env::var("NEARBYIM_UI_PREVIEW_DIR").expect("preview directory"),
        );
        std::fs::create_dir_all(&directory).unwrap();
        let (_d, mut app) = fixture();
        app.cfg.language = "zh-CN".into();
        app.cfg.color = "truecolor".into();
        app.no_color = false;
        app.ascii = false;
        app.theme = crate::Theme::Midnight;
        app.store.lock().unwrap().identity.name = "我的电脑".into();
        let peer = app.peers.values_mut().next().unwrap();
        peer.name = "小林的手机".into();
        app.ready.insert(peer.id.clone());
        app.messages.truncate(1);
        app.messages[0].time = 1_700_000_000_000;
        app.messages[0].body = "这份文件已经收到了，谢谢！\n稍后我把整理后的版本发给你。".into();
        app.editor.text = "好的，收到后我再确认一下。".into();
        app.editor.cursor = app.editor.text.len();
        app.status = app.t("status.ready");
        for (name, width, height) in [
            ("chat-80", 80, 28),
            ("chat-120", 120, 30),
            ("chat-45", 45, 24),
            ("empty-80", 80, 28),
            ("help-80", 80, 30),
        ] {
            let mut terminal =
                Terminal::new(ratatui::backend::TestBackend::new(width, height)).unwrap();
            if name.starts_with("empty") {
                app.peers.clear();
                app.selection = None;
                app.messages.clear();
                app.focus = 0;
                app.editor = Editor::default();
                app.status = app.fmt("status.listener_off", &[("commands", &app.shortcuts())]);
            }
            if name.starts_with("help") {
                app.view = "help".into();
                app.focus = 1;
                app.status = app.t("help.navigation");
            }
            terminal.draw(|f| app.draw(f)).unwrap();
            let buffer = terminal.backend().buffer();
            let cells=buffer.content.iter().map(|c|serde_json::json!({"text":c.symbol(),"width":c.symbol().width(),"fg":format!("{:?}",c.fg),"bg":format!("{:?}",c.bg),"modifiers":format!("{:?}",c.modifier)})).collect::<Vec<_>>();
            std::fs::write(
                directory.join(format!("{name}.json")),
                serde_json::to_vec(
                    &serde_json::json!({"width":width,"height":height,"cells":cells}),
                )
                .unwrap(),
            )
            .unwrap();
        }
    }
    #[test]
    fn renders_narrow_wide_cjk_and_consent() {
        let (_d, mut app) = fixture();
        for (w, h) in [(40, 16), (80, 24), (120, 30), (160, 40)] {
            let backend = ratatui::backend::TestBackend::new(w, h);
            let mut t = Terminal::new(backend).unwrap();
            t.draw(|f| app.draw(f)).unwrap();
            let text = t
                .backend()
                .buffer()
                .content
                .iter()
                .map(|c| c.symbol())
                .collect::<String>();
            assert!(text.contains("NearbyIM Terminal"));
            assert!(!text.contains('\u{1b}'));
        }
        let (reply, _) = std::sync::mpsc::sync_channel(1);
        let peer = app.peers.values().next().unwrap().clone();
        app.prompts.push_back(Prompt {
            peer,
            code: "a".repeat(64),
            reply,
            created: Instant::now(),
        });
        let mut t = Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        t.draw(|f| app.draw(f)).unwrap();
        let text = t
            .backend()
            .buffer()
            .content
            .iter()
            .map(|c| c.symbol())
            .collect::<String>();
        assert!(text.contains("Connection consent"));
        assert!(text.contains("Accept once"));
    }
}

#[cfg(test)]
mod long_input_tests {
    use super::*;
    #[test]
    fn preserves_long_unicode_paste() {
        let mut editor = Editor::default();
        let text = "👩🏽‍💻e\u{301}".repeat(1000);
        editor.insert(&text);
        assert_eq!(
            editor.text, text,
            "reasonable long paste must not disappear"
        );
    }
}

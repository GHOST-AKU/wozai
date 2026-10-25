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
    widgets::{Block, Clear, List, ListItem, ListState, Paragraph, Wrap},
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
}
impl Editor {
    fn insert(&mut self, s: &str) {
        let s: String = s
            .chars()
            .filter(|c| !c.is_control() || matches!(c, '\n' | '\t'))
            .collect();
        if self.text.len() + s.len() <= 8192 {
            self.text.insert_str(self.cursor, &s);
            self.cursor += s.len();
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
    fn display(&self, bytes: u64, total: u64) -> String {
        let seconds = self.start.elapsed().as_secs_f64();
        if seconds < 1.0 || bytes <= self.initial {
            return "Collecting speed samples".into();
        }
        let average = (bytes - self.initial) as f64 / seconds;
        format!(
            "{:.1} MiB/s · avg {:.1} MiB/s · ETA ~{} s",
            self.instant.unwrap_or(average) / 1048576.0,
            average / 1048576.0,
            ((total.saturating_sub(bytes)) as f64 / average).ceil() as u64
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
    fn t(&self, en: &'static str) -> String {
        if self.cfg.language != "zh-CN" {
            return en.into();
        }
        match en {
            "Peers" => "会话与设备",
            "Peers [focused]" => "会话与设备 [焦点]",
            "Chat" => "聊天",
            "Messages · saved ≠ read" => "消息 · 已保存不等于已读",
            "Command" => "命令",
            "Compose · Enter send · Alt+Enter newline" => "输入 · Enter 发送 · Alt+Enter 换行",
            "Details" => "设备详情",
            "Exit?" => "退出？",
            "Confirm attachment" => "确认发送附件",
            "Receive file?" => "接收文件？",
            "Connection consent" => "设备连接认可",
            _ => en,
        }
        .into()
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
            "set" => {
                let (key, value) = arg.split_once(' ').context("usage: set KEY VALUE")?;
                self.cfg.set(key, value)?;
                self.cfg.save(&self.store.lock().unwrap())?;
                self.status = "Settings saved; listener/theme changes apply on restart".into();
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
                self.status =
                    "History page loaded; older / latest navigate without unbounded memory".into();
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
            "cancel" => self.command(Command::Cancel(arg.into()))?,
            "resume" => self.command(Command::Resume(arg.into()))?,
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
                self.status="Commands: connect HOST:PORT | file PATH | pause/cancel/resume ID | code | verify FULL_CODE | unverify | search TEXT | peer ID | quit".into();
                return Ok(false);
            }
            _ => anyhow::bail!("unknown command; Ctrl+P then help"),
        }
        self.status = format!("{verb}: requested");
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
            Event::Ready(id) => {
                self.ready.insert(id);
                self.status =
                    "Encrypted connection ready; identity verification is separate".into();
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
                self.status = why;
            }
            Event::Error(e) => self.status = e,
        }
        Ok(())
    }
    fn draw(&self, f: &mut ratatui::Frame) {
        let area = f.area();
        if area.width < 45 || area.height < 16 {
            f.render_widget(
                Paragraph::new(
                    "NearbyIM Terminal\nEnlarge to at least 45 columns × 16 rows. Ctrl+C quits.",
                ),
                area,
            );
            return;
        }
        let rows = Layout::vertical([
            Constraint::Length(2),
            Constraint::Min(3),
            Constraint::Length(2),
        ])
        .split(area);
        let own = self.store.lock().unwrap().identity.name.clone();
        let color = if self.no_color {
            Color::Reset
        } else {
            match self.theme {
                crate::Theme::Paper => color_for((63, 96, 83), &self.cfg.color),
                crate::Theme::Sage => color_for((193, 221, 172), &self.cfg.color),
                _ => color_for((156, 209, 169), &self.cfg.color),
            }
        };
        let base = if self.no_color {
            Style::default()
        } else {
            match self.theme {
                crate::Theme::Midnight => Style::default()
                    .fg(color_for((226, 235, 227), &self.cfg.color))
                    .bg(color_for((17, 26, 23), &self.cfg.color)),
                crate::Theme::Sage => Style::default()
                    .fg(color_for((225, 241, 226), &self.cfg.color))
                    .bg(color_for((24, 48, 37), &self.cfg.color)),
                crate::Theme::Paper => Style::default()
                    .fg(color_for((38, 55, 46), &self.cfg.color))
                    .bg(color_for((244, 242, 236), &self.cfg.color)),
                _ => Style::default(),
            }
        };
        f.render_widget(Block::default().style(base), area);
        f.render_widget(Paragraph::new(format!("NearbyIM Terminal · {}\nTab focus · Ctrl+K commands · / search · ? help · Ctrl+C quit",safe(&own))).style(Style::default().fg(color)),rows[0]);
        let center = rows[1];
        let wide = center.width >= 72;
        let triple = center.width >= 110;
        let mut parts = vec![];
        if wide {
            parts.push(Constraint::Length(27));
            parts.push(Constraint::Min(30));
            if triple {
                parts.push(Constraint::Length(26));
            }
        } else {
            parts.push(Constraint::Percentage(100));
        }
        let cols = Layout::horizontal(parts).split(center);
        let ids = self.ids();
        let selected = self.current();
        if self.view == "chats" || self.view == "nearby" {
            if wide || self.focus == 0 {
                let items: Vec<ListItem> = ids
                    .iter()
                    .map(|id| {
                        let name = self
                            .peers
                            .get(id)
                            .map(|p| p.name.as_str())
                            .or_else(|| self.nearby.get(id).map(|p| p.name.as_str()))
                            .unwrap_or(id);
                        let state = if self.ready.contains(id) {
                            "connected"
                        } else if self.nearby.contains_key(id) {
                            "seen in last scan"
                        } else {
                            "offline"
                        };
                        ListItem::new(format!("{}\n{state} · {}", safe(name), &id[..8]))
                    })
                    .collect();
                let mut state = ListState::default().with_selected(
                    selected
                        .as_ref()
                        .and_then(|id| ids.iter().position(|p| p == id)),
                );
                f.render_stateful_widget(
                    List::new(items)
                        .block(panel(
                            self.ascii,
                            if self.focus == 0 {
                                "Peers [focused]"
                            } else {
                                "Peers"
                            },
                        ))
                        .highlight_style(Style::default().add_modifier(Modifier::REVERSED))
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
                let peer = selected.as_ref().and_then(|id| self.peers.get(id));
                let verified = peer.is_some_and(|p| p.verified);
                let ready = selected.as_ref().is_some_and(|id| self.ready.contains(id));
                let status = if ready {
                    format!(
                        "LAN · end-to-end encrypted · identity {}",
                        if verified { "verified" } else { "unverified" }
                    )
                } else {
                    "Offline · Enter on a peer connects".into()
                };
                f.render_widget(
                    Paragraph::new(status)
                        .wrap(Wrap { trim: false })
                        .block(panel(
                            self.ascii,
                            peer.map_or("Chat".into(), |p| safe(&p.name)),
                        )),
                    chatrows[0],
                );
                let mut lines: Vec<Line> = vec![];
                for m in self.messages.iter().filter(|m| {
                    Some(&m.peer) == selected.as_ref()
                        && (self.search.is_empty() || m.body.contains(&self.search))
                }) {
                    let prefix = if m.outgoing { "You" } else { "Peer" };
                    lines.push(Line::styled(
                        format!("{prefix} · {} · {}", crate::iso(m.time), safe(&m.state)),
                        Style::default().fg(color),
                    ));
                    for line in safe(&m.body).split('\n') {
                        lines.push(Line::raw(line.to_string()));
                    }
                    lines.push(Line::raw(""));
                }
                for s in self
                    .files
                    .values()
                    .filter(|s| Some(&s.peer) == selected.as_ref())
                {
                    lines.push(Line::raw(format!(
                        "File {}\n{} / {} bytes · {}\nID: {}",
                        safe(&s.info.name),
                        s.offset,
                        s.info.size,
                        s.state,
                        s.info.id
                    )));
                }
                for s in self
                    .files
                    .values()
                    .filter(|s| Some(&s.peer) == selected.as_ref())
                {
                    if let Some(rate) = self.rates.get(&s.info.id)
                        && !["received", "saved", "paused", "canceled", "failed"]
                            .contains(&s.state.as_str())
                    {
                        lines.push(Line::raw(rate.display(s.offset, s.info.size)));
                    }
                }
                if lines.is_empty() {
                    lines.push(Line::raw(
                        "No messages. Connect to a nearby device to start.",
                    ));
                }
                let text = Text::from(lines);
                let width = chatrows[1].width.saturating_sub(2).max(1) as usize;
                let count = text
                    .lines
                    .iter()
                    .map(|l| l.width().max(1).div_ceil(width))
                    .sum::<usize>();
                let offset = count
                    .saturating_sub(chatrows[1].height.saturating_sub(2) as usize)
                    .saturating_sub(self.scroll as usize)
                    .min(u16::MAX as usize) as u16;
                f.render_widget(
                    Paragraph::new(text)
                        .wrap(Wrap { trim: false })
                        .scroll((offset, 0))
                        .block(panel(self.ascii, self.t("Messages · saved ≠ read"))),
                    chatrows[1],
                );
                let input_width = chatrows[2].width.saturating_sub(2).max(1) as usize;
                let (composer, cursor_row, cursor_col) = editor_lines(&self.editor, input_width);
                let input_height = chatrows[2].height.saturating_sub(2).max(1) as usize;
                let input_scroll = cursor_row.saturating_sub(input_height - 1);
                f.render_widget(
                    Paragraph::new(composer)
                        .scroll((input_scroll.min(u16::MAX as usize) as u16, 0))
                        .block(panel(
                            self.ascii,
                            format!(
                                "{} · {} / 8192 bytes",
                                if self.palette {
                                    "Command"
                                } else {
                                    "Compose · Enter send · Alt+Enter newline"
                                },
                                self.editor.text.len()
                            ),
                        ))
                        .style(if self.focus == 2 {
                            Style::default().fg(color)
                        } else {
                            Style::default()
                        }),
                    chatrows[2],
                );
                if self.focus == 2 && self.prompts.is_empty() {
                    f.set_cursor_position((
                        chatrows[2].x + 1 + cursor_col.min(input_width - 1) as u16,
                        chatrows[2].y + 1 + (cursor_row - input_scroll) as u16,
                    ));
                }
                if triple {
                    let info=peer.map(|p|format!("Device\n{}\n\nIdentity fingerprint\n{}\n\nTrust\n{}\n\nFile transport\nLAN\n\n:code to compare\n:verify FULL_CODE\n\nFiles\n:file PATH\n:pause ID\n:resume ID\n:cancel ID",p.id,crate::profile::fingerprint(&STANDARD.decode(&p.root).unwrap_or_default()),if p.verified{"manually verified"}else{"not manually verified"})).unwrap_or_else(||"Select or connect a peer".into());
                    f.render_widget(
                        Paragraph::new(info)
                            .wrap(Wrap { trim: false })
                            .block(panel(self.ascii, self.t("Details"))),
                        cols[2],
                    );
                }
            }
        } else {
            let body=match self.view.as_str(){
                "transfers"=>self.files.values().map(|s|format!("{} · {} · {}/{} bytes\nID {}\nSaved location {}\n",safe(&s.info.name),s.state,s.offset,s.info.size,s.info.id,s.destination.display())).collect::<Vec<_>>().join("\n"),
                "security"=>self.peers.values().map(|p|format!("{} · {}\nRemembered={} · manually verified={}\nFingerprint {}\n",safe(&p.name),p.id,p.remembered,p.verified,crate::profile::fingerprint(&STANDARD.decode(&p.root).unwrap_or_default()))).collect::<Vec<_>>().join("\n"),
                "settings"=>serde_json::to_string_pretty(&self.cfg).unwrap_or_default(),
                _=>"Quiet Terminal · NearbyIM\n\nCtrl+K command palette (Ctrl+P alias) · Tab focus · Esc back\n? help / search j/k navigation · Enter open/send\nCtrl+J or Alt+Enter newline · a attach outside composer\n\nCommands: chats | scan | transfers | security | settings\nconnect HOST:PORT | disconnect | listen on|off\nfile PATH | files PATH (one per line; confirm before send) | pause ID | resume ID | cancel ID\ncode | verify FULL_CODE | unverify | search TEXT\nset KEY VALUE | quit\n\nLAN discovery is bounded and explicit. Default listening is off.\nOne active session; disconnect safely before switching peers.\nFiles are received only with approval unless trusted auto-receive is enabled.\nEncryption, TOFU consent and manual identity verification are separate.\nA saved receipt is not a read receipt. History is not encrypted at rest.".into(),
            };
            f.render_widget(
                Paragraph::new(safe(&body))
                    .wrap(Wrap { trim: false })
                    .scroll((self.scroll, 0))
                    .block(panel(self.ascii, self.view.clone())),
                center,
            );
        }
        f.render_widget(
            Paragraph::new(safe(&self.status)).wrap(Wrap { trim: false }),
            rows[2],
        );
        if self.palette {
            let p = centered(area, 80, 8);
            f.render_widget(Clear, p);
            let (text, row, col) =
                editor_lines(&self.editor, p.width.saturating_sub(2).max(1) as usize);
            let scroll = row.saturating_sub(3);
            f.render_widget(
                Paragraph::new(text)
                    .scroll((scroll as u16, 0))
                    .block(panel(self.ascii, "Command · Enter run · Esc cancel")),
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
            let p = centered(area, 70, 7);
            f.render_widget(Clear, p);
            f.render_widget(Paragraph::new("Active transfers will pause and checkpoint before exit. No background transfer continues.\n\ny Exit and pause · n Continue").wrap(Wrap{trim:false}).block(panel(self.ascii,"Exit?")),p);
        }
        if let Some(paths) = &self.attach {
            let p = centered(area, 75, 10);
            f.render_widget(Clear, p);
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
            f.render_widget(Paragraph::new(format!("{}\n{} bytes · recipient {}\nNo overwrite at receiver. Encrypted NIM4.\n\ny Send · n Cancel",format_args!("{} files\n{}", paths.len(), safe(&names)),size,self.current().unwrap_or_default())).wrap(Wrap{trim:false}).block(panel(self.ascii,"Confirm attachment")),p);
        }
        if let Some(pending) = self.file_prompts.front() {
            let p = centered(area, 75, 10);
            f.render_widget(Clear, p);
            f.render_widget(Paragraph::new(format!("{}\n{} bytes · sender {}\nSave to {}\nNo overwrite; file is never executed automatically.\n\ny Accept · n Reject",safe(&pending.name),pending.size,pending.peer,safe(&self.cfg.receive_dir.display().to_string()))).wrap(Wrap{trim:false}).block(panel(self.ascii,"Receive file?")),p);
        }

        if let Some(prompt) = self.prompts.front() {
            if area.width < 40 || area.height < 16 {
                f.render_widget(Clear, area);
                f.render_widget(
                    Paragraph::new(
                        "Enlarge to 40 × 16 to review connection consent. Ctrl+C quits.",
                    ),
                    area,
                );
                return;
            }
            let popup = centered(area, 80, 16);
            f.render_widget(Clear, popup);
            let code = prompt
                .code
                .as_bytes()
                .chunks(8)
                .map(|b| std::str::from_utf8(b).unwrap())
                .collect::<Vec<_>>()
                .join(" ");
            f.render_widget(Paragraph::new(format!("{}\n{}\nEncrypted; identity unverified.\nCompare this code out of band:\n\n{}\n\n1 Accept once  2 Remember  3 Reject\nConsent expires after 50 seconds.",safe(&prompt.peer.name),prompt.peer.id,code)).wrap(Wrap{trim:false}).block(panel(self.ascii,"Connection consent")),popup);
        }
    }
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
        selected: 0,
        selection: None,
        focus: 0,
        editor: Editor::default(),
        palette: false,
        chat_draft: None,
        status: if runtime.port == 0 {
            "LAN listening disabled · Ctrl+K: scan / connect HOST:PORT / listen on".into()
        } else {
            format!(
                "Listening on port {} · Ctrl+K: scan / connect HOST:PORT",
                runtime.port
            )
        },
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
                app.status="Scanning LAN for 8 seconds; Esc cancels. Use connect HOST:PORT if multicast is blocked.".into();
                dirty = true;
            }
            if scan_deadline.is_some_and(|d| Instant::now() >= d) {
                discovery.take();
                scan_deadline = None;
                app.status =
                    "Scan complete. Results are discovery hints, not online/identity proof.".into();
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
                                    app.status = e.to_string();
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
                app.last_message_width = if width >= 110 {
                    width.saturating_sub(55)
                } else if width >= 72 {
                    width.saturating_sub(29)
                } else {
                    width.saturating_sub(2)
                };
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
                                    app.status = e.to_string();
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
                        if size.width < 40 || size.height < 16 {
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
                            KeyCode::Tab => app.focus = (app.focus + 1) % 3,
                            KeyCode::BackTab => app.focus = (app.focus + 2) % 3,
                            KeyCode::Esc => {
                                if scan_deadline.is_some() {
                                    discovery.take();
                                    scan_deadline = None;
                                    app.status = "Scan canceled".into();
                                }
                                if app.palette {
                                    app.palette = false;
                                    app.editor = app.chat_draft.take().unwrap_or_default();
                                } else {
                                    app.focus = 0;
                                    app.view = "chats".into();
                                }
                            }
                            KeyCode::Char('?') if app.focus != 2 => {
                                app.view = "help".into();
                            }
                            KeyCode::Char('/') if app.focus != 2 => {
                                app.chat_draft = Some(std::mem::take(&mut app.editor));
                                app.palette = true;
                                app.editor = Editor {
                                    text: "search ".into(),
                                    cursor: 7,
                                };
                                app.focus = 2;
                            }
                            KeyCode::Char('a') if app.focus != 2 => {
                                app.chat_draft = Some(std::mem::take(&mut app.editor));
                                app.palette = true;
                                app.editor = Editor {
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
                            KeyCode::PageUp => app.scroll = app.scroll.saturating_add(10),
                            KeyCode::PageDown => app.scroll = app.scroll.saturating_sub(10),
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
                                    crate::wire::text_valid(&text)?;
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
                        Err(e) => app.status = format!("{e:#}"),
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
        assert_eq!(e.text.len(), 8190);
        e = Editor::default();
        e.insert("\u{1b}[31m\nabc");
        assert!(!e.text.contains('\u{1b}'));
        assert!(e.text.contains('\n'));
    }
}

#[cfg(test)]
mod render_tests {
    use super::*;
    #[test]
    fn renders_narrow_wide_cjk_and_consent() {
        let d = tempfile::tempdir().unwrap();
        let store = Arc::new(Mutex::new(Profile::open(d.path().join("p"), None).unwrap()));
        let peer = Peer {
            id: uuid::Uuid::new_v4().to_string(),
            name: "中文👩‍💻 peer".into(),
            root: STANDARD.encode(store.lock().unwrap().identity.public.clone()),
            remembered: false,
            verified: false,
            endpoint: "127.0.0.1:1234".into(),
        };
        let mut peers = BTreeMap::new();
        peers.insert(peer.id.clone(), peer.clone());
        let mut app = App {
            store,
            peers,
            nearby: BTreeMap::new(),
            connections: HashMap::new(),
            ready: Default::default(),
            selected: 0,
            selection: Some(peer.id.clone()),
            focus: 2,
            editor: Editor {
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
            cfg: Config::default(),
            view: "chats".into(),
            scan_request: false,
            listen_request: false,
            file_prompts: VecDeque::new(),
            quit_confirm: false,
            attach: None,
        };
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

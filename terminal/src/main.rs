mod config;
mod crypto;
mod discovery;
mod files;
mod network;
mod profile;
mod ui;
mod wire;

use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use clap::{CommandFactory, Parser, Subcommand};
use discovery::Discovery;
use network::{Command, Consent, Event, Runtime};
use profile::Profile;
use serde::Serialize;
use serde_json::{Value, json};
use std::{
    cell::RefCell,
    collections::HashMap,
    io::{self, BufRead, IsTerminal, Read},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, SyncSender},
    },
    thread,
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, clap::ValueEnum)]
pub enum Theme {
    Midnight,
    Paper,
    Sage,
    Mono,
    System,
}
#[derive(Parser)]
#[command(name="nearbyim",version,about="NearbyIM Terminal · 我在 — independent NIM4 terminal client",color=clap::ColorChoice::Never)]
struct Cli {
    #[arg(
        long,
        global = true,
        help = "Independent Terminal profile; never point at GUI data"
    )]
    profile: Option<PathBuf>,
    #[arg(long, global = true)]
    name: Option<String>,
    #[arg(
        long,
        global = true,
        help = "One versioned JSON result; no implicit approval"
    )]
    json: bool,
    #[arg(long, global = true)]
    no_color: bool,
    #[arg(long, global = true, value_enum)]
    theme: Option<Theme>,
    #[arg(long, global = true)]
    ascii: bool,
    #[arg(long,global=true,default_value_t=60,value_parser=duration,help="Deadline: seconds or suffix s/m (0 = until interrupted)")]
    timeout: u64,
    #[arg(long, global = true)]
    receive_dir: Option<PathBuf>,
    #[arg(
        long,
        global = true,
        help = "Explicitly enable foreground LAN listening on HOST:PORT"
    )]
    listen: Option<String>,
    #[arg(
        long,
        global = true,
        help = "Connect via this address; identity approval remains required"
    )]
    address: Option<String>,
    #[arg(
        long,
        global = true,
        help = "Additional diagnostics, never message bodies or keys"
    )]
    verbose: bool,
    #[command(subcommand)]
    command: Option<Action>,
}
#[derive(Subcommand)]
enum Action {
    /// Open the TUI focused on a peer; no hidden background service.
    Chat {
        peer: Option<String>,
    },
    /// Compatibility foreground line client; does not persist after exit.
    Connect {
        peer: Option<String>,
    },
    /// Bounded LAN DNS-SD discovery, including wired Ethernet peers.
    Peers {
        #[command(subcommand)]
        action: Option<PeersAction>,
    },
    /// Local conversations and message history.
    Chats {
        #[command(subcommand)]
        action: Option<ChatsAction>,
    },
    /// Send and wait for a durable saved receipt, not a read receipt.
    Send {
        peer: Option<String>,
        #[arg(long,required_unless_present_any=["file","stdin"],conflicts_with_all=["file","stdin"])]
        text: Option<String>,
        #[arg(long,required_unless_present_any=["text","stdin"],conflicts_with_all=["text","stdin"])]
        file: Option<PathBuf>,
        #[arg(long,required_unless_present_any=["text","file"],conflicts_with_all=["text","file"])]
        stdin: bool,
    },
    /// Transfer history, explicit checkpoint resume and cancellation.
    Files {
        #[command(subcommand)]
        action: FilesAction,
    },
    /// Compatibility shortcut for files resume.
    Resume {
        peer: String,
        id: String,
    },
    /// Compatibility shortcut for files list.
    Transfers,
    /// Explicit foreground receiver; no daemon. Unknown peers require a TTY.
    Listen {
        #[arg(long)]
        lan: bool,
    },
    Identity {
        #[command(subcommand)]
        action: IdentityAction,
    },
    Trust {
        #[command(subcommand)]
        action: TrustAction,
    },
    Config {
        #[command(subcommand)]
        action: ConfigAction,
    },
    Status,
    /// Redacted diagnostics for this profile, not another running process.
    Doctor,
    #[command(alias = "completions")]
    Completion {
        shell: clap_complete::Shell,
    },
}
#[derive(Subcommand)]
enum PeersAction {
    Scan,
    Show { peer: String },
}
#[derive(Subcommand)]
enum ChatsAction {
    List,
    Show {
        peer: String,
        #[arg(long)]
        before: Option<i64>,
        #[arg(long,default_value_t=500,value_parser=clap::value_parser!(u16).range(1..=500))]
        limit: u16,
    },
}
#[derive(Subcommand)]
enum FilesAction {
    List,
    Resume { id: String },
    Cancel { id: String },
}
#[derive(Subcommand)]
enum IdentityAction {
    Show,
    Verify { peer: String },
    Forget { peer: String },
}
#[derive(Subcommand)]
enum TrustAction {
    List,
    Revoke { peer: String },
}
#[derive(Subcommand)]
enum ConfigAction {
    List,
    Get { key: String },
    Set { key: String, value: String },
}
#[derive(Debug)]
struct Exit {
    code: i32,
    message: String,
}
impl std::fmt::Display for Exit {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}
impl std::error::Error for Exit {}
fn fail(code: i32, message: &str) -> anyhow::Error {
    Exit {
        code,
        message: message.into(),
    }
    .into()
}
fn duration(s: &str) -> std::result::Result<u64, String> {
    let (n, m) = if let Some(s) = s.strip_suffix('s') {
        (s, 1)
    } else if let Some(s) = s.strip_suffix('m') {
        (s, 60)
    } else {
        (s, 1)
    };
    let n = n
        .parse::<u64>()
        .map_err(|_| "expected seconds, e.g. 8s or 1m")?
        .checked_mul(m)
        .ok_or("timeout overflow")?;
    if n > 86400 {
        Err("timeout must not exceed 24 hours".into())
    } else {
        Ok(n)
    }
}
pub fn safe(s: &str) -> String {
    s.chars().map(|c|if c.is_control()&&!matches!(c,'\n'|'\t')||matches!(c,'\u{061c}'|'\u{200e}'|'\u{200f}'|'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}'){'�'}else{c}).collect()
}
thread_local! {static OUTPUT:RefCell<Value>=const{RefCell::new(Value::Null)};}
fn sanitize_json(mut value: Value) -> Value {
    match &mut value {
        Value::String(s) => *s = safe(s),
        Value::Array(a) => {
            for v in a {
                *v = sanitize_json(std::mem::take(v));
            }
        }
        Value::Object(o) => {
            for v in o.values_mut() {
                *v = sanitize_json(std::mem::take(v));
            }
        }
        _ => {}
    }
    value
}
fn emit(value: impl Serialize, json_mode: bool, plain: impl AsRef<str>) -> Result<()> {
    if json_mode {
        OUTPUT.with(|o| *o.borrow_mut() = sanitize_json(serde_json::to_value(value).unwrap()));
    } else {
        use io::Write;
        let mut out = io::stdout().lock();
        writeln!(out, "{}", safe(plain.as_ref()))?;
        out.flush()?;
    }
    Ok(())
}
fn prompt(question: &str, default: &str) -> Result<String> {
    eprint!("{question} [{default}]: ");
    use io::Write;
    io::stderr().flush()?;
    let mut s = String::new();
    ensure!(io::stdin().read_line(&mut s)? > 0, "input closed");
    let s = s.trim();
    Ok(if s.is_empty() {
        default.into()
    } else {
        s.into()
    })
}
fn setup(p: &mut Profile, cfg: &mut config::Config) -> Result<()> {
    eprintln!(
        "NearbyIM Terminal creates a separate device identity and local history. No account, cloud, telemetry or GUI identity import. History is not encrypted at rest."
    );
    cfg.language = prompt("Interface language: en / zh-CN", &cfg.language)?;
    cfg.nickname = prompt("Nickname", &cfg.nickname)?;
    cfg.receive_dir = prompt(
        "Receive directory",
        cfg.receive_dir
            .to_str()
            .context("receive path must be UTF-8")?,
    )?
    .into();
    cfg.lan_listen = prompt("Enable LAN listening? yes/no", "no")? == "yes";
    cfg.auto_receive_trusted = prompt(
        "Automatically receive files from remembered devices? yes/no",
        "no",
    )? == "yes";
    cfg.save(p)?;
    p.identity.name = cfg.nickname.clone();
    Ok(())
}
fn run(mut cli: Cli) -> Result<()> {
    if let Some(Action::Completion { shell }) = &cli.command {
        let mut script = Vec::new();
        clap_complete::generate(*shell, &mut Cli::command(), "nearbyim", &mut script);
        if cli.json {
            return emit(
                json!({"status":"ok","shell":shell.to_string(),"completion":String::from_utf8(script)?}),
                true,
                "",
            );
        }
        use io::Write;
        io::stdout().write_all(&script)?;
        return Ok(());
    }
    let interactive = io::stdin().is_terminal() && io::stdout().is_terminal() && !cli.json;
    let tui = cli.command.is_none() || matches!(cli.command, Some(Action::Chat { .. }));
    if tui && !interactive {
        return Err(fail(
            2,
            "the TUI requires a terminal; use --help, status, listen, or send",
        ));
    }
    if tui && std::env::var("TERM").is_ok_and(|t| t == "dumb") {
        return Err(fail(
            2,
            "TERM=dumb cannot display a TUI; use a CLI subcommand",
        ));
    }
    if let Some(Action::Send {
        text, stdin, file, ..
    }) = &mut cli.command
    {
        if *stdin {
            let mut b = Vec::new();
            io::stdin().take(8193).read_to_end(&mut b)?;
            if b.len() > 8192 {
                return Err(fail(2, "stdin exceeds 8192 UTF-8 bytes"));
            }
            *text = Some(String::from_utf8(b).map_err(|_| fail(2, "stdin must be valid UTF-8"))?);
        }
        if let Some(t) = text {
            wire::text_valid(t)
                .map_err(|e| fail(2, &format!("{e}; got {} UTF-8 bytes", t.len())))?;
        }
        if let Some(path) = file {
            let m = std::fs::metadata(path)?;
            if !m.is_file() || m.len() > wire::MAX_FILE {
                return Err(fail(2, "source must be a regular file of at most 10 GiB"));
            }
        }
    }
    let path = cli.profile.clone().unwrap_or_else(|| {
        dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("nearbyim-terminal")
    });
    let first = !path.join("config.json").exists();
    let mut profile = Profile::open(path, cli.name.as_deref()).map_err(|e| {
        if e.to_string().contains("profile is busy") {
            fail(4, &e.to_string())
        } else {
            e
        }
    })?;
    let mut cfg = config::Config::load(&profile)?;
    if first && tui {
        setup(&mut profile, &mut cfg)?;
    }
    if let Some(name) = &cli.name {
        wire::nickname(name)?;
        profile.identity.name = name.clone();
    } else {
        profile.identity.name = cfg.nickname.clone();
    }
    let store = Arc::new(Mutex::new(profile));
    match &cli.command {
        Some(
            Action::Status
            | Action::Doctor
            | Action::Identity {
                action: IdentityAction::Show,
            },
        ) => {
            let p = store.lock().unwrap();
            let fingerprint = profile::fingerprint(&p.identity.public);
            if matches!(
                cli.command,
                Some(Action::Identity {
                    action: IdentityAction::Show
                })
            ) {
                return emit(
                    json!({"status":"ok","device_id":p.identity.id,"name":p.identity.name,"public_key":STANDARD.encode(&p.identity.public),"root_fingerprint":fingerprint}),
                    cli.json,
                    format!(
                        "{} · {}\nRoot fingerprint: {}",
                        p.identity.name, p.identity.id, fingerprint
                    ),
                );
            }
            return emit(
                json!({"schema":1,"status":"not_running","client":"NearbyIM Terminal","version":env!("CARGO_PKG_VERSION"),"protocol":"NIM4","device_id":p.identity.id,"name":p.identity.name,"root_fingerprint":fingerprint,"lan":true,"bluetooth":{"supported":false,"reason":"Classic Bluetooth RFCOMM backend not implemented"},"wifi_direct":{"supported":false,"reason":"native P2P adapter not implemented"},"identity_storage":if cfg!(windows){"current-user DPAPI"}else{"0700 profile / 0600 identity"},"history_encrypted_at_rest":false,"tty":interactive,"daemon":false,"listening_configured":cfg.lan_listen}),
                cli.json,
                format!(
                    "NearbyIM Terminal {}\nProtocol: NIM4\nDevice: {} · {}\nRoot fingerprint: {}\nStatus: not_running (no daemon/IPC)\nLAN listening configured: {}\nBluetooth / Wi-Fi Direct: unsupported\nHistory is not encrypted at rest.",
                    env!("CARGO_PKG_VERSION"),
                    p.identity.name,
                    p.identity.id,
                    fingerprint,
                    cfg.lan_listen
                ),
            );
        }
        Some(Action::Config { action }) => {
            let p = store.lock().unwrap();
            match action {
                ConfigAction::List => {}
                ConfigAction::Get { key } => {
                    let v = serde_json::to_value(&cfg)?;
                    return emit(
                        json!({"status":"ok","key":key,"value":v.get(key).context("unknown setting")?}),
                        cli.json,
                        v.get(key).unwrap().to_string(),
                    );
                }
                ConfigAction::Set { key, value } => {
                    cfg.set(key, value).map_err(|e| fail(2, &e.to_string()))?;
                    cfg.save(&p)?;
                }
            }
            return emit(
                json!({"status":"ok","config":cfg}),
                cli.json,
                serde_json::to_string_pretty(&cfg)?,
            );
        }
        Some(Action::Chats { action }) => {
            let p = store.lock().unwrap();
            if let Some(ChatsAction::Show {
                peer,
                before,
                limit,
            }) = action
            {
                let (messages, next_before, has_more) =
                    p.history_page(Some(peer), *before, *limit as usize)?;
                return emit(
                    json!({"status":"ok","peer_id":peer,"messages":messages,"next_before":next_before,"has_more":has_more}),
                    cli.json,
                    messages
                        .iter()
                        .map(|m| {
                            format!(
                                "{} {} [{}] {}",
                                iso(m.time),
                                if m.outgoing { "you" } else { "peer" },
                                m.state,
                                m.body
                            )
                        })
                        .collect::<Vec<_>>()
                        .join("\n"),
                );
            }
            let peers = p.peers()?;
            let mut chats = vec![];
            for peer in peers {
                let messages = p.history(Some(&peer.id))?;
                chats.push(json!({"peer_id":peer.id,"name":peer.name,"last_message":messages.last(),"unconfirmed":messages.iter().filter(|m|m.state=="unconfirmed"||m.state=="pending").count()}));
            }
            return emit(
                json!({"status":"ok","chats":chats}),
                cli.json,
                serde_json::to_string_pretty(&chats)?,
            );
        }
        Some(
            Action::Transfers
            | Action::Files {
                action: FilesAction::List,
            },
        ) => {
            let records = store.lock().unwrap().transfers::<files::Saved>()?;
            return emit(
                json!({"status":"ok","transfers":records}),
                cli.json,
                records
                    .iter()
                    .map(|s| {
                        format!(
                            "{} {} {} {}/{} {}",
                            s.info.id,
                            s.peer,
                            s.state,
                            s.offset,
                            s.info.size,
                            s.path.display()
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
            );
        }
        Some(Action::Files {
            action: FilesAction::Cancel { id },
        }) => {
            let p = store.lock().unwrap();
            let mut s = p
                .transfers::<files::Saved>()?
                .into_iter()
                .find(|s| &s.info.id == id)
                .ok_or_else(|| fail(3, "unknown transfer"))?;
            ensure!(
                !["saved", "received"].contains(&s.state.as_str()),
                "completed transfer cannot be canceled"
            );
            s.state = "canceled".into();
            p.transfer(&s.key(), &s)?;
            return emit(
                json!({"status":"canceled","transfer_id":id}),
                cli.json,
                "Transfer canceled locally; its tombstone prevents later resume.",
            );
        }
        Some(Action::Trust {
            action: TrustAction::List,
        }) => {
            let peers = store.lock().unwrap().peers()?;
            return emit(
                json!({"status":"ok","peers":peers}),
                cli.json,
                peers
                    .iter()
                    .map(|p| {
                        format!(
                            "{} {} remembered={} verified={}",
                            p.id, p.name, p.remembered, p.verified
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
            );
        }
        Some(
            Action::Trust {
                action: TrustAction::Revoke { peer },
            }
            | Action::Identity {
                action: IdentityAction::Verify { peer } | IdentityAction::Forget { peer },
            },
        ) => {
            if !interactive {
                return Err(fail(
                    6,
                    "trust changes require interactive comparison/confirmation",
                ));
            }
            let p = store.lock().unwrap();
            let mut remote = p.peer(peer)?.ok_or_else(|| fail(3, "unknown peer"))?;
            match cli.command.as_ref().unwrap() {
                Action::Identity {
                    action: IdentityAction::Verify { .. },
                } => {
                    let code = profile::safety(&p.identity.public, &STANDARD.decode(&remote.root)?);
                    eprintln!("Compare the full safety code out of band:\n{code}");
                    let input = prompt("Enter the full matching code", "")?;
                    ensure!(
                        input.split_whitespace().collect::<String>() == code,
                        "full safety code does not match"
                    );
                    remote.verified = true;
                    p.save_peer(&remote)?;
                }
                Action::Identity {
                    action: IdentityAction::Forget { .. },
                } => {
                    ensure!(
                        prompt("To forget this identity pin, enter the device UUID", "")?
                            == remote.id,
                        "identity forget canceled"
                    );
                    p.db.execute("DELETE FROM peers WHERE id=?", [peer])?;
                }
                _ => {
                    ensure!(
                        prompt("Revoke remembered consent and verification? yes/no", "no")?
                            == "yes",
                        "trust revoke canceled"
                    );
                    remote.verified = false;
                    remote.remembered = false;
                    p.save_peer(&remote)?;
                }
            }
            return emit(
                json!({"status":"ok","peer_id":peer,"manually_verified":remote.verified,"remembered":remote.remembered}),
                cli.json,
                "Trust record updated explicitly.",
            );
        }
        Some(Action::Peers {
            action: Some(PeersAction::Show { peer }),
        }) => {
            let p = store
                .lock()
                .unwrap()
                .peer(peer)?
                .ok_or_else(|| fail(3, "unknown peer"))?;
            return emit(
                json!({"status":"ok","peer":p}),
                cli.json,
                serde_json::to_string_pretty(&p)?,
            );
        }
        _ => {}
    }
    let directory = cli
        .receive_dir
        .clone()
        .unwrap_or_else(|| cfg.receive_dir.clone());
    let is_scan = matches!(cli.command, Some(Action::Peers { .. }));
    let is_listen = matches!(cli.command, Some(Action::Listen { .. }));
    let listening = !is_scan && (is_listen || cli.listen.is_some() || (tui && cfg.lan_listen));
    let runtime = if listening {
        Runtime::start(
            store.clone(),
            cli.listen.as_deref().unwrap_or(&cfg.listen),
            directory,
        )?
    } else {
        Runtime::client(store.clone(), directory)
    };
    if is_scan {
        let d = Discovery::start(&store.lock().unwrap().identity, 0)?;
        let until = Instant::now() + Duration::from_secs(cli.timeout.min(8));
        let mut found = HashMap::new();
        while Instant::now() < until {
            if let Ok(f) = d.events.recv_timeout(Duration::from_millis(100))
                && (found.len() < 256 || found.contains_key(&f.id))
            {
                found.insert(f.id.clone(), f);
            }
        }
        let mut peers: Vec<_> = found.into_values().collect();
        peers.sort_by(|a, b| a.id.cmp(&b.id));
        return emit(
            json!({"status":"ok","peers":peers}),
            cli.json,
            peers
                .iter()
                .map(|p| format!("{}\t{}\t{}", p.id, p.name, p.endpoint))
                .collect::<Vec<_>>()
                .join("\n"),
        );
    }
    if tui {
        let target = if let Some(Action::Chat { peer }) = &cli.command {
            cli.address.as_ref().or(peer.as_ref()).cloned()
        } else {
            cli.address.clone()
        };
        let no_color = cli.no_color
            || std::env::var_os("NO_COLOR").is_some()
            || cfg.theme == "mono"
            || cfg.color == "none";
        let theme = cli.theme.unwrap_or(match cfg.theme.as_str() {
            "paper" => Theme::Paper,
            "sage" => Theme::Sage,
            "mono" => Theme::Mono,
            "system" => Theme::System,
            _ => Theme::Midnight,
        });
        return ui::run(
            store,
            runtime,
            None,
            ui::Options {
                no_color: no_color || matches!(theme, Theme::Mono),
                ascii: cli.ascii,
                theme,
                cfg,
                target,
            },
        );
    }
    let discovery = if is_listen {
        Some(Discovery::advertise(
            &store.lock().unwrap().identity,
            runtime.port,
        )?)
    } else {
        None
    };
    foreground(&cli, store, runtime, discovery, interactive)
}
fn resolve(
    target: &str,
    address: Option<&str>,
    store: &Arc<Mutex<Profile>>,
    budget: Duration,
) -> Result<(String, Option<String>)> {
    if let Some(addr) = address {
        return Ok((
            addr.into(),
            if wire::uuid(target, true) {
                Some(target.into())
            } else {
                None
            },
        ));
    }
    if !wire::uuid(target, true) {
        return Ok((target.into(), None));
    }
    let discovery = Discovery::start(&store.lock().unwrap().identity, 0)?;
    let until = Instant::now() + budget;
    while Instant::now() < until {
        if let Ok(f) = discovery.events.recv_timeout(Duration::from_millis(100))
            && f.id == target
        {
            return Ok((f.endpoint, Some(target.into())));
        }
    }
    if let Some(p) = store.lock().unwrap().peer(target)?
        && !p.endpoint.is_empty()
    {
        return Ok((p.endpoint, Some(target.into())));
    }
    Err(fail(3, "peer not discovered; use --address HOST:PORT"))
}
fn foreground(
    cli: &Cli,
    store: Arc<Mutex<Profile>>,
    runtime: Runtime,
    _discovery: Option<Discovery>,
    interactive: bool,
) -> Result<()> {
    let start = Instant::now();
    let resume = if let Some(Action::Files {
        action: FilesAction::Resume { id },
    }) = &cli.command
    {
        let s = store
            .lock()
            .unwrap()
            .transfers::<files::Saved>()?
            .into_iter()
            .find(|s| &s.info.id == id)
            .ok_or_else(|| fail(3, "unknown transfer"))?;
        Some((s.peer, id.clone()))
    } else {
        None
    };
    let target = match cli.command.as_ref().unwrap() {
        Action::Connect { peer } | Action::Send { peer, .. } => Some(
            peer.as_deref()
                .or(cli.address.as_deref())
                .ok_or_else(|| fail(2, "specify a peer or --address HOST:PORT"))?,
        ),
        Action::Resume { peer, .. } => Some(peer.as_str()),
        _ => resume.as_ref().map(|r| r.0.as_str()),
    };
    if let Some(t) = target {
        let (endpoint, expected) = resolve(
            t,
            cli.address.as_deref(),
            &store,
            Duration::from_secs(if cli.timeout == 0 {
                4
            } else {
                cli.timeout.min(4)
            }),
        )?;
        runtime
            .connect(&endpoint, expected)
            .map_err(|e| fail(5, &e.to_string()))?;
    }
    if !cli.json {
        emit(
            json!({}),
            false,
            if runtime.port == 0 {
                "Client mode; LAN listening disabled.".into()
            } else {
                format!("Listening on TCP port {}", runtime.port)
            },
        )?;
    }
    let interrupted = Arc::new(AtomicBool::new(false));
    let flag = interrupted.clone();
    ctrlc::set_handler(move || flag.store(true, Ordering::Relaxed))?;
    let (input_tx, input) = mpsc::sync_channel(16);
    if interactive {
        thread::spawn(move || {
            for line in io::stdin().lock().lines() {
                match line {
                    Ok(s) => {
                        if input_tx.send(s).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
    }
    let mut connections = HashMap::<String, SyncSender<Command>>::new();
    let mut selected = None::<String>;
    let mut pending_consent = None::<SyncSender<Consent>>;
    let mut pending_file = None::<SyncSender<bool>>;
    let mut submitted = false;
    loop {
        if interrupted.load(Ordering::Relaxed) {
            return Err(fail(11, "canceled by user; active files are checkpointed"));
        }
        if cli.timeout != 0 && start.elapsed() >= Duration::from_secs(cli.timeout) {
            return Err(fail(
                10,
                "no remote saved receipt before deadline; result unconfirmed",
            ));
        }
        if let Ok(line) = input.try_recv() {
            if let Some(reply) = pending_consent.take() {
                let c = match line.trim() {
                    "once" | "1" => Consent::Once,
                    "remember" | "2" => Consent::Remember,
                    _ => Consent::Reject,
                };
                let _ = reply.send(c);
            } else if let Some(reply) = pending_file.take() {
                let _ = reply.send(line.trim() == "yes");
            } else if let Some(id) = &selected {
                let tx = connections.get(id).context("not connected")?;
                let command = if line == "/quit" {
                    Command::Close
                } else if let Some(p) = line.strip_prefix("/file ") {
                    Command::File(p.into())
                } else if let Some(p) = line.strip_prefix("/pause ") {
                    Command::Pause(p.into())
                } else if let Some(p) = line.strip_prefix("/resume ") {
                    Command::Resume(p.into())
                } else if let Some(p) = line.strip_prefix("/cancel ") {
                    Command::Cancel(p.into())
                } else {
                    wire::text_valid(&line)?;
                    Command::Text(line)
                };
                tx.try_send(command)?;
            }
        }
        let e = match runtime.events.recv_timeout(Duration::from_millis(30)) {
            Ok(e) => e,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(_) => return Err(fail(5, "network worker stopped")),
        };
        match e {
            Event::Consent {
                peer,
                safety,
                reply,
            } => {
                if !interactive {
                    let _ = reply.send(Consent::Reject);
                    if target.is_some() {
                        return Err(fail(
                            6,
                            "unknown/unremembered peer requires interactive consent; use chat or connect",
                        ));
                    }
                } else {
                    eprintln!(
                        "Identity: {} ({})\nSafety code: {}\nEncryption is established; identity is not manually verified.\nType once, remember, or reject:",
                        safe(&peer.name),
                        peer.id,
                        safety
                    );
                    pending_consent = Some(reply);
                }
            }
            Event::FileOfferClosed { .. } => {
                pending_file = None;
            }
            Event::FileOffer { peer, info, reply } => {
                if interactive {
                    eprintln!(
                        "Receive {} ({} bytes) from {}? No overwrite; type yes or no:",
                        safe(&info.name),
                        info.size,
                        peer
                    );
                    pending_file = Some(reply);
                } else {
                    let _ = reply.send(false);
                }
            }
            Event::Connected(peer, tx, outgoing) => {
                if outgoing {
                    selected = Some(peer.id.clone());
                }
                connections.insert(peer.id.clone(), tx);
                if !cli.json {
                    emit(
                        json!({}),
                        false,
                        format!(
                            "Connected to {} · LAN · encrypted · identity {}",
                            peer.name,
                            if peer.verified {
                                "verified"
                            } else {
                                "unverified"
                            }
                        ),
                    )?;
                }
            }
            Event::Ready(id) => {
                if selected.as_ref() == Some(&id) && !submitted {
                    let tx = &connections[&id];
                    match cli.command.as_ref().unwrap() {
                        Action::Send { text, file, .. } => {
                            tx.try_send(if let Some(t) = text {
                                Command::Text(t.clone())
                            } else {
                                Command::File(file.clone().unwrap())
                            })?;
                            submitted = true;
                        }
                        Action::Resume { id, .. }
                        | Action::Files {
                            action: FilesAction::Resume { id },
                        } => {
                            tx.try_send(Command::Resume(id.clone()))?;
                            submitted = true;
                        }
                        _ => {}
                    }
                }
            }
            Event::Message(m) => {
                emit(
                    json!({"status":if m.outgoing{"sending"}else{"received"},"message_id":m.id,"peer_id":m.peer}),
                    cli.json,
                    format!(
                        "{} [{}] {}",
                        if m.outgoing { "you" } else { "peer" },
                        m.state,
                        m.body
                    ),
                )?;
            }
            Event::Saved { peer, id } => {
                emit(
                    json!({"status":"saved","peer_id":peer,"message_id":id,"read":false,"protocol":"NIM4"}),
                    cli.json,
                    format!("Saved by peer: {id} (not a read receipt)"),
                )?;
                if submitted && matches!(cli.command, Some(Action::Send { text: Some(_), .. })) {
                    return Ok(());
                }
            }
            Event::File(s) => {
                emit(
                    json!({"status":s.state,"transfer_id":s.info.id,"peer_id":s.peer,"bytes":s.offset,"total_bytes":s.info.size,"path":s.destination,"protocol":"NIM4"}),
                    cli.json,
                    format!(
                        "{} · {} / {} bytes · {}",
                        s.info.id, s.offset, s.info.size, s.state
                    ),
                )?;
                let resuming = match cli.command.as_ref() {
                    Some(
                        Action::Resume { id, .. }
                        | Action::Files {
                            action: FilesAction::Resume { id },
                        },
                    ) => Some(id),
                    _ => None,
                };
                if submitted
                    && ((resuming == Some(&s.info.id)
                        && matches!(s.state.as_str(), "saved" | "received"))
                        || (resuming.is_none() && s.outgoing && s.state == "saved"))
                {
                    return Ok(());
                }
                if submitted && s.state == "failed" {
                    return Err(fail(5, "peer file transfer failed; no saved receipt"));
                }
                if submitted && s.state == "canceled" {
                    return Err(fail(11, "file rejected or canceled"));
                }
            }
            Event::Disconnected(id, reason) => {
                connections.remove(&id);
                if !cli.json {
                    emit(json!({}), false, format!("Disconnected: {reason}"))?;
                }
                if selected.as_ref() == Some(&id) {
                    if submitted {
                        return Err(fail(
                            5,
                            "disconnected before saved receipt; checkpoints remain resumable",
                        ));
                    }
                    return Ok(());
                }
            }
            Event::Error(e) => {
                let code = if e.contains("IDENTITY CHANGED") || e.contains("persistent root proof")
                {
                    7
                } else if e.contains("handshake")
                    || e.contains("authentication")
                    || e.contains("NIM4")
                {
                    8
                } else if e.contains("permission") || e.contains("disk") || e.contains("source") {
                    9
                } else {
                    5
                };
                if target.is_some() {
                    return Err(fail(code, &e));
                }
                if cli.verbose {
                    eprintln!("{}", safe(&e));
                }
            }
        }
    }
}
fn iso(millis: u64) -> String {
    // Civil-from-days conversion (Gregorian calendar); timestamps are presentation only.
    let seconds = millis / 1000;
    let z = (seconds / 86400) as i64 + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let mut year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    year += (month <= 2) as i64;
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        seconds / 3600 % 24,
        seconds / 60 % 60,
        seconds % 60,
        millis % 1000
    )
}
fn main() {
    let json_mode = std::env::args().any(|a| a == "--json");
    let cli = match Cli::try_parse() {
        Ok(c) => c,
        Err(e) => {
            if !json_mode
                || matches!(
                    e.kind(),
                    clap::error::ErrorKind::DisplayHelp | clap::error::ErrorKind::DisplayVersion
                )
            {
                e.exit();
            }
            let value = json!({"schema":1,"ok":false,"status":"error","exit_code":2,"error":{"code":"INVALID_INPUT","message":e.to_string()}});
            println!("{value}");
            std::process::exit(2);
        }
    };
    let operation = match &cli.command {
        Some(Action::Send { file: Some(_), .. }) => "send_file",
        Some(Action::Send { .. }) => "send_text",
        Some(
            Action::Resume { .. }
            | Action::Files {
                action: FilesAction::Resume { .. },
            },
        ) => "resume_file",
        _ => "query",
    };
    let result = run(cli);
    let mut output = OUTPUT.with(|o| o.borrow().clone());
    if !output.is_object() {
        output = json!({});
    }
    let code = if let Err(e) = &result {
        let code = e.downcast_ref::<Exit>().map_or_else(
            || {
                if e.downcast_ref::<std::io::Error>()
                    .is_some_and(|e| e.kind() == std::io::ErrorKind::AddrInUse)
                {
                    4
                } else {
                    9
                }
            },
            |e| e.code,
        );
        let error_code = match code {
            2 => "INVALID_INPUT",
            3 => "NOT_FOUND",
            4 => "PROFILE_BUSY",
            5 => "NETWORK_UNREACHABLE",
            6 => "INTERACTION_REQUIRED",
            7 => "IDENTITY_CHANGED",
            8 => "PROTOCOL_ERROR",
            9 => "LOCAL_IO",
            10 => "ACK_TIMEOUT",
            11 => "CANCELED",
            _ => "ERROR",
        };
        let message = format!("{e:#}");
        output["status"] = json!(
            if operation.starts_with("send") || operation == "resume_file" {
                "unconfirmed"
            } else {
                "error"
            }
        );
        output["error"] = json!({"code":error_code,"message":message});
        if !json_mode {
            eprintln!("nearbyim: {}", safe(&message));
        }
        code
    } else {
        0
    };
    if json_mode {
        output["schema"] = json!(1);
        output["ok"] = json!(code == 0);
        output["exit_code"] = json!(code);
        output["operation"] = json!(operation);
        output["timestamp"] = json!(iso(profile::now()));
        if output.get("status").is_none() {
            output["status"] = json!("ok");
        }
        println!("{output}");
    }
    if code != 0 {
        std::process::exit(code);
    }
}
#[cfg(test)]
mod interop;

use anyhow::{Context, Result, bail};
use nearby_mesh::store::Store;
use serde_json::{Value, json};
use std::{
    io::{self, BufRead, Read, Write},
    path::PathBuf,
    sync::mpsc,
    thread,
};
fn emit(value: Value) {
    println!("{value}");
}
fn command_rx() -> mpsc::Receiver<Value> {
    let (tx, rx) = mpsc::sync_channel(16);
    thread::spawn(move || {
        let stdin = io::stdin();
        let mut input = stdin.lock();
        loop {
            let mut bytes = Vec::new();
            match Read::by_ref(&mut input)
                .take(16385)
                .read_until(b'\n', &mut bytes)
            {
                Ok(0) => {
                    let _ = tx.send(json!({"cmd":"quit"}));
                    break;
                }
                Ok(_) if bytes.len() > 16384 || !bytes.ends_with(b"\n") => {
                    let _ = tx.send(json!({"cmd":"control_error"}));
                    break;
                }
                Ok(_) => {
                    let value = serde_json::from_slice(&bytes)
                        .unwrap_or_else(|_| json!({"cmd":"control_error"}));
                    if tx.send(value).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    rx
}
fn main() -> Result<()> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    if args.is_empty() || args[0] == "--help" {
        writeln!(
            io::stdout(),
            "mesh-lab keygen --state DIR\nmesh-lab run --state DIR --listen IP:PORT [--peer IP:PORT] [--relay] [--discover]\nstdin NDJSON: send(text,ttl=600,hops=3), neighbors, status, diagnostics, relay(enabled), quit\nPublic plaintext; signed pseudonyms do not establish NIM4 trust. Relay defaults OFF."
        )?;
        return Ok(());
    }
    if args[0] == "discovery-worker" {
        return nearby_mesh::discovery::worker(
            args.get(1).context("missing discovery port")?.parse()?,
        );
    }
    let mut state = None;
    let mut listen = "127.0.0.1:39770".parse()?;
    let mut peers = Vec::new();
    let mut relay = false;
    let mut discover = false;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--relay" => {
                relay = true;
                i += 1;
                continue;
            }
            "--discover" => {
                discover = true;
                i += 1;
                continue;
            }
            "--state" => state = Some(PathBuf::from(args.get(i + 1).context("missing state")?)),
            "--listen" => listen = args.get(i + 1).context("missing listen address")?.parse()?,
            "--peer" => {
                if peers.len() >= 16 {
                    bail!("candidate_limit");
                }
                peers.push(args.get(i + 1).context("missing peer address")?.parse()?);
            }
            _ => bail!("unknown argument"),
        }
        i += 2;
    }
    let state = state.context("--state is required")?;
    match args[0].as_str() {
        "keygen" => {
            let store = Store::open(&state)?;
            emit(json!({"key":store.identity()?.key()}));
            Ok(())
        }
        "run" => nearby_mesh::runtime::run(
            nearby_mesh::runtime::RuntimeConfig {
                state,
                listen,
                peers,
                relay,
                discover,
            },
            command_rx(),
            emit,
            std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        ),
        _ => bail!("unknown command"),
    }
}

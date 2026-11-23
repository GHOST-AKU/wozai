//! Bounded in-process host; neither stdin/stdout nor a child process is required.
use crate::runtime::{self, RuntimeConfig};
use anyhow::{Result, bail};
use serde_json::Value;
use std::{
    path::Path,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicI32, Ordering},
        mpsc::{self, Receiver, SyncSender},
    },
    thread::{self, JoinHandle},
    time::Duration,
};

pub const MAX_CONTROL_BYTES: usize = 16384;
pub const MAX_EVENT_BYTES: usize = 16384;
pub struct Engine {
    commands: SyncSender<Value>,
    events: Mutex<(Option<Value>, Receiver<Value>)>,
    stop: Arc<AtomicBool>,
    state: Arc<AtomicI32>,
    worker: Mutex<Option<JoinHandle<()>>>,
}
impl Engine {
    /// Starts only a private loopback listener. Relay always starts disabled.
    pub fn start(directory: &Path) -> Result<Self> {
        let (commands, input) = mpsc::sync_channel(16);
        let (output, events) = mpsc::sync_channel(128);
        let stop = Arc::new(AtomicBool::new(false));
        let state = Arc::new(AtomicI32::new(1));
        let worker_stop = stop.clone();
        let worker_state = state.clone();
        let config = RuntimeConfig {
            state: directory.into(),
            listen: "127.0.0.1:0".parse()?,
            peers: vec![],
            relay: false,
            discover: false,
        };
        let worker = thread::Builder::new()
            .name("nearby-mesh".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    runtime::run(
                        config,
                        input,
                        |value| {
                            if serde_json::to_vec(&value)
                                .map_or(true, |v| v.len() > MAX_EVENT_BYTES)
                                || output.try_send(value).is_err()
                            {
                                worker_state.store(-2, Ordering::Release);
                                worker_stop.store(true, Ordering::Release);
                            }
                        },
                        worker_stop.clone(),
                    )
                }));
                let finished = if matches!(result, Ok(Ok(()))) { 0 } else { -1 };
                let _ =
                    worker_state.compare_exchange(1, finished, Ordering::AcqRel, Ordering::Acquire);
            })?;
        let first = events.recv_timeout(Duration::from_secs(10));
        match first {
            Ok(event) if event["event"] == "ready" => Ok(Self {
                commands,
                events: Mutex::new((Some(event), events)),
                stop,
                state,
                worker: Mutex::new(Some(worker)),
            }),
            _ => {
                stop.store(true, Ordering::Release);
                let _ = worker.join();
                bail!("mesh_start_failed");
            }
        }
    }
    pub fn command(&self, bytes: &[u8]) -> Result<()> {
        if self.state() != 1 {
            bail!("mesh_stopped");
        }
        if bytes.is_empty() || bytes.len() > MAX_CONTROL_BYTES {
            bail!("control_bound");
        }
        let value: Value = serde_json::from_slice(bytes)?;
        if !matches!(
            value["cmd"].as_str(),
            Some("send" | "relay" | "neighbors" | "status" | "diagnostics")
        ) {
            bail!("control_command");
        }
        self.commands
            .try_send(value)
            .map_err(|_| anyhow::anyhow!("control_queue_full"))
    }
    pub fn event(&self, timeout: Duration) -> Result<Option<Value>> {
        let mut guard = self
            .events
            .lock()
            .map_err(|_| anyhow::anyhow!("event_lock"))?;
        if let Some(first) = guard.0.take() {
            return Ok(Some(first));
        }
        match guard
            .1
            .recv_timeout(timeout.min(Duration::from_millis(250)))
        {
            Ok(value) => Ok(Some(value)),
            Err(mpsc::RecvTimeoutError::Timeout | mpsc::RecvTimeoutError::Disconnected) => Ok(None),
        }
    }
    pub fn state(&self) -> i32 {
        self.state.load(Ordering::Acquire)
    }
    pub fn close(&self) {
        self.stop.store(true, Ordering::Release);
        if let Ok(mut worker) = self.worker.lock() {
            if let Some(worker) = worker.take() {
                let _ = worker.join();
            }
        }
    }
}
impl Drop for Engine {
    fn drop(&mut self) {
        self.close();
    }
}

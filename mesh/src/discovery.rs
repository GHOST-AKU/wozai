//! Optional LAN DNS-SD runs in a separate Linux process with an address-space limit.
//! Discovery only produces untrusted addresses; peer possession is checked by the handshake.
use anyhow::{Result, bail};
use std::{
    io::{BufRead, BufReader, Read},
    net::SocketAddr,
    process::{Child, Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, Instant},
};

pub struct Discovery {
    child: Child,
    events: Receiver<SocketAddr>,
}
impl Discovery {
    pub fn start(port: u16) -> Result<Self> {
        if !cfg!(target_os = "linux") {
            bail!(
                "bounded discovery currently requires Linux; use native discovery candidates on other platforms"
            );
        }
        let mut child = Command::new(std::env::current_exe()?)
            .arg("discovery-worker")
            .arg(port.to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()?;
        let output = child
            .stdout
            .take()
            .ok_or_else(|| anyhow::anyhow!("discovery stdout"))?;
        let (sender, events) = mpsc::sync_channel(16);
        thread::spawn(move || {
            let mut reader = BufReader::new(output);
            loop {
                let mut bytes = Vec::new();
                match std::io::Read::by_ref(&mut reader)
                    .take(129)
                    .read_until(b'\n', &mut bytes)
                {
                    Ok(0) => break,
                    Ok(_) if bytes.len() <= 128 => {
                        if let Ok(s) = std::str::from_utf8(&bytes) {
                            if let Ok(address) = s.trim().parse() {
                                if sender.send(address).is_err() {
                                    break;
                                }
                            }
                        }
                    }
                    _ => break,
                }
            }
        });
        Ok(Self { child, events })
    }
    pub fn poll(&self) -> Vec<SocketAddr> {
        self.events.try_iter().take(16).collect()
    }
    pub fn ended(&mut self) -> bool {
        self.child.try_wait().ok().flatten().is_some()
    }
}
impl Drop for Discovery {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

pub fn worker(port: u16) -> Result<()> {
    if port == 0 {
        bail!("discovery_port");
    }
    #[cfg(target_os = "linux")]
    {
        // mdns-sd has dynamic caches; the OS limit isolates that upstream allocation surface.
        let limit = libc::rlimit {
            rlim_cur: 96 * 1024 * 1024,
            rlim_max: 96 * 1024 * 1024,
        };
        if unsafe { libc::setrlimit(libc::RLIMIT_AS, &limit) } != 0 {
            bail!("discovery memory limit unavailable");
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        bail!("bounded discovery requires Linux");
    }
    let daemon = mdns_sd::ServiceDaemon::new()?;
    let instance = format!("mesh-{}", crate::protocol::nonce());
    let service_type = "_nearby-mesh._tcp.local.";
    let service = mdns_sd::ServiceInfo::new(
        service_type,
        &instance,
        &format!("{instance}.local."),
        "",
        port,
        &[("v", "1")][..],
    )?
    .enable_addr_auto();
    let own = service.get_fullname().to_string();
    daemon.register(service)?;
    let events = daemon.browse(service_type)?;
    let start = Instant::now();
    let mut seen = std::collections::BTreeSet::new();
    let interfaces = if_addrs::get_if_addrs()?;
    while start.elapsed() < Duration::from_secs(60) {
        if let Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) =
            events.recv_timeout(Duration::from_millis(200))
        {
            if info.get_fullname() == own || info.get_property_val_str("v") != Some("1") {
                continue;
            }
            for ip in info.get_addresses_v4() {
                let address = SocketAddr::new(ip.into(), info.get_port());
                if interfaces.iter().any(|interface| match &interface.addr {
                    if_addrs::IfAddr::V4(v4) => on_link(ip, v4.ip, v4.netmask),
                    _ => false,
                }) && info.get_port() != 0
                    && seen.len() < 16
                    && seen.insert(address)
                {
                    println!("{address}");
                }
            }
        }
    }
    let _ = daemon.unregister(&own);
    let _ = daemon.shutdown();
    Ok(())
}

pub fn on_link(
    candidate: std::net::Ipv4Addr,
    local: std::net::Ipv4Addr,
    mask: std::net::Ipv4Addr,
) -> bool {
    let ip = u32::from(candidate);
    let host = u32::from(local);
    let mask = u32::from(mask);
    if candidate.is_loopback()
        || candidate.is_unspecified()
        || candidate.is_multicast()
        || candidate == local
        || mask == 0
    {
        return false;
    }
    let subnet = host & mask;
    ip & mask == subnet && ip != subnet && ip != (subnet | !mask)
}

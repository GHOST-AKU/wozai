//! DNS-SD advertisements are untrusted routing hints, never identity proofs.
use crate::profile::Identity;
use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::Serialize;
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver},
    },
    thread,
    time::Duration,
};
const TYPE: &str = "_nearbyim._tcp.local.";
#[derive(Clone, Debug, Serialize)]
pub struct Found {
    pub id: String,
    pub name: String,
    pub endpoint: String,
}
pub struct Discovery {
    pub events: Receiver<Found>,
    daemon: ServiceDaemon,
    stop: Arc<AtomicBool>,
    fullname: String,
}
impl Discovery {
    pub fn start(identity: &Identity, port: u16) -> Result<Self> {
        Self::create(identity, port, true)
    }
    pub fn advertise(identity: &Identity, port: u16) -> Result<Self> {
        Self::create(identity, port, false)
    }
    fn create(identity: &Identity, port: u16, scan: bool) -> Result<Self> {
        let daemon = ServiceDaemon::new()?;
        let instance = format!("nearby-terminal-{}", &identity.id[..8]);
        let host = format!("nearbyim-{}.local.", &identity.id[..8]);
        let mut properties = HashMap::new();
        properties.insert("id".to_string(), identity.id.clone());
        properties.insert("name".to_string(), identity.name.clone());
        let service =
            ServiceInfo::new(TYPE, &instance, &host, "", port, properties)?.enable_addr_auto();
        let fullname = service.get_fullname().to_string();
        if port != 0 {
            daemon.register(service)?;
        }
        let (tx, events) = mpsc::sync_channel(128);
        let stop = Arc::new(AtomicBool::new(false));
        if !scan {
            return Ok(Self {
                events,
                daemon,
                stop,
                fullname,
            });
        }
        let browse = daemon.browse(TYPE)?;
        let end = stop.clone();
        let own = identity.id.clone();
        thread::spawn(move || {
            while !end.load(Ordering::Relaxed) {
                if let Ok(ServiceEvent::ServiceResolved(info)) =
                    browse.recv_timeout(Duration::from_millis(200))
                {
                    let Some(id) = info.get_property_val_str("id") else {
                        continue;
                    };
                    if id == own || !crate::wire::uuid(id, true) || info.get_port() == 0 {
                        continue;
                    }
                    let name = info.get_property_val_str("name").unwrap_or("NearbyIM");
                    if crate::wire::nickname(name).is_err() {
                        continue;
                    }
                    for address in info.get_addresses_v4() {
                        if !(address.is_private()
                            || address.is_link_local()
                            || address.is_loopback())
                        {
                            continue;
                        }
                        if let Err(mpsc::TrySendError::Disconnected(_)) = tx.try_send(Found {
                            id: id.into(),
                            name: name.into(),
                            endpoint: format!("{address}:{}", info.get_port()),
                        }) {
                            return;
                        }
                    }
                }
            }
        });
        Ok(Self {
            events,
            daemon,
            stop,
            fullname,
        })
    }
}
impl Drop for Discovery {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

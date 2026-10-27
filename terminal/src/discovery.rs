//! DNS-SD advertisements are untrusted routing hints, never identity proofs.
use crate::profile::Identity;
use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::Serialize;
use std::{
    cmp::Reverse,
    collections::HashMap,
    net::Ipv4Addr,
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
                    let hosts: Vec<_> = info.get_addresses_v4().into_iter().collect();
                    if let Some(address) = preferred_host(&hosts, &local_subnets())
                        && let Err(mpsc::TrySendError::Disconnected(_)) = tx.try_send(Found {
                            id: id.into(),
                            name: name.into(),
                            endpoint: format!("{address}:{}", info.get_port()),
                        })
                    {
                        return;
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

fn local_subnets() -> Vec<(Ipv4Addr, Ipv4Addr)> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|interface| interface.is_oper_up() && ordinary_name(&interface.name))
        .filter_map(|interface| match interface.addr {
            if_addrs::IfAddr::V4(address)
                if address.ip.is_private() || address.ip.is_link_local() =>
            {
                Some((address.ip, address.netmask))
            }
            _ => None,
        })
        .collect()
}

fn ordinary_name(name: &str) -> bool {
    let name = name.to_lowercase();
    name != "lo"
        && name != "lo0"
        && !["p2p", "tun", "utun", "tap", "wg", "tailscale", "zt", "vpn"]
            .iter()
            .any(|prefix| name.starts_with(prefix))
        && ![
            "wireguard",
            "openvpn",
            "wi-fi direct",
            "wifi direct",
            "wifi-direct",
            "wifi_direct",
        ]
        .iter()
        .any(|part| name.contains(part))
}

fn preferred_host(hosts: &[Ipv4Addr], subnets: &[(Ipv4Addr, Ipv4Addr)]) -> Option<Ipv4Addr> {
    // Emit one stable endpoint so TUI updates cannot overwrite a reachable LAN with another NIC.
    hosts
        .iter()
        .copied()
        .filter(|host| host.is_private() || host.is_link_local())
        .min_by_key(|host| {
            let prefix = subnets
                .iter()
                .filter_map(|(local, mask)| {
                    let mask = u32::from(*mask);
                    if mask != 0 && u32::from(*local) & mask == u32::from(*host) & mask {
                        Some(mask.leading_ones())
                    } else {
                        None
                    }
                })
                .max()
                .unwrap_or(0);
            (Reverse(prefix), *host)
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ip(value: &str) -> Ipv4Addr {
        value.parse().unwrap()
    }

    #[test]
    fn multihomed_peer_prefers_on_link_ethernet_over_unrelated_address() {
        let hosts = [ip("10.77.0.2"), ip("192.168.10.20")];
        let lan = [(ip("192.168.10.5"), ip("255.255.255.0"))];
        assert_eq!(preferred_host(&hosts, &lan), Some(ip("192.168.10.20")));
        assert_eq!(
            preferred_host(&[hosts[1], hosts[0]], &lan),
            Some(ip("192.168.10.20"))
        );
    }

    #[test]
    fn longest_subnet_wins_and_answer_order_is_stable() {
        let hosts = [ip("10.2.0.20"), ip("192.168.10.20"), ip("192.168.10.10")];
        let lan = [
            (ip("10.0.0.5"), ip("255.0.0.0")),
            (ip("192.168.10.5"), ip("255.255.255.0")),
        ];
        assert_eq!(preferred_host(&hosts, &lan), Some(ip("192.168.10.10")));
        assert_eq!(
            preferred_host(&[hosts[2], hosts[1], hosts[0]], &lan),
            Some(ip("192.168.10.10"))
        );
    }

    #[test]
    fn default_route_is_not_proof_of_lan_and_routed_fallback_remains_local() {
        let hosts = [ip("192.168.10.20"), ip("10.77.0.2")];
        let default = [(ip("192.168.10.5"), ip("0.0.0.0"))];
        assert_eq!(preferred_host(&hosts, &default), Some(ip("10.77.0.2")));
        assert_eq!(
            preferred_host(
                &[
                    ip("8.8.8.8"),
                    ip("127.0.0.1"),
                    ip("0.0.0.0"),
                    ip("224.0.0.251")
                ],
                &[]
            ),
            None
        );
        assert_eq!(
            preferred_host(&[ip("169.254.10.2")], &[]),
            Some(ip("169.254.10.2"))
        );
    }

    #[test]
    fn ordinary_ethernet_and_wifi_are_eligible_for_route_preference() {
        for name in [
            "Ethernet",
            "Local Area Connection",
            "enp3s0",
            "eth0",
            "Wi-Fi",
            "wlan0",
            "vEthernet (LAN)",
        ] {
            assert!(ordinary_name(name), "{name}");
        }
        for name in [
            "lo",
            "tun0",
            "utun1",
            "wg0",
            "tailscale0",
            "OpenVPN TAP Adapter",
            "WireGuard Tunnel",
            "Microsoft Wi-Fi Direct Virtual Adapter",
        ] {
            assert!(!ordinary_name(name), "{name}");
        }
    }

    #[test]
    #[ignore = "requires a private LAN interface and multicast; included by test-interop.sh"]
    fn real_lan_discovery_endpoints_connect_in_both_directions() {
        use std::{
            io::{Read, Write},
            net::{SocketAddr, TcpListener, TcpStream},
            time::Instant,
        };
        let root = tempfile::tempdir().unwrap();
        let a = crate::profile::Profile::open(root.path().join("a"), Some("Ethernet A")).unwrap();
        let b = crate::profile::Profile::open(root.path().join("b"), Some("Ethernet B")).unwrap();
        let listener_a = TcpListener::bind("0.0.0.0:0").unwrap();
        let listener_b = TcpListener::bind("0.0.0.0:0").unwrap();
        let first = Discovery::start(&a.identity, listener_a.local_addr().unwrap().port()).unwrap();
        let second =
            Discovery::start(&b.identity, listener_b.local_addr().unwrap().port()).unwrap();
        let find = |events: &Receiver<Found>, expected: &str| {
            let deadline = Instant::now() + Duration::from_secs(25);
            loop {
                assert!(
                    Instant::now() < deadline,
                    "mDNS did not discover the other LAN endpoint"
                );
                if let Ok(peer) = events.recv_timeout(Duration::from_millis(200))
                    && peer.id == expected
                {
                    break peer;
                }
            }
        };
        let found_b = find(&first.events, &b.identity.id);
        let found_a = find(&second.events, &a.identity.id);
        for (peer, listener) in [(found_b, listener_b), (found_a, listener_a)] {
            let address: SocketAddr = peer.endpoint.parse().unwrap();
            assert!(!address.ip().is_loopback());
            let mut outgoing =
                TcpStream::connect_timeout(&address, Duration::from_secs(5)).unwrap();
            let (mut incoming, _) = listener.accept().unwrap();
            incoming
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            outgoing.write_all(b"LAN\0route").unwrap();
            let mut bytes = [0; 9];
            incoming.read_exact(&mut bytes).unwrap();
            assert_eq!(&bytes, b"LAN\0route");
        }
    }
}

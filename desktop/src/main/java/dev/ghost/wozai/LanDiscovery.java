package dev.ghost.wozai;

import dev.ghost.nearbyim.core.LocalEndpoint;
import javax.jmdns.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Android-compatible DNS-SD. Advertisements are routing hints, never trusted keys. */
public final class LanDiscovery implements AutoCloseable {
    public record Nearby(String service, String id, String name, String endpoint) {
        public String toString() { return name + " · " + endpoint; }
    }
    public interface Listener { void found(Nearby peer); void lost(String service); void failed(); }
    private static final String TYPE = "_nearbyim._tcp.local.";
    private final AtomicInteger epoch = new AtomicInteger();
    private final List<JmDNS> instances = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "wozai-mdns"); t.setDaemon(true); return t; });
    public void start(String ownId, String nickname, int port, Listener listener) {
        int run = epoch.incrementAndGet();
        worker.execute(() -> {
            closeInstances(); boolean any = false;
            try {
                List<InetAddress> addresses = new ArrayList<>();
                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                    for (InetAddress address : Collections.list(iface.getInetAddresses()))
                        if (address instanceof Inet4Address && LocalEndpoint.isLocal(address)) addresses.add(address);
                }
                for (InetAddress address : addresses.stream().limit(4).toList()) {
                    if (epoch.get() != run) return;
                    JmDNS dns = null;
                    try {
                        dns = JmDNS.create(address, "WoZai-" + ownId.substring(0, 8));
                        if (epoch.get() != run) { dns.close(); return; }
                        synchronized (instances) { instances.add(dns); }
                        final JmDNS instance = dns;
                        dns.addServiceListener(TYPE, new ServiceListener() {
                            public void serviceAdded(ServiceEvent event) { if (epoch.get() == run) instance.requestServiceInfo(TYPE, event.getName(), true, 1500); }
                            public void serviceRemoved(ServiceEvent event) { if (epoch.get() == run) listener.lost(address.getHostAddress() + ":" + event.getName()); }
                            public void serviceResolved(ServiceEvent event) {
                                if (epoch.get() != run) return;
                                ServiceInfo info = event.getInfo();
                                try {
                                    String id = info.getPropertyString("id");
                                    if (id == null || !UUID.fromString(id).toString().equals(id) || id.equals(ownId)) return;
                                    String name = info.getPropertyString("name");
                                    if (name == null || name.isBlank()) name = info.getName();
                                    name = name.codePoints().filter(c -> !Character.isISOControl(c)).limit(32).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
                                    for (InetAddress host : info.getInet4Addresses()) {
                                        if (!LocalEndpoint.isLocal(host) || info.getPort() < 1) continue;
                                        listener.found(new Nearby(address.getHostAddress() + ":" + event.getName(), id, name, DesktopClient.endpoint(host, info.getPort())));
                                        break;
                                    }
                                } catch (IllegalArgumentException ignored) { }
                            }
                        });
                        Map<String, String> attributes = Map.of("id", ownId, "name", nickname);
                        dns.registerService(ServiceInfo.create(TYPE, "nearby-" + ownId.substring(0, 8), port, 0, 0, attributes));
                        any = true;
                    } catch (IOException e) { if (dns != null) try { dns.close(); } catch (IOException ignored) { } }
                }
            } catch (SocketException e) { }
            if (!any && epoch.get() == run) listener.failed();
        });
    }
    public void stop() { epoch.incrementAndGet(); if (!worker.isShutdown()) worker.execute(this::closeInstances); }
    private void closeInstances() {
        List<JmDNS> previous;
        synchronized (instances) { previous = new ArrayList<>(instances); instances.clear(); }
        for (JmDNS dns : previous) try { dns.close(); } catch (IOException ignored) { }
    }
    public void close() { epoch.incrementAndGet(); worker.execute(this::closeInstances); worker.shutdown(); }
}

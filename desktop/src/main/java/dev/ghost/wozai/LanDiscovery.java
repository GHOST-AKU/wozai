package dev.ghost.wozai;

import dev.ghost.nearbyim.core.LocalEndpoint;
import javax.jmdns.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Android-compatible DNS-SD. Advertisements are routing hints, never trusted keys. */
public final class LanDiscovery implements AutoCloseable {
    public record Nearby(String service, String id, String name, String endpoint) {
        public String toString() { return name + " · " + endpoint; }
    }
    public interface Listener {
        void found(Nearby peer);
        void lost(String service);
        void failed();
        default void networkChanged() { }
    }
    private static final String TYPE = "_nearbyim._tcp.local.";
    private final AtomicInteger epoch = new AtomicInteger();
    // Registration ownership stays on the worker; callbacks also check the per-interface lifetime.
    private final Map<InetAddress, Registration> instances = new LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "wozai-mdns"); t.setDaemon(true); return t; });
    private final Supplier<List<InetAddress>> addressSource;
    private ScheduledFuture<?> monitor;
    private volatile boolean closed;
    private boolean unavailable;
    private final AtomicInteger responses = new AtomicInteger(), resolved = new AtomicInteger();
    private volatile String state = "stopped", lastError = "";
    private volatile List<InetAddress> eligible = List.of(), registered = List.of();

    public LanDiscovery() { this(LanDiscovery::addresses); }
    LanDiscovery(Supplier<List<InetAddress>> addressSource) { this.addressSource = addressSource; }

    static List<InetAddress> addresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!DesktopFileRoutes.ordinary(iface)) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses()))
                    if (address instanceof Inet4Address && LocalEndpoint.isLocal(address)) result.add(address);
            }
        } catch (SocketException unavailable) { throw new UncheckedIOException(unavailable); }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    public void start(String ownId, String nickname, int port, Listener listener) {
        if (closed) return;
        int run = epoch.incrementAndGet();
        execute(() -> {
            closeInstances(); unavailable = false;
            if (epoch.get() != run || closed) return;
            state = "starting"; lastError = ""; responses.set(0); resolved.set(0);
            reconcile(run, ownId, nickname, port, listener);
            if (epoch.get() == run && !closed)
                monitor = worker.scheduleWithFixedDelay(() -> reconcile(run, ownId, nickname, port, listener), 2, 2, TimeUnit.SECONDS);
        });
    }

    private void reconcile(int run, String ownId, String nickname, int port, Listener listener) {
        if (epoch.get() != run || closed) return;
        Set<InetAddress> current = new LinkedHashSet<>();
        try {
            for (InetAddress address : addressSource.get())
                if (address instanceof Inet4Address && LocalEndpoint.isLocal(address)) current.add(address);
        } catch (RuntimeException failure) {
            lastError = error(failure);
            if (instances.isEmpty()) {
                state = "interface_error";
                if (!unavailable) listener.failed();
                unavailable = true;
            }
            return;
        }
        boolean changed = !new HashSet<>(eligible).equals(current);
        eligible = List.copyOf(current);
        for (InetAddress address : new ArrayList<>(instances.keySet())) {
            if (!current.contains(address)) { instances.remove(address).retire(true); changed = true; }
        }
        for (InetAddress address : current) {
            if (epoch.get() != run || closed) return;
            if (instances.containsKey(address)) continue;
            Registration registration = null;
            try {
                registration = new Registration(address, run, listener, JmDNS.create(address, "NearbyIM-" + ownId.substring(0, 8)));
                if (epoch.get() != run || closed) { registration.retire(false); return; }
                registration.dns.addServiceListener(TYPE, registration.events(ownId));
                registration.dns.registerService(ServiceInfo.create(TYPE, "nearby-" + ownId.substring(0, 8), port, 0, 0, Map.of("id", ownId, "name", nickname)));
                instances.put(address, registration); changed = true;
            } catch (IOException | RuntimeException failure) {
                lastError = address.getHostAddress() + ":" + error(failure);
                if (registration != null) registration.retire(true);
                // Other interfaces stay alive; failed ones are retried on the next topology poll.
            }
        }
        if (epoch.get() != run || closed) return;
        registered = List.copyOf(instances.keySet());
        state = instances.isEmpty() ? current.isEmpty() ? "no_interfaces" : "registration_failed" : "running";
        if (changed) listener.networkChanged();
        if (instances.isEmpty() && !unavailable) listener.failed();
        unavailable = instances.isEmpty();
    }

    static InetAddress preferredHost(InetAddress local, InetAddress[] hosts) {
        List<InterfaceAddress> links = List.of();
        try {
            NetworkInterface iface = NetworkInterface.getByInetAddress(local);
            if (iface != null) links = iface.getInterfaceAddresses();
        } catch (SocketException unavailable) { }
        InetAddress fallback = null;
        for (InetAddress host : hosts) {
            if (!(host instanceof Inet4Address) || !LocalEndpoint.isLocal(host)) continue;
            if (fallback == null) fallback = host;
            for (InterfaceAddress link : links)
                if (local.equals(link.getAddress()) && DesktopFileRoutes.sameNetwork(local, link.getNetworkPrefixLength(), host)) return host;
        }
        // Reflected mDNS can legitimately describe a routed private subnet.
        return fallback;
    }

    private final class Registration {
        final InetAddress address;
        final int run;
        final Listener listener;
        final JmDNS dns;
        final Set<String> visible = new HashSet<>();
        boolean active = true;
        Registration(InetAddress address, int run, Listener listener, JmDNS dns) {
            this.address = address; this.run = run; this.listener = listener; this.dns = dns;
        }
        boolean current() { return active && epoch.get() == run && !closed; }
        ServiceListener events(String ownId) {
            return new ServiceListener() {
                public void serviceAdded(ServiceEvent event) {
                    synchronized (Registration.this) {
                        if (current()) { responses.incrementAndGet(); dns.requestServiceInfo(TYPE, event.getName(), true, 1500); }
                    }
                }
                public void serviceRemoved(ServiceEvent event) {
                    synchronized (Registration.this) {
                        String service = address.getHostAddress() + ":" + event.getName();
                        if (current() && visible.remove(service)) listener.lost(service);
                    }
                }
                public void serviceResolved(ServiceEvent event) {
                    synchronized (Registration.this) {
                        if (!current()) return;
                        ServiceInfo info = event.getInfo();
                        try {
                            String id = info.getPropertyString("id");
                            if (id == null || !UUID.fromString(id).toString().equals(id) || id.equals(ownId) || info.getPort() < 1 || info.getPort() > 65535) return;
                            InetAddress host = preferredHost(address, info.getInet4Addresses());
                            if (host == null) return;
                            String name = info.getPropertyString("name");
                            if (name == null || name.isBlank()) name = info.getName();
                            name = name.codePoints().filter(c -> !Character.isISOControl(c)).limit(32).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
                            String service = address.getHostAddress() + ":" + event.getName();
                            if (visible.size() >= 128 && !visible.contains(service)) return;
                            visible.add(service);
                            resolved.incrementAndGet();
                            listener.found(new Nearby(service, id, name, DesktopClient.endpoint(host, info.getPort())));
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            };
        }
        void retire(boolean notify) {
            synchronized (this) {
                active = false;
                if (notify && epoch.get() == run && !closed) for (String service : visible) listener.lost(service);
                visible.clear();
            }
            try { dns.close(); } catch (IOException ignored) { }
        }
    }

    private void execute(Runnable action) {
        try { worker.execute(action); } catch (RejectedExecutionException alreadyClosed) { }
    }
    public void stop() { epoch.incrementAndGet(); execute(this::closeInstances); }
    private void closeInstances() {
        if (monitor != null) { monitor.cancel(false); monitor = null; }
        for (Registration registration : instances.values()) registration.retire(false);
        instances.clear();
        registered = List.of(); eligible = List.of(); state = "stopped";
    }
    public synchronized void close() {
        if (closed) return;
        closed = true; epoch.incrementAndGet(); execute(this::closeInstances); worker.shutdown();
    }

    public String diagnostics() {
        List<InetAddress> addresses = eligible;
        return "lan_discovery_schema=1\nlan_discovery_state=" + state
                + "\nlan_mdns_service_type=" + TYPE
                + "\nlan_mdns_eligible_interfaces=" + addresses.size()
                + "\nlan_mdns_interfaces=" + String.join(";", addresses.stream().map(LanDiscovery::describe).toList())
                + "\nlan_mdns_registered_interfaces=" + registered.size()
                + "\nlan_mdns_service_responses=" + responses.get()
                + "\nlan_mdns_resolved_responses=" + resolved.get()
                + "\nlan_mdns_last_error=" + lastError + "\n";
    }
    private static String describe(InetAddress address) {
        try {
            NetworkInterface iface = NetworkInterface.getByInetAddress(address);
            if (iface != null) return address.getHostAddress() + "@" + iface.getName() + ",multicast=" + iface.supportsMulticast();
        } catch (SocketException unavailable) { }
        return address.getHostAddress();
    }
    private static String error(Throwable failure) {
        return failure.getClass().getSimpleName() + ":" + Objects.toString(failure.getMessage(), "")
                .codePoints().filter(c -> !Character.isISOControl(c)).limit(160)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
    }
}

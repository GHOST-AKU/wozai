package dev.ghost.nearbyim.transport;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android-free policy. Discovery UUIDs and randomized addresses remain untrusted hints. */
final class WifiDirectPolicy {
    static final String TYPE = "_wozai-p2p._tcp";
    static final int PORT = 39741;
    static final long PEER_TTL_MS = 45_000;
    static final class StartupDeadline {
        private final long run, deadline; private boolean ready;
        StartupDeadline(long run, long deadline) { this.run = run; this.deadline = deadline; }
        void acknowledge(long run) { if (this.run == run) ready = true; }
        boolean expired(long run, long now) { return this.run == run && !ready && now >= deadline; }
    }

    /** Every platform/socket completion must carry the run and, for a dial, its attempt. */
    static final class Lifecycle {
        private long epoch, attempt;
        private boolean running, pending, owned, foreign;
        synchronized long start() { epoch++; attempt++; running = true; pending = owned = foreign = false; return epoch; }
        synchronized void stop() { epoch++; attempt++; running = pending = owned = foreign = false; }
        synchronized boolean valid(long run) { return running && run == epoch; }
        synchronized long beginAttempt() { pending = true; return ++attempt; }
        synchronized void cancelAttempt() { if (pending) { pending = false; attempt++; } }
        synchronized boolean pending() { return pending; }
        synchronized boolean validAttempt(long run, long ticket) { return valid(run) && ticket == attempt; }
        synchronized void connected(long run, long ticket) { if (validAttempt(run, ticket)) pending = false; }
        synchronized boolean claimGroup(long run, boolean verified) { if (!valid(run) || !verified) return false; owned = true; foreign = false; return true; }
        synchronized boolean ownsGroup() { return owned; }
        synchronized void loseGroup(long run) { if (valid(run)) owned = foreign = false; }
        synchronized void foreignGroup(long run) { if (valid(run)) foreign = true; }
        synchronized boolean foreign() { return foreign; }
    }
    static String counterpart(String candidate, Peer selected, boolean pending, Registry registry, long now) {
        String address = address(candidate); if (address == null) return null;
        if (pending && selected != null && address.equals(selected.wifiDirectAddress)) return address;
        Peer known = registry.byAddress(address, now);
        return known != null && (!pending || selected == null || known.peerId.equals(selected.peerId) && known.wifiDirectAddress.equals(selected.wifiDirectAddress)) ? address : null;
    }

    /** No next native start/connect may run while an older cancel/remove is unacknowledged. */
    static final class CleanupGate {
        private long serial, pending;
        long begin() { if (pending == 0) pending = ++serial; return pending; }
        boolean complete(long token) { if (token != pending || pending == 0) return false; pending = 0; return true; }
        boolean blocked() { return pending != 0; }
        boolean current(long token) { return pending != 0 && token == pending; }
    }

    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        try { return UUID.fromString(value).toString(); } catch (IllegalArgumentException invalid) { return null; }
    }
    static String address(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return null;
        String result = value.toUpperCase(Locale.ROOT);
        if (result.equals("00:00:00:00:00:00") || (Integer.parseInt(result.substring(0, 2), 16) & 1) != 0) return null;
        return result;
    }
    private static String serviceType(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals(TYPE) || lower.equals(TYPE + ".") || lower.equals(TYPE + ".local.") || lower.equals(TYPE + ".local") ? TYPE : null;
    }
    private static String instance(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}")) return null;
        return value.toLowerCase(Locale.ROOT);
    }
    private static String domainInstance(String domain) {
        if (domain == null || domain.length() > 100) return null;
        String lower = domain.toLowerCase(Locale.ROOT), suffix = "." + TYPE + ".local.";
        if (!lower.endsWith(suffix)) return null;
        return instance(lower.substring(0, lower.length() - suffix.length()));
    }
    static String displayName(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 96) return null;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return null;
        return name;
    }

    static final class Registry {
        private static final int LIMIT = 64;
        private final String own;
        private final LinkedHashMap<String, Advertisement> entries = new LinkedHashMap<>();
        private final ArrayDeque<Peer> retired = new ArrayDeque<>();
        Registry(String own) { this.own = uuid(own); }
        Peer service(String instance, String type, String deviceAddress, long now) {
            String mac = address(deviceAddress), service = instance(instance);
            if (mac == null || service == null || serviceType(type) == null) return null;
            Advertisement entry = entry(mac, now); entry.service = service; entry.serviceAt = now;
            return peer(mac, entry, now);
        }
        Peer txt(String domain, Map<String,String> values, String deviceAddress, long now) {
            String mac = address(deviceAddress), service = domainInstance(domain);
            if (mac == null || service == null || values == null || values.size() < 3 || values.size() > 8) return null;
            int bytes = 0;
            for (Map.Entry<String,String> field : values.entrySet()) {
                if (field.getKey() == null || field.getValue() == null || field.getKey().length() > 32 || field.getValue().getBytes(StandardCharsets.UTF_8).length > 128) return null;
                bytes += field.getKey().getBytes(StandardCharsets.UTF_8).length + field.getValue().getBytes(StandardCharsets.UTF_8).length;
            }
            String id = uuid(values.get("id")), name = displayName(values.get("name"));
            if (bytes > 512 || id == null || id.equals(own) || name == null || !"1".equals(values.get("v"))) return null;
            Advertisement entry = entry(mac, now); entry.domain = service; entry.id = id; entry.name = name; entry.txtAt = now;
            return peer(mac, entry, now);
        }
        private Advertisement entry(String mac, long now) {
            Advertisement result = entries.get(mac);
            if (result == null) {
                if (entries.size() == LIMIT) retire(entries.remove(entries.keySet().iterator().next()));
                result = new Advertisement(); entries.put(mac, result);
            }
            result.time = now; return result;
        }
        private Peer peer(String mac, Advertisement entry, long now) {
            if (!fresh(entry, now)) { retire(entry); entry.peer = null; return null; }
            if (entry.published != null && !entry.published.peerId.equals(entry.id)) retire(entry);
            // A UUID is only a discovery hint. Re-discovery replaces its obsolete randomized MAC.
            Iterator<Map.Entry<String,Advertisement>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) { var other = iterator.next(); if (!other.getKey().equals(mac) && entry.id.equals(other.getValue().id)) iterator.remove(); }
            entry.peer = entry.published = Peer.wifiDirect(entry.id, entry.name, mac); entry.time = now; return entry.peer;
        }
        Peer byAddress(String deviceAddress, long now) {
            Advertisement entry = entries.get(address(deviceAddress));
            return entry != null && fresh(entry, now) ? entry.peer : null;
        }
        boolean contains(Peer peer, long now) {
            if (peer == null || peer.mode != Peer.WIFI_DIRECT) return false;
            Peer current = byAddress(peer.wifiDirectAddress, now);
            return current != null && current.peerId.equals(peer.peerId);
        }
        List<Peer> expire(long now) {
            List<Peer> lost = takeLost(); Iterator<Advertisement> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Advertisement entry = iterator.next();
                if (entry.published != null && !fresh(entry, now)) { lost.add(entry.published); entry.published = null; entry.peer = null; }
                if (now - entry.time > PEER_TTL_MS) iterator.remove();
            }
            return lost;
        }
        List<Peer> takeLost() { List<Peer> lost = new ArrayList<>(retired); retired.clear(); return lost; }
        private void retire(Advertisement entry) {
            if (entry != null && entry.published != null) {
                if (retired.size() == LIMIT) retired.removeFirst();
                retired.addLast(entry.published); entry.published = null;
            }
        }
        int size() { return entries.size(); }
        private static boolean fresh(Advertisement entry, long now) {
            return entry.id != null && entry.service != null && entry.service.equals(entry.domain) && now - entry.serviceAt <= PEER_TTL_MS && now - entry.txtAt <= PEER_TTL_MS;
        }
        private static final class Advertisement { String service, domain, id, name; long time, serviceAt, txtAt; Peer peer, published; }
    }

    static final class InterfaceRoute {
        final String name; final InetAddress local; final int prefix;
        InterfaceRoute(String name, InetAddress local, int prefix) { this.name = name; this.local = local; this.prefix = prefix; }
    }
    static final class Route {
        final String name; final InetAddress local; final int prefix;
        Route(InterfaceRoute route) { name = route.name; local = route.local; prefix = route.prefix; }
        boolean accepts(InetAddress remote) { return ipv4(remote) && !local.equals(remote) && subnet(local, remote, prefix) && hostAddress(remote, prefix); }
    }
    static Route prove(String name, InetAddress owner, boolean go, List<InterfaceRoute> routes, boolean exactNetwork, boolean vpn) {
        if (name == null || name.isEmpty() || !ipv4(owner) || (!exactNetwork && vpn)) return null;
        InterfaceRoute selected = null;
        for (InterfaceRoute route : routes) {
            if (!name.equals(route.name) || !ipv4(route.local) || route.prefix < 1 || route.prefix > 30 || !hostAddress(route.local, route.prefix)) continue;
            if (go ? route.local.equals(owner) : !route.local.equals(owner) && subnet(route.local, owner, route.prefix) && hostAddress(owner, route.prefix)) {
                if (selected == null || route.prefix > selected.prefix) selected = route;
            }
        }
        if (selected == null) return null;
        if (!exactNetwork) for (InterfaceRoute other : routes) {
            if (!name.equals(other.name) && ipv4(other.local) && other.prefix >= 0 && other.prefix <= 32 &&
                    subnet(selected.local, other.local, Math.min(selected.prefix, other.prefix))) return null;
        }
        return new Route(selected);
    }
    private static boolean ipv4(InetAddress address) { return address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress(); }
    private static boolean subnet(InetAddress first, InetAddress second, int bits) {
        byte[] a = first.getAddress(), b = second.getAddress(); if (a.length != 4 || b.length != 4) return false;
        for (int i = 0; i < 4; i++) { int count = Math.max(0, Math.min(8, bits - i * 8)); int mask = count == 0 ? 0 : 0xff << (8 - count); if ((a[i] & mask) != (b[i] & mask)) return false; }
        return true;
    }
    private static boolean hostAddress(InetAddress address, int prefix) {
        byte[] a = address.getAddress(); long value = 0; for (byte part : a) value = (value << 8) | (part & 0xff);
        long hostMask = (1L << (32 - prefix)) - 1, host = value & hostMask;
        return host != 0 && host != hostMask;
    }
}

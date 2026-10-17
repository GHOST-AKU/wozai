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
    /** Eligibility comes only from an approved control-chat file attempt, never discovery. */
    static final class FileAttempt {
        private String expected;
        private boolean dialer, dialing;
        void arm(String peerId, boolean dialer) {
            String id = uuid(peerId);
            if (id == null) throw new IllegalArgumentException("Invalid file peer");
            expected = id; this.dialer = dialer; dialing = false;
        }
        boolean allows(Peer peer) { return expected != null && peer != null && expected.equals(peer.peerId); }
        boolean armed() { return expected != null; }
        boolean dial(Peer peer) {
            if (!dialer || dialing || !allows(peer)) return false;
            dialing = true; return true;
        }
        void stop() { expected = null; dialer = dialing = false; }
    }
    /** A fresh known group is usable only after this command's ACK and exact role/name proof. */
    static final class KnownGroup {
        private final String ssid;private final boolean owner;
        private boolean fiveGhz,stopped,announced;private int state; // 0 idle, 1 unknown ACK, 2 accepted, 3 rejected
        KnownGroup(String ssid,boolean owner,boolean fiveGhz){this.ssid=Objects.requireNonNull(ssid);this.owner=owner;this.fiveGhz=fiveGhz;}
        boolean begin(){if(stopped||state!=0)return false;state=1;return true;}
        boolean ack(boolean accepted){if(stopped||state!=1)return false;state=accepted?2:3;return true;}
        boolean mayFallback(){return !stopped&&owner&&fiveGhz&&state==3;}
        boolean fallback(){if(!mayFallback())return false;fiveGhz=false;state=0;return true;}
        boolean prefers5Ghz(){return fiveGhz;}
        boolean matches(String network,boolean groupOwner){return !stopped&&state==2&&owner==groupOwner&&ssid.equals(network);}
        boolean announce(){if(stopped||state!=2||!owner||announced)return false;announced=true;return true;}
        void stop(){stopped=true;}
    }
    /** A formed GO may still be waiting for tethering/DHCP; repeated snapshots cannot extend this wait. */
    static final class GroupReadiness {
        enum State { WAIT_GROUP, WAIT_ROLE, WAIT_ADDRESS, WAIT_INTERFACE, READY, EXPIRED, RETIRED }
        private final long timeoutMs;private long expires=-1;private boolean stopped;
        GroupReadiness(long timeoutMs){this.timeoutMs=timeoutMs;}
        State observe(long now,boolean formed,boolean roleMatches,boolean hasAddress,boolean hasInterface){
            if(stopped)return State.RETIRED;
            if(expires>=0&&now>=expires)return State.EXPIRED;
            if(formed&&roleMatches&&hasAddress&&hasInterface){expires=-1;return State.READY;}
            if(expires<0)expires=now+timeoutMs;
            return !formed?State.WAIT_GROUP:!roleMatches?State.WAIT_ROLE:!hasAddress?State.WAIT_ADDRESS:State.WAIT_INTERFACE;
        }
        boolean waiting(){return !stopped&&expires>=0;}
        long remaining(long now){return Math.max(0,expires-now);}
        void stop(){stopped=true;}
    }
    static final class Request<T> {
        final Object client; final long run, scan; final T value;
        private final long deadline;
        private boolean addPending = true, retiring, cleared, timeoutReported;
        Request(Object client, long run, long scan, T value, long now) { this.client = client; this.run = run; this.scan = scan; this.value = value; deadline = now + 4_000; }
    }
    static final class RequestClear<T> {
        final Request<T> request; private final long deadline; private boolean timeoutReported;
        RequestClear(Request<T> request, long now) { this.request = request; deadline = now + 4_000; }
    }
    /** One own-client subscription plus at most one unresolved clear listener; failure is not deletion. */
    static final class RequestLedger<T> {
        private Request<T> request;
        private RequestClear<T> clear;
        Request<T> add(Object client, long run, long scan, T value, long now) {
            if (request != null) return null;
            return request = new Request<>(client, run, scan, value, now);
        }
        boolean addPending(Request<T> added) { return request == added && added.addPending; }
        boolean addReply(Request<T> added, boolean success) {
            if (!addPending(added)) return false;
            added.addPending = false;
            if (!success) added.retiring = true;
            boolean discover = success && !added.retiring;
            settle(); return discover;
        }
        RequestClear<T> clear(long now) {
            if (request == null || clear != null || request.cleared) return null;
            request.retiring = true; return clear = new RequestClear<>(request, now);
        }
        boolean clearReply(RequestClear<T> replied, boolean success) {
            if (clear != replied || request != replied.request) return false;
            clear = null; request.cleared = success;
            settle(); return true;
        }
        boolean occupied() { return request != null; }
        boolean retiring() { return request != null && request.retiring; }
        Request<T> current() { return request; }
        boolean addTimeout(Request<T> added, long now) {
            if (!addPending(added) || added.timeoutReported || now < added.deadline) return false;
            added.timeoutReported = added.retiring = true; return true;
        }
        boolean clearTimeout(RequestClear<T> pending, long now) {
            if (clear != pending || pending.timeoutReported || now < pending.deadline) return false;
            pending.timeoutReported = true; return true;
        }
        void closed(Object client) { if (request != null && request.client == client) { request = null; clear = null; } }
        private void settle() { if (request != null && request.cleared && !request.addPending && clear == null) request = null; }
    }
    enum Release { LOCAL_ONLY, WAIT_NATIVE_ACK }
    enum Admission { NEGOTIATE, REUSE_GROUP, FOREIGN_GROUP, INVALID_PEER }
    static final class NativeCommand {
        final long run, attempt;
        private int reply; // 0 unknown, 1 accepted, 2 rejected; ACK never proves group creation.
        private boolean retired, timeoutReported;
        private long deadline;
        NativeCommand(long run, long attempt) { this.run = run; this.attempt = attempt; }
    }
    /** Public P2P APIs expose usable topology, never the group's initiating UID or removal authority. */
    static final class SessionAuthority {
        private volatile Peer peer;
        private NativeCommand command;
        private boolean passiveSuppressed;
        void observe(Peer peer) { this.peer = peer; }
        boolean hasGroup() { return peer != null; }
        void loseGroup() { peer = null; }
        Peer currentPeer(String id) { Peer current = peer; return current != null && current.peerId.equals(uuid(id)) ? current : null; }
        NativeCommand currentCommand() { return command; }
        NativeCommand issue(long run, long attempt) {
            if (command != null && command.reply == 0) throw new IllegalStateException("Native connect acknowledgement unresolved");
            return command = new NativeCommand(run, attempt);
        }
        boolean reply(NativeCommand replied, boolean accepted) {
            if (replied != command || replied.reply != 0) return false;
            replied.reply = accepted ? 1 : 2;
            return !replied.retired;
        }
        Release release(boolean pending, boolean stopping, long now) {
            if (pending || peer != null || stopping && command != null) passiveSuppressed = true;
            if (pending || stopping) peer = null;
            if (command != null) {
                command.retired = true;
                if (command.reply == 0 && command.deadline == 0) command.deadline = now + 4_000;
            }
            return blocked() ? Release.WAIT_NATIVE_ACK : Release.LOCAL_ONLY;
        }
        boolean blocked() { return command != null && command.retired && command.reply == 0; }
        boolean timeout(NativeCommand retired, long now) {
            if (retired != command || !blocked() || retired.timeoutReported || now < retired.deadline) return false;
            retired.timeoutReported = true; return true;
        }
        boolean mayOpen(boolean explicit) { return !blocked() && (explicit || !passiveSuppressed); }
        void rearmFile() { if (!blocked()) passiveSuppressed = false; }
        Admission admit(Peer candidate, boolean exists, boolean matches, boolean fresh) {
            Peer retained = candidate == null ? null : currentPeer(candidate.peerId);
            boolean sameHint = retained != null && Objects.equals(retained.wifiDirectAddress, candidate.wifiDirectAddress);
            if (exists) return matches && (fresh || sameHint) ? Admission.REUSE_GROUP : Admission.FOREIGN_GROUP;
            return fresh ? Admission.NEGOTIATE : Admission.INVALID_PEER;
        }
    }
    /** Preparation and scanning have separate bounds; duplicate callbacks never extend either. */
    static final class DiscoveryDeadline {
        private final long prepareMs,scanMs;private int state;private long expires;
        DiscoveryDeadline(long prepareMs,long scanMs){this.prepareMs=prepareMs;this.scanMs=scanMs;}
        boolean prepare(long now){if(state!=0)return false;state=1;expires=now+prepareMs;return true;}
        boolean scan(long now){if(state>1||state==1&&now>=expires)return false;state=2;expires=now+scanMs;return true;}
        String failure(long now){return now<expires?"":state==1?"discovery_prepare_timeout":state==2?"discovery_timeout":"";}
        long expires(){return expires;}
        void found(){state=3;}
        void stop(){state=4;}
        void reset(){state=0;expires=0;}
    }
    static final class StartupDeadline {
        private final long run, deadline; private boolean ready;
        StartupDeadline(long run, long deadline) { this.run = run; this.deadline = deadline; }
        void acknowledge(long run) { if (this.run == run) ready = true; }
        boolean expired(long run, long now) { return this.run == run && !ready && now >= deadline; }
    }

    /** Every platform/socket completion must carry the run and, for a dial, its attempt. */
    static final class Lifecycle {
        private long epoch, attempt;
        private boolean running, pending, observed, foreign;
        synchronized long start() { epoch++; attempt++; running = true; pending = observed = foreign = false; return epoch; }
        synchronized void stop() { epoch++; attempt++; running = pending = observed = foreign = false; }
        synchronized boolean valid(long run) { return running && run == epoch; }
        synchronized long beginAttempt() { pending = true; return ++attempt; }
        synchronized void cancelAttempt() { if (pending) { pending = false; attempt++; } }
        synchronized boolean pending() { return pending; }
        synchronized boolean validAttempt(long run, long ticket) { return valid(run) && ticket == attempt; }
        synchronized void connected(long run, long ticket) { if (validAttempt(run, ticket)) pending = false; }
        synchronized boolean observeGroup(long run, boolean verified) { if (!valid(run) || !verified) return false; observed = true; foreign = false; return true; }
        synchronized boolean hasObservedGroup() { return observed; }
        synchronized void loseGroup(long run) { if (valid(run)) observed = foreign = false; }
        synchronized void foreignGroup(long run) { if (valid(run)) foreign = true; }
        synchronized boolean foreign() { return foreign; }
    }
    static String counterpart(String candidate, Peer selected, boolean pending, Registry registry, long now) {
        String address = address(candidate); if (address == null) return null;
        if (pending && selected != null && address.equals(selected.wifiDirectAddress)) return address;
        Peer known = registry.byAddress(address, now);
        return known != null && (!pending || selected == null || known.peerId.equals(selected.peerId) && known.wifiDirectAddress.equals(selected.wifiDirectAddress)) ? address : null;
    }

    /** Retired commands fence later starts/connects until their own matching native reply. */
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

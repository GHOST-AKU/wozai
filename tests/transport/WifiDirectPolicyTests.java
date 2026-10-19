package dev.ghost.nearbyim.transport;

import java.net.InetAddress;
import java.util.*;

/** Public-radio callbacks are deliberately not simulated; these exercise their lifecycle/route policy. */
public final class WifiDirectPolicyTests {
    private static final String OWN = "63344c17-0b20-4050-8434-edee90357785", OTHER = "e7d89444-7092-4912-b658-765d6276d994";
    private static final String MAC = "02:11:22:33:44:55", ROTATED = "02:11:22:33:44:66";
    private static int failures;
    public static void main(String[] args) throws Exception {
        check("late callbacks cannot resurrect a stopped run", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long old = life.start();
            life.stop(); long current = life.start();
            truth(!life.valid(old) && life.valid(current), "A restarted run must reject old callbacks");
            truth(!life.observeGroup(old, true) && !life.hasObservedGroup(), "Stale group cannot become observed");
        });
        check("cancel invalidates pending attempts without closing a delivered session", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long run = life.start(), first = life.beginAttempt();
            life.cancelAttempt(); truth(!life.validAttempt(run, first), "Cancelled socket callback is stale");
            long second = life.beginAttempt(); life.connected(run, second); life.cancelAttempt();
            truth(life.validAttempt(run, second), "Cancel must leave delivered session alone");
        });
        check("foreign groups never become verified app groups", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long run = life.start();
            truth(!life.observeGroup(run, false) && !life.hasObservedGroup(), "Unknown group must be preserved");
            truth(life.observeGroup(run, true) && life.hasObservedGroup(), "Verified app-peer group permits interface checks");
            life.loseGroup(run); truth(!life.hasObservedGroup(), "Group loss removes observed topology");
        });
        check("confirmed foreign group loss permits the next explicit attempt", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long run = life.start(); life.foreignGroup(run);
            truth(life.foreign(), "An unproven current group blocks an explicit attempt");
            life.loseGroup(run); long next = life.beginAttempt();
            truth(!life.foreign() && life.validAttempt(run, next), "Confirmed group absence removes the foreign-group block");
        });
        check("clicked snapshot only authorizes its bounded pending attempt", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0); Peer clicked = registry.txt(domain(), txt(OTHER), MAC, 1);
            equal(MAC, WifiDirectPolicy.counterpart(MAC, clicked, true, registry, 50_000), "Consent can outlive the original discovery TTL");
            equal(null, WifiDirectPolicy.counterpart(MAC, clicked, false, registry, 50_000), "Completed request cannot authorize a later passive group");
            registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 50_001); registry.txt(domain(), txt(OTHER), MAC, 50_002);
            equal(MAC, WifiDirectPolicy.counterpart(MAC, null, false, registry, 50_003), "Fresh app-service proof permits a new passive group");
        });
        check("cleanup acknowledgements fence later starts", () -> {
            WifiDirectPolicy.CleanupGate gate = new WifiDirectPolicy.CleanupGate(); long old = gate.begin();
            truth(gate.blocked(), "Native cleanup must block a new start");
            truth(gate.complete(old), "Matching acknowledgement releases cleanup"); long next = gate.begin();
            truth(!gate.complete(old) && gate.blocked(), "Late old acknowledgement cannot release new cleanup");
            truth(gate.complete(next) && !gate.blocked(), "Current cleanup may release the next operation");
        });
        check("passive app-peer observation never grants global cleanup authority", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); Peer peer = Peer.wifiDirect(OTHER, "Pad", MAC);
            authority.observe(peer);
            equal(WifiDirectPolicy.Release.LOCAL_ONLY, authority.release(false, false, 0), "Disconnect must leave a concurrently created observed group alone");
            authority.observe(peer);
            equal(WifiDirectPolicy.Release.LOCAL_ONLY, authority.release(false, true, 0), "Stop closes this client's resources without removeGroup");
        });
        check("cancel before BUSY reply retires only this command", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); var command = authority.issue(7, 9);
            equal(WifiDirectPolicy.Release.WAIT_NATIVE_ACK, authority.release(true, false, 0), "Unknown ACK cannot justify global cancelConnect");
            truth(authority.blocked(), "No second command may overlap retirement");
            truth(!authority.reply(command, false), "Late BUSY reply cannot approve the canceled attempt");
            truth(!authority.blocked() && !authority.mayOpen(false), "Rejection releases ACK fence but cannot resurrect passive chat");
        });
        check("unresolved ACK timeout stays fenced without guessing radio ownership", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); var command = authority.issue(7, 9);
            authority.release(true, true, 100);
            truth(!authority.timeout(command, 4_099) && authority.timeout(command, 4_100), "Unknown command retirement has a bounded visible timeout");
            truth(authority.blocked(), "Timeout never guesses that global negotiation stopped");
            truth(!authority.mayOpen(true), "An unresolved retired command also blocks a fresh explicit socket attempt");
            truth(!authority.timeout(command, 4_101), "Timeout is reported once");
        });
        check("late accepted ACK cannot reopen sockets after app cancel", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); var command = authority.issue(7, 9);
            authority.release(true, false, 0);
            truth(!authority.reply(command, true) && !authority.mayOpen(false), "Late success retires metadata only, never approves a chat");
            truth(authority.mayOpen(true), "A fresh explicit attempt can authorize socket reuse");
        });
        check("invitation ACK for an existing group cannot promote removal authority", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); var command = authority.issue(7, 9);
            truth(authority.reply(command, true), "Current accepted command may inspect group readiness");
            authority.observe(Peer.wifiDirect(OTHER, "Pad", MAC));
            equal(WifiDirectPolicy.Release.LOCAL_ONLY, authority.release(false, true, 0), "Connect ACK and peer hint do not identify a group's initiating app");
        });
        check("healthy observed-group hint survives TTL for explicit same-peer socket retry", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); Peer peer = Peer.wifiDirect(OTHER, "Pad", MAC); authority.observe(peer);
            truth(authority.hasGroup(), "Verified current group pauses client discovery waves");
            authority.release(false, false, 60_000);
            equal(peer, authority.currentPeer(OTHER), "Disconnect preserves only the in-memory current group hint");
            equal(WifiDirectPolicy.Admission.REUSE_GROUP, authority.admit(peer, true, true, false), "Fresh matching group query permits reuse after DNS expiry");
            equal(WifiDirectPolicy.Admission.FOREIGN_GROUP, authority.admit(peer, true, false, true), "Another group cannot inherit the retained address");
            authority.loseGroup(); equal(null, authority.currentPeer(OTHER), "Confirmed group loss discards hint");
            equal(WifiDirectPolicy.Admission.INVALID_PEER, authority.admit(peer, false, false, false), "Stale hint cannot create a new radio negotiation");
        });
        check("canceled snapshots and stale ACKs cannot become a current peer", () -> {
            var authority = new WifiDirectPolicy.SessionAuthority(); Peer peer = Peer.wifiDirect(OTHER, "Pad", MAC); authority.observe(peer);
            var old = authority.issue(7, 9); authority.release(true, false, 0); authority.reply(old, false);
            equal(null, authority.currentPeer(OTHER), "Canceled group is not a remembered routing hint");
            var next = authority.issue(7, 10);
            truth(!authority.reply(old, true), "Duplicate old success cannot alter a newer command");
            authority.release(true, false, 10);
            truth(authority.blocked(), "Old ACK cannot release the new command's fence");
            authority.reply(next, false);
        });
        check("failed ADD retains the one native request slot until own clear ACK", () -> {
            var requests = new WifiDirectPolicy.RequestLedger<String>(); Object client = new Object();
            var added = requests.add(client, 7, 1, "own-request", 0);
            truth(!requests.addReply(added, false), "Failed ADD cannot start discovery");
            truth(requests.occupied() && requests.add(client, 7, 2, "second", 1) == null, "ADD failure may have inserted the original native request");
            var clear = requests.clear(2); truth(clear != null, "Uncertain own request needs client-scoped cleanup");
            requests.clearReply(clear, true); truth(!requests.occupied(), "Only successful cleanup settles the slot");
        });
        check("failed clear never permits accumulating new requests", () -> {
            var requests = new WifiDirectPolicy.RequestLedger<String>(); Object client = new Object();
            var added = requests.add(client, 7, 1, "own-request", 0); requests.addReply(added, true);
            var clear = requests.clear(1); requests.clearReply(clear, false);
            for (int i = 0; i < 100; i++) truth(requests.add(client, 7, i + 2, "new", i + 2) == null, "Repeated Search cannot ADD over failed cleanup");
            var retry = requests.clear(200); truth(retry != null, "ACKed failure permits one later explicit cleanup attempt");
            truth(!requests.clearReply(clear, true) && requests.occupied(), "Older clear ACK cannot settle the retry");
            requests.clearReply(retry, true); truth(!requests.occupied(), "Current cleanup success allows the next search");
        });
        check("ADD in flight stop and late ACK cannot restart a canceled scan", () -> {
            var requests = new WifiDirectPolicy.RequestLedger<String>(); Object client = new Object();
            var added = requests.add(client, 7, 1, "own-request", 0); var clear = requests.clear(1);
            requests.clearReply(clear, true);
            truth(requests.occupied(), "Even after native clear, unresolved ADD listener remains bounded in its slot");
            truth(!requests.addReply(added, true) && !requests.occupied(), "Late ADD success settles metadata without discovery resurrection");
            var next = requests.add(client, 7, 2, "next", 2);
            truth(!requests.addReply(added, false) && requests.current() == next, "Duplicate old ADD failure cannot touch the next request");
        });
        check("unknown ADD and CLEAR callbacks have bounded visible watchdogs", () -> {
            var requests = new WifiDirectPolicy.RequestLedger<String>(); Object client = new Object();
            var added = requests.add(client, 7, 1, "own-request", 100);
            truth(!requests.addTimeout(added, 4_099) && requests.addTimeout(added, 4_100), "Missing ADD reply is visible within four seconds");
            var clear = requests.clear(4_100);
            truth(!requests.clearTimeout(clear, 8_099) && requests.clearTimeout(clear, 8_100), "Missing clear reply is visible within four seconds");
            for (int i = 0; i < 100; i++) truth(requests.clear(8_101 + i) == null, "Unknown clear is fenced instead of accumulating native ActionListeners");
            truth(!requests.clearTimeout(clear, 8_201) && requests.occupied(), "Timeout reports once and never guesses request deletion");
        });
        check("request retirement is scoped to its exact client and clears stale callbacks", () -> {
            var requests = new WifiDirectPolicy.RequestLedger<String>(); Object oldClient = new Object(), nextClient = new Object();
            var old = requests.add(oldClient, 7, 1, "old", 0); var clear = requests.clear(1);
            requests.closed(nextClient); truth(requests.occupied(), "Closing another channel cannot retire this client's request");
            requests.closed(oldClient); truth(!requests.occupied(), "Successful public own-client close retires its native listener/request slot");
            var next = requests.add(nextClient, 8, 2, "new", 2);
            truth(!requests.clearReply(clear, true) && !requests.addReply(old, true) && requests.current() == next, "Old channel callbacks cannot clear or discover on the new channel");
        });
        check("startup without a native advertisement acknowledgement is bounded", () -> {
            var deadline = new WifiDirectPolicy.StartupDeadline(7, 10_000);
            truth(!deadline.expired(7, 9_999), "Wait for the bounded startup interval");
            deadline.acknowledge(6);
            truth(deadline.expired(7, 10_000), "Missing or stale native callback must produce a visible startup timeout");
            var ready = new WifiDirectPolicy.StartupDeadline(8, 10_000); ready.acknowledge(8);
            truth(!ready.expired(8, 20_000), "Current advertisement acknowledgement ends startup wait without proving a TCP path");
        });
        check("registry requires matching service and TXT provenance", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            equal(null, registry.txt(domain(), txt(OTHER), MAC, 0), "TXT alone is not app service proof");
            Peer peer = registry.service("wozai-peer", "_wozai-p2p._tcp.local.", MAC, 1);
            truth(peer != null && OTHER.equals(peer.peerId), "Matching service plus TXT identifies app peer hint");
            truth(registry.contains(peer, 2), "Current UUID/address can connect");
            equal(null, registry.service("wrong", "_http._tcp", ROTATED, 2), "Foreign service rejected");
            equal(null, registry.txt("mismatch._wozai-p2p._tcp.local.", txt(OTHER), ROTATED, 2), "Unmatched instance not advertised");
        });
        check("registry rejects self, malformed TXT, address, UUID and version", () -> {
            for (Map<String,String> invalid : List.of(txt(OWN), txt("1-1-1-1-1"), with("v", "2"), with("name", "x".repeat(97)), with("id", "\n" + OTHER))) {
                WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN); registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0);
                equal(null, registry.txt(domain(), invalid, MAC, 1), "Malformed/self advertisement must be discarded");
            }
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            equal(null, registry.service("wozai-peer", "_wozai-p2p._tcp", "FF:FF:FF:FF:FF:FF", 0), "Broadcast address rejected");
        });
        check("registry expires and replaces randomized addresses without trusting identity", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN); registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0);
            Peer first = registry.txt(domain(), txt(OTHER), MAC, 1);
            registry.service("wozai-peer", "_wozai-p2p._tcp", ROTATED, 2); Peer latest = registry.txt(domain(), txt(OTHER), ROTATED, 3);
            truth(latest != null && first.key.equals(latest.key), "UUID discovery key survives address rotation");
            truth(!registry.contains(first, 4) && registry.contains(latest, 4), "Old address cannot be used after rediscovery");
            equal(1, registry.expire(50_000).size(), "Expired UUID disappears");
            truth(!registry.contains(latest, 50_000), "Expired hint cannot authorize native connect");
        });
        check("registry has a bounded unsolicited service cache", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            for (int i = 0; i < 300; i++) registry.service("wozai-peer", "_wozai-p2p._tcp", String.format(Locale.ROOT, "02:11:22:33:%02X:%02X", i / 256, i % 256), i);
            truth(registry.size() <= 64, "Unsolicited discovery cannot grow cache without limit");
        });
        check("new service provenance cannot preserve an obsolete TXT identity", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0); Peer peer = registry.txt(domain(), txt(OTHER), MAC, 1);
            truth(peer != null, "Start from a real matched advertisement");
            registry.service("different-instance", "_wozai-p2p._tcp", MAC, 2);
            truth(!registry.contains(peer, 3), "Changed service instance invalidates previous TXT association");
        });
        check("service responses alone cannot keep stale TXT identity alive", () -> {
            WifiDirectPolicy.Registry registry = new WifiDirectPolicy.Registry(OWN);
            registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0); Peer peer = registry.txt(domain(), txt(OTHER), MAC, 1);
            registry.service("wozai-peer", "_wozai-p2p._tcp", MAC, 40_000);
            truth(!registry.contains(peer, 50_000), "Both service and TXT provenance must stay fresh");
            equal(1, registry.expire(50_000).size(), "Stale published identity receives one lost event");
            equal(0, registry.expire(50_001).size(), "Lost peer is reported only once");
        });
        check("replaced and evicted discovery identities are removed from the visible peer list", () -> {
            WifiDirectPolicy.Registry replaced = new WifiDirectPolicy.Registry(OWN);
            replaced.service("wozai-peer", "_wozai-p2p._tcp", MAC, 0); Peer first = replaced.txt(domain(), txt(OTHER), MAC, 1);
            Peer second = replaced.txt(domain(), txt("db66eaa2-ce5e-4c0e-bf12-3b49bab42056"), MAC, 2);
            truth(second != null && !replaced.contains(first, 3), "Replacing identity changes current address association");
            equal(List.of(first), replaced.expire(4), "Previous visible UUID receives a lost event");
            WifiDirectPolicy.Registry bounded = new WifiDirectPolicy.Registry(OWN);
            for (int i = 0; i < 65; i++) {
                String mac = String.format(Locale.ROOT, "02:11:22:33:44:%02X", i);
                String id = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", i);
                bounded.service("wozai-peer", "_wozai-p2p._tcp", mac, i); bounded.txt(domain(), txt(id), mac, i);
            }
            equal(1, bounded.expire(70).size(), "Cache eviction cannot leave unlimited visible ghost peers");
            truth(bounded.size() <= 64, "Published and unpublished hints share the same cap");
        });
        check("actual group interface and owner address prove each role", () -> {
            var local = address("10.42.9.7"); var owner = address("10.42.9.1");
            var routes = List.of(new WifiDirectPolicy.InterfaceRoute("p2p-wlan0-0", local, 24));
            var client = WifiDirectPolicy.prove("p2p-wlan0-0", owner, false, routes, false, WifiDirectPolicy.VpnScope.ABSENT);
            truth(client != null && local.equals(client.local), "Client binds the actual interface source");
            truth(client.accepts(address("10.42.9.23")) && !client.accepts(address("10.42.8.23")), "TCP remote must use the group subnet");
            truth(WifiDirectPolicy.prove("p2p-wlan0-0", local, true, routes, false, WifiDirectPolicy.VpnScope.ABSENT) != null, "GO binds actual owner address");
            equal(null, WifiDirectPolicy.prove("p2p-missing", owner, false, routes, false, WifiDirectPolicy.VpnScope.ABSENT), "No interface means not ready");
            equal(null, WifiDirectPolicy.prove("p2p-wlan0-0", owner, true, routes, false, WifiDirectPolicy.VpnScope.ABSENT), "GO owner address must be assigned locally");
        });
        check("fallback rejects VPN, overlapping routes and unsupported IPv6", () -> {
            var routes = List.of(new WifiDirectPolicy.InterfaceRoute("p2p0", address("10.42.9.7"), 24), new WifiDirectPolicy.InterfaceRoute("wlan0", address("10.42.9.8"), 24));
            equal(null, WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes, false, WifiDirectPolicy.VpnScope.ABSENT), "Overlapping LAN cannot be labelled Direct");
            equal(null, WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes.subList(0,1), false, WifiDirectPolicy.VpnScope.APPLIES), "Unbound VPN path is ambiguous");
            truth(WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes, true, WifiDirectPolicy.VpnScope.APPLIES) != null, "Exact bound P2P Network resolves competing routes");
            equal(null, WifiDirectPolicy.prove("p2p0", address("fe80::1"), false, routes, false, WifiDirectPolicy.VpnScope.ABSENT), "Scoped IPv6 needs visible unsupported failure");
        });
        check("GO listens on its own P2P address while a separate VPN is active", () -> {
            var owner = address("192.168.49.1");
            var routes = List.of(new WifiDirectPolicy.InterfaceRoute("p2p0", owner, 24),
                    new WifiDirectPolicy.InterfaceRoute("wlan0", address("192.168.31.5"), 24),
                    new WifiDirectPolicy.InterfaceRoute("tun0", address("10.8.0.2"), 32));
            var listener = WifiDirectPolicy.prove("p2p0", owner, true, routes, false, WifiDirectPolicy.VpnScope.APPLIES);
            truth(listener != null && owner.equals(listener.local), "VPN presence alone must not reject the P2P-bound listener");
            truth(listener.accepts(address("192.168.49.2")) && !listener.accepts(address("192.168.31.6")), "Only connections from the observed group subnet are admitted");
            equal(null, WifiDirectPolicy.prove("p2p0", address("192.168.49.2"), true, routes, false, WifiDirectPolicy.VpnScope.APPLIES), "VPN exception cannot invent a local GO address");
        });
        check("GO VPN coexistence still rejects competing interface addresses", () -> {
            var owner = address("192.168.49.1");
            var group = new WifiDirectPolicy.InterfaceRoute("p2p0", owner, 24);
            for (String other : List.of("192.168.49.1", "192.168.49.8")) {
                var routes = List.of(group, new WifiDirectPolicy.InterfaceRoute("wlan0", address(other), 24));
                truth(WifiDirectPolicy.localRoute("p2p0", owner, true, routes) != null, "An assigned but conflicting interface is not misreported as still waiting for an IP");
                equal(null, WifiDirectPolicy.prove("p2p0", owner, true, routes, false, WifiDirectPolicy.VpnScope.APPLIES), "GO does not use a client Network proof to bypass competing return paths");
                equal(null, WifiDirectPolicy.prove("p2p0", owner, true, routes, true, WifiDirectPolicy.VpnScope.APPLIES), "An available Network cannot bypass GO interface overlap checks");
            }
        });
        if (failures != 0) throw new AssertionError(failures + " Wi-Fi Direct policy checks failed");
        System.out.println("Wi-Fi Direct lifecycle, discovery and route checks passed");
    }
    private interface Checked { void run() throws Exception; }
    private static void check(String name, Checked test) { try { test.run(); } catch (Throwable failure) { failures++; System.out.println("FAIL " + name + ": " + failure.getMessage()); } }
    private static InetAddress address(String ip) throws Exception { return InetAddress.getByName(ip); }
    private static String domain() { return "wozai-peer._wozai-p2p._tcp.local."; }
    private static Map<String,String> txt(String id) { return Map.of("id", id, "name", "Pad", "v", "1"); }
    private static Map<String,String> with(String key, String value) { var result = new HashMap<>(txt(OTHER)); result.put(key, value); return result; }
    private static void truth(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual); }
}

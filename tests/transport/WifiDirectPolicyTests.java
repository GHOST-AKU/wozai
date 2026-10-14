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
            truth(!life.claimGroup(old, true) && !life.ownsGroup(), "Stale group cannot become owned");
        });
        check("cancel invalidates pending attempts without closing a delivered session", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long run = life.start(), first = life.beginAttempt();
            life.cancelAttempt(); truth(!life.validAttempt(run, first), "Cancelled socket callback is stale");
            long second = life.beginAttempt(); life.connected(run, second); life.cancelAttempt();
            truth(life.validAttempt(run, second), "Cancel must leave delivered session alone");
        });
        check("foreign groups are never owned", () -> {
            WifiDirectPolicy.Lifecycle life = new WifiDirectPolicy.Lifecycle(); long run = life.start();
            truth(!life.claimGroup(run, false) && !life.ownsGroup(), "Unknown group must be preserved");
            truth(life.claimGroup(run, true) && life.ownsGroup(), "Verified app-peer group can be owned");
            life.loseGroup(run); truth(!life.ownsGroup(), "Group loss removes ownership");
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
            var client = WifiDirectPolicy.prove("p2p-wlan0-0", owner, false, routes, false, false);
            truth(client != null && local.equals(client.local), "Client binds the actual interface source");
            truth(client.accepts(address("10.42.9.23")) && !client.accepts(address("10.42.8.23")), "TCP remote must use the group subnet");
            truth(WifiDirectPolicy.prove("p2p-wlan0-0", local, true, routes, false, false) != null, "GO binds actual owner address");
            equal(null, WifiDirectPolicy.prove("p2p-missing", owner, false, routes, false, false), "No interface means not ready");
            equal(null, WifiDirectPolicy.prove("p2p-wlan0-0", owner, true, routes, false, false), "GO owner address must be assigned locally");
        });
        check("fallback rejects VPN, overlapping routes and unsupported IPv6", () -> {
            var routes = List.of(new WifiDirectPolicy.InterfaceRoute("p2p0", address("10.42.9.7"), 24), new WifiDirectPolicy.InterfaceRoute("wlan0", address("10.42.9.8"), 24));
            equal(null, WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes, false, false), "Overlapping LAN cannot be labelled Direct");
            equal(null, WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes.subList(0,1), false, true), "Unbound VPN path is ambiguous");
            truth(WifiDirectPolicy.prove("p2p0", address("10.42.9.1"), false, routes, true, true) != null, "Exact bound P2P Network resolves competing routes");
            equal(null, WifiDirectPolicy.prove("p2p0", address("fe80::1"), false, routes, false, false), "Scoped IPv6 needs visible unsupported failure");
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

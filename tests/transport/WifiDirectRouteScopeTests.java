package dev.ghost.nearbyim.transport;

import java.lang.reflect.*;
import java.net.InetAddress;
import java.util.List;

/** Exercises the UID-scoped observation and the real production route admission together. */
public final class WifiDirectRouteScopeTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Class<?> scope;
        try { scope = Class.forName("dev.ghost.nearbyim.transport.WifiDirectPolicy$VpnScope"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("P2P routing must distinguish the current app's VPN scope from device-wide VPN presence", missing); }
        Method observe = scope.getDeclaredMethod("observe", boolean.class, boolean.class, boolean.class);
        Object excluded = observe.invoke(null, true, false, true);
        Object applies = observe.invoke(null, true, true, false);
        Object unknown = observe.invoke(null, false, false, false);
        check(excluded.toString().equals("EXCLUDED"), "An active NOT_VPN network is evidence for this calling UID");
        check(applies.toString().equals("APPLIES"), "An active VPN applies to the calling app");
        check(unknown.toString().equals("UNKNOWN"), "No active capabilities must not mean VPN off");
        check(observe.invoke(null, true, false, false).equals(unknown), "Missing NOT_VPN evidence stays unknown");
        check(!observe.invoke(null, true, true, true).equals(excluded), "Conflicting VPN capabilities never grant an exemption");
        Method complete = scope.getDeclaredMethod("complete", boolean.class, boolean.class);
        Object absent = complete.invoke(unknown, true, false);
        check(absent.toString().equals("ABSENT"), "A complete enumeration without tunnel evidence preserves offline P2P");
        check(complete.invoke(unknown, false, false).equals(unknown), "Incomplete enumeration is not absence evidence");
        check(complete.invoke(unknown, true, true).equals(unknown), "An observed tunnel with no active UID network stays unknown");
        check(complete.invoke(excluded, true, true).equals(excluded), "A device tunnel cannot undo positive UID exclusion evidence");
        check(complete.invoke(applies, true, false).equals(applies), "A global snapshot cannot override positive UID VPN evidence");
        var owner = InetAddress.getByName("192.168.49.1");
        var routes = List.of(new WifiDirectPolicy.InterfaceRoute("p2p0", InetAddress.getByName("192.168.49.2"), 24),
                new WifiDirectPolicy.InterfaceRoute("wlan0", InetAddress.getByName("192.168.31.8"), 24),
                new WifiDirectPolicy.InterfaceRoute("tun0", InetAddress.getByName("198.18.0.1"), 30));
        Method prove = WifiDirectPolicy.class.getDeclaredMethod("prove", String.class, InetAddress.class, boolean.class, List.class, boolean.class, scope);
        check(prove.invoke(null, "p2p0", owner, false, routes.subList(0, 2), false, absent) != null,
                "Offline P2P without VPN evidence still admits its exact source interface");
        check(prove.invoke(null, "p2p0", owner, false, routes, false, excluded) != null,
                "An app excluded from the VPN may dial its proven P2P interface even without a standalone Network");
        for (Object denied : List.of(applies, unknown)) {
            check(prove.invoke(null, "p2p0", owner, false, routes, false, denied) == null,
                    "Covered or unknown VPN scope needs exact Network binding");
            check(prove.invoke(null, "p2p0", owner, false, routes, true, denied) != null,
                    "An exact P2P Network remains sufficient");
        }
        var overlap = List.of(routes.get(0), new WifiDirectPolicy.InterfaceRoute("wlan0", InetAddress.getByName("192.168.49.8"), 24));
        check(prove.invoke(null, "p2p0", owner, false, overlap, false, excluded) == null,
                "VPN exclusion does not authorize an overlapping LAN route");
        check(prove.invoke(null, "wrong-interface", owner, false, routes, false, excluded) == null,
                "VPN exclusion does not invent a local P2P interface");
        System.out.println("P2P app VPN scope: " + checks + " checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

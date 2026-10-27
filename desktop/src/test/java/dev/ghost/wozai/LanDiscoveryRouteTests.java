package dev.ghost.wozai;

import dev.ghost.nearbyim.core.LocalEndpoint;
import java.net.*;
import java.util.*;

/** Multi-address DNS answers must prefer the interface that received the service. */
public final class LanDiscoveryRouteTests {
    public static void main(String[] args) throws Exception {
        InetAddress local = localAddress();
        // The real local LAN address is reachable by both test peers on this host.
        InetAddress neighbor = local;
        InetAddress unrelated = InetAddress.getByName(local.getHostAddress().startsWith("10.")
                ? "192.168.222.2" : "10.231.42.2");
        check(neighbor.equals(LanDiscovery.preferredHost(local, new InetAddress[]{unrelated, neighbor})),
                "An unrelated advertised address hid the peer's reachable Ethernet/Wi-Fi address");
        check(neighbor.equals(LanDiscovery.preferredHost(local, new InetAddress[]{neighbor, unrelated})),
                "DNS answer order changed the preferred LAN endpoint");
        check(unrelated.equals(LanDiscovery.preferredHost(local, new InetAddress[]{unrelated})),
                "A routed private LAN endpoint was discarded when no on-link address existed");
        check(LanDiscovery.preferredHost(local, new InetAddress[]{InetAddress.getLoopbackAddress(), InetAddress.getByName("8.8.8.8")}) == null,
                "Discovery accepted loopback or public Internet addresses");
        System.out.println("LanDiscoveryRouteTests: on-link address preference, routed fallback and local-address restrictions passed");
    }

    static InetAddress localAddress() throws SocketException {
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!DesktopFileRoutes.ordinary(iface)) continue;
            for (InterfaceAddress address : iface.getInterfaceAddresses())
                if (address.getAddress() instanceof Inet4Address && LocalEndpoint.isLocal(address.getAddress())
                        && address.getNetworkPrefixLength() > 0 && address.getNetworkPrefixLength() <= 30)
                    return address.getAddress();
        }
        throw new AssertionError("A private ordinary LAN interface is required for this network test");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

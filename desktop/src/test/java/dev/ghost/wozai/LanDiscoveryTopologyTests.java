package dev.ghost.wozai;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Controlled interface visibility with real multicast sockets and advertisements. */
public final class LanDiscoveryTopologyTests {
    public static void main(String[] args) throws Exception {
        InetAddress address = LanDiscoveryRouteTests.localAddress();
        AtomicReference<List<InetAddress>> addresses = new AtomicReference<>(List.of());
        AtomicBoolean enumerationFailure = new AtomicBoolean();
        String own = UUID.randomUUID().toString(), peer = UUID.randomUUID().toString();
        BlockingQueue<LanDiscovery.Nearby> found = new LinkedBlockingQueue<>();
        BlockingQueue<String> lost = new LinkedBlockingQueue<>();
        CountDownLatch initiallyUnavailable = new CountDownLatch(1);
        LanDiscovery.Listener events = new LanDiscovery.Listener() {
            public void found(LanDiscovery.Nearby value) { if (value.id().equals(peer)) found.add(value); }
            public void lost(String service) { lost.add(service); }
            public void failed() { initiallyUnavailable.countDown(); }
        };
        try (LanDiscovery browser = new LanDiscovery(() -> {
                 if (enumerationFailure.get()) throw new IllegalStateException("controlled enumeration failure");
                 return addresses.get();
             });
             LanDiscovery advertiser = new LanDiscovery(() -> List.of(address))) {
            browser.start(own, "Wired browser", 12345, events);
            advertiser.start(peer, "Ethernet peer", 23456, events);
            check(initiallyUnavailable.await(5, TimeUnit.SECONDS), "No-interface state did not finish discovery startup");
            check(browser.diagnostics().contains("lan_discovery_state=no_interfaces\n"), "Diagnostics hid the missing LAN interface");
            addresses.set(List.of(address));
            LanDiscovery.Nearby first = found.poll(25, TimeUnit.SECONDS);
            check(first != null, "Plugging in Ethernet after startup did not start discovery");
            check(browser.diagnostics().contains(address.getHostAddress() + "@"), "Diagnostics omitted the actual discovery interface");
            enumerationFailure.set(true);
            await(() -> browser.diagnostics().contains("controlled enumeration failure"), "Enumeration failure disappeared from diagnostics");
            check(browser.diagnostics().contains("lan_mdns_registered_interfaces=1\n") && lost.isEmpty(),
                    "A transient enumeration error discarded an existing discovery socket");
            enumerationFailure.set(false);
            addresses.set(List.of());
            check(first.service().equals(lost.poll(15, TimeUnit.SECONDS)), "Unplugging Ethernet left a stale nearby device");
            // A new registration must rediscover the peer after the old interface has retired.
            found.clear();
            addresses.set(List.of(address));
            check(found.poll(25, TimeUnit.SECONDS) != null, "Reconnecting Ethernet did not restore discovery");
            browser.stop();
            await(() -> browser.diagnostics().contains("lan_discovery_state=stopped\n"), "Stopping reception did not retire discovery");
            check(browser.diagnostics().contains("lan_mdns_registered_interfaces=0\n"), "Stopped discovery still reports registered interfaces");
            browser.close();
            browser.close();
        }
        System.out.println("LanDiscoveryTopologyTests: late Ethernet, transient enumeration failure, removal, reconnect, stop and repeated close passed (controlled visibility, real mDNS)");
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, message);
            Thread.sleep(20);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package dev.ghost.wozai;

import java.util.UUID;
import java.util.concurrent.*;

/** Real multicast DNS registration and resolution using the Android DNS-SD type. */
public final class DiscoveryTests {
    public static void main(String[] args) throws Exception {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        BlockingQueue<LanDiscovery.Nearby> found = new LinkedBlockingQueue<>();
        try (var first = new LanDiscovery(); var second = new LanDiscovery()) {
            LanDiscovery.Listener events = new LanDiscovery.Listener() {
                public void found(LanDiscovery.Nearby peer) { if (peer.id().equals(b)) found.add(peer); }
                public void lost(String service) { }
                public void failed() { }
            };
            first.start(a, "Desktop", 12345, events);
            second.start(b, "手机 🙂", 23456, events);
            var peer = found.poll(20, TimeUnit.SECONDS);
            if (peer == null || !peer.name().equals("手机 🙂") || !peer.endpoint().endsWith(":23456"))
                throw new AssertionError("DNS-SD did not resolve UUID, Unicode name and LAN endpoint");
            System.out.println("DiscoveryTests: real mDNS registration and resolution passed");
        }
    }
}

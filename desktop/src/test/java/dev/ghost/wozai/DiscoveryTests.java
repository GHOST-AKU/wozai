package dev.ghost.wozai;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import dev.ghost.nearbyim.i18n.UiText;

/** Real mDNS discovery followed by encrypted consent, chat and saved receipts on LAN sockets. */
public final class DiscoveryTests {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wozai-discovery-chat");
        try (Endpoint a = new Endpoint(root.resolve("a"), "Wired Desktop");
             Endpoint b = new Endpoint(root.resolve("b"), "手机 🙂")) {
            a.discover(); b.discover();
            LanDiscovery.Nearby bFound = a.find(b.identity.id()), aFound = b.find(a.identity.id());
            check(bFound.name().equals("手机 🙂") && bFound.endpoint().endsWith(":" + b.listening.port()), "DNS-SD lost the Unicode name or listening port");
            connectAndExchange(a, b, bFound, true);
            a.client.disconnect().get(5, TimeUnit.SECONDS);
            a.await("idle"); b.await("idle");
            // Reverse the initiator on the same discovered addresses and remembered public keys.
            connectAndExchange(b, a, aFound, false);
            check(a.requests.isEmpty() && b.requests.isEmpty(), "Remembered LAN identities requested consent again");
            System.out.println("DiscoveryTests: bidirectional mDNS, discovered TCP endpoints, NIM4 consent, reverse reconnect and persisted message receipts passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void connectAndExchange(Endpoint sender, Endpoint receiver, LanDiscovery.Nearby target, boolean first) throws Exception {
        sender.states.clear(); receiver.states.clear();
        sender.client.connect(target.endpoint(), target.id()).get(5, TimeUnit.SECONDS);
        if (first) {
            DesktopClient.Request request = receiver.requests.poll(8, TimeUnit.SECONDS);
            check(request != null && request.peer().id().equals(sender.identity.id()), "Discovered address did not reach authenticated consent");
            check(receiver.store.peer(sender.identity.id()) == null, "mDNS metadata was trusted before consent");
            check(!sender.client.send("before consent").get(5, TimeUnit.SECONDS), "LAN sent text before receiver consent");
            receiver.client.approve(request, true).get(5, TimeUnit.SECONDS);
        }
        check(sender.await("ready").transport().equals("lan") && receiver.await("ready").transport().equals("lan"), "Discovered socket did not become a ready LAN session");
        String outgoing = "LAN message " + UUID.randomUUID(), reply = "有线回复 🙂 " + UUID.randomUUID();
        check(sender.client.send(outgoing).get(5, TimeUnit.SECONDS), "Discovered LAN could not send text");
        check(receiver.client.send(reply).get(5, TimeUnit.SECONDS), "Discovered LAN could not send a reply");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            var sent = sender.store.messages(receiver.identity.id());
            var received = receiver.store.messages(sender.identity.id());
            boolean complete = sent.stream().anyMatch(m -> m.outgoing() && m.body().equals(outgoing) && m.status().equals("delivered"))
                    && received.stream().anyMatch(m -> !m.outgoing() && m.body().equals(outgoing))
                    && received.stream().anyMatch(m -> m.outgoing() && m.body().equals(reply) && m.status().equals("delivered"))
                    && sent.stream().anyMatch(m -> !m.outgoing() && m.body().equals(reply));
            if (complete) return;
            check(System.nanoTime() < deadline, "LAN messages or saved receipts were missing");
            Thread.sleep(20);
        }
    }

    private static final class Endpoint implements AutoCloseable {
        final BlockingQueue<DesktopClient.State> states = new LinkedBlockingQueue<>();
        final BlockingQueue<DesktopClient.Request> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<LanDiscovery.Nearby> found = new LinkedBlockingQueue<>();
        final DesktopStore store;
        final DesktopIdentity.Identity identity;
        final DesktopClient client;
        final LanDiscovery discovery = new LanDiscovery();
        final DesktopClient.Listening listening;
        final String name;
        Endpoint(Path root, String name) throws Exception {
            this.name = name;
            store = new DesktopStore(root);
            identity = DesktopIdentity.load(root.resolve("identity.properties"));
            store.setSetting("nickname", name);
            client = new DesktopClient(store, identity, new DesktopClient.Listener() {
                public void changed(DesktopClient.State state) { states.add(state); }
                public void request(DesktopClient.Request request) { requests.add(request); }
                public void notice(UiText text) { }
            });
            listening = client.listen().get(5, TimeUnit.SECONDS);
        }
        void discover() {
            discovery.start(identity.id(), name, listening.port(), new LanDiscovery.Listener() {
                public void found(LanDiscovery.Nearby peer) { found.add(peer); }
                public void lost(String service) { }
                public void failed() { }
                public void networkChanged() { client.refresh(); }
            });
        }
        LanDiscovery.Nearby find(String id) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                LanDiscovery.Nearby peer = found.poll(1, TimeUnit.SECONDS);
                if (peer != null && peer.id().equals(id)) return peer;
            }
            throw new AssertionError("DNS-SD did not discover " + id);
        }
        DesktopClient.State await(String phase) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                DesktopClient.State state = states.poll(1, TimeUnit.SECONDS);
                if (state != null && state.phase().equals(phase)) return state;
            }
            throw new AssertionError("Discovered LAN did not reach " + phase);
        }
        public void close() throws Exception { discovery.close(); client.close(); store.close(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

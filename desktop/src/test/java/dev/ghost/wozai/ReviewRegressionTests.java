package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Review regressions exercise persistence and the actual signed session/receipt pipeline. */
public final class ReviewRegressionTests {
    private static final String PEER = "abcdef01-abcd-abcd-abcd-abcdef012345";
    private static int checks;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nearbyim-review-");
        try {
            String test = args.length == 0 ? "all" : args[0];
            if (test.equals("all") || test.equals("uuid")) uuidPaths(root.resolve("uuid"));
            if (test.equals("all") || test.equals("restart")) restart(root.resolve("restart"));
            for (String name : List.of("receipt", "conflict", "handshake", "direction", "failedPublish"))
                if (test.equals("all") || test.equals(name)) session(root.resolve(name), name);
            System.out.println("ReviewRegressionTests: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static void uuidPaths(Path root) throws Exception {
        String upper = PEER.toUpperCase(Locale.ROOT), id = UUID.randomUUID().toString();
        try (var store = new DesktopStore(root)) {
            store.peer(new DesktopStore.Peer(upper, "Phone", "pin", ""));
            check(store.peer(PEER).id().equals(PEER), "Uppercase UUID created another peer");
            store.save(upper, new DesktopStore.Message(id.toUpperCase(Locale.ROOT), "Body", 1, true, "pending"));
            store.save(PEER, new DesktopStore.Message(id, "Body", 1, true, "pending"));
            store.acknowledge(upper, id.toUpperCase(Locale.ROOT));
            check(store.messages(PEER).size() == 1 && store.messages(PEER).get(0).status().equals("delivered"), "UUID case broke deduplication or ACK");
            store.draft(upper, "Draft"); check(store.draft(PEER).equals("Draft"), "UUID case split drafts");
            check(Files.isRegularFile(root.resolve("peers").resolve(PEER + ".properties")), "Peer filename is not canonical");
            store.revoke(upper); check(store.peer(PEER).publicKey().isEmpty(), "UUID case broke revocation");
            store.clear(upper); check(store.messages(PEER).isEmpty(), "UUID case broke history clear");
            for (String invalid : List.of("../escape", "1-1-1-1-1", PEER + "/")) {
                try { store.messages(invalid); throw new AssertionError("Unsafe UUID accepted: " + invalid); }
                catch (IllegalArgumentException expected) { checks++; }
            }
        }
    }
    private static StreamConnection connection(Socket socket) { return new StreamConnection() {
        public InputStream input() throws IOException { return socket.getInputStream(); }
        public OutputStream output() throws IOException { return socket.getOutputStream(); }
        public String label() { return "LAN"; }
        public void close() throws IOException { socket.close(); }
    }; }
    private static void restart(Path root) throws Exception {
        String id = UUID.randomUUID().toString(), oldId = UUID.randomUUID().toString(), oldOutId = UUID.randomUUID().toString();
        DesktopStore.Message original;
        try (var store = new DesktopStore(root)) {
            store.peer(new DesktopStore.Peer(PEER, "Phone", "pin", ""));
            store.receive(PEER, id, "Saved", Long.MAX_VALUE); original = store.messages(PEER).get(0);
            Properties legacy = new Properties(); legacy.setProperty("body", "Old"); legacy.setProperty("time", "123"); legacy.setProperty("outgoing", "false"); legacy.setProperty("status", "received");
            AtomicFiles.write(root.resolve("messages").resolve(PEER).resolve(oldId + ".properties"), legacy);
            legacy.setProperty("outgoing", "true"); legacy.setProperty("status", "pending");
            AtomicFiles.write(root.resolve("messages").resolve(PEER).resolve(oldOutId + ".properties"), legacy);
        }
        try (var store = new DesktopStore(root)) {
            store.receive(PEER, id.toUpperCase(Locale.ROOT), "Saved", Long.MAX_VALUE);
            check(store.messages(PEER).stream().anyMatch(original::equals), "Restart lost receipt time or sender timestamp");
            store.receive(PEER, oldId, "Old", 123);
            check(store.messages(PEER).size() == 3, "Legacy message could not be replayed");
            store.save(PEER, new DesktopStore.Message(oldId, "Other direction", 124, true, "pending"));
            store.receive(PEER, oldOutId, "Other direction", 125);
            store.acknowledge(PEER, oldId); store.acknowledge(PEER, oldOutId);
            var messages = store.messages(PEER);
            check(messages.size() == 5, "Direction namespaces lost legacy or new messages");
            check(messages.stream().filter(m -> m.outgoing() && m.status().equals("delivered")).count() == 2, "ACK missed new or legacy outgoing namespace");
            check(messages.stream().filter(m -> !m.outgoing() && m.status().equals("received")).count() == 3, "ACK changed an incoming legacy message");
            check(Files.exists(root.resolve("messages").resolve(PEER).resolve(oldId + ".properties")) && Files.exists(root.resolve("messages").resolve(PEER).resolve(oldOutId + ".properties")), "Legacy compatibility moved the original files");
            for (var conflict : List.of(new DesktopStore.Message(id, "Saved", original.time() + 1, false, "received", Long.MAX_VALUE),
                    new DesktopStore.Message(id, "Saved", original.time(), false, "pending", Long.MAX_VALUE))) {
                try { store.save(PEER, conflict); throw new AssertionError("Conflicting metadata silently accepted"); }
                catch (IOException expected) { checks++; }
            }
            Files.writeString(root.resolve("messages").resolve(PEER).resolve("in-" + id + ".properties"), "broken");
            try { store.receive(PEER, id, "Saved", Long.MAX_VALUE); throw new AssertionError("Corrupt existing message accepted"); }
            catch (IOException expected) { checks++; }
        }
    }
    private static void session(Path root, String test) throws Exception {
        var requests = new LinkedBlockingQueue<DesktopClient.Request>();
        var acks = new LinkedBlockingQueue<String>();
        var remoteText = new LinkedBlockingQueue<Frame>();
        var state = new AtomicReference<DesktopClient.State>();
        CountDownLatch ready = new CountDownLatch(1), hello = new CountDownLatch(1), closed = new CountDownLatch(1);
        DeviceIdentity remoteIdentity = DeviceIdentity.generate();
        try (var store = new DesktopStore(root);
             var client = new DesktopClient(store, DesktopIdentity.load(root.resolve("identity.properties")), new DesktopClient.Listener() {
                 public void changed(DesktopClient.State next) { state.set(next); }
                 public void request(DesktopClient.Request request) { requests.add(request); }
                 public void notice(UiText text) { }
             });
             var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             var accepted = server.accept()) {
            boolean uppercase = test.equals("handshake");
            if (uppercase) store.peer(new DesktopStore.Peer(PEER, "Phone", remoteIdentity.publicKey(), ""));
            FramedSession remote = new FramedSession(connection(socket), uppercase ? PEER.toUpperCase(Locale.ROOT) : PEER, "Phone", remoteIdentity, new FramedSession.Listener() {
                public void onHello(Frame frame) { hello.countDown(); }
                public void onReady() { ready.countDown(); }
                public void onText(Frame frame) { remoteText.add(frame); }
                public void onAck(String id) { acks.add(id); }
                public void onClosed(UiText reason) { closed.countDown(); }
            });
            try {
                client.acceptConnection(connection(accepted), "").get(2, TimeUnit.SECONDS); remote.start();
                if (!uppercase) {
                    var request = requests.poll(3, TimeUnit.SECONDS);
                    check(request != null, "No consent request"); client.approve(request, true).get(2, TimeUnit.SECONDS);
                }
                check(hello.await(3, TimeUnit.SECONDS), "Valid uppercase signed HELLO was rejected");
                remote.approve(); check(ready.await(3, TimeUnit.SECONDS), "Session never ready");
                long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < readyDeadline && !state.get().phase().equals("ready")) Thread.sleep(10);
                check(state.get().phase().equals("ready"), "Desktop model never ready");
                check(requests.isEmpty(), "UUID case bypassed existing trust");
                if (test.equals("failedPublish")) {
                    Files.writeString(root.resolve("peers").resolve(PEER + ".properties"), "broken");
                    client.refresh().get(2, TimeUnit.SECONDS);
                    check(closed.await(3, TimeUnit.SECONDS), "Unreadable peer store did not close session");
                    awaitDisconnected(state);
                    check(state.get().history().size() == 1, "Fallback state lost the last readable history");
                    return;
                }
                if (test.equals("direction")) {
                    check(client.send("Outbound").get(2, TimeUnit.SECONDS), "Outbound message rejected");
                    Frame sent = remoteText.poll(3, TimeUnit.SECONDS); check(sent != null, "Remote did not observe outbound ID");
                    remote.acknowledge(sent.id);
                    check(remote.send(new Frame(Frame.TEXT, sent.id, "Inbound with same ID", 123)), "Opposite-direction message rejected");
                    check(sent.id.equals(acks.poll(3, TimeUnit.SECONDS)), "Opposite-direction collision was not acknowledged");
                    var pair = store.messages(PEER);
                    check(pair.size() == 2 && pair.stream().anyMatch(m -> m.outgoing() && m.status().equals("delivered")) && pair.stream().anyMatch(m -> !m.outgoing() && m.body().equals("Inbound with same ID")), "Direction collision lost content or changed the wrong receipt");
                    check(state.get().phase().equals("ready"), "Valid opposite-direction collision disconnected the UI");
                    return;
                }
                long before = System.currentTimeMillis();
                String id = UUID.randomUUID().toString();
                if (uppercase) id = id.toUpperCase(Locale.ROOT);
                Frame frame = new Frame(Frame.TEXT, id, "Original", Long.MAX_VALUE);
                check(remote.send(frame), "Original text rejected"); check(id.equals(acks.poll(3, TimeUnit.SECONDS)), "Missing saved-message ACK");
                var original = store.messages(PEER).get(0);
                if (test.equals("receipt")) {
                    check(original.time() >= before && original.time() <= System.currentTimeMillis(), "Incoming order uses sender-controlled timestamp");
                    // Later messages must evict the future-dated first frame from the 200-message window.
                    while (System.currentTimeMillis() <= original.time()) Thread.sleep(1);
                    for (int i = 0; i < 201; i++) {
                        String next = UUID.randomUUID().toString();
                        check(remote.send(new Frame(Frame.TEXT, next, "Later " + i, 0)), "Later text rejected");
                        check(next.equals(acks.poll(3, TimeUnit.SECONDS)), "Later receipt missing");
                    }
                    var history = store.messages(PEER);
                    String firstId = id;
                    check(history.size() == 200 && history.stream().noneMatch(m -> m.id().equals(firstId)), "Future timestamp pinned old message in history");
                    check(client.summaries().get(2, TimeUnit.SECONDS).get(PEER).body().startsWith("Later"), "Future timestamp pinned conversation summary");
                } else {
                    // A retransmission keeps its original local receipt time and remains ACK-able.
                    while (System.currentTimeMillis() <= original.time() && original.time() != Long.MAX_VALUE) Thread.sleep(1);
                    check(remote.send(frame), "Replay rejected"); check(id.equals(acks.poll(3, TimeUnit.SECONDS)), "Identical replay not acknowledged");
                    check(store.messages(PEER).size() == 1 && store.messages(PEER).get(0).equals(original), "Replay changed persisted receipt time");
                    check(remote.send(new Frame(Frame.TEXT, id, test.equals("conflict") ? "Changed" : "Original", test.equals("conflict") ? Long.MAX_VALUE : 0)), "Conflict not sent");
                    check(closed.await(3, TimeUnit.SECONDS), "Conflicting message did not terminate session");
                    check(acks.poll(200, TimeUnit.MILLISECONDS) == null, "Conflicting message was acknowledged");
                    check(store.messages(PEER).get(0).equals(original), "Conflict changed original content");
                    awaitDisconnected(state);
                }
            } finally { remote.close(UiText.EMPTY); }
        }
    }
    private static void awaitDisconnected(AtomicReference<DesktopClient.State> state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && !state.get().phase().equals("idle")) Thread.sleep(10);
        check(state.get().phase().equals("idle") && state.get().peer() == null, "Storage failure left published UI state connected");
    }
}

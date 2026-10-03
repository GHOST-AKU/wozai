package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;

import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** Tests desktop persistence and actual NIM2 sockets, without a display or Android SDK. */
public final class DesktopTests {
    private static int passed;
    private static final String PEER = "12345678-1234-1234-1234-123456789abc";
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); passed++; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wozai-desktop-test");
        try {
            identity(root.resolve("identity.properties"));
            defaultNicknames(root.resolve("nickname-defaults"));
            persistence(root.resolve("store"));
            interoperability(root.resolve("client"));
            failedSaveAndTemporaryConsent(root.resolve("failure"));
            revokeDuringHandshake(root.resolve("revoke"));
            translations();
            System.out.println("DesktopTests: " + passed + " checks passed");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void revokeDuringHandshake(Path path) throws Exception {
        InetAddress address = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && LocalEndpoint.isLocal(a)).findFirst().orElseThrow();
        var states = new LinkedBlockingQueue<DesktopClient.State>();
        DeviceIdentity remoteIdentity = DeviceIdentity.generate();
        try (var store = new DesktopStore(path); var server = new ServerSocket(0); var client = new DesktopClient(store, DesktopIdentity.load(path.resolve("identity.properties")), new DesktopClient.Listener() {
            public void changed(DesktopClient.State state) { states.add(state); }
            public void request(DesktopClient.Request request) { }
            public void notice(UiText text) { }
        })) {
            String target = DesktopClient.endpoint(address, server.getLocalPort());
            store.peer(new DesktopStore.Peer(PEER, "Phone", remoteIdentity.publicKey(), target));
            client.connect(target, PEER).get(2, TimeUnit.SECONDS);
            try (Socket accepted = server.accept()) {
                DesktopClient.State state;
                do { state = states.poll(2, TimeUnit.SECONDS); } while (state != null && !state.phase().equals("handshake"));
                check(state != null, "Outgoing socket did not begin authentication");
                client.revoke(PEER).get(2, TimeUnit.SECONDS);
                CountDownLatch closed = new CountDownLatch(1);
                FramedSession remote = new FramedSession(socket(accepted), PEER, "Phone", remoteIdentity, new FramedSession.Listener() {
                    public void onHello(Frame frame) { }
                    public void onReady() { }
                    public void onText(Frame frame) { }
                    public void onAck(String id) { }
                    public void onClosed(UiText reason) { closed.countDown(); }
                });
                try {
                    remote.start();
                    check(closed.await(2, TimeUnit.SECONDS), "Revoking during authentication did not cancel the outgoing connection");
                    check(store.peer(PEER).publicKey().isEmpty(), "In-flight reconnect restored revoked trust");
                } finally { remote.close(UiText.EMPTY); }
            }
        }
    }
    private static void translations() throws IOException {
        Properties zh = new Properties(), en = new Properties();
        try (var input = DesktopTests.class.getResourceAsStream("Strings_zh_Hans.properties")) { zh.load(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)); }
        try (var input = DesktopTests.class.getResourceAsStream("Strings_en.properties")) { en.load(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)); }
        check(zh.keySet().equals(en.keySet()), "Missing translation keys");
        check(new Strings("zh").text("app").equals("我在"), "Chinese fell back to host English locale");
        check(new Strings("en").text("requestBody", "Peer").contains("Peer"), "English placeholder lost");
        check(new Strings("zh").text("requestBody", "朋友").contains("朋友"), "Chinese placeholder lost");
    }
    private static DesktopClient.Listener silentListener() { return new DesktopClient.Listener() {
        public void changed(DesktopClient.State state) { }
        public void request(DesktopClient.Request request) { }
        public void notice(UiText text) { }
    }; }
    private static void defaultNicknames(Path root) throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.UK);
            Path legacy = root.resolve("legacy"); DesktopIdentity.Identity identity = DesktopIdentity.load(legacy.resolve("identity.properties"));
            try (DesktopStore store = new DesktopStore(legacy)) {
                store.setSetting("language", "en");
                try (DesktopClient client = new DesktopClient(store, identity, silentListener())) {
                    check(store.setting("nickname", "").equals("我在 Windows"), "Upgrade renamed an existing identity without a saved nickname");
                    check(store.setting("language", "").equals("en"), "Nickname migration changed the chosen language");
                }
            }
            Path fresh = root.resolve("fresh");
            try (DesktopStore store = new DesktopStore(fresh);
                 DesktopClient client = new DesktopClient(store, DesktopIdentity.load(fresh.resolve("identity.properties")), silentListener())) {
                check(store.setting("nickname", "").equals("NearbyIM Windows"), "Fresh profile did not persist the system-localized nickname before connecting");
                store.setSetting("language", "zh-Hans"); client.refresh().get(2, TimeUnit.SECONDS);
                check(store.setting("nickname", "").equals("NearbyIM Windows"), "Language change renamed a device");
            }
            try (DesktopStore store = new DesktopStore(fresh);
                 DesktopClient client = new DesktopClient(store, DesktopIdentity.load(fresh.resolve("identity.properties")), silentListener())) {
                check(store.setting("nickname", "").equals("NearbyIM Windows"), "Restart reinterpreted a persisted nickname as an old default");
                store.setSetting("nickname", "O'Brien 朋友");
            }
            try (DesktopStore store = new DesktopStore(fresh);
                 DesktopClient client = new DesktopClient(store, DesktopIdentity.load(fresh.resolve("identity.properties")), silentListener())) {
                check(store.setting("nickname", "").equals("O'Brien 朋友"), "Startup changed the owner's nickname");
            }
        } finally { Locale.setDefault(Locale.Category.DISPLAY, previous); }
    }
    private static void failedSaveAndTemporaryConsent(Path path) throws Exception {
        InetAddress address = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && LocalEndpoint.isLocal(a)).findFirst().orElseThrow();
        var requests = new LinkedBlockingQueue<DesktopClient.Request>();
        var acknowledgments = new LinkedBlockingQueue<String>();
        DeviceIdentity remoteIdentity = DeviceIdentity.generate();
        try (var store = new DesktopStore(path); var client = new DesktopClient(store, DesktopIdentity.load(path.resolve("identity.properties")), new DesktopClient.Listener() {
            public void changed(DesktopClient.State state) { }
            public void request(DesktopClient.Request request) { requests.add(request); }
            public void notice(UiText text) { }
        })) {
            int port = client.listen().get(3, TimeUnit.SECONDS).port();
            for (int round = 0; round < 3; round++) {
                CountDownLatch ready = new CountDownLatch(1), closed = new CountDownLatch(1), hello = new CountDownLatch(1);
                FramedSession remote = new FramedSession(socket(new Socket(address, port)), PEER, "Phone", remoteIdentity, new FramedSession.Listener() {
                    public void onHello(Frame frame) { hello.countDown(); }
                    public void onReady() { ready.countDown(); }
                    public void onText(Frame frame) { }
                    public void onAck(String id) { acknowledgments.add(id); }
                    public void onClosed(UiText reason) { closed.countDown(); }
                });
                try {
                    remote.start(); var request = requests.poll(3, TimeUnit.SECONDS);
                    check(request != null, "Temporary consent became permanent");
                    if (round == 1) {
                        client.reject(request).get(2, TimeUnit.SECONDS);
                        check(closed.await(2, TimeUnit.SECONDS), "Reject left the socket open");
                        check(store.peer(PEER) == null || store.peer(PEER).publicKey().isEmpty(), "Reject stored a trusted key");
                        continue;
                    }
                    client.approve(request, false).get(2, TimeUnit.SECONDS);
                    check(hello.await(2, TimeUnit.SECONDS), "Remote did not receive authenticated HELLO");
                    remote.approve(); check(ready.await(2, TimeUnit.SECONDS), "Temporary session did not become ready");
                    if (round == 2) {
                        Files.createDirectories(path.resolve("messages"));
                        Files.writeString(path.resolve("messages").resolve(PEER), "disk failure barrier");
                        String messageId = UUID.randomUUID().toString();
                        check(remote.send(new Frame(Frame.TEXT, messageId, "must not be acknowledged", 1)), "Failure probe send rejected");
                        check(closed.await(3, TimeUnit.SECONDS), "Persistence failure did not close connection");
                        check(acknowledgments.poll(100, TimeUnit.MILLISECONDS) == null, "Failed save produced delivery receipt");
                        Files.delete(path.resolve("messages").resolve(PEER));
                    }
                    client.disconnect().get(2, TimeUnit.SECONDS);
                } finally { remote.close(UiText.EMPTY); }
            }
        }
    }
    private static void identity(Path path) throws Exception {
        var first = DesktopIdentity.load(path);
        var second = DesktopIdentity.load(path);
        check(first.id().equals(second.id()), "Restart replaced device UUID");
        check(first.signer().publicKey().equals(second.signer().publicKey()), "Restart replaced public key");
        byte[] body = "restart identity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(first.signer().publicKey()))));
        verifier.update(body);
        check(verifier.verify(second.signer().sign(body)), "Persisted private key no longer signs");
        if (DesktopIdentity.windows()) {
            Properties encoded = new Properties(); try (var input = Files.newInputStream(path)) { encoded.load(input); }
            check("dpapi".equals(encoded.getProperty("protection")), "Windows key was not DPAPI protected");
        }
        Files.writeString(path, "broken identity");
        try { DesktopIdentity.load(path); throw new AssertionError("Corrupt identity silently regenerated"); }
        catch (IOException expected) { passed++; }
    }
    private static void persistence(Path path) throws Exception {
        String mid = UUID.randomUUID().toString();
        try (var store = new DesktopStore(path)) {
            store.peer(new DesktopStore.Peer(PEER, "朋友", "pin", "192.168.1.2:1234"));
            store.save(PEER, new DesktopStore.Message(mid, "你好\n🙂", 123, true, "pending"));
            store.save(PEER, new DesktopStore.Message(mid, "你好\n🙂", 123, true, "pending"));
            try { store.save(PEER, new DesktopStore.Message(mid, "duplicate", 124, true, "pending")); throw new AssertionError("Conflicting message silently accepted"); }
            catch (IOException expected) { passed++; }
            check(store.messages(PEER).size() == 1 && store.messages(PEER).get(0).body().equals("你好\n🙂"), "Message deduplication overwrote original");
            store.draft(PEER, "草稿");
            store.setSetting("language", "en");
            try (var duplicate = new DesktopStore(path)) { throw new AssertionError("Second process acquired data lock"); }
            catch (IOException expected) { passed++; }
        }
        try (var store = new DesktopStore(path)) {
            check(store.messages(PEER).get(0).status().equals("unknown"), "Restart claimed pending message delivered");
            check(store.draft(PEER).equals("草稿"), "Draft was lost");
            check(store.setting("language", "zh").equals("en"), "Language was lost");
            store.acknowledge(UUID.randomUUID().toString(), mid);
            check(store.messages(PEER).get(0).status().equals("unknown"), "Cross-conversation receipt changed message");
            store.acknowledge(PEER, mid);
            check(store.messages(PEER).get(0).status().equals("delivered"), "Receipt did not persist");
            store.clear(PEER);
            check(store.messages(PEER).isEmpty() && store.peer(PEER).publicKey().equals("pin"), "Clear also revoked trust");
            store.revoke(PEER);
            check(store.peer(PEER).publicKey().isEmpty(), "Revoke retained trusted key");
            try { store.messages("../escape"); throw new AssertionError("Path traversal accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
        }
    }
    private static StreamConnection socket(Socket s) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return s.getInputStream(); }
            public OutputStream output() throws IOException { return s.getOutputStream(); }
            public String label() { return "test"; }
            public void close() throws IOException { s.close(); }
        };
    }
    private static void interoperability(Path path) throws Exception {
        InetAddress address = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && LocalEndpoint.isLocal(a)).findFirst().orElseThrow();
        var requests = new LinkedBlockingQueue<DesktopClient.Request>();
        var notices = new LinkedBlockingQueue<String>();
        var remoteText = new LinkedBlockingQueue<Frame>();
        var remoteAck = new LinkedBlockingQueue<String>();
        DeviceIdentity androidIdentity = DeviceIdentity.generate();
        try (var store = new DesktopStore(path); var client = new DesktopClient(store, DesktopIdentity.load(path.resolve("identity.properties")), new DesktopClient.Listener() {
            public void changed(DesktopClient.State state) { }
            public void request(DesktopClient.Request request) { requests.add(request); }
            public void notice(UiText text) { notices.add(text.key); }
        })) {
            int port = client.listen().get(3, TimeUnit.SECONDS).port();
            // Uses exactly the same FramedSession as the Android transport.
            for (int round = 0; round < 3; round++) {
                CountDownLatch ready = new CountDownLatch(1), closed = new CountDownLatch(1);
                var key = round == 2 ? DeviceIdentity.generate() : androidIdentity;
                FramedSession android = new FramedSession(socket(new Socket(address, port)), PEER, "安卓", key, new FramedSession.Listener() {
                    public void onHello(Frame hello) { }
                    public void onReady() { ready.countDown(); }
                    public void onText(Frame frame) { remoteText.add(frame); }
                    public void onAck(String id) { remoteAck.add(id); }
                    public void onClosed(UiText reason) { closed.countDown(); }
                });
                try {
                    android.start();
                    if (round == 0) {
                        var request = requests.poll(3, TimeUnit.SECONDS);
                        check(request != null, "First-time authenticated peer did not request consent");
                        check(!client.send("too early").get(2, TimeUnit.SECONDS), "Text sent before consent");
                        client.approve(request, true).get(2, TimeUnit.SECONDS);
                    }
                    if (round == 2) {
                        check(closed.await(3, TimeUnit.SECONDS), "Changed key was not rejected");
                        check(requests.isEmpty(), "Changed key reached consent UI");
                        long noticeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (!notices.contains("identityChanged") && System.nanoTime() < noticeDeadline) Thread.sleep(10);
                        check(notices.contains("identityChanged"), "Changed-key warning missing");
                        continue;
                    }
                    // wait until Android receives HELLO before approving
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!android.isReady() && System.nanoTime() < deadline) {
                        android.approve();
                        if (ready.await(20, TimeUnit.MILLISECONDS)) break;
                    }
                    check(ready.getCount() == 0, "Android and desktop did not become ready");
                    if (round == 1) check(requests.isEmpty(), "Trusted reconnect asked again");
                    String incoming = UUID.randomUUID().toString();
                    check(android.send(new Frame(Frame.TEXT, incoming, "手机 → Windows 🙂", 1234)), "Android send rejected");
                    check(incoming.equals(remoteAck.poll(3, TimeUnit.SECONDS)), "No receipt after desktop persistence");
                    check(store.messages(PEER).stream().anyMatch(m -> m.id().equals(incoming)), "ACK preceded persistent message");
                    check(client.send("Windows → 手机\n你好").get(3, TimeUnit.SECONDS), "Desktop send rejected");
                    Frame received = remoteText.poll(3, TimeUnit.SECONDS);
                    check(received != null && received.body.equals("Windows → 手机\n你好"), "Desktop Unicode text lost");
                    android.acknowledge(received.id);
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (System.nanoTime() < deadline && store.messages(PEER).stream().noneMatch(m -> m.id().equals(received.id) && m.status().equals("delivered"))) Thread.sleep(10);
                    check(store.messages(PEER).stream().anyMatch(m -> m.id().equals(received.id) && m.status().equals("delivered")), "Desktop receipt not persisted");
                    client.disconnect().get(3, TimeUnit.SECONDS);
                } finally { android.close(UiText.EMPTY); }
            }
            client.stopListening().get(3, TimeUnit.SECONDS);
            try (var probe = new Socket(address, port)) { throw new AssertionError("Listener still accepted connections"); }
            // A closed local listener can refuse or reset the connect, depending
            // on the OS and whether its former accept thread has just exited.
            catch (SocketException expected) { passed++; }
        }
    }
}

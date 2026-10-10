package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Lab driver: one real desktop GUI, a separate signed TCP peer, disposable profiles. */
public final class PerformanceScenario {
    private static final String PAYLOAD = "中".repeat(64) + "x".repeat(64); // 256 UTF-8 bytes.
    private static final int RATE = 5; // Messages/second in EACH direction.
    private static Field field(String name) throws Exception { Field field = DesktopWindow.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        throw new IllegalStateException("Timeout: " + description);
    }
    private static void report(Path file, Map<String, String> values) throws Exception {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, PerformanceProbe.json(values));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
    private static boolean ready(DesktopWindow window) {
        AtomicBoolean result = new AtomicBoolean();
        try { SwingUtilities.invokeAndWait(() -> { try { var state = (DesktopClient.State)field("state").get(window); result.set(state != null && state.phase().equals("ready")); } catch (Exception failure) { throw new RuntimeException(failure); } }); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        return result.get();
    }
    private static void runMessages(DesktopClient client, AtomicBoolean active, AtomicBoolean done, AtomicReference<Throwable> failure) {
        Thread sender = new Thread(() -> {
            try { while (!done.get()) { if (active.get() && !client.send(PAYLOAD).get(5, TimeUnit.SECONDS)) throw new IllegalStateException("Benchmark send was rejected"); Thread.sleep(1000 / RATE); } }
            catch (Throwable error) { failure.set(error); }
        }, "nearbyim-performance-traffic");
        sender.setDaemon(true); sender.start();
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(directory);
        boolean peer = args[0].equals("peer");
        Path profile = directory.resolve(peer ? "peer-profile" : "target-profile");
        AtomicBoolean active = new AtomicBoolean(), done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DesktopStore store = new DesktopStore(profile); LanDiscovery discovery = new LanDiscovery()) {
            var identity = DesktopIdentity.load(profile.resolve("identity.properties"));
            if (peer) {
                AtomicReference<DesktopClient> reference = new AtomicReference<>();
                try (DesktopClient client = new DesktopClient(store, identity, new DesktopClient.Listener() {
                    public void changed(DesktopClient.State state) { }
                    public void request(DesktopClient.Request request) { reference.get().approve(request, true); }
                    public void notice(UiText text) { }
                })) {
                    reference.set(client); var listening = client.listen().get(5, TimeUnit.SECONDS);
                    InetAddress host = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                        .filter(i -> { try { return i.isUp() && !i.isLoopback(); } catch (SocketException e) { return false; } })
                        .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                        .filter(a -> a instanceof Inet4Address && dev.ghost.nearbyim.core.LocalEndpoint.isLocal(a)).findFirst().orElseThrow();
                    discovery.start(identity.id(), "Performance peer", listening.port(), new LanDiscovery.Listener() {
                        public void found(LanDiscovery.Nearby other) { } public void lost(String service) { } public void failed() { }
                    });
                    report(directory.resolve("peer.json"), Map.of("id", identity.id(), "endpoint", DesktopClient.endpoint(host, listening.port())));
                    runMessages(client, active, done, failure);
                    commands(directory, client, store, null, args.length > 2 ? args[2] : "", active, done, failure);
                }
            } else {
                AtomicReference<DesktopWindow> windowReference = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    try { AppTheme.install(false); var window = new DesktopWindow(store, identity, profile);
                        PerformanceProbe.install(window, directory.resolve("scenario-ready.json")); window.setVisible(true); windowReference.set(window); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                var window = windowReference.get(); var client = (DesktopClient)field("client").get(window);
                try {
                    long discoveryStart = System.nanoTime(); AtomicReference<Double> discoveryMs = new AtomicReference<>();
                    discovery.start(identity.id(), "Performance target", client.listen().get(5, TimeUnit.SECONDS).port(), new LanDiscovery.Listener() {
                        public void found(LanDiscovery.Nearby other) { if (other.id().equals(args[3])) discoveryMs.compareAndSet(null, (System.nanoTime() - discoveryStart) / 1e6); }
                        public void lost(String service) { } public void failed() { }
                    });
                    try { await(() -> discoveryMs.get() != null, "mDNS discovery"); } catch (IllegalStateException missingDiscovery) { /* Record missing discovery; direct TCP is still measured. */ }
                    long start = System.nanoTime(); client.connect(args[2], args[3]).get(5, TimeUnit.SECONDS); await(() -> ready(window), "signed connection and persisted trust");
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("phase", "connected"); values.put("connect_ms", Double.toString((System.nanoTime() - start) / 1e6));
                    values.put("discovery_ms", discoveryMs.get() == null ? "unavailable" : discoveryMs.get().toString());
                    values.put("payload_utf8_bytes", Integer.toString(PAYLOAD.getBytes(StandardCharsets.UTF_8).length)); values.put("messages_per_second_each_direction", Integer.toString(RATE));
                    report(directory.resolve("phase.json"), values);
                    runMessages(client, active, done, failure);
                    commands(directory, client, store, window, args[3], active, done, failure);
                } finally {
                    done.set(true); Files.writeString(directory.resolve("scenario-ready.json.stop"), "stop");
                    await(() -> !window.isDisplayable(), "normal GUI shutdown");
                }
            }
        } finally { done.set(true); }
    }
    private static List<Properties> allMessages(Path profile, String peerId) throws Exception {
        try (var files = Files.list(profile.resolve("messages").resolve(peerId))) {
            List<Properties> result = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".properties")).toList()) result.add(AtomicFiles.read(file));
            return result;
        }
    }
    private static void commands(Path directory, DesktopClient client, DesktopStore store, DesktopWindow window,
            String peerId, AtomicBoolean active, AtomicBoolean done, AtomicReference<Throwable> failure) throws Exception {
        String previous = ""; Path commandFile = directory.resolve("command.txt");
        while (!done.get()) {
            if (failure.get() != null) throw new RuntimeException(failure.get());
            String command = Files.exists(commandFile) ? Files.readString(commandFile).strip() : "";
            if(window!=null&&command.equals("connected")&&!ready(window))throw new IllegalStateException("Idle connected measurement lost its authenticated session");
            active.set(command.equals("active"));
            if (window != null && !command.equals(previous)) {
                if (command.equals("active")) report(directory.resolve("phase.json"), Map.of("phase", "active"));
                if (command.equals("reconnect")) {
                    active.set(false); client.disconnect().get(5, TimeUnit.SECONDS);
                    // Let the remote model finish handling EOF before reconnecting.
                    Thread.sleep(500); long start = System.nanoTime();
                    var saved = store.peer(peerId); client.connect(saved.endpoint(), peerId).get(5, TimeUnit.SECONDS); await(() -> ready(window), "trusted history reconnect");
                    report(directory.resolve("phase.json"), Map.of("phase", "reconnected", "reconnect_ms", Double.toString((System.nanoTime() - start) / 1e6)));
                }
                if (command.equals("report")) {
                    Thread.sleep(1000); // Drain the sender that observed the previous command.
                    await(() -> { try { var messages = allMessages(directory.resolve("target-profile"), peerId); return messages.stream().filter(m -> m.getProperty("outgoing").equals("true")).allMatch(m -> m.getProperty("status").equals("delivered")); } catch (Exception e) { return false; } }, "all outgoing persistence receipts");
                    var messages = allMessages(directory.resolve("target-profile"), peerId);
                    report(directory.resolve("traffic.json"), Map.of("sent", Long.toString(messages.stream().filter(m -> m.getProperty("outgoing").equals("true")).count()),
                        "received", Long.toString(messages.stream().filter(m -> m.getProperty("outgoing").equals("false")).count()),
                        "delivered", Long.toString(messages.stream().filter(m -> m.getProperty("outgoing").equals("true") && m.getProperty("status").equals("delivered")).count())));
                }
            }
            if (command.equals("stop")) done.set(true);
            previous = command; Thread.sleep(200);
        }
    }
}

package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Run by the native private-bus fixture: JNI descriptors feed the real signed chat/store pipeline. */
public final class LinuxBluetoothWireTests {
    private static int passed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) { passed++; return; } Thread.sleep(15); }
        throw new AssertionError(message);
    }
    private static final class Events implements DesktopClient.Listener {
        final BlockingQueue<DesktopClient.Request> requests = new LinkedBlockingQueue<>();
        final AtomicReference<DesktopClient.State> state = new AtomicReference<>();
        public void changed(DesktopClient.State value) { state.set(value); }
        public void request(DesktopClient.Request request) { requests.add(request); }
        public void notice(UiText text) { }
        boolean ready() { return state.get() != null && state.get().phase().equals("ready"); }
        boolean idle() { return state.get() != null && state.get().phase().equals("idle"); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nearbyim-bluez-wire-");
        Events incoming = new Events(), outgoing = new Events();
        try {
            var status = DesktopBluetooth.status(); check(status.supported() && status.available(), "BlueZ test adapter is not available");
            var devices = DesktopBluetooth.scan(1);
            check(devices.size() == 1 && devices.get(0).name().equals("手机 日本語 한국어 🙂"), "JNI discovery damaged Unicode");
            Path first = root.resolve("receiver"), second = root.resolve("sender");
            var receiverId = DesktopIdentity.load(first.resolve("identity.properties"));
            var senderId = DesktopIdentity.load(second.resolve("identity.properties"));
            try (var receiveStore = new DesktopStore(first); var sendStore = new DesktopStore(second);
                    var receiver = new DesktopClient(receiveStore, receiverId, incoming);
                    var sender = new DesktopClient(sendStore, senderId, outgoing)) {
                receiver.listenBluetooth().get(3, TimeUnit.SECONDS);
                sender.connect("bluetooth:AB:CD:01:23:45:67", receiverId.id()).get(3, TimeUnit.SECONDS);
                var consent = incoming.requests.poll(5, TimeUnit.SECONDS);
                check(consent != null && consent.publicKey().equals(senderId.signer().publicKey()), "Incoming Bluetooth skipped authenticated consent");
                check(receiveStore.peer(senderId.id()) == null, "System pairing created app trust before consent");
                receiver.approve(consent, true).get(3, TimeUnit.SECONDS);
                await(() -> incoming.ready() && outgoing.ready(), "BlueZ descriptors did not establish NIM2");
                check(incoming.state.get().transport().equals("bluetooth") && outgoing.state.get().transport().equals("bluetooth"), "Bluetooth route was not retained");
                check(receiveStore.peer(senderId.id()).publicKey().equals(senderId.signer().publicKey()), "Persistent trust lost verified key");
                check(sender.send("Linux → Android 协议 🙂\n日本語 한국어").get(3, TimeUnit.SECONDS), "Native Bluetooth send rejected");
                await(() -> { try { return receiveStore.messages(senderId.id()).size() == 1 && sendStore.messages(receiverId.id()).get(0).status().equals("delivered"); } catch (Exception e) { return false; } }, "Message not persisted before receipt");
                receiver.stopBluetoothListening().get(3, TimeUnit.SECONDS);
                check(receiver.send("停止接收后继续聊天").get(3, TimeUnit.SECONDS), "Stop reception broke active connection");
                await(() -> { try { return sendStore.messages(receiverId.id()).size() == 2; } catch (Exception e) { return false; } }, "Stopped receiver's established stream was closed");
                sender.disconnect().get(3, TimeUnit.SECONDS);
                await(() -> incoming.idle() && outgoing.idle(), "Disconnect retained active session");
                receiver.listenBluetooth().get(3, TimeUnit.SECONDS);
                sender.connect("bluetooth:AB:CD:01:23:45:67", receiverId.id()).get(3, TimeUnit.SECONDS);
                await(() -> incoming.ready() && outgoing.ready(), "Trusted Bluetooth history did not reconnect");
                check(incoming.requests.isEmpty(), "Trusted reconnect requested consent again");
                receiver.revoke(senderId.id()).get(3, TimeUnit.SECONDS);
                await(() -> incoming.idle() && outgoing.idle(), "Revoking trust did not close native Bluetooth");
                check(receiveStore.peer(senderId.id()).publicKey().isEmpty() && receiveStore.messages(senderId.id()).size() == 2, "Revoke deleted history or preserved trust");
            }
            System.out.println("LinuxBluetoothWireTests: " + passed + " checks passed (BlueZ D-Bus double, real JNI Unix descriptors, NIM2, persistence, ACK, reconnect and revoke; no physical radio)");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}

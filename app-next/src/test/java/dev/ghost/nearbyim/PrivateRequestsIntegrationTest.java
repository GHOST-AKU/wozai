package dev.ghost.nearbyim;

import android.content.Context;
import android.os.Looper;
import dev.ghost.nearbyim.core.DeviceIdentity;
import dev.ghost.nearbyim.core.Frame;
import dev.ghost.nearbyim.core.FramedSession;
import dev.ghost.nearbyim.core.StreamConnection;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.Peer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Tests the real Android controller at the socket-delivery boundary, with isolated test identities. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, shadows = {
        PrivateRequestsIntegrationTest.TestRoot.class, PrivateRequestsIntegrationTest.TestNoiseKey.class
})
public class PrivateRequestsIntegrationTest {
    private static final String LOCAL = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String REMOTE = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";
    private ChatController controller;
    private DeviceIdentity remoteIdentity;

    @Implements(value = AndroidIdentity.class, isInAndroidSdk = false)
    public static class TestRoot {
        @Implementation protected static DeviceIdentity load() throws GeneralSecurityException { return DeviceIdentity.generate(); }
    }
    @Implements(value = AndroidNoiseIdentity.class, isInAndroidSdk = false)
    public static class TestNoiseKey {
        @Implementation protected static byte[] load(Context context, DeviceIdentity root) { return randomKey(); }
    }

    @Before public void setUp() throws GeneralSecurityException {
        remoteIdentity = DeviceIdentity.generate();
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("id", LOCAL).commit();
        controller = new ChatController(context, () -> {});
        controller.lanRunning = true;
        assertTrue("The fixture must provide the local Android identity", controller.error.isEmpty());
    }

    @After public void tearDown() {
        if (controller != null) controller.destroy();
    }

    @Test public void requestsRequireConsentAndRememberedIncomingCallsDoNotOpenOtherChats() throws Exception {
        String firstToken;
        try (Remote first = connect(true, remoteIdentity)) {
            await(() -> controller.approvalId != null);
            firstToken = controller.approvalId;
            assertFalse(controller.connected);
            assertNull(controller.openedPrivateChat);
            controller.acceptPrivateRequest(firstToken, false);
            await(() -> controller.connected && first.wire.isReady());
            assertEquals(REMOTE, controller.selectedId);
            assertNotNull(controller.openedPrivateChat);
            assertFalse(controller.isTrusted(REMOTE));
            controller.disconnectPrivately();
            await(() -> "peerEnded".equals(first.closed.get()));
        }

        try (Remote next = connect(true, remoteIdentity)) {
            await(() -> controller.approvalId != null);
            String token = controller.approvalId;
            assertNotEquals(firstToken, token);
            controller.reject(firstToken);
            assertEquals(token, controller.approvalId);
            controller.acceptPrivateRequest(token, true);
            await(() -> controller.connected && next.wire.isReady() && controller.isTrusted(REMOTE));
            assertEquals(remoteIdentity.publicKey(), controller.rememberedRoot(REMOTE));
            controller.disconnectPrivately();
        }

        controller.selectConversation(new ChatStore.Conversation(OTHER, "Other contact", 0));
        String previousOpening = controller.openedPrivateChat;
        try (Remote remembered = connect(true, remoteIdentity)) {
            await(() -> controller.connected && remembered.wire.isReady());
            assertNull(controller.approvalId);
            assertEquals(OTHER, controller.selectedId);
            assertEquals(previousOpening, controller.openedPrivateChat);
        }
    }

    @Test public void explicitRequestCanOpenBeforeReadyAndAnUnconfirmedMessageHoldsTheConnection() throws Exception {
        beginAttempt(null, true);
        try (Remote remote = connect(false, remoteIdentity, false)) {
            await(() -> controller.openedPrivateChat != null);
            assertFalse(controller.connected);
            assertNotNull(controller.privateProof(REMOTE));
            assertEquals(remoteIdentity.publicKey(), controller.privateProof(REMOTE).root());
            assertFalse(controller.canSend());
            remote.wire.approve();
            await(() -> controller.connected && remote.wire.isReady());
            remote.acknowledgeText = false;
            assertTrue(controller.send("Hello from the private conversation"));
            await(() -> !remote.text.isEmpty());
            Frame received = remote.text.remove();
            assertEquals("Hello from the private conversation", received.body);
            assertFalse("An unconfirmed message must hold the current link", controller.canYieldPrivateSession());
            remote.wire.acknowledge(received.id);
            await(controller::canYieldPrivateSession);
            controller.disconnectPrivately();
            await(() -> "peerEnded".equals(remote.closed.get()));
        }
    }

    @Test public void decliningAnIncomingRequestSendsAnAuthenticatedEndBeforeReady() throws Exception {
        try (Remote rejected = connect(true, remoteIdentity)) {
            await(() -> controller.approvalId != null);
            assertFalse(rejected.wire.isReady());
            controller.reject(controller.approvalId);
            await(() -> rejected.closed.get() != null);
            assertEquals("peerEnded", rejected.closed.get());
            assertFalse(controller.connected);
            assertFalse(controller.isTrusted(REMOTE));
        }
    }

    @Test public void simultaneousRememberedCallYieldsOnlyToTheExpectedPinnedIdentity() throws Exception {
        try (Remote initial = connect(true, remoteIdentity)) {
            await(() -> controller.approvalId != null);
            controller.acceptPrivateRequest(controller.approvalId, true);
            await(() -> controller.connected && initial.wire.isReady() && controller.isTrusted(REMOTE));
            controller.disconnectPrivately();
        }
        controller.selectConversation(new ChatStore.Conversation(OTHER, "Other contact", 0));
        beginAttempt(remoteIdentity.publicKey(), false);
        try (Remote differentIdentity = connect(true, DeviceIdentity.generate())) {
            await(() -> differentIdentity.closed.get() != null && !controller.connecting);
            assertFalse(controller.connected);
            assertNull(controller.approvalId);
            assertEquals(remoteIdentity.publicKey(), controller.rememberedRoot(REMOTE));
        }

        beginAttempt(remoteIdentity.publicKey(), false);
        String previousOpening = controller.openedPrivateChat;
        try (Remote winner = connect(true, remoteIdentity)) {
            await(() -> controller.connected && winner.wire.isReady());
            assertEquals(REMOTE, controller.connectedPeerId);
            assertNull(controller.approvalId);
            assertEquals(OTHER, controller.selectedId);
            assertEquals(previousOpening, controller.openedPrivateChat);
        }
    }

    private void beginAttempt(String pin, boolean open) throws Exception {
        // The native route still performs its own DNS/RFCOMM work. Deliver the resulting sockets
        // in controlled order to exercise the production controller's attempt and consent state.
        Method begin = ChatController.class.getDeclaredMethod("beginConnect", int.class, String.class,
                boolean.class, String.class, String.class, boolean.class);
        begin.setAccessible(true);
        assertEquals(true, begin.invoke(controller, Peer.LAN, REMOTE, false, null, pin, open));
    }

    private Remote connect(boolean incoming, DeviceIdentity identity) throws Exception {
        return connect(incoming, identity, true);
    }

    private Remote connect(boolean incoming, DeviceIdentity identity, boolean approve) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            Socket remote = new Socket(loopback, server.getLocalPort());
            Socket local = server.accept();
            Remote peer = new Remote(remote, identity, incoming, approve);
            controller.onConnection(Peer.LAN, wrap(local), incoming);
            peer.wire.start();
            return peer;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 7_000_000_000L;
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for the private session transition");
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static StreamConnection wrap(Socket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "Isolated private chat test"; }
            public void close() throws IOException { socket.close(); }
        };
    }

    private static final class Remote implements FramedSession.Listener, AutoCloseable {
        final FramedSession wire;
        final boolean approve;
        final AtomicReference<String> closed = new AtomicReference<>();
        final LinkedBlockingQueue<Frame> text = new LinkedBlockingQueue<>();
        volatile boolean acknowledgeText = true;

        Remote(Socket socket, DeviceIdentity identity, boolean initiator, boolean approve) throws IOException {
            this.approve = approve;
            wire = FramedSession.secure(wrap(socket), REMOTE, "Private device", identity, randomKey(), initiator, this);
        }
        public void onHello(Frame hello) { if (approve) wire.approve(); }
        public void onReady() {}
        public void onAck(String id) {}
        public void onText(Frame frame) {
            text.add(frame);
            if (acknowledgeText) wire.acknowledge(frame.id);
        }
        public void onClosed(UiText reason) { closed.set(reason.key); }
        public void close() { wire.close(UiText.EMPTY); }
    }
}

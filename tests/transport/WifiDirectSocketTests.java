package dev.ghost.nearbyim.transport;

import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real localhost TCP only. This proves socket lifecycle, never native Wi-Fi radio readiness. */
public final class WifiDirectSocketTests {
    public static void main(String[] args) throws Exception {
        try (WifiDirectSockets owner = new WifiDirectSockets(); WifiDirectSockets client = new WifiDirectSockets()) {
            long go = owner.reset(), gc = client.reset();
            BlockingQueue<Integer> ports = new ArrayBlockingQueue<>(1);
            BlockingQueue<StreamConnection> accepted = new ArrayBlockingQueue<>(1), connected = new ArrayBlockingQueue<>(1);
            AtomicReference<Throwable> error = new AtomicReference<>(); AtomicBoolean sourceBinding = new AtomicBoolean();
            owner.listen(go, InetAddress.getByName("127.0.0.1"), 0, remote -> remote.getHostAddress().equals("127.0.0.2"), events(ports, accepted, true, error));
            Integer port = ports.poll(2, TimeUnit.SECONDS);
            truth(port != null, "GO must publish readiness only after binding an actual listening socket");
            client.connect(gc, InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"), port,
                    socket -> sourceBinding.set(!socket.isBound() && !socket.isConnected()), events(null, connected, false, error));
            StreamConnection responder = accepted.poll(2, TimeUnit.SECONDS), initiator = connected.poll(2, TimeUnit.SECONDS);
            truth(error.get() == null && responder != null && initiator != null, "Real local TCP must deliver GO responder and GC initiator: " + error.get());
            truth(sourceBinding.get(), "Network binding occurs before source binding and connect");
            initiator.output().write(41); initiator.output().flush(); equal(41, responder.input().read(), "GC can send to requested GO");
            responder.output().write(42); responder.output().flush(); equal(42, initiator.input().read(), "GO can send to passive GC");
            try (Socket extra = new Socket()) {
                extra.bind(new InetSocketAddress("127.0.0.2", 0)); extra.connect(new InetSocketAddress("127.0.0.1", port)); extra.setSoTimeout(2000);
                equal(-1, extra.getInputStream().read(), "Second session is refused while one is delivered");
            }
            owner.reset(); truth(!owner.current(go), "Group generation changes on loss");
            boolean closed = false;
            try { closed = initiator.input().read() == -1; } catch (SocketException peerClosed) { closed = true; }
            truth(closed, "Group loss closes the delivered stream so session/checkpoints react");
            initiator.close(); responder.close();
            client.connect(gc, InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"), port, null, events(null, connected, false, error));
            client.reset(); truth(connected.poll(200, TimeUnit.MILLISECONDS) == null, "Cancelled queued dial cannot publish a late connection");
        }
        bindingFailureClosesSocket();
        clientWaitsForDelayedOwnerListener();
        cancellationClosesPendingDialAndStopsReadinessRetries();
        System.out.println("Wi-Fi Direct real socket role, binding and group-loss checks passed");
    }
    private static void cancellationClosesPendingDialAndStopsReadinessRetries() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reserved.getLocalPort(); }
        try (WifiDirectSockets sockets = new WifiDirectSockets()) {
            long run = sockets.reset(); AtomicInteger bindings = new AtomicInteger(); AtomicReference<Socket> pending = new AtomicReference<>();
            CountDownLatch retryEntered = new CountDownLatch(1), release = new CountDownLatch(1), bindingDone = new CountDownLatch(1);
            BlockingQueue<StreamConnection> connected = new ArrayBlockingQueue<>(1); AtomicReference<Throwable> error = new AtomicReference<>();
            sockets.connect(run, InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"), port,
                    socket -> {
                        if (bindings.incrementAndGet() != 2) return;
                        pending.set(socket); retryEntered.countDown();
                        try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Test binding was not released"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                        finally { bindingDone.countDown(); }
                    }, events(null, connected, false, error));
            truth(retryEntered.await(2, TimeUnit.SECONDS), "Early refusal must enter a bounded readiness retry");
            sockets.reset(); truth(pending.get().isClosed(), "Cancel closes the live pending socket immediately"); release.countDown();
            truth(bindingDone.await(2, TimeUnit.SECONDS), "Cancelled worker can exit");
            truth(connected.poll(300, TimeUnit.MILLISECONDS) == null && error.get() == null, "Cancelled retry emits neither connection nor failure to a later run");
            equal(2, bindings.get(), "Cancel prevents any further TCP-ready attempts");
        }
    }
    private static void clientWaitsForDelayedOwnerListener() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reserved.getLocalPort(); }
        try (WifiDirectSockets owner = new WifiDirectSockets(); WifiDirectSockets client = new WifiDirectSockets()) {
            long go = owner.reset(), gc = client.reset();
            BlockingQueue<StreamConnection> accepted = new ArrayBlockingQueue<>(1), connected = new ArrayBlockingQueue<>(1);
            AtomicReference<Throwable> error = new AtomicReference<>(); AtomicInteger attempts = new AtomicInteger();
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            try {
                client.connect(gc, InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"), port,
                        socket -> { if (attempts.incrementAndGet() == 1) timer.schedule(() -> {
                            try { owner.listen(go, InetAddress.getByName("127.0.0.1"), port, remote -> true, events(null, accepted, true, error)); }
                            catch (UnknownHostException impossible) { error.set(impossible); }
                        }, 400, TimeUnit.MILLISECONDS); }, events(null, connected, false, error));
                StreamConnection initiator = connected.poll(3, TimeUnit.SECONDS), responder = accepted.poll(1, TimeUnit.SECONDS);
                truth(initiator != null && responder != null && error.get() == null, "GC must wait for actual GO TCP readiness instead of failing on an early refusal: " + error.get());
                truth(attempts.get() > 1, "Delayed real listener requires more than one dial");
                initiator.output().write(73); initiator.output().flush(); equal(73, responder.input().read(), "Delayed listener produces a working TCP stream");
                initiator.close(); responder.close();
            } finally { timer.shutdownNow(); }
        }
    }
    private static void bindingFailureClosesSocket() throws Exception {
        try (WifiDirectSockets sockets = new WifiDirectSockets()) {
            long run = sockets.reset(); AtomicReference<Socket> captured = new AtomicReference<>(); CountDownLatch failed = new CountDownLatch(1); AtomicInteger bindings = new AtomicInteger();
            sockets.connect(run, InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"), 1,
                    socket -> { bindings.incrementAndGet(); captured.set(socket); throw new ConnectException("Cannot bind P2P network"); }, new WifiDirectSockets.Events() {
                        public void onReady(int port) { throw new AssertionError("Dial cannot report listening"); }
                        public void onConnection(StreamConnection connection, boolean incoming) { throw new AssertionError("Failed route cannot deliver"); }
                        public void onFailure(Exception failure) { failed.countDown(); }
                    });
            truth(failed.await(2, TimeUnit.SECONDS), "Failed Network binding is reported");
            truth(captured.get() != null && captured.get().isClosed(), "Failed binding closes pending socket");
            equal(1, bindings.get(), "A binding failure is terminal even if its exception type is ConnectException");
        }
    }
    private static WifiDirectSockets.Events events(BlockingQueue<Integer> ports, BlockingQueue<StreamConnection> streams, boolean expectedIncoming, AtomicReference<Throwable> error) {
        return new WifiDirectSockets.Events() {
            public void onReady(int port) { if (ports != null) ports.offer(port); }
            public void onConnection(StreamConnection connection, boolean incoming) {
                if (incoming != expectedIncoming) error.set(new AssertionError("incoming must describe TCP role, independent of user intent"));
                streams.offer(connection);
            }
            public void onFailure(Exception failure) { error.set(failure); }
        };
    }
    private static void truth(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(int expected, int actual, String message) { if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual); }
}

package dev.ghost.nearbyim.transport;

import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Bounded TCP workers and one delivered session. No process-wide network binding. */
final class WifiDirectSockets implements AutoCloseable {
    interface Binding { void bind(Socket socket) throws IOException; }
    interface Events {
        void onReady(int port);
        void onConnection(StreamConnection connection, boolean incoming);
        void onFailure(Exception failure);
    }
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> { Thread thread = new Thread(runnable, "wozai-p2p-tcp"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private final Object lock = new Object();
    private long generation;
    private ServerSocket server;
    private Socket pending, delivered;
    long reset() {
        synchronized (lock) {
            generation++; close(server); server = null; close(pending); pending = null; close(delivered); delivered = null;
            workers.getQueue().clear(); return generation;
        }
    }
    boolean current(long token) { synchronized (lock) { return generation == token && !workers.isShutdown(); } }
    boolean hasDelivered() { synchronized (lock) { return delivered != null && !delivered.isClosed(); } }
    void listen(long token, InetAddress local, int port, Predicate<InetAddress> allowed, Events events) {
        execute(token, events, () -> {
            ServerSocket opened = new ServerSocket();
            try {
                synchronized (lock) { if (!current(token) || server != null) { close(opened); return; } server = opened; }
                opened.setReuseAddress(true); opened.bind(new InetSocketAddress(local, port), 1);
                if (current(token)) events.onReady(opened.getLocalPort());
                while (current(token)) {
                    Socket socket = opened.accept();
                    if (!allowed.test(socket.getInetAddress()) || !deliver(token, socket, true, events)) close(socket);
                }
            } finally { close(opened); synchronized (lock) { if (server == opened) server = null; } }
        });
    }
    void connect(long token, InetAddress local, InetAddress remote, int port, Binding binding, Events events) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        execute(token, events, () -> {
            while (current(token)) {
                Socket socket = new Socket(); boolean refused = false;
                synchronized (lock) { if (!current(token) || pending != null || hasDelivered()) { close(socket); return; } pending = socket; }
                try {
                    // Binding failures are terminal. Only connect() refusal while GO starts is retried.
                    if (binding != null) binding.bind(socket);
                    socket.bind(new InetSocketAddress(local, 0));
                    int remaining = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    try { socket.connect(new InetSocketAddress(remote, port), remaining); }
                    catch (ConnectException notListeningYet) {
                        if (System.nanoTime() >= deadline) throw notListeningYet;
                        refused = true;
                    }
                    if (!refused) { if (!deliver(token, socket, false, events)) close(socket); return; }
                } catch (IOException | RuntimeException failure) { close(socket); throw failure; }
                finally { if (refused) close(socket); synchronized (lock) { if (pending == socket) pending = null; } }
                if (!current(token)) return;
                try { Thread.sleep(Math.min(250, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("P2P dial interrupted"); }
            }
        });
    }
    private boolean deliver(long token, Socket socket, boolean incoming, Events events) throws IOException {
        socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        synchronized (lock) {
            if (!current(token) || hasDelivered()) return false;
            delivered = socket;
        }
        StreamConnection connection = new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "Wi-Fi Direct · " + socket.getInetAddress().getHostAddress(); }
            public void close() throws IOException { try { socket.close(); } finally { synchronized (lock) { if (delivered == socket) delivered = null; } } }
        };
        if (current(token)) events.onConnection(connection, incoming); else connection.close();
        return true;
    }
    private interface Operation { void run() throws IOException; }
    private void execute(long token, Events events, Operation operation) {
        if (!current(token)) return;
        try {
            workers.execute(() -> {
                if (!current(token)) return;
                try { operation.run(); } catch (IOException | RuntimeException failure) { if (current(token)) events.onFailure(failure); }
            });
        } catch (RejectedExecutionException busy) { if (current(token)) events.onFailure(busy); }
    }
    public void close() { reset(); workers.shutdownNow(); }
    private static void close(Closeable object) { if (object != null) try { object.close(); } catch (IOException ignored) {} }
}

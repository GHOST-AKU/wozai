package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FramedSession {
    public interface Listener {
        void onHello(Frame hello); void onReady(); void onText(Frame frame); void onAck(String id); void onClosed(String reason);
    }
    private final StreamConnection connection;
    private final Listener listener;
    private final AuthenticatedChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean(), notifiedReady = new AtomicBoolean();
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> daemon(r, "nearby-writer"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "nearby-timeout"));
    private volatile boolean greeted, approved, remoteReady;
    private volatile OutputStream output;
    private volatile long lastReadNanos = System.nanoTime();
    private final Map<String, Integer> receiving = new HashMap<>();
    private final Set<String> awaitingReceipts = new HashSet<>();
    private int unpersisted;

    public FramedSession(StreamConnection connection, String localId, String nickname, DeviceIdentity identity, Listener listener) {
        this.connection = Objects.requireNonNull(connection);
        this.listener = Objects.requireNonNull(listener);
        try { channel = new AuthenticatedChannel(identity, new Frame(Frame.HELLO, localId, nickname, System.currentTimeMillis())); }
        catch (IOException error) { throw new IllegalArgumentException("Invalid local identity", error); }
    }
    private static Thread daemon(Runnable runnable, String name) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }
    public void start() {
        if (!started.compareAndSet(false, true) || closed.get()) return;
        timer.schedule(() -> { if (!greeted) close("对方没有响应握手"); }, 15, TimeUnit.SECONDS);
        timer.schedule(() -> { if (!isReady()) close("连接确认超时"); }, 60, TimeUnit.SECONDS);
        enqueue(() -> { output = connection.output(); channel.writeOffer(output); });
        daemon(this::readLoop, "nearby-reader").start();
    }
    private void readLoop() {
        try {
            InputStream input = connection.input();
            channel.readOffer(input);
            if (!enqueue(() -> channel.writeProof(output))) return;
            channel.readProof(input);
            greeted = true; listener.onHello(channel.remoteHello());
            while (!closed.get()) {
                Frame frame = channel.read(input);
                lastReadNanos = System.nanoTime();
                switch (frame.type) {
                    case Frame.READY:
                        if (remoteReady) throw new IOException("Repeated approval");
                        remoteReady = true; maybeReady(); break;
                    case Frame.TEXT:
                        if (!isReady()) throw new IOException("Text before approval");
                        synchronized (receiving) {
                            if (unpersisted >= 32) throw new IOException("Too many unpersisted messages");
                            receiving.put(frame.id, receiving.getOrDefault(frame.id, 0) + 1); unpersisted++;
                        }
                        listener.onText(frame); break;
                    case Frame.ACK:
                        if (!isReady()) throw new IOException("Receipt before approval");
                        boolean known; synchronized (awaitingReceipts) { known = awaitingReceipts.remove(frame.id); }
                        if (known) listener.onAck(frame.id); break;
                    case Frame.BYE: close("对方结束了聊天"); return;
                    case Frame.PING:
                        if (!isReady()) throw new IOException("Heartbeat before approval");
                        enqueue(() -> channel.write(output, new Frame(Frame.PONG, "", "", System.currentTimeMillis()))); break;
                    case Frame.PONG:
                        if (!isReady()) throw new IOException("Heartbeat before approval"); break;
                    default: throw new IOException("Unexpected frame");
                }
            }
        } catch (IOException | RuntimeException error) { close("连接已断开，未收到回执的消息状态未知"); }
    }
    public void approve() {
        if (!greeted || approved || closed.get()) return;
        enqueue(() -> {
            if (approved) return;
            // Consent exists before the peer can observe READY. The single writer
            // still ensures any queued TEXT follows these READY bytes.
            approved = true;
            channel.write(output, new Frame(Frame.READY, "", "", System.currentTimeMillis()));
            maybeReady();
        });
    }
    private void maybeReady() {
        if (isReady() && notifiedReady.compareAndSet(false, true)) {
            lastReadNanos = System.nanoTime();
            timer.scheduleWithFixedDelay(() -> {
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastReadNanos) >= 20) close("对方失去响应，请重新连接");
                else enqueue(() -> channel.write(output, new Frame(Frame.PING, "", "", System.currentTimeMillis())));
            }, 5, 5, TimeUnit.SECONDS);
            listener.onReady();
        }
    }
    public boolean send(Frame frame) {
        if (!isReady() || frame.type != Frame.TEXT) return false;
        synchronized (awaitingReceipts) { if (awaitingReceipts.size() >= 32 || !awaitingReceipts.add(frame.id)) return false; }
        if (enqueue(() -> channel.write(output, frame))) return true;
        synchronized (awaitingReceipts) { awaitingReceipts.remove(frame.id); } return false;
    }
    public void acknowledge(String id) {
        if (!isReady()) return;
        synchronized (receiving) {
            Integer count = receiving.get(id); if (count == null) return;
            if (count == 1) receiving.remove(id); else receiving.put(id, count - 1); unpersisted--;
        }
        enqueue(() -> channel.write(output, new Frame(Frame.ACK, id, "", System.currentTimeMillis())));
    }
    private interface Write { void run() throws IOException; }
    private boolean enqueue(Write operation) {
        if (closed.get()) return false;
        try { writer.execute(() -> { if (!closed.get()) try { operation.run(); } catch (IOException | RuntimeException e) { close("消息发送中断"); } }); return true; }
        catch (RejectedExecutionException e) { close("消息处理队列已满，请重新连接"); return false; }
    }
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow(); writer.shutdownNow();
        try { connection.close(); } catch (IOException ignored) {}
        listener.onClosed(reason);
    }
    /** Available only after proof verification, including during onHello. */
    public String remotePublicKey() { return channel.remotePublicKey(); }
    public boolean isReady() { return greeted && approved && remoteReady && !closed.get(); }
}

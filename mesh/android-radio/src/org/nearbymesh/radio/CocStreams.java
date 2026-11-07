package org.nearbymesh.radio;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real stream lifecycle, independent of Android wrappers so host tests exercise ownership and IO. */
final class CocStreams implements AutoCloseable {
    interface Socket extends AutoCloseable {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void close();
    }
    interface Factory { Socket open() throws IOException; }
    interface Observer {
        void event(String event, String peer, String reason, long tx, long rx);
        void capacityChanged();
        void progress(Link link);
    }
    static final int CAPACITY = 4;
    static final long SETUP_MS = 15000, WRITE_MS = 5000;
    private final Object gate = new Object();
    private final Set<Link> slots = new HashSet<>();
    private final RadioConfig config;
    private final Observer observer;
    private final Executor executor;
    private final CocRuntime.Time time;
    private boolean closed;
    CocStreams(RadioConfig config, Observer observer) { this(config, observer, CocRuntime.IO, CocRuntime.TIME); }
    CocStreams(RadioConfig config, Observer observer, Executor executor, CocRuntime.Time time) {
        this.config = config; this.observer = observer; this.executor = executor; this.time = time;
    }
    int size() { synchronized (gate) { return slots.size(); } }
    boolean has(String label) { synchronized (gate) { for (Link l : slots) if (label.equals(l.peer)) return true; return false; } }
    private Link reserve(String expected) {
        synchronized (gate) {
            if (closed || slots.size() >= CAPACITY || (expected != null && has(expected))) return null;
            Link link = new Link(expected); slots.add(link); return link;
        }
    }
    boolean dial(String expected, Factory factory) {
        Link link = reserve(expected);
        if (link == null) return false;
        link.armSetup();
        observer.event("ble_connecting", expected, "le_coc", 0, 0);
        submit(link, () -> {
            Socket socket = factory.open();
            if (!link.attach(socket)) return;
            socket.connect(); link.run(socket);
        });
        return true;
    }
    boolean accept(Socket socket) {
        Link link = reserve(null);
        if (link == null) { socket.close(); return false; }
        if (!link.attach(socket)) return false;
        link.armSetup(); submit(link, () -> link.run(socket)); return true;
    }
    interface Job { void run() throws IOException; }
    private void submit(Link link, Job job) {
        try { executor.execute(() -> {
            try { if (!link.closed.get()) job.run(); }
            catch (SecurityException denied) { link.end("permission_denied"); }
            catch (IOException | RuntimeException failure) { link.end("socket_io"); }
            finally { link.end("socket_eof"); }
        }); } catch (RejectedExecutionException full) { link.end("worker_capacity"); }
    }
    void awaitCapacity() throws InterruptedException {
        synchronized (gate) { while (!closed && slots.size() >= CAPACITY) gate.wait(); }
    }
    @Override public void close() {
        List<Link> copy;
        synchronized (gate) { if (closed) return; closed = true; copy = new ArrayList<>(slots); gate.notifyAll(); }
        for (Link link : copy) link.end("stopped");
    }
    final class Link {
        private final Object owner = new Object();
        final AtomicBoolean closed = new AtomicBoolean(), progressPending = new AtomicBoolean();
        final AtomicLong tx = new AtomicLong(), rx = new AtomicLong();
        private final String expected;
        private final long deadline;
        volatile String peer;
        volatile String stage = "connecting";
        private Socket socket;
        private CoreBridge bridge;
        private CocRuntime.Ticket setup, write;
        private long operation;
        Link(String expected) { this.expected = expected; this.peer = expected == null ? "" : expected; deadline = time.now() + SETUP_MS; }
        void armSetup() {
            synchronized (owner) { if (!closed.get()) setup = time.after(deadline - time.now(), () -> end("setup_timeout")); }
        }
        boolean attach(Socket value) {
            synchronized (owner) { if (!closed.get()) { socket = value; return true; } }
            value.close(); return false;
        }
        private void checkDeadline() throws IOException {
            if (closed.get() || time.now() >= deadline) { end("setup_timeout"); throw new IOException("setup_timeout"); }
        }
        private void run(Socket current) throws IOException {
            checkDeadline(); stage = "preface"; InputStream input = current.input(); OutputStream output = current.output();
            writeBytes(output, () -> CocProtocol.writePreface(output, config.label));
            String remote = CocProtocol.readPreface(input); checkDeadline();
            synchronized (gate) {
                if (closed.get() || !config.accepts(remote) || (expected == null ? remote.compareTo(config.label) >= 0
                        : !expected.equals(remote) || config.label.compareTo(remote) >= 0)) throw new IOException("preface_rejected");
                for (Link other : slots) if (other != this && remote.equals(other.peer)) throw new IOException("duplicate");
                peer = remote;
            }
            stage = "ready";
            writeBytes(output, () -> output.write(CocProtocol.READY));
            byte[] ready = new byte[1]; CocProtocol.readExact(input, ready); checkDeadline();
            if ((ready[0] & 255) != CocProtocol.READY) throw new IOException("ready_invalid");
            CoreBridge core = new CoreBridge(config.corePort, new CoreBridge.Sink() {
                public void send(byte[] bytes) throws IOException {
                    writeBytes(output, () -> output.write(bytes));
                    tx.addAndGet(bytes.length); observer.progress(Link.this);
                }
                public void close() { end("core_closed"); }
            }, () -> end("core_closed"));
            synchronized (owner) {
                if (closed.get() || time.now() >= deadline) throw new IOException("setup_timeout");
                if (setup != null) setup.cancel(); setup = null; bridge = core;
            }
            stage = "core";
            core.start();
            synchronized (owner) {
                if (closed.get()) throw new IOException("socket_closed");
                stage = "active";
                observer.event("ble_link_up", peer, "le_coc", 0, 0);
            }
            byte[] bytes = new byte[CoreBridge.MAX_CHUNK];
            while (!closed.get()) {
                int count = input.read(bytes);
                if (count < 0) return;
                if (count == 0) throw new IOException("socket_zero_read");
                if (!core.receive(bytes, 0, count)) { end("backpressure"); return; }
                rx.addAndGet(count); observer.progress(this);
            }
        }
        private void writeBytes(OutputStream output, Job job) throws IOException {
            final long id;
            synchronized (owner) {
                if (closed.get()) throw new IOException("socket_closed");
                id = ++operation;
                write = time.after(WRITE_MS, () -> finish("socket_write_timeout", id));
            }
            try { job.run(); }
            finally {
                synchronized (owner) { if (operation == id) { operation++; if (write != null) write.cancel(); write = null; } }
            }
            if (closed.get()) throw new IOException("socket_closed");
        }
        void end(String reason) { finish(reason, -1); }
        private void finish(String reason, long expectedOperation) {
            Socket owned; CoreBridge core;
            synchronized (owner) {
                if (expectedOperation != -1 && expectedOperation != operation) return;
                if (!closed.compareAndSet(false, true)) return;
                if (setup != null) setup.cancel(); if (write != null) write.cancel();
                owned = socket; socket = null; core = bridge; bridge = null;
            }
            synchronized (gate) { slots.remove(this); gate.notifyAll(); }
            // No ownership/slot lock is held across platform close or CoreBridge callbacks.
            if (owned != null) owned.close();
            if (core != null) core.close();
            observer.event("ble_failed_stage", peer, stage, tx.get(), rx.get());
            observer.event("ble_link_down", peer, reason, tx.get(), rx.get());
            observer.capacityChanged();
        }
    }
}

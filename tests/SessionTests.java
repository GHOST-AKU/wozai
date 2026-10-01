package dev.ghost.nearbyim.core;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public final class SessionTests {
    static final class Events implements FramedSession.Listener {
        final BlockingQueue<Frame> hello = new LinkedBlockingQueue<>(), text = new LinkedBlockingQueue<>();
        final BlockingQueue<String> ack = new LinkedBlockingQueue<>(), closed = new LinkedBlockingQueue<>();
        final CountDownLatch ready = new CountDownLatch(1);
        public void onHello(Frame frame) { hello.add(frame); }
        public void onReady() { ready.countDown(); }
        public void onText(Frame frame) { text.add(frame); }
        public void onAck(String id) { ack.add(id); }
        public void onClosed(String reason) { closed.add(reason); }
    }
    static StreamConnection connection(Socket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "test TCP"; }
            public void close() throws IOException { socket.close(); }
        };
    }
    static final class RawPeer {
        final Socket socket;
        final AuthenticatedChannel channel;
        RawPeer(Socket socket) throws Exception {
            this.socket = socket;
            channel = new AuthenticatedChannel(DeviceIdentity.generate(), new Frame(Frame.HELLO, CoreTests.B, "对方", 1));
        }
        void handshake() throws IOException {
            channel.readOffer(socket.getInputStream()); channel.writeOffer(socket.getOutputStream());
            channel.writeProof(socket.getOutputStream()); channel.readProof(socket.getInputStream());
        }
        void write(Frame frame) throws IOException { channel.write(socket.getOutputStream(), frame); }
        Frame read() throws IOException { return channel.read(socket.getInputStream()); }
    }
    static final class Pair implements AutoCloseable {
        final Events ea = new Events(), eb = new Events();
        final FramedSession a, b;
        Pair() throws Exception {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                a = new FramedSession(connection(client), CoreTests.A, "志豪", DeviceIdentity.generate(), ea);
                b = new FramedSession(connection(server.accept()), CoreTests.B, "朋友", DeviceIdentity.generate(), eb);
            }
            a.start(); b.start();
        }
        void approve() throws Exception {
            CoreTests.check(ea.hello.poll(2, TimeUnit.SECONDS) != null && eb.hello.poll(2, TimeUnit.SECONDS) != null, "Missing HELLO");
            a.approve(); b.approve();
            CoreTests.check(ea.ready.await(2, TimeUnit.SECONDS) && eb.ready.await(2, TimeUnit.SECONDS), "Approval did not connect");
        }
        public void close() { a.close("test complete"); b.close("test complete"); }
    }
    static void run() {
        CoreTests.test("Private numeric endpoints accepted, internet/hostname rejected", () -> {
            CoreTests.check(LocalEndpoint.parse("192.168.1.24:3456").port == 3456, "IPv4 failed");
            CoreTests.check(LocalEndpoint.parse("[fd00::1]:4567").address.getHostAddress().startsWith("fd00"), "IPv6 failed");
            CoreTests.rejects(() -> LocalEndpoint.parse("example.com:80"));
            CoreTests.rejects(() -> LocalEndpoint.parse("8.8.8.8:53"));
            CoreTests.rejects(() -> LocalEndpoint.parse("192.168.1.1:65536"));
            CoreTests.rejects(() -> LocalEndpoint.parse("192.168.999.1:1234"));
            CoreTests.rejects(() -> LocalEndpoint.parse("192.168.1.1:0"));
        });
        CoreTests.test("Real TCP exchanges text in both directions and explicit receipt", () -> {
            try (Pair pair = new Pair()) {
                pair.approve();
                CoreTests.check(pair.a.send(new Frame(Frame.TEXT, CoreTests.A, "你好 🕷️", 42)), "Send rejected");
                Frame received = pair.eb.text.poll(2, TimeUnit.SECONDS);
                CoreTests.check(received != null && received.body.equals("你好 🕷️"), "Text lost");
                CoreTests.check(pair.ea.ack.isEmpty(), "Receipt emitted before receiver requested it");
                pair.b.acknowledge(received.id);
                CoreTests.check(CoreTests.A.equals(pair.ea.ack.poll(2, TimeUnit.SECONDS)), "Receipt lost");
                pair.b.send(new Frame(Frame.TEXT, CoreTests.B, "收到！", 43));
                CoreTests.check(pair.ea.text.poll(2, TimeUnit.SECONDS).body.equals("收到！"), "Reverse send failed");
            }
        });
        CoreTests.test("One-sided approval cannot send; closing is idempotent", () -> {
            try (Pair pair = new Pair()) {
                CoreTests.check(pair.ea.hello.poll(2, TimeUnit.SECONDS) != null, "No greeting");
                pair.a.approve();
                CoreTests.check(!pair.a.send(new Frame(Frame.TEXT, CoreTests.A, "not allowed", 1)), "Premature send allowed");
                pair.a.close("rejected"); pair.a.close("again");
                CoreTests.check("rejected".equals(pair.ea.closed.poll(2, TimeUnit.SECONDS)) && pair.ea.closed.isEmpty(), "Multiple close events");
                CoreTests.check(pair.eb.closed.poll(2, TimeUnit.SECONDS) != null, "Disconnect not observed");
            }
        });
        CoreTests.test("Raw peer cannot inject text before approval", () -> {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000);
                Events events = new Events();
                FramedSession session = new FramedSession(connection(server.accept()), CoreTests.A, "测试", DeviceIdentity.generate(), events);
                try {
                    session.start(); RawPeer peer = new RawPeer(raw); peer.handshake();
                    peer.write(new Frame(Frame.TEXT, CoreTests.B, "injection", 1));
                    CoreTests.check(events.closed.poll(2, TimeUnit.SECONDS) != null && events.text.isEmpty(), "Premature text accepted");
                } finally { session.close("cleanup"); }
            }
        });
        CoreTests.test("Peer may send immediately after observing approval bytes", () -> {
            CountDownLatch releaseFlush = new CountDownLatch(1);
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000); Socket local = server.accept(); Events events = new Events();
                StreamConnection gated = new StreamConnection() {
                    final OutputStream output = new FilterOutputStream(local.getOutputStream()) {
                        int flushes;
                        public void flush() throws IOException {
                            super.flush();
                            if (++flushes == 3) try {
                                if (!releaseFlush.await(2, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        }
                    };
                    public InputStream input() throws IOException { return local.getInputStream(); }
                    public OutputStream output() { return output; }
                    public String label() { return "gated TCP"; }
                    public void close() throws IOException { releaseFlush.countDown(); local.close(); }
                };
                FramedSession session = new FramedSession(gated, CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start(); RawPeer peer = new RawPeer(raw); peer.handshake();
                    peer.write(new Frame(Frame.READY, "", "", 1));
                    CoreTests.check(events.hello.poll(2, TimeUnit.SECONDS) != null, "No greeting"); session.approve();
                    CoreTests.check(peer.read().type == Frame.READY, "No local approval");
                    peer.write(new Frame(Frame.TEXT, CoreTests.B, "immediate", 1));
                    Frame received = events.text.poll(1, TimeUnit.SECONDS);
                    CoreTests.check(received != null && received.body.equals("immediate"), "Valid immediate message rejected");
                } finally { releaseFlush.countDown(); session.close("cleanup"); }
            }
        });
        CoreTests.test("Unpersisted incoming burst is bounded", () -> {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000); Events events = new Events();
                FramedSession session = new FramedSession(connection(server.accept()), CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start(); RawPeer peer = new RawPeer(raw); peer.handshake();
                    peer.write(new Frame(Frame.READY, "", "", 1));
                    CoreTests.check(events.hello.poll(2, TimeUnit.SECONDS) != null, "No greeting"); session.approve();
                    peer.read(); CoreTests.check(events.ready.await(2, TimeUnit.SECONDS), "Not connected");
                    for (int i = 0; i < 33; i++) peer.write(new Frame(Frame.TEXT, java.util.UUID.randomUUID().toString(), "burst", 1));
                    CoreTests.check(events.closed.poll(2, TimeUnit.SECONDS) != null && events.text.size() == 32, "Incoming queue is unbounded");
                } finally { session.close("cleanup"); }
            }
        });
        CoreTests.test("Unknown receipts do not reach message storage", () -> {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000); Events events = new Events();
                FramedSession session = new FramedSession(connection(server.accept()), CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start(); RawPeer peer = new RawPeer(raw); peer.handshake();
                    peer.write(new Frame(Frame.READY, "", "", 1));
                    CoreTests.check(events.hello.poll(2, TimeUnit.SECONDS) != null, "No greeting"); session.approve();
                    peer.read(); CoreTests.check(events.ready.await(2, TimeUnit.SECONDS), "Not connected");
                    peer.write(new Frame(Frame.ACK, CoreTests.B, "", 1));
                    CoreTests.check(events.ack.poll(200, TimeUnit.MILLISECONDS) == null, "Unknown receipt accepted");
                } finally { session.close("cleanup"); }
            }
        });
        CoreTests.test("Slow authenticated writer has a bounded 64-operation queue", () -> {
            CountDownLatch releaseFlush = new CountDownLatch(1);
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000); Socket local = server.accept(); Events events = new Events();
                StreamConnection gated = new StreamConnection() {
                    final OutputStream output = new FilterOutputStream(local.getOutputStream()) {
                        int flushes;
                        public void flush() throws IOException {
                            super.flush();
                            if (++flushes == 4) try {
                                if (!releaseFlush.await(3, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        }
                    };
                    public InputStream input() throws IOException { return local.getInputStream(); }
                    public OutputStream output() { return output; }
                    public String label() { return "slow TCP"; }
                    public void close() throws IOException { releaseFlush.countDown(); local.close(); }
                };
                FramedSession session = new FramedSession(gated, CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start(); RawPeer peer = new RawPeer(raw); peer.handshake();
                    peer.write(new Frame(Frame.READY, "", "", 1));
                    CoreTests.check(events.hello.poll(2, TimeUnit.SECONDS) != null, "No greeting"); session.approve();
                    CoreTests.check(peer.read().type == Frame.READY && events.ready.await(2, TimeUnit.SECONDS), "Not connected");
                    peer.write(new Frame(Frame.PING, "", "", 1));
                    CoreTests.check(peer.read().type == Frame.PONG, "Writer did not reach gate");
                    for (int i = 0; i < 65; i++) peer.write(new Frame(Frame.PING, "", "", 1));
                    String reason = events.closed.poll(2, TimeUnit.SECONDS);
                    CoreTests.check(reason != null && reason.contains("队列已满"), "Writer queue accepted an unbounded burst: " + reason);
                } finally { releaseFlush.countDown(); session.close("cleanup"); }
            }
        });
        CoreTests.test("Outgoing receipt window is bounded and receipt releases a slot", () -> {
            try (Pair pair = new Pair()) {
                pair.approve();
                for (int i = 0; i < 32; i++) CoreTests.check(pair.a.send(new Frame(Frame.TEXT, java.util.UUID.randomUUID().toString(), "bounded", 1)), "Window too small");
                Frame next = new Frame(Frame.TEXT, CoreTests.B, "after receipt", 2);
                CoreTests.check(!pair.a.send(next), "Unbounded outstanding receipts");
                Frame received = pair.eb.text.poll(2, TimeUnit.SECONDS); CoreTests.check(received != null, "No text");
                pair.b.acknowledge(received.id); CoreTests.check(received.id.equals(pair.ea.ack.poll(2, TimeUnit.SECONDS)), "No receipt");
                CoreTests.check(pair.a.send(next), "Receipt did not release capacity");
            }
        });
    }
}

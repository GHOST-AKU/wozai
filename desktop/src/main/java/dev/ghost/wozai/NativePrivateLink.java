package dev.ghost.wozai;

import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.HexFormat;

/** One-use, authenticated loopback carrier for a native BLE adapter in the owning UI. */
public final class NativePrivateLink implements AutoCloseable {
    private final ServerSocket listener;
    private final byte[] token = new byte[32];
    private volatile Socket pending;
    private volatile boolean closed;

    public NativePrivateLink() throws IOException {
        new SecureRandom().nextBytes(token);
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        listener.setSoTimeout(15_000);
    }
    public int port() { return listener.getLocalPort(); }
    public String token() { return HexFormat.of().formatHex(token); }

    public StreamConnection accept() throws IOException {
        Socket socket = listener.accept(); pending = socket;
        try {
            if (closed || !socket.getInetAddress().isLoopbackAddress()) throw new IOException("Native link closed");
            socket.setSoTimeout(3_000); socket.setTcpNoDelay(true);
            byte[] supplied = socket.getInputStream().readNBytes(32);
            if (!MessageDigest.isEqual(token, supplied)) throw new IOException("Invalid native link token");
            socket.setSoTimeout(0);
            synchronized (this) {
                if (closed) throw new IOException("Native link closed");
                pending = null; listener.close();
            }
            return new StreamConnection() {
                public InputStream input() throws IOException { return socket.getInputStream(); }
                public OutputStream output() throws IOException { return socket.getOutputStream(); }
                public String label() { return "Bluetooth"; }
                public void close() throws IOException { socket.close(); }
                // No IP path: LAN/file-route discovery must not mistake this IPC socket for Wi-Fi.
            };
        } catch (IOException error) { socket.close(); throw error; }
    }
    @Override public synchronized void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        if (pending != null) try { pending.close(); } catch (IOException ignored) { }
        pending = null;
    }
}

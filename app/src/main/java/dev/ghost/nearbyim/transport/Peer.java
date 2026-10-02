package dev.ghost.nearbyim.transport;

import java.net.InetAddress;

public final class Peer implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final int LAN = 1, BLUETOOTH = 2;
    public final int mode;
    public final String key, name, detail, bluetoothAddress, peerId;
    public final InetAddress host;
    public final int port;
    public final boolean paired;
    public Peer(int mode, String key, String name, String detail, InetAddress host, int port, String bluetoothAddress) {
        this(mode, key, name, detail, host, port, bluetoothAddress, null);
    }
    /** peerId is a discovery hint; only the signed handshake proves identity. */
    public Peer(int mode, String key, String name, String detail, InetAddress host, int port, String bluetoothAddress, String peerId) {
        this(mode, key, name, detail, host, port, bluetoothAddress, peerId, false);
    }
    public Peer(int mode, String key, String name, String detail, InetAddress host, int port, String bluetoothAddress, String peerId, boolean paired) {
        this.paired = paired; this.peerId = peerId; this.mode = mode; this.key = key; this.name = name; this.detail = detail;
        this.host = host; this.port = port; this.bluetoothAddress = bluetoothAddress;
    }
}

package dev.ghost.nearbyim.transport;

import java.net.InetAddress;

public final class Peer implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final int LAN = 1, BLUETOOTH = 2, WIFI_DIRECT = 3;
    public final int mode;
    public final String key, name, detail, bluetoothAddress, peerId;
    /** Ephemeral discovery/connection hint, never a persisted identity or trust key. */
    public final String wifiDirectAddress;
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
        this(mode, key, name, detail, host, port, bluetoothAddress, peerId, paired, null);
    }
    private Peer(int mode, String key, String name, String detail, InetAddress host, int port, String bluetoothAddress, String peerId, boolean paired, String wifiDirectAddress) {
        this.paired = paired; this.peerId = peerId; this.mode = mode; this.key = key; this.name = name; this.detail = detail;
        this.host = host; this.port = port; this.bluetoothAddress = bluetoothAddress; this.wifiDirectAddress = wifiDirectAddress;
    }
    public static Peer wifiDirect(String peerId, String name, String deviceAddress) {
        return new Peer(WIFI_DIRECT, "p2p:" + peerId, name, deviceAddress, null, 39741, null, peerId, false, deviceAddress);
    }
}

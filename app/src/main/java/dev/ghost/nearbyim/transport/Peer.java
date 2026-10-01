package dev.ghost.nearbyim.transport;

import java.net.InetAddress;

public final class Peer implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final int LAN = 1, BLUETOOTH = 2;
    public final int mode;
    public final String key, name, detail, bluetoothAddress;
    public final InetAddress host;
    public final int port;
    public Peer(int mode, String key, String name, String detail, InetAddress host, int port, String bluetoothAddress) {
        this.mode = mode; this.key = key; this.name = name; this.detail = detail;
        this.host = host; this.port = port; this.bluetoothAddress = bluetoothAddress;
    }
}

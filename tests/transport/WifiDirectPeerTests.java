package dev.ghost.nearbyim.transport;

public final class WifiDirectPeerTests {
    public static void main(String[] args) throws Exception {
        int mode;
        try { mode = Peer.class.getField("WIFI_DIRECT").getInt(null); }
        catch (NoSuchFieldException missing) { throw new AssertionError("Wi-Fi Direct must have its own mode instead of claiming LAN", missing); }
        equal(3, mode, "Dedicated transport mode");
        Peer direct;
        try { direct = (Peer) Peer.class.getMethod("wifiDirect", String.class, String.class, String.class)
                .invoke(null, "63344c17-0b20-4050-8434-edee90357785", "Pad", "02:11:22:33:44:55"); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Direct discovery must retain UUID separately from its ephemeral address", missing); }
        equal(mode, direct.mode, "Factory mode");
        equal("63344c17-0b20-4050-8434-edee90357785", direct.peerId, "Discovery UUID hint");
        equal("p2p:" + direct.peerId, direct.key, "Address rotation cannot change the discovered identity hint");
        equal("02:11:22:33:44:55", Peer.class.getField("wifiDirectAddress").get(direct), "Current address hint");
        equal(null, direct.bluetoothAddress, "Direct address cannot be remembered as Bluetooth");
        equal(null, direct.host, "Service discovery is not an IP endpoint");
        equal(39741, direct.port, "Protocol TCP port");
        Peer old = new Peer(Peer.LAN, "lan:old", "Old", "", null, 12, null);
        equal(null, Peer.class.getField("wifiDirectAddress").get(old), "Old constructors remain compatible");
        System.out.println("Wi-Fi Direct peer checks passed");
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}

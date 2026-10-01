package dev.ghost.nearbyim.transport;
import dev.ghost.nearbyim.core.StreamConnection;

/** Implementations deliver all callbacks on the Android main thread. */
public interface TransportListener {
    void onPeer(Peer peer);
    void onLost(String key);
    default void onSearching(int mode, boolean searching) {}
    /** Stopping discovery failed; this does not imply a new connection attempt failed. */
    default void onSearchStopFailed(int mode, String message) {}
    void onListening(int mode, String detail);
    void onConnection(int mode, StreamConnection connection, boolean incoming);
    /** Actual RFCOMM remote address, independent of discovery labels or names. */
    default void onConnection(int mode, StreamConnection connection, boolean incoming, String bluetoothAddress) {
        onConnection(mode, connection, incoming);
    }
    void onError(int mode, String message, boolean fatal);
    void onConnectFailed(int mode, String message);
}

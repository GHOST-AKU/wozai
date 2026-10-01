package dev.ghost.nearbyim.transport;
import dev.ghost.nearbyim.core.StreamConnection;

/** Implementations deliver all callbacks on the Android main thread. */
public interface TransportListener {
    void onPeer(Peer peer);
    void onLost(String key);
    void onListening(int mode, String detail);
    void onConnection(int mode, StreamConnection connection, boolean incoming);
    void onError(int mode, String message, boolean fatal);
    void onConnectFailed(int mode, String message);
}

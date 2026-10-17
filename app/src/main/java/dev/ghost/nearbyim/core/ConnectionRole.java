package dev.ghost.nearbyim.core;

/** User consent follows their selected peer; Noise roles follow TCP direction. */
public enum ConnectionRole {
    DISCARD, USER_REQUESTED, PEER_REQUESTED;

    public boolean userRequested() { return this==USER_REQUESTED; }

    public static ConnectionRole decide(boolean wifiDirect,boolean tcpIncoming,boolean requestedAttempt,boolean matchingMode) {
        if(requestedAttempt&&!matchingMode)return DISCARD;
        if(wifiDirect)return requestedAttempt?USER_REQUESTED:PEER_REQUESTED;
        if(tcpIncoming)return requestedAttempt?DISCARD:PEER_REQUESTED;
        return requestedAttempt?USER_REQUESTED:DISCARD;
    }
}

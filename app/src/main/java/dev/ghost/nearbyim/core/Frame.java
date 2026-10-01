package dev.ghost.nearbyim.core;

public final class Frame {
    public static final int HELLO = 1, READY = 2, TEXT = 3, ACK = 4, BYE = 5, PING = 6, PONG = 7;
    public final int type;
    public final String id;
    public final String body;
    public final long timestamp;
    public Frame(int type, String id, String body, long timestamp) {
        this.type = type; this.id = id; this.body = body; this.timestamp = timestamp;
    }
}

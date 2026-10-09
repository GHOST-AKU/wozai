package dev.ghost.nearbyim.core;

public final class Frame {
    public static final int HELLO = 1, READY = 2, TEXT = 3, ACK = 4, BYE = 5, PING = 6, PONG = 7;
    public static final int FILE_OFFER=8, FILE_ACCEPT=9, FILE_REJECT=10, FILE_CHUNK=11, FILE_PROGRESS=12, FILE_FINISH=13, FILE_RECEIPT=14, FILE_CANCEL=15;
    public static final int ATTACHMENT_V2=16;
    public final long offset;
    public final byte[] data;
    public final int type;
    public final String id;
    public final String body;
    public final long timestamp;
    public Frame(int type, String id, String body, long timestamp) {
        this(type,id,body,timestamp,0,new byte[0]);
    }
    public Frame(int type,String id,String body,long timestamp,long offset,byte[] data) {
        this.type=type;this.id=id;this.body=body;this.timestamp=timestamp;this.offset=offset;this.data=data.clone();
    }
}

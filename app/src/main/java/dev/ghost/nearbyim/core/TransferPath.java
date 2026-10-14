package dev.ghost.nearbyim.core;

/** A current data channel, deliberately independent of discovery and guessed IP topology. */
public enum TransferPath {
    NONE(""),
    LAN_UNCONFIRMED("attachmentPathLanUnconfirmed"),
    WIFI_DIRECT("attachmentPathWifiDirect"),
    BLUETOOTH("attachmentPathBluetooth"),
    PENDING_LAN("attachmentPathPendingLan"),
    PENDING_WIFI_DIRECT("attachmentPathPendingWifiDirect"),
    PENDING_BLUETOOTH("attachmentPathPendingBluetooth"),
    DISCONNECTED("attachmentPathDisconnected"),
    UNKNOWN("attachmentPathUnknown");

    private final String key;
    TransferPath(String key) { this.key=key; }
    public String key() { return key; }

    /** ready must belong to this record's peer and the currently approved session. */
    public static TransferPath forAttachment(String transport,boolean ready,AttachmentRecord record) {
        if(record==null||!record.active()&&!record.resumable())return NONE;
        if(!ready)return DISCONNECTED;
        boolean waiting=record.state.equals("queued")||record.state.equals("preparing")||record.state.equals("offered");
        if("lan".equals(transport))return waiting?PENDING_LAN:LAN_UNCONFIRMED;
        if("wifi_direct".equals(transport))return waiting?PENDING_WIFI_DIRECT:WIFI_DIRECT;
        if("bluetooth".equals(transport))return waiting?PENDING_BLUETOOTH:BLUETOOTH;
        return UNKNOWN;
    }
}

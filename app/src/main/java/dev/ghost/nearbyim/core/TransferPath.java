package dev.ghost.nearbyim.core;

/** A current data channel, deliberately independent of discovery and guessed IP topology. */
public enum TransferPath {
    NONE(""),
    SELECTING("attachmentPathSelecting"),
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

    /** Legacy arguments are ignored: file routes are persisted independently of chat. */
    public static TransferPath forAttachment(String transport,boolean ready,AttachmentRecord record) {
        if(record==null)return NONE;
        if("lan".equals(record.route))return LAN_UNCONFIRMED;
        if("wifi-direct".equals(record.route))return WIFI_DIRECT;
        if("bluetooth".equals(record.route))return BLUETOOTH;
        if(record.state.equals("queued")||record.state.equals("preparing")||record.state.equals("offered"))return SELECTING;
        return UNKNOWN;
    }
}

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
    UNKNOWN("attachmentPathUnknown"),
    RECOVERING_LAN("fileRecoveringLan"),COMPARING("fileComparingLinks"),
    DIRECT_LAN("attachmentPathDirectLan"),DIRECT_BT("attachmentPathDirectBluetooth"),LAN_BT("attachmentPathLanBluetooth"),
    LAN_DIRECT("attachmentPathLanDirect"),BT_DIRECT("attachmentPathBluetoothDirect"),BT_LAN("attachmentPathBluetoothLan"),ALL_LINKS("attachmentPathAllLinks");

    private final String key;
    TransferPath(String key) { this.key=key; }
    public String key() { return key; }

    /** Legacy arguments are ignored: file routes are persisted independently of chat. */
    public static TransferPath forAttachment(String transport,boolean ready,AttachmentRecord record) {
        if(record==null)return NONE;
        if(record.route.contains(">")){
            switch(record.route){case "wifi-direct>lan":return DIRECT_LAN;case "wifi-direct>bluetooth":return DIRECT_BT;case "lan>bluetooth":return LAN_BT;case "lan>wifi-direct":return LAN_DIRECT;case "bluetooth>wifi-direct":return BT_DIRECT;case "bluetooth>lan":return BT_LAN;default:return ALL_LINKS;}
        }
        if("lan".equals(record.route))return LAN_UNCONFIRMED;
        if("wifi-direct".equals(record.route))return WIFI_DIRECT;
        if("bluetooth".equals(record.route))return BLUETOOTH;
        if(record.state.equals("queued")||record.state.equals("preparing")||record.state.equals("offered"))return SELECTING;
        return UNKNOWN;
    }
}

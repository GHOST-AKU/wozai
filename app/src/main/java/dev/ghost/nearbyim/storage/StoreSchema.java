package dev.ghost.nearbyim.storage;

/** SQLite statements executed by ChatStore and by the real SQLite migration tests. */
public final class StoreSchema {
    private StoreSchema() {}
    public static final int VERSION = 3;
    public static final String MIGRATE_MESSAGE_STATES = "UPDATE messages SET state=CASE state WHEN '待确认' THEN 'pending' WHEN '已送达' THEN 'delivered' WHEN '未确认' THEN 'unknown' ELSE state END WHERE outgoing=1";
    public static final String RECOVER_PENDING = "UPDATE messages SET state='unknown' WHERE outgoing=1 AND state='pending'";
    /** Ordered, lossless upgrade steps; v1 history never grants device trust. */
    public static java.util.List<String> upgradeStatements(int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion > VERSION || newVersion < oldVersion)
            throw new IllegalArgumentException("Unsupported schema upgrade: " + oldVersion + " -> " + newVersion);
        java.util.List<String> statements = new java.util.ArrayList<>();
        for (int version = oldVersion; version < newVersion; version++) {
            if (version == 1) statements.add(CREATE_TRUST);
            else if (version == 2) statements.add(MIGRATE_MESSAGE_STATES);
        }
        return statements;
    }
    public static final String CREATE_CONVERSATIONS = "CREATE TABLE conversations (peer_id TEXT PRIMARY KEY, name TEXT NOT NULL, updated INTEGER NOT NULL)";
    public static final String CREATE_MESSAGES = "CREATE TABLE messages (peer_id TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL, received INTEGER NOT NULL, PRIMARY KEY(peer_id,id,outgoing))";
    public static final String CREATE_MESSAGE_INDEX = "CREATE INDEX messages_timeline ON messages(peer_id,received)";
    public static final String CREATE_TRUST = "CREATE TABLE trusted_devices (peer_id TEXT PRIMARY KEY, public_key TEXT NOT NULL, name TEXT NOT NULL, last_connected INTEGER NOT NULL, mode INTEGER NOT NULL CHECK(mode IN (1,2)), bluetooth_address TEXT)";
    public static final String CONVERSATIONS = "SELECT c.peer_id,c.name,COALESCE(m.time,0),COALESCE(m.body,''),COALESCE(m.outgoing,0),COALESCE(m.state,'') FROM conversations c LEFT JOIN messages m ON m.rowid=(SELECT rowid FROM messages WHERE peer_id=c.peer_id ORDER BY received DESC,rowid DESC LIMIT 1) ORDER BY COALESCE(m.time,0) DESC,c.peer_id LIMIT 100";
    // Compatible with API 26 SQLite: no newer UPSERT syntax. A changed pin never replaces the old record.
    public static final String REMEMBER_TRUST = "INSERT OR REPLACE INTO trusted_devices (peer_id,public_key,name,last_connected,mode,bluetooth_address) SELECT ?1,?2,?3,?4,?5,COALESCE(?6,(SELECT bluetooth_address FROM trusted_devices WHERE peer_id=?1)) WHERE NOT EXISTS (SELECT 1 FROM trusted_devices WHERE peer_id=?1 AND public_key<>?2)";
    public static final String REVOKE_TRUST = "DELETE FROM trusted_devices WHERE peer_id=?";
    public static final String CLEAR_MESSAGES = "DELETE FROM messages WHERE peer_id=?";
    public static final String TOUCH_INSERT = "INSERT OR IGNORE INTO conversations (peer_id,name,updated) VALUES (?,?,0)";
    public static final String TOUCH_UPDATE = "UPDATE conversations SET name=? WHERE peer_id=?";
}

package dev.ghost.nearbyim;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import dev.ghost.nearbyim.core.Frame;
import dev.ghost.nearbyim.storage.StoreSchema;
import java.util.*;

/** Access exclusively on ChatController's storage executor. */
public final class ChatStore extends SQLiteOpenHelper {
    public static final String PENDING = "待确认", DELIVERED = "已送达", UNKNOWN = "未确认";
    public static final class Conversation {
        public final String id, name, preview, state;
        public final boolean outgoing;
        public final long time;
        public Conversation(String id, String name, long time) { this(id, name, time, "", false, ""); }
        public Conversation(String id, String name, long time, String preview, boolean outgoing, String state) {
            this.id = id; this.name = name; this.time = time; this.preview = preview; this.outgoing = outgoing; this.state = state;
        }
    }
    public static final class TrustedDevice {
        public final String id, name, publicKey, bluetoothAddress;
        public final long time;
        public final int mode;
        TrustedDevice(String id, String name, String publicKey, long time, int mode, String bluetoothAddress) {
            this.id = id; this.name = name; this.publicKey = publicKey; this.time = time;
            this.mode = mode; this.bluetoothAddress = bluetoothAddress;
        }
    }
    public static final class Message {
        public final String id, text, state;
        public final boolean outgoing;
        public final long time;
        Message(String id, String text, String state, boolean outgoing, long time) {
            this.id = id; this.text = text; this.state = state; this.outgoing = outgoing; this.time = time;
        }
    }
    public ChatStore(Context context) { super(context, "nearby-im.db", null, StoreSchema.VERSION); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(StoreSchema.CREATE_CONVERSATIONS);
        db.execSQL(StoreSchema.CREATE_MESSAGES);
        db.execSQL(StoreSchema.CREATE_MESSAGE_INDEX);
        db.execSQL(StoreSchema.CREATE_TRUST);
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == StoreSchema.VERSION) {
            // Only add authorization storage: UUID-only history has no key proof.
            db.execSQL(StoreSchema.CREATE_TRUST); return;
        }
        throw new IllegalStateException("Unknown schema upgrade");
    }
    public void touch(String peerId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(StoreSchema.TOUCH_INSERT, new Object[]{peerId, name});
        db.execSQL(StoreSchema.TOUCH_UPDATE, new Object[]{name, peerId});
    }
    public void save(String peerId, String name, Frame frame, boolean outgoing) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            touch(peerId, name);
            ContentValues values = new ContentValues(); values.put("peer_id", peerId); values.put("id", frame.id); values.put("body", frame.body);
            values.put("outgoing", outgoing ? 1 : 0); values.put("state", outgoing ? PENDING : "");
            values.put("time", outgoing ? frame.timestamp : System.currentTimeMillis()); values.put("received", System.currentTimeMillis());
            // Constraint duplicates are benign; all other database failures propagate, preventing an ACK.
            try { db.insertOrThrow("messages", null, values); }
            catch (SQLiteConstraintException duplicate) {
                try (Cursor existing = db.query("messages", new String[]{"body"}, "peer_id=? AND id=? AND outgoing=?",
                        new String[]{peerId, frame.id, outgoing ? "1" : "0"}, null, null, null)) {
                    if (!existing.moveToFirst() || !existing.getString(0).equals(frame.body)) throw duplicate;
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public void delivered(String peerId, String messageId) {
        ContentValues values = new ContentValues(); values.put("state", DELIVERED);
        getWritableDatabase().update("messages", values, "peer_id=? AND id=? AND outgoing=1", new String[]{peerId, messageId});
    }
    public void uncertain(String peerId) {
        ContentValues values = new ContentValues(); values.put("state", UNKNOWN);
        getWritableDatabase().update("messages", values, "peer_id=? AND outgoing=1 AND state=?", new String[]{peerId, PENDING});
    }
    public void recoverPending() {
        ContentValues values = new ContentValues(); values.put("state", UNKNOWN);
        getWritableDatabase().update("messages", values, "outgoing=1 AND state=?", new String[]{PENDING});
    }
    public List<Message> messages(String peerId) {
        List<Message> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "body", "state", "outgoing", "time"},
                "peer_id=?", new String[]{peerId}, null, null, "received DESC, rowid DESC", "200")) {
            while (cursor.moveToNext()) list.add(new Message(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1, cursor.getLong(4)));
        }
        Collections.reverse(list); return list;
    }
    public List<Conversation> conversations() {
        List<Conversation> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(StoreSchema.CONVERSATIONS, null)) {
            while (cursor.moveToNext()) list.add(new Conversation(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                    cursor.getString(3), cursor.getInt(4) == 1, cursor.getString(5)));
        }
        return list;
    }
    public void clear(String peerId) {
        getWritableDatabase().execSQL(StoreSchema.CLEAR_MESSAGES, new Object[]{peerId});
    }
    public TrustedDevice trusted(String peerId) {
        try (Cursor cursor = getReadableDatabase().query("trusted_devices",
                new String[]{"peer_id", "name", "public_key", "last_connected", "mode", "bluetooth_address"},
                "peer_id=?", new String[]{peerId}, null, null, null)) {
            return cursor.moveToFirst() ? trustedRow(cursor) : null;
        }
    }
    public List<TrustedDevice> trustedDevices() {
        List<TrustedDevice> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("trusted_devices",
                new String[]{"peer_id", "name", "public_key", "last_connected", "mode", "bluetooth_address"},
                null, null, null, null, "last_connected DESC, peer_id")) {
            while (cursor.moveToNext()) list.add(trustedRow(cursor));
        }
        return list;
    }
    private static TrustedDevice trustedRow(Cursor cursor) {
        return new TrustedDevice(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getLong(3), cursor.getInt(4), cursor.isNull(5) ? null : cursor.getString(5));
    }
    public void remember(String peerId, String name, String key, int mode, String bluetoothAddress) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL(StoreSchema.REMEMBER_TRUST, new Object[]{peerId, key, name, System.currentTimeMillis(), mode, bluetoothAddress});
            TrustedDevice saved = trusted(peerId);
            if (saved == null || !key.equals(saved.publicKey)) throw new SQLiteException("Device identity changed");
            touch(peerId, name); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public void revokeTrust(String peerId) {
        getWritableDatabase().execSQL(StoreSchema.REVOKE_TRUST, new Object[]{peerId});
    }

}

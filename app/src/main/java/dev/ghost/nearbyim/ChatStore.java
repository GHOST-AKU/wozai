package dev.ghost.nearbyim;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import dev.ghost.nearbyim.core.Frame;
import java.util.*;

/** Access exclusively on ChatController's storage executor. */
public final class ChatStore extends SQLiteOpenHelper {
    public static final String PENDING = "待确认", DELIVERED = "已送达", UNKNOWN = "未确认";
    public static final class Conversation {
        public final String id, name;
        public final long time;
        Conversation(String id, String name, long time) { this.id = id; this.name = name; this.time = time; }
    }
    public static final class Message {
        public final String id, text, state;
        public final boolean outgoing;
        public final long time;
        Message(String id, String text, String state, boolean outgoing, long time) {
            this.id = id; this.text = text; this.state = state; this.outgoing = outgoing; this.time = time;
        }
    }
    public ChatStore(Context context) { super(context, "nearby-im.db", null, 1); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (peer_id TEXT PRIMARY KEY, name TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (peer_id TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL, received INTEGER NOT NULL, PRIMARY KEY(peer_id,id,outgoing))");
        db.execSQL("CREATE INDEX messages_timeline ON messages(peer_id,received)");
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unknown schema upgrade"); }
    public void touch(String peerId, String name) {
        ContentValues values = new ContentValues(); values.put("peer_id", peerId); values.put("name", name); values.put("updated", System.currentTimeMillis());
        if (getWritableDatabase().insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new SQLiteException("Conversation metadata could not be saved");
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
        try (Cursor cursor = getReadableDatabase().query("conversations", new String[]{"peer_id", "name", "updated"}, null, null, null, null, "updated DESC", "100")) {
            while (cursor.moveToNext()) list.add(new Conversation(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
        }
        return list;
    }
    public void clear(String peerId) {
        getWritableDatabase().delete("messages", "peer_id=?", new String[]{peerId});
    }
}

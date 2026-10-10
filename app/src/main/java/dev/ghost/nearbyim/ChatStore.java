package dev.ghost.nearbyim;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import dev.ghost.nearbyim.core.*;
import java.nio.file.*;
import java.io.IOException;
import dev.ghost.nearbyim.storage.StoreSchema;
import java.util.*;

/** Access exclusively on ChatController's storage executor. */
public final class ChatStore extends SQLiteOpenHelper {
    public static final String PENDING = "pending", DELIVERED = "delivered", UNKNOWN = "unknown";
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
        public final AttachmentRecord attachment;
        public final String id, text, state;
        public final boolean outgoing;
        public final long time;
        Message(String id, String text, String state, boolean outgoing, long time) {
            this(id,text,state,outgoing,time,null);
        }
        Message(String id,String text,String state,boolean outgoing,long time,AttachmentRecord attachment){
            this.id=id;this.text=text;this.state=state;this.outgoing=outgoing;this.time=time;this.attachment=attachment;
        }
    }
    private final Path attachments;
    public ChatStore(Context context) { super(context, "nearby-im.db", null, StoreSchema.VERSION); attachments=context.getFilesDir().toPath().resolve("attachments"); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(StoreSchema.CREATE_CONVERSATIONS);
        db.execSQL(StoreSchema.CREATE_MESSAGES);
        db.execSQL(StoreSchema.CREATE_MESSAGE_INDEX);
        db.execSQL(StoreSchema.CREATE_TRUST);
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (String statement : StoreSchema.upgradeStatements(oldVersion, newVersion)) db.execSQL(statement);
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
                try (Cursor existing = db.query("messages", new String[]{"body","attachment"}, "peer_id=? AND id=? AND outgoing=?",
                        new String[]{peerId, frame.id, outgoing ? "1" : "0"}, null, null, null)) {
                    if (!existing.moveToFirst() || !existing.getString(0).equals(frame.body)||!existing.isNull(1)) throw duplicate;
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
        SQLiteDatabase db=getWritableDatabase();db.execSQL(StoreSchema.RECOVER_PENDING);
        Map<String,Set<String>> complete=new HashMap<>();
        try(Cursor cursor=db.query("messages",new String[]{"peer_id","id","outgoing","attachment"},"attachment IS NOT NULL",null,null,null,null)){
            while(cursor.moveToNext()){
                String peer=cursor.getString(0);AttachmentRecord record=decodeAttachment(cursor.getString(3));AttachmentRecord recovered;
                try{recovered=record.recovered();}catch(IOException e){throw new IllegalStateException(e);}
                if(!recovered.equals(record)){ContentValues values=new ContentValues();values.put("attachment",encodeAttachment(recovered));db.update("messages",values,"peer_id=? AND id=? AND outgoing=?",new String[]{peer,record.info.id,record.outgoing?"1":"0"});}
                if(!recovered.outgoing&&recovered.state.equals("received")||recovered.outgoing&&recovered.state.equals("delivered")&&recovered.info.mime.startsWith("image/"))complete.computeIfAbsent(peer,k->new HashSet<>()).add(record.info.id);
            }
        }
        try{if(Files.exists(attachments))try(java.util.stream.Stream<Path> dirs=Files.list(attachments)){for(Path dir:(Iterable<Path>)dirs::iterator){String peer=dir.getFileName().toString();AttachmentTransfer.clean(attachmentDirectory(peer),complete.getOrDefault(peer,Collections.emptySet()));}}}
        catch(IOException e){throw new IllegalStateException(e);}
    }
    private static AttachmentRecord decodeAttachment(String value){try{return value==null?null:AttachmentRecord.decode(value);}catch(IOException e){throw new IllegalStateException(e);}}
    private static String encodeAttachment(AttachmentRecord value){try{return value.encode();}catch(IOException e){throw new IllegalStateException(e);}}
    public Path attachmentDirectory(String peer)throws IOException {
        if(!peer.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||Files.isSymbolicLink(attachments))throw new IOException("Unsafe attachment path");
        Files.createDirectories(attachments);Path directory=attachments.resolve(peer);if(Files.isSymbolicLink(directory))throw new IOException("Unsafe attachment directory");return directory;
    }
    public Path attachmentFile(String peer,AttachmentInfo info)throws IOException{return AttachmentTransfer.file(attachmentDirectory(peer),info);}
    public Path attachmentsRoot(){return attachments;}
    public Path attachmentFile(String peer,AttachmentInfo info,boolean outgoing)throws IOException{return AttachmentTransfer.file(attachmentDirectory(peer),info,outgoing);}
    public void attachment(String peer,String name,AttachmentRecord record){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            touch(peer,name);ContentValues values=new ContentValues();values.put("attachment",encodeAttachment(record));values.put("state",record.outgoing?(record.state.equals("delivered")?DELIVERED:record.active()?PENDING:UNKNOWN):"");
            try(Cursor cursor=db.query("messages",new String[]{"attachment"},"peer_id=? AND id=? AND outgoing=?",new String[]{peer,record.info.id,record.outgoing?"1":"0"},null,null,null)){
                if(cursor.moveToFirst()){
                    AttachmentRecord previous=decodeAttachment(cursor.getString(0));if(previous==null||!previous.mayReplace(record))throw new SQLiteException("Conflicting attachment ID");
                    db.update("messages",values,"peer_id=? AND id=? AND outgoing=?",new String[]{peer,record.info.id,record.outgoing?"1":"0"});
                }else{
                    values.put("peer_id",peer);values.put("id",record.info.id);values.put("body",record.info.name);values.put("outgoing",record.outgoing?1:0);values.put("time",System.currentTimeMillis());values.put("received",System.currentTimeMillis());db.insertOrThrow("messages",null,values);
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public List<Message> messages(String peerId) {
        List<Message> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "body", "state", "outgoing", "time", "attachment"},
                "peer_id=?", new String[]{peerId}, null, null, "received DESC, rowid DESC", "200")) {
            while (cursor.moveToNext()) list.add(new Message(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1, cursor.getLong(4),decodeAttachment(cursor.isNull(5)?null:cursor.getString(5))));
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
        try{Path directory=attachmentDirectory(peerId);if(Files.exists(directory))AttachmentTransfer.clear(directory);}catch(IOException e){throw new IllegalStateException(e);}
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

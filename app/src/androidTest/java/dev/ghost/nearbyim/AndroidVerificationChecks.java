package dev.ghost.nearbyim;

import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.*;
import dev.ghost.nearbyim.core.DeviceIdentity;
import dev.ghost.nearbyim.storage.StoreSchema;
import java.io.*;
import java.util.UUID;

/** Real Android SQLite v4 upgrade and exact-root verification; isolated from app data. */
final class AndroidVerificationChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static int run(Context context)throws Exception {
        File directory=new File(context.getCacheDir(),"verification-db-"+UUID.randomUUID());
        if(!directory.mkdir())throw new IOException("Fixture directory unavailable");
        Context isolated=new ContextWrapper(context){
            public File getDatabasePath(String name){return new File(directory,name);}
            public File getFilesDir(){return directory;}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(new File(directory,name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler error){return SQLiteDatabase.openOrCreateDatabase(new File(directory,name).getPath(),factory,error);}
        };
        String peer=UUID.randomUUID().toString(),local=DeviceIdentity.generate().publicKey(),remote=DeviceIdentity.generate().publicKey(),other=DeviceIdentity.generate().publicKey();
        File database=new File(directory,"nearby-im.db");
        try {
            try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(database,null)){
                old.execSQL(StoreSchema.CREATE_CONVERSATIONS);old.execSQL(StoreSchema.CREATE_MESSAGES);old.execSQL(StoreSchema.CREATE_MESSAGE_INDEX);old.execSQL(StoreSchema.CREATE_TRUST.replace("CHECK(mode IN (1,2,3))","CHECK(mode IN (1,2))"));old.setVersion(4);
                old.execSQL("INSERT INTO trusted_devices(peer_id,public_key,name,last_connected,mode,bluetooth_address) VALUES(?,?,?,1,2,'02:11:22:33:44:55')",new Object[]{peer,remote,"old device"});
                boolean directRejected=false;try{old.execSQL("UPDATE trusted_devices SET mode=3 WHERE peer_id=?",new Object[]{peer});}catch(SQLiteConstraintException expected){directRejected=true;}check(directRejected,"Legacy v4 trust constraint rejects Direct mode");
                old.execSQL("INSERT INTO messages(peer_id,id,body,outgoing,state,time,received) VALUES(?,?,?,0,'delivered',1,1)",new Object[]{peer,UUID.randomUUID().toString(),"migration history"});
            }
            try(ChatStore store=new ChatStore(isolated)){
                check(store.getReadableDatabase().getVersion()==StoreSchema.VERSION&&StoreSchema.VERSION==6,"Actual Android DB migrated from v4 to v6");
                check(remote.equals(store.trusted(peer).publicKey)&&store.trusted(peer).mode==2&&"02:11:22:33:44:55".equals(store.trusted(peer).bluetoothAddress)&&store.messages(peer).size()==1,"Upgrade preserves original trust, Bluetooth hint and history");
                check(!store.isVerified(peer,local,remote),"Upgraded TOFU pin is not automatically verified");
                store.markVerified(peer,local,remote);check(store.isVerified(peer,local,remote),"Actual SQLite stores exact verified roots");
                store.remember(peer,"direct device",remote,3,null);check(store.trusted(peer).mode==3&&"02:11:22:33:44:55".equals(store.trusted(peer).bluetoothAddress)&&store.isVerified(peer,local,remote),"Migrated native schema accepts Direct without changing verified roots or Bluetooth hint");
                check(!store.isVerified(peer,other,remote)&&!store.isVerified(peer,local,other),"Either changed root invalidates status");
                boolean rejected=false;try{store.markVerified(peer,local,other);}catch(SQLiteException changed){rejected=true;}check(rejected&&store.isVerified(peer,local,remote),"Wrong root cannot replace a verified pin");
            }
            try(ChatStore store=new ChatStore(isolated)){
                check(store.isVerified(peer,local,remote),"Verification survives DB reopen");
                store.invalidateLocalVerifications(other);check(!store.isVerified(peer,local,remote),"Changed local identity clears old verification");
                store.markVerified(peer,local,remote);store.revokeTrust(peer);check(!store.isVerified(peer,local,remote)&&store.messages(peer).size()==1,"Revoke clears verification and retains history");
                store.remember(peer,"old device",remote,1,null);check(!store.isVerified(peer,local,remote),"Re-pair with the same key still requires new human verification");
            }
            return 12;
        }finally{try(var files=java.nio.file.Files.walk(directory.toPath())){var iterator=files.sorted(java.util.Comparator.reverseOrder()).iterator();while(iterator.hasNext())java.nio.file.Files.deleteIfExists(iterator.next());}}
    }
}

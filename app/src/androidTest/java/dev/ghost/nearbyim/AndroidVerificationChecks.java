package dev.ghost.nearbyim;

import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.*;
import dev.ghost.nearbyim.core.DeviceIdentity;
import dev.ghost.nearbyim.core.AttachmentInfo;
import dev.ghost.nearbyim.core.AttachmentRecord;
import dev.ghost.nearbyim.storage.StoreSchema;
import java.io.*;
import java.util.UUID;

/** Real Android SQLite v6 upgrade and exact-root verification; isolated from app data. */
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
                old.execSQL(StoreSchema.CREATE_CONVERSATIONS);old.execSQL(StoreSchema.CREATE_MESSAGES);old.execSQL(StoreSchema.CREATE_MESSAGE_INDEX);old.execSQL(StoreSchema.CREATE_TRUST.replace("CHECK(mode IN (1,2))","CHECK(mode IN (1,2,3))"));old.execSQL(StoreSchema.CREATE_VERIFICATION);old.setVersion(6);
                old.execSQL("INSERT INTO trusted_devices(peer_id,public_key,name,last_connected,mode,bluetooth_address) VALUES(?,?,?,1,3,'02:11:22:33:44:55')",new Object[]{peer,remote,"old device"});
                old.execSQL("INSERT INTO verified_identities VALUES(?,?,?,1)",new Object[]{peer,local,remote});
                old.execSQL("INSERT INTO messages(peer_id,id,body,outgoing,state,time,received) VALUES(?,?,?,0,'delivered',1,1)",new Object[]{peer,UUID.randomUUID().toString(),"migration history"});
            }
            try(ChatStore store=new ChatStore(isolated)){
                check(store.getReadableDatabase().getVersion()==StoreSchema.VERSION&&StoreSchema.VERSION==7,"Actual Android DB migrated from v6 to v7");
                check(remote.equals(store.trusted(peer).publicKey)&&store.trusted(peer).mode==1&&"02:11:22:33:44:55".equals(store.trusted(peer).bluetoothAddress)&&store.messages(peer).size()==1,"Upgrade preserves original trust, Bluetooth hint and history");
                check(store.isVerified(peer,local,remote),"Upgrade preserves exact verified roots");
                store.markVerified(peer,local,remote);check(store.isVerified(peer,local,remote),"Actual SQLite stores exact verified roots");
                store.remember(peer,"LAN device",remote,1,null);check(store.trusted(peer).mode==1&&"02:11:22:33:44:55".equals(store.trusted(peer).bluetoothAddress)&&store.isVerified(peer,local,remote),"Migrated native schema retains roots and Bluetooth hints when remembering LAN");
                String routePeer=UUID.randomUUID().toString();AttachmentInfo routeInfo=AttachmentInfo.v2(UUID.randomUUID().toString(),"route.bin","application/octet-stream",8,"0".repeat(64),1);
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,true,"transferring",4).withRoute("wifi-direct"));
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,true,"paused",4));
                check(store.messages(routePeer).get(0).attachment.route.equals("wifi-direct"),"Blank native callback must retain the actual route");
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,true,"delivered",8));
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,true,"delivered",8));
                check(store.messages(routePeer).get(0).attachment.route.equals("wifi-direct"),"Native terminal history must retain the actual route");
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,true,"delivered",8).withRoute("bluetooth"));
                check(store.messages(routePeer).get(0).attachment.route.equals("wifi-direct"),"Native terminal replay cannot relabel the original route");
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,false,"received",8).withRoute("lan"));
                store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,false,"received",8).withRoute("bluetooth"));
                check(store.messages(routePeer).stream().filter(m->!m.outgoing).findFirst().orElseThrow().attachment.route.equals("lan"),"Native saved receipt replay must retain its original LAN route");
                boolean terminalConflict=false;try{store.attachment(routePeer,"route peer",new AttachmentRecord(routeInfo,false,"delivered",8).withRoute("bluetooth"));}catch(SQLiteException rejected){terminalConflict=true;}
                check(terminalConflict,"Native terminal replay must reject a changed final state");
                AttachmentInfo unknownInfo=AttachmentInfo.v2(UUID.randomUUID().toString(),"old.bin","application/octet-stream",8,"0".repeat(64),1);
                store.attachment(routePeer,"route peer",new AttachmentRecord(unknownInfo,false,"received",8));
                store.attachment(routePeer,"route peer",new AttachmentRecord(unknownInfo,false,"received",8).withRoute("bluetooth"));
                check(store.messages(routePeer).stream().filter(m->m.id.equals(unknownInfo.id)).findFirst().orElseThrow().attachment.route.isEmpty(),"Native terminal replay cannot invent an unknown historic route");
                check(!store.isVerified(peer,other,remote)&&!store.isVerified(peer,local,other),"Either changed root invalidates status");
                boolean rejected=false;try{store.markVerified(peer,local,other);}catch(SQLiteException changed){rejected=true;}check(rejected&&store.isVerified(peer,local,remote),"Wrong root cannot replace a verified pin");
            }
            try(ChatStore store=new ChatStore(isolated)){
                check(store.isVerified(peer,local,remote),"Verification survives DB reopen");
                store.invalidateLocalVerifications(other);check(!store.isVerified(peer,local,remote),"Changed local identity clears old verification");
                store.markVerified(peer,local,remote);store.revokeTrust(peer);check(!store.isVerified(peer,local,remote)&&store.messages(peer).size()==1,"Revoke clears verification and retains history");
                store.remember(peer,"old device",remote,1,null);check(!store.isVerified(peer,local,remote),"Re-pair with the same key still requires new human verification");
            }
            return 18;
        }finally{try(var files=java.nio.file.Files.walk(directory.toPath())){var iterator=files.sorted(java.util.Comparator.reverseOrder()).iterator();while(iterator.hasNext())java.nio.file.Files.deleteIfExists(iterator.next());}}
    }
}

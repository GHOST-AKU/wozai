package dev.ghost.nearbyim;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import dev.ghost.nearbyim.core.*;
/** Real API 26+ SQLite migration, attachment history, recovery and read-only provider checks. */
final class AndroidAttachmentChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static int run(Context context)throws Exception {
        String peer=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();Path root=Files.createTempDirectory(context.getCacheDir().toPath(),"native-store-");
        Context isolated=new ContextWrapper(context){
            public File getFilesDir(){File file=root.resolve("files").toFile();file.mkdirs();return file;}
            public File getDatabasePath(String name){return root.resolve(name).toFile();}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        try {
            try(SQLiteDatabase legacy=SQLiteDatabase.openOrCreateDatabase(isolated.getDatabasePath("nearby-im.db"),null)){
                legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_CONVERSATIONS);legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_MESSAGES.replace(" attachment TEXT,",""));legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_MESSAGE_INDEX);legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_TRUST);
                legacy.execSQL("INSERT INTO messages VALUES (?,?,?,?,?,?,?)",new Object[]{peer,id,"old text 中文",1,"delivered",1,2});legacy.setVersion(3);
            }
            AttachmentInfo info=new AttachmentInfo(id,"photo.png","image/png",10,"0000000000000000000000000000000000000000000000000000000000000000",1);
            try(ChatStore store=new ChatStore(isolated)){
                check(store.getWritableDatabase().getVersion()==4,"Native schema did not migrate");check(store.messages(peer).get(0).text.equals("old text 中文")&&store.messages(peer).get(0).attachment==null,"Legacy text changed");
                String transfer=UUID.randomUUID().toString();info=new AttachmentInfo(transfer,info.name,info.mime,info.size,info.hash,1);store.attachment(peer,"peer",new AttachmentRecord(info,true,"awaitingReceipt",10));store.recoverPending();check(store.messages(peer).get(1).attachment.state.equals("unknown"),"Pending receipt falsely completed");
                store.attachment(peer,"peer",new AttachmentRecord(info,false,"offered",0));store.attachment(peer,"peer",new AttachmentRecord(info,false,"received",10));Path directory=store.attachmentDirectory(peer);Files.createDirectories(directory);Path content=AttachmentTransfer.file(directory,info);Files.write(content,new byte[10]);
                store.remember(peer,"peer",DeviceIdentity.generate().publicKey(),1,null);store.clear(peer);check(!Files.exists(content)&&store.messages(peer).isEmpty(),"Clear left attachment content or metadata");check(store.trusted(peer)!=null,"Clear revoked independent trust");
            }
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(file);}}
        Path directory=context.getFilesDir().toPath().resolve("attachments").resolve(peer);Files.createDirectories(directory);AttachmentInfo info=new AttachmentInfo(id,"document.txt","text/plain",3,"0000000000000000000000000000000000000000000000000000000000000000",1);Path file=AttachmentTransfer.file(directory,info);Files.write(file,new byte[]{1,2,3});
        Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath(peer).appendPath(file.getFileName().toString()).appendQueryParameter("name",info.name).appendQueryParameter("mime",info.mime).build();
        try {
            try(InputStream input=context.getContentResolver().openInputStream(uri)){check(input.read()==1,"Read-only provider lost data");}
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){check(cursor.moveToFirst()&&cursor.getString(0).equals(info.name)&&cursor.getLong(1)==3,"Provider metadata wrong");}
            boolean denied=false;try{context.getContentResolver().openOutputStream(uri).close();}catch(FileNotFoundException|SecurityException expected){denied=true;}check(denied,"Provider allowed writing");
            Uri unsafe=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath("..").appendPath("identity").build();denied=false;try{context.getContentResolver().openInputStream(unsafe).close();}catch(FileNotFoundException|SecurityException expected){denied=true;}check(denied,"Provider allowed traversal");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        return 9;
    }
}

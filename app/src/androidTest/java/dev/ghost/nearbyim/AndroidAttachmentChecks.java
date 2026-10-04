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
            Path sandbox=root.resolve("sandbox");
            try(AttachmentTransfer transfer=new AttachmentTransfer(sandbox,new AttachmentTransfer.Wire(){
                public boolean send(Frame frame){return true;}
                public void abort(){throw new AssertionError("Empty attachment session aborted");}
            },record->{},AttachmentInfo.CHUNK_SIZE,AttachmentInfo.MAX_SIZE)){
                check(Files.isDirectory(sandbox),"Attachment session could not initialize in Android's private directory");
                check(sandbox.toFile().getUsableSpace()>0,"Android private-directory free space could not be read");
                transfer.shutdown().get(10,java.util.concurrent.TimeUnit.SECONDS);
            }
            try(SQLiteDatabase legacy=SQLiteDatabase.openOrCreateDatabase(isolated.getDatabasePath("nearby-im.db"),null)){
                legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_CONVERSATIONS);legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_MESSAGES.replace(" attachment TEXT,",""));legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_MESSAGE_INDEX);legacy.execSQL(dev.ghost.nearbyim.storage.StoreSchema.CREATE_TRUST);
                legacy.execSQL("INSERT INTO messages VALUES (?,?,?,?,?,?,?)",new Object[]{peer,id,"old text 中文",1,"delivered",1,2});legacy.setVersion(3);
            }
            AttachmentInfo info=new AttachmentInfo(id,"photo.png","image/png",10,"0000000000000000000000000000000000000000000000000000000000000000",1);
            try(ChatStore store=new ChatStore(isolated)){
                check(store.getWritableDatabase().getVersion()==4,"Native schema did not migrate");check(store.messages(peer).get(0).text.equals("old text 中文")&&store.messages(peer).get(0).attachment==null,"Legacy text changed");
                String transfer=UUID.randomUUID().toString();info=new AttachmentInfo(transfer,info.name,info.mime,info.size,info.hash,1);store.attachment(peer,"peer",new AttachmentRecord(info,true,"awaitingReceipt",10));store.recoverPending();check(store.messages(peer).get(1).attachment.state.equals("unknown"),"Pending receipt falsely completed");
                store.attachment(peer,"peer",new AttachmentRecord(info,false,"offered",0));store.attachment(peer,"peer",new AttachmentRecord(info,false,"received",10));Path directory=store.attachmentDirectory(peer);Files.createDirectories(directory);Path content=AttachmentTransfer.file(directory,info);Files.write(content,new byte[10]);
                AttachmentInfo sent=new AttachmentInfo(UUID.randomUUID().toString(),"sent.jpg","image/jpeg",10,info.hash,1);store.attachment(peer,"peer",new AttachmentRecord(sent,true,"awaitingReceipt",10));Path sentPhoto=AttachmentTransfer.file(directory,sent,true);Files.write(sentPhoto,new byte[10]);store.attachment(peer,"peer",new AttachmentRecord(sent,true,"delivered",10));store.recoverPending();check(Files.exists(sentPhoto),"History recovery deleted a delivered sent photo");
                store.remember(peer,"peer",DeviceIdentity.generate().publicKey(),1,null);store.clear(peer);check(!Files.exists(content)&&store.messages(peer).isEmpty(),"Clear left attachment content or metadata");check(store.trusted(peer)!=null,"Clear revoked independent trust");
                check(!Files.exists(sentPhoto),"Clear left a private sent-photo copy");
            }
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(file);}}
        Path directory=context.getFilesDir().toPath().resolve("attachments").resolve(peer);Files.createDirectories(directory);AttachmentInfo info=new AttachmentInfo(id,"document.txt","text/plain",3,"0000000000000000000000000000000000000000000000000000000000000000",1);Path file=AttachmentTransfer.file(directory,info);Files.write(file,new byte[]{1,2,3});
        Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath(peer).appendPath(file.getFileName().toString()).appendQueryParameter("name",info.name).appendQueryParameter("mime",info.mime).build();
        try {
            try(InputStream input=context.getContentResolver().openInputStream(uri)){check(input.read()==1,"Read-only provider lost data");}
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){check(cursor.moveToFirst()&&cursor.getString(0).equals(info.name)&&cursor.getLong(1)==3,"Provider metadata wrong");}
            boolean denied=false;try{context.getContentResolver().openOutputStream(uri).close();}catch(FileNotFoundException|SecurityException expected){denied=true;}check(denied,"Provider allowed writing");
            Uri unsafe=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath("..").appendPath("identity").build();denied=false;try{context.getContentResolver().openInputStream(unsafe).close();}catch(FileNotFoundException|SecurityException expected){denied=true;}check(denied,"Provider allowed traversal");
            Path staged=directory.resolve("in-"+id+".part");Files.write(staged,new byte[]{9});Uri stagedUri=uri.buildUpon().path("/"+peer+"/"+staged.getFileName()).build();denied=false;try{context.getContentResolver().openInputStream(stagedUri).close();}catch(FileNotFoundException|SecurityException expected){denied=true;}finally{Files.deleteIfExists(staged);}check(denied,"Provider exposed an incomplete transfer's staging file");
            android.graphics.Bitmap original=android.graphics.Bitmap.createBitmap(1600,800,android.graphics.Bitmap.Config.ARGB_8888);original.eraseColor(android.graphics.Color.GREEN);try(OutputStream output=Files.newOutputStream(file)){original.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,output);}original.recycle();
            android.media.ExifInterface exif=new android.media.ExifInterface(file.toString());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,Integer.toString(android.media.ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();
            android.graphics.Bitmap preview=PhotoDecoder.decode(context.getContentResolver(),uri,256);check(preview.getWidth()==100&&preview.getHeight()==200,"Thumbnail decoding did not bound or rotate EXIF dimensions");preview.recycle();
            android.graphics.Bitmap full=PhotoDecoder.decode(context.getContentResolver(),uri,2048);check(full.getWidth()==800&&full.getHeight()==1600,"Viewer did not preserve the photo's EXIF orientation");full.recycle();
            android.graphics.Bitmap odd=android.graphics.Bitmap.createBitmap(1025,513,android.graphics.Bitmap.Config.ARGB_8888);odd.eraseColor(android.graphics.Color.BLUE);try(OutputStream output=Files.newOutputStream(file)){odd.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}odd.recycle();android.graphics.Bitmap bounded=PhotoDecoder.decode(context.getContentResolver(),uri,256);check(Math.max(bounded.getWidth(),bounded.getHeight())<=256,"Odd photo dimensions exceeded the preview decoding bound");bounded.recycle();
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        return 17;
    }
}

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
            Path source=root.resolve("native-source.bin");Files.write(source,new byte[]{1,2,3});
            try(FileAttachmentSource input=new FileAttachmentSource(source);InputStream view=input.open(1)){
                check(input.size()==3&&view.read()==2&&view.read()==3&&view.read()==-1,"Native positional source bounds changed");input.verifyUnchanged();
            }
            AttachmentInfo v2=AttachmentInfo.v2(UUID.randomUUID().toString(),"native.bin","application/octet-stream",TransferLimits.MAX_FILE_BYTES,null,1);
            AttachmentRecord pending=new AttachmentRecord(v2,true,"offered",0);check(AttachmentRecord.decode(pending.encode()).equals(pending),"Native v2 history changed");
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
            android.graphics.Bitmap pixels=android.graphics.Bitmap.createBitmap(2,3,android.graphics.Bitmap.Config.ARGB_8888);
            int[] colors={0xffff0000,0xff00ff00,0xff0000ff,0xffffff00,0xffff00ff,0xff00ffff};pixels.setPixels(colors,0,2,0,0,2,3);
            ByteArrayOutputStream png=new ByteArrayOutputStream();pixels.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png);pixels.recycle();byte[] originalPng=png.toByteArray();
            for(int direction=1;direction<=8;direction++){
                byte[] tiff={73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,(byte)direction,0,0,0,0,0,0,0};
                ByteArrayOutputStream annotated=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(annotated);out.write(originalPng,0,33);out.writeInt(tiff.length);out.writeBytes("eXIf");out.write(tiff);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(new byte[]{101,88,73,102});crc.update(tiff);out.writeInt((int)crc.getValue());out.write(originalPng,33,originalPng.length-33);Files.write(file,annotated.toByteArray());
                android.graphics.Bitmap decoded=PhotoDecoder.decode(context.getContentResolver(),uri,2048);
                try{check(decoded.getWidth()==(direction>=5?3:2)&&decoded.getHeight()==(direction>=5?2:3),"PNG orientation dimensions lost");
                    for(int y=0;y<3;y++)for(int x=0;x<2;x++){
                        int dx=x,dy=y;switch(direction){case 2:dx=1-x;break;case 3:dx=1-x;dy=2-y;break;case 4:dy=2-y;break;case 5:dx=y;dy=x;break;case 6:dx=2-y;dy=x;break;case 7:dx=2-y;dy=1-x;break;case 8:dx=y;dy=1-x;break;}
                        check(decoded.getPixel(dx,dy)==colors[y*2+x],"PNG rotation/mirror pixels lost: "+direction);
                    }
                }finally{decoded.recycle();}
            }
            byte[] oversized={(byte)137,80,78,71,13,10,26,10,127,(byte)255,(byte)255,(byte)255,101,88,73,102};
            check(ImageOrientation.read(new ByteArrayInputStream(oversized))==1,"Native oversized EXIF accepted");
            check(!new AttachmentInfo(UUID.randomUUID().toString(),"install.apk","application/vnd.android.package-archive",0,"0000000000000000000000000000000000000000000000000000000000000000",1).canOpenExternally(),"Android installer opened from received file");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        return 77;
    }
}

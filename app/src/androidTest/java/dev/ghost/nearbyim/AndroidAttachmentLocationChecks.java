package dev.ghost.nearbyim;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.graphics.Bitmap;
import android.provider.DocumentsContract;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Real native destination copies, provenance, revoked access and grant-retention cleanup. */
final class AndroidAttachmentLocationChecks {
    private static int checks;
    static int run(Context context)throws Exception {
        checks=0;
        Path root=Files.createTempDirectory(context.getCacheDir().toPath(),"native-location-");
        Context isolated=new ContextWrapper(context){
            public File getFilesDir(){File file=root.resolve("files").toFile();file.mkdirs();return file;}
            public File getDatabasePath(String name){return root.resolve(name).toFile();}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        String peer=UUID.randomUUID().toString();byte[] bytes={2,4,6,8};
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"saved report.txt","text/plain",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);
        AttachmentRecord record=new AttachmentRecord(info,false,"received",info.size);
        Uri destination=Uri.parse("content://dev.ghost.nearbyim.test.sources/export/location"),writeOnly=Uri.parse("content://dev.ghost.nearbyim.test.sources/export/write-only");
        ContentResolver resolver=context.getContentResolver();resolver.call(destination,"export-reset",null,null);resolver.call(destination,"export-grant",context.getPackageName(),null);
        int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        try(ChatStore store=new ChatStore(isolated)){
            store.attachment(peer,"peer",record);Path source=store.attachmentFile(peer,info,false);Files.createDirectories(source.getParent());Files.write(source,bytes);
            try(AttachmentLocations locations=new AttachmentLocations(isolated)){
                check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).kind()==AttachmentLocations.Kind.APP_PRIVATE,"Unsaved original was advertised as an external folder");
                locations.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS);
                check(persistedRead(resolver,destination)&&new AttachmentExportStore(isolated.getFilesDir().toPath().resolve("attachment-exports")).load(peer,record).persistentRead(),"Returned persistable read grant was not taken and journaled");
                try(InputStream input=resolver.openInputStream(destination)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[32];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);check(Arrays.equals(output.toByteArray(),bytes),"Native export changed original bytes");}
                var location=locations.locate(peer,record).get(10,TimeUnit.SECONDS);check(location.kind()==AttachmentLocations.Kind.DOCUMENT&&location.availability()==AttachmentLocations.Availability.AVAILABLE&&destination.equals(location.savedUri()),"Manual export destination was discarded");
                Intent browse=AttachmentLocations.browseIntent(location);check(Intent.ACTION_OPEN_DOCUMENT.equals(browse.getAction())&&destination.equals(browse.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)),"Browser did not receive actual saved URI");
                Intent view=AttachmentLocations.openSavedIntent(location,info.mime);check(destination.equals(view.getData())&&view.getFlags()==Intent.FLAG_GRANT_READ_URI_PERMISSION&&destination.equals(view.getClipData().getItemAt(0).getUri()),"External open granted the wrong URI or write access");
                checkGalleryLocation(isolated,store,locations,peer);
                AndroidAttachmentSource.releaseUnusedGrants(isolated);check(AttachmentLocations.retainedUris(isolated).contains(destination.toString())&&persistedRead(resolver,destination),"Transfer cleanup revoked a saved destination's persisted read grant");
                resolver.call(destination,"export-revoke",null,null);check(!persistedRead(resolver,destination)&&locations.locate(peer,record).get(10,TimeUnit.SECONDS).availability()==AttachmentLocations.Availability.ACCESS_LOST,"Revoked public access silently switched to original");
                resolver.call(destination,"export-grant",context.getPackageName(),null);resolver.call(destination,"export-delete",null,null);check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).availability()==AttachmentLocations.Availability.MISSING,"Deleted destination silently switched to original");
                locations.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS);Files.write(source,new byte[]{0,0,0,0});
                expectFailure(()->locations.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS));check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).kind()!=AttachmentLocations.Kind.DOCUMENT,"Failed overwrite left a partial destination openable");Files.write(source,bytes);
                locations.export(peer,record,writeOnly,Intent.FLAG_GRANT_WRITE_URI_PERMISSION).get(10,TimeUnit.SECONDS);check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).availability()==AttachmentLocations.Availability.ACCESS_LOST,"Write-only export was claimed to be readable");
                Files.write(source,new byte[]{0,0,0,0});expectFailure(()->locations.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS));
                check(new AttachmentExportStore(isolated.getFilesDir().toPath().resolve("attachment-exports")).load(peer,record).uri().equals(writeOnly.toString()),"Failed export replaced a valid previous saved location");Files.write(source,bytes);
                expectFailure(()->locations.export(peer,record,Uri.fromFile(source.toFile()),flags).get(10,TimeUnit.SECONDS));
                expectFailure(()->locations.export(peer,record,destination,0).get(10,TimeUnit.SECONDS));
                Uri privateUri=Uri.parse("content://"+context.getPackageName()+".attachments/"+peer+"/"+source.getFileName());expectFailure(()->locations.export(peer,record,privateUri,flags).get(10,TimeUnit.SECONDS));
                expectFailure(()->locations.export(UUID.randomUUID().toString(),record,destination,flags).get(10,TimeUnit.SECONDS));
                expectFailure(()->locations.export(peer,new AttachmentRecord(info,false,"paused",2),destination,flags).get(10,TimeUnit.SECONDS));
            }
            try(AttachmentLocations restarted=new AttachmentLocations(isolated)){
                check(writeOnly.equals(restarted.locate(peer,record).get(10,TimeUnit.SECONDS).savedUri()),"Saved URI did not survive subsystem restart");
                restarted.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS);check(persistedRead(resolver,destination),"Clear test has no live persisted destination grant");
                restarted.clearPeer(peer).get(10,TimeUnit.SECONDS);check(!persistedRead(resolver,destination),"Clear did not release the obsolete persisted read grant");expectFailure(()->restarted.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS));store.clear(peer);restarted.finishClear(peer);check(AttachmentLocations.retainedUris(isolated).isEmpty(),"Clear retained obsolete export grants");
                try(InputStream input=resolver.openInputStream(destination)){check(input.read()==2,"Clear deleted user-owned public file");}
                expectFailure(()->restarted.export(peer,record,destination,flags).get(10,TimeUnit.SECONDS));
            }
            return checks;
        }finally{try{resolver.releasePersistableUriPermission(destination,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException absent){}resolver.call(destination,"export-revoke",null,null);resolver.call(destination,"export-reset",null,null);try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(file);}}
    }
    private static void checkGalleryLocation(Context context,ChatStore store,AttachmentLocations locations,String peer)throws Exception {
        Bitmap image=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);image.eraseColor(0xff256b51);ByteArrayOutputStream encoded=new ByteArrayOutputStream();try{image.compress(Bitmap.CompressFormat.PNG,100,encoded);}finally{image.recycle();}byte[] bytes=encoded.toByteArray();
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"location photo.png","image/png",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);AttachmentRecord record=new AttachmentRecord(info,false,"received",info.size);store.attachment(peer,"peer",record);Path source=store.attachmentFile(peer,info,false);Files.write(source,bytes);Uri published=null;
        try(GalleryPublisher gallery=new GalleryPublisher(context)){
            gallery.automatic(false);published=gallery.export(peer,record,source,true).get(30,TimeUnit.SECONDS).uri();var location=locations.locate(peer,record).get(10,TimeUnit.SECONDS);
            check(location.kind()==AttachmentLocations.Kind.GALLERY&&location.availability()==AttachmentLocations.Availability.AVAILABLE&&published.equals(location.savedUri()),"Gallery location did not use the actual published MediaStore item");
            PhotoExportStore journal=new PhotoExportStore(context.getFilesDir().toPath().resolve("gallery-exports"));journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.PENDING,published.toString()));check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).kind()==AttachmentLocations.Kind.APP_PRIVATE,"Pending gallery publication became externally openable");journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.SAVED,published.toString()));
            context.getContentResolver().delete(published,null,null);check(locations.locate(peer,record).get(10,TimeUnit.SECONDS).availability()==AttachmentLocations.Availability.MISSING,"Deleted gallery item was hidden by stale saved status");
        }finally{if(published!=null)context.getContentResolver().delete(published,null,null);}
    }
    private static boolean persistedRead(ContentResolver resolver,Uri uri){for(UriPermission grant:resolver.getPersistedUriPermissions())if(grant.isReadPermission()&&uri.equals(grant.getUri()))return true;return false;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private interface Checked {void run()throws Exception;}
    private static void expectFailure(Checked work)throws Exception {boolean failed=false;try{work.run();}catch(ExecutionException expected){failed=true;}check(failed,"Unsafe, incomplete or no-longer-authoritative export accepted");}
}

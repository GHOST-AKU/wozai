package dev.ghost.nearbyim;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.DocumentsContract;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native saved destinations, resolved independently of private file-system paths. */
public final class AttachmentLocations implements AutoCloseable {
    public enum Kind {DOCUMENT,GALLERY,APP_PRIVATE}
    public enum Availability {AVAILABLE,MISSING,ACCESS_LOST,UNAVAILABLE}
    public record Location(Kind kind,Availability availability,Uri savedUri,String displayName){}
    private static volatile AttachmentLocations shared;
    public static AttachmentLocations get(Context context)throws IOException {
        AttachmentLocations value=shared;if(value==null)synchronized(AttachmentLocations.class){if(shared==null)shared=new AttachmentLocations(context.getApplicationContext());value=shared;}return value;
    }
    private final Context context;
    private final ContentResolver resolver;
    private final ChatStore store;
    private final AttachmentExportStore exports;
    private final PhotoExportStore photos;
    private final ConcurrentHashMap<String,AtomicLong> generations=new ConcurrentHashMap<>();
    private final Set<String> clearing=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread thread=new Thread(r,"attachment-location");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    AttachmentLocations(Context context)throws IOException {
        this.context=context;resolver=context.getContentResolver();store=new ChatStore(context);
        Path root=context.getFilesDir().getCanonicalFile().toPath();exports=new AttachmentExportStore(root.resolve("attachment-exports"));photos=new PhotoExportStore(root.resolve("gallery-exports"));
    }
    private static <T> CompletableFuture<T> failed(Throwable error){CompletableFuture<T> future=new CompletableFuture<>();future.completeExceptionally(error);return future;}
    private <T> CompletableFuture<T> submit(Callable<T> work){
        CompletableFuture<T> future=new CompletableFuture<>();if(closed){future.completeExceptionally(new IOException("Attachment locations closed"));return future;}
        try{worker.execute(()->{try{future.complete(work.call());}catch(Exception error){future.completeExceptionally(error);}});}catch(RejectedExecutionException full){future.completeExceptionally(full);}return future;
    }
    private long generation(String peer){return generations.computeIfAbsent(peer,value->new AtomicLong()).get();}
    private boolean canceled(String peer,long expected){return closed||clearing.contains(peer)||generation(peer)!=expected;}
    public CompletableFuture<Location> locate(String peer,AttachmentRecord expected){
        try{AttachmentExportStore.key(peer,expected);}catch(IOException invalid){return failed(invalid);}
        long token=generation(peer);return submit(()->{
            if(canceled(peer,token))throw new IOException("Attachment location canceled");Location location=locateNow(peer,expected);
            if(canceled(peer,token))throw new IOException("Attachment location canceled");store.completedAttachment(peer,expected);return location;
        });
    }
    private Location locateNow(String peer,AttachmentRecord expected)throws IOException {
        AttachmentRecord record=store.completedAttachment(peer,expected);AttachmentExportStore.Entry saved=exports.load(peer,record);
        if(saved!=null)return probe(Kind.DOCUMENT,Uri.parse(saved.uri()),record);
        if(record.info.mime.startsWith("image/")){
            PhotoExportStore.Entry photo=photos.load(peer,record);
            if(photo!=null&&(photo.state()==PhotoExportStore.State.SAVED||photo.state()==PhotoExportStore.State.MISSING)&&!photo.uri().isEmpty()){
                Uri uri=Uri.parse(photo.uri());if("content".equals(uri.getScheme()))return probe(Kind.GALLERY,uri,record);
            }
        }
        try{Path source=store.attachmentFile(peer,record.info,record.outgoing);boolean available=Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&Files.size(source)==record.info.size;
            return new Location(Kind.APP_PRIVATE,available?Availability.AVAILABLE:Availability.UNAVAILABLE,null,record.info.name);
        }catch(IOException invalid){return new Location(Kind.APP_PRIVATE,Availability.UNAVAILABLE,null,record.info.name);}
    }
    private Location probe(Kind kind,Uri uri,AttachmentRecord record){
        try(AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(uri,"r")){
            if(descriptor==null)return new Location(kind,Availability.MISSING,uri,record.info.name);
            long length=descriptor.getLength();return new Location(kind,length>=0&&length!=record.info.size?Availability.UNAVAILABLE:Availability.AVAILABLE,uri,record.info.name);
        }catch(FileNotFoundException missing){return new Location(kind,Availability.MISSING,uri,record.info.name);}
        catch(SecurityException denied){return new Location(kind,Availability.ACCESS_LOST,uri,record.info.name);}
        catch(IOException|RuntimeException unavailable){return new Location(kind,Availability.UNAVAILABLE,uri,record.info.name);}
    }
    public CompletableFuture<Void> export(String peer,AttachmentRecord expected,Uri destination,int pickerFlags){
        if(destination==null||!"content".equals(destination.getScheme())||destination.getAuthority()==null||(context.getPackageName()+".attachments").equals(destination.getAuthority()))return failed(new IOException("Invalid public export location"));
        if((pickerFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)return failed(new IOException("Export write access unavailable"));
        try{AttachmentExportStore.key(peer,expected);new AttachmentExportStore.Entry(destination.toString(),false,0);}catch(IOException|IllegalArgumentException invalid){return failed(invalid);}
        if(clearing.contains(peer))return failed(new IOException("Attachment history is being cleared"));
        CompletableFuture<Void> future=new CompletableFuture<>();if(closed){future.completeExceptionally(new IOException("Attachment locations closed"));return future;}
        long token=generation(peer);AndroidAttachmentSource.retainSelections(List.of(destination));
        try{worker.execute(()->{
            try{if(!future.isCancelled()){exportNow(peer,expected,destination,pickerFlags,token,future);future.complete(null);}}
            catch(Exception error){future.completeExceptionally(error);}
            finally{AndroidAttachmentSource.releaseSelections(List.of(destination));if(future.isCompletedExceptionally())try{AndroidAttachmentSource.releaseUnusedGrants(context);}catch(IOException ignored){}}
        });}catch(RejectedExecutionException full){AndroidAttachmentSource.releaseSelections(List.of(destination));future.completeExceptionally(full);}return future;
    }
    private void exportNow(String peer,AttachmentRecord expected,Uri destination,int pickerFlags,long token,CompletableFuture<Void> future)throws Exception {
            AttachmentRecord record=store.completedAttachment(peer,expected);if(canceled(peer,token)||future.isCancelled())throw new IOException("Attachment export canceled");
            Path source=store.attachmentFile(peer,record.info,record.outgoing);boolean persistent=false;
            if((pickerFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))==(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)){
                try{resolver.takePersistableUriPermission(destination,Intent.FLAG_GRANT_READ_URI_PERMISSION);persistent=true;}catch(SecurityException unsupported){}
            }
            AttachmentExportStore.Entry previous=exports.load(peer,record);boolean touched=false;
            try{
                touched=true;OutputStream opened=resolver.openOutputStream(destination,"wt");if(opened==null)throw new IOException("Export unavailable");
                try(OutputStream output=opened){VerifiedAttachmentCopy.copy(source,output,record.info,()->canceled(peer,token)||future.isCancelled());}
                if(canceled(peer,token)||future.isCancelled())throw new IOException("Attachment export canceled");store.completedAttachment(peer,record);
                exports.save(peer,record,new AttachmentExportStore.Entry(destination.toString(),persistent,System.currentTimeMillis()));
            }catch(Exception failure){
                if(touched&&previous!=null&&previous.uri().equals(destination.toString()))try{exports.forget(peer,record);}catch(IOException removal){failure.addSuppressed(removal);}throw failure;
            }
    }
    public CompletableFuture<Void> clearPeer(String peer){clearing.add(peer);generations.computeIfAbsent(peer,value->new AtomicLong()).incrementAndGet();return submit(()->{exports.clear(peer);AndroidAttachmentSource.releaseUnusedGrants(context);return null;});}
    /** Called after ChatController's ordered database/private-file clear has finished. */
    void finishClear(String peer){clearing.remove(peer);}
    static Set<String> retainedUris(Context context)throws IOException {return new AttachmentExportStore(context.getFilesDir().getCanonicalFile().toPath().resolve("attachment-exports")).retainedUris();}
    /** System document browsers attempt the selected document's parent; OEM support varies. */
    public static Intent browseIntent(Location location){
        if(location==null||location.kind()!=Kind.DOCUMENT||location.availability()!=Availability.AVAILABLE||location.savedUri()==null)return null;
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(DocumentsContract.EXTRA_INITIAL_URI,location.savedUri());
    }
    public static Intent openSavedIntent(Location location,String mime){
        if(location==null||location.kind()==Kind.APP_PRIVATE||location.availability()!=Availability.AVAILABLE||location.savedUri()==null)return null;
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(location.savedUri(),mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(location.displayName(),location.savedUri()));return intent;
    }
    public void close(){closed=true;worker.shutdown();try{if(worker.awaitTermination(30,TimeUnit.SECONDS))store.close();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}

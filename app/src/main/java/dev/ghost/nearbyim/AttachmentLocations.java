package dev.ghost.nearbyim;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
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
    public record Directory(Uri uri,String name){}
    public record SaveStatus(AttachmentDirectoryStore.State state){}
    public interface Listener {void changed(String peer,String id);}
    private static volatile AttachmentLocations shared;
    public static AttachmentLocations get(Context context)throws IOException {
        AttachmentLocations value=shared;if(value==null)synchronized(AttachmentLocations.class){if(shared==null)shared=new AttachmentLocations(context.getApplicationContext());value=shared;}return value;
    }
    private final Context context;
    private final ContentResolver resolver;
    private final ChatStore store;
    private final AttachmentExportStore exports;
    private final PhotoExportStore photos;
    private final AttachmentDirectoryStore directoryJobs;
    private final SharedPreferences preferences;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private final Map<String,SaveStatus> saved=Collections.synchronizedMap(new LinkedHashMap<String,SaveStatus>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,SaveStatus> entry){return size()>256;}});
    private final Map<String,CompletableFuture<AttachmentDirectoryStore.State>> saving=new HashMap<>();
    private final ConcurrentHashMap<String,AtomicLong> generations=new ConcurrentHashMap<>();
    private final Set<String> clearing=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread thread=new Thread(r,"attachment-save");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor lookup=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread thread=new Thread(r,"attachment-location");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    AttachmentLocations(Context context)throws IOException {
        this.context=context;resolver=context.getContentResolver();store=new ChatStore(context);
        Path root=context.getFilesDir().getCanonicalFile().toPath();exports=new AttachmentExportStore(root.resolve("attachment-exports"));photos=new PhotoExportStore(root.resolve("gallery-exports"));
        directoryJobs=new AttachmentDirectoryStore(root.resolve("receive-directory-jobs"));preferences=context.getSharedPreferences("receive-directory",Context.MODE_PRIVATE);
    }
    public void listen(Listener listener){listeners.add(listener);}
    public void unlisten(Listener listener){listeners.remove(listener);}
    public Directory directory(){String uri=preferences.getString("uri","");return uri.isEmpty()?null:new Directory(Uri.parse(uri),preferences.getString("name",""));}
    public static Intent directoryPicker(Directory previous){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if(previous!=null)intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,previous.uri());return intent;
    }
    /** Folder validation runs separately from a possibly large queued copy. */
    public CompletableFuture<Directory> selectDirectory(Uri uri,int flags){return CompletableFuture.supplyAsync(()->{
        int required=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;boolean retained=false;
        try{
            if((flags&(required|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))!=(required|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))throw new IOException("Persistable folder access unavailable");
            validateTree(uri);AndroidAttachmentSource.retainSelections(List.of(uri));retained=true;resolver.takePersistableUriPermission(uri,required);Directory selected=queryDirectory(uri);
            if(!preferences.edit().putString("uri",uri.toString()).putString("name",selected.name()).commit())throw new IOException("Folder preference could not be saved");
            return selected;
        }catch(IOException|RuntimeException error){throw new CompletionException(error);}
        finally{if(retained)AndroidAttachmentSource.releaseSelections(List.of(uri));try{AndroidAttachmentSource.releaseUnusedGrants(context);}catch(IOException ignored){}}
    });}
    public void stopDirectory(){preferences.edit().remove("uri").remove("name").apply();submit(()->{AndroidAttachmentSource.releaseUnusedGrants(context);return null;});}
    private void validateTree(Uri uri)throws IOException {
        if(uri==null||!"content".equals(uri.getScheme())||!DocumentsContract.isTreeUri(uri)||(context.getPackageName()+".attachments").equals(uri.getAuthority()))throw new IOException("Invalid public directory");
        try{new AttachmentExportStore.Entry(uri.toString(),false,0);if(!DocumentsContract.buildTreeDocumentUri(uri.getAuthority(),DocumentsContract.getTreeDocumentId(uri)).equals(uri))throw new IOException("Expected a directory tree grant");}catch(IllegalArgumentException invalid){throw new IOException("Invalid directory tree",invalid);}
    }
    private Directory queryDirectory(Uri tree)throws IOException {
        validateTree(tree);Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor cursor=resolver.query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
            if(cursor==null||!cursor.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))||(cursor.getLong(2)&DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)==0)throw new IOException("Directory cannot create files");
            return new Directory(tree,cursor.getString(0));
        }
    }
    public SaveStatus savedStatus(String peer,AttachmentRecord record){try{return saved.get(AttachmentExportStore.key(peer,record));}catch(IOException invalid){return null;}}
    private AttachmentDirectoryStore.State saved(String peer,AttachmentRecord record,AttachmentDirectoryStore.State state)throws IOException {
        SaveStatus value=new SaveStatus(state),previous=saved.put(AttachmentExportStore.key(peer,record),value);
        if(!value.equals(previous))main.post(()->{for(Listener listener:listeners)listener.changed(peer,record.info.id);});return state;
    }
    /** Inspect only previously scheduled saves; choosing a folder never migrates old history. */
    public synchronized CompletableFuture<AttachmentDirectoryStore.State> inspectReceived(String peer,AttachmentRecord record){
        SaveStatus known=savedStatus(peer,record);if(known!=null)return CompletableFuture.completedFuture(known.state());
        String key;try{key=AttachmentExportStore.key(peer,record);if(record.outgoing)throw new IOException("Not received");}catch(IOException error){return failed(error);}
        CompletableFuture<AttachmentDirectoryStore.State> active=saving.get(key);if(active!=null)return active;
        long token=generation(peer);CompletableFuture<AttachmentDirectoryStore.State> future=submit(()->{
            if(canceled(peer,token))throw new IOException("Directory save canceled");AttachmentDirectoryStore.Entry entry=directoryJobs.load(peer,record);
            if(entry!=null&&(entry.state()==AttachmentDirectoryStore.State.PENDING||entry.state()==AttachmentDirectoryStore.State.COPIED))return saveReceivedNow(peer,record,entry,token);
            if(entry!=null&&entry.state()==AttachmentDirectoryStore.State.SAVED&&probe(Kind.DOCUMENT,Uri.parse(entry.documentUri()),record).availability()!=Availability.AVAILABLE){entry=new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.MISSING,entry.treeUri(),entry.documentUri());directoryJobs.save(peer,record,entry);}
            return saved(peer,record,entry==null?null:entry.state());
        });return trackSave(key,peer,record,future);
    }
    public synchronized CompletableFuture<AttachmentDirectoryStore.State> saveReceived(String peer,AttachmentRecord record,boolean retry){
        String key;AttachmentDirectoryStore.Entry entry;long token=generation(peer);
        try{
            key=AttachmentExportStore.key(peer,record);if(record.outgoing||canceled(peer,token))throw new IOException("Not an active received file");
            CompletableFuture<AttachmentDirectoryStore.State> active=saving.get(key);if(active!=null)return active;
            entry=directoryJobs.load(peer,record);
            if(!retry&&entry!=null&&entry.state()!=AttachmentDirectoryStore.State.PENDING&&entry.state()!=AttachmentDirectoryStore.State.COPIED)return CompletableFuture.completedFuture(saved(peer,record,entry.state()));
            if(retry||entry==null){Directory selected=directory();if(selected==null){if(retry)throw new IOException("Choose a receive folder first");return CompletableFuture.completedFuture(null);}
                if(!retry&&exports.load(peer,record)!=null)return CompletableFuture.completedFuture(null);
                boolean sameFolder=entry!=null&&entry.treeUri().equals(selected.uri().toString());
                if(!(sameFolder&&entry.state()==AttachmentDirectoryStore.State.COPIED)){
                    String partial=sameFolder&&(entry.state()==AttachmentDirectoryStore.State.FAILED||entry.state()==AttachmentDirectoryStore.State.PENDING)?entry.documentUri():"";
                    entry=new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.PENDING,selected.uri().toString(),partial);
                }
                directoryJobs.save(peer,record,entry);
            }
            saved(peer,record,AttachmentDirectoryStore.State.PENDING);
        }catch(IOException|RuntimeException error){if(directory()!=null&&!canceled(peer,token)&&record!=null&&!record.outgoing)try{saved(peer,record,AttachmentDirectoryStore.State.FAILED);}catch(IOException ignored){}return failed(error);}
        AttachmentDirectoryStore.Entry scheduled=entry;return trackSave(key,peer,record,submit(()->saveReceivedNow(peer,record,scheduled,token)));
    }
    private synchronized CompletableFuture<AttachmentDirectoryStore.State> trackSave(String key,String peer,AttachmentRecord record,CompletableFuture<AttachmentDirectoryStore.State> future){
        saving.put(key,future);future.whenComplete((value,error)->{synchronized(AttachmentLocations.this){saving.remove(key,future);}if(error!=null&&!clearing.contains(peer))try{saved(peer,record,AttachmentDirectoryStore.State.FAILED);}catch(IOException ignored){}});return future;
    }
    private AttachmentDirectoryStore.State saveReceivedNow(String peer,AttachmentRecord expected,AttachmentDirectoryStore.Entry entry,long token)throws Exception {
        Uri destination=entry.documentUri().isEmpty()?null:Uri.parse(entry.documentUri());boolean copied=entry.state()==AttachmentDirectoryStore.State.COPIED,owned=false;
        try{
            AttachmentRecord record=store.completedAttachment(peer,expected);if(canceled(peer,token))throw new IOException("Directory save canceled");
            Uri tree=Uri.parse(entry.treeUri());queryDirectory(tree);
            if(destination==null){
                String mime=DocumentsContract.Document.MIME_TYPE_DIR.equals(record.info.mime)?"application/octet-stream":record.info.mime;
                destination=DocumentsContract.createDocument(resolver,DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),mime,AttachmentInfo.safeName(record.info.name));
                if(destination==null)throw new IOException("Cannot create received file");
            }
            if(!tree.getAuthority().equals(destination.getAuthority())||!DocumentsContract.isTreeUri(destination)||!DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(destination))||DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getDocumentId(destination)))throw new IOException("Destination is outside the selected folder");
            owned=true;
            if(!copied){
                entry=new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.PENDING,entry.treeUri(),destination.toString());directoryJobs.save(peer,record,entry);
                OutputStream opened=resolver.openOutputStream(destination,"wt");if(opened==null)throw new IOException("Folder output unavailable");
                try(OutputStream output=opened){VerifiedAttachmentCopy.copy(store.attachmentFile(peer,record.info,false),output,record.info,()->canceled(peer,token));}
                copied=true;entry=new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.COPIED,entry.treeUri(),destination.toString());directoryJobs.save(peer,record,entry);
            }
            if(canceled(peer,token))throw new IOException("Directory save canceled");store.completedAttachment(peer,record);
            if(probe(Kind.DOCUMENT,destination,record).availability()!=Availability.AVAILABLE)throw new IOException("Saved document unavailable");
            exports.save(peer,record,new AttachmentExportStore.Entry(destination.toString(),false,System.currentTimeMillis()));
            directoryJobs.save(peer,record,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.SAVED,entry.treeUri(),destination.toString()));
            return saved(peer,record,AttachmentDirectoryStore.State.SAVED);
        }catch(Exception failure){
            if(destination!=null&&!copied&&owned)try{if(DocumentsContract.deleteDocument(resolver,destination))destination=null;}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(!owned&&!copied)destination=null;
            if(!canceled(peer,token))try{directoryJobs.save(peer,expected,new AttachmentDirectoryStore.Entry(copied?AttachmentDirectoryStore.State.COPIED:AttachmentDirectoryStore.State.FAILED,entry.treeUri(),destination==null?"":destination.toString()));}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    private static <T> CompletableFuture<T> failed(Throwable error){CompletableFuture<T> future=new CompletableFuture<>();future.completeExceptionally(error);return future;}
    private <T> CompletableFuture<T> submit(Callable<T> work){return submit(worker,work);}
    private <T> CompletableFuture<T> submit(ThreadPoolExecutor executor,Callable<T> work){
        CompletableFuture<T> future=new CompletableFuture<>();if(closed){future.completeExceptionally(new IOException("Attachment locations closed"));return future;}
        try{executor.execute(()->{try{future.complete(work.call());}catch(Exception error){future.completeExceptionally(error);}});}catch(RejectedExecutionException full){future.completeExceptionally(full);}return future;
    }
    private long generation(String peer){return generations.computeIfAbsent(peer,value->new AtomicLong()).get();}
    private boolean canceled(String peer,long expected){return closed||clearing.contains(peer)||generation(peer)!=expected;}
    public CompletableFuture<Location> locate(String peer,AttachmentRecord expected){
        try{AttachmentExportStore.key(peer,expected);}catch(IOException invalid){return failed(invalid);}
        long token=generation(peer);return submit(lookup,()->{
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
    public CompletableFuture<Void> clearPeer(String peer){clearing.add(peer);generations.computeIfAbsent(peer,value->new AtomicLong()).incrementAndGet();return submit(()->{exports.clear(peer);directoryJobs.clear(peer);saved.clear();AndroidAttachmentSource.releaseUnusedGrants(context);return null;});}
    /** Called after ChatController's ordered database/private-file clear has finished. */
    void finishClear(String peer){clearing.remove(peer);}
    static Set<String> retainedUris(Context context)throws IOException {
        Path root=context.getFilesDir().getCanonicalFile().toPath();Set<String> retained=new HashSet<>(new AttachmentExportStore(root.resolve("attachment-exports")).retainedUris());
        retained.addAll(new AttachmentDirectoryStore(root.resolve("receive-directory-jobs")).retainedTrees());
        String selected=context.getSharedPreferences("receive-directory",Context.MODE_PRIVATE).getString("uri","");if(!selected.isEmpty())retained.add(selected);
        for(String value:new ArrayList<>(retained)){Uri uri=Uri.parse(value);if(DocumentsContract.isTreeUri(uri))retained.add(DocumentsContract.buildTreeDocumentUri(uri.getAuthority(),DocumentsContract.getTreeDocumentId(uri)).toString());}
        return Set.copyOf(retained);
    }
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
    public void close(){closed=true;worker.shutdown();lookup.shutdown();try{if(worker.awaitTermination(30,TimeUnit.SECONDS)&&lookup.awaitTermination(30,TimeUnit.SECONDS))store.close();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}

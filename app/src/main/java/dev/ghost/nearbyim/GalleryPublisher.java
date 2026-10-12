package dev.ghost.nearbyim;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Serial, crash-reconcilable gallery publication. Transfer receipts and originals are immutable. */
final class GalleryPublisher implements AutoCloseable {
    record Result(PhotoExportStore.State state,Uri uri){}
    interface Listener {void changed(String peer,String id);}
    private static final String GALLERY_DIRECTORY="Wozai";
    private static volatile GalleryPublisher shared;
    static GalleryPublisher get(Context context)throws IOException {GalleryPublisher value=shared;if(value==null)synchronized(GalleryPublisher.class){if(shared==null)shared=new GalleryPublisher(context.getApplicationContext());value=shared;}return value;}
    private final Context context;private final ContentResolver resolver;private final SharedPreferences preferences;private final PhotoExportStore journal;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"gallery-publish");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Result> status=Collections.synchronizedMap(new LinkedHashMap<String,Result>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Result> entry){return size()>256;}});
    private final Map<String,CompletableFuture<Result>> inspecting=new HashMap<>();
    private final ConcurrentHashMap<String,AtomicLong> generations=new ConcurrentHashMap<>();
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();private volatile boolean closed;
    GalleryPublisher(Context context)throws IOException {this.context=context;resolver=context.getContentResolver();preferences=context.getSharedPreferences("gallery",Context.MODE_PRIVATE);journal=new PhotoExportStore(context.getFilesDir().getCanonicalFile().toPath().resolve("gallery-exports"));}
    boolean automatic(){return preferences.getBoolean("automatic",true);}
    void automatic(boolean enabled){preferences.edit().putBoolean("automatic",enabled).apply();}
    void listen(Listener listener){listeners.add(listener);}void unlisten(Listener listener){listeners.remove(listener);}
    private long generation(String peer){return generations.computeIfAbsent(peer,p->new AtomicLong()).get();}
    private boolean canceled(String peer,long generation){return closed||generation(peer)!=generation;}
    private String key(String peer,AttachmentRecord record)throws IOException{return PhotoExportStore.key(peer,record);}
    Result cached(String peer,AttachmentRecord record){try{return status.get(key(peer,record));}catch(IOException invalid){return null;}}
    private void changed(String peer,AttachmentRecord record,Result result)throws IOException {Result previous=status.put(key(peer,record),result);if(result.equals(previous))return;main.post(()->{for(Listener listener:listeners)listener.changed(peer,record.info.id);});}
    private <T> CompletableFuture<T> submit(Callable<T> work){CompletableFuture<T> future=new CompletableFuture<>();if(closed){future.completeExceptionally(new IOException("Gallery publisher closed"));return future;}try{worker.execute(()->{try{future.complete(work.call());}catch(Exception failure){future.completeExceptionally(failure);}});}catch(RejectedExecutionException full){future.completeExceptionally(new IOException("Gallery queue full",full));}return future;}
    synchronized CompletableFuture<Result> inspect(String peer,AttachmentRecord record){
        Result known=cached(peer,record);if(known!=null)return CompletableFuture.completedFuture(known);
        String id;try{id=key(peer,record);}catch(IOException invalid){CompletableFuture<Result> failed=new CompletableFuture<>();failed.completeExceptionally(invalid);return failed;}
        CompletableFuture<Result> pending=inspecting.get(id);if(pending!=null)return pending;
        CompletableFuture<Result> future=submit(()->{PhotoExportStore.Entry entry=journal.load(peer,record);
            if(entry!=null&&automatic()&&(entry.state()==PhotoExportStore.State.PENDING||entry.state()==PhotoExportStore.State.COPIED))return exportNow(peer,record,AttachmentTransfer.file(context.getFilesDir().toPath().resolve("attachments").resolve(peer),record.info,record.outgoing),false,generation(peer));
            Result value=entry==null?new Result(null,null):new Result(entry.state(),uri(entry));status.put(id,value);if(value.state()!=null)main.post(()->{for(Listener listener:listeners)listener.changed(peer,record.info.id);});return value;});
        inspecting.put(id,future);future.whenComplete((value,error)->{synchronized(GalleryPublisher.this){inspecting.remove(id);}});return future;
    }
    CompletableFuture<Result> export(String peer,AttachmentRecord record,Path original,boolean manual){long expected=generation(peer);CompletableFuture<Result> future=submit(()->exportNow(peer,record,original,manual,expected));future.whenComplete((value,error)->{if(error!=null)try{changed(peer,record,new Result(PhotoExportStore.State.FAILED,null));}catch(IOException ignored){}});return future;}
    private Result exportNow(String peer,AttachmentRecord record,Path original,boolean manual,long expected)throws Exception {

        if(canceled(peer,expected))throw new IOException("Gallery task canceled");PhotoExportStore.Entry entry=journal.load(peer,record);
        if(!manual&&!automatic()){Result result=entry==null?new Result(null,null):new Result(entry.state(),uri(entry));status.put(key(peer,record),result);return result;}
        if(!manual&&entry!=null&&(entry.state()==PhotoExportStore.State.FAILED||entry.state()==PhotoExportStore.State.MISSING)){Result result=new Result(entry.state(),uri(entry));status.put(key(peer,record),result);return result;}
        Uri destination=entry==null?null:uri(entry);boolean publishing=false;
        try{
            if(Build.VERSION.SDK_INT<=28&&context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)throw new LocalizedIOException(UiText.of("gallerySavePermission"));
            if(entry!=null&&(entry.state()==PhotoExportStore.State.SAVED||entry.state()==PhotoExportStore.State.MISSING)&&destination!=null){
                if(exists(destination))return finish(peer,record,destination);
                journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.MISSING,destination.toString()));changed(peer,record,new Result(PhotoExportStore.State.MISSING,destination));if(!manual)return new Result(PhotoExportStore.State.MISSING,destination);destination=null;entry=null;
            }
            if(entry!=null&&entry.state()==PhotoExportStore.State.COPIED&&destination!=null&&exists(destination)){
                destination=publish(destination,record.info.mime);return finish(peer,record,destination);
            }
            changed(peer,record,new Result(PhotoExportStore.State.PENDING,null));
            journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.PENDING,destination==null?"":destination.toString()));publishing=true;
            if(Build.VERSION.SDK_INT>=29){
                if(destination==null)destination=find(key(peer,record));
                if(destination!=null&&!pending(destination))return finish(peer,record,destination);
                if(destination==null){ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,AttachmentInfo.safeName(record.info.name));values.put(MediaStore.Images.Media.MIME_TYPE,record.info.mime);values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/"+GALLERY_DIRECTORY+"/");values.put(MediaStore.Images.Media.DESCRIPTION,"wozai:"+key(peer,record));values.put(MediaStore.Images.Media.IS_PENDING,1);destination=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(destination==null)throw new IOException("Gallery insert failed");}
                journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.PENDING,destination.toString()));
                ParcelFileDescriptor fd=resolver.openFileDescriptor(destination,"wt");if(fd==null)throw new IOException("Gallery output unavailable");
                try(ParcelFileDescriptor.AutoCloseOutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(fd)){VerifiedAttachmentCopy.copy(original,output,record.info,()->canceled(peer,expected));output.getChannel().force(true);}
            }else{
                Path directory=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toPath().resolve(GALLERY_DIRECTORY);Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw new IOException("Unsafe gallery directory");
                String safe=AttachmentInfo.safeName(record.info.name);int dot=safe.lastIndexOf('.');String stem=dot>0?safe.substring(0,dot):safe,extension=dot>0?safe.substring(dot):"";if(stem.length()>100)stem=stem.substring(0,100);Path target=directory.resolve(stem+"-"+key(peer,record).substring(0,24)+extension);
                destination=Uri.fromFile(target.toFile());journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.PENDING,destination.toString()));
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))VerifiedAttachmentCopy.copy(target,OutputStream.nullOutputStream(),record.info,()->canceled(peer,expected));
                else{Path temporary=directory.resolve(".wozai-"+key(peer,record)+".tmp");try{
                    if(Files.isSymbolicLink(temporary))throw new IOException("Unsafe gallery temporary");try(FileOutputStream output=new FileOutputStream(temporary.toFile())){VerifiedAttachmentCopy.copy(original,output,record.info,()->canceled(peer,expected));output.getChannel().force(true);}
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                }finally{Files.deleteIfExists(temporary);}}
            }
            journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.COPIED,destination.toString()));
            if(canceled(peer,expected))throw new IOException("Gallery task canceled");destination=publish(destination,record.info.mime);return finish(peer,record,destination);
        }catch(Exception failure){
            if(publishing&&destination!=null)cleanupPending(destination);
            if(entry==null||entry.state()!=PhotoExportStore.State.SAVED&&entry.state()!=PhotoExportStore.State.MISSING){try{journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.FAILED,""));}catch(IOException ignored){}}
            changed(peer,record,new Result(PhotoExportStore.State.FAILED,null));throw failure;
        }

    }
    private static Uri uri(PhotoExportStore.Entry entry){return entry.uri().isEmpty()?null:Uri.parse(entry.uri());}
    private Result finish(String peer,AttachmentRecord record,Uri uri)throws IOException {journal.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.SAVED,uri.toString()));Result result=new Result(PhotoExportStore.State.SAVED,uri);changed(peer,record,result);return result;}
    private boolean exists(Uri uri)throws IOException {if("file".equals(uri.getScheme()))return Files.isRegularFile(file(uri),LinkOption.NOFOLLOW_LINKS);try(android.content.res.AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(uri,"r")){return descriptor!=null;}catch(FileNotFoundException absent){return false;}catch(SecurityException denied){throw new LocalizedIOException(UiText.of("gallerySavePermission"),denied);}}
    private Path file(Uri uri)throws IOException {Path path=Paths.get(java.net.URI.create(uri.toString())).toAbsolutePath().normalize(),root=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toPath().resolve(GALLERY_DIRECTORY).toAbsolutePath().normalize();if(!path.getParent().equals(root)||Files.isSymbolicLink(root)||Files.isSymbolicLink(path))throw new IOException("Invalid gallery file location");return path;}
    private Uri find(String key){try(Cursor cursor=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Images.Media._ID},MediaStore.Images.Media.DESCRIPTION+"=? AND "+MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=?",new String[]{"wozai:"+key,context.getPackageName()},null)){return cursor!=null&&cursor.moveToFirst()?ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)):null;}}
    private boolean pending(Uri uri)throws IOException {if(!"media".equals(uri.getAuthority()))throw new IOException("Invalid gallery media location");try(Cursor cursor=resolver.query(uri,new String[]{MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.OWNER_PACKAGE_NAME},null,null,null)){if(cursor==null||!cursor.moveToFirst()||!context.getPackageName().equals(cursor.getString(1)))throw new IOException("Gallery item is not owned by this app");return cursor.getInt(0)!=0;}}
    private Uri publish(Uri uri,String mime)throws Exception {
        if(Build.VERSION.SDK_INT>=29){if(pending(uri)){ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new IOException("Gallery publication failed");}return uri;}
        if("content".equals(uri.getScheme()))return uri;Path path=file(uri);CountDownLatch scan=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Uri> scanned=new java.util.concurrent.atomic.AtomicReference<>();MediaScannerConnection.scanFile(context,new String[]{path.toString()},new String[]{mime},(name,result)->{scanned.set(result);scan.countDown();});if(!scan.await(20,TimeUnit.SECONDS)||scanned.get()==null)throw new IOException("Gallery indexing did not finish");return scanned.get();
    }
    private void cleanupPending(Uri uri){if(Build.VERSION.SDK_INT>=29)try{if(pending(uri))resolver.delete(uri,null,null);}catch(Exception ignored){}}
    CompletableFuture<Void> clearPeer(String peer){generation(peer);generations.get(peer).incrementAndGet();return submit(()->{for(PhotoExportStore.Entry entry:journal.entries(peer))if(!entry.uri().isEmpty())cleanupPending(Uri.parse(entry.uri()));journal.clear(peer);status.clear();return null;});}
    public void close(){closed=true;worker.shutdown();try{worker.awaitTermination(30,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}listeners.clear();}
}

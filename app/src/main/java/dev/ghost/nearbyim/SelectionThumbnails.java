package dev.ghost.nearbyim;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One shared decode per URI, cancelable subscribers and a byte-bounded cache. UI-thread owner. */
final class SelectionThumbnails implements AutoCloseable {
    private final ContentResolver resolver;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,2,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"selection-thumbnail");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final LruCache<Uri,Bitmap> cache=new LruCache<Uri,Bitmap>(4*1024*1024){protected int sizeOf(Uri uri,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    private final Map<Uri,Request> requests=new HashMap<>();
    private final Set<Uri> failed=new HashSet<>();
    private boolean closed;
    private static final class Request {final Set<Consumer<Bitmap>> subscribers=new HashSet<>();Future<?> future;}
    SelectionThumbnails(ContentResolver resolver){this.resolver=resolver;}
    AutoCloseable bind(Uri uri,Consumer<Bitmap> receive){
        if(closed)return ()->{};Bitmap ready=cache.get(uri);if(ready!=null){receive.accept(ready);return ()->{};}if(failed.contains(uri))return ()->{};
        Request request=requests.get(uri);if(request==null){request=new Request();requests.put(uri,request);Request expected=request;
            try{request.future=worker.submit(()->{Bitmap decoded=null;try{decoded=PhotoDecoder.decode(resolver,uri,144);}catch(Exception|OutOfMemoryError ignored){}Bitmap result=decoded;
                main.post(()->{if(closed||requests.get(uri)!=expected)return;requests.remove(uri);if(result!=null)cache.put(uri,result);else {failed.add(uri);if(failed.size()>256)failed.remove(failed.iterator().next());}for(Consumer<Bitmap> subscriber:List.copyOf(expected.subscribers))subscriber.accept(result);});
            });}catch(RejectedExecutionException full){requests.remove(uri);return ()->{};}
        }request.subscribers.add(receive);Request expected=request;
        return ()->{expected.subscribers.remove(receive);if(expected.subscribers.isEmpty()&&requests.remove(uri,expected)){expected.future.cancel(true);worker.remove((Runnable)expected.future);}};
    }
    public void close(){closed=true;for(Request request:requests.values())request.future.cancel(true);requests.clear();worker.shutdownNow();cache.evictAll();failed.clear();}
}

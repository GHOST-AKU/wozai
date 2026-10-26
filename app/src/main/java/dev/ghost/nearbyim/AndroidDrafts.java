package dev.ghost.nearbyim;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.ghost.nearbyim.core.DraftStore;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** One application-scoped writer prevents Activity recreation from reordering draft saves. */
final class AndroidDrafts {
    private static AndroidDrafts instance;
    static synchronized AndroidDrafts get(Context context){if(instance==null)instance=new AndroidDrafts(context.getApplicationContext());return instance;}
    private final DraftStore store;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"draft-storage");t.setDaemon(true);return t;});
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<>(16,.75f,true),pending=new LinkedHashMap<>();
    private boolean writing;
    private AndroidDrafts(Context context){store=new DraftStore(context.getFilesDir().toPath().resolve("drafts-v1"));}
    void load(String peer,BiConsumer<String,IOException> result){worker.execute(()->{
        String text;IOException failure=null;
        try{synchronized(this){text=cache.get(peer);}if(text==null)text=store.load(peer);if(text==null)text="";
            synchronized(this){if(cache.containsKey(peer))text=cache.get(peer);else{cache.put(peer,text);trim();}}
        }catch(IOException error){text="";failure=error;}
        String value=text;IOException error=failure;main.post(()->result.accept(value,error));
    });}
    void clearIfUnchanged(String peer,String sent,java.util.function.Consumer<IOException> failed){
        worker.execute(()->{
            try{String saved=store.load(peer);synchronized(this){String latest=cache.containsKey(peer)?cache.get(peer):saved;if(Objects.equals(latest,sent))save(peer,"",failed);}}
            catch(IOException error){main.post(()->failed.accept(error));}
        });
    }
    synchronized void save(String peer,String text,java.util.function.Consumer<IOException> failed){
        if(!pending.containsKey(peer)&&pending.size()>=32){main.post(()->failed.accept(new IOException("Draft writer is busy")));return;}
        cache.put(peer,text);pending.put(peer,text);trim();if(writing)return;writing=true;
        worker.execute(()->{
            while(true){String id,value;synchronized(this){if(pending.isEmpty()){writing=false;trim();return;}id=pending.keySet().iterator().next();value=pending.remove(id);}
                try{store.save(id,value);}catch(IOException error){main.post(()->failed.accept(error));}
            }
        });
    }
    private void trim(){Iterator<String> keys=cache.keySet().iterator();while(cache.size()>16&&keys.hasNext()){String id=keys.next();if(!pending.containsKey(id))keys.remove();}}
}

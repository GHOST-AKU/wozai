package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One bounded, durable send queue per authenticated identity pair and active session. */
public final class AttachmentBatchQueue implements AutoCloseable {
    public static final int MAX_PENDING=100;
    public record Selection(String reference,String generation,String name,String mime,long size,boolean exact) {
        public Selection {
            if(reference==null||reference.isEmpty()||reference.length()>8192||generation==null||!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||size< -1||size>TransferLimits.MAX_FILE_BYTES)throw new IllegalArgumentException("Invalid selection source");
            try{AttachmentInfo.v2(generation,name,mime,Math.max(0,size),null,0);}catch(IOException e){throw new IllegalArgumentException(e);}
        }
    }
    public record Item(String batchId,AttachmentInfo info,Selection selection,String state,long transferred) {
        public boolean terminal(){return Set.of("delivered","canceled","failed","rejected").contains(state);}
        public AttachmentRecord record()throws IOException{return new AttachmentRecord(info,true,state,transferred);}
    }
    public interface SourceResolver {AttachmentSource open(Selection selected)throws IOException;}
    public interface Listener {void changed(AttachmentRecord record)throws IOException;default void finished(AttachmentRecord record){}}
    private final Path root;
    private final AttachmentTransferV2 engine;
    private final SourceResolver sources;
    private final AttachmentBatchStore store;
    private final List<Item> items=new ArrayList<>();
    private final ThreadPoolExecutor actor=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"attachment-batch");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile List<Item> snapshot=List.of();
    private volatile Listener listener=record->{};
    private volatile boolean paused,closed;
    private volatile AttachmentSource preparing;
    private CompletableFuture<Void> shutdownFuture;
    private volatile String active;
    private final Set<String> cancelRequests=ConcurrentHashMap.newKeySet();
    AttachmentBatchQueue(Path root,AttachmentTransferV2 engine,SourceResolver sources)throws IOException {
        this.root=root;this.engine=engine;this.sources=sources;
        TransferTaskKey identity=engine.taskIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString());
        store=new AttachmentBatchStore(root,identity.localRoot(),identity.remoteRoot());var saved=store.read();
        paused=saved.paused();
        for(Item item:saved.items()){
            if(!item.terminal()){
                TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).load(engine.taskIdentity(item.info().id,item.selection().generation())).orElse(null);
                if(checkpoint!=null&&checkpoint.state()==TransferCheckpoint.State.COMPLETE)item=new Item(item.batchId(),checkpoint.info(),item.selection(),"delivered",checkpoint.info().size);
                else if(checkpoint!=null&&checkpoint.state()==TransferCheckpoint.State.CANCELED)item=new Item(item.batchId(),checkpoint.info(),item.selection(),"canceled",checkpoint.writtenOffset());
                else{paused=true;if(checkpoint!=null)item=new Item(item.batchId(),checkpoint.info(),new Selection(checkpoint.sourceReference(),checkpoint.key().sourceGeneration(),checkpoint.info().name,checkpoint.info().mime,checkpoint.info().size,true),"paused",checkpoint.writtenOffset());else if(!item.state().equals("queued"))item=new Item(item.batchId(),item.info(),item.selection(),"paused",item.transferred());}
            }
            items.add(item);
        }
        snapshot=List.copyOf(items);engine.taskObserver(this::observe);
    }
    public void listener(Listener value){listener=Objects.requireNonNull(value);later(()->{for(Item item:List.copyOf(items))try{listener.changed(item.record());}catch(IOException failure){paused=true;}});}
    public List<Item> items(){return snapshot;}
    public boolean pending(){return snapshot.stream().anyMatch(item->!item.terminal());}
    public boolean paused(){return paused;}
    public static Selection selectFile(Path path)throws IOException {
        String mime=Files.probeContentType(path);if(mime==null||!mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}"))mime="application/octet-stream";
        try(FileAttachmentSource source=new FileAttachmentSource(path)){return describe(source,path.getFileName().toString(),mime);}
    }
    public static Selection describe(AttachmentSource source,String name,String mime){return new Selection(source.persistentReference(),source.generation(),name,mime,source.size(),source.seekable());}
    public static AttachmentTransferV2.SourceResolver fileResolver(){return checkpoint->{
        String ref=checkpoint.sourceReference();
        if(ref.startsWith("snapshot:\n")){Path path=Paths.get(java.net.URI.create(ref.substring(10).split("\n",2)[0]));return OwnedSnapshotSource.restore(path.getParent(),ref,checkpoint.key().sourceGeneration());}
        FileAttachmentSource source=new FileAttachmentSource(Paths.get(java.net.URI.create(ref.split("\n",2)[0])),checkpoint.key().sourceGeneration());
        if(!source.persistentReference().equals(ref)){source.close();throw new IOException("Attachment source changed");}return source;
    };}
    public CompletableFuture<String> enqueue(List<Selection> selection) {
        List<Selection> selected=List.copyOf(selection);return command(()->{
            long pending=items.stream().filter(item->!item.terminal()).count();
            if(selected.isEmpty()||selected.size()>MAX_PENDING||pending+selected.size()>MAX_PENDING)throw new IOException("Batch selection exceeds bounds");
            List<Item> before=List.copyOf(items);
            while(items.size()+selected.size()>AttachmentBatchStore.MAX_ITEMS){int i=0;while(i<items.size()&&!items.get(i).terminal())i++;if(i==items.size())throw new IOException("Batch history bound exceeded");items.remove(i);}
            String batch=UUID.randomUUID().toString();long time=System.currentTimeMillis();List<Item> added=new ArrayList<>();
            for(Selection s:selected)added.add(new Item(batch,AttachmentInfo.v2(UUID.randomUUID().toString(),s.name(),s.mime(),Math.max(0,s.size()),null,time),s,"queued",0));
            items.addAll(added);try{persist();}catch(IOException error){items.clear();items.addAll(before);snapshot=List.copyOf(items);throw error;}for(Item item:added)listener.changed(item.record());later(this::pump);return batch;
        });
    }
    private <T> CompletableFuture<T> command(Callable<T> work){
        CompletableFuture<T> future=new CompletableFuture<>();if(closed){future.completeExceptionally(new IOException("Batch session closed"));return future;}
        try{actor.execute(()->{try{if(closed)throw new IOException("Batch session closed");future.complete(work.call());}catch(Exception e){future.completeExceptionally(e);}});}
        catch(RejectedExecutionException full){future.completeExceptionally(new IOException("Batch queue full",full));}return future;
    }
    private void later(Runnable work){if(closed)return;try{actor.execute(()->{if(!closed)work.run();});}catch(RejectedExecutionException full){paused=true;}}
    private void persist()throws IOException {store.write(paused,items);snapshot=List.copyOf(items);}
    private int index(String id){for(int i=0;i<items.size();i++)if(items.get(i).info().id.equals(id))return i;return -1;}
    private void state(Item next)throws IOException {int i=index(next.info().id);if(i<0)return;items.set(i,next);persist();listener.changed(next.record());}
    private void pump() {
        if(closed||paused||active!=null)return;
        Item item=items.stream().filter(e->!e.terminal()).findFirst().orElse(null);if(item==null)return;
        active=item.info().id;
        try {
            TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).load(engine.taskIdentity(item.info().id,item.selection().generation())).orElse(null);
            if(checkpoint!=null){
                if(checkpoint.state()==TransferCheckpoint.State.COMPLETE){observe(new AttachmentRecord(checkpoint.info(),true,"delivered",checkpoint.info().size));return;}
                if(checkpoint.state()==TransferCheckpoint.State.CANCELED){fail(item,null,"canceled");return;}
                AttachmentSource source=null;try{source=sources.open(new Selection(checkpoint.sourceReference(),checkpoint.key().sourceGeneration(),checkpoint.info().name,checkpoint.info().mime,checkpoint.info().size,true));}catch(IOException missing){if(checkpoint.info().hash==null)throw missing;}
                engine.resume(checkpoint.key(),source).whenComplete((id,error)->{if(error!=null)later(()->fail(item,error,"failed"));});return;
            }
            state(new Item(item.batchId(),item.info(),item.selection(),"preparing",0));
            AttachmentSource opened=sources.open(item.selection());preparing=opened;
            if(cancelRequests.contains(active))throw new IOException("Preparation canceled");
            if(paused||closed){opened.close();preparing=null;active=null;state(new Item(item.batchId(),item.info(),item.selection(),"paused",0));return;}
            AttachmentSource source=engine.prepareSource(opened);preparing=source;
            if(item.selection().size()>=0&&source.size()!=item.selection().size())throw new IOException("Attachment source size changed");
            Selection descriptor=describe(source,item.info().name,item.info().mime);
            AttachmentInfo info=AttachmentInfo.v2(item.info().id,item.info().name,item.info().mime,source.size(),null,item.info().time);
            Item ready=new Item(item.batchId(),info,descriptor,"queued",0);state(ready);
            if(cancelRequests.contains(active))throw new IOException("Preparation canceled");
            if(paused||closed){source.close();preparing=null;active=null;return;}
            preparing=null;
            engine.offer(source,info.name,info.mime,info.id,info.time).whenComplete((id,error)->{if(error!=null)later(()->fail(ready,error,"failed"));});
        }catch(Exception error){if(preparing!=null){try{preparing.close();preparing.discard();}catch(IOException ignored){}preparing=null;}fail(item,error,cancelRequests.contains(item.info().id)?"canceled":paused||closed?"paused":"failed");}
    }
    private void fail(Item item,Throwable error,String status){
        active=null;int i=index(item.info().id);if(i>=0)item=items.get(i);try{state(new Item(item.batchId(),item.info(),item.selection(),status,item.transferred()));}catch(IOException storageFailure){paused=true;}
        cancelRequests.remove(item.info().id);if(!paused&&!closed)pump();
    }
    private void observe(AttachmentRecord record){if(!record.outgoing)return;later(()->{
        int i=index(record.info.id);if(i<0)return;Item previous=items.get(i);if(previous.terminal())return;
        Item next=new Item(previous.batchId(),record.info,previous.selection(),record.state,record.transferred);
        try{
            // Core already persisted its own event before this observer. Only journal queue state here.
            items.set(i,next);if(!previous.state().equals(next.state())||next.terminal())persist();else snapshot=List.copyOf(items);
            if(record.state.equals("delivered")||record.state.equals("failed")||record.state.equals("canceled")||record.state.equals("rejected")){listener.finished(record);cancelRequests.remove(record.info.id);if(record.info.id.equals(active))active=null;pump();}
        }catch(IOException failure){paused=true;}
    });}
    public CompletableFuture<Void> pauseAll(){paused=true;AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}
        return command(()->{persist();return active;}).thenCompose(id->id==null?CompletableFuture.completedFuture(null):engine.pause(id,true));
    }
    public CompletableFuture<Void> resumeAll(){return command(()->{paused=false;active=null;persist();later(this::pump);return null;});}
    public CompletableFuture<Void> cancel(String id){if(snapshot.stream().anyMatch(item->item.info().id.equals(id)&&!item.terminal()))cancelRequests.add(id);if(id.equals(active)){AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}}return command(()->{
        int i=index(id);if(i<0)return null;Item item=items.get(i);if(item.terminal())return null;
        if(id.equals(active)){return engine.cancel(id,true);}
        state(new Item(item.batchId(),item.info(),item.selection(),"canceled",item.transferred()));return cancelStored(item);
    }).thenCompose(value->value==null?CompletableFuture.completedFuture(null):value);}
    public CompletableFuture<Void> cancelAll(){paused=true;for(Item item:snapshot)if(!item.terminal())cancelRequests.add(item.info().id);AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}return command(()->{
        CompletableFuture<Void> done=CompletableFuture.completedFuture(null);for(Item item:List.copyOf(items))if(!item.terminal()){
            boolean running=item.info().id.equals(active);if(!running)state(new Item(item.batchId(),item.info(),item.selection(),"canceled",item.transferred()));
            done=done.thenCompose(ignored->running?engine.cancel(item.info().id,true):cancelStored(item));
        }return done;
    }).thenCompose(done->done);}
    private CompletableFuture<Void> cancelStored(Item item){return engine.cancel(item.info().id,true).thenRun(()->{discardSnapshot(item);try{listener.finished(new AttachmentRecord(item.info(),true,"canceled",item.transferred()));}catch(IOException error){throw new CompletionException(error);}});}
    private void discardSnapshot(Item item){if(item.selection().reference().startsWith("snapshot:\n"))try{OwnedSnapshotSource.discardSaved(root.resolve(".sources-v2"),item.selection().reference());}catch(IOException error){throw new CompletionException(error);}}
    public synchronized CompletableFuture<Void> shutdown(){
        if(shutdownFuture!=null)return shutdownFuture;
        paused=true;AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}
        CompletableFuture<Void> done=command(()->{persist();return null;}).thenCompose(ignored->engine.shutdown()).thenCompose(ignored->command(()->{closed=true;actor.shutdown();return null;}));
        shutdownFuture=done.whenComplete((value,error)->{if(error!=null){closed=true;actor.shutdown();}});return shutdownFuture;
    }
    public void close()throws IOException {try{shutdown().get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Batch shutdown failed",e);}}
    public static List<String> pendingReferences(Path root)throws IOException {return AttachmentBatchStore.references(root);}
}

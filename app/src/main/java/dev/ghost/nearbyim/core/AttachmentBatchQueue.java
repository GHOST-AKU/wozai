package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** One bounded, durable send queue per authenticated identity pair and active session. */
public final class AttachmentBatchQueue implements AutoCloseable {
    public static final int MAX_PENDING=100;
    public record Selection(String reference,String generation,String name,String mime,long size,boolean exact) {
        public Selection {
            if(reference==null||reference.isEmpty()||reference.length()>8192||generation==null||!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||size< -1||size>TransferLimits.MAX_FILE_BYTES)throw new IllegalArgumentException("Invalid selection source");
            try{AttachmentInfo.v2(generation,name,mime,Math.max(0,size),null,0);}catch(IOException e){throw new IllegalArgumentException(e);}
        }
    }
    public record Item(String batchId,AttachmentInfo info,Selection selection,String state,long transferred,Selection origin,String retryOf) {
        public Item(String batchId,AttachmentInfo info,Selection selection,String state,long transferred){this(batchId,info,selection,state,transferred,selection,"");}
        public boolean terminal(){return Set.of("delivered","canceled","failed","rejected").contains(state);}
        public AttachmentRecord record()throws IOException{return new AttachmentRecord(info,true,state,transferred);}
    }
    public enum RetryState {AVAILABLE,RETRYING,RETRIED,UNAVAILABLE,INELIGIBLE}
    public record RetryBatchResult(List<String> queuedIds,List<String> unavailableIds) {
        public RetryBatchResult {queuedIds=List.copyOf(queuedIds);unavailableIds=List.copyOf(unavailableIds);}
    }
    public static final class RetrySourceUnavailableException extends IOException {
        public RetrySourceUnavailableException(Throwable cause){super("Retry source unavailable; select the file again",cause);}
    }
    public interface SourceResolver {AttachmentSource open(Selection selected)throws IOException;}
    public interface Listener {void changed(AttachmentRecord record)throws IOException;default void finished(AttachmentRecord record){}}
    private final Path root;
    private final AttachmentTransferV2 engine;
    private final SourceResolver sources;
    private final AttachmentBatchStore store;
    private final List<Item> items=new ArrayList<>();
    private final Map<String,CompletableFuture<?>> routeGates=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor actor=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"attachment-batch");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile List<Item> snapshot=List.of();
    private volatile Listener listener=record->{};
    private volatile boolean paused,closed,closing,routeSuspended;
    private volatile AttachmentSource preparing,retryProbe;
    private CompletableFuture<Void> shutdownFuture;
    private volatile String active;
    private final Set<String> cancelRequests=ConcurrentHashMap.newKeySet();
    private final Set<String> resumeRequests=ConcurrentHashMap.newKeySet();
    private final Set<String> automaticResumes=ConcurrentHashMap.newKeySet();
    AttachmentBatchQueue(Path root,AttachmentTransferV2 engine,SourceResolver sources)throws IOException {
        this.root=root;this.engine=engine;this.sources=sources;
        TransferTaskKey identity=engine.taskIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString());
        store=new AttachmentBatchStore(root,identity.localRoot(),identity.remoteRoot());var saved=store.read();
        // A previous session's shutdown pauses its existing records, not future user selections.
        paused=false;
        for(Item item:saved.items()){
            if(!item.terminal()){
                TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).load(engine.taskIdentity(item.info().id,item.selection().generation())).orElse(null);
                if(checkpoint!=null&&checkpoint.state()==TransferCheckpoint.State.COMPLETE)item=new Item(item.batchId(),checkpoint.info(),item.selection(),"delivered",checkpoint.info().size,item.origin(),item.retryOf());
                else if(checkpoint!=null&&checkpoint.state()==TransferCheckpoint.State.CANCELED)item=new Item(item.batchId(),checkpoint.info(),item.selection(),"canceled",checkpoint.writtenOffset(),item.origin(),item.retryOf());
                else{if(checkpoint!=null)item=new Item(item.batchId(),checkpoint.info(),new Selection(checkpoint.sourceReference(),checkpoint.key().sourceGeneration(),checkpoint.info().name,checkpoint.info().mime,checkpoint.info().size,true),"paused",checkpoint.writtenOffset(),item.origin(),item.retryOf());else item=new Item(item.batchId(),item.info(),item.selection(),"paused",item.transferred(),item.origin(),item.retryOf());}
            }
            items.add(item);
        }
        snapshot=List.copyOf(items);engine.taskObserver(this::observe);
    }
    public void listener(Listener value){listener=Objects.requireNonNull(value);later(()->{for(Item item:List.copyOf(items))try{listener.changed(item.record());}catch(IOException failure){paused=true;}});}
    public List<Item> items(){return snapshot;}
    public boolean pending(){return snapshot.stream().anyMatch(item->!item.terminal());}
    public boolean paused(){return paused;}
    /** Queued selections waiting for routing must not block the idle route handshake. */
    public boolean routeBusy(){return snapshot.stream().anyMatch(item->{CompletableFuture<?> gate=routeGates.get(item.batchId());return !item.terminal()&&(gate==null||gate.isDone());});}
    private static String retryRoot(Item item){return item.retryOf().isEmpty()?item.info().id:item.retryOf();}
    private static Item latest(List<Item> values,String id){
        Item parent=values.stream().filter(i->i.info().id.equals(id)).findFirst().orElse(null);
        String root=parent==null?id:retryRoot(parent);Item last=null;
        for(Item item:values)if(retryRoot(item).equals(root))last=item;return last;
    }
    public RetryState retryState(String id){
        Item last=latest(snapshot,id);if(last==null)return RetryState.UNAVAILABLE;
        if(last.state().equals("failed"))return RetryState.AVAILABLE;
        if(!last.retryOf().isEmpty()){if(last.state().equals("delivered"))return RetryState.RETRIED;if(!last.terminal())return RetryState.RETRYING;return RetryState.INELIGIBLE;}
        return RetryState.UNAVAILABLE;
    }
    public boolean hasRetryableBatch(String id){
        List<Item> values=snapshot;Item member=latest(values,id);if(member==null)return false;
        return latestInBatch(values,member.batchId()).stream().anyMatch(i->i.state().equals("failed"));
    }
    private static List<Item> latestInBatch(List<Item> values,String batch){
        LinkedHashMap<String,Item> latest=new LinkedHashMap<>();for(Item item:values)if(item.batchId().equals(batch))latest.put(retryRoot(item),item);return List.copyOf(latest.values());
    }
    public CompletableFuture<String> retry(String id){return retry(id,()->true);}
    public CompletableFuture<String> retry(String id,BooleanSupplier valid){Objects.requireNonNull(valid);return command(()->retryOne(id,null,valid));}
    public CompletableFuture<String> retryWithSelection(String id,Selection replacement){return retryWithSelection(id,replacement,()->true);}
    public CompletableFuture<String> retryWithSelection(String id,Selection replacement,BooleanSupplier valid){Objects.requireNonNull(replacement);Objects.requireNonNull(valid);return command(()->retryOne(id,replacement,valid));}
    private void requireRetrySession(BooleanSupplier valid)throws IOException {if(closing||closed||!valid.getAsBoolean())throw new IOException("Retry session changed");}
    private String retryOne(String id,Selection replacement,BooleanSupplier valid)throws IOException {
        requireRetrySession(valid);
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid retry task");
        Item last=latest(items,id);
        if(last!=null&&!last.state().equals("failed")){
            if(!last.retryOf().isEmpty()&&(!last.terminal()||last.state().equals("delivered")))return last.info().id;
            throw new IOException("Only failed outgoing tasks can be retried");
        }
        if(last==null&&replacement==null)throw new RetrySourceUnavailableException(null);
        Selection selection=retrySelection(replacement==null?last.origin():replacement,valid);
        String batch=last==null?UUID.randomUUID().toString():last.batchId(),parent=last==null?id:retryRoot(last);
        Item added=newRetry(batch,parent,selection);requireRetrySession(valid);admit(List.of(added));return added.info().id;
    }
    private Selection retrySelection(Selection origin,BooleanSupplier valid)throws IOException {
        requireRetrySession(valid);
        // Fresh attempts reopen the original selection. Active checkpoints retain their exact fingerprint.
        Selection fresh=new Selection(origin.reference(),UUID.randomUUID().toString(),origin.name(),origin.mime(),origin.size(),false);
        try(AttachmentSource source=sources.open(fresh)){
            retryProbe=source;requireRetrySession(valid);
            // Check that the stream can open; a pipe's first byte may block until its producer is ready.
            try(InputStream probe=source.open(0)){}
            requireRetrySession(valid);return new Selection(origin.reference(),fresh.generation(),origin.name(),origin.mime(),source.size(),false);
        }catch(IOException|RuntimeException unavailable){requireRetrySession(valid);throw new RetrySourceUnavailableException(unavailable);}finally{retryProbe=null;}
    }
    private Item newRetry(String batch,String parent,Selection selected)throws IOException {
        return new Item(batch,AttachmentInfo.v2(UUID.randomUUID().toString(),selected.name(),selected.mime(),Math.max(0,selected.size()),null,System.currentTimeMillis()),selected,paused?"paused":"queued",0,selected,parent);
    }
    public CompletableFuture<RetryBatchResult> retryFailedBatch(String id){return retryFailedBatch(id,()->true);}
    public CompletableFuture<RetryBatchResult> retryFailedBatch(String id,BooleanSupplier valid){Objects.requireNonNull(valid);return command(()->{
        requireRetrySession(valid);
        Item member=latest(items,id);if(member==null)throw new RetrySourceUnavailableException(null);
        List<Item> failures=latestInBatch(items,member.batchId()).stream().filter(i->i.state().equals("failed")).collect(java.util.stream.Collectors.toList());
        List<Item> added=new ArrayList<>();List<String> unavailable=new ArrayList<>();
        for(Item failed:failures)try{added.add(newRetry(failed.batchId(),retryRoot(failed),retrySelection(failed.origin(),valid)));}catch(RetrySourceUnavailableException missing){unavailable.add(failed.info().id);}
        requireRetrySession(valid);if(!added.isEmpty())admit(added);return new RetryBatchResult(added.stream().map(i->i.info().id).collect(java.util.stream.Collectors.toList()),unavailable);
    });}
    private void admit(List<Item> added)throws IOException {
        if(items.stream().filter(i->!i.terminal()).count()+added.size()>MAX_PENDING)throw new IOException("Batch selection exceeds bounds");
        List<Item> before=List.copyOf(items);
        while(items.size()+added.size()>AttachmentBatchStore.MAX_ITEMS){int i=0;while(i<items.size()&&!items.get(i).terminal())i++;if(i==items.size())throw new IOException("Batch history bound exceeded");items.remove(i);}
        items.addAll(added);try{persist();}catch(IOException error){items.clear();items.addAll(before);snapshot=List.copyOf(items);throw error;}
        for(Item item:added)listener.changed(item.record());later(this::pump);
    }
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
    public CompletableFuture<String> enqueue(List<Selection> selection) {return enqueue(selection,CompletableFuture.completedFuture(null));}
    public CompletableFuture<String> enqueue(List<Selection> selection,CompletableFuture<?> routeReady) {
        Objects.requireNonNull(routeReady);
        List<Selection> selected=List.copyOf(selection);return command(()->{
            if(closing)throw new IOException("Batch session closed");
            long pending=items.stream().filter(item->!item.terminal()).count();
            if(selected.isEmpty()||selected.size()>MAX_PENDING||pending+selected.size()>MAX_PENDING)throw new IOException("Batch selection exceeds bounds");
            List<Item> before=List.copyOf(items);
            while(items.size()+selected.size()>AttachmentBatchStore.MAX_ITEMS){int i=0;while(i<items.size()&&!items.get(i).terminal())i++;if(i==items.size())throw new IOException("Batch history bound exceeded");items.remove(i);}
            String batch=UUID.randomUUID().toString();long time=System.currentTimeMillis();List<Item> added=new ArrayList<>();
            for(Selection s:selected)added.add(new Item(batch,AttachmentInfo.v2(UUID.randomUUID().toString(),s.name(),s.mime(),Math.max(0,s.size()),null,time),s,paused?"paused":"queued",0));
            routeGates.put(batch,routeReady);
            items.addAll(added);try{persist();}catch(IOException error){routeGates.remove(batch);items.clear();items.addAll(before);snapshot=List.copyOf(items);throw error;}
            for(Item item:added)listener.changed(item.record());
            routeReady.whenComplete((value,error)->later(()->{
                routeGates.remove(batch);
                if(error!=null){for(Item item:List.copyOf(items))if(item.batchId().equals(batch)&&!item.terminal()&&!resumableState(item.state()))try{state(new Item(item.batchId(),item.info(),item.selection(),routeSuspended?"paused":"failed",item.transferred(),item.origin(),item.retryOf()));}catch(IOException storageFailure){paused=true;}}
                pump();
            }));later(this::pump);return batch;
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
    private static boolean resumableState(String state){return Set.of("paused","sourceUnavailable","interrupted","unknown").contains(state);}
    private void pump() {
        if(closed||paused||routeSuspended||active!=null)return;
        Item item=items.stream().filter(e->!e.terminal()&&(!resumableState(e.state())||resumeRequests.contains(e.info().id))).findFirst().orElse(null);if(item==null||routeGates.containsKey(item.batchId()))return;
        active=item.info().id;
        try {
            TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).load(engine.taskIdentity(item.info().id,item.selection().generation())).orElse(null);
            if(checkpoint!=null){
                if(checkpoint.state()==TransferCheckpoint.State.COMPLETE){observe(new AttachmentRecord(checkpoint.info(),true,"delivered",checkpoint.info().size));return;}
                if(checkpoint.state()==TransferCheckpoint.State.CANCELED){fail(item,null,"canceled");return;}
                AttachmentSource source=null;try{source=sources.open(new Selection(checkpoint.sourceReference(),checkpoint.key().sourceGeneration(),checkpoint.info().name,checkpoint.info().mime,checkpoint.info().size,true));}catch(IOException missing){if(checkpoint.info().hash==null)throw missing;}
                if(paused||routeSuspended||closed){if(source!=null)source.close();active=null;return;}
                (automaticResumes.remove(item.info().id)?engine.resumeAutomatic(checkpoint.key(),source):engine.resume(checkpoint.key(),source)).whenComplete((id,error)->{if(error!=null)later(()->fail(item,error,"failed"));});return;
            }
            state(new Item(item.batchId(),item.info(),item.selection(),"preparing",0,item.origin(),item.retryOf()));
            AttachmentSource opened=sources.open(item.selection());preparing=opened;
            if(cancelRequests.contains(active))throw new IOException("Preparation canceled");
            if(paused||routeSuspended||closed){opened.close();preparing=null;active=null;state(new Item(item.batchId(),item.info(),item.selection(),routeSuspended?"queued":"paused",0,item.origin(),item.retryOf()));return;}
            AttachmentSource source=engine.prepareSource(opened);preparing=source;
            if(item.selection().size()>=0&&source.size()!=item.selection().size())throw new IOException("Attachment source size changed");
            Selection descriptor=describe(source,item.info().name,item.info().mime);
            AttachmentInfo info=AttachmentInfo.v2(item.info().id,item.info().name,item.info().mime,source.size(),null,item.info().time);
            Item ready=new Item(item.batchId(),info,descriptor,"queued",0,item.origin(),item.retryOf());state(ready);
            if(cancelRequests.contains(active))throw new IOException("Preparation canceled");
            if(paused||routeSuspended||closed){source.close();preparing=null;active=null;return;}
            preparing=null;
            engine.offer(source,info.name,info.mime,info.id,info.time).whenComplete((id,error)->{if(error!=null)later(()->fail(ready,error,"failed"));});
        }catch(Exception error){if(preparing!=null){try{preparing.close();preparing.discard();}catch(IOException ignored){}preparing=null;}fail(item,error,cancelRequests.contains(item.info().id)?"canceled":paused||closed?"paused":routeSuspended?"queued":"failed");}
    }
    private void fail(Item item,Throwable error,String status){
        active=null;int i=index(item.info().id);if(i>=0)item=items.get(i);try{state(new Item(item.batchId(),item.info(),item.selection(),status,item.transferred(),item.origin(),item.retryOf()));}catch(IOException storageFailure){paused=true;}
        cancelRequests.remove(item.info().id);resumeRequests.remove(item.info().id);if(!paused&&!closed)pump();
    }
    private void observe(AttachmentRecord record){if(!record.outgoing)return;later(()->{
        int i=index(record.info.id);if(i<0)return;Item previous=items.get(i);if(previous.terminal())return;
        Item next=new Item(previous.batchId(),record.info,previous.selection(),record.state,record.transferred,previous.origin(),previous.retryOf());
        try{
            // Core already persisted its own event before this observer. Only journal queue state here.
            items.set(i,next);if(!previous.state().equals(next.state())||next.terminal())persist();else snapshot=List.copyOf(items);
            if(resumableState(record.state))resumeRequests.remove(record.info.id);
            if(next.terminal()){listener.finished(record);cancelRequests.remove(record.info.id);resumeRequests.remove(record.info.id);if(record.info.id.equals(active))active=null;pump();}
        }catch(IOException failure){paused=true;}
    });}
    public CompletableFuture<Void> pauseAll(){return pauseAll(true);}
    /** Routing freezes admission separately from the user's persistent pause intent. */
    public CompletableFuture<Set<String>> freezeForRouteChange(){
        routeSuspended=true;AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}
        return command(()->{Set<String> ids=new LinkedHashSet<>();if(!paused)for(Item item:items)if(!item.terminal()&&!resumableState(item.state()))ids.add(item.info().id);return Set.copyOf(ids);});
    }
    public CompletableFuture<Void> resumeAfterRouteChange(Set<String> ids,BooleanSupplier allowed){
        return command(()->{
            routeSuspended=false;if(!allowed.getAsBoolean()||paused)return null;
            for(Item item:items)if(ids.contains(item.info().id)&&!item.terminal()&&!item.state().equals("sourceUnavailable")){resumeRequests.add(item.info().id);automaticResumes.add(item.info().id);if(item.info().id.equals(active))active=null;}
            persist();later(this::pump);return null;
        });
    }
    public CompletableFuture<Void> pauseOnDisconnect(){return pauseAll(false);}
    private CompletableFuture<Void> pauseAll(boolean notifyPeer){paused=true;AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}
        return command(()->{resumeRequests.clear();for(Item item:List.copyOf(items))if(!item.terminal()&&!item.info().id.equals(active)&&!item.state().equals("paused"))state(new Item(item.batchId(),item.info(),item.selection(),"paused",item.transferred(),item.origin(),item.retryOf()));persist();return active;}).thenCompose(id->id==null?CompletableFuture.completedFuture(null):notifyPeer?engine.pause(id,true):engine.pauseOnDisconnect(id,true)).thenCompose(ignored->command(()->null));
    }
    public CompletableFuture<Void> resumeAll(){return command(()->{boolean wasPaused=paused;paused=false;routeSuspended=false;automaticResumes.clear();for(Item item:items)if(!item.terminal()&&resumableState(item.state()))resumeRequests.add(item.info().id);if(wasPaused||active!=null&&resumeRequests.contains(active))active=null;persist();later(this::pump);return null;});}
    public CompletableFuture<Void> cancel(String id){if(snapshot.stream().anyMatch(item->item.info().id.equals(id)&&!item.terminal()))cancelRequests.add(id);if(id.equals(active)){AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}}return command(()->{
        int i=index(id);if(i<0)return null;Item item=items.get(i);if(item.terminal())return null;
        if(id.equals(active)){return engine.cancel(id,true);}
        state(new Item(item.batchId(),item.info(),item.selection(),"canceled",item.transferred(),item.origin(),item.retryOf()));return cancelStored(item);
    }).thenCompose(value->value==null?CompletableFuture.completedFuture(null):value);}
    public CompletableFuture<Void> cancelAll(){paused=true;for(Item item:snapshot)if(!item.terminal())cancelRequests.add(item.info().id);AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}return command(()->{
        CompletableFuture<Void> done=CompletableFuture.completedFuture(null);for(Item item:List.copyOf(items))if(!item.terminal()){
            boolean running=item.info().id.equals(active);if(!running)state(new Item(item.batchId(),item.info(),item.selection(),"canceled",item.transferred(),item.origin(),item.retryOf()));
            done=done.thenCompose(ignored->running?engine.cancel(item.info().id,true):cancelStored(item));
        }return done;
    }).thenCompose(done->done);}
    private CompletableFuture<Void> cancelStored(Item item){resumeRequests.remove(item.info().id);return engine.cancel(item.info().id,true).thenRun(()->{try{discardSnapshot(item);listener.finished(new AttachmentRecord(item.info(),true,"canceled",item.transferred()));}catch(IOException error){throw new CompletionException(error);}finally{cancelRequests.remove(item.info().id);}});}
    private void discardSnapshot(Item item){if(item.selection().reference().startsWith("snapshot:\n"))try{OwnedSnapshotSource.discardSaved(root.resolve(".sources-v2"),item.selection().reference());}catch(IOException error){throw new CompletionException(error);}}
    public synchronized CompletableFuture<Void> shutdown(){
        if(shutdownFuture!=null)return shutdownFuture;
        closing=true;AttachmentSource probe=retryProbe;if(probe!=null)try{probe.close();}catch(IOException ignored){}
        paused=true;AttachmentSource source=preparing;if(source!=null)try{source.close();}catch(IOException ignored){}
        CompletableFuture<Void> done=command(()->{persist();return null;}).thenCompose(ignored->engine.shutdown()).thenCompose(ignored->command(()->{closed=true;actor.shutdown();return null;}));
        shutdownFuture=done.whenComplete((value,error)->{if(error!=null){closed=true;actor.shutdown();}});return shutdownFuture;
    }
    public void close()throws IOException {try{shutdown().get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Batch shutdown failed",e);}}
    public static List<String> pendingReferences(Path root)throws IOException {return AttachmentBatchStore.references(root);}
}

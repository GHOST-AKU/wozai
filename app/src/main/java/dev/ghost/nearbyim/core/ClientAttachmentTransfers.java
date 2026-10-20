package dev.ghost.nearbyim.core;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
/** Platform bridge. V2 is selected only by an authenticated session's capability. */
public final class ClientAttachmentTransfers implements AutoCloseable {
    private final TransferDiagnostics metrics=new TransferDiagnostics();
    private final AttachmentTransfer legacy;
    private final AttachmentTransferV2 streaming;
    private final Path root;
    private final AttachmentTransferV2.SourceResolver sources;
    private final AttachmentBatchQueue batch;
    static final class Intent {
        final java.util.concurrent.atomic.AtomicBoolean alive=new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.Set<String> blocked=ConcurrentHashMap.newKeySet();
        private static String key(String id,boolean sent){return (sent?"send:":"receive:")+id;}
        boolean allowed(TransferTaskKey key){return allowed(key.transferId(),key.direction()==TransferTaskKey.Direction.SEND);}
        boolean allowed(String id,boolean sent){return alive.get()&&!blocked.contains(key(id,sent));}
        void block(String id,boolean sent){blocked.add(key(id,sent));}
        void unblock(String id,boolean sent){blocked.remove(key(id,sent));}
        void resumeSending(){blocked.removeIf(key->key.startsWith("send:"));}
    }
    private volatile Intent intent=new Intent();
    private volatile FileRouteResume routeResume;
    private CompletableFuture<FileRouteResume> suspending;
    public ClientAttachmentTransfers(AttachmentTransfer legacy){this(null,legacy);}
    public ClientAttachmentTransfers(Path root,AttachmentTransfer legacy){this.legacy=legacy;streaming=null;this.root=root;sources=null;batch=null;}
    public ClientAttachmentTransfers(Path root,AttachmentTransferV2 streaming,AttachmentTransferV2.SourceResolver sources)throws IOException {
        this(root,streaming,sources,selected->{AttachmentInfo info=AttachmentInfo.v2(java.util.UUID.randomUUID().toString(),selected.name(),selected.mime(),Math.max(0,selected.size()),null,0);
            return sources.open(new TransferCheckpoint(streaming.taskIdentity(info.id,selected.generation()),info,selected.reference(),0,0,0,TransferCheckpoint.State.PAUSED,0));});
    }
    public ClientAttachmentTransfers(Path root,AttachmentTransferV2 streaming,AttachmentTransferV2.SourceResolver sources,AttachmentBatchQueue.SourceResolver selected)throws IOException {
        this.streaming=streaming;this.sources=sources;legacy=null;streaming.sourceResolver(sources);streaming.resumePermission(key->intent.allowed(key));try{this.root=root.toRealPath();batch=new AttachmentBatchQueue(this.root,streaming,selected);}catch(IOException error){streaming.shutdown();throw error;}
    }
    public void batchListener(AttachmentBatchQueue.Listener listener){if(batch!=null)batch.listener(listener);}
    public CompletableFuture<String> enqueue(java.util.List<AttachmentBatchQueue.Selection> selected){return batch==null?failed(new IOException("Batch requires V2")):batch.enqueue(selected);}
    public CompletableFuture<String> enqueue(java.util.List<AttachmentBatchQueue.Selection> selected,CompletableFuture<?> routeReady){return batch==null?failed(new IOException("Batch requires V2")):batch.enqueue(selected,routeReady);}
    public boolean routeBusy(){return streaming!=null&&(streaming.hasActive()||batch.routeBusy());}
    public AttachmentBatchQueue.RetryState retryState(String id){return batch==null?AttachmentBatchQueue.RetryState.UNAVAILABLE:batch.retryState(id);}
    public boolean hasRetryableBatch(String id){return batch!=null&&batch.hasRetryableBatch(id);}
    public CompletableFuture<String> retry(String id){return batch==null?failed(new IOException("Retry requires V2")):batch.retry(id);}
    public CompletableFuture<AttachmentBatchQueue.RetryBatchResult> retryFailedBatch(String id){return batch==null?failed(new IOException("Retry requires V2")):batch.retryFailedBatch(id);}
    public CompletableFuture<String> retryWithSelection(String id,AttachmentBatchQueue.Selection replacement){return batch==null?failed(new IOException("Retry requires V2")):batch.retryWithSelection(id,replacement);}
    public CompletableFuture<String> retry(String id,java.util.function.BooleanSupplier valid){return batch==null?failed(new IOException("Retry requires V2")):batch.retry(id,valid);}
    public CompletableFuture<AttachmentBatchQueue.RetryBatchResult> retryFailedBatch(String id,java.util.function.BooleanSupplier valid){return batch==null?failed(new IOException("Retry requires V2")):batch.retryFailedBatch(id,valid);}
    public CompletableFuture<String> retryWithSelection(String id,AttachmentBatchQueue.Selection replacement,java.util.function.BooleanSupplier valid){return batch==null?failed(new IOException("Retry requires V2")):batch.retryWithSelection(id,replacement,valid);}
    public java.util.List<AttachmentBatchQueue.Item> batchItems(){return batch==null?java.util.List.of():batch.items();}
    public CompletableFuture<Void> resumeBatch(){intent.resumeSending();return batch==null?failed(new IOException("Batch requires V2")):batch.resumeAll();}
    public FileTransferActivity activity(){return streaming==null?FileTransferActivity.IDLE:streaming.activity();}
    public String diagnostics(){return metrics.snapshot()+(streaming==null?"attachment_v2=false\n":streaming.diagnostics());}
    public boolean v2(){return streaming!=null;}
    boolean matches(String local,String remote,String generation){return streaming!=null&&streaming.matches(local,remote,generation);}
    public void receiveRecoveryOffer(TransferPacket packet)throws IOException{if(streaming==null)throw new IOException("Recovery requires V2");streaming.receiveRecoveryOffer(packet,true);}
    public AttachmentSource prepareSource(AttachmentSource source)throws IOException {return streaming==null?source:streaming.prepareSource(source);}
    boolean automaticActive(){return streaming!=null&&intent.alive.get()&&(streaming.automaticActive()||!batch.paused()&&batchItems().stream().anyMatch(item->!item.terminal()&&!java.util.Set.of("paused","sourceUnavailable","interrupted","unknown").contains(item.state())&&intent.allowed(item.info().id,true)));}
    public boolean hasActive(){return streaming!=null&&(streaming.hasActive()||batch.pending());}
    private void blockSending(){for(AttachmentBatchQueue.Item item:batchItems())if(!item.terminal())intent.block(item.info().id,true);}
    public CompletableFuture<Void> pauseBatch(){blockSending();return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseAll();}
    public CompletableFuture<Void> cancelBatch(){return streaming==null?legacy.cancelAll():batch.cancelAll();}
    public CompletableFuture<Void> pauseAll(){blockSending();if(routeResume!=null)routeResume.cancel();return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseAll().thenCompose(value->streaming.pauseAll());}
    /** Preserve the batch and checkpoints without attempting to write PAUSE to a lost lane. */
    public CompletableFuture<Void> pauseOnDisconnect(){return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseOnDisconnect().thenCompose(value->streaming.pauseOnDisconnect());}
    CompletableFuture<Void> quiesceOutgoing(){return batch.freezeForRouteChange().thenCompose(ids->streaming.quiesceOutgoing());}
    public synchronized CompletableFuture<FileRouteResume> suspendForRouteChange(){
        if(streaming==null)return failed(new IOException("Recovery requires V2"));
        if(routeResume!=null&&routeResume.active()&&!routeResume.claimed.get())return CompletableFuture.completedFuture(routeResume);
        if(suspending!=null&&!suspending.isDone())return suspending;
        Intent leaseIntent=intent;
        suspending=batch.freezeForRouteChange().thenCompose(queued->streaming.suspendForRouteChange().thenApply(keys->{
            FileRouteResume lease=new FileRouteResume(root,keys,queued,leaseIntent);routeResume=lease;return lease;
        }));return suspending;
    }
    public CompletableFuture<Void> resumeAfterRouteChange(FileRouteResume lease){
        if(streaming==null||lease==null||!root.equals(lease.directory)||lease.tasks.stream().anyMatch(key->!streaming.owns(key)))return failed(new IOException("Recovery identity changed"));
        if(!lease.active()||!lease.claimed.compareAndSet(false,true))return CompletableFuture.completedFuture(null);
        intent=lease.intent;routeResume=null;
        java.util.Set<String> sending=new java.util.LinkedHashSet<>();for(String id:lease.queued)if(lease.queuedPermitted.test(id))sending.add(id);
        java.util.List<TransferTaskKey> direct=new java.util.ArrayList<>();
        for(TransferTaskKey key:lease.tasks)if(lease.permitted.test(key)){
            if(key.direction()==TransferTaskKey.Direction.SEND&&batchItems().stream().anyMatch(item->item.info().id.equals(key.transferId())))sending.add(key.transferId());else direct.add(key);
        }
        // Provider reopens and checkpoint scans run on a file worker, never a UI/route actor.
        CompletableFuture<Void> restored=CompletableFuture.runAsync(()->{
            for(TransferTaskKey key:direct)try{
                if(!lease.active()||!lease.permitted.test(key))continue;
                TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).load(key).orElse(null);
                if(checkpoint==null||checkpoint.state()==TransferCheckpoint.State.CANCELED)continue;
                AttachmentSource source=key.direction()==TransferTaskKey.Direction.SEND?sources.open(checkpoint):null;
                streaming.resumeAutomatic(key,source).join();
            }catch(IOException error){throw new CompletionException(error);}
        });
        return restored.thenCompose(value->batch.resumeAfterRouteChange(sending,lease::active));
    }
    public CompletableFuture<String> offer(AttachmentSource source,String name,String mime) {
        if(streaming!=null)return streaming.offer(source,name,mime);
        if(source.size()>AttachmentInfo.MAX_SIZE){dispose(source);return failed(new IOException("Attachment exceeds legacy file size limit"));}
        CompletableFuture<String> result=legacy.offer(()->{try{return new FilterInputStream(source.open(0)) {public void close()throws IOException{try{super.close();}finally{source.close();source.discard();}}};}catch(IOException error){dispose(source);throw error;}},name,mime);
        result.whenComplete((id,error)->{if(error!=null)dispose(source);});return result;
    }
    public void receive(Frame frame){if(legacy==null)throw new IllegalStateException("Legacy attachment in V2 session");legacy.receive(frame,true);}
    public void receive(TransferPacket packet)throws IOException{if(streaming==null)throw new IOException("V2 attachment requires an encrypted V2 session");streaming.receive(packet,true);}
    public void receive(TransferPacket packet,java.util.function.BooleanSupplier current)throws IOException{if(streaming==null)throw new IOException("V2 attachment requires an encrypted V2 session");streaming.receive(packet,true,current);}
    public CompletableFuture<Void> pause(String id,boolean outgoing){intent.block(id,outgoing);if(outgoing)blockSending();return streaming==null?failed(new IOException("Pause requires V2")):outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id))?batch.pauseAll():streaming.pause(id,outgoing);}
    /** Called on the platform's file/model executor, never the Android/UI thread. */
    public CompletableFuture<Void> resume(String id,boolean outgoing)throws IOException {
        if(streaming==null)return failed(new IOException("Resume requires V2"));
        intent.unblock(id,outgoing);if(outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id))){intent.resumeSending();return batch.resumeAll();}
        TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).list().stream().filter(value->streaming.owns(value.key())&&value.info().id.equals(id)&&(value.key().direction()==TransferTaskKey.Direction.SEND)==outgoing).findFirst().orElseThrow(()->new IOException("Missing attachment task"));
        AttachmentSource source=null;if(outgoing){long began=metrics.begin(TransferDiagnostics.Stage.SOURCE_OPEN);try{source=sources.open(checkpoint);}catch(IOException error){if(checkpoint.info().hash==null)throw error;}finally{metrics.end(TransferDiagnostics.Stage.SOURCE_OPEN,began,0);}}
        return streaming.resume(checkpoint.key(),source).thenApply(value->null);
    }
    public CompletableFuture<Void> cancel(String id,boolean outgoing){intent.block(id,outgoing);CompletableFuture<Void> result=streaming==null?legacy.cancel(id,outgoing):outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id))?batch.cancel(id):streaming.cancel(id,outgoing);return result.whenComplete((value,error)->{if(error==null)intent.unblock(id,outgoing);});}
    public CompletableFuture<Void> cancelAll(){if(routeResume!=null)routeResume.cancel();return streaming==null?legacy.cancelAll():batch.cancelAll().thenCompose(value->streaming.cancelAll());}
    public CompletableFuture<java.util.List<AttachmentRecord>> revokePending(){
        if(streaming!=null)return cancelAll().thenApply(value->java.util.Collections.emptyList());
        return legacy.cancelAll().thenApply(value->{try{return root==null?java.util.Collections.emptyList():AttachmentTransferV2.cancelPending(root);}catch(IOException error){throw new CompletionException(error);}});
    }
    public CompletableFuture<Void> accept(String id){return legacy==null?failed(new IOException("V2 auto-receives approved files")):legacy.accept(id);}
    public CompletableFuture<Void> reject(String id){return legacy==null?streaming.cancel(id,false):legacy.reject(id);}
    public CompletableFuture<Void> shutdown(){intent.alive.set(false);if(routeResume!=null)routeResume.cancel();return shutdownForRouteChange();}
    public CompletableFuture<Void> shutdownForRouteChange(){return streaming==null?legacy.shutdown():batch.shutdown();}
    public void close()throws IOException{try{shutdown().get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Attachment bridge shutdown failed",e);}}
    private static <T> CompletableFuture<T> failed(Exception error){CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(error);return result;}
    private static void dispose(AttachmentSource source){try{source.close();source.discard();}catch(IOException ignored){}}
}

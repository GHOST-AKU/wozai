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
    public ClientAttachmentTransfers(AttachmentTransfer legacy){this(null,legacy);}
    public ClientAttachmentTransfers(Path root,AttachmentTransfer legacy){this.legacy=legacy;streaming=null;this.root=root;sources=null;batch=null;}
    public ClientAttachmentTransfers(Path root,AttachmentTransferV2 streaming,AttachmentTransferV2.SourceResolver sources)throws IOException {
        this(root,streaming,sources,selected->{AttachmentInfo info=AttachmentInfo.v2(java.util.UUID.randomUUID().toString(),selected.name(),selected.mime(),Math.max(0,selected.size()),null,0);
            return sources.open(new TransferCheckpoint(streaming.taskIdentity(info.id,selected.generation()),info,selected.reference(),0,0,0,TransferCheckpoint.State.PAUSED,0));});
    }
    public ClientAttachmentTransfers(Path root,AttachmentTransferV2 streaming,AttachmentTransferV2.SourceResolver sources,AttachmentBatchQueue.SourceResolver selected)throws IOException {
        this.streaming=streaming;this.sources=sources;legacy=null;streaming.sourceResolver(sources);try{this.root=root.toRealPath();batch=new AttachmentBatchQueue(this.root,streaming,selected);}catch(IOException error){streaming.shutdown();throw error;}
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
    public CompletableFuture<Void> resumeBatch(){return batch==null?failed(new IOException("Batch requires V2")):batch.resumeAll();}
    public String diagnostics(){return metrics.snapshot()+(streaming==null?"attachment_v2=false\n":streaming.diagnostics());}
    public boolean v2(){return streaming!=null;}
    public AttachmentSource prepareSource(AttachmentSource source)throws IOException {return streaming==null?source:streaming.prepareSource(source);}
    public boolean hasActive(){return streaming!=null&&(streaming.hasActive()||batch.pending());}
    public CompletableFuture<Void> pauseBatch(){return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseAll();}
    public CompletableFuture<Void> cancelBatch(){return streaming==null?legacy.cancelAll():batch.cancelAll();}
    public CompletableFuture<Void> pauseAll(){return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseAll().thenCompose(value->streaming.pauseAll());}
    /** Preserve the batch and checkpoints without attempting to write PAUSE to a lost lane. */
    public CompletableFuture<Void> pauseOnDisconnect(){return streaming==null?failed(new IOException("Pause requires V2")):batch.pauseOnDisconnect().thenCompose(value->streaming.pauseOnDisconnect());}
    public CompletableFuture<String> offer(AttachmentSource source,String name,String mime) {
        if(streaming!=null)return streaming.offer(source,name,mime);
        if(source.size()>AttachmentInfo.MAX_SIZE){dispose(source);return failed(new IOException("Attachment exceeds legacy file size limit"));}
        CompletableFuture<String> result=legacy.offer(()->{try{return new FilterInputStream(source.open(0)) {public void close()throws IOException{try{super.close();}finally{source.close();source.discard();}}};}catch(IOException error){dispose(source);throw error;}},name,mime);
        result.whenComplete((id,error)->{if(error!=null)dispose(source);});return result;
    }
    public void receive(Frame frame){if(legacy==null)throw new IllegalStateException("Legacy attachment in V2 session");legacy.receive(frame,true);}
    public void receive(TransferPacket packet)throws IOException{if(streaming==null)throw new IOException("V2 attachment requires an encrypted V2 session");streaming.receive(packet,true);}
    public CompletableFuture<Void> pause(String id,boolean outgoing){return streaming==null?failed(new IOException("Pause requires V2")):outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id))?batch.pauseAll():streaming.pause(id,outgoing);}
    /** Called on the platform's file/model executor, never the Android/UI thread. */
    public CompletableFuture<Void> resume(String id,boolean outgoing)throws IOException {
        if(streaming==null)return failed(new IOException("Resume requires V2"));
        if(outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id)))return batch.resumeAll();
        TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).list().stream().filter(value->streaming.owns(value.key())&&value.info().id.equals(id)&&(value.key().direction()==TransferTaskKey.Direction.SEND)==outgoing).findFirst().orElseThrow(()->new IOException("Missing attachment task"));
        AttachmentSource source=null;if(outgoing){long began=metrics.begin(TransferDiagnostics.Stage.SOURCE_OPEN);try{source=sources.open(checkpoint);}catch(IOException error){if(checkpoint.info().hash==null)throw error;}finally{metrics.end(TransferDiagnostics.Stage.SOURCE_OPEN,began,0);}}
        return streaming.resume(checkpoint.key(),source).thenApply(value->null);
    }
    public CompletableFuture<Void> cancel(String id,boolean outgoing){return streaming==null?legacy.cancel(id,outgoing):outgoing&&batchItems().stream().anyMatch(item->item.info().id.equals(id))?batch.cancel(id):streaming.cancel(id,outgoing);}
    public CompletableFuture<Void> cancelAll(){return streaming==null?legacy.cancelAll():batch.cancelAll().thenCompose(value->streaming.cancelAll());}
    public CompletableFuture<java.util.List<AttachmentRecord>> revokePending(){
        if(streaming!=null)return cancelAll().thenApply(value->java.util.Collections.emptyList());
        return legacy.cancelAll().thenApply(value->{try{return root==null?java.util.Collections.emptyList():AttachmentTransferV2.cancelPending(root);}catch(IOException error){throw new CompletionException(error);}});
    }
    public CompletableFuture<Void> accept(String id){return legacy==null?failed(new IOException("V2 auto-receives approved files")):legacy.accept(id);}
    public CompletableFuture<Void> reject(String id){return legacy==null?streaming.cancel(id,false):legacy.reject(id);}
    public CompletableFuture<Void> shutdown(){return streaming==null?legacy.shutdown():batch.shutdown();}
    public void close()throws IOException{try{shutdown().get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Attachment bridge shutdown failed",e);}}
    private static <T> CompletableFuture<T> failed(Exception error){CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(error);return result;}
    private static void dispose(AttachmentSource source){try{source.close();source.discard();}catch(IOException ignored){}}
}

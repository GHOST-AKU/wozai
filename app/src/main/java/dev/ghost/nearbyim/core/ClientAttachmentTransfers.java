package dev.ghost.nearbyim.core;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
/** Platform bridge. V2 is selected only by an authenticated session's capability. */
public final class ClientAttachmentTransfers {
    private final AttachmentTransfer legacy;
    private final AttachmentTransferV2 streaming;
    private final Path root;
    private final AttachmentTransferV2.SourceResolver sources;
    public ClientAttachmentTransfers(AttachmentTransfer legacy){this(null,legacy);}
    public ClientAttachmentTransfers(Path root,AttachmentTransfer legacy){this.legacy=legacy;streaming=null;this.root=root;sources=null;}
    public ClientAttachmentTransfers(Path root,AttachmentTransferV2 streaming,AttachmentTransferV2.SourceResolver sources){this.root=root;this.streaming=streaming;this.sources=sources;legacy=null;streaming.sourceResolver(sources);}
    public boolean v2(){return streaming!=null;}
    public AttachmentSource prepareSource(AttachmentSource source)throws IOException {return streaming==null?source:streaming.prepareSource(source);}
    public boolean hasActive(){return streaming!=null&&streaming.hasActive();}
    public CompletableFuture<Void> pauseAll(){return streaming==null?failed(new IOException("Pause requires V2")):streaming.pauseAll();}
    public CompletableFuture<String> offer(AttachmentSource source,String name,String mime) {
        if(streaming!=null)return streaming.offer(source,name,mime);
        if(source.size()>AttachmentInfo.MAX_SIZE){dispose(source);return failed(new IOException("Attachment exceeds legacy file size limit"));}
        CompletableFuture<String> result=legacy.offer(()->{try{return new FilterInputStream(source.open(0)) {public void close()throws IOException{try{super.close();}finally{source.close();source.discard();}}};}catch(IOException error){dispose(source);throw error;}},name,mime);
        result.whenComplete((id,error)->{if(error!=null)dispose(source);});return result;
    }
    public void receive(Frame frame){if(legacy==null)throw new IllegalStateException("Legacy attachment in V2 session");legacy.receive(frame,true);}
    public void receive(TransferPacket packet)throws IOException{if(streaming==null)throw new IOException("V2 attachment requires an encrypted V2 session");streaming.receive(packet,true);}
    public CompletableFuture<Void> pause(String id,boolean outgoing){return streaming==null?failed(new IOException("Pause requires V2")):streaming.pause(id,outgoing);}
    /** Called on the platform's file/model executor, never the Android/UI thread. */
    public CompletableFuture<Void> resume(String id,boolean outgoing)throws IOException {
        if(streaming==null)return failed(new IOException("Resume requires V2"));
        TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve(".tasks-v2")).list().stream().filter(value->streaming.owns(value.key())&&value.info().id.equals(id)&&(value.key().direction()==TransferTaskKey.Direction.SEND)==outgoing).findFirst().orElseThrow(()->new IOException("Missing attachment task"));
        AttachmentSource source=null;if(outgoing)try{source=sources.open(checkpoint);}catch(IOException error){if(checkpoint.info().hash==null)throw error;}
        return streaming.resume(checkpoint.key(),source).thenApply(value->null);
    }
    public CompletableFuture<Void> cancel(String id,boolean outgoing){return streaming==null?legacy.cancel(id,outgoing):streaming.cancel(id,outgoing);}
    public CompletableFuture<Void> cancelAll(){return streaming==null?legacy.cancelAll():streaming.cancelAll();}
    public CompletableFuture<java.util.List<AttachmentRecord>> revokePending(){
        if(streaming!=null)return streaming.cancelAll().thenApply(value->java.util.Collections.emptyList());
        return legacy.cancelAll().thenApply(value->{try{return root==null?java.util.Collections.emptyList():AttachmentTransferV2.cancelPending(root);}catch(IOException error){throw new CompletionException(error);}});
    }
    public CompletableFuture<Void> accept(String id){return legacy==null?failed(new IOException("V2 auto-receives approved files")):legacy.accept(id);}
    public CompletableFuture<Void> reject(String id){return legacy==null?streaming.cancel(id,false):legacy.reject(id);}
    public CompletableFuture<Void> shutdown(){return streaming==null?legacy.shutdown():streaming.shutdown();}
    private static <T> CompletableFuture<T> failed(Exception error){CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(error);return result;}
    private static void dispose(AttachmentSource source){try{source.close();source.discard();}catch(IOException ignored){}}
}

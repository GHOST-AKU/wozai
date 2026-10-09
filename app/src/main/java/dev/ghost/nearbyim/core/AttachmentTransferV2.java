package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Sequential file pipeline. This layer requires an authenticated, consented Wire. */
public final class AttachmentTransferV2 implements AutoCloseable {
    public interface Wire {boolean send(TransferPacket packet);void abort();}
    public interface Listener {void changed(AttachmentRecord record)throws IOException;}
    private interface Work {void run()throws Exception;}
    private static final long QUEUE_BUDGET=8L*1024*1024;
    private final Path root;
    private final String localRoot,remoteRoot,connection;
    private final Wire wire;
    private final Listener listener;
    private final long initialWindow,maxWindow,creditBatch,creditDelayNs;
    private final TransferBufferPool buffers;
    private final AtomicLong queued=new AtomicLong();
    private final AtomicBoolean creditPending=new AtomicBoolean();
    private final ThreadPoolExecutor actor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),r->daemon(r,"attachment-v2-state"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"attachment-v2-credit"));
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    private final ConcurrentHashMap<CompletableFuture<String>,AttachmentSource> preparing=new ConcurrentHashMap<>();
    private final Map<String,Ended> ended=new LinkedHashMap<>();
    private volatile Task outgoing,incoming;
    private volatile boolean closed;
    private static final class Ended {long remaining;int controls=64;Ended(long bytes){remaining=bytes;}}
    private static final class Task {
        AttachmentInfo info;
        final boolean outgoing;
        final String generation;
        final AtomicBoolean canceled=new AtomicBoolean();
        final Path partial;
        AttachmentSource source;
        volatile InputStream input;
        volatile FileChannel output;
        String state="offered";
        long sent,written,verified,lastCredit,lastCreditNs,lastNotice;
        MessageDigest whole,block;
        TransferByteWindow window;
        Task(AttachmentInfo info,boolean outgoing,String generation,Path partial){this.info=info;this.outgoing=outgoing;this.generation=generation;this.partial=partial;}
    }
    public AttachmentTransferV2(Path privateRoot,String localRoot,String remoteRoot,String connectionGeneration,boolean rfcomm,Wire wire,Listener listener)throws IOException {
        if(localRoot==null||remoteRoot==null||!localRoot.matches("[0-9a-f]{64}")||!remoteRoot.matches("[0-9a-f]{64}")||connectionGeneration==null
                ||!connectionGeneration.matches("([0-9a-f]{64}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})"))throw new IOException("Invalid attachment session identity");
        root=privateRoot;this.localRoot=localRoot;this.remoteRoot=remoteRoot;connection=connectionGeneration;this.wire=Objects.requireNonNull(wire);this.listener=Objects.requireNonNull(listener);
        if(Files.isSymbolicLink(root)||Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment directory");
        Files.createDirectories(root);permissions(root,"rwx------");
        initialWindow=rfcomm?65536:1048576;maxWindow=rfcomm?524288:8388608;creditBatch=rfcomm?32768:262144;creditDelayNs=rfcomm?50_000_000:10_000_000;
        buffers=new TransferBufferPool(TransferLimits.DATA_BYTES,TransferLimits.DATA_BYTES);
        timer.scheduleWithFixedDelay(()->{
            if(closed||!creditPending.compareAndSet(false,true))return;
            if(!post(()->{try{Task task=incoming;if(task!=null&&!task.canceled.get())credit(task,false);}finally{creditPending.set(false);}}))creditPending.set(false);
        },10,10,TimeUnit.MILLISECONDS);
    }
    private static Thread daemon(Runnable action,String name){Thread thread=new Thread(action,name);thread.setDaemon(true);return thread;}
    private boolean post(Work work) {
        if(closed)return false;
        try{actor.execute(()->{if(closed)return;try{work.run();}catch(Exception error){abort();}});return true;}
        catch(RejectedExecutionException error){abort();return false;}
    }
    /** Ownership of source passes to this task, including rejected/failed offers. */
    public CompletableFuture<String> offer(AttachmentSource source,String name,String mime) {
        CompletableFuture<String> result=new CompletableFuture<>();
        preparing.put(result,source);result.whenComplete((id,error)->preparing.remove(result));
        if(!post(()->{
            Task task=null;
            try {
                if(closed||result.isDone())throw new IOException("Attachment session closed");
                if(outgoing!=null)throw new IOException("Attachment busy");
                if(!source.seekable()||source.size()<0)throw new IOException("Attachment source requires a bounded snapshot");
                source.verifyUnchanged();AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),name,mime,source.size(),null,System.currentTimeMillis());
                task=new Task(info,true,source.generation(),null);task.source=source;outgoing=task;notice(task,true);
                send(TransferPacket.offer(info,task.generation,connection));result.complete(info.id);
            }catch(Exception error){closeResource(source);if(task!=null&&outgoing==task)finish(task,"failed",false);result.completeExceptionally(error);}
        })){closeResource(source);result.completeExceptionally(new IOException("Attachment session closed"));}
        return result;
    }
    public void receive(TransferPacket packet,boolean allowed)throws IOException {
        if(closed||!allowed||!connection.equals(packet.connectionGeneration))throw new IOException("Attachment outside approved session generation");
        long charge=256L+packet.data.length+(packet.info==null?0:2048);long total=queued.addAndGet(charge);
        if(total>QUEUE_BUDGET){queued.addAndGet(-charge);abort();throw new IOException("Attachment receive budget exceeded");}
        if(packet.kind==TransferPacket.Kind.CANCEL) {
            Task task=packet.fromSender?incoming:outgoing;
            if(matches(task,packet)){task.canceled.set(true);closeTaskIO(task);}
        }
        try {actor.execute(()->{
            try {
                if(closed)return;
                try{handle(packet);}catch(IOException error){Task task=packet.fromSender?incoming:outgoing;if(task==null||!task.canceled.get())throw error;}
            }catch(Exception error){abort();}
            finally{queued.addAndGet(-charge);}
        });}catch(RejectedExecutionException error){queued.addAndGet(-charge);abort();throw new IOException("Attachment receive queue full",error);}
    }
    private void handle(TransferPacket packet)throws Exception {
        if(packet.kind==TransferPacket.Kind.OFFER) {
            if(incoming!=null){send(control(packet,TransferPacket.Kind.CANCEL,false,0,0,null,"busy"));return;}
            if(ended.containsKey(key(packet.transferId,packet.sourceGeneration,true)))throw new IOException("Reused attachment task");
            Task task=new Task(packet.info,false,packet.sourceGeneration,root.resolve("in-"+packet.transferId+".part"));incoming=task;notice(task,true);
            task.output=FileChannel.open(task.partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);permissions(task.partial,"rw-------");
            task.whole=digest();task.block=digest();task.state="transferring";task.lastCreditNs=System.nanoTime();notice(task,true);
            send(control(packet,TransferPacket.Kind.ACCEPT,false,0,initialWindow,null,""));return;
        }
        Task task=packet.fromSender?incoming:outgoing;
        if(!matches(task,packet)) {
            Ended known=ended.get(key(packet.transferId,packet.sourceGeneration,packet.fromSender));
            if(known!=null) {
                if(packet.kind==TransferPacket.Kind.DATA&&packet.data.length<=known.remaining){known.remaining-=packet.data.length;return;}
                if(packet.kind!=TransferPacket.Kind.DATA&&known.controls-->0)return;
            }
            throw new IOException("Unknown attachment task");
        }
        if(packet.totalSize!=task.info.size)throw new IOException("Attachment size changed");
        if(packet.kind==TransferPacket.Kind.CANCEL){finish(task,packet.reason.equals("canceled")?"canceled":"failed",false);return;}
        if(task.canceled.get())return;
        switch(packet.kind) {
            case ACCEPT:
                require(task.outgoing&&task.state.equals("offered")&&packet.offset==0&&packet.windowBytes>0);
                task.window=new TransferByteWindow(packet.windowBytes,maxWindow);task.whole=digest();task.block=digest();task.input=task.source.open(0);task.state="transferring";notice(task,true);pump(task);break;
            case CREDIT:
                require(task.outgoing&&(task.state.equals("transferring")||task.state.equals("awaitingReceipt")));
                task.window.acknowledge(packet.offset,packet.windowBytes);task.written=packet.offset;notice(task,false);if(task.state.equals("transferring"))pump(task);break;
            case DATA:
                require(!task.outgoing&&task.state.equals("transferring")&&packet.offset==task.written&&packet.data.length<=TransferLimits.BLOCK_BYTES-(task.written-task.verified)
                        &&packet.data.length<=initialWindow-(task.written-task.lastCredit));
                ByteBuffer data=ByteBuffer.wrap(packet.data);while(data.hasRemaining())task.output.write(data);
                task.whole.update(packet.data);task.block.update(packet.data);task.written+=packet.data.length;notice(task,false);credit(task,false);break;
            case BLOCK_HASH:
                require(!task.outgoing&&packet.offset==task.written&&task.written>task.verified&&(task.written-task.verified==TransferLimits.BLOCK_BYTES||task.written==task.info.size));
                if(!MessageDigest.isEqual(task.block.digest(),packet.hash)){finish(task,"failed",true);return;}task.verified=task.written;break;
            case END:
                require(!task.outgoing&&task.written==task.info.size&&task.verified==task.info.size);
                if(!MessageDigest.isEqual(task.whole.digest(),packet.hash)){finish(task,"failed",true);return;}
                task.info=AttachmentInfo.v2(task.info.id,task.info.name,task.info.mime,task.info.size,AttachmentInfo.hex(packet.hash),task.info.time);
                task.state="verifying";notice(task,true);task.output.force(true);task.output.close();task.output=null;
                Path destination=AttachmentTransfer.file(root,task.info);if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Attachment destination exists");
                Files.move(task.partial,destination,StandardCopyOption.ATOMIC_MOVE);task.state="received";
                try{notice(task,true);}catch(IOException error){Files.deleteIfExists(destination);throw error;}
                remember(task);incoming=null;send(control(packet,TransferPacket.Kind.COMPLETE,false,task.info.size,0,packet.hash,""));break;
            case COMPLETE:
                require(task.outgoing&&task.state.equals("awaitingReceipt")&&task.info.hash.equals(AttachmentInfo.hex(packet.hash)));
                task.written=task.info.size;finish(task,"delivered",false);break;
            default:throw new IOException("Unsupported attachment state transition");
        }
    }
    private void pump(Task task)throws Exception {
        try {
            while(!task.canceled.get()&&task.sent<task.info.size) {
                int length=(int)Math.min(TransferLimits.DATA_BYTES,Math.min(task.info.size-task.sent,TransferLimits.BLOCK_BYTES-task.sent%TransferLimits.BLOCK_BYTES));
                if(task.window.availableBytes()<length)return;
                try(TransferBufferPool.Lease lease=buffers.acquire()) {
                    byte[] bytes=lease.bytes();int offset=0,emptyReads=0;
                    while(offset<length){int n=task.input.read(bytes,offset,length-offset);if(n<0)throw new EOFException("Attachment source truncated");if(n==0){if(++emptyReads>32)throw new IOException("Source repeatedly returned no data");continue;}emptyReads=0;offset+=n;}
                    if(task.canceled.get())return;
                    require(task.window.tryReserve(task.sent,length));task.whole.update(bytes,0,length);task.block.update(bytes,0,length);
                    send(TransferPacket.data(task.info.id,task.generation,connection,task.info.size,task.sent,length==bytes.length?bytes:Arrays.copyOf(bytes,length)));task.sent+=length;
                }
                if(task.sent%TransferLimits.BLOCK_BYTES==0||task.sent==task.info.size)send(control(task,TransferPacket.Kind.BLOCK_HASH,true,task.sent,0,task.block.digest(),""));
            }
            if(!task.canceled.get()&&task.sent==task.info.size) {
                task.source.verifyUnchanged();byte[] hash=task.whole.digest();task.info=AttachmentInfo.v2(task.info.id,task.info.name,task.info.mime,task.info.size,AttachmentInfo.hex(hash),task.info.time);
                task.state="awaitingReceipt";closeResource(task.input);task.input=null;notice(task,true);send(control(task,TransferPacket.Kind.END,true,task.sent,0,hash,""));
            }
        }catch(IOException error){if(!task.canceled.get())finish(task,"failed",true);}
    }
    private void credit(Task task,boolean force)throws IOException {
        if(task.outgoing||task.written==task.lastCredit)return;
        long now=System.nanoTime(),uncredited=task.written-task.lastCredit;
        if(force||uncredited>=creditBatch||now-task.lastCreditNs>=creditDelayNs||uncredited>=initialWindow-TransferLimits.DATA_BYTES) {
            send(control(task,TransferPacket.Kind.CREDIT,false,task.written,initialWindow,null,""));task.lastCredit=task.written;task.lastCreditNs=now;
        }
    }
    public void cancel(String id,boolean sent) {
        Task task=sent?outgoing:incoming;if(task==null||!task.info.id.equals(id))return;task.canceled.set(true);closeTaskIO(task);
        post(()->{if((sent?outgoing:incoming)==task)finish(task,"canceled",true);});
    }
    private void finish(Task task,String state,boolean notifyPeer)throws IOException {
        task.canceled.set(true);closeTaskIO(task);if(task.partial!=null)Files.deleteIfExists(task.partial);
        task.state=state;notice(task,true);remember(task);if(task==outgoing)outgoing=null;if(task==incoming)incoming=null;
        if(notifyPeer)send(control(task,TransferPacket.Kind.CANCEL,task.outgoing,0,0,null,state.equals("canceled")?"canceled":"failed"));
    }
    private void remember(Task task){ended.put(key(task.info.id,task.generation,!task.outgoing),new Ended(initialWindow));while(ended.size()>32)ended.remove(ended.keySet().iterator().next());}
    private static String key(String id,String generation,boolean fromSender){return id+":"+generation+":"+fromSender;}
    private static boolean matches(Task task,TransferPacket packet){return task!=null&&task.info.id.equals(packet.transferId)&&task.generation.equals(packet.sourceGeneration);}
    private TransferPacket control(Task task,TransferPacket.Kind kind,boolean fromSender,long offset,long window,byte[] hash,String reason)throws IOException {return TransferPacket.control(kind,task.info.id,task.generation,connection,fromSender,task.info.size,offset,window,hash,reason);}
    private TransferPacket control(TransferPacket packet,TransferPacket.Kind kind,boolean fromSender,long offset,long window,byte[] hash,String reason)throws IOException {return TransferPacket.control(kind,packet.transferId,packet.sourceGeneration,connection,fromSender,packet.totalSize,offset,window,hash,reason);}
    private void send(TransferPacket packet)throws IOException {if(closed||!wire.send(packet))throw new IOException("Attachment wire closed");}
    private void notice(Task task,boolean force)throws IOException {long now=System.nanoTime();if(force||now-task.lastNotice>=250_000_000){listener.changed(new AttachmentRecord(task.info,task.outgoing,task.state,task.written));task.lastNotice=now;}}
    private static MessageDigest digest()throws NoSuchAlgorithmException{return MessageDigest.getInstance("SHA-256");}
    private static void require(boolean valid)throws IOException{if(!valid)throw new IOException("Invalid attachment sequence");}
    private void abort(){wire.abort();close();}
    private static void closeResource(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
    private static void closeTaskIO(Task task){closeResource(task.input);closeResource(task.output);closeResource(task.source);}
    private static void permissions(Path path,String permissions)throws IOException{if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(permissions));}
    public long queuedBytes(){return Math.max(0,queued.get());}
    public long retainedBufferBytes(){return buffers.retainedBytes();}
    public CompletableFuture<Void> shutdown(){close();return terminated;}
    public synchronized void close() {
        if(closed)return;closed=true;timer.shutdownNow();for(Task task:new Task[]{outgoing,incoming})if(task!=null){task.canceled.set(true);closeTaskIO(task);}
        preparing.forEach((future,source)->{closeResource(source);future.completeExceptionally(new IOException("Attachment session closed"));});preparing.clear();
        actor.getQueue().clear();
        try{actor.execute(()->{try{for(Task task:new Task[]{outgoing,incoming})if(task!=null){if(task.partial!=null)Files.deleteIfExists(task.partial);task.state="interrupted";notice(task,true);}}catch(IOException ignored){}finally{outgoing=null;incoming=null;queued.set(0);buffers.close();terminated.complete(null);}});}
        catch(RejectedExecutionException ignored){queued.set(0);buffers.close();terminated.complete(null);}
        actor.shutdown();
    }
}

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
    private final TransferCheckpointStore checkpoints;
    private final TransferStorageBudget storage;
    private final FileChannel writerLock;
    private static final long CHECKPOINT_BYTES=32L*1024*1024,RETENTION_MS=7L*24*60*60*1000;
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
        TransferTaskKey key;
        String reference="";
        TransferStorageBudget.Reservation reservation;
        AttachmentSource source;
        volatile InputStream input;
        volatile FileChannel output;
        String state="offered";
        long sent,written,verified,durable,lastCheckpointNs,lastCredit,lastCreditVerified,lastCreditDurable,lastCreditNs,lastNotice;
        MessageDigest whole,block;
        TransferByteWindow window;
        Task(AttachmentInfo info,boolean outgoing,String generation,Path partial){this.info=info;this.outgoing=outgoing;this.generation=generation;this.partial=partial;}
    }
    public AttachmentTransferV2(Path privateRoot,String localRoot,String remoteRoot,String connectionGeneration,boolean rfcomm,Wire wire,Listener listener)throws IOException {
        this(privateRoot,localRoot,remoteRoot,connectionGeneration,rfcomm,wire,listener,new TransferStorageBudget(privateRoot,TransferStorageBudget.DEFAULT_QUOTA));
    }
    public AttachmentTransferV2(Path privateRoot,String localRoot,String remoteRoot,String connectionGeneration,boolean rfcomm,Wire wire,Listener listener,TransferStorageBudget storage)throws IOException {
        if(localRoot==null||remoteRoot==null||!localRoot.matches("[0-9a-f]{64}")||!remoteRoot.matches("[0-9a-f]{64}")||connectionGeneration==null
                ||!connectionGeneration.matches("([0-9a-f]{64}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})"))throw new IOException("Invalid attachment session identity");
        root=privateRoot;this.localRoot=localRoot;this.remoteRoot=remoteRoot;connection=connectionGeneration;this.wire=Objects.requireNonNull(wire);this.listener=Objects.requireNonNull(listener);
        if(Files.isSymbolicLink(root)||Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment directory");
        Files.createDirectories(root);permissions(root,"rwx------");
        checkpoints=new TransferCheckpointStore(root.resolve(".tasks-v2"));this.storage=Objects.requireNonNull(storage);
        FileChannel lease=FileChannel.open(root.resolve(".tasks-v2/.writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{if(lease.tryLock()==null)throw new IOException("Attachment directory already has a writer");permissions(root.resolve(".tasks-v2/.writer.lock"),"rw-------");}
        catch(IOException|java.nio.channels.OverlappingFileLockException error){lease.close();throw new IOException("Attachment directory already has a writer",error);}writerLock=lease;
        initialWindow=rfcomm?65536:1048576;maxWindow=rfcomm?524288:8388608;creditBatch=rfcomm?32768:262144;creditDelayNs=rfcomm?50_000_000:10_000_000;
        buffers=new TransferBufferPool(TransferLimits.DATA_BYTES,TransferLimits.DATA_BYTES);
        timer.scheduleWithFixedDelay(()->{
            if(closed||!creditPending.compareAndSet(false,true))return;
            if(!post(()->{try{Task task=incoming;if(task!=null&&!task.canceled.get()){checkpointIncoming(task,false,TransferCheckpoint.State.ACTIVE);credit(task,false);}}finally{creditPending.set(false);}}))creditPending.set(false);
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
                requireRoom(TransferTaskKey.Direction.SEND);
                if(!source.seekable()||source.size()<0)throw new IOException("Attachment source requires a bounded snapshot");
                source.verifyUnchanged();AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),name,mime,source.size(),null,System.currentTimeMillis());
                task=new Task(info,true,source.generation(),null);task.source=source;task.key=taskKey(info.id,task.generation,true);task.reference=source.persistentReference();outgoing=task;
                checkpoint(task,TransferCheckpoint.State.ACTIVE);notice(task,true);
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
        if(packet.kind==TransferPacket.Kind.STATUS){restoreIncoming(packet);return;}
        if(packet.kind==TransferPacket.Kind.END&&incoming==null&&checkpoints.load(taskKey(packet.transferId,packet.sourceGeneration,false)).map(value->value.state()==TransferCheckpoint.State.COMPLETE).orElse(false)){restoreIncoming(packet);return;}
        if(packet.kind==TransferPacket.Kind.OFFER) {
            if(incoming!=null){send(control(packet,TransferPacket.Kind.CANCEL,false,0,0,null,"busy"));return;}
            if(ended.containsKey(key(packet.transferId,packet.sourceGeneration,true)))throw new IOException("Reused attachment task");
            TransferTaskKey key=taskKey(packet.transferId,packet.sourceGeneration,false);
            if(checkpoints.load(key).isPresent())throw new IOException("Reused durable attachment task");
            try{requireRoom(TransferTaskKey.Direction.RECEIVE);}catch(IOException error){send(control(packet,TransferPacket.Kind.CANCEL,false,0,0,null,"busy"));return;}
            Task task=new Task(packet.info,false,packet.sourceGeneration,partial(key));task.key=key;incoming=task;notice(task,true);
            try{task.reservation=storage.reserve(key,task.info.size,0);}catch(IOException error){finish(task,"failed",true);return;}
            task.output=FileChannel.open(task.partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);permissions(task.partial,"rw-------");
            checkpoint(task,TransferCheckpoint.State.ACTIVE);
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
        if(packet.kind==TransferPacket.Kind.PAUSE){pauseTask(task,false);return;}
        if(task.canceled.get())return;
        switch(packet.kind) {
            case ACCEPT:
                require(task.outgoing&&task.state.equals("offered")&&packet.offset==0&&packet.windowBytes>0);
                task.window=new TransferByteWindow(packet.windowBytes,maxWindow);task.whole=digest();task.block=digest();task.input=task.source.open(0);task.state="transferring";notice(task,true);pump(task);break;
            case CREDIT:
                require(task.outgoing&&(task.state.equals("transferring")||task.state.equals("awaitingReceipt")));
                require(packet.verifiedOffset>=task.verified&&packet.durableOffset>=task.durable);
                task.window.acknowledge(packet.offset,packet.windowBytes);task.written=packet.offset;task.verified=packet.verifiedOffset;
                if(packet.durableOffset>task.durable){task.durable=packet.durableOffset;checkpoint(task,TransferCheckpoint.State.ACTIVE);}
                notice(task,false);if(task.state.equals("transferring"))pump(task);break;
            case RESUME:
                require(task.outgoing&&task.state.equals("checking")&&!packet.fromSender&&packet.offset==packet.durableOffset&&packet.verifiedOffset==packet.offset&&packet.windowBytes>0);
                restoreSource(task,packet);break;
            case DATA:
                require(!task.outgoing&&task.state.equals("transferring")&&packet.offset==task.written&&packet.data.length<=TransferLimits.BLOCK_BYTES-(task.written-task.verified)
                        &&packet.data.length<=initialWindow-(task.written-task.lastCredit));
                ByteBuffer data=ByteBuffer.wrap(packet.data);while(data.hasRemaining())task.output.write(data);
                task.whole.update(packet.data);task.block.update(packet.data);task.written+=packet.data.length;task.reservation.written(task.written);notice(task,false);credit(task,false);break;
            case BLOCK_HASH:
                require(!task.outgoing&&packet.offset==task.written&&task.written>task.verified&&(task.written-task.verified==TransferLimits.BLOCK_BYTES||task.written==task.info.size));
                if(!MessageDigest.isEqual(task.block.digest(),packet.hash)){finish(task,"failed",true);return;}task.verified=task.written;checkpoints.appendBlock(task.key,task.verified,packet.hash);checkpointIncoming(task,false,TransferCheckpoint.State.ACTIVE);break;
            case END:
                require(!task.outgoing&&task.written==task.info.size&&task.verified==task.info.size);
                if(!MessageDigest.isEqual(task.whole.digest(),packet.hash)){finish(task,"failed",true);return;}
                task.info=AttachmentInfo.v2(task.info.id,task.info.name,task.info.mime,task.info.size,AttachmentInfo.hex(packet.hash),task.info.time);
                task.state="verifying";notice(task,true);checkpointIncoming(task,true,TransferCheckpoint.State.ACTIVE);task.output.close();task.output=null;
                Path destination=AttachmentTransfer.file(root,task.info);if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Attachment destination exists");
                Files.move(task.partial,destination,StandardCopyOption.ATOMIC_MOVE);TransferCheckpointStore.syncDirectory(root);checkpoints.complete(task.key,task.info.size,packet.hash);task.state="received";
                notice(task,true);closeResource(task.reservation);remember(task);incoming=null;send(control(packet,TransferPacket.Kind.COMPLETE,false,task.info.size,0,packet.hash,""));break;
            case COMPLETE:
                require(task.outgoing&&(task.state.equals("awaitingReceipt")||task.state.equals("checking")));
                if(task.state.equals("checking"))verifyCompletedSource(task,packet.hash);
                require(task.info.hash.equals(AttachmentInfo.hex(packet.hash)));
                task.written=task.verified=task.durable=task.info.size;checkpoints.complete(task.key,task.info.size,packet.hash);finish(task,"delivered",false);break;
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
                if(task.sent%TransferLimits.BLOCK_BYTES==0||task.sent==task.info.size){byte[] hash=task.block.digest();checkpoints.appendBlock(task.key,task.sent,hash);send(control(task,TransferPacket.Kind.BLOCK_HASH,true,task.sent,0,hash,""));}
            }
            if(!task.canceled.get()&&task.sent==task.info.size) {
                task.source.verifyUnchanged();byte[] hash=task.whole.digest();task.info=AttachmentInfo.v2(task.info.id,task.info.name,task.info.mime,task.info.size,AttachmentInfo.hex(hash),task.info.time);
                task.state="awaitingReceipt";closeResource(task.input);task.input=null;notice(task,true);send(control(task,TransferPacket.Kind.END,true,task.sent,0,hash,""));
            }
        }catch(IOException error){if(!task.canceled.get())finish(task,"failed",true);}
    }
    private void credit(Task task,boolean force)throws IOException {
        if(task.outgoing||task.written==task.lastCredit&&task.verified==task.lastCreditVerified&&task.durable==task.lastCreditDurable)return;
        long now=System.nanoTime(),uncredited=task.written-task.lastCredit;
        if(force||uncredited>=creditBatch||now-task.lastCreditNs>=creditDelayNs||uncredited>=initialWindow-TransferLimits.DATA_BYTES) {
            send(TransferPacket.progress(TransferPacket.Kind.CREDIT,task.info.id,task.generation,connection,task.info.size,task.written,task.verified,task.durable,initialWindow,null));task.lastCredit=task.written;task.lastCreditVerified=task.verified;task.lastCreditDurable=task.durable;task.lastCreditNs=now;
        }
    }
    private TransferTaskKey taskKey(String id,String generation,boolean sent){return new TransferTaskKey(localRoot,remoteRoot,sent?TransferTaskKey.Direction.SEND:TransferTaskKey.Direction.RECEIVE,id,generation);}
    private Path partial(TransferTaskKey key){return root.resolve(key.fileName()+".part");}
    private void checkpoint(Task task,TransferCheckpoint.State state)throws IOException {
        checkpoints.checkpoint(new TransferCheckpoint(task.key,task.info,task.reference,task.written,task.verified,task.durable,state,System.currentTimeMillis()));task.lastCheckpointNs=System.nanoTime();
    }
    private void checkpointIncoming(Task task,boolean force,TransferCheckpoint.State state)throws IOException {
        long now=System.nanoTime();if(!force&&(task.verified==task.durable||task.verified-task.durable<CHECKPOINT_BYTES&&now-task.lastCheckpointNs<2_000_000_000L))return;
        if(task.output!=null&&task.output.isOpen())task.output.force(true);
        else try(FileChannel content=FileChannel.open(task.partial,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){content.force(true);}
        task.durable=task.verified;checkpoint(task,state);
    }
    private void requireRoom(TransferTaskKey.Direction direction)throws IOException {
        int pending=0;long now=System.currentTimeMillis();
        for(TransferCheckpoint value:checkpoints.list()) {
            if(!value.key().localRoot().equals(localRoot)||!value.key().remoteRoot().equals(remoteRoot)||value.key().direction()!=direction
                    ||value.state()==TransferCheckpoint.State.COMPLETE||value.state()==TransferCheckpoint.State.CANCELED)continue;
            if(now-value.updatedMillis()>RETENTION_MS){checkpoints.cancel(value.key());if(direction==TransferTaskKey.Direction.RECEIVE)Files.deleteIfExists(partial(value.key()));}
            else pending++;
        }if(pending>=4)throw new IOException("Too many paused attachment tasks");
    }
    public void pause(String id,boolean sent) {
        Task task=sent?outgoing:incoming;if(task==null||!task.info.id.equals(id))return;
        task.canceled.set(true);closeTaskIO(task);post(()->{if((sent?outgoing:incoming)==task)pauseTask(task,true);});
    }
    private void pauseTask(Task task,boolean notifyPeer)throws IOException {
        task.canceled.set(true);closeTaskIO(task);
        TransferCheckpoint saved=checkpoints.load(task.key).orElse(null);
        if(saved!=null&&(saved.state()==TransferCheckpoint.State.COMPLETE||saved.state()==TransferCheckpoint.State.CANCELED)){closeResource(task.reservation);return;}
        if(!task.outgoing&&Files.exists(task.partial,LinkOption.NOFOLLOW_LINKS)) {
            try(FileChannel file=FileChannel.open(task.partial,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){file.truncate(task.verified);file.force(true);}
            task.written=task.durable=task.verified;checkpoints.truncateIndex(task.key,task.durable);
        }
        checkpoint(task,TransferCheckpoint.State.PAUSED);closeResource(task.reservation);task.state="paused";notice(task,true);remember(task);
        if(task==outgoing)outgoing=null;if(task==incoming)incoming=null;
        if(notifyPeer)send(control(task,TransferPacket.Kind.PAUSE,task.outgoing,0,0,null,""));
    }
    /** Explicit resume; source ownership passes here. A receiver supplies null. */
    public CompletableFuture<String> resume(TransferTaskKey key,AttachmentSource source) {
        CompletableFuture<String> result=new CompletableFuture<>();if(source!=null){preparing.put(result,source);result.whenComplete((id,error)->preparing.remove(result));}
        if(!post(()->{try {
            require(key.localRoot().equals(localRoot)&&key.remoteRoot().equals(remoteRoot));
            TransferCheckpoint saved=checkpoints.load(key).orElseThrow(()->new IOException("Attachment checkpoint missing"));
            require(saved.state()!=TransferCheckpoint.State.CANCELED&&(saved.state()==TransferCheckpoint.State.COMPLETE||System.currentTimeMillis()-saved.updatedMillis()<=RETENTION_MS));
            if(key.direction()==TransferTaskKey.Direction.RECEIVE){require(source==null&&incoming==null);send(TransferPacket.control(TransferPacket.Kind.STATUS,key.transferId(),key.sourceGeneration(),connection,false,saved.info().size,0,0,null,""));}
            else startOutgoing(saved,source);
            result.complete(key.transferId());
        }catch(Exception error){closeResource(source);result.completeExceptionally(error);}})){closeResource(source);result.completeExceptionally(new IOException("Attachment session closed"));}
        return result;
    }
    private void startOutgoing(TransferCheckpoint saved,AttachmentSource source)throws Exception {
        require(outgoing==null&&source!=null&&source.seekable()&&source.size()==saved.info().size&&source.generation().equals(saved.key().sourceGeneration())
                &&source.persistentReference().equals(saved.sourceReference()));source.verifyUnchanged();
        if(saved.state()==TransferCheckpoint.State.COMPLETE){closeResource(source);listener.changed(new AttachmentRecord(saved.info(),true,"delivered",saved.info().size));return;}
        Task task=new Task(saved.info(),true,saved.key().sourceGeneration(),null);task.key=saved.key();task.reference=saved.sourceReference();task.source=source;task.written=saved.writtenOffset();task.verified=saved.verifiedOffset();task.durable=saved.durableOffset();task.state="checking";outgoing=task;notice(task,true);
        send(control(task,TransferPacket.Kind.STATUS,true,0,0,null,""));
    }
    private void restoreIncoming(TransferPacket packet)throws Exception {
        if(!packet.fromSender) {
            require(outgoing==null);TransferCheckpoint saved=checkpoints.load(taskKey(packet.transferId,packet.sourceGeneration,true)).orElseThrow(()->new IOException("Unknown resume source"));
            require(saved.state()!=TransferCheckpoint.State.CANCELED&&saved.info().size==packet.totalSize&&(saved.state()==TransferCheckpoint.State.COMPLETE||System.currentTimeMillis()-saved.updatedMillis()<=RETENTION_MS));
            String uri=saved.sourceReference().split("\n",2)[0];if(!uri.startsWith("file:"))throw new IOException("Source needs selection or restored provider grant");
            FileAttachmentSource source=new FileAttachmentSource(Paths.get(java.net.URI.create(uri)),saved.key().sourceGeneration());
            try{startOutgoing(saved,source);}catch(Exception error){closeResource(source);throw error;}return;
        }
        TransferTaskKey key=taskKey(packet.transferId,packet.sourceGeneration,false);TransferCheckpoint saved=checkpoints.load(key).orElse(null);
        if(saved==null||saved.state()==TransferCheckpoint.State.CANCELED||saved.state()!=TransferCheckpoint.State.COMPLETE&&System.currentTimeMillis()-saved.updatedMillis()>RETENTION_MS) {
            send(control(packet,TransferPacket.Kind.CANCEL,false,0,0,null,saved!=null&&saved.state()==TransferCheckpoint.State.CANCELED?"canceled":"rejected"));return;
        }
        require(packet.totalSize==saved.info().size);
        Path destination=AttachmentTransfer.file(root,saved.info());
        if(saved.state()==TransferCheckpoint.State.COMPLETE||Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
            require(saved.info().hash!=null&&Files.size(destination)==saved.info().size);
            TransferCheckpoint complete=new TransferCheckpoint(key,saved.info(),saved.sourceReference(),saved.info().size,saved.info().size,saved.info().size,TransferCheckpoint.State.COMPLETE,System.currentTimeMillis());
            byte[] hash=checkpoints.verifyPrefix(complete,destination,()->closed).sha256();require(saved.info().hash.equals(AttachmentInfo.hex(hash)));checkpoints.complete(key,saved.info().size,hash);
            listener.changed(new AttachmentRecord(saved.info(),false,"received",saved.info().size));send(control(packet,TransferPacket.Kind.COMPLETE,false,saved.info().size,0,hash,""));return;
        }
        require(incoming==null);Task task=new Task(saved.info(),false,key.sourceGeneration(),partial(key));task.key=key;task.reference=saved.sourceReference();task.written=task.verified=task.durable=saved.durableOffset();task.state="checking";incoming=task;notice(task,true);
        TransferCheckpointStore.PrefixResult prefix;
        try{prefix=checkpoints.verifyPrefix(saved,task.partial,()->closed||task.canceled.get());}catch(IOException error){if(!closed&&!task.canceled.get())finish(task,"failed",true);return;}
        task.written=task.verified=task.durable=prefix.offset();task.lastCredit=task.lastCreditVerified=task.lastCreditDurable=prefix.offset();task.whole=prefix.whole();task.block=digest();
        task.reservation=storage.reserve(key,task.info.size,task.durable);task.output=FileChannel.open(task.partial,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);task.output.truncate(task.durable);task.output.position(task.durable);checkpoints.truncateIndex(key,task.durable);
        task.state="transferring";task.lastCreditNs=System.nanoTime();checkpoint(task,TransferCheckpoint.State.ACTIVE);notice(task,true);
        send(TransferPacket.progress(TransferPacket.Kind.RESUME,task.info.id,task.generation,connection,task.info.size,task.durable,task.durable,task.durable,initialWindow,prefix.sha256()));
    }
    private byte[] scanSource(Task task,long end)throws Exception {
        task.source.verifyUnchanged();task.whole=digest();task.block=digest();MessageDigest prefix=digest();checkpoints.truncateIndex(task.key,0);
        task.input=task.source.open(0);long position=0;byte[] buffer=new byte[TransferLimits.DATA_BYTES];int empty=0;
        try {while(position<end){if(closed||task.canceled.get())throw new InterruptedIOException("Source verification canceled");int n=task.input.read(buffer,0,(int)Math.min(buffer.length,Math.min(end-position,TransferLimits.BLOCK_BYTES-position%TransferLimits.BLOCK_BYTES)));if(n<0)throw new EOFException("Source truncated");if(n==0){if(++empty>32)throw new IOException("Source made no progress");continue;}empty=0;task.whole.update(buffer,0,n);prefix.update(buffer,0,n);task.block.update(buffer,0,n);position+=n;if(position%TransferLimits.BLOCK_BYTES==0||position==task.info.size)checkpoints.appendBlock(task.key,position,task.block.digest());}}
        finally{closeResource(task.input);task.input=null;Arrays.fill(buffer,(byte)0);}task.source.verifyUnchanged();return prefix.digest();
    }
    private void restoreSource(Task task,TransferPacket packet)throws Exception {
        require(packet.offset>=task.durable&&(packet.offset%TransferLimits.BLOCK_BYTES==0||packet.offset==task.info.size));
        if(!MessageDigest.isEqual(scanSource(task,packet.offset),packet.hash)){finish(task,"failed",true);return;}
        task.sent=task.written=task.verified=task.durable=packet.offset;task.window=new TransferByteWindow(packet.windowBytes,maxWindow,packet.offset);
        task.input=task.source.open(packet.offset);task.state="transferring";checkpoint(task,TransferCheckpoint.State.ACTIVE);notice(task,true);pump(task);
    }
    private void verifyCompletedSource(Task task,byte[] hash)throws Exception {
        require(MessageDigest.isEqual(scanSource(task,task.info.size),hash));task.info=AttachmentInfo.v2(task.info.id,task.info.name,task.info.mime,task.info.size,AttachmentInfo.hex(hash),task.info.time);
    }
    public void cancel(String id,boolean sent) {
        Task task=sent?outgoing:incoming;if(task==null||!task.info.id.equals(id))return;task.canceled.set(true);closeTaskIO(task);
        post(()->{if((sent?outgoing:incoming)==task)finish(task,"canceled",true);});
    }
    private void finish(Task task,String state,boolean notifyPeer)throws IOException {
        task.canceled.set(true);closeTaskIO(task);if(task.partial!=null)Files.deleteIfExists(task.partial);
        closeResource(task.reservation);
        if(!state.equals("delivered")){if(checkpoints.load(task.key).isEmpty())checkpoint(task,TransferCheckpoint.State.ACTIVE);checkpoints.cancel(task.key);}
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
        try{actor.execute(()->{Exception failure=null;try{for(Task task:new Task[]{outgoing,incoming})if(task!=null){try{pauseTask(task,false);}finally{closeResource(task.reservation);}}}catch(Exception error){failure=error;}finally{outgoing=null;incoming=null;queued.set(0);buffers.close();closeResource(writerLock);if(failure==null)terminated.complete(null);else terminated.completeExceptionally(failure);}});}
        catch(RejectedExecutionException ignored){queued.set(0);buffers.close();closeResource(writerLock);terminated.complete(null);}
        actor.shutdown();
    }
}

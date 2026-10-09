package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One outgoing and one incoming transfer, bounded chunks, explicit consent and durable receipts. */
public final class AttachmentTransfer implements AutoCloseable {
    public interface Source { InputStream open() throws IOException; }
    public interface Wire { boolean send(Frame frame); void abort(); }
    public interface Listener { void changed(AttachmentRecord record) throws IOException; }
    private final Path root;
    private final Wire wire;
    private final Listener listener;
    private final int chunkSize;
    private final long sizeLimit;
    private final boolean retainSentPhotos;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->daemon(r,"attachment-state"),new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor preparer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->daemon(r,"attachment-source"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"attachment-timeout"));
    private final LinkedHashMap<String,Integer> ended=new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    public CompletableFuture<Void> shutdown(){close();return terminated;}
    private volatile boolean closed;
    private volatile Task outgoing,incoming;
    private static final class Task {
        volatile AttachmentInfo info;
        final boolean outgoing;
        final AtomicBoolean canceled=new AtomicBoolean();
        final AtomicReference<InputStream> preparing=new AtomicReference<>();
        final Path temporary;
        String state="offered";
        long sent,done,lastNotice;
        volatile long activity=System.nanoTime();
        FileOutputStream output;
        InputStream input;
        MessageDigest digest;
        final ArrayDeque<Long> outstanding=new ArrayDeque<>();
        Task(AttachmentInfo info,boolean outgoing,Path temporary){this.info=info;this.outgoing=outgoing;this.temporary=temporary;}
    }
    public AttachmentTransfer(Path root,Wire wire,Listener listener,int chunkSize,long sizeLimit)throws IOException {
        this(root,wire,listener,chunkSize,sizeLimit,false);
    }
    public AttachmentTransfer(Path root,Wire wire,Listener listener,int chunkSize,long sizeLimit,boolean retainSentPhotos)throws IOException {
        this.root=root;this.wire=wire;this.listener=listener;
        this.retainSentPhotos=retainSentPhotos;
        if(chunkSize<1||chunkSize>AttachmentInfo.CHUNK_SIZE||sizeLimit<0||sizeLimit>AttachmentInfo.MAX_SIZE)throw new IOException("Invalid transfer limits");
        this.chunkSize=chunkSize;this.sizeLimit=sizeLimit;privateDirectory(root);
        timer.scheduleWithFixedDelay(()->execute(()->timeouts()),1,1,TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    private interface Work {void run()throws Exception;}
    private boolean execute(Work work){
        if(closed)return false;
        try {worker.execute(()->{if(closed)return;try{work.run();}catch(Exception e){abort();}});return true;}
        catch(RejectedExecutionException e){abort();return false;}
    }
    public CompletableFuture<String> offer(Source source,String name,String mime) {
        CompletableFuture<String> result=new CompletableFuture<>();pending.add(result);result.whenComplete((v,e)->pending.remove(result));
        if(!execute(()->{
            if(outgoing!=null){result.completeExceptionally(new IOException("Attachment busy"));return;}
            String id=UUID.randomUUID().toString();
            AttachmentInfo info;
            try{info=new AttachmentInfo(id,name,mime,0,"0000000000000000000000000000000000000000000000000000000000000000",System.currentTimeMillis());}
            catch(IOException e){result.completeExceptionally(e);return;}
            Task task=new Task(info,true,root.resolve("out-"+id+".part"));task.state="preparing";outgoing=task;
            try{notice(task,true);preparer.execute(()->prepare(task,source));result.complete(id);}
            catch(Exception e){result.completeExceptionally(e);finish(task,"failed",true);}
        }))result.completeExceptionally(new IOException("Attachment connection closed"));
        return result;
    }
    private void prepare(Task task,Source source){
        boolean posted=false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream input=source.open()) {
                task.preparing.set(input);
                if(task.canceled.get()||closed)throw new IOException("Canceled");
                try(OutputStream output=Files.newOutputStream(task.temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    privateFile(task.temporary);byte[] bytes=new byte[chunkSize];int n;
                    while((n=input.read(bytes))!=-1){
                        if(task.canceled.get()||closed)throw new IOException("Canceled");if(n==0)continue;
                        if(n>sizeLimit-size)throw new IOException("Attachment too large");size+=n;output.write(bytes,0,n);digest.update(bytes,0,n);task.activity=System.nanoTime();
                    }
                }
            } finally{task.preparing.set(null);}
            AttachmentInfo ready=new AttachmentInfo(task.info.id,task.info.name,task.info.mime,size,AttachmentInfo.hex(digest.digest()),task.info.time);
            posted=execute(()->{if(outgoing!=task||task.canceled.get()){Files.deleteIfExists(task.temporary);return;}task.info=ready;task.state="offered";touch(task);notice(task,true);send(ready.offer());});
        }catch(Exception e){posted=execute(()->{if(outgoing==task)finish(task,"failed",false);else Files.deleteIfExists(task.temporary);});}
        finally{if(!posted||task.canceled.get())delete(task.temporary);}
    }
    public void receive(Frame frame){receive(frame,false);}
    public void receive(Frame frame,boolean automaticallyAccept){
        if(!execute(()->{handle(frame);if(automaticallyAccept&&frame.type==Frame.FILE_OFFER)accept(frame.id);}))wire.abort();
    }
    public CompletableFuture<Void> accept(String id){return command(()->{
        Task task=incoming;if(task==null||!task.info.id.equals(id)||!task.state.equals("offered"))return;
        try{
            if(root.toFile().getUsableSpace()<task.info.size+1024*1024)throw new IOException("Insufficient disk space");
            Files.createFile(task.temporary);privateFile(task.temporary);task.output=new FileOutputStream(task.temporary.toFile());task.digest=MessageDigest.getInstance("SHA-256");
            task.state="transferring";touch(task);notice(task,true);send(control(Frame.FILE_ACCEPT,id,""));
        }catch(IOException e){finish(task,"failed",true);}
    });}
    public CompletableFuture<Void> reject(String id){return command(()->{Task task=incoming;if(task!=null&&task.info.id.equals(id)&&task.state.equals("offered")){send(control(Frame.FILE_REJECT,id,"rejected"));finish(task,"rejected",false);}});}
    public CompletableFuture<Void> cancel(String id,boolean sent){return command(()->{Task task=sent?outgoing:incoming;if(task!=null&&task.info.id.equals(id))finish(task,"canceled",!task.state.equals("preparing"));});}
    public CompletableFuture<Void> cancelAll(){return command(()->{if(outgoing!=null)finish(outgoing,"canceled",!outgoing.state.equals("preparing"));if(incoming!=null)finish(incoming,"canceled",true);});}
    private CompletableFuture<Void> command(Work work){CompletableFuture<Void> result=new CompletableFuture<>();pending.add(result);result.whenComplete((v,e)->pending.remove(result));if(!execute(()->{try{work.run();result.complete(null);}catch(Exception e){result.completeExceptionally(e);throw e;}}))result.completeExceptionally(new IOException("Transfer closed"));return result;}
    private void handle(Frame frame)throws Exception {
        if(frame.type==Frame.FILE_OFFER){
            AttachmentInfo info=AttachmentInfo.from(frame);
            if(outgoing!=null&&outgoing.info.id.equals(info.id))throw new IOException("Conflicting bidirectional attachment ID");
            if(ended.containsKey(info.id))throw new IOException("Reused transfer ID");
            if(incoming!=null){send(control(Frame.FILE_REJECT,info.id,"busy"));return;}
            if(info.size>sizeLimit){send(control(Frame.FILE_REJECT,info.id,"tooLarge"));return;}
            incoming=new Task(info,false,root.resolve("in-"+info.id+".part"));notice(incoming,true);return;
        }
        if(frame.type==Frame.FILE_ACCEPT||frame.type==Frame.FILE_REJECT||frame.type==Frame.FILE_PROGRESS||frame.type==Frame.FILE_RECEIPT){
            Task task=outgoing;
            if(task==null||!task.info.id.equals(frame.id)){if(ended.containsKey(frame.id))return;throw new IOException("Unknown outgoing attachment");}
            if(frame.type==Frame.FILE_ACCEPT){require(task.state.equals("offered"));task.state="transferring";task.input=Files.newInputStream(task.temporary);touch(task);notice(task,true);pump(task);}
            else if(frame.type==Frame.FILE_REJECT){require(task.state.equals("offered"));finish(task,"rejected",false);}
            else if(frame.type==Frame.FILE_PROGRESS){
                require(task.state.equals("transferring")&&frame.offset>task.done&&frame.offset<=task.sent&&task.outstanding.contains(frame.offset));
                while(!task.outstanding.isEmpty()&&task.outstanding.peekFirst()<=frame.offset)task.outstanding.removeFirst();
                task.done=frame.offset;touch(task);notice(task,false);pump(task);
            }else{require(task.state.equals("awaitingReceipt"));task.done=task.info.size;finish(task,"delivered",false);}
            return;
        }
        if(frame.type==Frame.FILE_CANCEL){
            // UUIDs are direction-scoped; a malicious equal-ID bidirectional offer is refused below.
            Task task=incoming!=null&&incoming.info.id.equals(frame.id)?incoming:outgoing!=null&&outgoing.info.id.equals(frame.id)?outgoing:null;
            if(task==null){if(ended.containsKey(frame.id))return;throw new IOException("Unknown canceled attachment");}
            finish(task,frame.body.equals("canceled")?"canceled":"failed",false);return;
        }
        Task task=incoming;
        if(task==null||!task.info.id.equals(frame.id)){
            Integer allowed=ended.get(frame.id);
            if(allowed!=null&&frame.type==Frame.FILE_CHUNK&&allowed>0){ended.put(frame.id,allowed-1);return;}
            if(allowed!=null&&frame.type==Frame.FILE_FINISH)return;
            throw new IOException("Unknown incoming attachment");
        }
        require(task.state.equals("transferring"));
        if(frame.type==Frame.FILE_CHUNK){
            require(frame.offset==task.done&&frame.data.length<=chunkSize&&frame.data.length<=task.info.size-task.done);
            try{task.output.write(frame.data);task.digest.update(frame.data);task.done+=frame.data.length;touch(task);notice(task,false);send(new Frame(Frame.FILE_PROGRESS,frame.id,"",System.currentTimeMillis(),task.done,new byte[0]));}
            catch(IOException e){finish(task,"failed",true);}
        }else if(frame.type==Frame.FILE_FINISH){
            require(task.done==task.info.size);
            if(!AttachmentInfo.hex(task.digest.digest()).equals(task.info.hash)){finish(task,"failed",true);return;}
            try {
                task.state="verifying";notice(task,true);task.output.getChannel().force(true);task.output.close();task.output=null;
                Path destination=file(root,task.info);
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Attachment already exists");
                Files.move(task.temporary,destination,StandardCopyOption.ATOMIC_MOVE);
                task.state="received";
                try{notice(task,true);}catch(IOException e){delete(destination);throw e;}
                remember(task.info.id,0);incoming=null;
                // Persistence has committed. A lost receipt cannot roll it back.
                if(!wire.send(control(Frame.FILE_RECEIPT,frame.id,"")))wire.abort();
            }catch(IOException e){finish(task,"failed",true);}
        }else throw new IOException("Unexpected attachment frame");
    }
    private void pump(Task task)throws Exception {
        try {
            while(task.outstanding.size()<4&&task.sent<task.info.size){
                int size=(int)Math.min(chunkSize,task.info.size-task.sent);byte[] bytes=new byte[size];int offset=0;
                while(offset<size){int n=task.input.read(bytes,offset,size-offset);if(n<0)throw new IOException("Truncated source");offset+=n;}
                send(new Frame(Frame.FILE_CHUNK,task.info.id,"",System.currentTimeMillis(),task.sent,bytes));task.sent+=size;task.outstanding.addLast(task.sent);
            }
            if(task.done==task.info.size&&task.outstanding.isEmpty()){
                task.state="awaitingReceipt";touch(task);notice(task,true);send(control(Frame.FILE_FINISH,task.info.id,""));close(task.input);task.input=null;
            }
        }catch(IOException e){finish(task,"failed",true);}
    }
    private void timeouts()throws IOException {
        long now=System.nanoTime();Task[] tasks={outgoing,incoming};
        for(Task task:tasks)if(task!=null&&TimeUnit.NANOSECONDS.toSeconds(now-task.activity)>= (task.state.equals("offered")?120:60))finish(task,"failed",!task.state.equals("preparing"));
    }
    private void finish(Task task,String state,boolean notifyPeer)throws IOException {
        task.canceled.set(true);close(task.preparing.getAndSet(null));close(task.input);close(task.output);task.input=null;task.output=null;
        if(retainSentPhotos&&task.outgoing&&state.equals("delivered")&&task.info.mime.startsWith("image/")){
            Files.move(task.temporary,file(root,task.info,true),StandardCopyOption.ATOMIC_MOVE);
        }
        delete(task.temporary);
        if(notifyPeer)send(control(Frame.FILE_CANCEL,task.info.id,state.equals("canceled")?"canceled":"failed"));
        task.state=state;notice(task,true);remember(task.info.id,4);if(task==outgoing)outgoing=null;if(task==incoming)incoming=null;
    }
    private void remember(String id,int allowance){ended.put(id,allowance);while(ended.size()>16)ended.remove(ended.keySet().iterator().next());}
    private void notice(Task task,boolean force)throws IOException {long now=System.nanoTime();if(force||now-task.lastNotice>=250000000){listener.changed(new AttachmentRecord(task.info,task.outgoing,task.state,task.done));task.lastNotice=now;}}
    private void touch(Task task){task.activity=System.nanoTime();}
    private void send(Frame frame)throws IOException {if(!wire.send(frame))throw new IOException("Attachment send failed");}
    private static Frame control(int type,String id,String reason){return new Frame(type,id,reason,System.currentTimeMillis());}
    private static void require(boolean valid)throws IOException {if(!valid)throw new IOException("Invalid attachment sequence");}
    private void abort(){wire.abort();close();}
    public void close(){
        if(closed)return;closed=true;timer.shutdownNow();preparer.shutdownNow();for(CompletableFuture<?> future:pending)future.completeExceptionally(new IOException("Transfer closed"));
        // Close streams immediately to unblock I/O, then serialize terminal history updates.
        for(Task task:new Task[]{outgoing,incoming})if(task!=null){task.canceled.set(true);close(task.preparing.getAndSet(null));close(task.input);close(task.output);}
        worker.getQueue().clear();
        try{worker.execute(()->{for(Task task:new Task[]{outgoing,incoming})if(task!=null){delete(task.temporary);try{if(!task.state.equals("received")&&!task.state.equals("delivered")){task.state=task.state.equals("awaitingReceipt")?"unknown":"interrupted";notice(task,true);}}catch(IOException ignored){}}outgoing=null;incoming=null;terminated.complete(null);});}catch(RejectedExecutionException ignored){terminated.complete(null);}
        worker.shutdown();
    }
    public static Path file(Path root,AttachmentInfo info)throws IOException {
        return file(root,info,false);
    }
    public static Path file(Path root,AttachmentInfo info,boolean outgoing)throws IOException {
        String id=info.id;String suffix="bin";int dot=info.name.lastIndexOf('.');if(dot>=0){String extension=info.name.substring(dot+1);if(extension.matches("[A-Za-z0-9]{1,16}")&&!extension.equalsIgnoreCase("part"))suffix=extension.toLowerCase(Locale.ROOT);}
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid attachment path");
        Path path=root.resolve((outgoing?"out-":"in-")+id+"."+suffix);if(Files.isSymbolicLink(path)||Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment file");return path;
    }
    public static void clean(Path root,Set<String> received)throws IOException {
        privateDirectory(root);Set<String> durable=new HashSet<>(),sources=new HashSet<>();Path journal=root.resolve(".tasks-v2");
        if(Files.exists(journal,LinkOption.NOFOLLOW_LINKS)) {
            TransferCheckpointStore store=new TransferCheckpointStore(journal);
            for(TransferCheckpoint value:store.list()) {
                if(value.state()==TransferCheckpoint.State.CANCELED)continue;
                if(value.state()!=TransferCheckpoint.State.COMPLETE&&System.currentTimeMillis()-value.updatedMillis()>7L*24*60*60*1000){store.cancel(value.key());continue;}
                if(value.key().direction()==TransferTaskKey.Direction.RECEIVE) {
                    if(value.state()!=TransferCheckpoint.State.COMPLETE)durable.add(value.key().fileName()+".part");
                    if(value.info().hash!=null)durable.add(file(root,value.info()).getFileName().toString());
                }
                if(value.state()!=TransferCheckpoint.State.COMPLETE&&value.sourceReference().startsWith("snapshot:\n")) {
                    try{Path source=Paths.get(java.net.URI.create(value.sourceReference().substring(10).split("\n",2)[0]));if(!source.getParent().equals(root.toAbsolutePath().normalize().resolve(".sources-v2")))throw new IOException("Unsafe snapshot reference");sources.add(source.getFileName().toString());}
                    catch(IllegalArgumentException error){throw new IOException("Invalid snapshot reference",error);}
                }
            }
        }
        try(java.util.stream.Stream<Path> entries=Files.list(root)){
            for(Path path:(Iterable<Path>)entries::iterator){String name=path.getFileName().toString();if(name.equals(".tasks-v2"))continue;if(name.equals(".sources-v2")){cleanSources(path,sources);continue;}if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IOException("Unsafe attachment entry");
                if(durable.contains(name))continue;
                int prefix=name.startsWith("out-")?4:3;
                if(name.matches("[0-9a-f]{64}\\.part")||name.matches("(in|out)-[0-9a-f-]{36}\\.part")||name.matches("(in|out)-[0-9a-f-]{36}\\.[a-z0-9]{1,16}")&&!received.contains(name.substring(prefix,prefix+36)))Files.delete(path);
            }
        }
    }
    /** Explicit history deletion/revocation differs from startup recovery. */
    public static void clear(Path root)throws IOException {
        privateDirectory(root);Path journal=root.resolve(".tasks-v2");
        if(Files.exists(journal,LinkOption.NOFOLLOW_LINKS)){TransferCheckpointStore store=new TransferCheckpointStore(journal);for(TransferCheckpoint saved:store.list())if(saved.state()!=TransferCheckpoint.State.CANCELED)store.cancel(saved.key());}
        try(var entries=Files.list(root)){for(Path file:(Iterable<Path>)entries::iterator){if(file.getFileName().toString().equals(".tasks-v2"))continue;if(file.getFileName().toString().equals(".sources-v2")){cleanSources(file,Collections.emptySet());continue;}if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment entry");Files.delete(file);}}
    }
    private static void cleanSources(Path directory,Set<String> retained)throws IOException {privateDirectory(directory);try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe source snapshot");if(!retained.contains(file.getFileName().toString()))Files.delete(file);}}}
    private static void privateDirectory(Path path)throws IOException {
        if(Files.isSymbolicLink(path)||Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment directory");
        Files.createDirectories(path);if(Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    private static void privateFile(Path path)throws IOException {if(Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
    private static void close(Closeable stream){if(stream!=null)try{stream.close();}catch(IOException ignored){}}
    private static void delete(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
}

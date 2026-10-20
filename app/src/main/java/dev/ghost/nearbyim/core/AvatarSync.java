package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One identity-bound avatar transfer per direction, separate from chat and attachment state. */
public final class AvatarSync implements AutoCloseable {
    public interface Transport { boolean send(AvatarPacket packet); }
    public interface Listener {
        void onChanged(AvatarSnapshot snapshot);
        /** Protocol violations close the containing authenticated session. */
        default void onProtocolError(IOException error) {}
        /** Invalid images or local storage failures terminate only an avatar task. */
        default void onFailure(AvatarPacket.Code code) {}
    }
    private static final long TIMEOUT_NANOS=TimeUnit.SECONDS.toNanos(30);
    private static final Object PARTIAL_GATE=new Object();
    private static final Set<Path> ACTIVE_PARTIALS=new HashSet<>();
    private final AvatarStore local,remote;
    private final Transport transport;
    private final Listener listener;
    private final Object gate=new Object();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->daemon(()->{try{r.run();}finally{close();cleanupOwned();}},"nearby-avatar"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"nearby-avatar-timeout"));
    private final AtomicBoolean localQueued=new AtomicBoolean(),tickQueued=new AtomicBoolean();
    private boolean started;
    private final AtomicBoolean closed=new AtomicBoolean();
    private int queuedChunks,queuedControls;
    // Admission publishes the latest metadata before older disk work can commit.
    private AvatarProfile announced=AvatarProfile.defaults(0),latestRemote=AvatarProfile.defaults(0);
    private volatile Incoming incoming;
    private volatile Outgoing outgoing;
    private final LinkedHashMap<String,Ended> ended=new LinkedHashMap<>();
    private final LinkedHashSet<String> terminalAdmissions=new LinkedHashSet<>();

    private abstract static class Task {
        final String id;final AvatarProfile profile;long progress=System.nanoTime();
        Task(String id,AvatarProfile profile){this.id=id;this.profile=profile;}
        boolean matches(AvatarPacket packet){return id.equals(packet.transferId)&&profile.revision==packet.revision&&profile.avatarHash.equals(packet.avatarHash);}
    }
    private static final class Incoming extends Task {
        final Path path;final FileOutputStream output;final MessageDigest digest;long offset;
        Incoming(String id,AvatarProfile profile,Path path,FileOutputStream output,MessageDigest digest){super(id,profile);this.path=path;this.output=output;this.digest=digest;}
    }
    private static final class Outgoing extends Task {
        final byte[] png;final ArrayDeque<Long> pending=new ArrayDeque<>(AvatarPacket.WINDOW_CHUNKS);long sent,ack;
        Outgoing(String id,AvatarSnapshot snapshot){super(id,snapshot.profile());png=snapshot.png();}
    }
    private static final class Ended {
        final String id,hash;final long revision,size,sent;final boolean receiving;long offset;int remaining;
        Ended(Task task,boolean receiving,long offset,long sent){id=task.id;hash=task.profile.avatarHash;revision=task.profile.revision;size=task.profile.byteLength;this.receiving=receiving;this.offset=offset;this.sent=sent;remaining=AvatarPacket.WINDOW_CHUNKS;}
        Ended(AvatarPacket request){id=request.transferId;hash=request.avatarHash;revision=request.revision;size=sent=offset=0;receiving=false;remaining=0;}
        boolean matches(AvatarPacket packet){return id.equals(packet.transferId)&&revision==packet.revision&&hash.equals(packet.avatarHash);}
    }
    public AvatarSync(AvatarStore local,AvatarStore remote,Transport transport,Listener listener){
        this.local=Objects.requireNonNull(local);this.remote=Objects.requireNonNull(remote);this.transport=Objects.requireNonNull(transport);this.listener=Objects.requireNonNull(listener);
        if(local.ownerFingerprint().equals(remote.ownerFingerprint()))throw new IllegalArgumentException("Local and contact avatar identities must be independent");
    }
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    /** Call only after the containing session verifies both identities, both avatar bits and both READY records. */
    public void start(){
        if(closed.get())return;
        synchronized(gate){if(closed.get()||started)return;started=true;if(!submit(()->{
            try{reclaimPartials();AvatarSnapshot current=remote.snapshot();latestRemote=current.profile();synchronized(gate){if(announced.revision<latestRemote.revision)announced=latestRemote;}send(AvatarPacket.profile(local.snapshot().profile()));}
            catch(IOException storage){failure(AvatarPacket.Code.STORAGE_ERROR);}
        }))return;try{timer.scheduleWithFixedDelay(()->{if(tickQueued.compareAndSet(false,true)&&!submit(()->{try{expire();}finally{tickQueued.set(false);}}))tickQueued.set(false);},1,1,TimeUnit.SECONDS);}catch(RejectedExecutionException stopped){close();}}
    }
    /** Coalesces rapid local saves; immutable snapshots always come from this device's own store. */
    public void localChanged(){
        if(closed.get())return;
        synchronized(gate){if(closed.get()||!started)return;if(!localQueued.compareAndSet(false,true))return;
            if(!submit(()->{localQueued.set(false);try{AvatarProfile current=local.snapshot().profile();if(outgoing!=null&&!outgoing.profile.equals(current))cancelOutgoing(AvatarPacket.Code.SUPERSEDED,true);send(AvatarPacket.profile(current));}catch(IOException storage){failure(AvatarPacket.Code.STORAGE_ERROR);}}))localQueued.set(false);}
    }
    /** Nonblocking bounded admission. False means the caller must close its authenticated session. */
    public boolean receive(AvatarPacket packet){
        if(closed.get()||packet==null)return false;try{packet.validate();}catch(IOException invalid){return false;}
        synchronized(gate){
            if(closed.get()||!started)return false;boolean chunk=packet.kind==AvatarPacket.Kind.CHUNK;
            if(chunk?queuedChunks>=AvatarPacket.WINDOW_CHUNKS:queuedControls>=8)return false;
            if(packet.kind==AvatarPacket.Kind.PROFILE_UPDATE){
                if(packet.revision==announced.revision&&!packet.profile.equals(announced))return false;
                if(packet.revision>announced.revision)announced=packet.profile;
            }
            if(packet.kind==AvatarPacket.Kind.CANCEL||packet.kind==AvatarPacket.Kind.RESULT&&packet.code!=AvatarPacket.Code.OK){
                terminalAdmissions.add(taskKey(packet.transferId,packet.revision,packet.avatarHash));
                while(terminalAdmissions.size()>16)terminalAdmissions.remove(terminalAdmissions.iterator().next());
            }
            if(chunk)queuedChunks++;else queuedControls++;
            AtomicBoolean charged=new AtomicBoolean(true);
            Runnable consumed=()->{if(charged.compareAndSet(true,false))synchronized(gate){if(chunk)queuedChunks--;else queuedControls--;}};
            if(submit(()->{try{handle(packet,consumed);}catch(IOException violation){protocolError(violation);}finally{consumed.run();}}))return true;
            consumed.run();return false;
        }
    }
    private boolean submit(Runnable operation){
        synchronized(gate){if(closed.get())return false;try{worker.execute(()->{synchronized(gate){if(closed.get())return;}operation.run();});return true;}catch(RejectedExecutionException full){return false;}}
    }
    private void handle(AvatarPacket packet,Runnable consumed)throws IOException {
        switch(packet.kind){
            case PROFILE_UPDATE: profile(packet.profile);break;
            case REQUEST: request(packet);break;
            case CHUNK: chunk(packet,consumed);break;
            case ACK: ack(packet);break;
            case RESULT: result(packet);break;
            case CANCEL: cancel(packet);break;
            default:throw new IOException("Unknown avatar operation");
        }
    }
    private void profile(AvatarProfile profile)throws IOException {
        synchronized(gate){if(closed.get()||profile.revision<announced.revision)return;}
        if(profile.revision<latestRemote.revision)return;
        if(profile.revision==latestRemote.revision&&!profile.equals(latestRemote))throw new IOException("Conflicting avatar revision");
        if(incoming!=null){if(incoming.profile.equals(profile))return;cancelIncoming(AvatarPacket.Code.SUPERSEDED,true);}
        latestRemote=profile;
        AvatarSnapshot saved;
        try{saved=remote.snapshot();}catch(IOException storage){failure(AvatarPacket.Code.STORAGE_ERROR);return;}
        if(profile.revision<saved.profile().revision)return;
        if(profile.revision==saved.profile().revision&&!profile.equals(saved.profile()))throw new IOException("Conflicting persisted avatar revision");
        if(profile.equals(saved.profile())&&(profile.avatarHash.isEmpty()||saved.png().length!=0))return;
        try{
            if(profile.avatarHash.isEmpty()){adopt(profile,new byte[0],null);return;}
            byte[] cached=remote.content(profile.avatarHash);
            if(cached.length!=0&&matchesImage(profile,cached)){adopt(profile,cached,null);return;}
            Path path=createPartial();FileOutputStream output=null;
            try{PrivateMetadata.permissions(path,false);output=new FileOutputStream(path.toFile());Incoming task=new Incoming(UUID.randomUUID().toString(),profile,path,output,sha());
                synchronized(gate){if(closed.get()||!announced.equals(profile)){cleanup(task);return;}incoming=task;}}
            catch(IOException|RuntimeException error){if(output!=null)try{output.close();}catch(IOException ignored){}deletePartial(path);throw error;}
            send(AvatarPacket.request(profile,incoming.id));
        }catch(IOException storage){failure(AvatarPacket.Code.STORAGE_ERROR);if(incoming!=null)finishIncoming(AvatarPacket.Code.STORAGE_ERROR);}
    }
    private void request(AvatarPacket packet)throws IOException {
        if(incoming!=null&&incoming.id.equals(packet.transferId)||ended.containsKey(packet.transferId))throw new IOException("Reused avatar transfer identity");
        if(outgoing!=null){if(outgoing.id.equals(packet.transferId))throw new IOException("Repeated avatar request");rememberReply(packet);send(AvatarPacket.result(packet.transferId,packet.revision,packet.avatarHash,AvatarPacket.Code.BUSY));return;}
        AvatarSnapshot snapshot;
        try{snapshot=local.snapshot();}catch(IOException storage){rememberReply(packet);send(AvatarPacket.result(packet.transferId,packet.revision,packet.avatarHash,AvatarPacket.Code.STORAGE_ERROR));failure(AvatarPacket.Code.STORAGE_ERROR);return;}
        if(snapshot.profile().revision!=packet.revision||!snapshot.profile().avatarHash.equals(packet.avatarHash)){
            rememberReply(packet);send(AvatarPacket.cancel(packet.transferId,packet.revision,packet.avatarHash,AvatarPacket.Code.SUPERSEDED));send(AvatarPacket.profile(snapshot.profile()));return;
        }
        if(snapshot.png().length!=snapshot.profile().byteLength){rememberReply(packet);send(AvatarPacket.result(packet.transferId,packet.revision,packet.avatarHash,AvatarPacket.Code.STORAGE_ERROR));return;}
        synchronized(gate){if(closed.get())return;outgoing=new Outgoing(packet.transferId,snapshot);}pump();
    }
    private void pump(){
        Outgoing task=outgoing;if(task==null)return;
        while(task.pending.size()<AvatarPacket.WINDOW_CHUNKS&&task.sent<task.profile.byteLength){
            synchronized(gate){
            if(closed.get()||outgoing!=task||terminal(task))return;
            int end=(int)Math.min(task.profile.byteLength,task.sent+AvatarPacket.CHUNK_BYTES);
            byte[] bytes=Arrays.copyOfRange(task.png,(int)task.sent,end);
            if(!send(AvatarPacket.chunk(task.id,task.profile.revision,task.profile.avatarHash,task.sent,bytes)))return;
            task.sent=end;task.pending.addLast(task.sent);
            }
        }
    }
    private static String taskKey(String id,long revision,String hash){return id+":"+revision+":"+hash;}
    private boolean terminal(Task task){return terminalAdmissions.contains(taskKey(task.id,task.profile.revision,task.profile.avatarHash));}
    private boolean current(Incoming task){synchronized(gate){return !closed.get()&&incoming==task&&!terminal(task)&&announced.equals(task.profile);}}
    private void chunk(AvatarPacket packet,Runnable consumed)throws IOException {
        Incoming task=incoming;
        if(task==null||!task.matches(packet)){discardEnded(packet,true);return;}
        if(!current(task)){cancelIncoming(AvatarPacket.Code.SUPERSEDED,true);discardEnded(packet,true);return;}
        if(packet.offset!=task.offset||packet.data.length>task.profile.byteLength-task.offset)throw new IOException("Out of order or excessive avatar chunk");
        try{
            task.output.write(packet.data);task.digest.update(packet.data);task.offset+=packet.data.length;task.progress=System.nanoTime();
            // Free the admission slot before the peer can receive ACK and send its next chunk.
            consumed.run();
            send(AvatarPacket.ack(task.id,task.profile.revision,task.profile.avatarHash,task.offset));
            if(task.offset==task.profile.byteLength)complete(task);
        }catch(IOException storage){if(current(task))finishIncoming(AvatarPacket.Code.STORAGE_ERROR);}
    }
    private void complete(Incoming task)throws IOException {
        task.output.getChannel().force(true);task.output.close();
        if(!AttachmentInfo.hex(task.digest.digest()).equals(task.profile.avatarHash)){finishIncoming(AvatarPacket.Code.HASH_MISMATCH);return;}
        byte[] bytes;
        try{if(Files.size(task.path)!=task.profile.byteLength)throw new IOException("Avatar length mismatch");bytes=readBounded(task.path,task.profile.byteLength);}catch(IOException storage){finishIncoming(AvatarPacket.Code.STORAGE_ERROR);return;}
        if(!matchesImage(task.profile,bytes)){finishIncoming(AvatarPacket.Code.INVALID_IMAGE);return;}
        if(!current(task)){cancelIncoming(AvatarPacket.Code.SUPERSEDED,true);return;}
        try{if(adopt(task.profile,bytes,task)){finishIncoming(AvatarPacket.Code.OK);}}
        catch(IOException storage){if(current(task))finishIncoming(AvatarPacket.Code.STORAGE_ERROR);}
    }
    private static byte[] readBounded(Path path,int length)throws IOException {
        if(length<1||length>AvatarSpec.MAX_BYTES)throw new IOException("Invalid avatar file length");byte[] bytes=new byte[length];
        try(DataInputStream in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){in.readFully(bytes);if(in.read()!=-1)throw new IOException("Excess avatar bytes");}return bytes;
    }
    private static boolean matchesImage(AvatarProfile profile,byte[] bytes){
        try{AvatarSpec.PngInfo info=AvatarSpec.validatePng(bytes);return bytes.length==profile.byteLength&&info.width()==profile.width&&info.height()==profile.height;}
        catch(IOException invalid){return false;}
    }
    private boolean adopt(AvatarProfile profile,byte[] bytes,Incoming task)throws IOException {
        synchronized(gate){
            if(closed.get()||!announced.equals(profile)||task!=null&&(incoming!=task||terminal(task)))return false;
            long expected=remote.snapshot().profile().revision;AvatarSnapshot saved=remote.adopt(expected,profile,bytes);
            if(!closed.get()&&announced.equals(profile)&&(task==null||incoming==task))listener.onChanged(saved);return true;
        }
    }
    private void ack(AvatarPacket packet)throws IOException {
        Outgoing task=outgoing;if(task==null||!task.matches(packet)){discardEnded(packet,false);return;}
        if(packet.offset<=task.ack||!task.pending.contains(packet.offset))throw new IOException("Unexpected avatar acknowledgement");
        while(!task.pending.isEmpty()&&task.pending.peekFirst()<=packet.offset)task.pending.removeFirst();
        task.ack=packet.offset;task.progress=System.nanoTime();pump();
    }
    private void result(AvatarPacket packet)throws IOException {
        Outgoing task=outgoing;if(task==null||!task.matches(packet)){
            // A source can fail before sending any data for our requested task.
            if(incoming!=null&&incoming.matches(packet)&&packet.code!=AvatarPacket.Code.OK){Incoming receiving=incoming;remember(receiving,true,receiving.offset,0);cleanup(receiving);incoming=null;failure(packet.code);return;}
            discardEnded(packet,false);return;
        }
        if(packet.code==AvatarPacket.Code.OK&&(task.sent!=task.profile.byteLength||task.ack!=task.profile.byteLength))throw new IOException("Avatar success before complete acknowledgement");
        remember(task,false,task.ack,task.sent);outgoing=null;if(packet.code!=AvatarPacket.Code.OK)failure(packet.code);
    }
    private void cancel(AvatarPacket packet)throws IOException {
        if(incoming!=null&&incoming.matches(packet)){cancelIncoming(packet.code,false);return;}
        if(outgoing!=null&&outgoing.matches(packet)){cancelOutgoing(packet.code,false);return;}
        Ended done=ended.get(packet.transferId);if(done!=null&&done.matches(packet))return;
        throw new IOException("Unknown avatar cancellation");
    }
    private void discardEnded(AvatarPacket packet,boolean chunk)throws IOException {
        Ended done=ended.get(packet.transferId);
        if(done==null||!done.matches(packet))throw new IOException("Unknown avatar transfer");
        if(chunk){
            if(!done.receiving||done.remaining==0||packet.offset!=done.offset||packet.data.length>done.size-done.offset)throw new IOException("Excess canceled avatar window");
            done.remaining--;done.offset+=packet.data.length;
        }else if(packet.kind==AvatarPacket.Kind.ACK){if(done.receiving||packet.offset<=done.offset||packet.offset>done.sent)throw new IOException("Invalid canceled avatar acknowledgement");done.offset=packet.offset;}
    }
    private void remember(Task task,boolean receiving,long offset,long sent){
        ended.put(task.id,new Ended(task,receiving,offset,sent));trimEnded();
    }
    private void rememberReply(AvatarPacket request){ended.put(request.transferId,new Ended(request));trimEnded();}
    private void trimEnded(){while(ended.size()>16)ended.remove(ended.keySet().iterator().next());}
    private void finishIncoming(AvatarPacket.Code code){
        Incoming task=incoming;if(task==null)return;remember(task,true,task.offset,0);cleanup(task);incoming=null;
        send(AvatarPacket.result(task.id,task.profile.revision,task.profile.avatarHash,code));if(code!=AvatarPacket.Code.OK)failure(code);
    }
    private void cancelIncoming(AvatarPacket.Code code,boolean notify){
        Incoming task=incoming;if(task==null)return;remember(task,true,task.offset,0);cleanup(task);incoming=null;
        if(notify)send(AvatarPacket.cancel(task.id,task.profile.revision,task.profile.avatarHash,code));
    }
    private void cancelOutgoing(AvatarPacket.Code code,boolean notify){
        Outgoing task=outgoing;if(task==null)return;remember(task,false,task.ack,task.sent);outgoing=null;
        if(notify)send(AvatarPacket.cancel(task.id,task.profile.revision,task.profile.avatarHash,code));
    }
    private void expire(){
        long now=System.nanoTime();if(incoming!=null&&now-incoming.progress>=TIMEOUT_NANOS)cancelIncoming(AvatarPacket.Code.TIMEOUT,true);
        if(outgoing!=null&&now-outgoing.progress>=TIMEOUT_NANOS)cancelOutgoing(AvatarPacket.Code.TIMEOUT,true);
    }
    private boolean send(AvatarPacket packet){synchronized(gate){if(closed.get())return false;if(transport.send(packet))return true;close();return false;}}
    private void failure(AvatarPacket.Code code){synchronized(gate){if(!closed.get())listener.onFailure(code);}}
    private void protocolError(IOException error){synchronized(gate){if(closed.get())return;listener.onProtocolError(error);}close();}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    /** Startup cleanup is bounded and never deletes a different live session's private partial. */
    private void reclaimPartials()throws IOException {
        synchronized(PARTIAL_GATE){int count=0;try(DirectoryStream<Path> paths=Files.newDirectoryStream(remote.directory(),".avatar-sync-*.part")){
            for(Path path:paths){if(++count>4096)throw new IOException("Too many orphan avatar partials");if(ACTIVE_PARTIALS.contains(path))continue;
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))throw new IOException("Unsafe avatar partial");Files.deleteIfExists(path);}
        }}
    }
    private Path createPartial()throws IOException {synchronized(PARTIAL_GATE){Path path=Files.createTempFile(remote.directory(),".avatar-sync-",".part");ACTIVE_PARTIALS.add(path);return path;}}
    private static void deletePartial(Path path)throws IOException {synchronized(PARTIAL_GATE){try{Files.deleteIfExists(path);}finally{ACTIVE_PARTIALS.remove(path);}}}
    private static void cleanup(Incoming task){try{task.output.close();}catch(IOException ignored){}try{deletePartial(task.path);}catch(IOException deferred){} }
    /** Invalidate immediately; the owning worker performs file cleanup after it exits. */
    public void close(){
        if(!closed.compareAndSet(false,true))return;
        timer.shutdownNow();worker.shutdownNow();
    }
    private void cleanupOwned(){
        synchronized(gate){Incoming task=incoming;if(task!=null)cleanup(task);incoming=null;outgoing=null;ended.clear();terminalAdmissions.clear();}
    }
}

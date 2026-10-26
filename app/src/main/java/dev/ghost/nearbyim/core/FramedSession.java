package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;

public final class FramedSession {
    public interface Listener {
        default void onTextLimit(int bytes) {}
        default void onAttachment(Frame frame) { throw new IllegalStateException("Attachments unavailable"); }
        default void onTransfer(TransferPacket packet) { throw new IllegalStateException("Encrypted attachments unavailable"); }
        default void onFileRoute(FileRoutePacket packet) { throw new IllegalStateException("Automatic file routes unavailable"); }
        default void onFileLink(FileLinkPacket packet) { throw new IllegalStateException("FILE migration unavailable"); }
        default void onFailure(IOException error) {}
        default void onAvatar(AvatarPacket packet) { throw new IllegalStateException("Avatars unavailable"); }
        void onHello(Frame hello); void onReady(); void onText(Frame frame); void onAck(String id); void onClosed(UiText reason);
    }
    private final TransferDiagnostics metrics=new TransferDiagnostics();
    private final StreamConnection connection;
    private final Listener listener;
    private final AuthenticatedChannel channel;
    private final NoiseRecordChannel noise;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean(), notifiedReady = new AtomicBoolean();
    private final FairRecordWriter writer=new FairRecordWriter(error->close(UiText.of("sendInterrupted")));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "nearby-timeout"));
    private volatile boolean greeted, approved, remoteReady;
    private boolean fileLane;private volatile boolean fileLinkEnabled;
    private volatile TextCapability textCapability;
    private volatile OutputStream output;
    private volatile long lastReadNanos = System.nanoTime();
    private final Map<String, Integer> receiving = new HashMap<>();
    private final Set<String> awaitingReceipts = new HashSet<>();
    private final List<AvatarWrite> avatarWrites=new ArrayList<>(AvatarPacket.WINDOW_CHUNKS);
    private int unpersisted;

    public FramedSession(StreamConnection connection, String localId, String nickname, DeviceIdentity identity, Listener listener) {
        this.connection = Objects.requireNonNull(connection);
        this.listener = Objects.requireNonNull(listener);
        noise=null;
        try { channel = new AuthenticatedChannel(identity, new Frame(Frame.HELLO, localId, nickname, System.currentTimeMillis())); }
        catch (IOException error) { throw new IllegalArgumentException("Invalid local identity", error); }
    }
    private FramedSession(StreamConnection connection,String localId,String nickname,DeviceIdentity identity,byte[] staticKey,boolean initiator,Listener listener,boolean avatars,boolean automaticFiles)throws IOException {
        this.connection=Objects.requireNonNull(connection);this.listener=Objects.requireNonNull(listener);channel=null;
        noise=new NoiseRecordChannel(connection,initiator,identity,localId==null?null:localId.toLowerCase(Locale.ROOT),nickname,staticKey,null,null,avatars,automaticFiles);
    }
    /** Explicit NIM4 selection; this constructor never falls back to NIM3. */
    public static FramedSession secure(StreamConnection connection,String localId,String nickname,DeviceIdentity identity,byte[] staticKey,boolean initiator,Listener listener)throws IOException {
        return secure(connection,localId,nickname,identity,staticKey,initiator,listener,false);
    }
    public static FramedSession secure(StreamConnection connection,String localId,String nickname,DeviceIdentity identity,byte[] staticKey,boolean initiator,Listener listener,boolean avatars)throws IOException {
        return secure(connection,localId,nickname,identity,staticKey,initiator,listener,avatars,false);
    }
    public static FramedSession secure(StreamConnection connection,String localId,String nickname,DeviceIdentity identity,byte[] staticKey,boolean initiator,Listener listener,boolean avatars,boolean automaticFiles)throws IOException {
        return new FramedSession(connection,localId,nickname,identity,staticKey,initiator,listener,avatars,automaticFiles);
    }
    /** Dedicated FILE streams accept no chat text, receipts, or avatar profile traffic. */
    void restrictToFileLane(){if(started.get())throw new IllegalStateException("FILE lane already started");fileLane=true;}
    /** Call only after the parent-bound, legacy-safe capability exchange succeeded. */
    void enableFileLink(){fileLinkEnabled=true;}
    private static Thread daemon(Runnable runnable, String name) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }
    public void start() {
        if (!started.compareAndSet(false, true) || closed.get()) return;
        timer.schedule(() -> { if (!greeted) close(UiText.of("handshakeTimeout")); }, 15, TimeUnit.SECONDS);
        timer.schedule(() -> { if (!isReady()) close(UiText.of("consentTimeout")); }, 60, TimeUnit.SECONDS);
        if(noise==null)enqueue(() -> { output = connection.output(); channel.writeOffer(output); });
        daemon(this::readLoop, "nearby-reader").start();
    }
    private void readLoop() {
        try {
            InputStream input = connection.input();
            if(noise==null) {
                channel.readOffer(input);
                if (!enqueue(() -> channel.writeProof(output))) return;
                channel.readProof(input);
            }else {noise.establish();output=connection.output();if(!fileLane)textCapability=new TextCapability(noise.connectionGeneration(),time->enqueueFrame(new Frame(Frame.PONG,"","",time)),()->listener.onTextLimit(textLimit()));}
            greeted = true; listener.onHello(noise==null?channel.remoteHello():noise.remoteHello());
            while (!closed.get()) {
                Frame frame;
                if(noise==null)frame=channel.read(input);
                else {
                    ProtocolV4.Record record=ProtocolV4.decodeRecord(noise.read(),textCapability==null?TextLimits.LEGACY_BYTES:textCapability.incoming());lastReadNanos=System.nanoTime();
                    if(record.fileLink!=null){if(!automaticFilesSupported())throw new NoiseRecordChannel.AuthenticationException("FILE migration before approval");listener.onFileLink(record.fileLink);continue;}
                    if(record.fileRoute!=null){if(!automaticFilesSupported())throw new IOException("File routing before negotiated approval");listener.onFileRoute(record.fileRoute);continue;}
                    if(record.transfer!=null){if(!isReady())throw new NoiseRecordChannel.AuthenticationException("File before approval");listener.onTransfer(record.transfer);continue;}
                    if(record.avatar!=null){if(fileLane)throw new NoiseRecordChannel.AuthenticationException("Avatar on FILE lane");if(!avatarsSupported())throw new IOException("Avatar before negotiated approval");
                        listener.onAvatar(record.avatar);
                        if(record.avatar.kind==AvatarPacket.Kind.CANCEL||record.avatar.kind==AvatarPacket.Kind.RESULT&&record.avatar.code!=AvatarPacket.Code.OK)discardAvatarWrites(record.avatar);
                        continue;}
                    frame=record.frame;
                }
                lastReadNanos = System.nanoTime();
                switch (frame.type) {
                    case Frame.READY:
                        if (remoteReady) throw new IOException("Repeated approval");
                        remoteReady = true; maybeReady(); break;
                    case Frame.TEXT:
                        if(fileLane)throw new NoiseRecordChannel.AuthenticationException("Text on FILE lane");
                        if (!isReady()) throw new IOException("Text before approval");
                        synchronized (receiving) {
                            if (unpersisted >= 32) throw new IOException("Too many unpersisted messages");
                            receiving.put(frame.id, receiving.getOrDefault(frame.id, 0) + 1); unpersisted++;
                        }
                        listener.onText(frame); break;
                    case Frame.ACK:
                        if(fileLane)throw new NoiseRecordChannel.AuthenticationException("Receipt on FILE lane");
                        if (!isReady()) throw new IOException("Receipt before approval");
                        boolean known; synchronized (awaitingReceipts) { known = awaitingReceipts.remove(frame.id); }
                        if (known) listener.onAck(frame.id); break;
                    case Frame.BYE: close(UiText.of("peerEnded")); return;
                    case Frame.PING:
                        if (!isReady()) throw new IOException("Heartbeat before approval");
                        enqueueFrame(new Frame(Frame.PONG, "", "", System.currentTimeMillis())); break;
                    case Frame.PONG:
                        if (!isReady()) throw new IOException("Heartbeat before approval");
                        if(textCapability!=null)textCapability.receive(frame.timestamp);break;
                    default:
                        if(noise!=null&&frame.type==Frame.ATTACHMENT_V2&&isReady())listener.onTransfer(TransferCodec.read(new ByteArrayInputStream(frame.data)));
                        else if(noise==null&&frame.type>=Frame.FILE_OFFER && frame.type<=Frame.FILE_CANCEL && isReady() && channel.attachments())listener.onAttachment(frame);
                        else throw new IOException("Unexpected frame");
                }
            }
        } catch (UnsupportedProtocolException error) { close(UiText.of("attachmentUpgradeRequired")); }
        catch (IOException | RuntimeException error) {
            if(closed.get())return;
            IOException failure=error instanceof IOException?(IOException)error:new IOException(error);
            if(fileLane&&noise!=null&&noise.verified()&&!(failure instanceof NoiseRecordChannel.AuthenticationException))failure=new NoiseRecordChannel.AuthenticationException("Invalid authenticated FILE record",failure);
            listener.onFailure(failure);close(UiText.of("disconnected"));
        }
    }
    public void approve() {
        if (!greeted || approved || closed.get()) return;
        enqueue(() -> {
            if (approved) return;
            // Consent exists before the peer can observe READY. The single writer
            // still ensures any queued TEXT follows these READY bytes.
            approved = true;
            writeFrame(new Frame(Frame.READY, "", "", System.currentTimeMillis()));
            maybeReady();
        });
    }
    private void maybeReady() {
        if (isReady() && notifiedReady.compareAndSet(false, true)) {
            lastReadNanos = System.nanoTime();
            timer.scheduleWithFixedDelay(() -> {
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastReadNanos) >= 20) close(UiText.of("peerUnresponsive"));
                else enqueueFrame(new Frame(Frame.PING, "", "", System.currentTimeMillis()));
            }, 5, 5, TimeUnit.SECONDS);
            listener.onReady();
            if(textCapability!=null)textCapability.start();
        }
    }
    public boolean attachmentsSupported() { return isReady() && (noise!=null||channel.attachments()); }
    public boolean attachmentsV2(){return isReady()&&noise!=null;}
    public boolean avatarsSupported(){return isReady()&&noise!=null&&noise.avatarsSupported();}
    public boolean automaticFilesSupported(){return isReady()&&noise!=null&&noise.automaticFilesSupported();}
    public boolean sendFileRoute(FileRoutePacket packet){
        if(!automaticFilesSupported())return false;
        try{byte[] bytes=ProtocolV4.encodeFileRoute(packet);return enqueue(()->noise.write(bytes),bytes.length+256,false);}catch(IOException|IllegalArgumentException invalid){return false;}
    }
    public boolean sendFileLink(FileLinkPacket packet){return sendFileLink(packet,()->true);}
    boolean sendFileLink(FileLinkPacket packet,java.util.function.BooleanSupplier current){
        if(!fileLinkEnabled||!automaticFilesSupported())return false;
        try{byte[] bytes=ProtocolV4.encodeFileLink(packet);boolean fifo=packet.kind==FileLinkPacket.Kind.PROBE_START||packet.kind==FileLinkPacket.Kind.PROBE_DATA||packet.kind==FileLinkPacket.Kind.PROBE_END||packet.kind==FileLinkPacket.Kind.PAUSE;
            return enqueue(()->{if(current.getAsBoolean())noise.write(bytes);},bytes.length+256,fifo);
        }catch(IOException invalid){return false;}
    }
    public String connectionGeneration(){return noise==null?null:noise.connectionGeneration();}
    public SocketPath socketPath(){return isReady()&&!fileLane?connection.socketPath():null;}
    public int attachmentChunkSize() { return noise==null?channel.chunkSize():TransferLimits.DATA_BYTES; }
    public long attachmentSizeLimit() { return noise==null?channel.fileSize():TransferLimits.MAX_FILE_BYTES; }
    public boolean sendAttachment(Frame frame) {
        if(noise!=null||!attachmentsSupported() || frame.type<Frame.FILE_OFFER || frame.type>Frame.FILE_CANCEL)return false;
        return enqueueFrame(frame);
    }
    public boolean sendTransfer(TransferPacket packet){return sendTransfer(packet,()->{});}
    boolean sendTransfer(TransferPacket packet,Runnable written) {
        if(!attachmentsV2())return false;
        try {byte[] plaintext=ProtocolV4.encodeTransfer(packet);return enqueue(()->{noise.write(plaintext);written.run();},plaintext.length+256,true);}
        catch(IOException error){close(UiText.of("attachmentFailed"));return false;}
    }
    public boolean sendAvatar(AvatarPacket packet) {
        if(!avatarsSupported())return false;
        try{byte[] plaintext=ProtocolV4.encodeAvatar(packet);
            if(packet.kind==AvatarPacket.Kind.CHUNK){
                AvatarWrite write=new AvatarWrite(packet,plaintext);
                synchronized(avatarWrites){if(avatarWrites.size()>=AvatarPacket.WINDOW_CHUNKS)return false;avatarWrites.add(write);}
                if(enqueue(write,plaintext.length+256,true))return true;
                synchronized(avatarWrites){avatarWrites.remove(write);write.plaintext=null;}return false;
            }
            if(packet.kind==AvatarPacket.Kind.CANCEL||packet.kind==AvatarPacket.Kind.RESULT&&packet.code!=AvatarPacket.Code.OK)discardAvatarWrites(packet);
            return enqueue(()->noise.write(plaintext),plaintext.length+256,false);
        }
        catch(IOException|IllegalArgumentException invalid){return false;}
    }
    /** Release queued canceled PNG bytes while allowing an active encrypted record to finish. */
    private void discardAvatarWrites(AvatarPacket packet){
        synchronized(avatarWrites){Iterator<AvatarWrite> writes=avatarWrites.iterator();while(writes.hasNext()){
            AvatarWrite write=writes.next();if(write.revision==packet.revision&&write.id.equals(packet.transferId)&&write.hash.equals(packet.avatarHash)){write.plaintext=null;writes.remove();}
        }}
    }
    public boolean send(Frame frame) {
        if (fileLane || !isReady() || frame.type != Frame.TEXT) return false;
        try{Protocol.validate(frame,textLimit());}catch(IOException invalid){return false;}
        synchronized (awaitingReceipts) { if (awaitingReceipts.size() >= 32 || !awaitingReceipts.add(frame.id)) return false; }
        if (enqueueFrame(frame)) return true;
        synchronized (awaitingReceipts) { awaitingReceipts.remove(frame.id); } return false;
    }
    public void acknowledge(String id) {
        if (!isReady()) return;
        synchronized (receiving) {
            Integer count = receiving.get(id); if (count == null) return;
            if (count == 1) receiving.remove(id); else receiving.put(id, count - 1); unpersisted--;
        }
        enqueueFrame(new Frame(Frame.ACK, id, "", System.currentTimeMillis()));
    }
    private interface Write { void run() throws IOException; }
    private final class AvatarWrite implements Write {
        final String id,hash;final long revision;byte[] plaintext;
        AvatarWrite(AvatarPacket packet,byte[] plaintext){id=packet.transferId;hash=packet.avatarHash;revision=packet.revision;this.plaintext=plaintext;}
        public void run()throws IOException {
            byte[] record;synchronized(avatarWrites){avatarWrites.remove(this);record=plaintext;plaintext=null;}
            if(record!=null)noise.write(record);
        }
    }
    public int textLimit(){return textCapability==null?TextLimits.LEGACY_BYTES:textCapability.outgoing();}
    private void writeFrame(Frame frame)throws IOException {if(noise==null)channel.write(output,frame);else noise.write(ProtocolV4.encode(frame,textLimit()));}
    private boolean enqueueFrame(Frame frame) {
        if(noise!=null){
            try{byte[] bytes=ProtocolV4.encode(frame,textLimit());return enqueue(()->noise.write(bytes),bytes.length+256,frame.type>=Frame.FILE_OFFER);}
            catch(IOException invalid){return false;}
        }
        return enqueue(()->writeFrame(frame),frame.data.length+frame.body.length()*2+256,frame.type>=Frame.FILE_OFFER);
    }
    private boolean enqueue(Write operation) {
        return enqueue(operation,512,false);
    }
    private boolean enqueue(Write operation,int retainedBytes,boolean file) {
        if (closed.get()) return false;
        long enqueued=System.nanoTime();
        if(writer.submit(()->{metrics.record(TransferDiagnostics.Stage.WRITER_QUEUE,System.nanoTime()-enqueued,retainedBytes);if(!closed.get())operation.run();},retainedBytes,file))return true;
        close(UiText.of("messageQueueFull"));return false;
    }
    public void close(UiText reason) {
        if (!closed.compareAndSet(false, true)) return;
        try { connection.close(); } catch (IOException ignored) {}
        timer.shutdownNow(); writer.close();
        synchronized(avatarWrites){for(AvatarWrite write:avatarWrites)write.plaintext=null;avatarWrites.clear();}
        if(noise!=null)try{noise.close();}catch(IOException ignored){}
        synchronized(receiving){receiving.clear();unpersisted=0;}synchronized(awaitingReceipts){awaitingReceipts.clear();}
        listener.onClosed(reason);
    }
    /** Available only after proof verification, including during onHello. */
    public String remotePublicKey() { return noise==null?channel.remotePublicKey():noise.remotePublicKey(); }
    public boolean isReady() { return greeted && approved && remoteReady && !closed.get(); }
    public String diagnostics(){return "protocol="+(noise==null?"NIM3":"NIM4")+"\nend_to_end_encrypted="+endToEndEncrypted()+"\ntext_send_limit_bytes="+textLimit()+"\ntext_receive_limit_bytes="+(textCapability==null?TextLimits.LEGACY_BYTES:textCapability.incoming())+"\nwriter_pending_bytes="+writer.pendingBytes()+"\n"+metrics.snapshot()+(noise==null?"":noise.diagnostics());}
    public boolean endToEndEncrypted(){return isReady()&&noise!=null&&noise.verified();}
}

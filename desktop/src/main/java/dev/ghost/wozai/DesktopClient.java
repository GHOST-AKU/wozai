package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.storage.TrustPolicy;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** All session, consent and disk mutations run on one bounded model executor. */
public final class DesktopClient implements AutoCloseable {
    public record Listening(int port, List<String> endpoints) { }
    public record State(String phase, DesktopStore.Peer peer, Listening listening, List<DesktopStore.Peer> history, boolean bluetoothListening, String transport) { }
    public record Request(long token, DesktopStore.Peer peer, String publicKey) { }
    public interface Listener {
        void changed(State state);
        void request(Request request);
        void notice(UiText text);
    }
    private final DesktopStore store;
    private final DesktopIdentity.Identity identity;
    private final Listener listener;
    private final TrustPolicy policy = new TrustPolicy();
    private final ExecutorService model = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> daemon(r, "wozai-model"), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile Socket connecting;
    private volatile DesktopBluetooth.Connection connectingBluetooth;
    private volatile DesktopBluetooth.Server bluetoothServer;
    private String connectingPeer;
    private long generation;
    private Session current;
    private Listening listening;
    private String phase = "idle";
    private List<DesktopStore.Peer> lastHistory = List.of();
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    public DesktopClient(DesktopStore store, DesktopIdentity.Identity identity, Listener listener) throws IOException {
        this.store = store; this.identity = identity; this.listener = listener;
        store.nickname();
    }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            model.execute(() -> {
                if (closed) { result.completeExceptionally(new LocalizedIOException(UiText.of("disconnected"))); return; }
                try { result.complete(task.call()); }
                catch (Exception e) { result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }
    private void event(Runnable r) {
        try { model.execute(() -> { if (!closed) r.run(); }); }
        catch (RejectedExecutionException e) {
            // Stop the wire immediately if the disk/model cannot keep up.
            Session session = current; if (session != null) session.wire.close(UiText.of("busy"));
        }
    }
    public CompletableFuture<Void> refresh() { return submit(() -> { publish(); return null; }); }
    private void publish() {
        try { lastHistory = store.peers(); listener.changed(new State(phase, current == null ? null : current.peer, listening, lastHistory, bluetoothServer != null, current == null ? "" : current.transport)); }
        catch (IOException e) { failure(); }
    }
    private void failure() {
        disconnectNow();
        // The failed store must not be read again to tell the UI that the wire is closed.
        listener.changed(new State(phase, null, listening, lastHistory, bluetoothServer != null, ""));
        listener.notice(UiText.of("storageFailure"));
    }
    public CompletableFuture<Listening> listen() {
        return submit(() -> {
            stopServer();
            ServerSocket opened = new ServerSocket(0); server = opened;
            List<String> endpoints = new ArrayList<>();
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses()))
                    if (LocalEndpoint.isLocal(address) && !address.getHostAddress().contains("%")) endpoints.add(endpoint(address, opened.getLocalPort()));
            }
            listening = new Listening(opened.getLocalPort(), List.copyOf(endpoints));
            daemon(() -> {
                try {
                    while (server == opened && !closed) {
                        Socket socket = opened.accept();
                        if (!LocalEndpoint.isLocal(socket.getInetAddress())) { socket.close(); continue; }
                        socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                        try { model.execute(() -> {
                            if (closed || server != opened || current != null || phase.equals("connecting")) closeSocket(socket);
                            else attach(socket, true, null, "");
                        }); } catch (RejectedExecutionException e) { closeSocket(socket); }
                    }
                } catch (IOException e) {
                    event(() -> { if (server == opened) { stopServer(); publish(); listener.notice(UiText.of("listenFailed")); } });
                }
            }, "wozai-accept").start();
            publish(); return listening;
        });
    }
    public CompletableFuture<Void> stopListening() { return submit(() -> { stopServer(); publish(); return null; }); }
    private void stopServer() {
        ServerSocket previous = server; server = null; listening = null;
        if (previous != null) try { previous.close(); } catch (IOException ignored) { }
    }
    public CompletableFuture<Void> connect(String text, String expectedId) {
        final String canonicalId = expectedId == null ? null : DesktopStore.uuid(expectedId);
        if(text.startsWith("bluetooth:")) return connectBluetooth(text.substring(10),canonicalId);
        return submit(() -> {
            LocalEndpoint target = LocalEndpoint.parse(text);
            if (current != null || phase.equals("connecting")) throw new LocalizedIOException(UiText.of("busy"));
            long attempt = ++generation, trustVersion = policy.version(); phase = "connecting"; publish();
            Socket socket = new Socket(); connecting = socket; connectingPeer = canonicalId;
            daemon(() -> {
                try {
                    socket.connect(new InetSocketAddress(target.address, target.port), 8000);
                    socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                    event(() -> {
                        if (attempt != generation || closed) { closeSocket(socket); return; }
                        connecting = null; connectingPeer = null; attach(socket, false, canonicalId, text, trustVersion);
                    });
                } catch (IOException e) {
                    closeSocket(socket);
                    event(() -> { if (attempt == generation) { connecting = null; connectingPeer = null; phase = "idle"; publish(); listener.notice(UiText.of("connectFailed")); } });
                }
            }, "wozai-connect").start();
            return null;
        });
    }
    private final class Session implements FramedSession.Listener {
        final long token = ++generation;
        final boolean incoming;
        final String expectedId, target, transport;
        final long trustVersion;
        final FramedSession wire;
        DesktopStore.Peer peer;
        TrustPolicy.Authorization authorization;
        AttachmentTransfer transfers;
        Session(StreamConnection connection, boolean incoming, String expectedId, String target, long trustVersion) throws IOException {
            this.incoming = incoming; this.expectedId = expectedId; this.target = target; this.trustVersion = trustVersion; this.transport = connection.label().equals("Bluetooth") ? "bluetooth" : "lan";
            String nickname = store.nickname();
            wire = new FramedSession(connection, identity.id(), nickname, identity.signer(), this);
        }
        public void onHello(Frame hello) { event(() -> {
            if (current != this) return;
            if (policy.version() != trustVersion) { reject("canceled"); return; }
            // Normalize only after authentication; the signed offer keeps its original bytes.
            String peerId = DesktopStore.uuid(hello.id);
            if (peerId.equalsIgnoreCase(identity.id()) || expectedId != null && !expectedId.equals(peerId)) { reject("identityChanged"); return; }
            try {
                DesktopStore.Peer saved = store.peer(peerId);
                String pin = saved == null || saved.publicKey().isEmpty() ? null : saved.publicKey();
                authorization = policy.begin(peerId, wire.remotePublicKey(), pin, !incoming, true);
                peer = new DesktopStore.Peer(peerId, hello.body, pin == null ? "" : pin, target.isEmpty() && saved != null ? saved.endpoint() : target);
                if (authorization.decision == TrustPolicy.Decision.IDENTITY_CHANGED) { reject("identityChanged"); return; }
                phase = "consent"; publish();
                if (authorization.decision == TrustPolicy.Decision.APPROVE) wire.approve();
                else listener.request(new Request(token, peer, wire.remotePublicKey()));
            } catch (IOException | RuntimeException e) { failure(); }
        }); }
        public void onReady() { event(() -> {
            if (current != this) return;
            if (!policy.ready(authorization)) { reject("canceled"); return; }
            try {
                final String[] pin = {peer.publicKey()};
                policy.persist(authorization, () -> pin[0] = wire.remotePublicKey());
                peer = new DesktopStore.Peer(peer.id(), peer.name(), pin[0], peer.endpoint());
                store.peer(peer);
                if(wire.attachmentsSupported())transfers=new AttachmentTransfer(store.attachmentDirectory(peer.id()),new AttachmentTransfer.Wire(){
                    public boolean send(Frame frame){return wire.sendAttachment(frame);}
                    public void abort(){wire.close(UiText.of("attachmentFailed"));}
                },record->{store.attachment(peer.id(),record);event(()->publish());},wire.attachmentChunkSize(),wire.attachmentSizeLimit(),true);
                phase = "ready"; publish();
            } catch (IOException e) { failure(); }
        }); }
        public void onAttachment(Frame frame){event(()->{if(current==this&&transfers!=null)transfers.receive(frame,true);});}
        public void onText(Frame frame) { event(() -> {
            if (current != this || peer == null || !wire.isReady()) return;
            try {
                store.receive(peer.id(), frame.id, frame.body, frame.timestamp);
                wire.acknowledge(frame.id); publish();
            } catch (IOException e) { failure(); }
        }); }
        public void onAck(String id) { event(() -> {
            if (current != this || peer == null) return;
            try { store.acknowledge(peer.id(), id); publish(); }
            catch (IOException e) { failure(); }
        }); }
        public void onClosed(UiText reason) { event(() -> {
            if (current != this) return;
            disconnectNow(); publish(); listener.notice(reason.key.isEmpty() ? UiText.of("disconnected") : reason);
        }); }
        void reject(String key) { disconnectNow(); publish(); listener.notice(UiText.of(key)); }
    }
    private void attach(Socket socket, boolean incoming, String expectedId, String target) {
        attach(socket, incoming, expectedId, target, policy.version());
    }
    private void attach(Socket socket, boolean incoming, String expectedId, String target, long trustVersion) {
        attach(wrap(socket),incoming,expectedId,target,trustVersion);
    }
    private void attach(StreamConnection connection,boolean incoming,String expectedId,String target,long trustVersion) {
        try { current = new Session(connection, incoming, expectedId, target, trustVersion); phase = "handshake"; current.wire.start(); publish(); }
        catch (IOException | RuntimeException e) { closeConnection(connection); phase = "idle"; publish(); listener.notice(UiText.of("connectFailed")); }
    }
    public CompletableFuture<Void> approve(Request request, boolean remember) { return submit(() -> {
        if (current != null && current.token == request.token() && policy.approve(current.authorization, remember)) current.wire.approve();
        return null;
    }); }
    public CompletableFuture<Void> reject(Request request) { return submit(() -> {
        if (current != null && current.token == request.token()) { disconnectNow(); publish(); }
        return null;
    }); }
    public CompletableFuture<Boolean> send(String text) { return submit(() -> {
        Session session = current;
        if (session == null || !phase.equals("ready") || !session.wire.isReady()) return false;
        Frame frame = new Frame(Frame.TEXT, UUID.randomUUID().toString(), text, System.currentTimeMillis());
        Protocol.write(OutputStream.nullOutputStream(), frame); // validate before saving or clearing draft
        try {
            store.save(session.peer.id(), new DesktopStore.Message(frame.id, text, frame.timestamp, true, "pending"));
            boolean sent = session.wire.send(frame);
            if (!sent) store.status(session.peer.id(), frame.id, "unknown");
            publish(); return sent;
        } catch (IOException e) { failure(); throw e; }
    }); }
    public CompletableFuture<String> sendAttachment(String peerId,java.nio.file.Path file){return submit(()->{
        Session session=current;
        if(session==null||session.peer==null||!session.peer.id().equals(peerId)||session.transfers==null||!session.wire.attachmentsSupported())throw new LocalizedIOException(UiText.of("notConnected"));
        if(!java.nio.file.Files.isRegularFile(file))throw new LocalizedIOException(UiText.of("attachmentFailed"));
        String name=file.getFileName().toString();String mime=java.nio.file.Files.probeContentType(file);if(mime==null||!mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}"))mime="application/octet-stream";
        return session.transfers.offer(()->java.nio.file.Files.newInputStream(file),name,mime);
    }).thenCompose(future->future);}
    public CompletableFuture<Void> attachmentAction(String peerId,String id,boolean outgoing,String action){return submit(()->{
        Session session=current;if(session==null||session.peer==null||!session.peer.id().equals(peerId)||session.transfers==null)throw new LocalizedIOException(UiText.of("notConnected"));
        if(action.equals("accept"))return session.transfers.accept(id);if(action.equals("reject"))return session.transfers.reject(id);return session.transfers.cancel(id,outgoing);
    }).thenCompose(future->future);}
    public CompletableFuture<java.nio.file.Path> attachmentPath(String peerId,AttachmentInfo info){return submit(()->{
        java.nio.file.Path file=store.attachmentFile(peerId,info);if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new LocalizedIOException(UiText.of("attachmentUnavailable"));return file;
    });}
    public CompletableFuture<java.nio.file.Path> attachmentPath(String peerId,AttachmentRecord record){return submit(()->{
        java.nio.file.Path file=store.attachmentFile(peerId,record.info,record.outgoing);if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new LocalizedIOException(UiText.of("attachmentUnavailable"));return file;
    });}
    public CompletableFuture<List<DesktopStore.Message>> messages(String id) { return submit(() -> store.messages(id)); }
    public CompletableFuture<String> draft(String id) { return submit(() -> store.draft(id)); }
    public CompletableFuture<Void> draft(String id, String text) { return submit(() -> { store.draft(id, text); return null; }); }
    public CompletableFuture<Void> setting(String key, String value) { return submit(() -> { store.setSetting(key, value); return null; }); }
    public CompletableFuture<Void> revoke(String peerId) { final String id = DesktopStore.uuid(peerId); return submit(() -> {
        policy.revoke(id); store.revoke(id);
        if (id.equals(connectingPeer) || current != null && (id.equals(current.expectedId) || current.peer != null && current.peer.id().equals(id))) disconnectNow();
        publish(); return null;
    }); }
    public CompletableFuture<Void> clear(String id) { return submit(() -> { if(current!=null&&current.peer!=null&&current.peer.id().equals(id)&&current.transfers!=null)current.transfers.cancelAll().get(5,TimeUnit.SECONDS);store.clear(id); publish(); return null; }); }
    public CompletableFuture<Void> disconnect() { return submit(() -> { disconnectNow(); publish(); return null; }); }
    private void disconnectNow() {
        ++generation; closeSocket(connecting); connecting = null; closeConnection(connectingBluetooth); connectingBluetooth=null; connectingPeer = null;
        Session previous = current; current = null; phase = "idle";
        if (previous != null) {
            if(previous.transfers!=null)try{previous.transfers.shutdown().get(5,TimeUnit.SECONDS);}catch(Exception e){listener.notice(UiText.of("attachmentFailed"));}
            policy.cancel(previous.authorization); previous.wire.close(UiText.of("disconnected"));
            if (previous.peer != null) try { store.unknown(previous.peer.id()); }
            catch (IOException e) { listener.notice(UiText.of("storageFailure")); }
        }
    }
    public void close() {
        if (closed) return;
        try { submit(() -> { stopServer(); stopBluetoothServer(); disconnectNow(); closed = true; return null; }).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { closed = true; stopServer(); stopBluetoothServer(); closeSocket(connecting); closeConnection(connectingBluetooth); Session s = current; if (s != null) s.wire.close(UiText.of("disconnected")); }
        finally { model.shutdownNow(); }
    }
    public CompletableFuture<Map<String,DesktopStore.Message>> summaries() { return submit(() -> {
        Map<String,DesktopStore.Message> values=new HashMap<>(); for(var peer:store.peers()) { var messages=store.messages(peer.id()); if(!messages.isEmpty())values.put(peer.id(),messages.get(messages.size()-1)); } return values;
    }); }
    public CompletableFuture<Void> listenBluetooth() { return submit(() -> {
        stopBluetoothServer(); DesktopBluetooth.Server opened=DesktopBluetooth.listen(); bluetoothServer=opened;
        daemon(() -> {
            try { while(bluetoothServer==opened && !closed) {
                DesktopBluetooth.Connection connection=opened.accept();
                try { model.execute(() -> { if(closed||bluetoothServer!=opened||current!=null||phase.equals("connecting"))closeConnection(connection); else attach(connection,true,null,connection.routeKey(),policy.version()); }); }
                catch(RejectedExecutionException e) { closeConnection(connection); }
            } } catch(IOException e) { event(() -> { if(bluetoothServer==opened) { stopBluetoothServer(); publish(); listener.notice(UiText.of("bluetoothFailed")); } }); }
        },"wozai-bluetooth-accept").start(); publish(); return null;
    }); }
    public CompletableFuture<Void> stopBluetoothListening() { return submit(() -> { stopBluetoothServer(); publish(); return null; }); }
    private void stopBluetoothServer() { var previous=bluetoothServer; bluetoothServer=null; if(previous!=null)try { previous.close(); } catch(IOException ignored) { } }
    private CompletableFuture<Void> connectBluetooth(String address,String expectedId) { return submit(() -> {
        String normalized=DesktopBluetooth.normalizeAddress(address);
        if(current!=null||phase.equals("connecting"))throw new LocalizedIOException(UiText.of("busy"));
        DesktopBluetooth.Connection connection=DesktopBluetooth.openConnection(normalized);
        long attempt=++generation, trustVersion=policy.version(); connectingBluetooth=connection; connectingPeer=expectedId; phase="connecting"; publish();
        daemon(() -> {
            try { connection.connect(30000); event(() -> { if(attempt!=generation||closed) { closeConnection(connection); return; } connectingBluetooth=null; connectingPeer=null; attach(connection,false,expectedId,connection.routeKey(),trustVersion); }); }
            catch(IOException e) { closeConnection(connection); event(() -> { if(attempt==generation) { connectingBluetooth=null; connectingPeer=null; phase="idle"; publish(); listener.notice(UiText.of("bluetoothFailed")); } }); }
        },"wozai-bluetooth-connect").start(); return null;
    }); }
    // All byte transports enter the same signed identity, consent and receipt pipeline.
    CompletableFuture<Void> acceptConnection(StreamConnection connection,String route) { return submit(() -> {
        if(current!=null||phase.equals("connecting"))closeConnection(connection); else attach(connection,true,null,route,policy.version()); return null;
    }); }
    private static void closeConnection(StreamConnection connection) { if(connection!=null)try { connection.close(); } catch(IOException ignored) { } }
    public static String endpoint(InetAddress address, int port) {
        String ip = address.getHostAddress(); return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port;
    }
    private static void closeSocket(Socket s) { if (s != null) try { s.close(); } catch (IOException ignored) { } }
    private static StreamConnection wrap(Socket socket) { return new StreamConnection() {
        public InputStream input() throws IOException { return socket.getInputStream(); }
        public OutputStream output() throws IOException { return socket.getOutputStream(); }
        public String label() { return "LAN"; }
        public void close() throws IOException { socket.close(); }
    }; }
}

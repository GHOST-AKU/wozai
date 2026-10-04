package dev.ghost.nearbyim;

import android.content.*;
import android.os.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.nio.file.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.storage.TrustPolicy;
import dev.ghost.nearbyim.i18n.*;
import dev.ghost.nearbyim.transport.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

/** Mutable UI state stays on main; all database work runs on one ordered executor. */
public final class ChatController implements TransportListener {
    public interface Hooks { void onChanged(); }
    public final String localId;
    public String nickname;
    public UiText lanInfo = UiText.of("notStarted"), bluetoothInfo = UiText.of("notStarted"), status = UiText.of("initialStatus"), error = UiText.EMPTY;
    public boolean lanRunning, bluetoothRunning, lanListening, bluetoothListening, lanSearching, bluetoothSearching, connecting, connected;
    public int sessionMode;
    public String connectedPeerId, selectedId, selectedName, approvalId, approvalName;
    public String savedMessageId, savedMessagePeer, savedMessageBody;
    public boolean savingMessage;
    public final LinkedHashMap<String, Peer> peers = new LinkedHashMap<>();
    public List<ChatStore.Message> messages = new ArrayList<>();
    public List<ChatStore.Conversation> conversations = new ArrayList<>();
    public List<ChatStore.TrustedDevice> trustedDevices = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final ChatStore store;
    private final LanTransport lan;
    private final BluetoothTransport bluetooth;
    private final DeviceIdentity identity;
    private final TrustPolicy trust = new TrustPolicy();
    private final Hooks hooks;
    private Runnable observer, reconnectTimeout;
    private FramedSession active;
    private AttachmentTransfer transfers;
    private long attachmentToken;
    private CompletableFuture<Void> attachmentsStopped=CompletableFuture.completedFuture(null);
    private final ContentResolver resolver;
    private final ThreadPoolExecutor fileSelection=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"attachment-document");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private Frame remoteHello;
    private TrustPolicy.Authorization authorization;
    private int connectingMode;
    private String expectedPeerId, outgoingBluetoothAddress, lanReconnectId;
    private boolean rememberOutgoing, destroyed;

    public ChatController(Context context, Hooks hooks) {
        this.hooks = hooks; resolver=context.getApplicationContext().getContentResolver();preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String id = preferences.getString("id", null);
        boolean existingIdentity = id != null;
        if (id == null) { id = UUID.randomUUID().toString(); preferences.edit().putString("id", id).apply(); }
        localId = id; nickname = preferences.getString("nickname", null);
        if (nickname == null) { nickname = AndroidText.initialNickname(context, existingIdentity); preferences.edit().putString("nickname", nickname).apply(); }
        DeviceIdentity loaded = null;
        try { loaded = AndroidIdentity.load(); }
        catch (GeneralSecurityException | RuntimeException e) { error = UiText.of("identityUnavailableRestart"); }
        identity = loaded;
        store = new ChatStore(context); lan = new LanTransport(context, this); bluetooth = new BluetoothTransport(context, this);
        db(store::recoverPending, null); refresh();
    }
    public void observe(Runnable observer) { this.observer = observer; if (observer != null) observer.run(); }
    private void changed() { if (!destroyed) { hooks.onChanged(); if (observer != null) observer.run(); } }
    public boolean needsForeground() { return lanRunning || bluetoothRunning || connecting || active != null; }
    public boolean hasSession() { return active != null; }
    public boolean bluetoothAvailable() { return bluetooth.available(); }
    public boolean bluetoothEnabled() { return bluetooth.enabled(); }
    public void setNickname(String name) {
        String clean = name.trim();
        try { Protocol.write(new ByteArrayOutputStream(), new Frame(Frame.HELLO, localId, clean, 1)); }
        catch (IOException e) { fail(UiText.of("invalidNickname")); return; }
        nickname = clean; preferences.edit().putString("nickname", clean).apply(); changed();
    }
    public void start(int mode) {
        if (identity == null) { fail(UiText.of("identityUnavailable")); return; }
        error = UiText.EMPTY;
        if (mode == Peer.LAN) { if (lanRunning) return; lanRunning = true; lanListening = false; lanInfo = UiText.of("starting"); lan.start(localId, nickname); }
        else if (mode == Peer.BLUETOOTH) { if (bluetoothRunning) return; bluetoothRunning = true; bluetoothListening = false; bluetoothInfo = UiText.of("starting"); bluetooth.start(); }
        else { fail(UiText.of("invalidTransport")); return; }
        changed();
    }
    public void stop(int mode) {
        if (active != null && sessionMode == mode) closeActive(UiText.of("connectionEnded"));
        if (connecting && connectingMode == mode) cancelOutgoing();
        if (mode == Peer.LAN) { lan.stop(); lanRunning = false; lanListening = false; lanSearching = false; lanInfo = UiText.of("notStarted"); }
        else { bluetooth.stop(); bluetoothRunning = false; bluetoothListening = false; bluetoothSearching = false; bluetoothInfo = UiText.of("notStarted"); }
        peers.entrySet().removeIf(entry -> entry.getValue().mode == mode); changed();
    }
    public void stopAll() { stop(Peer.LAN); stop(Peer.BLUETOOTH); }
    public void search(int mode) {
        if (!running(mode)) { fail(UiText.of("startReceptionFirst")); return; }
        if (connecting) { fail(UiText.of("pleaseWaitConnecting")); return; }
        error = UiText.EMPTY; peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
        if (mode == Peer.LAN) lan.discover(); else bluetooth.scan(); changed();
    }
    public void stopSearch(int mode) {
        if (mode == Peer.LAN) {
            if (lanReconnectId != null) { cancelOutgoing(); status = UiText.of("connectionAttemptStopped"); }
            lan.stopSearch();
        } else bluetooth.stopSearch();
        changed();
    }
    private boolean running(int mode) { return mode == Peer.LAN ? lanRunning : mode == Peer.BLUETOOTH && bluetoothRunning; }
    private ChatStore.TrustedDevice trustedDevice(String peerId) {
        for (ChatStore.TrustedDevice device : trustedDevices) if (Objects.equals(device.id, peerId)) return device;
        return null;
    }
    public boolean isTrusted(String peerId) { return peerId != null && trustedDevice(peerId) != null; }
    public int preferredMode(String peerId) { ChatStore.TrustedDevice device = trustedDevice(peerId); return device == null ? Peer.LAN : device.mode; }
    public boolean isTrustedPeer(Peer peer) {
        if (peer.peerId != null) return isTrusted(peer.peerId);
        return trustedIdAtAddress(peer.bluetoothAddress) != null;
    }
    private String trustedIdAtAddress(String address) {
        if (address == null) return null;
        for (ChatStore.TrustedDevice device : trustedDevices) if (address.equals(device.bluetoothAddress)) return device.id;
        return null;
    }
    public void connect(Peer peer) { connect(peer, true); }
    public void connect(Peer peer, boolean remember) {
        String expected = peer.peerId == null ? trustedIdAtAddress(peer.bluetoothAddress) : peer.peerId;
        if (!beginConnect(peer.mode, expected, remember, peer.bluetoothAddress)) return;
        if (peer.mode == Peer.LAN) lan.connect(peer.host, peer.port); else bluetooth.connect(peer.bluetoothAddress);
    }
    public void connectAddress(String address) { connectAddress(address, true); }
    public void connectAddress(String address, boolean remember) {
        try { LocalEndpoint endpoint = LocalEndpoint.parse(address); if (beginConnect(Peer.LAN, null, remember, null)) lan.connect(endpoint.address, endpoint.port); }
        catch (LocalizedIllegalArgumentException e) { fail(e.text); }
    }
    public void reconnect(String peerId) {
        if (connected && Objects.equals(peerId, connectedPeerId)) return;
        ChatStore.TrustedDevice device = trustedDevice(peerId);
        if (device == null) { fail(UiText.of("notRemembered")); return; }
        if (device.mode == Peer.BLUETOOTH && device.bluetoothAddress == null) { fail(UiText.of("noBluetoothAddress")); return; }
        if (!beginConnect(device.mode, device.id, true, device.bluetoothAddress)) return;
        if (device.mode == Peer.BLUETOOTH) { bluetooth.connect(device.bluetoothAddress); return; }
        // Resolve a fresh advertised UUID. Never use a persisted IP as identity.
        lanReconnectId = device.id; status = UiText.of("findingRemembered");
        peers.entrySet().removeIf(entry -> entry.getValue().mode == Peer.LAN);
        reconnectTimeout = () -> {
            if (lanReconnectId == null) return;
            cancelOutgoing(); lan.stopSearch(); status = UiText.of("connectionUnavailable");
            fail(UiText.of("rememberedPeerNotFound"));
        };
        main.postDelayed(reconnectTimeout, 12000); lan.discover(); changed();
    }
    private boolean beginConnect(int mode, String expected, boolean remember, String bluetoothAddress) {
        if (active != null || connecting) { fail(UiText.of("busy")); return false; }
        if (identity == null) { fail(UiText.of("identityUnavailable")); return false; }
        if (!running(mode)) { fail(UiText.of("startModeReceptionFirst")); return false; }
        expectedPeerId = expected; rememberOutgoing = remember; outgoingBluetoothAddress = bluetoothAddress;
        connecting = true; connectingMode = mode; sessionMode = mode; error = UiText.EMPTY; status = UiText.of("connecting"); changed(); return true;
    }
    private void clearReconnectWait() {
        lanReconnectId = null;
        if (reconnectTimeout != null) { main.removeCallbacks(reconnectTimeout); reconnectTimeout = null; }
    }
    private void cancelOutgoing() {
        clearReconnectWait(); lan.cancelConnect(); bluetooth.cancelConnect();
        connecting = false; expectedPeerId = null; outgoingBluetoothAddress = null; rememberOutgoing = false;
    }
    public void revokeTrust(String peerId) {
        if (peerId == null) return;
        trust.revoke(peerId);
        trustedDevices = new ArrayList<>(trustedDevices);
        trustedDevices.removeIf(device -> device.id.equals(peerId));
        // Keep an established current chat, but cancel authorization still in flight.
        if (remoteHello != null && peerId.equals(remoteHello.id)) {
            trust.cancel(authorization);
            if (!connected) closeActive(UiText.of("trustRevokedReconnect"));
        }
        if (connecting && peerId.equals(expectedPeerId)) {
            if (active != null) closeActive(UiText.of("trustRevokedCanceled"));
            else { cancelOutgoing(); status = UiText.of("trustRevokedCanceled"); }
        }
        db(() -> store.revokeTrust(peerId), this::refresh); changed();
    }
    public void onPeer(Peer peer) {
        if (destroyed) return;
        peers.put(peer.key, peer);
        if (connecting && lanReconnectId != null && lanReconnectId.equals(peer.peerId) && peer.mode == Peer.LAN) {
            clearReconnectWait(); lan.stopSearch(); status = UiText.of("connecting"); lan.connect(peer.host, peer.port);
        }
        changed();
    }
    public void onLost(String key) { peers.remove(key); changed(); }
    public void onSearching(int mode, boolean searching) { if (mode == Peer.LAN) lanSearching = searching; else bluetoothSearching = searching; changed(); }
    public void onSearchStopFailed(int mode, UiText message) { fail(message); }
    public void onListening(int mode, UiText detail) { if (mode == Peer.LAN) { lanInfo = detail; lanListening = true; } else { bluetoothInfo = detail; bluetoothListening = true; } changed(); }
    public void onError(int mode, UiText message, boolean fatal) {
        if (lanReconnectId != null && mode == Peer.LAN) { cancelOutgoing(); status = UiText.of("connectionUnavailable"); }
        if (fatal) {
            if (connecting && connectingMode == mode) cancelOutgoing();
            if (mode == Peer.LAN) { lanRunning = false; lanListening = false; lanSearching = false; lanInfo = UiText.of("notStarted"); } else { bluetoothRunning = false; bluetoothListening = false; bluetoothSearching = false; bluetoothInfo = UiText.of("notStarted"); }
            peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
            if (active != null && sessionMode == mode) closeActive(message);
        }
        fail(message);
    }
    public void onConnectFailed(int mode, UiText message) {
        if (!connecting || connectingMode != mode || active != null) return;
        cancelOutgoing(); status = UiText.of("connectionUnavailable"); fail(message);
    }
    public void onConnection(int mode, StreamConnection connection, boolean incoming) { onConnection(mode, connection, incoming, null); }
    public void onConnection(int mode, StreamConnection connection, boolean incoming, String bluetoothAddress) {
        if (destroyed || identity == null || !running(mode) || active != null || (incoming && connecting)
                || (!incoming && (!connecting || connectingMode != mode))) { close(connection); return; }
        final String expected = incoming ? null : expectedPeerId;
        final long attemptRevision = expected == null ? -1 : trust.peerRevision(expected);
        final boolean remember = !incoming && rememberOutgoing;
        final String socketAddress = bluetoothAddress != null ? bluetoothAddress : incoming ? null : outgoingBluetoothAddress;
        clearReconnectWait(); connecting = true; connectingMode = mode; connected = false; connectedPeerId = null;
        sessionMode = mode; remoteHello = null; authorization = null;
        status = UiText.of("handshake"); error = UiText.EMPTY;
        final FramedSession[] reference = new FramedSession[1];
        reference[0] = new FramedSession(connection, localId, nickname, identity, new FramedSession.Listener() {
            public void onHello(Frame hello) { main.post(() -> {
                if (active != reference[0]) return;
                String publicKey = reference[0].remotePublicKey();
                if (publicKey == null || (expected != null && (!expected.equals(hello.id) || attemptRevision != trust.peerRevision(expected)))) {
                    closeActive(UiText.of("identityMismatch")); fail(UiText.of("identityVerificationFailed")); return;
                }
                remoteHello = hello;
                final long revision = trust.peerRevision(hello.id);
                final ChatStore.TrustedDevice[] pin = new ChatStore.TrustedDevice[1];
                db(() -> pin[0] = store.trusted(hello.id), () -> {
                    if (active != reference[0] || revision != trust.peerRevision(hello.id)) return;
                    authorization = trust.begin(hello.id, publicKey, pin[0] == null ? null : pin[0].publicKey, !incoming, remember);
                    if (authorization.decision == TrustPolicy.Decision.IDENTITY_CHANGED) {
                        closeActive(UiText.of("identityChangedRejected"));
                        fail(UiText.of("androidIdentityChanged"));
                    } else if (authorization.decision == TrustPolicy.Decision.APPROVE) {
                        status = incoming ? UiText.of("completingConnection") : UiText.of("waitingPeerConsent"); reference[0].approve(); changed();
                    } else {
                        approvalId = UUID.randomUUID().toString(); approvalName = hello.body; status = UiText.of("consent"); changed();
                    }
                });
            }); }
            public void onReady() { main.post(() -> {
                if (active != reference[0] || remoteHello == null || !reference[0].isReady() || !trust.ready(authorization)) return;
                connected = true; connecting = false; connectedPeerId = remoteHello.id;
                approvalId = null; approvalName = null; expectedPeerId = null; outgoingBluetoothAddress = null;
                Frame hello = remoteHello; TrustPolicy.Authorization grant = authorization;
                FramedSession wire=reference[0];
                if(wire.attachmentsSupported())try{transfers=new AttachmentTransfer(store.attachmentDirectory(hello.id),new AttachmentTransfer.Wire(){
                    public boolean send(Frame frame){return wire.sendAttachment(frame);}
                    public void abort(){wire.close(UiText.of("attachmentFailed"));}
                },record->{
                    try{storage.submit(()->store.attachment(hello.id,hello.body,record)).get(10,TimeUnit.SECONDS);}
                    catch(Exception e){throw new IOException("Attachment metadata save failed",e);}
                    main.post(()->{if(!destroyed)refresh();});
                },wire.attachmentChunkSize(),wire.attachmentSizeLimit(),true);}
                catch(IOException e){closeActive(UiText.of("attachmentFailed"));return;}
                if (!incoming) {
                    if (!Objects.equals(selectedId, hello.id)) messages = new ArrayList<>();
                    selectedId = hello.id; selectedName = hello.body;
                }
                status = UiText.of("connectedVia", UiText.of(mode == Peer.BLUETOOTH ? "bluetooth" : "lan")); changed();
                db(() -> {
                    trust.persist(grant, () -> store.remember(hello.id, hello.body, grant.publicKey, mode, socketAddress));
                    store.touch(hello.id, hello.body);
                }, ChatController.this::refresh);
            }); }
            public void onAttachment(Frame frame){main.post(()->{if(active==reference[0]&&transfers!=null)transfers.receive(frame,true);});}
            public void onText(Frame frame) { main.post(() -> {
                if (active != reference[0] || remoteHello == null || !reference[0].isReady()) return;
                String id = remoteHello.id, name = remoteHello.body;
                db(() -> store.save(id, name, frame, false), () -> { reference[0].acknowledge(frame.id); refresh(); });
            }); }
            public void onAck(String id) { main.post(() -> {
                if (active != reference[0] || remoteHello == null) return;
                String peerId = remoteHello.id; db(() -> store.delivered(peerId, id), ChatController.this::refresh);
            }); }
            public void onClosed(UiText reason) { main.post(() -> releaseSession(reference[0], reason)); }
        });
        attachmentToken++;active = reference[0]; active.start(); changed();
    }
    public void approve(String token) { approve(token, true); }
    public void approve(String token, boolean remember) {
        if (active == null || approvalId == null || !Objects.equals(token, approvalId) || remoteHello == null) return;
        if (!trust.approve(authorization, remember)) return;
        approvalId = null; approvalName = null; status = UiText.of("waitingBothReady");
        active.approve(); changed();
    }
    public void reject(String token) { if (active != null && approvalId != null && Objects.equals(token, approvalId)) closeActive(UiText.of("connectionRejected")); }
    private void releaseSession(FramedSession session, UiText reason) {
        if (active != session) return;
        String peerId = remoteHello == null ? null : remoteHello.id;
        if(transfers!=null){attachmentsStopped=CompletableFuture.allOf(attachmentsStopped,transfers.shutdown());transfers=null;}attachmentToken++;
        trust.cancel(authorization); authorization = null; active = null; remoteHello = null;
        connected = false; connectedPeerId = null; approvalId = null; approvalName = null;
        cancelOutgoing(); status = reason;
        if (peerId != null) db(() -> store.uncertain(peerId), this::refresh);
        changed();
    }
    private void closeActive(UiText reason) {
        FramedSession session = active;
        if (session != null) { releaseSession(session, reason); session.close(reason); }
    }
    private static void close(StreamConnection connection) { try { connection.close(); } catch (IOException ignored) {} }
    public void disconnect() { closeActive(UiText.of("connectionEnded")); cancelOutgoing(); status = UiText.of("connectionEnded"); changed(); }
    public boolean canSend() { return !savingMessage && connected && active != null && active.isReady() && remoteHello != null && Objects.equals(selectedId, connectedPeerId); }
    public long attachmentSessionToken(){return attachmentToken;}
    public boolean canSendAttachment(){return canSend()&&transfers!=null&&active.attachmentsSupported();}
    public void sendAttachment(String peer,long token,Uri uri){
        if(!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}
        try{fileSelection.execute(()->{
            try{
                String name=null;
                try(Cursor cursor=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
                if(name==null||name.trim().isEmpty())name="attachment";name=name.replaceAll("[\\p{Cntrl}]","_");
                String mime=resolver.getType(uri);if(mime==null||!mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}"))mime="application/octet-stream";
                String fileName=name,contentType=mime;
                main.post(()->{
                    if(destroyed)return;if(!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}
                    transfers.offer(()->{InputStream input=resolver.openInputStream(uri);if(input==null)throw new IOException("Document unavailable");return input;},fileName,contentType).whenComplete((id,e)->{if(e!=null)main.post(()->fail(UiText.of("attachmentFailed")));});
                });
            }catch(Exception e){main.post(()->{if(!destroyed)fail(UiText.of("attachmentFailed"));});}
        });}catch(RejectedExecutionException e){fail(UiText.of("attachmentFailed"));}
    }
    public void attachmentAction(String peer,String id,boolean outgoing,String action){
        if(transfers==null||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}
        CompletableFuture<Void> future=action.equals("accept")?transfers.accept(id):action.equals("reject")?transfers.reject(id):transfers.cancel(id,outgoing);
        future.whenComplete((v,e)->{if(e!=null)main.post(()->{if(!destroyed)fail(UiText.of("attachmentFailed"));});});
    }
    private CompletableFuture<Path> attachmentPath(java.util.function.Supplier<Path> lookup){
        try{return CompletableFuture.supplyAsync(lookup,fileSelection);}
        catch(RejectedExecutionException error){CompletableFuture<Path> failed=new CompletableFuture<>();failed.completeExceptionally(error);return failed;}
    }
    public CompletableFuture<Path> attachmentPath(String peer,AttachmentInfo info){return attachmentPath(()->{
        try{Path path=store.attachmentFile(peer,info);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Attachment unavailable");return path;}
        catch(IOException e){throw new CompletionException(e);}
    });}
    public CompletableFuture<Path> attachmentPath(String peer,AttachmentRecord record){return attachmentPath(()->{
        try{Path path=store.attachmentFile(peer,record.info,record.outgoing);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Attachment unavailable");return path;}
        catch(IOException e){throw new CompletionException(e);}
    });}
    public CompletableFuture<Void> exportAttachment(String peer,AttachmentRecord record,Uri uri){return attachmentPath(peer,record).thenAcceptAsync(path->{
        try(InputStream input=Files.newInputStream(path);OutputStream output=resolver.openOutputStream(uri,"wt")){
            if(output==null)throw new IOException("Export unavailable");byte[] bytes=new byte[32768];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);
        }catch(IOException e){throw new CompletionException(e);}
    },fileSelection);}
    public CompletableFuture<Void> exportAttachment(String peer,AttachmentInfo info,Uri uri){return attachmentPath(peer,info).thenAcceptAsync(path->{
        try(InputStream input=Files.newInputStream(path);OutputStream output=resolver.openOutputStream(uri,"wt")){
            if(output==null)throw new IOException("Export unavailable");byte[] bytes=new byte[32768];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);
        }catch(IOException e){throw new CompletionException(e);}
    },fileSelection);}
    public boolean send(String body) {
        if (!canSend()) { fail(UiText.of("notConnected")); return false; }
        Frame frame = new Frame(Frame.TEXT, UUID.randomUUID().toString(), body, System.currentTimeMillis());
        try { Protocol.write(new ByteArrayOutputStream(), frame); } catch (IOException e) { fail(UiText.of("invalidMessage")); return false; }
        FramedSession session = active; String peerId = remoteHello.id, name = remoteHello.body;
        savingMessage = true; changed();
        db(() -> store.save(peerId, name, frame, true), () -> {
            savingMessage = false; savedMessageId = frame.id; savedMessagePeer = peerId; savedMessageBody = frame.body;
            if (!session.send(frame)) db(() -> store.uncertain(peerId), this::refresh);
            changed(); refresh();
        }, () -> savingMessage = false); return true;
    }
    public void selectConversation(ChatStore.Conversation conversation) {
        if (!Objects.equals(selectedId, conversation.id)) messages = new ArrayList<>();
        selectedId = conversation.id; selectedName = conversation.name; refresh(); changed();
    }
    public void clearConversation() { if(selectedId!=null){String id=selectedId;if(transfers!=null&&Objects.equals(id,connectedPeerId))transfers.cancelAll().whenComplete((v,e)->main.post(()->{if(!destroyed){if(e==null)db(()->store.clear(id),this::refresh);else fail(UiText.of("attachmentFailed"));}}));else db(()->store.clear(id),this::refresh);} }
    private void refresh() {
        String selection = selectedId; long version = trust.version();
        db(() -> {
            List<ChatStore.Conversation> saved = store.conversations();
            List<ChatStore.TrustedDevice> devices = store.trustedDevices();
            List<ChatStore.Message> history = selection == null ? new ArrayList<>() : store.messages(selection);
            main.post(() -> {
                if (destroyed) return; conversations = saved;
                if (version == trust.version()) trustedDevices = devices;
                if (Objects.equals(selection, selectedId)) messages = history;
                changed();
            });
        }, null);
    }
    private void db(Runnable work, Runnable success) { db(work, success, null); }
    private void db(Runnable work, Runnable success, Runnable failure) {
        if (destroyed) return;
        storage.execute(() -> {
            try { work.run(); if (success != null) main.post(() -> { if (!destroyed) success.run(); }); }
            catch (RuntimeException e) { android.util.Log.e("NearbyIM", "Local record operation failed", e); main.post(() -> {
                if (!destroyed) { if (failure != null) failure.run(); closeActive(UiText.of("androidStorageStopped")); fail(UiText.of("androidStorageFailure")); }
            }); }
        });
    }
    private void fail(UiText message) { error = message; changed(); }
    public void destroy() {
        if (destroyed) return; stopAll(); destroyed = true; observer = null; lan.destroy(); bluetooth.destroy();
        fileSelection.shutdownNow();attachmentsStopped.whenComplete((v,e)->{storage.execute(store::close);storage.shutdown();});
    }
}

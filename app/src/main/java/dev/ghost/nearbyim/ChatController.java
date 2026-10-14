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
    public boolean lanRunning, bluetoothRunning, lanListening, bluetoothListening, lanSearching, bluetoothSearching, connecting;
    public boolean directRunning, directListening, directSearching;
    public UiText directInfo=UiText.of("notStarted"),routeNotice=UiText.EMPTY;
    public volatile boolean connected;
    public int sessionMode;
    public volatile String connectedPeerId, selectedId;
    public String selectedName, approvalId, approvalName;
    public String savedMessageId, savedMessagePeer, savedMessageBody;
    public boolean savingMessage;
    public final LinkedHashMap<String, Peer> peers = new LinkedHashMap<>();
    public List<ChatStore.Message> messages = new ArrayList<>();
    public List<ChatStore.Conversation> conversations = new ArrayList<>();
    public List<ChatStore.TrustedDevice> trustedDevices = new ArrayList<>();
    private Map<String,String> verifiedRoots=new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final ChatStore store;
    private final LanTransport lan;
    private final BluetoothTransport bluetooth;
    private final WifiDirectTransport direct;
    private final DeviceIdentity identity;
    private final CompletableFuture<byte[]> noiseIdentity=new CompletableFuture<>();
    private final TrustPolicy trust = new TrustPolicy();
    private final Hooks hooks;
    private Runnable observer, reconnectTimeout;
    private boolean refreshingHistory, refreshHistoryAgain;
    private volatile FramedSession active;
    private volatile ClientAttachmentTransfers transfers;
    private volatile AvatarSync avatarSync;
    private volatile boolean avatarPreparing;
    private record PendingAvatar(FramedSession wire,AvatarPacket packet){}
    private final ArrayBlockingQueue<PendingAvatar> pendingAvatars=new ArrayBlockingQueue<>(8);
    private final AndroidAvatars.Listener avatarListener;
    private volatile long attachmentToken;
    private long attachmentRetryRevision;
    private final Map<String,CompletableFuture<?>> attachmentRetryRequests=new HashMap<>();
    private final Map<String,Integer> retryingAttachments=new HashMap<>();
    private CompletableFuture<Void> attachmentsStopped=CompletableFuture.completedFuture(null);
    private CompletableFuture<ClientAttachmentTransfers> attachmentsPreparing;
    private final ContentResolver resolver;
    private final Context applicationContext;
    private volatile TransferStorageBudget attachmentStorage;
    private record Feedback(AttachmentRecord record,TransferProgress progress){}
    private final ConcurrentHashMap<String,Feedback> transferProgress=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor fileSelection=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"attachment-document");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<AttachmentSource,Long> selectingSources=new ConcurrentHashMap<>();
    private final Set<StreamConnection> waitingForNoise=new HashSet<>();
    private Frame remoteHello;
    private volatile TrustPolicy.Authorization authorization;
    private int connectingMode;
    private String expectedPeerId, outgoingBluetoothAddress, lanReconnectId,activeBluetoothAddress;
    private boolean rememberOutgoing;
    private boolean directFallback;
    private volatile boolean destroyed;

    public ChatController(Context context, Hooks hooks) {
        this.hooks = hooks;applicationContext=context.getApplicationContext();resolver=applicationContext.getContentResolver();preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String id = preferences.getString("id", null);
        boolean existingIdentity = id != null;
        if (id == null) { id = UUID.randomUUID().toString(); preferences.edit().putString("id", id).apply(); }
        localId = id; nickname = preferences.getString("nickname", null);
        if (nickname == null) { nickname = AndroidText.initialNickname(context, existingIdentity); preferences.edit().putString("nickname", nickname).apply(); }
        DeviceIdentity loaded = null;
        try { loaded = AndroidIdentity.load(); }
        catch (GeneralSecurityException | RuntimeException e) { error = UiText.of("identityUnavailableRestart"); }
        identity = loaded;
        avatarListener=root->main.post(()->{
            if(!destroyed&&identity!=null&&identity.publicKey().equals(root)&&avatarSync!=null)avatarSync.localChanged();
        });
        if(identity!=null)storage.execute(()->{try{noiseIdentity.complete(AndroidNoiseIdentity.load(applicationContext,identity));}catch(Exception failure){noiseIdentity.completeExceptionally(failure);main.post(()->{if(!destroyed)fail(UiText.of("identityUnavailableRestart"));});}});
        else noiseIdentity.completeExceptionally(new IOException("Device identity unavailable"));
        store = new ChatStore(context); lan = new LanTransport(context, this); bluetooth = new BluetoothTransport(context, this);direct=new WifiDirectTransport(context,this);
        if(identity!=null)db(()->store.invalidateLocalVerifications(identity.publicKey()),null);
        try{attachmentStorage=new TransferStorageBudget(store.attachmentsRoot(),preferences.getLong("attachmentQuota",TransferStorageBudget.DEFAULT_QUOTA));}catch(IOException error){this.error=attachmentError(error);}
        db(store::recoverPending, null); refresh();
        AndroidAvatars.get(applicationContext).listen(avatarListener);
    }
    public void observe(Runnable observer) { this.observer = observer; if (observer != null) observer.run(); }
    private void changed() { if (!destroyed) { hooks.onChanged(); if (observer != null) observer.run(); } }
    public boolean needsForeground() { return directRunning || lanRunning || bluetoothRunning || connecting || active != null; }
    public boolean hasSession() { return active != null; }
    public boolean bluetoothAvailable() { return bluetooth.available(); }
    public boolean bluetoothEnabled() { return bluetooth.enabled(); }
    public boolean directAvailable() { return direct.available(); }
    public boolean directEnabled() { return direct.enabled(); }
    public static String modeKey(int mode) { return mode==Peer.WIFI_DIRECT?"wifiDirect":mode==Peer.BLUETOOTH?"bluetooth":"lan"; }
    private String transportKey() { return sessionMode==Peer.WIFI_DIRECT?"wifi_direct":sessionMode==Peer.BLUETOOTH?"bluetooth":"lan"; }
    public boolean searching(int mode) { return mode==Peer.WIFI_DIRECT?directSearching:mode==Peer.LAN?lanSearching:mode==Peer.BLUETOOTH&&bluetoothSearching; }
    public boolean listening(int mode) { return mode==Peer.WIFI_DIRECT?directListening:mode==Peer.LAN?lanListening:mode==Peer.BLUETOOTH&&bluetoothListening; }
    public UiText info(int mode) { return mode==Peer.WIFI_DIRECT?directInfo:mode==Peer.LAN?lanInfo:bluetoothInfo; }
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
        else if (mode == Peer.WIFI_DIRECT) {
            if(directRunning)return;
            // Advertise both paths so a same-identity LAN fallback can also reach the receiver.
            if(!lanRunning)start(Peer.LAN);
            directRunning=true;directListening=false;directInfo=UiText.of("starting");direct.start(localId,nickname);
        }
        else { fail(UiText.of("invalidTransport")); return; }
        changed();
    }
    public void stop(int mode) {
        if (active != null && sessionMode == mode) closeActive(UiText.of("connectionEnded"));
        if (connecting && connectingMode == mode) cancelOutgoing();
        if (mode == Peer.LAN) { lan.stop(); lanRunning = false; lanListening = false; lanSearching = false; lanInfo = UiText.of("notStarted"); }
        else if(mode==Peer.WIFI_DIRECT){direct.stop();directRunning=false;directListening=false;directSearching=false;directInfo=UiText.of("notStarted");}
        else if(mode==Peer.BLUETOOTH) { bluetooth.stop(); bluetoothRunning = false; bluetoothListening = false; bluetoothSearching = false; bluetoothInfo = UiText.of("notStarted"); }
        peers.entrySet().removeIf(entry -> entry.getValue().mode == mode); changed();
    }
    public void stopAll() { stop(Peer.WIFI_DIRECT); stop(Peer.LAN); stop(Peer.BLUETOOTH); }
    public void search(int mode) {
        if (!running(mode)) { fail(UiText.of("startReceptionFirst")); return; }
        if (connecting) { fail(UiText.of("pleaseWaitConnecting")); return; }
        error = UiText.EMPTY; peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
        if (mode == Peer.LAN) lan.discover(); else if(mode==Peer.WIFI_DIRECT)direct.discover();else bluetooth.scan(); changed();
    }
    public void stopSearch(int mode) {
        if (mode == Peer.LAN) {
            if (lanReconnectId != null && connectingMode==mode) { cancelOutgoing(); status = UiText.of("connectionAttemptStopped"); }
            lan.stopSearch();
        } else if(mode==Peer.WIFI_DIRECT){if(lanReconnectId!=null&&connectingMode==mode){cancelOutgoing();status=UiText.of("connectionAttemptStopped");}direct.stopSearch();}else bluetooth.stopSearch();
        changed();
    }
    public boolean running(int mode) { return mode==Peer.WIFI_DIRECT?directRunning:mode == Peer.LAN ? lanRunning : mode == Peer.BLUETOOTH && bluetoothRunning; }
    private ChatStore.TrustedDevice trustedDevice(String peerId) {
        for (ChatStore.TrustedDevice device : trustedDevices) if (Objects.equals(device.id, peerId)) return device;
        return null;
    }
    public boolean isTrusted(String peerId) { return peerId != null && trustedDevice(peerId) != null; }
    public String localAvatarRoot() { return identity==null?null:identity.publicKey(); }
    /** Only a proven session root or a persisted contact root can select an avatar. */
    public String avatarRoot(String peerId) {
        if(peerId==null)return null;
        FramedSession wire=active;TrustPolicy.Authorization grant=authorization;
        if(connected&&Objects.equals(peerId,connectedPeerId)&&wire!=null&&wire.isReady()&&wire.endToEndEncrypted()&&grant!=null&&trust.canVerify(grant))return grant.publicKey;
        ChatStore.TrustedDevice device=trustedDevice(peerId);return device==null?null:device.publicKey;
    }
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
        if (peer.mode == Peer.LAN) lan.connect(peer.host, peer.port); else if(peer.mode==Peer.WIFI_DIRECT)direct.connect(peer);else bluetooth.connect(peer.bluetoothAddress);
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
        peers.entrySet().removeIf(entry -> entry.getValue().mode == device.mode);
        reconnectTimeout = () -> {
            if (lanReconnectId == null) return;
            if(device.mode==Peer.WIFI_DIRECT&&tryFallback(device.mode,UiText.of("rememberedPeerNotFound")))return;
            cancelOutgoing(); stopSearch(device.mode); status = UiText.of("connectionUnavailable");fail(UiText.of("rememberedPeerNotFound"));
        };
        main.postDelayed(reconnectTimeout, 12000);if(device.mode==Peer.WIFI_DIRECT)direct.discover();else lan.discover(); changed();
    }
    private boolean beginConnect(int mode, String expected, boolean remember, String bluetoothAddress) {
        if (active != null || connecting) { fail(UiText.of("busy")); return false; }
        if (identity == null) { fail(UiText.of("identityUnavailable")); return false; }
        if (!running(mode)) { fail(UiText.of("startModeReceptionFirst")); return false; }
        expectedPeerId = expected; rememberOutgoing = remember; outgoingBluetoothAddress = bluetoothAddress;
        directFallback=mode==Peer.WIFI_DIRECT;routeNotice=UiText.EMPTY;
        connecting = true; connectingMode = mode; sessionMode = mode; error = UiText.EMPTY; status = UiText.of(mode==Peer.WIFI_DIRECT?"wifiDirectConnecting":"connecting"); changed(); return true;
    }
    private void clearReconnectWait() {
        lanReconnectId = null;
        if (reconnectTimeout != null) { main.removeCallbacks(reconnectTimeout); reconnectTimeout = null; }
    }
    private void cancelOutgoing() {
        clearReconnectWait(); lan.cancelConnect(); bluetooth.cancelConnect();direct.cancelConnect();directFallback=false;
        for(StreamConnection connection:waitingForNoise)close(connection);waitingForNoise.clear();
        connecting = false; expectedPeerId = null; outgoingBluetoothAddress = null; rememberOutgoing = false;
    }
    public void revokeTrust(String peerId) {
        if (peerId == null) return;
        trust.revoke(peerId);
        verifiedRoots.remove(peerId);
        trustedDevices = new ArrayList<>(trustedDevices);
        trustedDevices.removeIf(device -> device.id.equals(peerId));
        if (remoteHello != null && peerId.equals(remoteHello.id)) {
            trust.cancel(authorization);
            closeActive(UiText.of("trustRevokedReconnect"));
        }
        if (connecting && peerId.equals(expectedPeerId)) {
            if (active != null) closeActive(UiText.of("trustRevokedCanceled"));
            else { cancelOutgoing(); status = UiText.of("trustRevokedCanceled"); }
        }
        db(()->store.revokeTrust(peerId),this::refresh);
        attachmentsStopped=attachmentsStopped.handle((value,failure)->null).thenRunAsync(()->{try{for(AttachmentRecord record:AttachmentTransferV2.cancelPending(store.attachmentDirectory(peerId)))store.attachment(peerId,peerId,record);AndroidAttachmentSource.releaseUnusedGrants(applicationContext);}catch(IOException failure){throw new CompletionException(failure);}},storage);
        attachmentsStopped.whenComplete((value,failure)->main.post(()->{if(!destroyed){if(failure!=null)fail(attachmentError(failure));refresh();}}));changed();
    }
    public void onPeer(Peer peer) {
        if (destroyed) return;
        peers.put(peer.key, peer);
        if (connecting && lanReconnectId != null && lanReconnectId.equals(peer.peerId) && peer.mode == connectingMode) {
            clearReconnectWait(); status = UiText.of(peer.mode==Peer.WIFI_DIRECT?"wifiDirectConnecting":"connecting");
            if(peer.mode==Peer.WIFI_DIRECT){direct.stopSearch();direct.connect(peer);}else{lan.stopSearch();lan.connect(peer.host, peer.port);}
        }
        changed();
    }
    public void onLost(String key) { peers.remove(key); changed(); }
    public void onSearching(int mode, boolean searching) { if(mode==Peer.WIFI_DIRECT)directSearching=searching;else if (mode == Peer.LAN) lanSearching = searching; else bluetoothSearching = searching; changed(); }
    public void onSearchStopFailed(int mode, UiText message) { fail(message); }
    public void onListening(int mode, UiText detail) { if(mode==Peer.WIFI_DIRECT){directInfo=detail;directListening=true;}else if (mode == Peer.LAN) { lanInfo = detail; lanListening = true; } else { bluetoothInfo = detail; bluetoothListening = true; } changed(); }
    public void onError(int mode, UiText message, boolean fatal) {
        boolean fallback=fatal&&connecting&&connectingMode==mode&&active==null&&tryFallback(mode,message);
        if (!fallback&&fatal&&lanReconnectId != null && mode == connectingMode) { cancelOutgoing(); status = UiText.of("connectionUnavailable"); }
        if (fatal) {
            if (!fallback&&connecting && connectingMode == mode) cancelOutgoing();
            if(mode==Peer.WIFI_DIRECT){directRunning=false;directListening=false;directSearching=false;directInfo=UiText.of("notStarted");}else if (mode == Peer.LAN) { lanRunning = false; lanListening = false; lanSearching = false; lanInfo = UiText.of("notStarted"); } else { bluetoothRunning = false; bluetoothListening = false; bluetoothSearching = false; bluetoothInfo = UiText.of("notStarted"); }
            peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
            if (active != null && sessionMode == mode) closeActive(message);
        }
        if(!fallback)fail(message);else changed();
    }
    public void onConnectFailed(int mode, UiText message) {
        if (!connecting || connectingMode != mode || active != null) return;
        if(tryFallback(mode,message))return;
        cancelOutgoing(); status = UiText.of("connectionUnavailable"); fail(message);
    }
    /** Only transport failure before Noise/identity validation permits an automatic fallback. */
    private boolean tryFallback(int failedMode,UiText reason) {
        if(!directFallback||active!=null||expectedPeerId==null||!connecting||connectingMode!=failedMode)return false;
        if(failedMode==Peer.WIFI_DIRECT) {
            direct.cancelConnect();clearReconnectWait();connectingMode=Peer.LAN;sessionMode=Peer.LAN;
            routeNotice=UiText.of("wifiDirectFallbackLan",reason);status=routeNotice;error=UiText.EMPTY;
            if(!lanRunning)start(Peer.LAN);
            for(Peer peer:peers.values())if(peer.mode==Peer.LAN&&expectedPeerId.equals(peer.peerId)){lan.connect(peer.host,peer.port);changed();return true;}
            lanReconnectId=expectedPeerId;
            reconnectTimeout=()->{if(lanReconnectId!=null){clearReconnectWait();onConnectFailed(Peer.LAN,UiText.of("rememberedPeerNotFound"));}};
            main.postDelayed(reconnectTimeout,12000);lan.discover();changed();return true;
        }
        if(failedMode==Peer.LAN&&bluetoothRunning) {
            String address=null;
            for(Peer peer:peers.values())if(peer.mode==Peer.BLUETOOTH&&expectedPeerId.equals(peer.peerId)){address=peer.bluetoothAddress;break;}
            if(address==null){ChatStore.TrustedDevice device=trustedDevice(expectedPeerId);if(device!=null)address=device.bluetoothAddress;}
            if(address==null)return false;
            lan.cancelConnect();clearReconnectWait();lan.stopSearch();connectingMode=Peer.BLUETOOTH;sessionMode=Peer.BLUETOOTH;outgoingBluetoothAddress=address;
            routeNotice=UiText.of("wifiDirectFallbackBluetooth",reason);status=routeNotice;error=UiText.EMPTY;bluetooth.connect(address);changed();return true;
        }
        return false;
    }
    public void onConnection(int mode, StreamConnection connection, boolean incoming) { onConnection(mode, connection, incoming, null); }
    public void onConnection(int mode, StreamConnection connection, boolean incoming, String bluetoothAddress) {
        ConnectionRole role=ConnectionRole.decide(mode==Peer.WIFI_DIRECT,incoming,connecting,connectingMode==mode);
        if (destroyed || identity == null || !running(mode) || active != null || role==ConnectionRole.DISCARD) { close(connection); return; }
        if(!noiseIdentity.isDone()){if(waitingForNoise.add(connection))noiseIdentity.whenComplete((key,failure)->main.post(()->{if(waitingForNoise.remove(connection))onConnection(mode,connection,incoming,bluetoothAddress);}));return;}
        final byte[] staticKey;
        try{staticKey=noiseIdentity.getNow(null);if(staticKey==null)throw new CompletionException(new IOException("Noise identity unavailable"));}
        catch(CompletionException failure){close(connection);cancelOutgoing();fail(UiText.of("identityUnavailableRestart"));return;}
        final boolean userRequested=role.userRequested();
        final String expected = userRequested ? expectedPeerId : null;
        final long attemptRevision = expected == null ? -1 : trust.peerRevision(expected);
        final boolean remember = userRequested && rememberOutgoing;
        final String socketAddress = bluetoothAddress != null ? bluetoothAddress : incoming ? null : outgoingBluetoothAddress;
        clearReconnectWait(); connecting = true; connectingMode = mode; connected = false; connectedPeerId = null;
        sessionMode = mode; remoteHello = null; authorization = null;activeBluetoothAddress=socketAddress;
        status = UiText.of("handshake"); error = UiText.EMPTY;
        final FramedSession[] reference = new FramedSession[1];
        try{reference[0] = FramedSession.secure(connection, localId, nickname, identity,staticKey,!incoming,new FramedSession.Listener() {
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
                    authorization = trust.begin(hello.id, publicKey, pin[0] == null ? null : pin[0].publicKey, userRequested, remember);
                    if (authorization.decision == TrustPolicy.Decision.IDENTITY_CHANGED) {
                        verifiedRoots.remove(hello.id);db(()->store.clearVerification(hello.id),null);
                        closeActive(UiText.of("identityChangedRejected"));
                        fail(UiText.of("androidIdentityChanged"));
                    } else if (authorization.decision == TrustPolicy.Decision.APPROVE) {
                        status = userRequested ? UiText.of("waitingPeerConsent") : UiText.of("completingConnection"); approveWithFiles(reference[0]); changed();
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
                if(transfers==null||!transfers.v2()){closeActive(UiText.of("attachmentFailed"));return;}
                startAvatars(wire,grant);
                if (userRequested) {
                    if (!Objects.equals(selectedId, hello.id)) messages = new ArrayList<>();
                    selectedId = hello.id; selectedName = hello.body;
                }
                directFallback=false;status = UiText.of("connectedVia", UiText.of(modeKey(mode))); changed();
                db(() -> {
                    trust.persist(grant, () -> store.remember(hello.id, hello.body, grant.publicKey, mode, socketAddress));
                    store.touch(hello.id, hello.body);
                }, ChatController.this::refresh);
            }); }
            public void onAttachment(Frame frame){main.post(()->{if(active==reference[0]&&transfers!=null)transfers.receive(frame);});}
            public void onTransfer(TransferPacket packet){ClientAttachmentTransfers target=transfers;if(active!=reference[0]||target==null)return;try{target.receive(packet);}catch(IOException failure){reference[0].close(UiText.of("attachmentFailed"));}}
            public void onAvatar(AvatarPacket packet){
                if(active!=reference[0]||!reference[0].avatarsSupported())return;
                AvatarSync sync=avatarSync;
                if(sync!=null){if(!sync.receive(packet))reference[0].close(UiText.of("busy"));}
                else if(avatarPreparing||!connected){if(!pendingAvatars.offer(new PendingAvatar(reference[0],packet)))reference[0].close(UiText.of("busy"));}
            }
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
        },true);}catch(IOException failure){close(connection);cancelOutgoing();status=UiText.of("connectionUnavailable");fail(UiText.of("identityUnavailableRestart"));return;}
        attachmentToken++;active = reference[0]; active.start(); changed();
    }
    public void approve(String token) { approve(token, true); }
    private void startAvatars(FramedSession wire,TrustPolicy.Authorization grant) {
        if(!wire.avatarsSupported()||grant==null||identity==null||identity.publicKey().equals(grant.publicKey)){pendingAvatars.clear();return;}
        avatarPreparing=true;
        CompletableFuture<AvatarSync> preparing=new CompletableFuture<>();
        try{storage.execute(()->{
            try {
                Path directory=applicationContext.getFilesDir().getCanonicalFile().toPath().resolve("avatars");
                AvatarSync sync=new AvatarSync(new AvatarStore(directory,identity.publicKey()),new AvatarStore(directory,grant.publicKey),wire::sendAvatar,new AvatarSync.Listener(){
                    public void onChanged(AvatarSnapshot snapshot){main.post(()->{if(!destroyed&&active==wire&&authorization==grant&&trust.ready(grant))AndroidAvatars.get(applicationContext).refresh(grant.publicKey);});}
                    public void onProtocolError(IOException error){if(active==wire)wire.close(UiText.of("disconnected"));}
                });
                preparing.complete(sync);
            }catch(Exception failure){preparing.completeExceptionally(failure);}
        });}catch(RejectedExecutionException stopped){preparing.completeExceptionally(stopped);}
        preparing.whenComplete((sync,error)->main.post(()->{
            if(destroyed||active!=wire||authorization!=grant||!wire.isReady()||!trust.ready(grant)){if(sync!=null)sync.close();return;}
            avatarPreparing=false;
            if(error!=null){pendingAvatars.clear();fail(UiText.of("avatarSaveFailed"));return;}
            avatarSync=sync;sync.start();PendingAvatar pending;while((pending=pendingAvatars.poll())!=null)if(pending.wire()==wire&&!sync.receive(pending.packet())){wire.close(UiText.of("busy"));break;}
        }));
    }
    public void approve(String token, boolean remember) {
        if (active == null || approvalId == null || !Objects.equals(token, approvalId) || remoteHello == null) return;
        if (!trust.approve(authorization, remember)) return;
        approvalId = null; approvalName = null; status = UiText.of("waitingBothReady");
        approveWithFiles(active); changed();
    }
    /** Prepare the file reader before READY can allow the peer to send DATA. */
    private void approveWithFiles(FramedSession wire) {
        if(active!=wire||remoteHello==null||attachmentsPreparing!=null)return;
        Frame hello=remoteHello;TrustPolicy.Authorization grant=authorization;long revision=trust.peerRevision(hello.id);boolean rfcomm=sessionMode==Peer.BLUETOOTH;
        CompletableFuture<Void> previousStopped=attachmentsStopped.handle((value,failure)->null);
        CompletableFuture<ClientAttachmentTransfers> preparing=new CompletableFuture<>();attachmentsPreparing=preparing;
        previousStopped.whenComplete((ignored,stopFailure)->{
            if(stopFailure!=null){preparing.completeExceptionally(stopFailure);return;}
            try{fileSelection.execute(()->{
                try {
                    if(active!=wire||destroyed)throw new IOException("Session changed during preparation");
                    Path directory=store.attachmentDirectory(hello.id);String localRoot=identity.fingerprint(),remoteRoot=DeviceIdentity.fingerprint(wire.remotePublicKey());
                    AttachmentTransferV2.SourceResolver sources=checkpoint->{String saved=checkpoint.sourceReference();
                        if(saved.startsWith("snapshot:\n"))return OwnedSnapshotSource.restore(directory.resolve(".sources-v2"),saved,checkpoint.key().sourceGeneration());
                        AndroidAttachmentSource source=new AndroidAttachmentSource(applicationContext,Uri.parse(saved.split("\n",2)[0]),checkpoint.key().sourceGeneration());
                        if(!saved.equals(source.persistentReference())){source.close();source.discard();throw new LocalizedIOException(UiText.of("attachmentSourceChanged"));}return source;};
                    AttachmentTransferV2 engine=new AttachmentTransferV2(directory,localRoot,remoteRoot,wire.connectionGeneration(),rfcomm,new AttachmentTransferV2.Wire(){
                        public boolean send(TransferPacket packet){return wire.sendTransfer(packet);}public void abort(){wire.close(UiText.of("attachmentFailed"));}
                    },new AttachmentTransferV2.Listener(){
                        public void changed(AttachmentRecord record)throws IOException {
                            try{storage.submit(()->store.attachment(hello.id,hello.body,record)).get(10,TimeUnit.SECONDS);}
                            catch(Exception failure){throw new IOException("Attachment metadata save failed",failure);}
                            if(!record.outgoing&&record.state.equals("received")&&record.info.mime.startsWith("image/"))try{GalleryPublisher.get(applicationContext).export(hello.id,record,AttachmentTransfer.file(directory,record.info,false),false);}catch(IOException exportFailure){main.post(()->{if(!destroyed)fail(UiText.of("gallerySaveFailed"));});}
                            main.post(()->{if(!destroyed)refresh();});
                        }
                        public void progress(AttachmentRecord record,TransferProgress progress){
                            if(active!=wire)return;String key=hello.id+":"+record.info.id+":"+record.outgoing;
                            if(!record.active()&&!record.resumable()){transferProgress.remove(key);return;}
                            transferProgress.put(key,new Feedback(record,progress));
                            main.post(()->{if(!destroyed&&active==wire){if(Objects.equals(selectedId,hello.id))messages=liveMessages(messages,hello.id);ChatController.this.changed();}});
                        }
                    },attachmentStorage);
                    ClientAttachmentTransfers files=new ClientAttachmentTransfers(directory,engine,sources,selected->{
                        if(selected.reference().startsWith("snapshot:\n"))return OwnedSnapshotSource.restore(directory.resolve(".sources-v2"),selected.reference(),selected.generation());
                        AndroidAttachmentSource source=new AndroidAttachmentSource(applicationContext,Uri.parse(selected.reference().split("\n",2)[0]),selected.generation());
                        if(selected.exact()&&!selected.reference().equals(source.persistentReference())){source.close();throw new LocalizedIOException(UiText.of("attachmentSourceChanged"));}return source;
                    });
                    files.batchListener(new AttachmentBatchQueue.Listener(){
                        public void changed(AttachmentRecord record)throws IOException {try{storage.submit(()->store.attachment(hello.id,hello.body,record)).get(10,TimeUnit.SECONDS);}catch(Exception failure){throw new IOException("Batch history save failed",failure);}main.post(()->{if(!destroyed)refresh();});releaseUnusedSources();}
                        public void finished(AttachmentRecord record){releaseUnusedSources();}
                        private void releaseUnusedSources(){try{fileSelection.execute(()->{try{AndroidAttachmentSource.releaseUnusedGrants(applicationContext);}catch(IOException ignored){}});}catch(RejectedExecutionException stopped){}}
                    });
                    preparing.complete(files);
                }catch(Exception failure){preparing.completeExceptionally(failure);}
            });}catch(RejectedExecutionException failure){preparing.completeExceptionally(failure);}
        });
        preparing.whenComplete((prepared,failure)->main.post(()->{
            if(active!=wire||destroyed||authorization!=grant||trust.peerRevision(hello.id)!=revision){if(prepared!=null)prepared.shutdown();return;}
            if(failure!=null){closeActive(UiText.of("attachmentFailed"));fail(attachmentError(failure));return;}
            transfers=prepared;wire.approve();changed();
        }));
    }
    public void reject(String token) { if (active != null && approvalId != null && Objects.equals(token, approvalId)) closeActive(UiText.of("connectionRejected")); }
    private void releaseSession(FramedSession session, UiText reason) {
        if (active != session) return;
        String peerId = remoteHello == null ? null : remoteHello.id;
        if(avatarSync!=null){avatarSync.close();avatarSync=null;}avatarPreparing=false;pendingAvatars.clear();
        if(transfers!=null){recordAttachmentShutdown(transfers.shutdown());transfers=null;}attachmentToken++;
        if(attachmentsPreparing!=null){CompletableFuture<ClientAttachmentTransfers> pending=attachmentsPreparing;attachmentsPreparing=null;
            recordAttachmentShutdown(pending.handle((created,failure)->created==null?CompletableFuture.<Void>completedFuture(null):created.shutdown()).thenCompose(value->value));}
        selectingSources.forEach((source,token)->{if(token!=attachmentToken){selectingSources.remove(source);try{source.close();}catch(IOException ignored){}}});
        transferProgress.clear();
        trust.cancel(authorization); authorization = null; active = null; remoteHello = null;
        connected = false; connectedPeerId = null; approvalId = null; approvalName = null;
        cancelOutgoing(); status = reason;
        if(sessionMode==Peer.WIFI_DIRECT)direct.disconnect();
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
    public String transferDiagnostics(){
        FramedSession wire=active;ClientAttachmentTransfers files=transfers;
        return "diagnostics_schema=1\nendpoint=android\nversion="+LanguageRegistry.VERSION+"\ntransport="+transportKey()+"\n"+(wire==null?"active_session=false\n":wire.diagnostics())+(files==null?"":files.diagnostics());
    }
    public boolean canSendAttachment(){return canSend()&&transfers!=null&&active.attachmentsSupported();}
    public long attachmentRetryRevision(){return attachmentRetryRevision;}
    /** Saved picker state includes both proven roots and the Noise connection generation. */
    public String attachmentSelectionSession(String peer){
        FramedSession wire=active;TrustPolicy.Authorization grant=authorization;
        if(!connected||!Objects.equals(peer,selectedId)||!Objects.equals(peer,connectedPeerId)||wire==null||!wire.isReady()||transfers==null||!wire.attachmentsSupported()||!wire.endToEndEncrypted()||!trust.canVerify(grant)||identity==null)return null;
        return identity.publicKey()+"\n"+grant.publicKey+"\n"+wire.connectionGeneration();
    }
    public AttachmentBatchQueue.RetryState attachmentRetryState(String peer,String id){
        if(retryingAttachments.containsKey(peer+":"+attachmentToken+":"+id))return AttachmentBatchQueue.RetryState.RETRYING;
        ClientAttachmentTransfers files=transfers;return files!=null&&Objects.equals(peer,connectedPeerId)?files.retryState(id):AttachmentBatchQueue.RetryState.AVAILABLE;
    }
    public boolean hasRetryableAttachmentBatch(String peer,String id){ClientAttachmentTransfers files=transfers;return files!=null&&Objects.equals(peer,connectedPeerId)&&files.hasRetryableBatch(id);}
    public TransferProgress transferProgress(String peer,AttachmentRecord record){Feedback value=transferProgress.get(peer+":"+record.info.id+":"+record.outgoing);return value==null?TransferProgress.UNKNOWN:value.progress();}
    public TransferPath attachmentRoute(String peer,AttachmentRecord record) {
        FramedSession wire=active;
        boolean ready=connected&&Objects.equals(peer,connectedPeerId)&&wire!=null&&wire.isReady()&&wire.attachmentsSupported();
        return TransferPath.forAttachment(transportKey(),ready,record);
    }
    public boolean endToEndEncrypted(String peer){FramedSession wire=active;return connected&&Objects.equals(peer,connectedPeerId)&&wire!=null&&wire.endToEndEncrypted();}
    public boolean identityVerified(String peer){return endToEndEncrypted(peer)&&authorization!=null&&authorization.publicKey.equals(verifiedRoots.get(peer));}
    public IdentityVerification verification(String peer){
        if(!endToEndEncrypted(peer)||!trust.canVerify(authorization))return null;
        try{return new IdentityVerification(peer,identity.publicKey(),authorization.publicKey,active.connectionGeneration(),identityVerified(peer));}
        catch(GeneralSecurityException invalid){fail(UiText.of("identityVerificationFailed"));return null;}
    }
    public boolean verificationCurrent(IdentityVerification expected){
        return expected!=null&&endToEndEncrypted(expected.peerId)&&trust.canVerify(authorization)&&expected.localRoot.equals(identity.publicKey())&&expected.remoteRoot.equals(authorization.publicKey)&&expected.generation.equals(active.connectionGeneration());
    }
    public void verifyIdentity(IdentityVerification expected,String scanned,java.util.function.Consumer<Boolean> completion){
        if(!verificationCurrent(expected)||scanned!=null&&!expected.code.matchesQr(scanned)){completion.accept(false);return;}
        FramedSession wire=active;TrustPolicy.Authorization grant=authorization;String name=remoteHello.body,address=activeBluetoothAddress;int mode=sessionMode;
        storage.execute(()->{
            boolean saved=false;UiText failure=null;
            try{saved=trust.verify(grant,()->{
                if(active!=wire||!wire.endToEndEncrypted())throw new CancellationException("Verification session closed");
                store.remember(expected.peerId,name,expected.remoteRoot,mode,address);store.markVerified(expected.peerId,expected.localRoot,expected.remoteRoot);
            });}
            catch(CancellationException canceled){}catch(RuntimeException error){failure=UiText.of("storageFailure");}
            boolean result=saved;UiText problem=failure;main.post(()->{
                if(destroyed)return;boolean current=result&&verificationCurrent(expected);if(current)verifiedRoots.put(expected.peerId,expected.remoteRoot);
                if(problem!=null)fail(problem);completion.accept(current);changed();refresh();
            });
        });
    }
    private List<ChatStore.Message> liveMessages(List<ChatStore.Message> history,String peer){
        List<ChatStore.Message> values=new ArrayList<>(history.size());
        for(ChatStore.Message message:history){AttachmentRecord previous=message.attachment;Feedback value=previous==null?null:transferProgress.get(peer+":"+previous.info.id+":"+previous.outgoing);
            if(value!=null&&previous.mayReplace(value.record())){AttachmentRecord record=value.record();String state=record.outgoing?(record.state.equals("delivered")?ChatStore.DELIVERED:record.active()?ChatStore.PENDING:ChatStore.UNKNOWN):message.state;
                values.add(new ChatStore.Message(message.id,message.text,state,message.outgoing,message.time,record));
            }else values.add(message);
        }return values;
    }
    public long attachmentQuota(){return attachmentStorage==null?TransferStorageBudget.DEFAULT_QUOTA:attachmentStorage.quota();}
    public boolean hasActiveAttachments(){return !selectingSources.isEmpty()||transfers!=null&&transfers.hasActive();}
    public boolean isPreparingAttachment(){return !selectingSources.isEmpty();}
    private void recordAttachmentShutdown(CompletableFuture<Void> stopped){
        // Completion is the writer-resource barrier; persistence errors remain reported separately.
        CompletableFuture<Void> reported=stopped.handle((value,failure)->{if(failure!=null)main.post(()->{if(!destroyed)fail(attachmentError(failure));});return null;});
        attachmentsStopped=CompletableFuture.allOf(attachmentsStopped.handle((value,failure)->null),reported);
    }
    public void pauseAttachments(){ClientAttachmentTransfers selected=transfers;if(selected!=null)selected.pauseAll().whenComplete((value,error)->{if(error!=null)main.post(()->fail(attachmentError(error)));});}
    public void cancelAttachments(){attachmentToken++;selectingSources.forEach((source,token)->{selectingSources.remove(source);try{source.close();}catch(IOException ignored){}discardSelection(source);});ClientAttachmentTransfers selected=transfers;if(selected!=null)selected.cancelAll().whenComplete((value,error)->{if(error!=null)main.post(()->fail(attachmentError(error)));});changed();}
    public CompletableFuture<Void> attachmentQuota(long bytes){CompletableFuture<Void> future=new CompletableFuture<>();try{fileSelection.execute(()->{try{if(attachmentStorage==null)attachmentStorage=new TransferStorageBudget(store.attachmentsRoot(),bytes);else attachmentStorage.quota(bytes);preferences.edit().putLong("attachmentQuota",bytes).apply();future.complete(null);}catch(IOException error){future.completeExceptionally(new LocalizedIOException(UiText.of("attachmentQuotaInUse"),error));}});}catch(RejectedExecutionException error){future.completeExceptionally(error);}return future;}
    public void pauseSendQueue(){ClientAttachmentTransfers selected=transfers;if(selected!=null)selected.pauseBatch().whenComplete((value,error)->{if(error!=null)main.post(()->fail(attachmentError(error)));});}
    public void cancelSendQueue(){attachmentToken++;ClientAttachmentTransfers selected=transfers;if(selected!=null)selected.cancelBatch().whenComplete((value,error)->{if(error!=null)main.post(()->fail(attachmentError(error)));});changed();}
    public void resumeAttachments(){ClientAttachmentTransfers selected=transfers;if(selected!=null)selected.resumeBatch().whenComplete((value,error)->{if(error!=null)main.post(()->fail(attachmentError(error)));});}
    public void sendAttachments(String peer,long token,List<Uri> selectedUris){
        List<Uri> uris=List.copyOf(selectedUris);if(uris.isEmpty()||uris.size()>AttachmentBatchQueue.MAX_PENDING){fail(UiText.of("attachmentBatchLimit",AttachmentBatchQueue.MAX_PENDING));return;}
        if(!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}ClientAttachmentTransfers selected=transfers;
        AndroidAttachmentSource.retainSelections(uris);
        try{fileSelection.execute(()->{try{
            List<AttachmentBatchQueue.Selection> descriptions=new ArrayList<>();
            for(Uri uri:uris){if(destroyed||token!=attachmentToken)throw new IOException("Attachment session changed");
                try(AndroidAttachmentSource source=new AndroidAttachmentSource(applicationContext,uri)){
                    // A provider can materialize the same document in a new inode on every open.
                    // Queue the URI and metadata; pin the live descriptor when this item's turn starts.
                    // Offered tasks still persist the full source reference for change/resume checks.
                    descriptions.add(new AttachmentBatchQueue.Selection(uri.toString(),source.generation(),source.name(),source.mime(),source.size(),false));
                }
                catch(IOException unavailable){descriptions.add(new AttachmentBatchQueue.Selection(uri.toString(),UUID.randomUUID().toString(),"attachment","application/octet-stream",-1,false));}
            }
            main.post(()->{if(destroyed||selected!=transfers||!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){releaseSelections(uris);if(!destroyed)fail(UiText.of("notConnected"));return;}
                selected.enqueue(descriptions).whenComplete((batch,error)->{releaseSelections(uris);if(error!=null)main.post(()->{if(!destroyed)fail(attachmentError(error));});});
            });
        }catch(Exception failure){releaseSelections(uris);main.post(()->{if(!destroyed)fail(attachmentError(failure));});}});}catch(RejectedExecutionException full){releaseSelections(uris);fail(UiText.of("attachmentFailed"));}
    }
    private void releaseSelections(List<Uri> uris){AndroidAttachmentSource.releaseSelections(uris);try{fileSelection.execute(()->{try{AndroidAttachmentSource.releaseUnusedGrants(applicationContext);}catch(IOException ignored){}});}catch(RejectedExecutionException stopped){}}
    public void sendAttachment(String peer,long token,Uri uri){
        if(!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}
        ClientAttachmentTransfers selected=transfers;
        try{fileSelection.execute(()->{
            AndroidAttachmentSource original=null;
            try{
                original=new AndroidAttachmentSource(applicationContext,uri);
                selectingSources.put(original,token);
                main.post(this::changed);
                String fileName=original.name(),contentType=original.mime();
                AttachmentSource source=selected.prepareSource(original);
                selectingSources.remove(original);selectingSources.put(source,token);
                main.post(()->{
                    selectingSources.remove(source);
                    if(destroyed||!canSendAttachment()||token!=attachmentToken||!Objects.equals(peer,connectedPeerId)){try{source.close();}catch(IOException ignored){}discardSelection(source);if(!destroyed&&token==attachmentToken)fail(UiText.of("notConnected"));return;}
                    changed();selected.offer(source,fileName,contentType).whenComplete((id,e)->{if(e!=null)main.post(()->{if(!destroyed&&token==attachmentToken)fail(attachmentError(e));});});
                });
            }catch(Exception e){if(original!=null){selectingSources.remove(original);try{original.discard();}catch(IOException ignored){}}main.post(()->{changed();if(!destroyed&&token==attachmentToken)fail(attachmentError(e));});}
        });}catch(RejectedExecutionException e){fail(UiText.of("attachmentFailed"));}
    }
    private void discardSelection(AttachmentSource source){try{fileSelection.execute(()->{try{source.discard();}catch(IOException ignored){}});}catch(RejectedExecutionException stopped){}}
    private record AttachmentRetrySession(String peer,long token,String identity,ClientAttachmentTransfers files,FramedSession wire,TrustPolicy.Authorization grant){}
    private AttachmentRetrySession attachmentRetrySession(String peer,long token,String expectedIdentity){
        String current=attachmentSelectionSession(peer);
        if(current==null||token!=attachmentToken||expectedIdentity!=null&&!expectedIdentity.equals(current))return null;
        return new AttachmentRetrySession(peer,token,current,transfers,active,authorization);
    }
    private boolean attachmentRetryCurrent(AttachmentRetrySession session){return !destroyed&&session.files()==transfers&&session.wire()==active&&session.grant()==authorization&&session.token()==attachmentToken&&session.identity().equals(attachmentSelectionSession(session.peer()));}
    private void requireFailedAttachment(String peer,String id){
        try(Cursor cursor=store.getReadableDatabase().query("messages",new String[]{"attachment"},"peer_id=? AND id=? AND outgoing=1",new String[]{peer,id},null,null,null,"1")){
            if(!cursor.moveToFirst()||cursor.isNull(0))throw new IOException("Missing failed attachment history");
            AttachmentRecord record=AttachmentRecord.decode(cursor.getString(0));
            if(!record.outgoing||record.info.version!=2||!record.info.id.equals(id)||!record.state.equals("failed"))throw new IOException("Attachment is not an outgoing failed task");
        }catch(IOException invalid){throw new CompletionException(invalid);}
    }
    private <T> CompletableFuture<T> rejectedAttachmentRetry(UiText message){fail(message);CompletableFuture<T> failed=new CompletableFuture<>();failed.completeExceptionally(new LocalizedIOException(message));return failed;}
    @FunctionalInterface private interface AttachmentRetryOperation<T>{CompletableFuture<T> run()throws IOException;}
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> queueAttachmentRetry(AttachmentRetrySession session,String request,List<String> ids,AttachmentRetryOperation<T> operation){
        CompletableFuture<?> previous=attachmentRetryRequests.get(request);if(previous!=null)return (CompletableFuture<T>)previous;
        CompletableFuture<T> result=new CompletableFuture<>();attachmentRetryRequests.put(request,result);
        for(String id:ids)retryingAttachments.merge(session.peer()+":"+session.token()+":"+id,1,Integer::sum);
        attachmentRetryRevision++;error=UiText.EMPTY;changed();
        CompletableFuture<T> admitted;
        try{admitted=CompletableFuture.runAsync(()->{for(String id:ids)requireFailedAttachment(session.peer(),id);},storage).thenComposeAsync(value->{
            if(!attachmentRetryCurrent(session))throw new CompletionException(new LocalizedIOException(UiText.of("notConnected")));
            try{return operation.run();}catch(IOException failure){throw new CompletionException(failure);}
        },fileSelection);}catch(RejectedExecutionException stopped){admitted=new CompletableFuture<>();admitted.completeExceptionally(stopped);}
        admitted.whenComplete((value,failure)->main.post(()->{
            attachmentRetryRequests.remove(request,result);
            for(String id:ids){String key=session.peer()+":"+session.token()+":"+id;retryingAttachments.computeIfPresent(key,(ignored,count)->count==1?null:count-1);}
            attachmentRetryRevision++;
            if(attachmentRetryCurrent(session)&&failure!=null)error=attachmentError(failure);
            changed();if(!attachmentRetryCurrent(session))result.completeExceptionally(new LocalizedIOException(UiText.of("notConnected")));else if(failure==null)result.complete(value);else result.completeExceptionally(failure);
        }));return result;
    }
    public CompletableFuture<String> retryAttachment(String peer,long token,String id){
        AttachmentRetrySession session=attachmentRetrySession(peer,token,null);if(session==null)return rejectedAttachmentRetry(UiText.of("notConnected"));
        return queueAttachmentRetry(session,"single:"+peer+":"+token+":"+id,List.of(id),()->session.files().retry(id,()->attachmentRetryCurrent(session)));
    }
    public CompletableFuture<AttachmentBatchQueue.RetryBatchResult> retryFailedAttachmentBatch(String peer,long token,String id){
        AttachmentRetrySession session=attachmentRetrySession(peer,token,null);if(session==null)return rejectedAttachmentRetry(UiText.of("notConnected"));
        List<AttachmentBatchQueue.Item> items=session.files().batchItems();String batch=items.stream().filter(item->item.info().id.equals(id)).map(AttachmentBatchQueue.Item::batchId).findFirst().orElse(null);
        if(batch==null)return rejectedAttachmentRetry(UiText.of("attachmentRetrySelectAgain"));
        List<String> ids=new ArrayList<>();ids.add(id);for(AttachmentBatchQueue.Item item:items)if(item.batchId().equals(batch)&&item.state().equals("failed")&&!item.info().id.equals(id)&&session.files().retryState(item.info().id)!=AttachmentBatchQueue.RetryState.RETRIED)ids.add(item.info().id);
        return queueAttachmentRetry(session,"batch:"+peer+":"+token+":"+batch,ids,()->session.files().retryFailedBatch(id,()->attachmentRetryCurrent(session)));
    }
    public static UiText attachmentRetryBatchFeedback(AttachmentBatchQueue.RetryBatchResult result){
        if(!result.unavailableIds().isEmpty())return UiText.of("attachmentRetryPartial",result.queuedIds().size(),result.unavailableIds().size());
        return UiText.of(result.queuedIds().isEmpty()?"attachmentRetryNone":"attachmentRetryQueued");
    }
    public CompletableFuture<String> retryAttachmentWithSelection(String peer,long token,String expectedSession,String id,Uri uri){
        AttachmentRetrySession session=expectedSession==null?null:attachmentRetrySession(peer,token,expectedSession);if(session==null)return rejectedAttachmentRetry(UiText.of("notConnected"));
        String request="single:"+peer+":"+token+":"+id;CompletableFuture<?> previous=attachmentRetryRequests.get(request);if(previous!=null){@SuppressWarnings("unchecked") CompletableFuture<String> existing=(CompletableFuture<String>)previous;return existing;}
        List<Uri> uris=List.of(uri);AndroidAttachmentSource.retainSelections(uris);
        CompletableFuture<String> result=queueAttachmentRetry(session,request,List.of(id),()->{
            AttachmentBatchQueue.Selection replacement;
            try(AndroidAttachmentSource source=new AndroidAttachmentSource(applicationContext,uri)){replacement=new AttachmentBatchQueue.Selection(uri.toString(),source.generation(),source.name(),source.mime(),source.size(),false);}
            catch(IOException unavailable){if(unavailable instanceof LocalizedIOException&&((LocalizedIOException)unavailable).text.key.equals("attachmentTooLarge"))throw unavailable;throw new LocalizedIOException(UiText.of("attachmentRetrySelectAgain"),unavailable);}
            if(!attachmentRetryCurrent(session))throw new LocalizedIOException(UiText.of("notConnected"));
            return session.files().retryWithSelection(id,replacement,()->attachmentRetryCurrent(session));
        });result.whenComplete((value,failure)->releaseSelections(uris));return result;
    }
    public static boolean attachmentRetryNeedsSelection(Throwable failure){
        while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();
        return failure instanceof AttachmentBatchQueue.RetrySourceUnavailableException||failure instanceof LocalizedIOException&&((LocalizedIOException)failure).text.key.equals("attachmentRetrySelectAgain");
    }
    public void attachmentAction(String peer,String id,boolean outgoing,String action){
        if(action==null){fail(UiText.of("attachmentFailed"));return;}
        if(action.equals("retry")){if(outgoing)retryAttachment(peer,attachmentToken,id);else fail(UiText.of("attachmentFailed"));return;}
        if(action.equals("retryBatch")){if(outgoing)retryFailedAttachmentBatch(peer,attachmentToken,id);else fail(UiText.of("attachmentFailed"));return;}
        if(!Set.of("resume","pause","accept","reject","cancel").contains(action)){fail(UiText.of("attachmentFailed"));return;}
        if(transfers==null||!Objects.equals(peer,connectedPeerId)){fail(UiText.of("notConnected"));return;}
        ClientAttachmentTransfers selected=transfers;long token=attachmentToken;
        try{fileSelection.execute(()->{try{if(selected!=transfers||token!=attachmentToken)throw new IOException("Attachment session changed");
            CompletableFuture<Void> future=action.equals("resume")?selected.resume(id,outgoing):action.equals("pause")?selected.pause(id,outgoing):action.equals("accept")?selected.accept(id):action.equals("reject")?selected.reject(id):selected.cancel(id,outgoing);
            future.whenComplete((v,e)->{if(e!=null)main.post(()->{if(!destroyed)fail(attachmentError(e));});});
        }catch(Exception e){main.post(()->{if(!destroyed)fail(attachmentError(e));});}});}catch(RejectedExecutionException e){fail(UiText.of("attachmentFailed"));}
    }
    static UiText attachmentError(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();if(error instanceof AttachmentBatchQueue.RetrySourceUnavailableException)return UiText.of("attachmentRetrySelectAgain");if(error instanceof TransferStorageException)return UiText.of(((TransferStorageException)error).reason==TransferStorageException.Reason.QUOTA?"attachmentQuotaExceeded":"attachmentStorageFull");return error instanceof LocalizedIOException?((LocalizedIOException)error).text:UiText.of("attachmentFailed");}
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
    private static <T> CompletableFuture<T> failedAttachmentFuture(Throwable error){CompletableFuture<T> future=new CompletableFuture<>();future.completeExceptionally(error);return future;}
    public CompletableFuture<AttachmentLocations.Location> attachmentLocation(String peer,AttachmentRecord record){
        try{return AttachmentLocations.get(applicationContext).locate(peer,record);}catch(IOException error){return failedAttachmentFuture(error);}
    }
    public CompletableFuture<Void> exportAttachment(String peer,AttachmentRecord record,Uri uri,int pickerFlags){
        return attachmentPath(peer,record).thenCompose(path->{try{return AttachmentLocations.get(applicationContext).export(peer,record,uri,pickerFlags);}catch(IOException error){return failedAttachmentFuture(error);}});
    }
    public CompletableFuture<Void> exportAttachment(String peer,AttachmentRecord record,Uri uri){return exportAttachment(peer,record,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
    public CompletableFuture<Void> exportAttachment(String peer,AttachmentInfo info,Uri uri){
        try{return exportAttachment(peer,new AttachmentRecord(info,false,"received",info.size),uri);}catch(IOException error){return failedAttachmentFuture(error);}
    }
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
    public void clearConversation() {if(selectedId==null)return;String id=selectedId;if(remoteHello!=null&&Objects.equals(id,remoteHello.id))closeActive(UiText.of("connectionEnded"));
        attachmentsStopped=attachmentsStopped.handle((value,failure)->null).thenRunAsync(()->{
            AttachmentLocations locations=null;
            try{locations=AttachmentLocations.get(applicationContext);GalleryPublisher.get(applicationContext).clearPeer(id).get(30,TimeUnit.SECONDS);locations.clearPeer(id).get(30,TimeUnit.SECONDS);store.clear(id);AndroidAttachmentSource.releaseUnusedGrants(applicationContext);}
            catch(Exception failure){throw new CompletionException(failure);}
            finally{if(locations!=null)locations.finishClear(id);}
        },storage);
        attachmentsStopped.whenComplete((value,failure)->main.post(()->{if(!destroyed){if(failure!=null)fail(attachmentError(failure));refresh();}}));}
    private void refresh() {
        if(destroyed)return;
        if(refreshingHistory){refreshHistoryAgain=true;return;}
        refreshingHistory=true;refreshHistoryAgain=false;
        String selection = selectedId; long version = trust.version();
        db(() -> {
            List<ChatStore.Conversation> saved = store.conversations();
            List<ChatStore.TrustedDevice> devices = store.trustedDevices();
            Map<String,String> checked=new HashMap<>();if(identity!=null)for(ChatStore.TrustedDevice device:devices)if(store.isVerified(device.id,identity.publicKey(),device.publicKey))checked.put(device.id,device.publicKey);
            List<ChatStore.Message> history = selection == null ? new ArrayList<>() : store.messages(selection);
            main.post(() -> {
                try {
                    if (destroyed) return; conversations = saved;
                    if (version == trust.version()) {trustedDevices = devices;verifiedRoots=checked;}
                    if (Objects.equals(selection, selectedId)) messages = liveMessages(history,selection);
                    changed();
                }finally{
                    refreshingHistory=false;
                    if(!destroyed&&refreshHistoryAgain)refresh();
                }
            });
        }, null,()->{refreshingHistory=false;refreshHistoryAgain=false;});
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
        if (destroyed) return; stopAll(); destroyed = true; observer = null; lan.destroy(); bluetooth.destroy();direct.destroy();
        AndroidAvatars.get(applicationContext).unlisten(avatarListener);
        noiseIdentity.thenAccept(key->Arrays.fill(key,(byte)0));
        fileSelection.shutdownNow();attachmentsStopped.whenComplete((v,e)->{storage.execute(store::close);storage.shutdown();});
    }
}

package dev.ghost.nearbyim;

import android.content.*;
import android.os.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.storage.TrustPolicy;
import dev.ghost.nearbyim.transport.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

/** Mutable UI state stays on main; all database work runs on one ordered executor. */
public final class ChatController implements TransportListener {
    public interface Hooks { void onChanged(); }
    public final String localId;
    public String nickname, lanInfo = "尚未开启", bluetoothInfo = "尚未开启", status = "选择连接方式，找到身边的人", error = "";
    public boolean lanRunning, bluetoothRunning, lanSearching, bluetoothSearching, connecting, connected;
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
    private Frame remoteHello;
    private TrustPolicy.Authorization authorization;
    private int connectingMode;
    private String expectedPeerId, outgoingBluetoothAddress, lanReconnectId;
    private boolean rememberOutgoing, destroyed;

    public ChatController(Context context, Hooks hooks) {
        this.hooks = hooks; preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String id = preferences.getString("id", null);
        if (id == null) { id = UUID.randomUUID().toString(); preferences.edit().putString("id", id).apply(); }
        localId = id; nickname = preferences.getString("nickname", "附近的朋友");
        DeviceIdentity loaded = null;
        try { loaded = AndroidIdentity.load(); }
        catch (GeneralSecurityException | RuntimeException e) { error = "本机身份密钥不可用，暂时无法连接，请重启应用后重试"; }
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
        catch (IOException e) { fail("昵称需要 1～32 个字符，不能包含换行或控制字符"); return; }
        nickname = clean; preferences.edit().putString("nickname", clean).apply(); changed();
    }
    public void start(int mode) {
        if (identity == null) { fail("本机身份密钥不可用，暂时无法连接"); return; }
        error = "";
        if (mode == Peer.LAN) { if (lanRunning) return; lanRunning = true; lanInfo = "正在启动…"; lan.start(localId, nickname); }
        else if (mode == Peer.BLUETOOTH) { if (bluetoothRunning) return; bluetoothRunning = true; bluetoothInfo = "正在启动…"; bluetooth.start(); }
        else { fail("连接方式无效"); return; }
        changed();
    }
    public void stop(int mode) {
        if (active != null && sessionMode == mode) closeActive("连接已结束");
        if (connecting && connectingMode == mode) cancelOutgoing();
        if (mode == Peer.LAN) { lan.stop(); lanRunning = false; lanSearching = false; lanInfo = "尚未开启"; }
        else { bluetooth.stop(); bluetoothRunning = false; bluetoothSearching = false; bluetoothInfo = "尚未开启"; }
        peers.entrySet().removeIf(entry -> entry.getValue().mode == mode); changed();
    }
    public void stopAll() { stop(Peer.LAN); stop(Peer.BLUETOOTH); }
    public void search(int mode) {
        if (!running(mode)) { fail("请先开启接收"); return; }
        if (connecting) { fail("正在连接，请稍候"); return; }
        error = ""; peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
        if (mode == Peer.LAN) lan.discover(); else bluetooth.scan(); changed();
    }
    public void stopSearch(int mode) {
        if (mode == Peer.LAN) {
            if (lanReconnectId != null) { cancelOutgoing(); status = "已停止连接尝试"; }
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
        catch (IllegalArgumentException e) { fail(e.getMessage()); }
    }
    public void reconnect(String peerId) {
        if (connected && Objects.equals(peerId, connectedPeerId)) return;
        ChatStore.TrustedDevice device = trustedDevice(peerId);
        if (device == null) { fail("此设备尚未被记住，请去附近重新连接"); return; }
        if (device.mode == Peer.BLUETOOTH && device.bluetoothAddress == null) { fail("没有可用的蓝牙地址，请去附近重新查找"); return; }
        if (!beginConnect(device.mode, device.id, true, device.bluetoothAddress)) return;
        if (device.mode == Peer.BLUETOOTH) { bluetooth.connect(device.bluetoothAddress); return; }
        // Resolve a fresh advertised UUID. Never use a persisted IP as identity.
        lanReconnectId = device.id; status = "正在查找已记住的设备…";
        peers.entrySet().removeIf(entry -> entry.getValue().mode == Peer.LAN);
        reconnectTimeout = () -> {
            if (lanReconnectId == null) return;
            cancelOutgoing(); lan.stopSearch(); status = "暂时无法连接";
            fail("暂时找不到对方，请确认同一网络、对方已开启接收后重试，或去附近查找");
        };
        main.postDelayed(reconnectTimeout, 12000); lan.discover(); changed();
    }
    private boolean beginConnect(int mode, String expected, boolean remember, String bluetoothAddress) {
        if (active != null || connecting) { fail("请先结束当前连接"); return false; }
        if (identity == null) { fail("本机身份密钥不可用，暂时无法连接"); return false; }
        if (!running(mode)) { fail("请先开启相应连接方式的接收"); return false; }
        expectedPeerId = expected; rememberOutgoing = remember; outgoingBluetoothAddress = bluetoothAddress;
        connecting = true; connectingMode = mode; sessionMode = mode; error = ""; status = "正在连接…"; changed(); return true;
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
            if (!connected) closeActive("信任已取消，请重新连接并同意");
        }
        if (connecting && peerId.equals(expectedPeerId)) {
            if (active != null) closeActive("信任已取消，连接尝试已停止");
            else { cancelOutgoing(); status = "信任已取消，连接尝试已停止"; }
        }
        db(() -> store.revokeTrust(peerId), this::refresh); changed();
    }
    public void onPeer(Peer peer) {
        if (destroyed) return;
        peers.put(peer.key, peer);
        if (connecting && lanReconnectId != null && lanReconnectId.equals(peer.peerId) && peer.mode == Peer.LAN) {
            clearReconnectWait(); lan.stopSearch(); status = "正在连接…"; lan.connect(peer.host, peer.port);
        }
        changed();
    }
    public void onLost(String key) { peers.remove(key); changed(); }
    public void onSearching(int mode, boolean searching) { if (mode == Peer.LAN) lanSearching = searching; else bluetoothSearching = searching; changed(); }
    public void onSearchStopFailed(int mode, String message) { fail(message); }
    public void onListening(int mode, String detail) { if (mode == Peer.LAN) lanInfo = detail; else bluetoothInfo = detail; changed(); }
    public void onError(int mode, String message, boolean fatal) {
        if (lanReconnectId != null && mode == Peer.LAN) { cancelOutgoing(); status = "暂时无法连接"; }
        if (fatal) {
            if (connecting && connectingMode == mode) cancelOutgoing();
            if (mode == Peer.LAN) { lanRunning = false; lanSearching = false; lanInfo = "尚未开启"; } else { bluetoothRunning = false; bluetoothSearching = false; bluetoothInfo = "尚未开启"; }
            peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
            if (active != null && sessionMode == mode) closeActive(message);
        }
        fail(message);
    }
    public void onConnectFailed(int mode, String message) {
        if (!connecting || connectingMode != mode || active != null) return;
        cancelOutgoing(); status = "暂时无法连接"; fail(message);
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
        status = "已建立链路，正在验证身份…"; error = "";
        final FramedSession[] reference = new FramedSession[1];
        reference[0] = new FramedSession(connection, localId, nickname, identity, new FramedSession.Listener() {
            public void onHello(Frame hello) { main.post(() -> {
                if (active != reference[0]) return;
                String publicKey = reference[0].remotePublicKey();
                if (publicKey == null || (expected != null && (!expected.equals(hello.id) || attemptRevision != trust.peerRevision(expected)))) {
                    closeActive("找到的设备身份不符，请去附近重新查找"); fail("设备身份验证不符，未授权此连接"); return;
                }
                remoteHello = hello;
                final long revision = trust.peerRevision(hello.id);
                final ChatStore.TrustedDevice[] pin = new ChatStore.TrustedDevice[1];
                db(() -> pin[0] = store.trusted(hello.id), () -> {
                    if (active != reference[0] || revision != trust.peerRevision(hello.id)) return;
                    authorization = trust.begin(hello.id, publicKey, pin[0] == null ? null : pin[0].publicKey, !incoming, remember);
                    if (authorization.decision == TrustPolicy.Decision.IDENTITY_CHANGED) {
                        closeActive("设备身份已变化，连接已拒绝");
                        fail("此设备的身份密钥已变化。如确认需要重新认识，请先在设置中取消该设备信任，再重新连接");
                    } else if (authorization.decision == TrustPolicy.Decision.APPROVE) {
                        status = incoming ? "正在完成连接…" : "等待对方同意聊天…"; reference[0].approve(); changed();
                    } else {
                        approvalId = UUID.randomUUID().toString(); approvalName = hello.body; status = "等待同意聊天"; changed();
                    }
                });
            }); }
            public void onReady() { main.post(() -> {
                if (active != reference[0] || remoteHello == null || !reference[0].isReady() || !trust.ready(authorization)) return;
                connected = true; connecting = false; connectedPeerId = remoteHello.id;
                approvalId = null; approvalName = null; expectedPeerId = null; outgoingBluetoothAddress = null;
                Frame hello = remoteHello; TrustPolicy.Authorization grant = authorization;
                if (!incoming) {
                    if (!Objects.equals(selectedId, hello.id)) messages = new ArrayList<>();
                    selectedId = hello.id; selectedName = hello.body;
                }
                status = "已连接 · " + connection.label(); changed();
                db(() -> {
                    trust.persist(grant, () -> store.remember(hello.id, hello.body, grant.publicKey, mode, socketAddress));
                    store.touch(hello.id, hello.body);
                }, ChatController.this::refresh);
            }); }
            public void onText(Frame frame) { main.post(() -> {
                if (active != reference[0] || remoteHello == null || !reference[0].isReady()) return;
                String id = remoteHello.id, name = remoteHello.body;
                db(() -> store.save(id, name, frame, false), () -> { reference[0].acknowledge(frame.id); refresh(); });
            }); }
            public void onAck(String id) { main.post(() -> {
                if (active != reference[0] || remoteHello == null) return;
                String peerId = remoteHello.id; db(() -> store.delivered(peerId, id), ChatController.this::refresh);
            }); }
            public void onClosed(String reason) { main.post(() -> releaseSession(reference[0], reason)); }
        });
        active = reference[0]; active.start(); changed();
    }
    public void approve(String token) { approve(token, true); }
    public void approve(String token, boolean remember) {
        if (active == null || approvalId == null || !Objects.equals(token, approvalId) || remoteHello == null) return;
        if (!trust.approve(authorization, remember)) return;
        approvalId = null; approvalName = null; status = "等待双方完成连接…";
        active.approve(); changed();
    }
    public void reject(String token) { if (active != null && approvalId != null && Objects.equals(token, approvalId)) closeActive("连接已拒绝"); }
    private void releaseSession(FramedSession session, String reason) {
        if (active != session) return;
        String peerId = remoteHello == null ? null : remoteHello.id;
        trust.cancel(authorization); authorization = null; active = null; remoteHello = null;
        connected = false; connectedPeerId = null; approvalId = null; approvalName = null;
        cancelOutgoing(); status = reason;
        if (peerId != null) db(() -> store.uncertain(peerId), this::refresh);
        changed();
    }
    private void closeActive(String reason) {
        FramedSession session = active;
        if (session != null) { releaseSession(session, reason); session.close(reason); }
    }
    private static void close(StreamConnection connection) { try { connection.close(); } catch (IOException ignored) {} }
    public void disconnect() { closeActive("连接已结束"); cancelOutgoing(); status = "连接已结束"; changed(); }
    public boolean canSend() { return !savingMessage && connected && active != null && active.isReady() && remoteHello != null && Objects.equals(selectedId, connectedPeerId); }
    public boolean send(String body) {
        if (!canSend()) { fail("当前会话未连接"); return false; }
        Frame frame = new Frame(Frame.TEXT, UUID.randomUUID().toString(), body, System.currentTimeMillis());
        try { Protocol.write(new ByteArrayOutputStream(), frame); } catch (IOException e) { fail("消息不能为空，且不能超过 8192 个 UTF-8 字节"); return false; }
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
    public void clearConversation() { if (selectedId != null) { String id = selectedId; db(() -> store.clear(id), this::refresh); } }
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
            catch (RuntimeException e) { main.post(() -> {
                if (!destroyed) { if (failure != null) failure.run(); closeActive("本地记录保存失败，连接已停止"); fail("本地记录读写失败，请检查手机可用空间"); }
            }); }
        });
    }
    private void fail(String message) { error = message; changed(); }
    public void destroy() {
        if (destroyed) return; stopAll(); destroyed = true; observer = null; lan.destroy(); bluetooth.destroy();
        storage.execute(store::close); storage.shutdown();
    }
}

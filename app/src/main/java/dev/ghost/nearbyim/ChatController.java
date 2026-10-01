package dev.ghost.nearbyim;

import android.content.*;
import android.os.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.transport.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Mutable UI state stays on main; all database work runs on one ordered executor. */
public final class ChatController implements TransportListener {
    public interface Hooks { void onChanged(); }
    public final String localId;
    public String nickname, lanInfo = "尚未开启", bluetoothInfo = "尚未开启", status = "选择连接方式，找到身边的人", error = "";
    public boolean lanRunning, bluetoothRunning, connecting, connected;
    public int sessionMode;
    public String selectedId, selectedName, approvalId, approvalName;
    public String savedMessageId, savedMessagePeer, savedMessageBody;
    public boolean savingMessage;
    public final LinkedHashMap<String, Peer> peers = new LinkedHashMap<>();
    public List<ChatStore.Message> messages = new ArrayList<>();
    public List<ChatStore.Conversation> conversations = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final ChatStore store;
    private final LanTransport lan;
    private final BluetoothTransport bluetooth;
    private final Hooks hooks;
    private Runnable observer;
    private FramedSession active;
    private Frame remoteHello;
    private int connectingMode;
    private boolean destroyed;

    public ChatController(Context context, Hooks hooks) {
        this.hooks = hooks; preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String id = preferences.getString("id", null);
        if (id == null) { id = UUID.randomUUID().toString(); preferences.edit().putString("id", id).apply(); }
        localId = id; nickname = preferences.getString("nickname", "附近的朋友");
        store = new ChatStore(context); lan = new LanTransport(context, this); bluetooth = new BluetoothTransport(context, this);
        db(() -> store.recoverPending(), null); refresh();
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
        error = "";
        if (mode == Peer.LAN) { if (lanRunning) return; lanRunning = true; lanInfo = "正在启动…"; lan.start(localId, nickname); }
        else { if (bluetoothRunning) return; bluetoothRunning = true; bluetoothInfo = "正在启动…"; bluetooth.start(); }
        changed();
    }
    public void stop(int mode) {
        if (active != null && sessionMode == mode) active.close("连接已结束");
        if (mode == Peer.LAN) { lan.stop(); lanRunning = false; lanInfo = "尚未开启"; }
        else { bluetooth.stop(); bluetoothRunning = false; bluetoothInfo = "尚未开启"; }
        peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
        if (connecting && connectingMode == mode) connecting = false;
        changed();
    }
    public void stopAll() { stop(Peer.LAN); stop(Peer.BLUETOOTH); }
    public void search(int mode) {
        if ((mode == Peer.LAN && !lanRunning) || (mode == Peer.BLUETOOTH && !bluetoothRunning)) { fail("请先开启接收"); return; }
        if (connecting) { fail("正在连接，请稍候"); return; }
        error = ""; peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
        if (mode == Peer.LAN) lan.discover(); else bluetooth.scan(); changed();
    }
    public void connect(Peer peer) {
        if (!beginConnect(peer.mode)) return;
        if (peer.mode == Peer.LAN) lan.connect(peer.host, peer.port); else bluetooth.connect(peer.bluetoothAddress);
    }
    public void connectAddress(String address) {
        try { LocalEndpoint endpoint = LocalEndpoint.parse(address); if (beginConnect(Peer.LAN)) lan.connect(endpoint.address, endpoint.port); }
        catch (IllegalArgumentException e) { fail(e.getMessage()); }
    }
    private boolean beginConnect(int mode) {
        if (active != null || connecting) { fail("请先结束当前连接"); return false; }
        if ((mode == Peer.LAN && !lanRunning) || (mode == Peer.BLUETOOTH && !bluetoothRunning)) { fail("请先开启接收"); return false; }
        connecting = true; connectingMode = mode; error = ""; status = "正在连接…"; changed(); return true;
    }
    public void onPeer(Peer peer) { peers.put(peer.key, peer); changed(); }
    public void onLost(String key) { peers.remove(key); changed(); }
    public void onListening(int mode, String detail) { if (mode == Peer.LAN) lanInfo = detail; else bluetoothInfo = detail; changed(); }
    public void onError(int mode, String message, boolean fatal) {
        if (fatal) {
            if (connectingMode == mode) connecting = false;
            if (mode == Peer.LAN) { lanRunning = false; lanInfo = "尚未开启"; } else { bluetoothRunning = false; bluetoothInfo = "尚未开启"; }
            peers.entrySet().removeIf(entry -> entry.getValue().mode == mode);
            if (active != null && sessionMode == mode) active.close(message);
        }
        fail(message);
    }
    public void onConnectFailed(int mode, String message) {
        if (connecting && connectingMode == mode) { connecting = false; status = "连接未成功，请重试"; }
        fail(message);
    }
    public void onConnection(int mode, StreamConnection connection, boolean incoming) {
        if (active != null || (connecting && incoming)) { try { connection.close(); } catch (IOException ignored) {} return; }
        connecting = false; connected = false; sessionMode = mode; remoteHello = null;
        status = "已建立链路，正在确认身份…"; error = "";
        final FramedSession[] reference = new FramedSession[1];
        reference[0] = new FramedSession(connection, localId, nickname, new FramedSession.Listener() {
            public void onHello(Frame hello) { main.post(() -> {
                if (active != reference[0]) return;
                remoteHello = hello; approvalId = UUID.randomUUID().toString(); approvalName = hello.body;
                status = "等待双方同意聊天"; changed();
            }); }
            public void onReady() { main.post(() -> { if (active == reference[0]) { connected = true; status = "已连接 · " + connection.label(); changed(); } }); }
            public void onText(Frame frame) { main.post(() -> {
                if (active != reference[0] || remoteHello == null) return;
                String id = remoteHello.id, name = remoteHello.body;
                db(() -> store.save(id, name, frame, false), () -> { reference[0].acknowledge(frame.id); refresh(); });
            }); }
            public void onAck(String id) { main.post(() -> {
                if (active != reference[0] || remoteHello == null) return;
                String peerId = remoteHello.id; db(() -> store.delivered(peerId, id), ChatController.this::refresh);
            }); }
            public void onClosed(String reason) { main.post(() -> {
                if (active != reference[0]) return;
                String peerId = remoteHello == null ? null : remoteHello.id;
                active = null; remoteHello = null; connected = false; approvalId = null; approvalName = null;
                status = reason; if (peerId != null) db(() -> store.uncertain(peerId), ChatController.this::refresh);
                changed();
            }); }
        });
        active = reference[0]; active.start(); changed();
    }
    public void approve(String token) {
        if (active == null || !Objects.equals(token, approvalId) || remoteHello == null) return;
        FramedSession session = active; Frame hello = remoteHello;
        approvalId = null; approvalName = null; selectedId = hello.id; selectedName = hello.body; messages = new ArrayList<>();
        db(() -> store.touch(hello.id, hello.body), () -> { if (active == session) session.approve(); refresh(); }); changed();
    }
    public void reject(String token) { if (active != null && Objects.equals(token, approvalId)) active.close("连接已拒绝"); }
    public void disconnect() { if (active != null) active.close("连接已结束"); }
    public boolean canSend() { return !savingMessage && active != null && active.isReady() && remoteHello != null && Objects.equals(selectedId, remoteHello.id); }
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
        selectedId = conversation.id; selectedName = conversation.name; messages = new ArrayList<>(); refresh(); changed();
    }
    public void clearConversation() { if (selectedId != null) { String id = selectedId; db(() -> store.clear(id), this::refresh); } }
    private void refresh() {
        String selection = selectedId;
        db(() -> {
            List<ChatStore.Conversation> saved = store.conversations();
            List<ChatStore.Message> history = selection == null ? new ArrayList<>() : store.messages(selection);
            main.post(() -> { if (destroyed) return; conversations = saved; if (Objects.equals(selection, selectedId)) messages = history; changed(); });
        }, null);
    }
    private void db(Runnable work, Runnable success) {
        db(work, success, null);
    }
    private void db(Runnable work, Runnable success, Runnable failure) {
        if (destroyed) return;
        storage.execute(() -> {
            try { work.run(); if (success != null) main.post(() -> { if (!destroyed) success.run(); }); }
            catch (RuntimeException e) { main.post(() -> { if (!destroyed) { if (failure != null) failure.run(); if (active != null) active.close("本地记录保存失败，连接已停止"); fail("本地记录读写失败，请检查手机可用空间"); } }); }
        });
    }
    private void fail(String message) { error = message; changed(); }
    public void destroy() {
        if (destroyed) return; stopAll(); destroyed = true; observer = null; lan.destroy(); bluetooth.destroy();
        storage.execute(store::close); storage.shutdown();
    }
}

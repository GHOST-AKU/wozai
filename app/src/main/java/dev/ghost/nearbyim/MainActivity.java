package dev.ghost.nearbyim;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import dev.ghost.nearbyim.transport.Peer;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private ChatService service;
    private ChatController controller;
    private boolean bound;
    private int mode = Peer.LAN, page;
    private int background, surface, ink, muted, accent, accentInk, tonal, line;
    private LinearLayout root, discoveryPage, chatPage, historyPage, peersList, historyList, bubbles;
    private TextView subtitle, status, connectionInfo, modeHint, chatName, chatStatus, errorView;
    private Button lanTab, bluetoothTab, startButton, searchButton, discoverableButton, manualButton, disconnectButton, sendButton;
    private Button[] pages;
    private EditText composer;
    private ScrollView messageScroll;
    private List<ChatStore.Message> renderedMessages;
    private AlertDialog approvalDialog;
    private String shownApproval;
    private String restoredSelectedId, restoredSelectedName, composerPeer, handledSavedMessage;
    private final Map<String, String> drafts = new HashMap<>();
    private static final int PERMISSION_STAGE = 1, ENABLE_STAGE = 2, BIND_STAGE = 3;
    private static final class UiAction implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        static final int START = 1, SEARCH = 2, DISCOVERABLE = 3, PEER = 4, ADDRESS = 5;
        final int type, mode;
        final Peer peer;
        final String address;
        UiAction(int type, int mode) { this(type, mode, null, null); }
        UiAction(int type, int mode, Peer peer, String address) { this.type = type; this.mode = mode; this.peer = peer; this.address = address; }
    }
    private UiAction pendingAction;
    private int pendingStage;

    private final ServiceConnection binding = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ChatService.LocalBinder) binder).service(); controller = service.controller;
            if (controller.selectedId == null && restoredSelectedId != null)
                controller.selectConversation(new ChatStore.Conversation(restoredSelectedId, restoredSelectedName, 0));
            controller.observe(MainActivity.this::render);
            resumePending();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; controller = null; render(); }
    };
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            mode = saved.getInt("mode", Peer.LAN); page = saved.getInt("page", 0);
            pendingAction = (UiAction) saved.getSerializable("pendingAction"); pendingStage = saved.getInt("pendingStage", 0);
            restoredSelectedId = saved.getString("selectedId"); restoredSelectedName = saved.getString("selectedName");
            composerPeer = saved.getString("composerPeer"); handledSavedMessage = saved.getString("handledSavedMessage");
            Bundle savedDrafts = saved.getBundle("drafts");
            if (savedDrafts != null) for (String key : savedDrafts.keySet()) drafts.put(key, savedDrafts.getString(key, ""));
        }
        palette(); buildUi();
        if (saved != null) composer.setText(saved.getString("draft", ""));
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::handleBack);
        render();
    }
    protected void onStart() { super.onStart(); bound = bindService(new Intent(this, ChatService.class), binding, Context.BIND_AUTO_CREATE); }
    protected void onStop() {
        if (controller != null) { restoredSelectedId = controller.selectedId; restoredSelectedName = controller.selectedName; controller.observe(null); }
        if (bound) { unbindService(binding); bound = false; } controller = null; service = null;
        if (approvalDialog != null) { approvalDialog.setOnCancelListener(null); approvalDialog.dismiss(); approvalDialog = null; shownApproval = null; }
        super.onStop();
    }
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("mode", mode); state.putInt("page", page); state.putString("draft", composer.getText().toString());
        state.putString("selectedId", controller == null ? restoredSelectedId : controller.selectedId);
        state.putString("selectedName", controller == null ? restoredSelectedName : controller.selectedName);
        state.putString("composerPeer", composerPeer); state.putString("handledSavedMessage", handledSavedMessage);
        if (composerPeer != null) drafts.put(composerPeer, composer.getText().toString());
        Bundle savedDrafts = new Bundle(); for (Map.Entry<String, String> entry : drafts.entrySet()) savedDrafts.putString(entry.getKey(), entry.getValue()); state.putBundle("drafts", savedDrafts);
        state.putSerializable("pendingAction", pendingAction); state.putInt("pendingStage", pendingStage); super.onSaveInstanceState(state);
    }
    // Platform OnBackInvokedDispatcher is registered above on API 33+;
    // this override remains only for the API 26-32 fallback.
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation") public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack(); else super.onBackPressed();
    }
    private void handleBack() { if (page != 0) { page = 0; render(); } else finish(); }
    private void palette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#121916" : "#F5F8F5"); surface = Color.parseColor(dark ? "#1E2823" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#E1EBE4" : "#1B2922"); muted = Color.parseColor(dark ? "#ABBCB0" : "#617368");
        accent = Color.parseColor(dark ? "#A1D2BE" : "#356758"); accentInk = Color.parseColor(dark ? "#103B2E" : "#FFFFFF");
        tonal = Color.parseColor(dark ? "#30483C" : "#DCEDE3"); line = Color.parseColor(dark ? "#38493F" : "#DCE5DE");
        if (Build.VERSION.SDK_INT >= 31) {
            accent = getResources().getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600, getTheme());
            tonal = getResources().getColor(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_100, getTheme());
        }
    }
    private void buildUi() {
        root = vertical(); root.setBackgroundColor(background); root.setPadding(dp(18), dp(12), dp(18), dp(8)); setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else {
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int appearance = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | appearance);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime()); top = bars.top; bottom = Math.max(bars.bottom, ime.bottom);
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            view.setPadding(dp(18), dp(12) + top, dp(18), dp(8) + bottom); return insets;
        });
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView emblem = label("邻", 24, accentInk); emblem.setTypeface(null, Typeface.BOLD); emblem.setGravity(Gravity.CENTER); emblem.setBackground(shape(accent, 18));
        header.addView(emblem, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout brand = vertical(); brand.setPadding(dp(12), 0, 0, 0); brand.addView(label("我在", 26, ink)); subtitle = label("无需账号，连接身边", 12, muted); brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = button("设置", false); menu.setOnClickListener(v -> settings()); header.addView(menu); root.addView(header);
        LinearLayout nav = horizontal(); nav.setPadding(0, dp(16), 0, dp(12)); pages = new Button[3]; String[] labels = {"发现", "聊天", "记录"};
        for (int i = 0; i < 3; i++) { final int selected = i; pages[i] = button(labels[i], false); pages[i].setOnClickListener(v -> { page = selected; render(); }); nav.addView(pages[i], weighted()); }
        root.addView(nav);
        status = label("正在准备…", 13, muted); status.setPadding(dp(12), dp(10), dp(12), dp(10)); status.setBackground(shape(tonal, 14)); root.addView(status);
        errorView = label("", 13, ink); errorView.setPadding(dp(8), dp(8), dp(8), dp(8)); root.addView(errorView);
        discoveryPage = vertical(); chatPage = vertical(); historyPage = vertical();
        root.addView(discoveryPage, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(chatPage, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(historyPage, new LinearLayout.LayoutParams(-1, 0, 1));
        buildDiscovery(); buildChat(); buildHistory();
    }
    private void buildDiscovery() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout content = vertical(); content.setPadding(0, dp(14), 0, dp(16)); scroll.addView(content); discoveryPage.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout modes = horizontal(); lanTab = button("局域网", true); bluetoothTab = button("蓝牙", false);
        lanTab.setOnClickListener(v -> { mode = Peer.LAN; render(); }); bluetoothTab.setOnClickListener(v -> { mode = Peer.BLUETOOTH; render(); });
        modes.addView(lanTab, weighted()); modes.addView(bluetoothTab, weighted()); content.addView(modes);
        LinearLayout card = card(); card.addView(label("我的连接", 15, ink)); connectionInfo = label("尚未开启", 16, ink); connectionInfo.setTextIsSelectable(true); card.addView(connectionInfo);
        LinearLayout actions = horizontal(); startButton = button("开启接收", true); searchButton = button("搜索设备", false);
        startButton.setOnClickListener(v -> {
            if (controller == null) return;
            boolean running = mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning;
            if (running) controller.stop(mode); else withPermissions(new UiAction(UiAction.START, mode));
        });
        searchButton.setOnClickListener(v -> withPermissions(new UiAction(UiAction.SEARCH, mode)));
        actions.addView(startButton, weighted()); actions.addView(searchButton, weighted()); card.addView(actions);
        manualButton = button("通过 IP 地址连接", false); manualButton.setOnClickListener(v -> manualConnect()); card.addView(manualButton);
        discoverableButton = button("允许被发现 · 120 秒", false); discoverableButton.setOnClickListener(v -> withPermissions(new UiAction(UiAction.DISCOVERABLE, Peer.BLUETOOTH))); card.addView(discoverableButton);
        modeHint = label("", 12, muted); modeHint.setPadding(0, dp(8), 0, 0); card.addView(modeHint); content.addView(card, spaced());
        content.addView(label("附近设备", 20, ink)); peersList = vertical(); content.addView(peersList);
    }
    private void buildChat() {
        LinearLayout header = horizontal(); header.setPadding(0, dp(12), 0, dp(8)); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = vertical(); chatName = label("开始一段聊天", 20, ink); chatStatus = label("连接设备后即可发送", 12, muted); names.addView(chatName); names.addView(chatStatus);
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); disconnectButton = button("断开", false); disconnectButton.setOnClickListener(v -> { if (controller != null) controller.disconnect(); }); header.addView(disconnectButton); chatPage.addView(header);
        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); bubbles = vertical(); bubbles.setPadding(0, dp(8), 0, dp(12)); messageScroll.addView(bubbles); chatPage.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout inputRow = horizontal(); inputRow.setGravity(Gravity.BOTTOM); inputRow.setPadding(0, dp(8), 0, 0);
        composer = new EditText(this); composer.setTextColor(ink); composer.setHintTextColor(muted); composer.setTextSize(16); composer.setHint("说点什么…"); composer.setBackground(shape(surface, 22));
        composer.setPadding(dp(16), dp(12), dp(16), dp(12)); composer.setMinHeight(dp(48)); composer.setMaxLines(4);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        inputRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1)); sendButton = button("发送", true);
        sendButton.setOnClickListener(v -> { if (controller != null) controller.send(composer.getText().toString()); });
        inputRow.addView(sendButton); chatPage.addView(inputRow);
    }
    private void buildHistory() {
        TextView title = label("本机聊天记录", 20, ink); title.setPadding(0, dp(16), 0, dp(8)); historyPage.addView(title);
        historyPage.addView(label("保存在这部手机上 · 每个会话显示最近 200 条", 12, muted));
        ScrollView scroll = new ScrollView(this); historyList = vertical(); scroll.addView(historyList); historyPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void render() {
        if (root == null) return;
        for (int i = 0; i < pages.length; i++) style(pages[i], page == i);
        discoveryPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE); chatPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE); historyPage.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        style(lanTab, mode == Peer.LAN); style(bluetoothTab, mode == Peer.BLUETOOTH);
        manualButton.setVisibility(mode == Peer.LAN ? View.VISIBLE : View.GONE); discoverableButton.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE);
        modeHint.setText(mode == Peer.LAN ? "两部设备连接同一 Wi-Fi，或一部开启热点、另一部加入。局域网消息为明文，请在可信网络使用。" : "双方安装我在并开启接收。对方允许被发现后再搜索；首次连接可能需要系统配对。Android 11 及以前搜索还需要打开定位。");
        boolean attached = controller != null; startButton.setEnabled(attached); searchButton.setEnabled(attached); manualButton.setEnabled(attached); discoverableButton.setEnabled(attached);
        if (!attached) { status.setText("正在连接聊天服务…"); sendButton.setEnabled(false); return; }
        subtitle.setText(getString(R.string.my_nickname, controller.nickname)); status.setText(controller.status);
        errorView.setText(controller.error); errorView.setVisibility(controller.error.isEmpty() ? View.GONE : View.VISIBLE);
        connectionInfo.setText(mode == Peer.LAN ? controller.lanInfo : controller.bluetoothInfo);
        boolean running = mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning;
        startButton.setText(running ? "停止接收" : "开启接收"); searchButton.setEnabled(!controller.connecting);
        peersList.removeAllViews(); int count = 0;
        for (Peer peer : controller.peers.values()) if (peer.mode == mode && count++ < 50) {
            LinearLayout card = card(); card.addView(label(peer.name, 17, ink)); card.addView(label(peer.detail, 12, muted));
            Button connect = button("发起聊天", false); connect.setEnabled(!controller.hasSession() && !controller.connecting);
            connect.setOnClickListener(v -> withPermissions(new UiAction(UiAction.PEER, peer.mode, peer, null))); card.addView(connect); peersList.addView(card, spaced());
        }
        if (count == 0) empty(peersList, "◌", "还没有发现设备", "让对方开启接收，然后搜索。也可以用 IP 地址直连。");
        chatName.setText(controller.selectedName == null ? "开始一段聊天" : controller.selectedName);
        if (!Objects.equals(composerPeer, controller.selectedId)) {
            if (composerPeer != null) drafts.put(composerPeer, composer.getText().toString());
            composerPeer = controller.selectedId; composer.setText(composerPeer == null ? "" : drafts.getOrDefault(composerPeer, ""));
        }
        if (controller.savedMessageId != null && !Objects.equals(handledSavedMessage, controller.savedMessageId)) {
            if (Objects.equals(drafts.get(controller.savedMessagePeer), controller.savedMessageBody)) drafts.put(controller.savedMessagePeer, "");
            if (Objects.equals(composerPeer, controller.savedMessagePeer) && composer.getText().toString().equals(controller.savedMessageBody)) composer.setText("");
            handledSavedMessage = controller.savedMessageId;
        }
        chatStatus.setText(controller.canSend() ? "在线 · " + (controller.sessionMode == Peer.LAN ? "局域网" : "蓝牙") : "未连接 · 可查看本机记录");
        disconnectButton.setVisibility(controller.hasSession() ? View.VISIBLE : View.GONE); sendButton.setEnabled(controller.canSend()); composer.setEnabled(controller.canSend());
        if (renderedMessages != controller.messages) {
            boolean atBottom = bubbles.getHeight() - messageScroll.getHeight() - messageScroll.getScrollY() < dp(100);
            int previousScroll = messageScroll.getScrollY(); renderedMessages = controller.messages; bubbles.removeAllViews();
            if (controller.messages.isEmpty()) empty(bubbles, "☺", controller.selectedId == null ? "先找到一位朋友" : "还没有消息", "连接后，发送你的第一句问候。 (・ω・)ノ");
            else for (ChatStore.Message message : controller.messages) addBubble(message);
            messageScroll.post(() -> { if (atBottom) messageScroll.fullScroll(View.FOCUS_DOWN); else messageScroll.scrollTo(0, previousScroll); });
        }
        historyList.removeAllViews();
        for (ChatStore.Conversation conversation : controller.conversations) {
            LinearLayout card = card(); card.addView(label(conversation.name, 17, ink));
            card.addView(label(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(conversation.time)), 12, muted));
            Button open = button("查看记录", false); open.setOnClickListener(v -> { page = 1; renderedMessages = null; controller.selectConversation(conversation); }); card.addView(open); historyList.addView(card, spaced());
        }
        if (controller.conversations.isEmpty()) empty(historyList, "⌁", "记录会留在这里", "连接过的人和收到的消息，仅保存在本机。");
        showApproval();
    }
    private void addBubble(ChatStore.Message message) {
        LinearLayout row = horizontal(); row.setGravity(message.outgoing ? Gravity.END : Gravity.START); row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout bubble = vertical(); bubble.setBackground(shape(message.outgoing ? tonal : surface, 20)); bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView body = label(message.text, 16, ink); body.setMaxWidth(Math.max(dp(140), getResources().getDisplayMetrics().widthPixels - dp(100))); body.setTextIsSelectable(true); bubble.addView(body);
        TextView meta = label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.time)) + (message.outgoing ? " · " + message.state : ""), 10, muted); meta.setPadding(0, dp(5), 0, 0); bubble.addView(meta);
        row.addView(bubble, new LinearLayout.LayoutParams(-2, -2)); bubbles.addView(row);
    }
    private void showApproval() {
        String token = controller.approvalId;
        if (approvalDialog != null && !Objects.equals(token, shownApproval)) { approvalDialog.setOnCancelListener(null); approvalDialog.dismiss(); approvalDialog = null; shownApproval = null; }
        if (token == null || approvalDialog != null || isFinishing()) return;
        shownApproval = token;
        String detail = controller.sessionMode == Peer.LAN ? "局域网消息为明文传输。" : "蓝牙连接由系统配对与加密。";
        approvalDialog = new AlertDialog.Builder(this).setTitle("允许与「" + controller.approvalName + "」聊天？")
                .setMessage("昵称由对方设备自行声明，请当面确认。\n\n" + detail + "双方同意后才能收发消息。")
                .setPositiveButton("允许聊天", (dialog, which) -> { if (controller != null) { page = 1; renderedMessages = null; controller.approve(token); } })
                .setNegativeButton("拒绝", (dialog, which) -> { if (controller != null) controller.reject(token); }).create();
        approvalDialog.setOnCancelListener(dialog -> { if (controller != null) controller.reject(token); }); approvalDialog.show();
    }
    private void startTransport(int chosen) {
        if (controller == null) return;
        startForegroundService(new Intent(this, ChatService.class).setAction(ChatService.START)); controller.start(chosen);
    }
    private void manualConnect() {
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("192.168.1.20:54321"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = vertical(); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(address);
        new AlertDialog.Builder(this).setTitle("通过 IP 地址连接").setMessage("输入对方「我的连接」中显示的完整地址，包括端口。").setView(box)
                .setPositiveButton("连接", (dialog, which) -> withPermissions(new UiAction(UiAction.ADDRESS, Peer.LAN, null, address.getText().toString())))
                .setNegativeButton("取消", null).show();
    }
    private void withPermissions(UiAction action) {
        int chosen = action.mode;
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        if (chosen == Peer.BLUETOOTH && !controller.bluetoothAvailable()) { toast("这部设备不支持蓝牙，可使用局域网"); return; }
        List<String> required = requiredPermissions(chosen); List<String> request = new ArrayList<>();
        for (String permission : required) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) request.add(permission);
        SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsAsked", false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            request.add(Manifest.permission.POST_NOTIFICATIONS); prefs.edit().putBoolean("notificationsAsked", true).apply();
        }
        if (!request.isEmpty()) { pendingAction = action; pendingStage = PERMISSION_STAGE; requestPermissions(request.toArray(new String[0]), 7); }
        else ensureEnabled(action);
    }
    private List<String> requiredPermissions(int chosen) {
        if (chosen != Peer.BLUETOOTH) return Collections.emptyList();
        if (Build.VERSION.SDK_INT >= 31) return Arrays.asList(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE);
        return Collections.singletonList(Manifest.permission.ACCESS_FINE_LOCATION);
    }
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 7 || pendingAction == null || pendingStage != PERMISSION_STAGE) return;
        UiAction action = pendingAction;
        for (String permission : requiredPermissions(action.mode)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = null; pendingStage = 0;
            new AlertDialog.Builder(this).setTitle("蓝牙权限尚未允许").setMessage("蓝牙搜索与连接需要这些权限。你可以使用局域网，或在应用设置中允许附近设备 / 定位。")
                    .setPositiveButton("应用设置", (d, w) -> appSettings()).setNegativeButton("关闭", null).show(); return;
        }
        ensureEnabled(action);
    }
    private void ensureEnabled(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        try {
            if (action.mode == Peer.BLUETOOTH && !controller.bluetoothEnabled()) {
                pendingAction = action; pendingStage = ENABLE_STAGE;
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8);
            } else { pendingAction = null; pendingStage = 0; execute(action); }
        } catch (SecurityException e) { pendingAction = null; pendingStage = 0; toast("蓝牙权限已撤销，请重新授权"); }
    }
    private void resumePending() {
        if (pendingAction == null || controller == null) return;
        boolean granted = true;
        for (String permission : requiredPermissions(pendingAction.mode)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) granted = false;
        if (pendingStage == BIND_STAGE || (pendingStage == PERMISSION_STAGE && granted)) withPermissions(pendingAction);
        else if (pendingStage == ENABLE_STAGE && granted && controller.bluetoothEnabled()) ensureEnabled(pendingAction);
    }
    private void execute(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        switch (action.type) {
            case UiAction.START: startTransport(action.mode); break;
            case UiAction.SEARCH:
                if (!(action.mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning)) startTransport(action.mode);
                controller.search(action.mode); break;
            case UiAction.DISCOVERABLE:
                if (!controller.bluetoothRunning) startTransport(Peer.BLUETOOTH);
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)); break;
            case UiAction.PEER: controller.connect(action.peer); break;
            case UiAction.ADDRESS:
                if (!controller.lanRunning) startTransport(Peer.LAN);
                controller.connectAddress(action.address); break;
        }
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 8) {
            if (pendingAction == null || pendingStage != ENABLE_STAGE) return;
            UiAction action = pendingAction;
            if (resultCode == RESULT_OK) { pendingStage = BIND_STAGE; withPermissions(action); }
            else { pendingAction = null; pendingStage = 0; toast("未开启蓝牙，请点按钮重试"); }
        }
    }
    private void settings() {
        String[] options = {"修改昵称", "清空当前会话记录", "停止所有连接", "应用权限设置", "使用说明"};
        new AlertDialog.Builder(this).setTitle("我在").setItems(options, (dialog, which) -> {
            if (which == 3) { appSettings(); return; }
            if (which == 4) { new AlertDialog.Builder(this).setTitle("我在 0.1.1").setMessage("局域网：双方连接同一 Wi-Fi 或热点，开启接收后搜索，也可输入 IP 地址和端口。\n\n蓝牙：双方开启接收，一方允许被发现，另一方搜索并发起聊天；首次连接按系统提示配对。\n\n双方同意后才可聊天。待确认表示尚未收到保存回执，已送达表示对方已保存，未确认表示结果未知。\n\n后台由持续通知维持；划掉最近任务或系统强杀后需重新连接。局域网为明文，昵称与设备身份未经认证。卸载会删除本机记录。")
                    .setPositiveButton("知道了", null).show(); return; }
            if (controller == null) return;
            if (which == 0) {
                EditText name = new EditText(this); name.setText(controller.nickname); name.setSingleLine(true); name.setSelectAllOnFocus(true);
                LinearLayout box = vertical(); box.setPadding(dp(24), dp(8), dp(24), 0); box.addView(name);
                new AlertDialog.Builder(this).setTitle("你的昵称").setView(box).setPositiveButton("保存", (d, w) -> { if (controller != null) controller.setNickname(name.getText().toString()); }).setNegativeButton("取消", null).show();
            } else if (which == 1 && controller.selectedId != null) new AlertDialog.Builder(this).setTitle("清空「" + controller.selectedName + "」的本机消息？")
                    .setPositiveButton("清空", (d, w) -> { if (controller != null) controller.clearConversation(); }).setNegativeButton("取消", null).show();
            else if (which == 2) controller.stopAll();
        }).show();
    }
    private void appSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(2), dp(4), dp(2), dp(4)); return params; }
    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(10), 0, dp(12)); return params; }
    private LinearLayout card() { LinearLayout card = vertical(); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setBackground(shape(surface, 24)); return card; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setPadding(0, dp(2), 0, dp(2)); return view; }
    private Button button(String text, boolean primary) { Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumWidth(dp(64)); button.setPadding(dp(14), dp(8), dp(14), dp(8)); style(button, primary); return button; }
    private void style(Button button, boolean primary) { button.setBackground(shape(primary ? accent : tonal, 24)); button.setTextColor(primary ? accentInk : ink); button.setAlpha(button.isEnabled() ? 1f : .45f); }
    private void empty(LinearLayout target, String symbol, String title, String detail) {
        LinearLayout box = vertical(); box.setGravity(Gravity.CENTER); box.setPadding(dp(12), dp(32), dp(12), dp(32));
        TextView icon = label(symbol, 44, accent); icon.setGravity(Gravity.CENTER); box.addView(icon);
        TextView heading = label(title, 18, ink); heading.setGravity(Gravity.CENTER); box.addView(heading);
        TextView body = label(detail, 13, muted); body.setGravity(Gravity.CENTER); body.setPadding(0, dp(6), 0, 0); box.addView(body); target.addView(box);
    }
}

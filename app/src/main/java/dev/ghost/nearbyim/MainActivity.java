package dev.ghost.nearbyim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import dev.ghost.nearbyim.transport.Peer;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native, local-data UI. Discovery, authorization and delivery remain controller state. */
public final class MainActivity extends Activity {
    private ChatService service;
    private ChatController controller;
    private boolean bound, detail, outgoingRequest, rebuildingMessages, resumePosted;
    private int mode = Peer.LAN, page;
    private int background, surface, ink, muted, accent, accentInk, tonal, line;
    private int sideInsetLeft, sideInsetRight, topInset, bottomInset, messageWidth;
    private LinearLayout root, header, bottomNav, homePage, nearbyPage, settingsPage, chatPage;
    private LinearLayout historyList, peersList, trustedList, bubbles, reconnectRow, chatAvatarBox;
    private FrameLayout content, homeFrame;
    private TextView pageTitle, networkName, networkState, errorView, receiveStatus, connectionInfo, modeHint, bluetoothState, requestStatus;
    private TextView chatName, chatStatus, chatAvatar, nickname, reconnectHint;
    private Button lanTab, bluetoothTab, receiveButton, searchButton, discoverableButton, manualButton, sendButton, reconnectButton, newMessages;
    private Button newChatButton, cancelConnectionButton;
    private ImageView networkIcon;
    private ProgressBar scanProgress;
    private EditText composer, conversationSearch;
    private ScrollView messageScroll, historyScroll;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private List<ChatStore.Message> renderedMessages;
    private List<ChatStore.Conversation> renderedConversations;
    private List<ChatStore.TrustedDevice> renderedTrusted;
    private String renderedConnection, renderedQuery, peerSignature, renderedPeer, deferredScrollPeer;
    private int deferredScrollY, messageGeneration;
    private AlertDialog approvalDialog;
    private String shownApproval, restoredSelectedId, restoredSelectedName, composerPeer, handledSavedMessage;
    private String searchQuery = "";
    private boolean lanDetails, rememberNext = true;
    private CheckBox rememberDevice;
    private long discoverableUntil;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, Integer> historyPositions = new HashMap<>();
    private static final int PERMISSION_STAGE = 1, ENABLE_STAGE = 2, BIND_STAGE = 3, CLOSE_STAGE = 4;
    private static final class UiAction implements java.io.Serializable {
        private static final long serialVersionUID = 2L;
        static final int START = 1, SEARCH = 2, DISCOVERABLE = 3, PEER = 4, ADDRESS = 5, RECONNECT = 6;
        final int type, mode;
        final Peer peer;
        final String address, peerId;
        final boolean remember;
        UiAction(int type, int mode) { this(type, mode, null, null, null, true); }
        UiAction(int type, int mode, Peer peer, String address, String peerId, boolean remember) {
            this.type = type; this.mode = mode; this.peer = peer; this.address = address; this.peerId = peerId; this.remember = remember;
        }
    }
    private UiAction pendingAction;
    private int pendingStage;
    private final Runnable discoverabilityTick = new Runnable() {
        public void run() { updateDiscoverability(); ui.postDelayed(this, 1000); }
    };
    private final ServiceConnection binding = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ChatService.LocalBinder) binder).service(); controller = service.controller;
            if (controller.selectedId == null && restoredSelectedId != null)
                controller.selectConversation(new ChatStore.Conversation(restoredSelectedId, restoredSelectedName, 0));
            controller.observe(MainActivity.this::render); resumePending();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; controller = null; render(); }
    };
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved == null) { SharedPreferences preferences = getSharedPreferences("ui", MODE_PRIVATE); mode = preferences.getInt("lastMode", Peer.LAN); rememberNext = preferences.getBoolean("rememberNext", true); }
        if (saved != null) {
            mode = saved.getInt("mode", Peer.LAN); page = saved.getInt("page", 0); detail = saved.getBoolean("detail");
            pendingAction = (UiAction) saved.getSerializable("pendingAction"); pendingStage = saved.getInt("pendingStage");
            restoredSelectedId = saved.getString("selectedId"); restoredSelectedName = saved.getString("selectedName");
            composerPeer = saved.getString("composerPeer"); handledSavedMessage = saved.getString("handledSavedMessage");
            outgoingRequest = saved.getBoolean("outgoingRequest"); searchQuery = saved.getString("searchQuery", "");
            lanDetails = saved.getBoolean("lanDetails"); rememberNext = saved.getBoolean("rememberNext", true); discoverableUntil = saved.getLong("discoverableUntil");
            Bundle draftState = saved.getBundle("drafts"), positions = saved.getBundle("historyPositions");
            if (draftState != null) for (String key : draftState.keySet()) drafts.put(key, draftState.getString(key, ""));
            if (positions != null) for (String key : positions.keySet()) historyPositions.put(key, positions.getInt(key));
        }
        palette(); buildUi();
        if (saved != null) composer.setText(saved.getString("draft", ""));
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::handleBack);
        render();
    }
    protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, ChatService.class), binding, Context.BIND_AUTO_CREATE);
        ui.post(discoverabilityTick);
    }
    protected void onResume() { super.onResume(); if (root != null) render(); }
    protected void onStop() {
        saveDraftAndPosition(); ui.removeCallbacks(discoverabilityTick);
        if (controller != null) { restoredSelectedId = controller.selectedId; restoredSelectedName = controller.selectedName; controller.observe(null); }
        if (bound) { unbindService(binding); bound = false; } controller = null; service = null;
        if (approvalDialog != null) { approvalDialog.setOnCancelListener(null); approvalDialog.dismiss(); approvalDialog = null; shownApproval = null; }
        super.onStop();
    }
    protected void onSaveInstanceState(Bundle state) {
        saveDraftAndPosition();
        state.putInt("mode", mode); state.putInt("page", page); state.putBoolean("detail", detail); state.putString("draft", composer.getText().toString());
        state.putString("selectedId", controller == null ? restoredSelectedId : controller.selectedId);
        state.putString("selectedName", controller == null ? restoredSelectedName : controller.selectedName);
        state.putString("composerPeer", composerPeer); state.putString("handledSavedMessage", handledSavedMessage);
        state.putBoolean("outgoingRequest", outgoingRequest); state.putString("searchQuery", searchQuery); state.putBoolean("lanDetails", lanDetails); state.putBoolean("rememberNext", rememberNext);
        state.putLong("discoverableUntil", discoverableUntil);
        Bundle draftState = new Bundle(), positions = new Bundle();
        for (Map.Entry<String, String> entry : drafts.entrySet()) draftState.putString(entry.getKey(), entry.getValue());
        for (Map.Entry<String, Integer> entry : historyPositions.entrySet()) positions.putInt(entry.getKey(), entry.getValue());
        state.putBundle("drafts", draftState); state.putBundle("historyPositions", positions);
        state.putSerializable("pendingAction", pendingAction); state.putInt("pendingStage", pendingStage); super.onSaveInstanceState(state);
    }
    private void saveDraftAndPosition() {
        if (composerPeer != null && composer != null) drafts.put(composerPeer, composer.getText().toString());
        if (renderedPeer != null && messageScroll != null && !Objects.equals(renderedPeer, deferredScrollPeer)) historyPositions.put(renderedPeer, messageScroll.getScrollY());
    }
    @SuppressLint("GestureBackNavigation") @SuppressWarnings("deprecation")
    public void onBackPressed() { if (Build.VERSION.SDK_INT < 33) handleBack(); else super.onBackPressed(); }
    private void handleBack() {
        if (detail) { saveDraftAndPosition(); detail = false; page = 0; hideKeyboard(); render(); animatePage(homePage); }
        else if (page != 0) navigate(0); else if (!searchQuery.isEmpty()) conversationSearch.setText(""); else finish();
    }
    private void palette() {
        boolean dark = isDark();
        background = color(dark ? "#101619" : "#FAFCFA"); surface = color(dark ? "#222B2F" : "#EEF3EF");
        ink = color(dark ? "#F1F5F3" : "#17211C"); muted = color(dark ? "#9AA8AD" : "#58675F");
        accent = color(dark ? "#82D8BA" : "#246B4E"); accentInk = color(dark ? "#103B2E" : "#FFFFFF");
        tonal = color(dark ? "#25453A" : "#DDF4E7"); line = color(dark ? "#33413D" : "#E3EAE5");
    }
    private boolean isDark() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private void buildUi() {
        root = vertical(); root.setBackgroundColor(background); root.setFocusableInTouchMode(true); setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController appearance = getWindow().getInsetsController();
            if (appearance != null) appearance.setSystemBarsAppearance(isDark() ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            int appearance = isDark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | appearance);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                topInset = bars.top; bottomInset = Math.max(bars.bottom, ime.bottom); sideInsetLeft = bars.left; sideInsetRight = bars.right;
            } else {
                topInset = insets.getSystemWindowInsetTop(); bottomInset = insets.getSystemWindowInsetBottom();
                sideInsetLeft = insets.getSystemWindowInsetLeft(); sideInsetRight = insets.getSystemWindowInsetRight();
            }
            applyRootPadding(); return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) { applyRootPadding(); updateMessageWidths(); }
        });
        header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16), dp(14), dp(16), dp(10));
        pageTitle = label("我在", 32, ink); pageTitle.setTypeface(null, Typeface.BOLD); header.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout indicator = horizontal(); indicator.setGravity(Gravity.CENTER_VERTICAL); networkIcon = icon(R.drawable.outline_wifi_24, accent, "");
        networkIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); indicator.addView(networkIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout networkText = vertical(); networkText.setPadding(dp(6), 0, 0, 0); networkName = label("", 13, ink); networkName.setTypeface(null, Typeface.BOLD);
        networkState = label("未连接", 12, muted); networkText.addView(networkName); networkText.addView(networkState); indicator.addView(networkText);
        indicator.setPadding(dp(8), dp(4), 0, dp(4)); header.addView(indicator); root.addView(header);
        errorView = label("", 13, ink); errorView.setPadding(dp(16), dp(10), dp(16), dp(10)); errorView.setBackgroundColor(tonal);
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(errorView);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        homePage = vertical(); nearbyPage = vertical(); settingsPage = vertical(); chatPage = vertical();
        content.addView(homePage, new FrameLayout.LayoutParams(-1, -1)); content.addView(nearbyPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1)); content.addView(chatPage, new FrameLayout.LayoutParams(-1, -1));
        buildHome(); buildNearby(); buildSettings(); buildChat(); buildNavigation(); root.requestApplyInsets();
    }
    private void applyRootPadding() {
        int gutter = Math.max(0, (root.getWidth() - sideInsetLeft - sideInsetRight - dp(720)) / 2);
        root.setPadding(sideInsetLeft + gutter, topInset, sideInsetRight + gutter, bottomInset);
    }
    private void buildNavigation() {
        bottomNav = horizontal(); bottomNav.setGravity(Gravity.CENTER); bottomNav.setPadding(dp(12), dp(9), dp(12), dp(7));
        GradientDrawable navSurface = shape(background, 0); bottomNav.setBackground(navSurface);
        String[] labels = {"聊天", "附近", "设置"}; int[] icons = {R.drawable.outline_chat_bubble_24, R.drawable.outline_wifi_tethering_24, R.drawable.outline_settings_24};
        for (int i = 0; i < 3; i++) {
            final int selected = i; LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(64)); item.setContentDescription(labels[i]);
            item.setFocusable(true); item.setClickable(true); item.setOnClickListener(v -> navigate(selected)); item.setBackground(ripple(background, 16));
            FrameLayout pill = new FrameLayout(this); pill.setTag("pill"); ImageView icon = icon(icons[i], muted, ""); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER); pill.addView(icon, imageParams);
            item.addView(pill, new LinearLayout.LayoutParams(dp(72), dp(32))); TextView text = label(labels[i], 13, muted); text.setGravity(Gravity.CENTER);
            item.addView(text); navItems[i] = item; navIcons[i] = icon; navLabels[i] = text; bottomNav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        View divider = new View(this); divider.setBackgroundColor(line); divider.setTag("navDivider"); root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(bottomNav);
    }
    private void chooseMode(int chosen) {
        mode = chosen; getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("lastMode", chosen).apply(); peerSignature = null; render();
    }
    private void navigate(int selected) {
        saveDraftAndPosition(); detail = false; page = selected; hideKeyboard(); render();
        animatePage(selected == 0 ? homePage : selected == 1 ? nearbyPage : settingsPage);
    }
    private void buildHome() {
        LinearLayout searchRow = horizontal(); searchRow.setGravity(Gravity.CENTER_VERTICAL); searchRow.setPadding(dp(14), 0, dp(4), 0); searchRow.setBackground(shape(surface, 28)); searchRow.setMinimumHeight(dp(48));
        searchRow.addView(icon(R.drawable.outline_search_24, muted, ""), new LinearLayout.LayoutParams(dp(24), dp(24)));
        conversationSearch = new EditText(this); conversationSearch.setTextColor(ink); conversationSearch.setHintTextColor(muted); conversationSearch.setTextSize(16);
        conversationSearch.setHint("搜索聊天"); conversationSearch.setSingleLine(true); conversationSearch.setBackgroundColor(Color.TRANSPARENT); conversationSearch.setPadding(dp(10), dp(10), dp(4), dp(10));
        conversationSearch.setInputType(InputType.TYPE_CLASS_TEXT); conversationSearch.setText(searchQuery); searchRow.addView(conversationSearch, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = iconButton(R.drawable.outline_close_24, "清除搜索", muted); clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> conversationSearch.setText("")); searchRow.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2); searchParams.setMargins(dp(16), dp(10), dp(16), dp(12)); homePage.addView(searchRow, searchParams);
        conversationSearch.addTextChangedListener(watcher(() -> { searchQuery = conversationSearch.getText().toString(); clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE); renderHistory(); }));
        homeFrame = new FrameLayout(this); homePage.addView(homeFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        historyScroll = new ScrollView(this); historyScroll.setClipToPadding(false); historyScroll.setPadding(dp(16), 0, dp(16), dp(88)); historyList = vertical();
        historyScroll.addView(historyList); homeFrame.addView(historyScroll, new FrameLayout.LayoutParams(-1, -1));
        newChatButton = button("新聊天", true);
        android.graphics.drawable.Drawable addIcon = getDrawable(R.drawable.outline_add_24);
        if (addIcon != null) { addIcon = addIcon.mutate(); addIcon.setTint(accentInk); addIcon.setBounds(0, 0, dp(24), dp(24)); newChatButton.setCompoundDrawablesRelative(addIcon, null, null, null); }
        newChatButton.setCompoundDrawableTintList(ColorStateList.valueOf(accentInk)); newChatButton.setCompoundDrawablePadding(dp(8)); newChatButton.setTextSize(16);
        newChatButton.setPadding(dp(22), dp(12), dp(22), dp(12)); newChatButton.setMinHeight(dp(56)); newChatButton.setElevation(dp(3)); newChatButton.setContentDescription("新聊天");
        newChatButton.setOnClickListener(v -> navigate(1)); FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(dp(16), dp(16), dp(16), dp(16)); homeFrame.addView(newChatButton, fabParams);
    }
    private void buildNearby() {
        ScrollView scroll = new ScrollView(this); LinearLayout nearbyContent = vertical(); nearbyContent.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(nearbyContent); nearbyPage.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout modes = horizontal(); modes.setPadding(dp(4), dp(4), dp(4), dp(4)); modes.setBackground(shape(surface, 28));
        lanTab = button("局域网", true); bluetoothTab = button("蓝牙", false); lanTab.setOnClickListener(v -> chooseMode(Peer.LAN));
        bluetoothTab.setOnClickListener(v -> chooseMode(Peer.BLUETOOTH)); modes.addView(lanTab, new LinearLayout.LayoutParams(0, -2, 1)); modes.addView(bluetoothTab, new LinearLayout.LayoutParams(0, -2, 1)); nearbyContent.addView(modes);
        modeHint = label("", 14, muted); modeHint.setPadding(0, dp(16), 0, dp(8)); nearbyContent.addView(modeHint);
        rememberDevice = new CheckBox(this); rememberDevice.setText("首次连接成功后记住设备"); rememberDevice.setTextSize(14); rememberDevice.setTextColor(muted); rememberDevice.setChecked(rememberNext);
        rememberDevice.setMinHeight(dp(48)); rememberDevice.setOnCheckedChangeListener((button, checked) -> { rememberNext = checked; getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("rememberNext", checked).apply(); }); nearbyContent.addView(rememberDevice);
        bluetoothState = label("", 13, muted); nearbyContent.addView(bluetoothState);
        LinearLayout receiveRow = horizontal(); receiveRow.setGravity(Gravity.CENTER_VERTICAL); receiveStatus = label("接收已关闭", 16, ink);
        receiveRow.addView(receiveStatus, new LinearLayout.LayoutParams(0, -2, 1)); receiveButton = button("开启接收", false);
        receiveButton.setOnClickListener(v -> {
            if (controller == null) return;
            if (mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning) {
                if (controller.hasSession() && controller.sessionMode == mode)
                    new AlertDialog.Builder(this).setTitle("关闭接收并结束当前聊天连接？").setMessage("本机聊天记录和信任会保留。")
                        .setPositiveButton("关闭接收", (d, w) -> { if (controller != null) controller.stop(mode); }).setNegativeButton("取消", null).show();
                else controller.stop(mode);
            } else withPermissions(new UiAction(UiAction.START, mode));
        }); receiveRow.addView(receiveButton); nearbyContent.addView(receiveRow);
        Button details = button("查看并复制本机地址", false); details.setTag("lanDetails"); details.setOnClickListener(v -> { lanDetails = !lanDetails; render(); }); nearbyContent.addView(details, topSpace());
        connectionInfo = label("", 13, muted); connectionInfo.setTextIsSelectable(true); connectionInfo.setPadding(0, dp(8), 0, dp(8));
        connectionInfo.setOnLongClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("本机地址", connectionInfo.getText())); toast("地址已复制"); return true; }); nearbyContent.addView(connectionInfo);
        searchButton = button("搜索设备", true); searchButton.setOnClickListener(v -> {
            if (controller != null && (mode == Peer.LAN ? controller.lanSearching : controller.bluetoothSearching)) controller.stopSearch(mode);
            else withPermissions(new UiAction(UiAction.SEARCH, mode));
        }); nearbyContent.addView(searchButton, topSpace());
        discoverableButton = button("允许被发现 120 秒", false); discoverableButton.setOnClickListener(v -> withPermissions(new UiAction(UiAction.DISCOVERABLE, Peer.BLUETOOTH))); nearbyContent.addView(discoverableButton, topSpace());
        manualButton = button("通过 IP 地址连接", false); manualButton.setOnClickListener(v -> manualConnect()); nearbyContent.addView(manualButton, topSpace());
        requestStatus = label("", 14, muted); requestStatus.setPadding(0, dp(12), 0, dp(4)); requestStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); nearbyContent.addView(requestStatus);
        cancelConnectionButton = button("取消连接", false); cancelConnectionButton.setOnClickListener(v -> { outgoingRequest = false; if (controller != null) controller.disconnect(); }); nearbyContent.addView(cancelConnectionButton, topSpace());
        LinearLayout devicesHeader = horizontal(); devicesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView devicesHeading = sectionHeading("附近设备"); devicesHeader.addView(devicesHeading, new LinearLayout.LayoutParams(0, -2, 1));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall); scanProgress.setIndeterminateTintList(ColorStateList.valueOf(accent)); scanProgress.setContentDescription("正在搜索设备");
        devicesHeader.addView(scanProgress, new LinearLayout.LayoutParams(dp(24), dp(24))); nearbyContent.addView(devicesHeader); peersList = vertical(); nearbyContent.addView(peersList);
    }
    private void buildSettings() {
        ScrollView scroll = new ScrollView(this); LinearLayout settingsContent = vertical(); settingsContent.setPadding(dp(16), dp(12), dp(16), dp(24)); scroll.addView(settingsContent); settingsPage.addView(scroll);
        settingsContent.addView(sectionHeading("这部设备")); nickname = label("", 14, muted); settingsContent.addView(settingsRow("本机昵称", nickname, this::editNickname));
        settingsContent.addView(sectionHeading("已信任设备")); trustedList = vertical(); settingsContent.addView(trustedList);
        settingsContent.addView(sectionHeading("应用")); settingsContent.addView(settingsRow("应用权限", label("查看附近设备与通知权限", 13, muted), this::appSettings));
        settingsContent.addView(settingsRow("停止所有连接", label("关闭接收，保留记录和信任", 13, muted), () -> {
            if (controller != null) new AlertDialog.Builder(this).setTitle("停止所有连接？").setMessage("两种连接方式的接收和当前聊天连接都会结束。")
                .setPositiveButton("停止", (d, w) -> { if (controller != null) controller.stopAll(); }).setNegativeButton("取消", null).show();
        }));
        settingsContent.addView(settingsRow("使用说明", null, this::showHelp));
        settingsContent.addView(settingsRow("关于「我在」", label("无需账号的附近文字聊天", 13, muted), () -> new AlertDialog.Builder(this).setTitle("我在")
            .setMessage("通过局域网或蓝牙进行一对一文字聊天。消息保存在本机，卸载或清除应用数据会删除记录与设备信任。")
            .setPositiveButton("知道了", null).show()));
    }
    private void buildChat() {
        LinearLayout chatHeader = horizontal(); chatHeader.setGravity(Gravity.CENTER_VERTICAL); chatHeader.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = iconButton(R.drawable.outline_arrow_back_24, "返回聊天列表", ink); back.setOnClickListener(v -> handleBack()); chatHeader.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chatAvatarBox = vertical(); chatHeader.addView(chatAvatarBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = vertical(); names.setPadding(dp(12), 0, dp(4), 0); chatName = label("", 18, ink); chatName.setTypeface(null, Typeface.BOLD); singleLine(chatName);
        chatStatus = label("未连接", 12, muted); names.addView(chatName); names.addView(chatStatus); chatHeader.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = iconButton(R.drawable.outline_more_vert_24, "聊天菜单", ink); more.setOnClickListener(this::chatMenu); chatHeader.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48))); chatPage.addView(chatHeader);
        reconnectRow = vertical(); reconnectRow.setPadding(dp(16), dp(4), dp(16), dp(10)); reconnectHint = label("", 13, muted); reconnectRow.addView(reconnectHint);
        LinearLayout reconnectActions = horizontal(); reconnectButton = button("连接", true); reconnectButton.setOnClickListener(v -> { if (controller != null && outgoingRequest && (controller.connecting || controller.hasSession() && !controller.connected)) { outgoingRequest = false; controller.disconnect(); } else reconnectSelected(); });
        reconnectActions.addView(reconnectButton, new LinearLayout.LayoutParams(0, -2, 1)); Button find = button("去附近查找", false); find.setOnClickListener(v -> navigate(1));
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(0, -2, 1); findParams.setMarginStart(dp(8)); reconnectActions.addView(find, findParams); reconnectRow.addView(reconnectActions); chatPage.addView(reconnectRow);
        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false); messageScroll.setPadding(dp(16), 0, dp(16), 0);
        bubbles = vertical(); bubbles.setPadding(0, dp(8), 0, dp(12)); messageScroll.addView(bubbles); chatPage.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        messageScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean followedBottom = ob > ot && bubbles.getHeight() - (ob - ot) - messageScroll.getScrollY() < dp(48);
            if (r - l != or - ol) updateMessageWidths();
            if (detail && followedBottom && b - t < ob - ot) messageScroll.post(() -> messageScroll.scrollTo(0, Math.max(0, bubbles.getHeight() - messageScroll.getHeight())));
        });
        messageScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (!rebuildingMessages && atBottom()) newMessages.setVisibility(View.GONE); });
        newMessages = button("新消息", false); newMessages.setVisibility(View.GONE); newMessages.setOnClickListener(v -> { messageScroll.smoothScrollTo(0, bubbles.getHeight()); newMessages.setVisibility(View.GONE); });
        chatPage.addView(newMessages, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout inputRow = horizontal(); inputRow.setGravity(Gravity.BOTTOM); inputRow.setPadding(dp(16), dp(8), dp(16), dp(10));
        composer = new EditText(this); composer.setTextColor(ink); composer.setHintTextColor(muted); composer.setTextSize(16); composer.setHint("说点什么…"); composer.setBackground(shape(surface, 24));
        composer.setPadding(dp(16), dp(12), dp(16), dp(12)); composer.setMinHeight(dp(48)); composer.setMaxLines(4); composer.setVerticalScrollBarEnabled(true);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        inputRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1)); sendButton = button("发送", true); sendButton.setOnClickListener(v -> { if (controller != null) controller.send(composer.getText().toString()); });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, -2); sendParams.setMarginStart(dp(8)); inputRow.addView(sendButton, sendParams); chatPage.addView(inputRow);
        composer.addTextChangedListener(watcher(this::updateSend));
    }
    private void render() {
        if (root == null) return;
        header.setVisibility(detail ? View.GONE : View.VISIBLE); bottomNav.setVisibility(detail ? View.GONE : View.VISIBLE);
        View divider = root.findViewWithTag("navDivider"); if (divider != null) divider.setVisibility(detail ? View.GONE : View.VISIBLE);
        homePage.setVisibility(!detail && page == 0 ? View.VISIBLE : View.GONE); nearbyPage.setVisibility(!detail && page == 1 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(!detail && page == 2 ? View.VISIBLE : View.GONE); chatPage.setVisibility(detail ? View.VISIBLE : View.GONE);
        pageTitle.setText(page == 0 ? "我在" : page == 1 ? "附近" : "设置");
        for (int i = 0; i < 3; i++) {
            navItems[i].setSelected(i == page); navItems[i].findViewWithTag("pill").setBackground(shape(i == page ? tonal : Color.TRANSPARENT, 20));
            navIcons[i].setImageTintList(ColorStateList.valueOf(i == page ? accent : muted)); navLabels[i].setTextColor(i == page ? accent : muted); navLabels[i].setTypeface(null, i == page ? Typeface.BOLD : Typeface.NORMAL);
        }
        errorView.setVisibility(controller != null && !controller.error.isEmpty() ? View.VISIBLE : View.GONE); errorView.setText(controller == null ? "" : controller.error);
        boolean connected = controller != null && controller.connected && controller.connectedPeerId != null;
        boolean connecting = controller != null && (controller.connecting || controller.hasSession() && !connected);
        networkName.setText(connected ? modeName(controller.sessionMode) : ""); networkName.setVisibility(connected ? View.VISIBLE : View.GONE);
        networkState.setText(connected ? "● 已连接" : connecting ? "连接中…" : "未连接"); networkState.setTextColor(connected ? accent : muted);
        networkIcon.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) networkIcon.setImageResource(controller.sessionMode == Peer.BLUETOOTH ? R.drawable.outline_bluetooth_24 : R.drawable.outline_wifi_24);
        if (controller != null && outgoingRequest && connected) {
            outgoingRequest = false; detail = true; page = 0;
            if (!Objects.equals(controller.selectedId, controller.connectedPeerId)) controller.selectConversation(new ChatStore.Conversation(controller.connectedPeerId, controller.selectedName, 0));
            render(); animatePage(chatPage); return;
        }
        if (controller != null && outgoingRequest && !controller.connecting && !controller.hasSession()) outgoingRequest = false;
        renderNearby(); renderHistory(); renderSettings(); renderChat();
        if (controller != null) showApproval();
        if (pendingAction != null && pendingStage == CLOSE_STAGE && controller != null && !controller.hasSession() && !controller.connecting && !resumePosted) {
            resumePosted = true; ui.post(() -> { resumePosted = false; resumePending(); });
        }
    }
    private void renderHistory() {
        if (historyList == null) return;
        List<ChatStore.Conversation> conversations = controller == null ? Collections.emptyList() : controller.conversations;
        String connection = controller == null ? null : controller.connectedPeerId;
        if (renderedConversations == conversations && Objects.equals(renderedConnection, connection) && Objects.equals(renderedQuery, searchQuery)) return;
        renderedConversations = conversations; renderedConnection = connection; renderedQuery = searchQuery; int oldY = historyScroll.getScrollY(); historyList.removeAllViews();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT); int count = 0; String previousGroup = null;
        for (ChatStore.Conversation conversation : conversations) {
            if (!safe(conversation.name).toLowerCase(Locale.ROOT).contains(query)) continue;
            String group = sameDay(conversation.time, System.currentTimeMillis()) ? "今天" : "较早";
            if (query.isEmpty() && !group.equals(previousGroup)) { historyList.addView(sectionHeading(group)); previousGroup = group; }
            count++; boolean active = controller != null && controller.connected && Objects.equals(connection, conversation.id);
            LinearLayout row = horizontal(); row.setGravity(Gravity.TOP); row.setPadding(0, dp(14), 0, dp(14)); row.setMinimumHeight(dp(active ? 98 : 82)); row.setBackground(ripple(background, 14));
            TextView avatar = avatar(conversation.id, conversation.name, 48); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout text = vertical(); text.setPadding(dp(14), 0, dp(8), 0); LinearLayout titleRow = horizontal(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(safe(conversation.name), 17, ink); name.setTypeface(null, Typeface.BOLD); singleLine(name); titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) { TextView dot = label(" ●", 13, accent); dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); titleRow.addView(dot); }
            text.addView(titleRow); if (active) text.addView(label("已连接 · " + modeName(controller.sessionMode), 13, muted));
            String preview = safe(conversation.preview); if (preview.isEmpty()) preview = "还没有消息";
            else if (conversation.outgoing && !safe(conversation.state).isEmpty()) preview = conversation.state + " · " + preview;
            TextView summary = label(preview, 15, muted); singleLine(summary); text.addView(summary); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView time = label(conversation.time > 0 ? listTime(conversation.time) : "", 12, muted); time.setGravity(Gravity.END); row.addView(time);
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> openConversation(conversation)); historyList.addView(row);
        }
        boolean emptyHistory = conversations.isEmpty(); newChatButton.setVisibility(emptyHistory ? View.GONE : View.VISIBLE);
        historyScroll.setPadding(dp(16), 0, dp(16), emptyHistory ? dp(16) : dp(88));
        if (count == 0) {
            if (emptyHistory && query.isEmpty()) {
                LinearLayout empty = empty("还没有聊天", "找到身边的人，聊第一句话。"); Button find = button("找附近的人", true); find.setOnClickListener(v -> navigate(1)); empty.addView(find, topSpace()); historyList.addView(empty);
            } else historyList.addView(empty("没有找到相关聊天", "试试其他昵称，或清除搜索。"));
        }
        historyScroll.post(() -> historyScroll.scrollTo(0, oldY));
    }
    private void openConversation(ChatStore.Conversation conversation) {
        if (controller == null) return; saveDraftAndPosition(); detail = true; page = 0; hideKeyboard(); controller.selectConversation(conversation); render(); animatePage(chatPage);
    }
    private void renderNearby() {
        boolean attached = controller != null; style(lanTab, mode == Peer.LAN); style(bluetoothTab, mode == Peer.BLUETOOTH);
        lanTab.setSelected(mode == Peer.LAN); bluetoothTab.setSelected(mode == Peer.BLUETOOTH);
        modeHint.setText(mode == Peer.LAN ? "两部设备连接同一 Wi-Fi，或加入同一个热点。局域网消息以明文传输，请使用可信网络。" : "对方开启接收并允许被发现后，再开始搜索。首次连接由系统处理配对。");
        boolean running = attached && (mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning);
        boolean searching = attached && (mode == Peer.LAN ? controller.lanSearching : controller.bluetoothSearching);
        String info = !attached ? "" : mode == Peer.LAN ? controller.lanInfo : controller.bluetoothInfo;
        boolean readyToReceive = running && !info.contains("正在启动") && !info.equals("尚未开启");
        receiveStatus.setText(!running ? "接收已关闭" : readyToReceive ? "正在接收" : "正在开启接收…"); receiveButton.setText(running ? "关闭接收" : "开启接收"); receiveButton.setEnabled(attached);
        scanProgress.setVisibility(searching ? View.VISIBLE : View.GONE); searchButton.setText(searching ? "停止搜索" : "搜索设备"); searchButton.setEnabled(attached && !controller.connecting);
        manualButton.setVisibility(mode == Peer.LAN ? View.VISIBLE : View.GONE); manualButton.setEnabled(attached && !controller.connecting);
        discoverableButton.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE); discoverableButton.setEnabled(attached && controller.bluetoothAvailable());
        boolean waiting = attached && (controller.connecting || controller.hasSession() && !controller.connected);
        requestStatus.setVisibility(waiting ? View.VISIBLE : View.GONE); requestStatus.setText(waiting ? controller.status : "");
        cancelConnectionButton.setVisibility(waiting ? View.VISIBLE : View.GONE);
        View details = nearbyPage.findViewWithTag("lanDetails"); details.setVisibility(mode == Peer.LAN && running ? View.VISIBLE : View.GONE);
        connectionInfo.setVisibility(mode == Peer.LAN && running && lanDetails ? View.VISIBLE : View.GONE); connectionInfo.setText(info);
        bluetoothState.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE);
        if (mode == Peer.BLUETOOTH) {
            String bt = !attached ? "正在连接服务…" : !controller.bluetoothAvailable() ? "这部设备不支持蓝牙，可切换局域网。" : !bluetoothPermissionsGranted() ? "附近设备权限尚未授权 · 点击搜索授权" : bluetoothEnabled() ? "蓝牙已开启" : "蓝牙已关闭 · 点击搜索开启";
            bluetoothState.setText(bt); searchButton.setEnabled(attached && controller.bluetoothAvailable() && !controller.connecting); updateDiscoverability();
        }
        style(receiveButton, false); style(searchButton, true); style(manualButton, false); style(discoverableButton, false);
        StringBuilder signature = new StringBuilder().append(mode).append(searching).append(attached);
        if (attached) for (Peer peer : controller.peers.values()) if (peer.mode == mode) signature.append(peer.key).append(peer.name).append(peer.detail).append(controller.isTrustedPeer(peer));
        if (Objects.equals(peerSignature, signature.toString())) return; peerSignature = signature.toString(); peersList.removeAllViews(); int count = 0;
        if (attached) for (Peer peer : controller.peers.values()) if (peer.mode == mode && count++ < 50) {
            LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(82)); row.setPadding(0, dp(12), 0, dp(12)); row.setBackground(ripple(background, 12));
            row.addView(avatar(peer.key, peer.name, 48), new LinearLayout.LayoutParams(dp(48), dp(48))); LinearLayout text = vertical(); text.setPadding(dp(14), 0, 0, 0);
            TextView name = label(peer.name, 17, ink); name.setTypeface(null, Typeface.BOLD); singleLine(name); text.addView(name);
            text.addView(label((controller.isTrustedPeer(peer) ? "可直接连接" : "已发现") + " · " + modeName(peer.mode), 13, muted));
            TextView address = label(peer.detail, 12, muted); singleLine(address); text.addView(address); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> initiatePeer(peer)); peersList.addView(row);
        }
        if (count == 0) peersList.addView(empty(searching ? "正在搜索附近设备…" : "还没有发现设备", mode == Peer.LAN ? "请确认对方已开启接收，并连接同一个 Wi-Fi 或热点。" : "请确认对方已开启接收并允许被发现。"));
    }
    private void renderSettings() {
        nickname.setText(controller == null ? "正在读取…" : controller.nickname);
        List<ChatStore.TrustedDevice> trusted = controller == null ? Collections.emptyList() : controller.trustedDevices;
        if (renderedTrusted == trusted) return; renderedTrusted = trusted; trustedList.removeAllViews();
        for (ChatStore.TrustedDevice device : trusted) {
            TextView info = label("可直接连接 · " + modeName(device.mode) + "\n最近连接 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(device.time)), 13, muted);
            trustedList.addView(settingsRow(device.name, info, () -> deviceInfo(device.id, device.name)));
        }
        if (trusted.isEmpty()) trustedList.addView(label("还没有已信任设备。首次聊天时可选择记住。", 14, muted));
    }
    private void renderChat() {
        String peerId = controller == null ? restoredSelectedId : controller.selectedId;
        String peerName = controller == null ? restoredSelectedName : controller.selectedName;
        chatName.setText(peerName == null ? "聊天" : peerName);
        boolean switched = !Objects.equals(composerPeer, peerId);
        if (switched) {
            if (composerPeer != null) drafts.put(composerPeer, composer.getText().toString()); composerPeer = peerId;
            composer.setText(peerId == null ? "" : drafts.getOrDefault(peerId, ""));
        }
        if (controller != null && controller.savedMessageId != null && !Objects.equals(handledSavedMessage, controller.savedMessageId)) {
            if (Objects.equals(drafts.get(controller.savedMessagePeer), controller.savedMessageBody)) drafts.put(controller.savedMessagePeer, "");
            if (Objects.equals(composerPeer, controller.savedMessagePeer) && composer.getText().toString().equals(controller.savedMessageBody)) composer.setText("");
            handledSavedMessage = controller.savedMessageId;
        }
        if (switched || chatAvatar == null) { chatAvatarBox.removeAllViews(); chatAvatar = avatar(peerId, peerName, 40); chatAvatarBox.addView(chatAvatar, new LinearLayout.LayoutParams(dp(40), dp(40))); }
        boolean ready = controller != null && controller.connected && Objects.equals(peerId, controller.connectedPeerId);
        boolean connecting = controller != null && outgoingRequest && (controller.connecting || controller.hasSession() && !controller.connected);
        chatStatus.setText(ready ? "已连接 · " + modeName(controller.sessionMode) : connecting ? "连接中…" : "未连接 · 本机记录");
        reconnectRow.setVisibility(!ready && peerId != null ? View.VISIBLE : View.GONE);
        boolean trusted = controller != null && controller.isTrusted(peerId);
        reconnectButton.setVisibility(trusted ? View.VISIBLE : View.GONE); reconnectButton.setEnabled(controller != null);
        reconnectButton.setText(connecting ? "取消连接" : controller != null && !controller.error.isEmpty() ? "重试连接" : "连接");
        reconnectHint.setText(connecting ? controller.status : trusted ? controller != null && !controller.error.isEmpty() ? "暂时无法连接。记录和草稿已保留。" : "已记住这部设备，可直接连接。对方需要开启接收。" : "连接后即可发送，草稿会留在这里。");
        composer.setEnabled(peerId != null); updateSend();
        if (controller == null || renderedMessages == controller.messages && Objects.equals(renderedPeer, peerId)) return;
        boolean newPeer = !Objects.equals(renderedPeer, peerId), wasAtBottom = atBottom(); int previousScroll = messageScroll.getScrollY();
        String oldLast = renderedMessages == null || renderedMessages.isEmpty() ? null : renderedMessages.get(renderedMessages.size() - 1).id;
        String newLast = controller.messages.isEmpty() ? null : controller.messages.get(controller.messages.size() - 1).id;
        boolean appended = !newPeer && oldLast != null && newLast != null && !Objects.equals(oldLast, newLast);
        if (renderedPeer != null && newPeer && !Objects.equals(renderedPeer, deferredScrollPeer)) historyPositions.put(renderedPeer, previousScroll);
        if (newPeer) { deferredScrollPeer = peerId != null && historyPositions.containsKey(peerId) ? peerId : null; deferredScrollY = deferredScrollPeer == null ? 0 : historyPositions.get(peerId); }
        renderedPeer = peerId; renderedMessages = controller.messages; final int generation = ++messageGeneration; rebuildingMessages = true; bubbles.removeAllViews();
        if (controller.messages.isEmpty()) bubbles.addView(empty("还没有消息", "连接后，发送你的第一句问候。"));
        else { long previousDay = Long.MIN_VALUE; for (ChatStore.Message message : controller.messages) {
            long day = dayKey(message.time); if (day != previousDay) { TextView date = label(new SimpleDateFormat("M月d日", Locale.getDefault()).format(new Date(message.time)), 12, muted); date.setGravity(Gravity.CENTER); date.setPadding(0, dp(16), 0, dp(10)); bubbles.addView(date); previousDay = day; }
            addBubble(message);
        }}
        updateMessageWidths();
        if (appended && !wasAtBottom && detail) newMessages.setVisibility(View.VISIBLE); if (newPeer) newMessages.setVisibility(View.GONE);
        messageScroll.post(() -> {
            if (generation != messageGeneration || !Objects.equals(renderedPeer, peerId)) return;
            if (Objects.equals(deferredScrollPeer, peerId) && deferredScrollPeer != null) {
                messageScroll.scrollTo(0, deferredScrollY);
                // Selection initially emits an empty list while SQLite loads the history.
                if (!renderedMessages.isEmpty()) deferredScrollPeer = null;
            }
            else if (newPeer || wasAtBottom) messageScroll.scrollTo(0, Math.max(0, bubbles.getHeight() - messageScroll.getHeight()));
            else messageScroll.scrollTo(0, previousScroll);
            rebuildingMessages = false;
        });
    }
    private boolean atBottom() { return bubbles == null || bubbles.getHeight() - messageScroll.getHeight() - messageScroll.getScrollY() < dp(48); }
    private void addBubble(ChatStore.Message message) {
        LinearLayout row = horizontal(); row.setGravity(message.outgoing ? Gravity.END : Gravity.START); row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout bubble = vertical(); bubble.setBackground(shape(message.outgoing ? tonal : surface, 18)); bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView body = label(message.text, 16, ink); body.setTag("messageBody"); body.setTextIsSelectable(true); bubble.addView(body);
        TextView meta = label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.time)) + (message.outgoing ? " · " + message.state : ""), 11, muted);
        meta.setTag("messageMeta"); meta.setPadding(0, dp(5), 0, 0); bubble.addView(meta); row.addView(bubble, new LinearLayout.LayoutParams(-2, -2)); bubbles.addView(row);
    }
    private void updateMessageWidths() {
        if (messageScroll == null || bubbles == null) return;
        int available = messageScroll.getWidth() - messageScroll.getPaddingLeft() - messageScroll.getPaddingRight(); if (available <= 0) return;
        messageWidth = Math.max(dp(64), Math.round(available * .78f) - dp(28));
        for (int i = 0; i < bubbles.getChildCount(); i++) { View row = bubbles.getChildAt(i); TextView body = row.findViewWithTag("messageBody"), meta = row.findViewWithTag("messageMeta"); if (body != null) body.setMaxWidth(messageWidth); if (meta != null) meta.setMaxWidth(messageWidth); }
    }
    private void updateSend() {
        if (sendButton == null || composer == null) return;
        sendButton.setEnabled(controller != null && controller.canSend() && !composer.getText().toString().trim().isEmpty()); style(sendButton, true);
    }
    private void showApproval() {
        String token = controller.approvalId;
        if (approvalDialog != null && !Objects.equals(token, shownApproval)) { approvalDialog.setOnCancelListener(null); approvalDialog.dismiss(); approvalDialog = null; shownApproval = null; }
        if (token == null || approvalDialog != null || isFinishing()) return; shownApproval = token;
        String transport = modeName(controller.sessionMode); String detailText = transport + "连接。记住后，下次可以直接连接。可在设备信息中取消信任。\n\n昵称由对方填写，设备信任不认证真实姓名。";
        if (controller.sessionMode == Peer.LAN) detailText += "局域网消息以明文传输，请使用可信网络。";
        approvalDialog = new AlertDialog.Builder(this).setTitle(safe(controller.approvalName) + "想和你聊天").setMessage(detailText)
            .setPositiveButton("同意并记住", (d, w) -> { if (controller != null) controller.approve(token, true); })
            .setNeutralButton("仅本次", (d, w) -> { if (controller != null) controller.approve(token, false); })
            .setNegativeButton("拒绝", (d, w) -> { if (controller != null) controller.reject(token); }).create();
        approvalDialog.setOnCancelListener(d -> { if (controller != null) controller.reject(token); }); approvalDialog.show();
    }
    private void reconnectSelected() {
        if (controller == null || controller.selectedId == null) return;
        String id = controller.selectedId; requestConnection(new UiAction(UiAction.RECONNECT, controller.preferredMode(id), null, null, id, true));
    }
    private void initiatePeer(Peer peer) {
        if (controller == null) return;
        // Tapping a device is consent for this outgoing attempt; no second approval dialog.
        requestConnection(new UiAction(UiAction.PEER, peer.mode, peer, null, null, controller.isTrustedPeer(peer) || rememberNext));
    }
    private void requestConnection(UiAction action) {
        if (controller != null && controller.connecting) { toast("正在连接，请稍候"); return; }
        if (controller != null && controller.hasSession()) {
            if (action.peerId != null && Objects.equals(action.peerId, controller.connectedPeerId)) { detail = true; render(); return; }
            new AlertDialog.Builder(this).setTitle("结束当前连接，再连接这部设备？").setMessage("当前会话记录和草稿会保留。")
                .setPositiveButton("结束并连接", (d, w) -> { if (controller != null) { pendingAction = action; pendingStage = CLOSE_STAGE; controller.disconnect(); } })
                .setNegativeButton("取消", null).show();
        } else withPermissions(action);
    }
    private void manualConnect() {
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("192.168.1.20:54321"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = vertical(); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(address); CheckBox remember = new CheckBox(this); remember.setText("连接成功后记住这部设备"); remember.setChecked(true); box.addView(remember);
        new AlertDialog.Builder(this).setTitle("通过 IP 地址连接").setMessage("输入对方显示的完整地址和端口。局域网消息以明文传输，请使用可信网络。").setView(box)
            .setPositiveButton("连接", (d, w) -> requestConnection(new UiAction(UiAction.ADDRESS, Peer.LAN, null, address.getText().toString(), null, remember.isChecked())))
            .setNegativeButton("取消", null).show();
    }
    private void startTransport(int chosen) {
        if (controller == null) return; startForegroundService(new Intent(this, ChatService.class).setAction(ChatService.START)); controller.start(chosen);
    }
    private void withPermissions(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        if (action.mode == Peer.BLUETOOTH && !controller.bluetoothAvailable()) { pendingAction = null; pendingStage = 0; toast("这部设备不支持蓝牙，可使用局域网"); return; }
        List<String> request = new ArrayList<>(); for (String permission : requiredPermissions(action)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) request.add(permission);
        SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsAsked", false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            request.add(Manifest.permission.POST_NOTIFICATIONS); prefs.edit().putBoolean("notificationsAsked", true).apply();
        }
        if (!request.isEmpty()) { pendingAction = action; pendingStage = PERMISSION_STAGE; requestPermissions(request.toArray(new String[0]), 7); }
        else ensureEnabled(action);
    }
    private List<String> requiredPermissions(UiAction action) {
        if (action.mode != Peer.BLUETOOTH) return Collections.emptyList();
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> permissions = new ArrayList<>(); permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (action.type == UiAction.SEARCH || action.type == UiAction.PEER) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (action.type == UiAction.DISCOVERABLE) permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE); return permissions;
        }
        return action.type == UiAction.SEARCH ? Collections.singletonList(Manifest.permission.ACCESS_FINE_LOCATION) : Collections.emptyList();
    }
    private boolean bluetoothPermissionsGranted() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private boolean bluetoothEnabled() { try { return controller != null && controller.bluetoothEnabled(); } catch (SecurityException e) { return false; } }
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 7 || pendingAction == null || pendingStage != PERMISSION_STAGE) return; UiAction action = pendingAction;
        for (String permission : requiredPermissions(action)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = null; pendingStage = 0;
            new AlertDialog.Builder(this).setTitle("蓝牙权限尚未允许").setMessage("请允许当前操作需要的附近设备 / 定位权限，也可以切换局域网。")
                .setPositiveButton("应用设置", (d, w) -> appSettings()).setNegativeButton("关闭", null).show(); render(); return;
        }
        ensureEnabled(action);
    }
    private void ensureEnabled(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        try {
            if (action.mode == Peer.BLUETOOTH && !controller.bluetoothEnabled()) { pendingAction = action; pendingStage = ENABLE_STAGE; startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8); }
            else { pendingAction = null; pendingStage = 0; execute(action); }
        } catch (SecurityException e) { pendingAction = null; pendingStage = 0; toast("蓝牙权限已撤销，请重新授权"); }
    }
    private void resumePending() {
        if (pendingAction == null || controller == null) return;
        boolean granted = true; for (String permission : requiredPermissions(pendingAction)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) granted = false;
        if (pendingStage == CLOSE_STAGE) { if (!controller.hasSession() && !controller.connecting) withPermissions(pendingAction); }
        else if (pendingStage == BIND_STAGE || pendingStage == PERMISSION_STAGE && granted) withPermissions(pendingAction);
        else if (pendingStage == ENABLE_STAGE && granted && bluetoothEnabled()) ensureEnabled(pendingAction);
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
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 9); break;
            case UiAction.PEER:
            case UiAction.ADDRESS:
            case UiAction.RECONNECT:
                if (!(action.mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning)) startTransport(action.mode);
                outgoingRequest = true;
                if (action.type == UiAction.PEER) controller.connect(action.peer, action.remember);
                else if (action.type == UiAction.ADDRESS) controller.connectAddress(action.address, action.remember);
                else controller.reconnect(action.peerId);
                break;
        }
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 8 && pendingAction != null && pendingStage == ENABLE_STAGE) {
            UiAction action = pendingAction; if (resultCode == RESULT_OK) { pendingStage = BIND_STAGE; withPermissions(action); }
            else { pendingAction = null; pendingStage = 0; toast("未开启蓝牙，请点按钮重试"); }
        } else if (requestCode == 9) { discoverableUntil = resultCode > 0 ? SystemClock.elapsedRealtime() + resultCode * 1000L : 0; updateDiscoverability(); }
    }
    @SuppressLint("MissingPermission")
    private void updateDiscoverability() {
        if (discoverableButton == null) return; boolean discoverable = false;
        if (bluetoothPermissionsGranted()) try { BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter(); discoverable = adapter != null && adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE; } catch (SecurityException ignored) {}
        long remaining = Math.max(0, (discoverableUntil - SystemClock.elapsedRealtime() + 999) / 1000);
        discoverableButton.setText(discoverable && remaining > 0 ? "可被发现 · 剩余 " + remaining + " 秒" : discoverable ? "当前可被发现" : "允许被发现 120 秒");
        if (!discoverable && remaining > 0) discoverableUntil = 0;
    }
    private void chatMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor); menu.getMenu().add("设备信息"); if (controller != null && controller.hasSession()) menu.getMenu().add("断开连接"); menu.getMenu().add("清空本会话记录");
        menu.setOnMenuItemClickListener(item -> { if (controller == null) return true; String text = item.getTitle().toString();
            if (text.equals("设备信息")) deviceInfo(controller.selectedId, controller.selectedName);
            else if (text.equals("断开连接")) controller.disconnect(); else clearConversation(); return true; }); menu.show();
    }
    private void clearConversation() {
        if (controller == null || controller.selectedId == null) return; String selected = controller.selectedId;
        new AlertDialog.Builder(this).setTitle("清空「" + controller.selectedName + "」的本机消息？").setMessage("设备信任会保留。这个操作无法恢复。")
            .setPositiveButton("清空", (d, w) -> { if (controller != null && Objects.equals(selected, controller.selectedId)) { deferredScrollPeer = null; historyPositions.remove(selected); controller.clearConversation(); } })
            .setNegativeButton("取消", null).show();
    }
    private void deviceInfo(String id, String name) {
        if (controller == null || id == null) return; boolean trusted = controller.isTrusted(id);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(safe(name)).setMessage((trusted ? "已记住这部设备，下次可直接连接。" : "尚未记住这部设备，下次需要确认。") + "\n\n信任基于设备密钥，不认证真实姓名。取消信任不会删除聊天记录。\n\n设备身份\n" + id).setNegativeButton("关闭", null);
        if (trusted) {
            builder.setPositiveButton("取消信任", (d, w) -> confirmRevoke(id, name, false));
            if (Objects.equals(id, controller.connectedPeerId)) builder.setNeutralButton("取消信任并断开", (d, w) -> confirmRevoke(id, name, true));
        }
        builder.show();
    }
    private void confirmRevoke(String id, String name, boolean disconnect) {
        new AlertDialog.Builder(this).setTitle("取消对「" + safe(name) + "」的信任？").setMessage("保留聊天记录。下次来访需要重新同意。")
            .setPositiveButton(disconnect ? "取消信任并断开" : "取消信任", (d, w) -> { if (controller != null) { controller.revokeTrust(id); if (disconnect && Objects.equals(id, controller.connectedPeerId)) controller.disconnect(); } })
            .setNegativeButton("返回", null).show();
    }
    private void editNickname() {
        if (controller == null) return; EditText name = new EditText(this); name.setText(controller.nickname); name.setSingleLine(true); name.setSelectAllOnFocus(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        LinearLayout box = vertical(); box.setPadding(dp(24), dp(8), dp(24), 0); box.addView(name);
        new AlertDialog.Builder(this).setTitle("你的昵称").setView(box).setPositiveButton("保存", (d, w) -> { if (controller != null) controller.setNickname(name.getText().toString()); }).setNegativeButton("取消", null).show();
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("使用说明").setMessage("局域网：双方连接同一个 Wi-Fi 或热点，开启接收后搜索，也可输入完整 IP 地址和端口。局域网文字以明文传输，请使用可信网络。\n\n蓝牙：对方开启接收并允许被发现后搜索，首次连接按系统提示配对。已记住设备可通过历史直接连接，对方仍需开启蓝牙和接收服务。\n\n首次聊天可选择记住设备或仅本次。记住后验证同一设备身份再直接连接；可在设置中取消信任。\n\n待确认表示尚未收到保存回执，已送达表示对方已保存，未确认表示结果未知，不能表示已读。\n\n聊天记录保存在本机。草稿在会话切换和界面重建时保留，停止应用后不保证保留。系统结束应用后需要重新开启接收。")
            .setPositiveButton("知道了", null).show();
    }
    private LinearLayout settingsRow(String title, TextView detailView, Runnable action) {
        LinearLayout row = vertical(); row.setMinimumHeight(dp(64)); row.setPadding(dp(2), dp(12), dp(2), dp(12)); TextView heading = label(title, 16, ink); row.addView(heading); if (detailView != null) row.addView(detailView);
        row.setFocusable(true); row.setClickable(true); row.setBackground(ripple(background, 12)); row.setOnClickListener(v -> action.run()); return row;
    }
    private TextView avatar(String id, String name, int size) {
        String clean = safe(name).trim(); String initial = clean.isEmpty() ? "?" : new String(Character.toChars(clean.codePointAt(0)));
        int index = Math.floorMod(safe(id).hashCode(), 5); String[] fills = isDark() ? new String[]{"#214C3D", "#3D3155", "#243E57", "#514329", "#573438"} : new String[]{"#D8F0E5", "#EEE6FC", "#DFEBFD", "#FFF0D7", "#FBE1E4"};
        String[] inks = isDark() ? new String[]{"#8BDABA", "#CEB6F1", "#AFD2FC", "#EED09D", "#ECB9C0"} : new String[]{"#167554", "#6A40A2", "#1B6598", "#9A6A1D", "#A94D55"};
        TextView avatar = label(initial, size == 48 ? 23 : 20, color(inks[index])); avatar.setTypeface(null, Typeface.BOLD); avatar.setGravity(Gravity.CENTER); avatar.setPadding(0, 0, 0, 0); avatar.setBackground(shape(color(fills[index]), size / 2)); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return avatar;
    }
    private void animatePage(View target) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return; target.animate().cancel(); target.setAlpha(.75f); target.setTranslationY(dp(5)); target.animate().alpha(1f).translationY(0).setDuration(170).start();
    }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); root.requestFocus(); }
    private void appSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(String value) { return Color.parseColor(value); }
    private String safe(String value) { return value == null ? "" : value; }
    private String modeName(int value) { return value == Peer.BLUETOOTH ? "蓝牙" : "局域网"; }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private LinearLayout.LayoutParams topSpace() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(10), 0, 0); return params; }
    private GradientDrawable shape(int fill, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); return shape; }
    private RippleDrawable ripple(int fill, int radius) { return new RippleDrawable(ColorStateList.valueOf(isDark() ? 0x2682D8BA : 0x18246B4E), shape(fill, radius), shape(Color.WHITE, radius)); }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setPadding(0, dp(2), 0, dp(2)); return view; }
    private TextView sectionHeading(String title) { TextView view = label(title, 14, muted); view.setTypeface(null, Typeface.BOLD); view.setPadding(0, dp(20), 0, dp(6)); return view; }
    private void singleLine(TextView view) { view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END); }
    private Button button(String text, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinimumWidth(dp(64)); button.setMinWidth(dp(64)); button.setPadding(dp(14), dp(10), dp(14), dp(10)); style(button, primary); return button;
    }
    private void style(Button button, boolean primary) { button.setBackground(ripple(primary ? accent : surface, 26)); button.setTextColor(primary ? accentInk : ink); button.setAlpha(button.isEnabled() ? 1f : .45f); }
    private ImageView icon(int resource, int tint, String description) { ImageView image = new ImageView(this); image.setImageResource(resource); image.setImageTintList(ColorStateList.valueOf(tint)); image.setScaleType(ImageView.ScaleType.FIT_CENTER); if (!description.isEmpty()) image.setContentDescription(description); return image; }
    private Button iconButton(int resource, String description, int tint) {
        Button button = button("", false); button.setContentDescription(description); button.setBackground(ripple(Color.TRANSPARENT, 24)); button.setMinimumWidth(0); button.setMinWidth(0); button.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.graphics.drawable.Drawable image = getDrawable(resource); if (image != null) { image = image.mutate(); image.setTint(tint); image.setBounds(0, 0, dp(24), dp(24)); button.setCompoundDrawables(image, null, null, null); } return button;
    }
    private LinearLayout empty(String title, String detailText) {
        LinearLayout box = vertical(); box.setGravity(Gravity.CENTER); box.setPadding(dp(12), dp(48), dp(12), dp(32)); TextView titleView = label(title, 18, ink); titleView.setGravity(Gravity.CENTER); box.addView(titleView);
        TextView detailView = label(detailText, 14, muted); detailView.setGravity(Gravity.CENTER); detailView.setPadding(0, dp(8), 0, dp(8)); box.addView(detailView); return box;
    }
    private TextWatcher watcher(Runnable callback) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { callback.run(); } public void afterTextChanged(Editable value) {} }; }
    private long dayKey(long time) { Calendar date = Calendar.getInstance(); date.setTimeInMillis(time); return date.get(Calendar.YEAR) * 1000L + date.get(Calendar.DAY_OF_YEAR); }
    private boolean sameDay(long a, long b) { return a > 0 && dayKey(a) == dayKey(b); }
    private String listTime(long time) {
        long now = System.currentTimeMillis(); if (sameDay(time, now)) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time));
        Calendar yesterday = Calendar.getInstance(); yesterday.add(Calendar.DAY_OF_YEAR, -1); if (sameDay(time, yesterday.getTimeInMillis())) return "昨天";
        return new SimpleDateFormat("M月d日", Locale.getDefault()).format(new Date(time));
    }
}

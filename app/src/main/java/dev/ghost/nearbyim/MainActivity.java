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
import dev.ghost.nearbyim.i18n.LanguageRegistry;
import dev.ghost.nearbyim.i18n.UiText;
import java.util.*;
import dev.ghost.nearbyim.core.AttachmentInfo;
import dev.ghost.nearbyim.core.AttachmentRecord;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.nio.file.Path;

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
    private TextView chatName, chatStatus, chatAvatar, nickname, reconnectHint, languageValue;
    private Button lanTab, bluetoothTab, receiveButton, searchButton, discoverableButton, manualButton, sendButton, reconnectButton, newMessages;
    private Button newChatButton, cancelConnectionButton,fileButton,photoButton;
    private String pendingAttachmentPeer,pendingExportPeer,pendingExportRecord;
    private long pendingAttachmentToken;
    private final java.util.concurrent.ThreadPoolExecutor imageWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(16),r->{Thread thread=new Thread(r,"attachment-thumbnail");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String,Bitmap> thumbnails=new LinkedHashMap<>();
    private final Set<String> loadingThumbnails=new HashSet<>(),thumbnailTargets=new HashSet<>();
    private final LinkedHashSet<String> failedThumbnails=new LinkedHashSet<>();
    private ImageView networkIcon;
    private ProgressBar scanProgress;
    private EditText composer, conversationSearch;
    private ScrollView messageScroll, historyScroll, nearbyScroll, settingsScroll;
    private int restoredHistoryY = -1, restoredNearbyY = -1, restoredSettingsY = -1;
    private boolean restoreUnread;
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
    protected void attachBaseContext(Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved == null) { SharedPreferences preferences = getSharedPreferences("ui", MODE_PRIVATE); mode = preferences.getInt("lastMode", Peer.LAN); rememberNext = preferences.getBoolean("rememberNext", true); }
        if (saved != null) {
            pendingAttachmentPeer=saved.getString("pendingAttachmentPeer");pendingAttachmentToken=saved.getLong("pendingAttachmentToken");pendingExportPeer=saved.getString("pendingExportPeer");pendingExportRecord=saved.getString("pendingExportRecord");
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
        if (saved != null) {
            composer.setText(saved.getString("draft", ""));
            int length = composer.length();
            composer.setSelection(Math.min(length, Math.max(0, saved.getInt("composerStart", length))),
                    Math.min(length, Math.max(0, saved.getInt("composerEnd", length))));
            restoredHistoryY = saved.getInt("historyY", -1); restoredNearbyY = saved.getInt("nearbyY", -1); restoredSettingsY = saved.getInt("settingsY", -1);
            restoreUnread = saved.getBoolean("unreadVisible");
            int searchLength = conversationSearch.length();
            conversationSearch.setSelection(Math.min(searchLength, Math.max(0, saved.getInt("searchStart", searchLength))),
                    Math.min(searchLength, Math.max(0, saved.getInt("searchEnd", searchLength))));
            if (saved.getInt("focusedInput") == 1) composer.requestFocus();
            else if (saved.getInt("focusedInput") == 2) conversationSearch.requestFocus();
        }
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
    protected void onDestroy(){imageWorker.shutdownNow();thumbnails.clear();super.onDestroy();}
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingAttachmentPeer",pendingAttachmentPeer);state.putLong("pendingAttachmentToken",pendingAttachmentToken);state.putString("pendingExportPeer",pendingExportPeer);state.putString("pendingExportRecord",pendingExportRecord);
        saveDraftAndPosition();
        state.putInt("mode", mode); state.putInt("page", page); state.putBoolean("detail", detail); state.putString("draft", composer.getText().toString());
        state.putString("selectedId", controller == null ? restoredSelectedId : controller.selectedId);
        state.putString("selectedName", controller == null ? restoredSelectedName : controller.selectedName);
        state.putString("composerPeer", composerPeer); state.putString("handledSavedMessage", handledSavedMessage);
        state.putBoolean("outgoingRequest", outgoingRequest); state.putString("searchQuery", searchQuery); state.putBoolean("lanDetails", lanDetails); state.putBoolean("rememberNext", rememberNext);
        state.putLong("discoverableUntil", discoverableUntil);
        state.putInt("composerStart", composer.getSelectionStart()); state.putInt("composerEnd", composer.getSelectionEnd());
        state.putInt("searchStart", conversationSearch.getSelectionStart()); state.putInt("searchEnd", conversationSearch.getSelectionEnd());
        state.putInt("focusedInput", composer.hasFocus() ? 1 : conversationSearch.hasFocus() ? 2 : 0);
        state.putInt("historyY", restoredHistoryY >= 0 ? restoredHistoryY : historyScroll.getScrollY());
        state.putInt("nearbyY", restoredNearbyY >= 0 ? restoredNearbyY : nearbyScroll.getScrollY());
        state.putInt("settingsY", restoredSettingsY >= 0 ? restoredSettingsY : settingsScroll.getScrollY());
        state.putBoolean("unreadVisible", newMessages.getVisibility() == View.VISIBLE || restoreUnread);
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
        pageTitle = label(t("app"), 32, ink); pageTitle.setTypeface(null, Typeface.BOLD); header.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout indicator = horizontal(); indicator.setGravity(Gravity.CENTER_VERTICAL); networkIcon = icon(R.drawable.outline_wifi_24, accent, "");
        networkIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); indicator.addView(networkIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout networkText = vertical(); networkText.setPadding(dp(6), 0, 0, 0); networkName = label("", 13, ink); networkName.setTypeface(null, Typeface.BOLD);
        networkState = label(t("idle"), 12, muted); networkText.addView(networkName); networkText.addView(networkState); indicator.addView(networkText);
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
        String[] labels = {t("chats"), t("nearby"), t("settings")}; int[] icons = {R.drawable.outline_chat_bubble_24, R.drawable.outline_wifi_tethering_24, R.drawable.outline_settings_24};
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
        conversationSearch.setHint(t("searchChats")); conversationSearch.setSingleLine(true); conversationSearch.setBackgroundColor(Color.TRANSPARENT); conversationSearch.setPadding(dp(10), dp(10), dp(4), dp(10));
        conversationSearch.setInputType(InputType.TYPE_CLASS_TEXT); conversationSearch.setText(searchQuery); searchRow.addView(conversationSearch, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = iconButton(R.drawable.outline_close_24, t("clearSearch"), muted); clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> conversationSearch.setText("")); searchRow.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2); searchParams.setMargins(dp(16), dp(10), dp(16), dp(12)); homePage.addView(searchRow, searchParams);
        conversationSearch.addTextChangedListener(watcher(() -> { searchQuery = conversationSearch.getText().toString(); clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE); renderHistory(); }));
        homeFrame = new FrameLayout(this); homePage.addView(homeFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        historyScroll = new ScrollView(this); historyScroll.setClipToPadding(false); historyScroll.setPadding(dp(16), 0, dp(16), dp(88)); historyList = vertical();
        historyScroll.addView(historyList); homeFrame.addView(historyScroll, new FrameLayout.LayoutParams(-1, -1));
        newChatButton = button(t("newChat"), true);
        android.graphics.drawable.Drawable addIcon = getDrawable(R.drawable.outline_add_24);
        if (addIcon != null) { addIcon = addIcon.mutate(); addIcon.setTint(accentInk); addIcon.setBounds(0, 0, dp(24), dp(24)); newChatButton.setCompoundDrawablesRelative(addIcon, null, null, null); }
        newChatButton.setCompoundDrawableTintList(ColorStateList.valueOf(accentInk)); newChatButton.setCompoundDrawablePadding(dp(8)); newChatButton.setTextSize(16);
        newChatButton.setPadding(dp(22), dp(12), dp(22), dp(12)); newChatButton.setMinHeight(dp(56)); newChatButton.setElevation(dp(3)); newChatButton.setContentDescription(t("newChat"));
        newChatButton.setOnClickListener(v -> navigate(1)); FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(dp(16), dp(16), dp(16), dp(16)); homeFrame.addView(newChatButton, fabParams);
    }
    private void buildNearby() {
        ScrollView scroll = nearbyScroll = new ScrollView(this); LinearLayout nearbyContent = vertical(); nearbyContent.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(nearbyContent); nearbyPage.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout modes = horizontal(); modes.setPadding(dp(4), dp(4), dp(4), dp(4)); modes.setBackground(shape(surface, 28));
        lanTab = button(t("lan"), true); bluetoothTab = button(t("bluetooth"), false); lanTab.setOnClickListener(v -> chooseMode(Peer.LAN));
        bluetoothTab.setOnClickListener(v -> chooseMode(Peer.BLUETOOTH)); modes.addView(lanTab, new LinearLayout.LayoutParams(0, -2, 1)); modes.addView(bluetoothTab, new LinearLayout.LayoutParams(0, -2, 1)); nearbyContent.addView(modes);
        modeHint = label("", 14, muted); modeHint.setPadding(0, dp(16), 0, dp(8)); nearbyContent.addView(modeHint);
        rememberDevice = new CheckBox(this); rememberDevice.setText(t("rememberFirstConnection")); rememberDevice.setTextSize(14); rememberDevice.setTextColor(muted); rememberDevice.setChecked(rememberNext);
        rememberDevice.setMinHeight(dp(48)); rememberDevice.setOnCheckedChangeListener((button, checked) -> { rememberNext = checked; getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("rememberNext", checked).apply(); }); nearbyContent.addView(rememberDevice);
        bluetoothState = label("", 13, muted); nearbyContent.addView(bluetoothState);
        LinearLayout receiveRow = horizontal(); receiveRow.setGravity(Gravity.CENTER_VERTICAL); receiveStatus = label(t("receptionOff"), 16, ink);
        receiveRow.addView(receiveStatus, new LinearLayout.LayoutParams(0, -2, 1)); receiveButton = button(t("startReception"), false);
        receiveButton.setOnClickListener(v -> {
            if (controller == null) return;
            if (mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning) {
                if (controller.hasSession() && controller.sessionMode == mode)
                    new AlertDialog.Builder(this).setTitle(t("stopReceptionTitle")).setMessage(t("historyTrustRetained"))
                        .setPositiveButton(t("stopReception"), (d, w) -> { if (controller != null) controller.stop(mode); }).setNegativeButton(t("cancel"), null).show();
                else controller.stop(mode);
            } else withPermissions(new UiAction(UiAction.START, mode));
        }); receiveRow.addView(receiveButton); nearbyContent.addView(receiveRow);
        Button details = button(t("viewCopyAddress"), false); details.setTag("lanDetails"); details.setOnClickListener(v -> { lanDetails = !lanDetails; render(); }); nearbyContent.addView(details, topSpace());
        connectionInfo = label("", 13, muted); connectionInfo.setTextIsSelectable(true); connectionInfo.setPadding(0, dp(8), 0, dp(8));
        connectionInfo.setOnLongClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(t("localAddress"), connectionInfo.getText())); toast(t("addressCopied")); return true; }); nearbyContent.addView(connectionInfo);
        searchButton = button(t("searchDevices"), true); searchButton.setOnClickListener(v -> {
            if (controller != null && (mode == Peer.LAN ? controller.lanSearching : controller.bluetoothSearching)) controller.stopSearch(mode);
            else withPermissions(new UiAction(UiAction.SEARCH, mode));
        }); nearbyContent.addView(searchButton, topSpace());
        discoverableButton = button(t("allowDiscoverable"), false); discoverableButton.setOnClickListener(v -> withPermissions(new UiAction(UiAction.DISCOVERABLE, Peer.BLUETOOTH))); nearbyContent.addView(discoverableButton, topSpace());
        manualButton = button(t("direct"), false); manualButton.setOnClickListener(v -> manualConnect()); nearbyContent.addView(manualButton, topSpace());
        requestStatus = label("", 14, muted); requestStatus.setPadding(0, dp(12), 0, dp(4)); requestStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); nearbyContent.addView(requestStatus);
        cancelConnectionButton = button(t("cancelConnection"), false); cancelConnectionButton.setOnClickListener(v -> { outgoingRequest = false; if (controller != null) controller.disconnect(); }); nearbyContent.addView(cancelConnectionButton, topSpace());
        LinearLayout devicesHeader = horizontal(); devicesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView devicesHeading = sectionHeading(t("nearbyDevices")); devicesHeader.addView(devicesHeading, new LinearLayout.LayoutParams(0, -2, 1));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall); scanProgress.setIndeterminateTintList(ColorStateList.valueOf(accent)); scanProgress.setContentDescription(t("searchingDevices"));
        devicesHeader.addView(scanProgress, new LinearLayout.LayoutParams(dp(24), dp(24))); nearbyContent.addView(devicesHeader); peersList = vertical(); nearbyContent.addView(peersList);
    }
    private void buildSettings() {
        ScrollView scroll = settingsScroll = new ScrollView(this); LinearLayout settingsContent = vertical(); settingsContent.setPadding(dp(16), dp(12), dp(16), dp(24)); scroll.addView(settingsContent); settingsPage.addView(scroll);
        settingsContent.addView(sectionHeading(t("deviceSection"))); nickname = label("", 14, muted); settingsContent.addView(settingsRow(t("nickname"), nickname, this::editNickname));
        languageValue = label("", 14, muted); settingsContent.addView(settingsRow(t("language"), languageValue, this::chooseLanguage));
        settingsContent.addView(sectionHeading(t("trustedDevices"))); trustedList = vertical(); settingsContent.addView(trustedList);
        settingsContent.addView(sectionHeading(t("appSection"))); settingsContent.addView(settingsRow(t("appPermissions"), label(t("permissionsSummary"), 13, muted), this::appSettings));
        settingsContent.addView(settingsRow(t("stopAll"), label(t("stopConnectionsSummary"), 13, muted), () -> {
            if (controller != null) new AlertDialog.Builder(this).setTitle(t("stopConnectionsTitle")).setMessage(t("stopConnectionsBody"))
                .setPositiveButton(t("stop"), (d, w) -> { if (controller != null) controller.stopAll(); }).setNegativeButton(t("cancel"), null).show();
        }));
        settingsContent.addView(settingsRow(t("help"), null, this::showHelp));
        settingsContent.addView(settingsRow(t("about"), label(t("androidAboutSummary", LanguageRegistry.VERSION), 13, muted), () -> new AlertDialog.Builder(this).setTitle(t("app"))
            .setMessage(t("androidAboutBody", LanguageRegistry.VERSION))
            .setPositiveButton(t("gotIt"), null).show()));
    }
    private void buildChat() {
        LinearLayout chatHeader = horizontal(); chatHeader.setGravity(Gravity.CENTER_VERTICAL); chatHeader.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = iconButton(R.drawable.outline_arrow_back_24, t("backToChats"), ink); back.setOnClickListener(v -> handleBack()); chatHeader.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chatAvatarBox = vertical(); chatHeader.addView(chatAvatarBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = vertical(); names.setPadding(dp(12), 0, dp(4), 0); chatName = label("", 18, ink); chatName.setTypeface(null, Typeface.BOLD); singleLine(chatName);
        chatStatus = label(t("idle"), 12, muted); names.addView(chatName); names.addView(chatStatus); chatHeader.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = iconButton(R.drawable.outline_more_vert_24, t("more"), ink); more.setOnClickListener(this::chatMenu); chatHeader.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48))); chatPage.addView(chatHeader);
        reconnectRow = vertical(); reconnectRow.setPadding(dp(16), dp(4), dp(16), dp(10)); reconnectHint = label("", 13, muted); reconnectRow.addView(reconnectHint);
        LinearLayout reconnectActions = horizontal(); reconnectButton = button(t("reconnect"), true); reconnectButton.setOnClickListener(v -> { if (controller != null && outgoingRequest && (controller.connecting || controller.hasSession() && !controller.connected)) { outgoingRequest = false; controller.disconnect(); } else reconnectSelected(); });
        reconnectActions.addView(reconnectButton, new LinearLayout.LayoutParams(0, -2, 1)); Button find = button(t("findNearby"), false); find.setOnClickListener(v -> navigate(1));
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(0, -2, 1); findParams.setMarginStart(dp(8)); reconnectActions.addView(find, findParams); reconnectRow.addView(reconnectActions); chatPage.addView(reconnectRow);
        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false); messageScroll.setPadding(dp(16), 0, dp(16), 0);
        bubbles = vertical(); bubbles.setPadding(0, dp(8), 0, dp(12)); messageScroll.addView(bubbles); chatPage.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        messageScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean followedBottom = ob > ot && bubbles.getHeight() - (ob - ot) - messageScroll.getScrollY() < dp(48);
            if (r - l != or - ol) updateMessageWidths();
            if (detail && followedBottom && b - t < ob - ot) messageScroll.post(() -> messageScroll.scrollTo(0, Math.max(0, bubbles.getHeight() - messageScroll.getHeight())));
        });
        messageScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (!rebuildingMessages && atBottom()) newMessages.setVisibility(View.GONE); });
        newMessages = button(t("newMessages"), false); newMessages.setVisibility(View.GONE); newMessages.setOnClickListener(v -> { messageScroll.smoothScrollTo(0, bubbles.getHeight()); newMessages.setVisibility(View.GONE); });
        chatPage.addView(newMessages, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout attachmentRow=horizontal();attachmentRow.setPadding(dp(16),dp(4),dp(16),0);
        fileButton=button(t("sendFile"),false);photoButton=button(t("sendPhoto"),false);fileButton.setContentDescription(t("sendFile"));photoButton.setContentDescription(t("sendPhoto"));
        fileButton.setOnClickListener(v->chooseAttachment(false));photoButton.setOnClickListener(v->chooseAttachment(true));attachmentRow.addView(fileButton);attachmentRow.addView(photoButton);chatPage.addView(attachmentRow);
        TextView attachmentHint=label(t("attachmentHint"),11,muted);attachmentHint.setPadding(dp(16),0,dp(16),0);chatPage.addView(attachmentHint);
        LinearLayout inputRow = horizontal(); inputRow.setGravity(Gravity.BOTTOM); inputRow.setPadding(dp(16), dp(8), dp(16), dp(10));
        composer = new EditText(this); composer.setTextColor(ink); composer.setHintTextColor(muted); composer.setTextSize(16); composer.setHint(t("androidComposer")); composer.setBackground(shape(surface, 24));
        composer.setPadding(dp(16), dp(12), dp(16), dp(12)); composer.setMinHeight(dp(48)); composer.setMaxLines(4); composer.setVerticalScrollBarEnabled(true);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        inputRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1)); sendButton = button(t("send"), true); sendButton.setOnClickListener(v -> { if (controller != null) controller.send(composer.getText().toString()); });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, -2); sendParams.setMarginStart(dp(8)); inputRow.addView(sendButton, sendParams); chatPage.addView(inputRow);
        composer.addTextChangedListener(watcher(this::updateSend));
    }
    private void render() {
        if (root == null) return;
        header.setVisibility(detail ? View.GONE : View.VISIBLE); bottomNav.setVisibility(detail ? View.GONE : View.VISIBLE);
        View divider = root.findViewWithTag("navDivider"); if (divider != null) divider.setVisibility(detail ? View.GONE : View.VISIBLE);
        homePage.setVisibility(!detail && page == 0 ? View.VISIBLE : View.GONE); nearbyPage.setVisibility(!detail && page == 1 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(!detail && page == 2 ? View.VISIBLE : View.GONE); chatPage.setVisibility(detail ? View.VISIBLE : View.GONE);
        pageTitle.setText(page == 0 ? t("app") : page == 1 ? t("nearby") : t("settings"));
        for (int i = 0; i < 3; i++) {
            navItems[i].setSelected(i == page); navItems[i].findViewWithTag("pill").setBackground(shape(i == page ? tonal : Color.TRANSPARENT, 20));
            navIcons[i].setImageTintList(ColorStateList.valueOf(i == page ? accent : muted)); navLabels[i].setTextColor(i == page ? accent : muted); navLabels[i].setTypeface(null, i == page ? Typeface.BOLD : Typeface.NORMAL);
        }
        errorView.setVisibility(controller != null && !UiText.EMPTY.equals(controller.error) ? View.VISIBLE : View.GONE); errorView.setText(controller == null ? "" : t(controller.error));
        boolean connected = controller != null && controller.connected && controller.connectedPeerId != null;
        boolean connecting = controller != null && (controller.connecting || controller.hasSession() && !connected);
        networkName.setText(connected ? modeName(controller.sessionMode) : ""); networkName.setVisibility(connected ? View.VISIBLE : View.GONE);
        networkState.setText(connected ? t("connectedIndicator") : connecting ? t("connecting") : t("idle")); networkState.setTextColor(connected ? accent : muted);
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
        renderedConversations = conversations; renderedConnection = connection; renderedQuery = searchQuery; int oldY = controller != null && restoredHistoryY >= 0 ? restoredHistoryY : historyScroll.getScrollY();
        if (controller != null) restoredHistoryY = -1; historyList.removeAllViews();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT); int count = 0; String previousGroup = null;
        for (ChatStore.Conversation conversation : conversations) {
            if (!safe(conversation.name).toLowerCase(Locale.ROOT).contains(query)) continue;
            String group = sameDay(conversation.time, System.currentTimeMillis()) ? t("today") : t("earlier");
            if (query.isEmpty() && !group.equals(previousGroup)) { historyList.addView(sectionHeading(group)); previousGroup = group; }
            count++; boolean active = controller != null && controller.connected && Objects.equals(connection, conversation.id);
            LinearLayout row = horizontal(); row.setGravity(Gravity.TOP); row.setPadding(0, dp(14), 0, dp(14)); row.setMinimumHeight(dp(active ? 98 : 82)); row.setBackground(ripple(background, 14));
            TextView avatar = avatar(conversation.id, conversation.name, 48); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout text = vertical(); text.setPadding(dp(14), 0, dp(8), 0); LinearLayout titleRow = horizontal(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(safe(conversation.name), 17, ink); name.setTypeface(null, Typeface.BOLD); singleLine(name); titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) { TextView dot = label(" ●", 13, accent); dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); titleRow.addView(dot); }
            text.addView(titleRow); if (active) text.addView(label(t("connectedVia", modeName(controller.sessionMode)), 13, muted));
            String preview = safe(conversation.preview); if (preview.isEmpty()) preview = t("noMessageTitle");
            else if (conversation.outgoing && !safe(conversation.state).isEmpty()) preview = t("chatPreviewState", AndroidText.messageState(this, conversation.state), preview);
            TextView summary = label(preview, 15, muted); singleLine(summary); text.addView(summary); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView time = label(conversation.time > 0 ? listTime(conversation.time) : "", 12, muted); time.setGravity(Gravity.END); row.addView(time);
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> openConversation(conversation)); historyList.addView(row);
        }
        boolean emptyHistory = conversations.isEmpty(); newChatButton.setVisibility(emptyHistory ? View.GONE : View.VISIBLE);
        historyScroll.setPadding(dp(16), 0, dp(16), emptyHistory ? dp(16) : dp(88));
        if (count == 0) {
            if (emptyHistory && query.isEmpty()) {
                LinearLayout empty = empty(t("noChatsTitle"), t("noChatsBody")); Button find = button(t("findPeopleNearby"), true); find.setOnClickListener(v -> navigate(1)); empty.addView(find, topSpace()); historyList.addView(empty);
            } else historyList.addView(empty(t("noSearchResultsTitle"), t("noSearchResultsBody")));
        }
        historyScroll.post(() -> historyScroll.scrollTo(0, oldY));
    }
    private void openConversation(ChatStore.Conversation conversation) {
        if (controller == null) return; saveDraftAndPosition(); detail = true; page = 0; hideKeyboard(); controller.selectConversation(conversation); render(); animatePage(chatPage);
    }
    private void renderNearby() {
        boolean attached = controller != null; style(lanTab, mode == Peer.LAN); style(bluetoothTab, mode == Peer.BLUETOOTH);
        lanTab.setSelected(mode == Peer.LAN); bluetoothTab.setSelected(mode == Peer.BLUETOOTH);
        modeHint.setText(mode == Peer.LAN ? t("androidLanHint") : t("androidBluetoothHint"));
        boolean running = attached && (mode == Peer.LAN ? controller.lanRunning : controller.bluetoothRunning);
        boolean searching = attached && (mode == Peer.LAN ? controller.lanSearching : controller.bluetoothSearching);
        String info = !attached ? "" : t(mode == Peer.LAN ? controller.lanInfo : controller.bluetoothInfo);
        boolean readyToReceive = running && (mode == Peer.LAN ? controller.lanListening : controller.bluetoothListening);
        receiveStatus.setText(!running ? t("receptionOff") : readyToReceive ? t("receiving") : t("startingReception")); receiveButton.setText(running ? t("stopReception") : t("startReception")); receiveButton.setEnabled(attached);
        scanProgress.setVisibility(searching ? View.VISIBLE : View.GONE); searchButton.setText(searching ? t("stopSearch") : t("searchDevices")); searchButton.setEnabled(attached && !controller.connecting);
        manualButton.setVisibility(mode == Peer.LAN ? View.VISIBLE : View.GONE); manualButton.setEnabled(attached && !controller.connecting);
        discoverableButton.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE); discoverableButton.setEnabled(attached && controller.bluetoothAvailable());
        boolean waiting = attached && (controller.connecting || controller.hasSession() && !controller.connected);
        requestStatus.setVisibility(waiting ? View.VISIBLE : View.GONE); requestStatus.setText(waiting ? t(controller.status) : "");
        cancelConnectionButton.setVisibility(waiting ? View.VISIBLE : View.GONE);
        View details = nearbyPage.findViewWithTag("lanDetails"); details.setVisibility(mode == Peer.LAN && running ? View.VISIBLE : View.GONE);
        connectionInfo.setVisibility(mode == Peer.LAN && running && lanDetails ? View.VISIBLE : View.GONE); connectionInfo.setText(info);
        bluetoothState.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE);
        if (mode == Peer.BLUETOOTH) {
            String bt = !attached ? t("serviceConnecting") : !controller.bluetoothAvailable() ? t("androidBluetoothUnavailable") : !bluetoothPermissionsGranted() ? t("nearbyPermissionMissing") : bluetoothEnabled() ? t("bluetoothOn") : t("bluetoothOffSearch");
            bluetoothState.setText(bt); searchButton.setEnabled(attached && controller.bluetoothAvailable() && !controller.connecting); updateDiscoverability();
        }
        style(receiveButton, false); style(searchButton, true); style(manualButton, false); style(discoverableButton, false);
        StringBuilder signature = new StringBuilder().append(mode).append(searching).append(attached);
        if (attached) for (Peer peer : controller.peers.values()) if (peer.mode == mode) signature.append(peer.key).append(peer.name).append(peer.detail).append(peer.paired).append(controller.isTrustedPeer(peer));
        if (Objects.equals(peerSignature, signature.toString())) return; peerSignature = signature.toString(); peersList.removeAllViews(); int count = 0;
        if (attached) for (Peer peer : controller.peers.values()) if (peer.mode == mode && count++ < 50) {
            LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(82)); row.setPadding(0, dp(12), 0, dp(12)); row.setBackground(ripple(background, 12));
            row.addView(avatar(peer.key, peerName(peer), 48), new LinearLayout.LayoutParams(dp(48), dp(48))); LinearLayout text = vertical(); text.setPadding(dp(14), 0, 0, 0);
            TextView name = label(peerName(peer), 17, ink); name.setTypeface(null, Typeface.BOLD); singleLine(name); text.addView(name);
            text.addView(label(t("peerStatusTransport", controller.isTrustedPeer(peer) ? t("trustedConnect") : t("discovered"), modeName(peer.mode)), 13, muted));
            TextView address = label(peer.mode == Peer.BLUETOOTH ? t(peer.paired ? "pairedAddress" : "unpairedAddress", peer.detail) : peer.detail, 12, muted); singleLine(address); text.addView(address); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> initiatePeer(peer)); peersList.addView(row);
        }
        if (attached && restoredNearbyY >= 0) { final int y = restoredNearbyY; restoredNearbyY = -1; nearbyScroll.post(() -> nearbyScroll.scrollTo(0, y)); }
        if (count == 0) peersList.addView(empty(searching ? t("searchingNearby") : t("noDevicesTitle"), mode == Peer.LAN ? t("lanNoDevicesBody") : t("bluetoothNoDevicesBody")));
    }
    private void renderSettings() {
        nickname.setText(controller == null ? t("loading") : controller.nickname);
        String selection = AppLanguage.selection(this);
        languageValue.setText(LanguageRegistry.SYSTEM.equals(selection) ? t("systemLanguage") : LanguageRegistry.resolve(selection, AppLanguage.systemLocale(this)).nativeName);
        List<ChatStore.TrustedDevice> trusted = controller == null ? Collections.emptyList() : controller.trustedDevices;
        if (renderedTrusted == trusted) return; renderedTrusted = trusted; trustedList.removeAllViews();
        for (ChatStore.TrustedDevice device : trusted) {
            TextView info = label(t("trustedLastConnection", modeName(device.mode), AndroidText.date(this, device.time, "yMMMdjm")), 13, muted);
            trustedList.addView(settingsRow(device.name, info, () -> deviceInfo(device.id, device.name)));
        }
        if (trusted.isEmpty()) trustedList.addView(label(t("noTrustedDevices"), 14, muted));
        if (controller != null && restoredSettingsY >= 0) { final int y = restoredSettingsY; restoredSettingsY = -1; settingsScroll.post(() -> settingsScroll.scrollTo(0, y)); }
    }
    private void renderChat() {
        String peerId = controller == null ? restoredSelectedId : controller.selectedId;
        String peerName = controller == null ? restoredSelectedName : controller.selectedName;
        chatName.setText(peerName == null ? t("chats") : peerName);
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
        chatStatus.setText(ready ? t("connectedVia", modeName(controller.sessionMode)) : connecting ? t("connecting") : t("localHistoryDisconnected"));
        reconnectRow.setVisibility(!ready && peerId != null ? View.VISIBLE : View.GONE);
        boolean trusted = controller != null && controller.isTrusted(peerId);
        reconnectButton.setVisibility(trusted ? View.VISIBLE : View.GONE); reconnectButton.setEnabled(controller != null);
        reconnectButton.setText(connecting ? t("cancelConnection") : controller != null && !UiText.EMPTY.equals(controller.error) ? t("retryConnection") : t("reconnect"));
        reconnectHint.setText(connecting ? t(controller.status) : trusted ? controller != null && !UiText.EMPTY.equals(controller.error) ? t("reconnectUnavailable") : t("reconnectRemembered") : t("draftRetainedHint"));
        composer.setEnabled(peerId != null);fileButton.setEnabled(controller!=null&&controller.canSendAttachment());photoButton.setEnabled(controller!=null&&controller.canSendAttachment());updateSend();
        if (controller == null || renderedMessages == controller.messages && Objects.equals(renderedPeer, peerId)) return;
        boolean newPeer = !Objects.equals(renderedPeer, peerId), wasAtBottom = atBottom(); int previousScroll = messageScroll.getScrollY();
        String oldLast = renderedMessages == null || renderedMessages.isEmpty() ? null : renderedMessages.get(renderedMessages.size() - 1).id;
        String newLast = controller.messages.isEmpty() ? null : controller.messages.get(controller.messages.size() - 1).id;
        boolean appended = !newPeer && oldLast != null && newLast != null && !Objects.equals(oldLast, newLast);
        if (renderedPeer != null && newPeer && !Objects.equals(renderedPeer, deferredScrollPeer)) historyPositions.put(renderedPeer, previousScroll);
        if (newPeer) { deferredScrollPeer = peerId != null && historyPositions.containsKey(peerId) ? peerId : null; deferredScrollY = deferredScrollPeer == null ? 0 : historyPositions.get(peerId); }
        thumbnailTargets.clear();for(int i=controller.messages.size()-1;i>=0&&thumbnailTargets.size()<12;i--){AttachmentRecord a=controller.messages.get(i).attachment;if(a!=null&&!a.outgoing&&a.state.equals("received")&&a.info.mime.startsWith("image/"))thumbnailTargets.add(a.info.id);}
        renderedPeer = peerId; renderedMessages = controller.messages; final int generation = ++messageGeneration; rebuildingMessages = true; bubbles.removeAllViews();
        if (controller.messages.isEmpty()) bubbles.addView(empty(t("noMessageTitle"), t("firstMessageHint")));
        else { long previousDay = Long.MIN_VALUE; for (ChatStore.Message message : controller.messages) {
            long day = dayKey(message.time); if (day != previousDay) { TextView date = label(AndroidText.date(this, message.time, "MMMd"), 12, muted); date.setGravity(Gravity.CENTER); date.setPadding(0, dp(16), 0, dp(10)); bubbles.addView(date); previousDay = day; }
            addBubble(message);
        }}
        updateMessageWidths();
        if (appended && !wasAtBottom && detail) newMessages.setVisibility(View.VISIBLE); if (newPeer) newMessages.setVisibility(View.GONE);
        if (restoreUnread && !controller.messages.isEmpty()) { newMessages.setVisibility(View.VISIBLE); restoreUnread = false; }
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
        AttachmentRecord attachment=message.attachment;
        String messageText=attachment==null?message.text:t("attachmentDetails",attachment.info.name,attachment.transferred,attachment.info.size,attachmentState(attachment));
        TextView body = label(messageText, 16, ink); body.setTag("messageBody"); body.setTextIsSelectable(true); bubble.addView(body);
        if(attachment!=null){
            String peer=controller.selectedId;
            if(!attachment.outgoing&&attachment.state.equals("received")&&attachment.info.mime.startsWith("image/")&&thumbnailTargets.contains(attachment.info.id)){
                ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setMaxWidth(dp(220));image.setMaxHeight(dp(220));image.setContentDescription(t("attachmentPhotoPreview",attachment.info.name));bubble.addView(image);thumbnail(peer,attachment,image);
            }
            if(attachment.active()&&!attachment.state.equals("offered")&&!attachment.state.equals("preparing")){
                ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);int percent=attachment.info.size==0?0:(int)(100*attachment.transferred/attachment.info.size);progress.setMax(100);progress.setProgress(percent);progress.setContentDescription(t("attachmentProgress",percent,attachmentState(attachment)));bubble.addView(progress,new LinearLayout.LayoutParams(dp(220),dp(20)));
            }
            LinearLayout controls=vertical();
            if(!attachment.outgoing&&attachment.state.equals("offered")){attachmentButton(controls,t("attachmentAccept"),()->controller.attachmentAction(peer,message.id,message.outgoing,"accept"));attachmentButton(controls,t("attachmentReject"),()->controller.attachmentAction(peer,message.id,message.outgoing,"reject"));}
            else if(attachment.active())attachmentButton(controls,t("cancel"),()->controller.attachmentAction(peer,message.id,message.outgoing,"cancel"));
            else if(!attachment.outgoing&&attachment.state.equals("received")){attachmentButton(controls,t("attachmentOpen"),()->openAttachment(peer,attachment));attachmentButton(controls,t("attachmentSaveAs"),()->saveAttachment(peer,attachment));}
            bubble.addView(controls);
        }
        TextView meta = label(message.outgoing ? t("outgoingMessageMeta", AndroidText.date(this, message.time, "jm"), AndroidText.messageState(this, message.state)) : AndroidText.date(this, message.time, "jm"), 11, muted);
        meta.setTag("messageMeta"); meta.setPadding(0, dp(5), 0, 0); bubble.addView(meta); row.addView(bubble, new LinearLayout.LayoutParams(-2, -2)); bubbles.addView(row);
    }
    private String attachmentState(AttachmentRecord record){return t(record.stateKey());}
    private void attachmentButton(LinearLayout row,String text,Runnable action){Button button=button(text,false);button.setContentDescription(text);button.setOnClickListener(v->{if(controller!=null)action.run();});row.addView(button);}
    private void chooseAttachment(boolean photo){
        if(controller==null||!controller.canSendAttachment())return;pendingAttachmentPeer=controller.connectedPeerId;pendingAttachmentToken=controller.attachmentSessionToken();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(photo?"image/*":"*/*");
        try{startActivityForResult(intent,20);}catch(ActivityNotFoundException e){pendingAttachmentPeer=null;toast(t("attachmentFailed"));}
    }
    private void saveAttachment(String peer,AttachmentRecord record){
        try{pendingExportPeer=peer;pendingExportRecord=record.encode();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(record.info.mime);intent.putExtra(Intent.EXTRA_TITLE,AttachmentInfo.safeName(record.info.name));startActivityForResult(intent,21);}
        catch(Exception e){pendingExportPeer=null;pendingExportRecord=null;toast(t("attachmentFailed"));}
    }
    private void openAttachment(String peer,AttachmentRecord record){
        if(controller==null)return;controller.attachmentPath(peer,record.info).whenComplete((path,error)->ui.post(()->{
            if(isDestroyed())return;if(error!=null){toast(t("attachmentUnavailable"));return;}
            Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".attachments").appendPath(peer).appendPath(path.getFileName().toString()).appendQueryParameter("name",record.info.name).appendQueryParameter("mime",record.info.mime).build();
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(record.info.name,uri));
            try{startActivity(intent);}catch(ActivityNotFoundException e){toast(t("attachmentFailed"));}
        }));
    }
    private void thumbnail(String peer,AttachmentRecord record,ImageView view){
        String key=peer+":"+record.info.id;Bitmap cached=thumbnails.get(key);if(cached!=null){view.setImageBitmap(cached);return;}
        if(controller==null||failedThumbnails.contains(key)||!loadingThumbnails.add(key))return;
        try{controller.attachmentPath(peer,record.info).thenApplyAsync(path->{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(path.toString(),options);
            if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>100000000)return null;
            options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
            return BitmapFactory.decodeFile(path.toString(),options);
        },imageWorker).whenComplete((bitmap,error)->ui.post(()->{
            loadingThumbnails.remove(key);if(isDestroyed())return;if(bitmap==null){failedThumbnails.add(key);while(failedThumbnails.size()>16)failedThumbnails.remove(failedThumbnails.iterator().next());return;}thumbnails.put(key,bitmap);while(thumbnails.size()>12)thumbnails.remove(thumbnails.keySet().iterator().next());
            if(controller!=null&&Objects.equals(peer,controller.selectedId)&&thumbnailTargets.contains(record.info.id)){renderedMessages=null;renderChat();}
        }));}catch(java.util.concurrent.RejectedExecutionException e){loadingThumbnails.remove(key);}
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
        String transport = modeName(controller.sessionMode);
        String detailText = t(controller.sessionMode == Peer.LAN ? "androidRequestLanBody" : "androidRequestBody", transport);
        approvalDialog = new AlertDialog.Builder(this).setTitle(t("requestNamedTitle", safe(controller.approvalName))).setMessage(detailText)
            .setPositiveButton(t("remember"), (d, w) -> { if (controller != null) controller.approve(token, true); })
            .setNeutralButton(t("once"), (d, w) -> { if (controller != null) controller.approve(token, false); })
            .setNegativeButton(t("reject"), (d, w) -> { if (controller != null) controller.reject(token); }).create();
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
        if (controller != null && controller.connecting) { toast(t("pleaseWaitConnecting")); return; }
        if (controller != null && controller.hasSession()) {
            if (action.peerId != null && Objects.equals(action.peerId, controller.connectedPeerId)) { detail = true; render(); return; }
            new AlertDialog.Builder(this).setTitle(t("connectionSwitchTitle")).setMessage(t("connectionSwitchBody"))
                .setPositiveButton(t("disconnectAndConnect"), (d, w) -> { if (controller != null) { pendingAction = action; pendingStage = CLOSE_STAGE; controller.disconnect(); } })
                .setNegativeButton(t("cancel"), null).show();
        } else withPermissions(action);
    }
    private void manualConnect() {
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("192.168.1.20:54321"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = vertical(); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(address); CheckBox remember = new CheckBox(this); remember.setText(t("rememberConnectedDevice")); remember.setChecked(true); box.addView(remember);
        new AlertDialog.Builder(this).setTitle(t("direct")).setMessage(t("androidDirectBody")).setView(box)
            .setPositiveButton(t("reconnect"), (d, w) -> requestConnection(new UiAction(UiAction.ADDRESS, Peer.LAN, null, address.getText().toString(), null, remember.isChecked())))
            .setNegativeButton(t("cancel"), null).show();
    }
    private void startTransport(int chosen) {
        if (controller == null) return; startForegroundService(new Intent(this, ChatService.class).setAction(ChatService.START)); controller.start(chosen);
    }
    private void withPermissions(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        if (action.mode == Peer.BLUETOOTH && !controller.bluetoothAvailable()) { pendingAction = null; pendingStage = 0; toast(t("unsupportedBluetooth")); return; }
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
            new AlertDialog.Builder(this).setTitle(t("bluetoothPermissionTitle")).setMessage(t("bluetoothPermissionBody"))
                .setPositiveButton(t("appSettings"), (d, w) -> appSettings()).setNegativeButton(t("close"), null).show(); render(); return;
        }
        ensureEnabled(action);
    }
    private void ensureEnabled(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        try {
            if (action.mode == Peer.BLUETOOTH && !controller.bluetoothEnabled()) { pendingAction = action; pendingStage = ENABLE_STAGE; startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8); }
            else { pendingAction = null; pendingStage = 0; execute(action); }
        } catch (SecurityException e) { pendingAction = null; pendingStage = 0; toast(t("bluetoothPermissionRevoked")); }
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
        if(requestCode==20){String peer=pendingAttachmentPeer;long token=pendingAttachmentToken;pendingAttachmentPeer=null;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&controller!=null&&peer!=null)controller.sendAttachment(peer,token,data.getData());return;
        }
        if(requestCode==21){String peer=pendingExportPeer,encoded=pendingExportRecord;pendingExportPeer=null;pendingExportRecord=null;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&controller!=null&&peer!=null&&encoded!=null)try{AttachmentRecord record=AttachmentRecord.decode(encoded);controller.exportAttachment(peer,record.info,data.getData()).whenComplete((v,e)->{if(e!=null)ui.post(()->{if(!isDestroyed())toast(t("attachmentFailed"));});});}catch(Exception e){toast(t("attachmentFailed"));}return;
        }
        if (requestCode == 8 && pendingAction != null && pendingStage == ENABLE_STAGE) {
            UiAction action = pendingAction; if (resultCode == RESULT_OK) { pendingStage = BIND_STAGE; withPermissions(action); }
            else { pendingAction = null; pendingStage = 0; toast(t("bluetoothEnableCanceled")); }
        } else if (requestCode == 9) { discoverableUntil = resultCode > 0 ? SystemClock.elapsedRealtime() + resultCode * 1000L : 0; updateDiscoverability(); }
    }
    @SuppressLint("MissingPermission")
    private void updateDiscoverability() {
        if (discoverableButton == null) return; boolean discoverable = false;
        if (bluetoothPermissionsGranted()) try { BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter(); discoverable = adapter != null && adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE; } catch (SecurityException ignored) {}
        long remaining = Math.max(0, (discoverableUntil - SystemClock.elapsedRealtime() + 999) / 1000);
        discoverableButton.setText(discoverable && remaining > 0 ? t("discoverableRemaining", remaining) : discoverable ? t("currentlyDiscoverable") : t("allowDiscoverable"));
        if (!discoverable && remaining > 0) discoverableUntil = 0;
    }
    private void chatMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, t("deviceInfo"));
        if (controller != null && controller.hasSession()) menu.getMenu().add(0, 2, 1, t("disconnect"));
        menu.getMenu().add(0, 3, 2, t("clearConversation"));
        menu.setOnMenuItemClickListener(item -> {
            if (controller == null) return true;
            switch (item.getItemId()) {
                case 1: deviceInfo(controller.selectedId, controller.selectedName); break;
                case 2: controller.disconnect(); break;
                case 3: clearConversation(); break;
            }
            return true;
        }); menu.show();
    }

    private void clearConversation() {
        if (controller == null || controller.selectedId == null) return; String selected = controller.selectedId;
        new AlertDialog.Builder(this).setTitle(t("clearAndroidTitle", safe(controller.selectedName))).setMessage(t("clearHistoryWarning"))
            .setPositiveButton(t("clearHistoryAction"), (d, w) -> { if (controller != null && Objects.equals(selected, controller.selectedId)) { deferredScrollPeer = null; historyPositions.remove(selected); controller.clearConversation(); } })
            .setNegativeButton(t("cancel"), null).show();
    }
    private void deviceInfo(String id, String name) {
        if (controller == null || id == null) return; boolean trusted = controller.isTrusted(id);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(safe(name)).setMessage(t(trusted ? "deviceInfoTrusted" : "deviceInfoUntrusted", id)).setNegativeButton(t("close"), null);
        if (trusted) {
            builder.setPositiveButton(t("revoke"), (d, w) -> confirmRevoke(id, name, false));
            if (Objects.equals(id, controller.connectedPeerId)) builder.setNeutralButton(t("revokeAndDisconnect"), (d, w) -> confirmRevoke(id, name, true));
        }
        builder.show();
    }
    private void confirmRevoke(String id, String name, boolean disconnect) {
        new AlertDialog.Builder(this).setTitle(t("revokeAndroidTitle", safe(name))).setMessage(t("revokeAndroidBody"))
            .setPositiveButton(disconnect ? t("revokeAndDisconnect") : t("revoke"), (d, w) -> { if (controller != null) { controller.revokeTrust(id); if (disconnect && Objects.equals(id, controller.connectedPeerId)) controller.disconnect(); } })
            .setNegativeButton(t("back"), null).show();
    }
    private void chooseLanguage() {
        List<String> selections = new ArrayList<>(), labels = new ArrayList<>();
        selections.add(LanguageRegistry.SYSTEM); labels.add(t("systemLanguage"));
        for (LanguageRegistry.Language language : LanguageRegistry.languages()) { selections.add(language.tag); labels.add(language.nativeName); }
        int selected = selections.indexOf(AppLanguage.selection(this));
        new AlertDialog.Builder(this).setTitle(t("language")).setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
            String selection = selections.get(which); dialog.dismiss();
            if (selection.equals(AppLanguage.selection(this))) return;
            saveDraftAndPosition(); AppLanguage.select(this, selection);
            if (service != null) service.refreshLanguage();
            if (Build.VERSION.SDK_INT < 33) recreate();
            else renderSettings();
        }).setNegativeButton(t("cancel"), null).show();
    }
    private String peerName(Peer peer) { return peer.name == null || peer.name.trim().isEmpty() ? t("unnamedDevice") : peer.name; }
    private void editNickname() {
        if (controller == null) return; EditText name = new EditText(this); name.setText(controller.nickname); name.setSingleLine(true); name.setSelectAllOnFocus(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        LinearLayout box = vertical(); box.setPadding(dp(24), dp(8), dp(24), 0); box.addView(name);
        new AlertDialog.Builder(this).setTitle(t("yourNickname")).setView(box).setPositiveButton(t("save"), (d, w) -> { if (controller != null) controller.setNickname(name.getText().toString()); }).setNegativeButton(t("cancel"), null).show();
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle(t("help")).setMessage(t("androidHelpBody"))
            .setPositiveButton(t("gotIt"), null).show();
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
    private String modeName(int value) { return t(value == Peer.BLUETOOTH ? "bluetooth" : "lan"); }
    private String t(String key, Object... arguments) { return AndroidText.get(this, key, arguments); }
    private String t(UiText text) { return AndroidText.get(this, text); }
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
        long now = System.currentTimeMillis(); if (sameDay(time, now)) return AndroidText.date(this, time, "jm");
        Calendar yesterday = Calendar.getInstance(); yesterday.add(Calendar.DAY_OF_YEAR, -1); if (sameDay(time, yesterday.getTimeInMillis())) return t("yesterday");
        return AndroidText.date(this, time, "MMMd");
    }
}

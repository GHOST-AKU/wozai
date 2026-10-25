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
import dev.ghost.nearbyim.core.AttachmentBatchQueue;
import dev.ghost.nearbyim.core.IdentityVerification;
import dev.ghost.nearbyim.core.AttachmentRecord;
import dev.ghost.nearbyim.core.TransferProgress;
import dev.ghost.nearbyim.core.TransferPath;
import android.graphics.Bitmap;
import java.nio.file.Path;

/** Native, local-data UI. Discovery, authorization and delivery remain controller state. */
public final class MainActivity extends Activity {
    private ChatService service;
    private ChatController controller;
    private final Runnable renderObserver = this::render;
    private boolean bound, detail, outgoingRequest, rebuildingMessages, resumePosted;
    private int mode = Peer.LAN, page;
    private int background, surface, ink, muted, accent, accentInk, tonal, line;
    private int sideInsetLeft, sideInsetRight, topInset, bottomInset, messageWidth;
    private AdaptiveScaffold root;
    private FoldWindow foldWindow;
    private final Map<android.app.Dialog,Boolean> adaptiveDialogs=new WeakHashMap<>();
    private LinearLayout header, bottomNav, homePage, nearbyPage, settingsPage, chatPage;
    private LinearLayout historyList, peersList, trustedList, bubbles, reconnectRow, chatAvatarBox;
    private FrameLayout homeFrame;
    private TextView pageTitle, networkName, networkState, errorView, receiveStatus, connectionInfo, modeHint, bluetoothState, requestStatus;
    private TextView chatName, chatStatus, nickname, reconnectHint, languageValue, themeValue, receiveFolderValue;
    private AvatarView chatAvatar, ownAvatar;
    private String renderedAvatarRoots;
    private List<ChatStore.TrustedDevice> renderedHistoryTrusted;
    private Button lanTab, bluetoothTab, receiveButton, searchButton, discoverableButton, manualButton, wifiSettingsButton, sendButton, reconnectButton, newMessages;
    private Button newChatButton, cancelConnectionButton,fileButton,photoButton;
    private LinearLayout attachmentTray;
    private Button attachmentToggle,emojiButton;
    private boolean hardwareSendKey;
    private PopupMenu attachmentActions;
    private android.app.Dialog verificationDialog;
    private IdentityVerification verificationSnapshot,pendingVerification;
    private TextView verificationConnection,verificationIdentity,verificationNotice;
    private Button verificationMark,verificationScan,verificationImage,verificationExport;
    private boolean verificationBusy;
    private String pendingVerificationCode;
    private String pendingAttachmentPeer,pendingExportPeer,pendingExportRecord;
    private long pendingAttachmentToken;
    private String pendingRetryPeer,pendingRetryId,pendingRetrySession;
    private long pendingRetryToken;
    private Uri selectedRetryUri;
    private ArrayList<Uri> selectedAttachmentUris;
    private Uri selectedExportUri;
    private int selectedExportFlags;
    private GalleryPublisher gallery;
    private AttachmentLocations fileLocations;
    private final AttachmentLocations.Listener fileLocationListener=this::attachmentVisualChanged;
    private String pendingGalleryPeer,pendingGalleryRecord;
    private final GalleryPublisher.Listener galleryListener=this::attachmentVisualChanged;
    private final Set<String> dirtyAttachmentRows=new HashSet<>();
    private TimelinePosition pendingTimelinePosition,restoredTimelinePosition;
    private final Runnable restoreTimelinePosition=this::restoreTimelinePosition;
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
    private String renderedSelected;
    private String renderedConnection, renderedQuery, peerSignature, renderedPeer, deferredScrollPeer;
    private String renderedRetryState;
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
    private static final int PERMISSION_STAGE = 1, ENABLE_STAGE = 2, BIND_STAGE = 3, CLOSE_STAGE = 4, WIFI_STAGE = 5;
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
    private boolean wifiSettingsReturned;
    private final Runnable discoverabilityTick = new Runnable() {
        public void run() { updateDiscoverability(); ui.postDelayed(this, 1000); }
    };
    private final ServiceConnection binding = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ChatService.LocalBinder) binder).service(); controller = service.controller;
            if (controller.selectedId == null && restoredSelectedId != null)
                controller.selectConversation(new ChatStore.Conversation(restoredSelectedId, restoredSelectedName, 0));
            // Publication may finish while a system picker or another Activity has this page stopped.
            for(ChatStore.Message message:controller.messages)if(message.attachment!=null&&!message.outgoing&&message.attachment.state.equals("received"))dirtyAttachmentRows.add(messageKey(message));
            controller.observe(renderObserver); resumePending(); resumeAttachmentSelection();resumeVerificationResult();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; controller = null; render(); }
    };
    protected void attachBaseContext(Context base) { super.attachBaseContext(AppAppearance.wrap(AppLanguage.wrap(base))); }
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try{gallery=GalleryPublisher.get(this);gallery.listen(galleryListener);}catch(java.io.IOException unavailable){}
        try{fileLocations=AttachmentLocations.get(this);fileLocations.listen(fileLocationListener);}catch(java.io.IOException unavailable){}
        if (saved == null) { SharedPreferences preferences = getSharedPreferences("ui", MODE_PRIVATE); mode = preferences.getInt("lastMode", Peer.LAN); rememberNext = preferences.getBoolean("rememberNext", true); }
        if (saved != null) {
            pendingGalleryPeer=saved.getString("pendingGalleryPeer");pendingGalleryRecord=saved.getString("pendingGalleryRecord");
            pendingAttachmentPeer=saved.getString("pendingAttachmentPeer");pendingAttachmentToken=saved.getLong("pendingAttachmentToken");pendingExportPeer=saved.getString("pendingExportPeer");pendingExportRecord=saved.getString("pendingExportRecord");
            pendingRetryPeer=saved.getString("pendingRetryPeer");pendingRetryId=saved.getString("pendingRetryId");pendingRetryToken=saved.getLong("pendingRetryToken");pendingRetrySession=saved.getString("pendingRetrySession");String retryUri=saved.getString("selectedRetryUri");selectedRetryUri=retryUri==null?null:Uri.parse(retryUri);
            selectedAttachmentUris=saved.getParcelableArrayList("selectedAttachmentUris");String exportUri=saved.getString("selectedExportUri");
            selectedExportUri=exportUri==null?null:Uri.parse(exportUri);
            selectedExportFlags=saved.getInt("selectedExportFlags");
            if(saved.getString("timelinePeer")!=null)restoredTimelinePosition=new TimelinePosition(saved.getString("timelinePeer"),saved.getString("timelineAnchor"),dp(saved.getInt("timelineYDp")),dp(saved.getInt("timelineOffsetDp")),saved.getBoolean("timelineBottom"));
            mode = saved.getInt("mode", Peer.LAN); page = saved.getInt("page", 0); detail = saved.getBoolean("detail");
            pendingAction = (UiAction) saved.getSerializable("pendingAction"); pendingStage = saved.getInt("pendingStage"); wifiSettingsReturned = saved.getBoolean("wifiSettingsReturned");
            restoredSelectedId = saved.getString("selectedId"); restoredSelectedName = saved.getString("selectedName");
            composerPeer = saved.getString("composerPeer"); handledSavedMessage = saved.getString("handledSavedMessage");
            outgoingRequest = saved.getBoolean("outgoingRequest"); searchQuery = saved.getString("searchQuery", "");
            lanDetails = saved.getBoolean("lanDetails"); rememberNext = saved.getBoolean("rememberNext", true); discoverableUntil = saved.getLong("discoverableUntil");
            Bundle draftState = saved.getBundle("drafts"), positions = saved.getBundle("historyPositions");
            if (draftState != null) for (String key : draftState.keySet()) drafts.put(key, draftState.getString(key, ""));
            if (positions != null) for (String key : positions.keySet()) historyPositions.put(key, positions.getInt(key));
        }
        mode=normalizeChatMode(mode);
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("lastMode",mode).apply();
        if(pendingAction!=null&&pendingAction.mode==Peer.WIFI_DIRECT){pendingAction=null;pendingStage=0;wifiSettingsReturned=false;}
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
        super.onStart();if(foldWindow!=null)foldWindow.start();if(gallery!=null)gallery.listen(galleryListener);if(fileLocations!=null)fileLocations.listen(fileLocationListener); bound = bindService(new Intent(this, ChatService.class), binding, Context.BIND_AUTO_CREATE);
        ui.post(discoverabilityTick);
    }
    protected void onResume() { super.onResume(); if (root != null) { if(controller!=null)AndroidAvatars.get(this).refresh(controller.localAvatarRoot()); resumePending(); render(); } }
    protected void onStop() {
        saveDraftAndPosition();if(foldWindow!=null)foldWindow.stop(); ui.removeCallbacks(discoverabilityTick);
        if(gallery!=null)gallery.unlisten(galleryListener);
        if(fileLocations!=null)fileLocations.unlisten(fileLocationListener);
        if(attachmentActions!=null){attachmentActions.dismiss();attachmentActions=null;}
        if (controller != null) { restoredSelectedId = controller.selectedId; restoredSelectedName = controller.selectedName; controller.unobserve(renderObserver); }
        if (bound) { unbindService(binding); bound = false; } controller = null; service = null;
        if (approvalDialog != null) { approvalDialog.setOnCancelListener(null); approvalDialog.dismiss(); approvalDialog = null; shownApproval = null; }
        super.onStop();
    }
    protected void onDestroy(){if(verificationDialog!=null)verificationDialog.dismiss();if(messageScroll!=null)messageScroll.removeCallbacks(restoreTimelinePosition);pendingTimelinePosition=null;imageWorker.shutdownNow();thumbnails.clear();super.onDestroy();}
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingGalleryPeer",pendingGalleryPeer);state.putString("pendingGalleryRecord",pendingGalleryRecord);
        state.putString("pendingAttachmentPeer",pendingAttachmentPeer);state.putLong("pendingAttachmentToken",pendingAttachmentToken);state.putString("pendingExportPeer",pendingExportPeer);state.putString("pendingExportRecord",pendingExportRecord);
        state.putString("pendingRetryPeer",pendingRetryPeer);state.putString("pendingRetryId",pendingRetryId);state.putLong("pendingRetryToken",pendingRetryToken);state.putString("pendingRetrySession",pendingRetrySession);state.putString("selectedRetryUri",selectedRetryUri==null?null:selectedRetryUri.toString());
        state.putParcelableArrayList("selectedAttachmentUris",selectedAttachmentUris);state.putString("selectedExportUri",selectedExportUri==null?null:selectedExportUri.toString());
        state.putInt("selectedExportFlags",selectedExportFlags);
        saveDraftAndPosition();
        TimelinePosition timeline=restoredTimelinePosition!=null?restoredTimelinePosition:pendingTimelinePosition!=null?pendingTimelinePosition:captureTimelinePosition();
        if(timeline.peer!=null){float density=getResources().getDisplayMetrics().density;state.putString("timelinePeer",timeline.peer);state.putString("timelineAnchor",timeline.anchor instanceof String?(String)timeline.anchor:null);state.putInt("timelineYDp",Math.round(timeline.y/density));state.putInt("timelineOffsetDp",Math.round(timeline.offset/density));state.putBoolean("timelineBottom",timeline.bottom);}
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
        state.putSerializable("pendingAction", pendingAction); state.putInt("pendingStage", pendingStage); state.putBoolean("wifiSettingsReturned", wifiSettingsReturned); super.onSaveInstanceState(state);
    }
    private void saveDraftAndPosition() {
        if (composerPeer != null && composer != null) drafts.put(composerPeer, composer.getText().toString());
        if (renderedPeer != null && messageScroll != null && !Objects.equals(renderedPeer, deferredScrollPeer)) historyPositions.put(renderedPeer, messageScroll.getScrollY());
    }
    @SuppressLint("GestureBackNavigation") @SuppressWarnings("deprecation")
    public void onBackPressed() { if (Build.VERSION.SDK_INT < 33) handleBack(); else super.onBackPressed(); }
    private void handleBack() {
        if(detail&&attachmentTray!=null&&attachmentTray.getVisibility()==View.VISIBLE){attachmentTray.setVisibility(View.GONE);return;}
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
        root = new AdaptiveScaffold(this); root.setBackgroundColor(background); root.setFocusableInTouchMode(true); setContentView(root);
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
        pageTitle = label(t("app"), 32, ink); pageTitle.setTypeface(null, Typeface.BOLD); singleLine(pageTitle); header.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout indicator = horizontal(); indicator.setGravity(Gravity.CENTER_VERTICAL); networkIcon = icon(R.drawable.outline_wifi_24, accent, "");
        networkIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); indicator.addView(networkIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout networkText = vertical(); networkText.setPaddingRelative(dp(6), 0, 0, 0); networkName = label("", 13, ink); networkName.setTypeface(null, Typeface.BOLD);
        networkState = label(t("idle"), 12, muted); singleLine(networkName);singleLine(networkState);networkName.setMaxWidth(dp(112));networkState.setMaxWidth(dp(112)); networkText.addView(networkName); networkText.addView(networkState); indicator.addView(networkText);
        indicator.setPaddingRelative(dp(8), dp(4), 0, dp(4)); header.addView(indicator); root.addView(header);
        errorView = label("", 13, ink); errorView.setPadding(dp(16), dp(10), dp(16), dp(10)); errorView.setBackgroundColor(tonal);
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(errorView);
        homePage = vertical(); nearbyPage = vertical(); settingsPage = vertical(); chatPage = vertical();
        root.addView(homePage); root.addView(nearbyPage);
        root.addView(settingsPage); root.addView(chatPage);
        buildHome(); buildNearby(); buildSettings(); buildChat(); buildNavigation();
        LinearLayout placeholder=empty(t("chats"),t("noMessageTitle"));root.addView(placeholder);
        root.bind(header,errorView,homePage,nearbyPage,settingsPage,chatPage,placeholder,bottomNav,line);
        root.navigationChanged=rail->{
            bottomNav.setBackground(shape(rail?surface:background,0));
            pageTitle.setTextSize(rail?24:32);header.setPadding(dp(16),dp(rail?8:14),dp(16),dp(rail?8:10));
            updateHomeActionPlacement(rail);
        };
        root.geometryChanging=()->{if(!rebuildingMessages&&chatPage.getVisibility()==View.VISIBLE&&renderedPeer!=null&&messageScroll.getWidth()>0)pendingTimelinePosition=captureTimelinePosition();};
        root.geometryChanged=()->{fitOpenDialogs();if(pendingTimelinePosition!=null){messageScroll.removeCallbacks(restoreTimelinePosition);messageScroll.post(restoreTimelinePosition);}};foldWindow=new FoldWindow(this,root::fold);root.requestApplyInsets();
    }
    private void applyRootPadding() {
        root.setPadding(sideInsetLeft, topInset, sideInsetRight, bottomInset);
    }
    private void buildNavigation() {
        bottomNav = horizontal(); bottomNav.setGravity(Gravity.CENTER); bottomNav.setPadding(dp(12), dp(9), dp(12), dp(7));
        GradientDrawable navSurface = shape(background, 0); bottomNav.setBackground(navSurface);
        LinearLayout compose = vertical();compose.setTag("railCompose");compose.setGravity(Gravity.CENTER);
        Button composeAction=iconButton(R.drawable.outline_add_24,t("newChat"),accentInk);composeAction.setBackground(ripple(accent,18));
        composeAction.setOnClickListener(v->navigate(1));compose.addView(composeAction,new LinearLayout.LayoutParams(dp(56),dp(56)));
        TextView composeLabel=label(t("newChat"),13,muted);composeLabel.setGravity(Gravity.CENTER);composeLabel.setMaxLines(2);composeLabel.setEllipsize(TextUtils.TruncateAt.END);compose.addView(composeLabel);
        bottomNav.addView(compose);navigationGap(1);
        String[] labels = {t("chats"), t("nearby"), t("settings")}; int[] icons = {R.drawable.outline_chat_bubble_24, R.drawable.outline_wifi_tethering_24, R.drawable.outline_settings_24};
        for (int i = 0; i < 3; i++) {
            final int selected = i; LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(64)); item.setContentDescription(labels[i]);
            item.setFocusable(true); item.setClickable(true); item.setOnClickListener(v -> navigate(selected));
            FrameLayout pill = new FrameLayout(this); pill.setTag("pill"); ImageView icon = icon(icons[i], muted, ""); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pill.setDuplicateParentStateEnabled(true);pill.setForeground(ripple(Color.TRANSPARENT,20));
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER); pill.addView(icon, imageParams);
            item.addView(pill, new LinearLayout.LayoutParams(dp(72), dp(32))); TextView text = label(labels[i], 13, muted); text.setGravity(Gravity.CENTER);
            text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(text); navItems[i] = item; navIcons[i] = icon; navLabels[i] = text; bottomNav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            if(i<2)navigationGap(i==0?1:3);
        }
        root.addView(bottomNav);
    }
    private void navigationGap(float weight){View gap=new View(this);gap.setTag(Float.valueOf(weight));gap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);bottomNav.addView(gap);}
    private void updateHomeActionPlacement(boolean rail){
        boolean show=!rail&&controller!=null&&!controller.conversations.isEmpty();
        newChatButton.setVisibility(show?View.VISIBLE:View.GONE);
        historyScroll.setPadding(dp(16),0,dp(16),dp(show?88:16));
    }
    static int normalizeChatMode(int mode){return mode==Peer.BLUETOOTH?Peer.BLUETOOTH:Peer.LAN;}
    private void chooseMode(int chosen) {
        chosen=normalizeChatMode(chosen);mode = chosen; getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("lastMode", chosen).apply(); peerSignature = null; render();
    }
    private void navigate(int selected) {
        saveDraftAndPosition(); detail = false; page = selected; hideKeyboard(); render();
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
        lanTab = button(t("lan"), true); bluetoothTab = button(t("bluetooth"), false);
        lanTab.setOnClickListener(v -> chooseMode(Peer.LAN)); bluetoothTab.setOnClickListener(v -> chooseMode(Peer.BLUETOOTH));
        modes.addView(lanTab, new LinearLayout.LayoutParams(0, -2, 1)); modes.addView(bluetoothTab, new LinearLayout.LayoutParams(0, -2, 1)); nearbyContent.addView(modes);
        modeHint = label("", 14, muted); modeHint.setPadding(0, dp(16), 0, dp(8)); nearbyContent.addView(modeHint);
        rememberDevice = new CheckBox(this); rememberDevice.setText(t("rememberFirstConnection")); rememberDevice.setTextSize(14); rememberDevice.setTextColor(muted); rememberDevice.setChecked(rememberNext);
        rememberDevice.setMinHeight(dp(48)); rememberDevice.setOnCheckedChangeListener((button, checked) -> { rememberNext = checked; getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("rememberNext", checked).apply(); }); nearbyContent.addView(rememberDevice);
        bluetoothState = label("", 13, muted); nearbyContent.addView(bluetoothState);
        LinearLayout receiveRow = horizontal(); receiveRow.setGravity(Gravity.CENTER_VERTICAL); receiveStatus = label(t("receptionOff"), 16, ink);
        receiveRow.addView(receiveStatus, new LinearLayout.LayoutParams(0, -2, 1)); receiveButton = button(t("startReception"), false);
        receiveButton.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.running(mode)) {
                if (controller.hasSession() && controller.sessionMode == mode)
                    new AdaptiveDialogBuilder().setTitle(t("stopReceptionTitle")).setMessage(t("historyTrustRetained"))
                        .setPositiveButton(t("stopReception"), (d, w) -> { if (controller != null) controller.stop(mode); }).setNegativeButton(t("cancel"), null).show();
                else controller.stop(mode);
            } else withPermissions(new UiAction(UiAction.START, mode));
        }); receiveRow.addView(receiveButton); nearbyContent.addView(receiveRow);
        Button details = button(t("viewCopyAddress"), false); details.setTag("lanDetails"); details.setOnClickListener(v -> { lanDetails = !lanDetails; render(); }); nearbyContent.addView(details, topSpace());
        connectionInfo = label("", 13, muted); connectionInfo.setTextIsSelectable(true); connectionInfo.setPadding(0, dp(8), 0, dp(8));
        connectionInfo.setOnLongClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(t("localAddress"), connectionInfo.getText())); toast(t("addressCopied")); return true; }); nearbyContent.addView(connectionInfo);
        searchButton = button(t("searchDevices"), true); searchButton.setOnClickListener(v -> {
            if (controller != null && controller.searching(mode)) controller.stopSearch(mode);
            else withPermissions(new UiAction(UiAction.SEARCH, mode));
        }); nearbyContent.addView(searchButton, topSpace());
        discoverableButton = button(t("allowDiscoverable"), false); discoverableButton.setOnClickListener(v -> withPermissions(new UiAction(UiAction.DISCOVERABLE, Peer.BLUETOOTH))); nearbyContent.addView(discoverableButton, topSpace());
        manualButton = button(t("direct"), false); manualButton.setOnClickListener(v -> manualConnect()); nearbyContent.addView(manualButton, topSpace());
        wifiSettingsButton = button(t("directWifiSettings"), false); wifiSettingsButton.setOnClickListener(v -> {
            if (directEnabled()) openLocationSettings(); else openWifiSettings();
        }); nearbyContent.addView(wifiSettingsButton, topSpace());
        requestStatus = label("", 14, muted); requestStatus.setPadding(0, dp(12), 0, dp(4)); requestStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); nearbyContent.addView(requestStatus);
        cancelConnectionButton = button(t("cancelConnection"), false); cancelConnectionButton.setOnClickListener(v -> { outgoingRequest = false; if (controller != null) controller.disconnect(); }); nearbyContent.addView(cancelConnectionButton, topSpace());
        LinearLayout devicesHeader = horizontal(); devicesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView devicesHeading = sectionHeading(t("nearbyDevices")); devicesHeader.addView(devicesHeading, new LinearLayout.LayoutParams(0, -2, 1));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall); scanProgress.setIndeterminateTintList(ColorStateList.valueOf(accent)); scanProgress.setContentDescription(t("searchingDevices"));
        devicesHeader.addView(scanProgress, new LinearLayout.LayoutParams(dp(24), dp(24))); nearbyContent.addView(devicesHeader); peersList = vertical(); nearbyContent.addView(peersList);
    }
    private void buildSettings() {
        ScrollView scroll = settingsScroll = new ScrollView(this); LinearLayout settingsContent = vertical(); settingsContent.setPadding(dp(16), dp(12), dp(16), dp(24)); scroll.addView(settingsContent); settingsPage.addView(scroll);
        settingsContent.addView(sectionHeading(t("deviceSection")));
        ownAvatar=new AvatarView(this);ownAvatar.setTag("avatarSettings");
        LinearLayout avatarSetting=horizontal();avatarSetting.setGravity(Gravity.CENTER_VERTICAL);avatarSetting.setPadding(dp(2),dp(12),dp(2),dp(12));avatarSetting.addView(ownAvatar,new LinearLayout.LayoutParams(dp(64),dp(64)));
        LinearLayout avatarText=vertical();avatarText.setPadding(dp(14),0,0,0);avatarText.addView(label(t("avatarTitle"),16,ink));avatarText.addView(label(t("avatarSavedOnDevice"),13,muted));avatarSetting.addView(avatarText,new LinearLayout.LayoutParams(0,-2,1));avatarSetting.setFocusable(true);avatarSetting.setClickable(true);avatarSetting.setBackground(ripple(background,12));avatarSetting.setOnClickListener(v->startActivity(new Intent(this,AvatarActivity.class)));settingsContent.addView(avatarSetting);
        nickname = label("", 14, muted); settingsContent.addView(settingsRow(t("nickname"), nickname, this::editNickname));
        settingsContent.addView(sectionHeading(t("appearanceSection")));
        languageValue = label("", 14, muted); settingsContent.addView(settingsRow(t("language"), languageValue, this::chooseLanguage));
        themeValue = label("", 14, muted); settingsContent.addView(settingsRow(t("theme"), themeValue, this::chooseTheme));
        settingsContent.addView(sectionHeading(t("trustedDevices"))); trustedList = vertical(); settingsContent.addView(trustedList);
        Switch automaticPhotos=new Switch(this);automaticPhotos.setText(t("galleryAutoSave"));automaticPhotos.setChecked(gallery==null||gallery.automatic());automaticPhotos.setPadding(dp(4),dp(12),dp(4),dp(12));automaticPhotos.setOnCheckedChangeListener((view,enabled)->{if(gallery!=null)gallery.automatic(enabled);});settingsContent.addView(automaticPhotos);
        receiveFolderValue=label("",14,muted);settingsContent.addView(settingsRow(t("receiveFolder"),receiveFolderValue,this::chooseReceiveDirectory));
        settingsContent.addView(sectionHeading(t("appSection"))); settingsContent.addView(settingsRow(t("appPermissions"), label(t("permissionsSummary"), 13, muted), this::appSettings));
        settingsContent.addView(settingsRow(t("attachmentQuota"),null,()->{if(controller==null)return;long[] choices={16,32,64,128};String[] labels=new String[choices.length];int selected=1;for(int i=0;i<choices.length;i++){labels[i]=t("attachmentQuotaGiB",choices[i]);if(controller.attachmentQuota()==choices[i]*1024*1024*1024)selected=i;}new AdaptiveDialogBuilder().setTitle(t("attachmentQuota")).setSingleChoiceItems(labels,selected,(dialog,index)->{controller.attachmentQuota(choices[index]*1024*1024*1024).whenComplete((value,error)->ui.post(()->{if(!isDestroyed()&&error!=null)toast(t("attachmentQuotaInUse"));}));dialog.dismiss();}).setNegativeButton(t("cancel"),null).show();}));
        settingsContent.addView(settingsRow(t("stopAll"), label(t("stopConnectionsSummary"), 13, muted), () -> {
            if (controller != null) new AdaptiveDialogBuilder().setTitle(t("stopConnectionsTitle")).setMessage(t("stopConnectionsBody"))
                .setPositiveButton(t("stop"), (d, w) -> { if (controller != null) controller.stopAll(); }).setNegativeButton(t("cancel"), null).show();
        }));
        settingsContent.addView(settingsRow(t("help"), null, this::showHelp));
        settingsContent.addView(settingsRow(t("about"), label(t("androidAboutSummary", LanguageRegistry.VERSION), 13, muted), () -> new AdaptiveDialogBuilder().setTitle(t("app"))
            .setMessage(t("androidAboutBody", LanguageRegistry.VERSION))
            .setPositiveButton(t("gotIt"), null).show()));
    }
    private void buildChat() {
        LinearLayout chatHeader = horizontal(); chatHeader.setGravity(Gravity.CENTER_VERTICAL); chatHeader.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = iconButton(R.drawable.outline_arrow_back_24, t("backToChats"), ink); back.setOnClickListener(v -> handleBack()); chatHeader.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chatAvatarBox = vertical(); chatHeader.addView(chatAvatarBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = vertical(); names.setPaddingRelative(dp(12), 0, dp(4), 0); chatName = label("", 18, ink); chatName.setTypeface(null, Typeface.BOLD); singleLine(chatName);
        chatStatus = label(t("idle"), 12, muted); names.addView(chatName); names.addView(chatStatus); chatHeader.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = iconButton(R.drawable.outline_more_vert_24, t("more"), ink); more.setOnClickListener(this::chatMenu); chatHeader.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48))); chatPage.addView(chatHeader);
        reconnectRow = vertical(); reconnectRow.setPadding(dp(16), dp(4), dp(16), dp(10)); reconnectHint = label("", 13, muted);
        AdaptiveReconnectActions reconnectActions = new AdaptiveReconnectActions(this);reconnectActions.addView(reconnectHint); reconnectButton = button(t("reconnect"), true); reconnectButton.setOnClickListener(v -> { if (controller != null && outgoingRequest && (controller.connecting || controller.hasSession() && !controller.connected)) { outgoingRequest = false; controller.disconnect(); } else reconnectSelected(); });
        reconnectActions.addView(reconnectButton); Button find = button(t("findNearby"), false); find.setOnClickListener(v -> navigate(1));
        reconnectActions.addView(find); reconnectRow.addView(reconnectActions); chatPage.addView(reconnectRow);
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
        attachmentTray=vertical();attachmentTray.setPadding(dp(8),dp(8),dp(8),dp(8));attachmentTray.setBackground(shape(surface,20));attachmentTray.setVisibility(View.GONE);
        fileButton=attachmentChoice(R.drawable.outline_description_24,t("sendFile"),()->chooseAttachment(false));photoButton=attachmentChoice(R.drawable.outline_photo_24,t("sendPhoto"),()->chooseAttachment(true));
        LinearLayout choices=horizontal();choices.addView(photoButton,new LinearLayout.LayoutParams(dp(96),dp(80)));choices.addView(fileButton,new LinearLayout.LayoutParams(dp(96),dp(80)));attachmentTray.addView(choices);
        TextView transferInfo=label(t(controller!=null&&controller.isPreparingAttachment()?"attachmentStatePreparing":"attachmentHintV2"),11,muted);transferInfo.setPadding(dp(8),dp(4),dp(8),dp(4));attachmentTray.addView(transferInfo);
        LinearLayout.LayoutParams trayParams=new LinearLayout.LayoutParams(-1,-2);trayParams.setMargins(dp(12),dp(4),dp(12),0);chatPage.addView(attachmentTray,trayParams);
        LinearLayout inputRow = horizontal(); inputRow.setGravity(Gravity.BOTTOM); inputRow.setPadding(dp(8), dp(6), dp(8), dp(8));
        LinearLayout inputBox=horizontal();inputBox.setGravity(Gravity.BOTTOM);inputBox.setBackground(shape(isDark()?surface:Color.WHITE,26));inputBox.setElevation(dp(1));
        emojiButton=iconButton(R.drawable.outline_emoji_emotions_24,t("emoji"),muted);emojiButton.setOnClickListener(v->showEmoji());inputBox.addView(emojiButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        composer = new EditText(this); composer.setTextColor(ink); composer.setHintTextColor(muted); composer.setTextSize(16); composer.setHint(t("androidComposer")); composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setPadding(0, dp(12), dp(4), dp(12)); composer.setMinHeight(dp(48)); composer.setMaxLines(4); composer.setVerticalScrollBarEnabled(true);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        inputBox.addView(composer,new LinearLayout.LayoutParams(0,-2,1));attachmentToggle=iconButton(R.drawable.outline_attach_file_24,t("attachments"),muted);
        attachmentToggle.setOnClickListener(v->{attachmentTray.setVisibility(attachmentTray.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});inputBox.addView(attachmentToggle,new LinearLayout.LayoutParams(dp(48),dp(48)));
        inputRow.addView(inputBox, new LinearLayout.LayoutParams(0, -2, 1)); sendButton = iconButton(R.drawable.outline_send_24,t("send"),accentInk);sendButton.setPadding(dp(12),dp(12),dp(12),dp(12)); sendButton.setOnClickListener(v -> { if (controller != null) controller.send(composer.getText().toString()); });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48)); sendParams.setMarginStart(dp(8)); inputRow.addView(sendButton, sendParams); chatPage.addView(inputRow);
        composer.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP){if(attachmentTray.getVisibility()==View.VISIBLE)attachmentTray.setVisibility(View.GONE);else hideKeyboard();return true;}
            if(key!=KeyEvent.KEYCODE_ENTER)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN){hardwareSendKey=!event.isShiftPressed()&&!event.isAltPressed()&&android.view.inputmethod.BaseInputConnection.getComposingSpanStart(composer.getText())<0;return hardwareSendKey;}
            if(event.getAction()==KeyEvent.ACTION_UP){boolean send=hardwareSendKey;hardwareSendKey=false;if(send&&sendButton.isEnabled())sendButton.performClick();return send;}return false;
        });
        composer.addTextChangedListener(watcher(this::updateSend));
    }
    private void render() {
        if (root == null) return;
        updateVerificationDialog();
        root.show(page,detail);
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
        String avatarRoots=controller==null?null:controller.avatarRoot(connection);
        List<ChatStore.TrustedDevice> trustedRoots=controller==null?Collections.emptyList():controller.trustedDevices;
        String selected=controller==null||!detail?null:controller.selectedId;
        if (Objects.equals(renderedSelected,selected)&&renderedConversations == conversations && Objects.equals(renderedConnection, connection) && Objects.equals(renderedQuery, searchQuery) && Objects.equals(renderedAvatarRoots,avatarRoots) && renderedHistoryTrusted==trustedRoots) return;
        renderedAvatarRoots=avatarRoots;renderedHistoryTrusted=trustedRoots;
        renderedSelected=selected;renderedConversations = conversations; renderedConnection = connection; renderedQuery = searchQuery; int oldY = controller != null && restoredHistoryY >= 0 ? restoredHistoryY : historyScroll.getScrollY();
        if (controller != null) restoredHistoryY = -1; historyList.removeAllViews();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT); int count = 0; String previousGroup = null;
        for (ChatStore.Conversation conversation : conversations) {
            if (!safe(conversation.name).toLowerCase(Locale.ROOT).contains(query)) continue;
            String group = sameDay(conversation.time, System.currentTimeMillis()) ? t("today") : t("earlier");
            if (query.isEmpty() && !group.equals(previousGroup)) { historyList.addView(sectionHeading(group)); previousGroup = group; }
            count++; boolean active = controller != null && controller.connected && Objects.equals(connection, conversation.id);
            LinearLayout row = horizontal(); row.setGravity(Gravity.TOP); row.setPadding(0, dp(14), 0, dp(14)); row.setMinimumHeight(dp(active ? 98 : 82)); row.setBackground(ripple(Objects.equals(selected,conversation.id)?tonal:background, 14));row.setSelected(Objects.equals(selected,conversation.id));
            AvatarView avatar = avatar(conversation.id, conversation.name, 48); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
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
        boolean emptyHistory = conversations.isEmpty(); updateHomeActionPlacement(bottomNav.getOrientation()==LinearLayout.VERTICAL);
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
        modeHint.setText(t(mode == Peer.LAN ? "androidLanHint" : "androidBluetoothHint"));
        boolean running = attached && controller.running(mode), searching = attached && controller.searching(mode);
        String info = !attached ? "" : t(controller.info(mode));
        boolean readyToReceive = running && controller.listening(mode), available = attached;
        String reception = !running ? t("receptionOff") : readyToReceive ? t("receiving") : t("startingReception");
        receiveStatus.setText(reception); receiveButton.setText(running ? t("stopReception") : t("startReception")); receiveButton.setEnabled(available);
        scanProgress.setVisibility(searching ? View.VISIBLE : View.GONE); searchButton.setText(searching ? t("stopSearch") : t("searchDevices")); searchButton.setEnabled(available && !controller.connecting);
        manualButton.setVisibility(mode == Peer.LAN ? View.VISIBLE : View.GONE); manualButton.setEnabled(attached && !controller.connecting);
        discoverableButton.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE); discoverableButton.setEnabled(attached && controller.bluetoothAvailable());
        boolean waiting = attached && (controller.connecting || controller.hasSession() && !controller.connected);
        requestStatus.setVisibility(waiting ? View.VISIBLE : View.GONE); requestStatus.setText(waiting ? withRouteNotice(t(controller.status)) : "");
        cancelConnectionButton.setVisibility(waiting ? View.VISIBLE : View.GONE);
        View details = nearbyPage.findViewWithTag("lanDetails"); details.setVisibility(mode == Peer.LAN && running ? View.VISIBLE : View.GONE);
        connectionInfo.setVisibility(running && mode == Peer.LAN && lanDetails ? View.VISIBLE : View.GONE);
        connectionInfo.setText(info);
        bluetoothState.setVisibility(mode == Peer.BLUETOOTH ? View.VISIBLE : View.GONE);
        wifiSettingsButton.setVisibility(View.GONE);
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
            row.addView(new AvatarView(this), new LinearLayout.LayoutParams(dp(48), dp(48))); LinearLayout text = vertical(); text.setPadding(dp(14), 0, 0, 0);
            TextView name = label(peerName(peer), 17, ink); name.setTypeface(null, Typeface.BOLD); singleLine(name); text.addView(name);
            text.addView(label(t("peerStatusTransport", controller.isTrustedPeer(peer) ? t("trustedConnect") : t("discovered"), modeName(peer.mode)), 13, muted));
            TextView address = label(peer.mode == Peer.WIFI_DIRECT ? t("directPeerHint") : peer.mode == Peer.BLUETOOTH ? t(peer.paired ? "pairedAddress" : "unpairedAddress", peer.detail) : peer.detail, 12, muted); singleLine(address); text.addView(address); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> initiatePeer(peer)); peersList.addView(row);
        }
        if (attached && restoredNearbyY >= 0) { final int y = restoredNearbyY; restoredNearbyY = -1; nearbyScroll.post(() -> nearbyScroll.scrollTo(0, y)); }
        if (count == 0) peersList.addView(empty(searching ? t("searchingNearby") : t("noDevicesTitle"), t(mode == Peer.LAN ? "lanNoDevicesBody" : "bluetoothNoDevicesBody")));
    }
    private void chooseReceiveDirectory(){
        if(fileLocations==null){toast(t("receiveFolderUnavailable"));return;}
        AttachmentLocations.Directory selected=fileLocations.directory();
        AlertDialog.Builder builder=new AdaptiveDialogBuilder().setTitle(t("receiveFolder")).setMessage(t("receiveFolderSummary"))
                .setPositiveButton(t("receiveFolderChoose"),(dialog,which)->{try{startActivityForResult(AttachmentLocations.directoryPicker(selected),26);}catch(ActivityNotFoundException|SecurityException unavailable){toast(t("receiveFolderUnavailable"));}})
                .setNegativeButton(t("cancel"),null);
        if(selected!=null)builder.setNeutralButton(t("receiveFolderStop"),(dialog,which)->{fileLocations.stopDirectory();receiveDirectoryChanged();});builder.show();
    }
    private void receiveDirectoryChanged(){renderSettings();if(controller!=null){for(ChatStore.Message message:controller.messages)if(message.attachment!=null&&!message.outgoing&&message.attachment.state.equals("received"))dirtyAttachmentRows.add(messageKey(message));renderChat();}}
    private void saveToReceiveDirectory(String peer,AttachmentRecord record){
        if(fileLocations==null)return;if(fileLocations.directory()==null){chooseReceiveDirectory();return;}
        fileLocations.saveReceived(peer,record,true).whenComplete((value,error)->ui.post(()->{if(!isDestroyed()&&error!=null)toast(t("receiveFolderUnavailable"));}));
    }
    private void renderSettings() {
        if(ownAvatar!=null)AndroidAvatars.get(this).bind(ownAvatar,controller==null?null:controller.localAvatarRoot());
        nickname.setText(controller == null ? t("loading") : controller.nickname);
        String selection = AppLanguage.selection(this);
        languageValue.setText(LanguageRegistry.SYSTEM.equals(selection) ? t("systemLanguage") : LanguageRegistry.resolve(selection, AppLanguage.systemLocale(this)).nativeName);
        String theme = AppAppearance.selection(this);
        themeValue.setText(t(AppAppearance.DARK.equals(theme) ? "darkTheme" : AppAppearance.LIGHT.equals(theme) ? "lightTheme" : "systemTheme"));
        AttachmentLocations.Directory directory=fileLocations==null?null:fileLocations.directory();receiveFolderValue.setText(directory==null?t("receiveFolderPrivate"):directory.name());
        List<ChatStore.TrustedDevice> trusted = controller == null ? Collections.emptyList() : controller.trustedDevices;
        if (renderedTrusted == trusted) return; renderedTrusted = trusted; trustedList.removeAllViews();
        for (ChatStore.TrustedDevice device : trusted) {
            TextView info = label(t("trustedLastConnection", modeName(device.mode), AndroidText.date(this, device.time, "yMMMdjm")), 13, muted);
            LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(avatar(device.id,device.name,48),new LinearLayout.LayoutParams(dp(48),dp(48)));
            LinearLayout text=vertical();text.setPadding(dp(14),dp(12),0,dp(12));text.addView(label(device.name,16,ink));text.addView(info);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));row.setMinimumHeight(dp(72));row.setFocusable(true);row.setClickable(true);row.setBackground(ripple(background,12));row.setOnClickListener(v->deviceInfo(device.id,device.name));trustedList.addView(row);
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
        if(chatAvatar!=null)AndroidAvatars.get(this).bind(chatAvatar,controller==null?null:controller.avatarRoot(peerId));
        boolean ready = controller != null && controller.connected && Objects.equals(peerId, controller.connectedPeerId);
        boolean connecting = controller != null && outgoingRequest && (controller.connecting || controller.hasSession() && !controller.connected);
        String connectionLabel=ready?t(controller.endToEndEncrypted(peerId)?"encryptedVia":"connectedVia",controller.sessionMode==Peer.LAN?t("lanPathUnconfirmed"):modeName(controller.sessionMode)):connecting?t("connecting"):t("localHistoryDisconnected");
        if(ready&&controller.endToEndEncrypted(peerId))connectionLabel+=" · "+t(controller.identityVerified(peerId)?"identityVerified":"identityUnverified");
        chatStatus.setText(ready&&controller.isPreparingAttachment()?connectionLabel+" · "+t("attachmentStatePreparing"):connectionLabel);
        chatStatus.setOnClickListener(v->showTransferPath(peerId));chatStatus.setFocusable(true);
        reconnectRow.setVisibility(!ready && peerId != null ? View.VISIBLE : View.GONE);
        boolean trusted = controller != null && controller.isTrusted(peerId);
        reconnectButton.setVisibility(trusted ? View.VISIBLE : View.GONE); reconnectButton.setEnabled(controller != null);
        reconnectButton.setText(connecting ? t("cancelConnection") : controller != null && !UiText.EMPTY.equals(controller.error) ? t("retryConnection") : t("reconnect"));
        reconnectHint.setText(connecting ? withRouteNotice(t(controller.status)) : trusted ? controller != null && !UiText.EMPTY.equals(controller.error) ? t("reconnectUnavailable") : t("reconnectRemembered") : t("draftRetainedHint"));
        if(ready)requestFileRoutePermissions();
        composer.setEnabled(peerId != null);emojiButton.setEnabled(peerId!=null);attachmentToggle.setEnabled(controller!=null&&controller.canSendAttachment());fileButton.setEnabled(controller!=null&&controller.canSendAttachment());photoButton.setEnabled(controller!=null&&controller.canSendAttachment());if(switched)attachmentTray.setVisibility(View.GONE);updateSend();
        updateAttachmentPaths(peerId);
        String retryState=controller==null?null:controller.attachmentRetryRevision()+":"+controller.canSendAttachment()+":"+peerId;if(!Objects.equals(renderedRetryState,retryState)){renderedMessages=null;renderedRetryState=retryState;}
        if (controller == null || dirtyAttachmentRows.isEmpty()&&renderedMessages == controller.messages && Objects.equals(renderedPeer, peerId)) return;
        boolean newPeer = !Objects.equals(renderedPeer, peerId), wasAtBottom = atBottom(); int previousScroll = messageScroll.getScrollY();
        String oldLast = renderedMessages == null || renderedMessages.isEmpty() ? null : renderedMessages.get(renderedMessages.size() - 1).id;
        String newLast = controller.messages.isEmpty() ? null : controller.messages.get(controller.messages.size() - 1).id;
        boolean appended = !newPeer && oldLast != null && newLast != null && !Objects.equals(oldLast, newLast);
        updateThumbnailTargets(peerId,controller.messages);
        if(!newPeer&&updateLiveAttachmentViews(controller.messages)){
            renderedMessages=controller.messages;if(appended&&!wasAtBottom&&detail)newMessages.setVisibility(View.VISIBLE);return;
        }
        if (renderedPeer != null && newPeer && !Objects.equals(renderedPeer, deferredScrollPeer)) historyPositions.put(renderedPeer, previousScroll);
        if (newPeer) { deferredScrollPeer = peerId != null && historyPositions.containsKey(peerId) ? peerId : null; deferredScrollY = deferredScrollPeer == null ? 0 : historyPositions.get(peerId); }
        messageScroll.removeCallbacks(restoreTimelinePosition);pendingTimelinePosition=null;dirtyAttachmentRows.clear();
        renderedPeer = peerId; renderedMessages = controller.messages; final int generation = ++messageGeneration; rebuildingMessages = true; bubbles.removeAllViews();
        if (controller.messages.isEmpty()) bubbles.addView(empty(t("noMessageTitle"), t("firstMessageHint")));
        else { long previousDay = Long.MIN_VALUE; for (ChatStore.Message message : controller.messages) {
            long day = dayKey(message.time); if (day != previousDay) { bubbles.addView(messageDate(message,day)); previousDay = day; }
            addBubble(message);
        }}
        updateMessageWidths();
        if (appended && !wasAtBottom && detail) newMessages.setVisibility(View.VISIBLE); if (newPeer) newMessages.setVisibility(View.GONE);
        if (restoreUnread && !controller.messages.isEmpty()) { newMessages.setVisibility(View.VISIBLE); restoreUnread = false; }
        messageScroll.post(() -> {
            if (generation != messageGeneration || !Objects.equals(renderedPeer, peerId)) return;
            if(restoredTimelinePosition!=null&&Objects.equals(restoredTimelinePosition.peer,peerId)&&!renderedMessages.isEmpty()){
                pendingTimelinePosition=restoredTimelinePosition;restoredTimelinePosition=null;deferredScrollPeer=null;restoreTimelinePosition();return;
            }
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
    private void updateAttachmentPaths(String peer) {
        if(controller==null||!Objects.equals(renderedPeer,peer))return;
        for(ChatStore.Message message:controller.messages){
            if(message.attachment==null)continue;View row=bubbles.findViewWithTag(messageKey(message));if(row==null)continue;
            TextView label=row.findViewWithTag("attachmentPath");if(label==null)continue;
            TransferPath route=controller.attachmentRoute(peer,message.attachment);String value=route==TransferPath.NONE?"":t(route.key());
            if(!label.getText().toString().equals(value))label.setText(value);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
            label.setContentDescription(value);
        }
    }
    private AlertDialog showAttachmentTransferPath(String peer,AttachmentRecord record) {
        TransferPath route=controller==null?TransferPath.forAttachment("",false,record):controller.attachmentRoute(peer,record);
        String key=route==TransferPath.WIFI_DIRECT?"pathDetailsWifiDirect":route==TransferPath.LAN_UNCONFIRMED?"pathDetailsLan":route==TransferPath.BLUETOOTH?"pathDetailsBluetooth":(route==TransferPath.SELECTING||route==TransferPath.PENDING_WIFI_DIRECT||route==TransferPath.PENDING_LAN||route==TransferPath.PENDING_BLUETOOTH)?"pathDetailsSelecting":route.key();
        return new AdaptiveDialogBuilder().setTitle(t("pathDetailsTitle")).setMessage(t(key)).setPositiveButton(t("close"),null).show();
    }
    private AlertDialog showTransferPath(String peer) {
        boolean ready=controller!=null&&controller.connected&&Objects.equals(peer,controller.connectedPeerId);
        String description=!ready?t("pathDetailsDisconnected"):t(controller.endToEndEncrypted(peer)?"encryptedVia":"connectedVia",controller.sessionMode==Peer.LAN?t("lanPathUnconfirmed"):modeName(controller.sessionMode));
        return new AdaptiveDialogBuilder().setTitle(t("pathDetailsTitle")).setMessage(withRouteNotice(description)).setPositiveButton(t("close"),null).show();
    }
    private String withRouteNotice(String value) {
        if (controller == null || UiText.EMPTY.equals(controller.routeNotice)) return value;
        String notice = t(controller.routeNotice); return value.contains(notice) ? value : value + "\n\n" + notice;
    }
    private void updateThumbnailTargets(String peer,List<ChatStore.Message> messages){
        Set<String> records=new HashSet<>();for(ChatStore.Message message:messages)if(message.attachment!=null)records.add(thumbnailKey(peer,message.attachment));
        String prefix=peer+":";thumbnails.keySet().removeIf(key->key.startsWith(prefix)&&!records.contains(key));failedThumbnails.removeIf(key->key.startsWith(prefix)&&!records.contains(key));
        thumbnailTargets.clear();for(int i=messages.size()-1;i>=0&&thumbnailTargets.size()<12;i--){AttachmentRecord record=messages.get(i).attachment;if(record!=null&&record.info.mime.startsWith("image/")&&photoAvailable(record))thumbnailTargets.add(thumbnailKey(peer,record));}
    }
    private static String messageKey(ChatStore.Message message){return "message:"+message.id+":"+message.outgoing;}
    private static boolean sameMessage(ChatStore.Message a,ChatStore.Message b){return a==b||a.outgoing==b.outgoing&&a.time==b.time&&Objects.equals(a.id,b.id)&&Objects.equals(a.text,b.text)&&Objects.equals(a.state,b.state)&&Objects.equals(a.attachment,b.attachment);}
    private static boolean sameAttachmentInfo(AttachmentInfo a,AttachmentInfo b){return a==b||a.version==b.version&&a.size==b.size&&a.time==b.time&&a.id.equals(b.id)&&a.name.equals(b.name)&&a.mime.equals(b.mime)&&Objects.equals(a.hash,b.hash);}
    private static boolean sameActiveAttachmentMessage(ChatStore.Message old,ChatStore.Message message){
        AttachmentRecord a=old.attachment,b=message.attachment;
        return a!=null&&b!=null&&a.active()&&b.active()&&sameAttachmentInfo(a.info,b.info)&&a.state.equals(b.state)&&a.route.equals(b.route)&&a.outgoing==b.outgoing&&old.time==message.time&&old.text.equals(message.text)&&old.state.equals(message.state);
    }
    private View messageDate(ChatStore.Message message,long day){TextView date=label(AndroidText.date(this,message.time,"MMMd"),12,muted);date.setTag("date:"+day+":"+messageKey(message));date.setGravity(Gravity.CENTER);date.setPadding(0,dp(16),0,dp(10));return date;}
    private boolean updateLiveAttachmentViews(List<ChatStore.Message> messages) {
        if(renderedMessages==null||renderedMessages.isEmpty()||messages.isEmpty()||deferredScrollPeer!=null)return false;
        Map<String,ChatStore.Message> before=new HashMap<>();for(ChatStore.Message message:renderedMessages)before.put(messageKey(message),message);
        Map<String,View> existing=new HashMap<>();for(int i=0;i<bubbles.getChildCount();i++){View view=bubbles.getChildAt(i);if(view.getTag() instanceof String)existing.put((String)view.getTag(),view);}
        TimelinePosition position=pendingTimelinePosition==null?captureTimelinePosition():pendingTimelinePosition;
        List<View> desired=new ArrayList<>();long previousDay=Long.MIN_VALUE;boolean layoutChanged=false;
        for(ChatStore.Message message:messages){
            long day=dayKey(message.time);if(day!=previousDay){String key="date:"+day+":"+messageKey(message);View date=existing.get(key);if(date==null){date=messageDate(message,day);layoutChanged=true;}desired.add(date);previousDay=day;}
            String key=messageKey(message);View row=existing.get(key);ChatStore.Message old=before.get(key);
            boolean reusable=row!=null&&old!=null&&!dirtyAttachmentRows.contains(key)&&sameMessage(old,message);
            if(reusable&&message.attachment!=null&&retryableAttachment(message.attachment)){
                AttachmentBatchQueue.RetryState state=controller.attachmentRetryState(controller.selectedId,message.id);
                Button retry=row.findViewWithTag("attachmentRetry");boolean enabled=controller.canSendAttachment()&&state!=AttachmentBatchQueue.RetryState.RETRYING&&state!=AttachmentBatchQueue.RetryState.RETRIED&&state!=AttachmentBatchQueue.RetryState.INELIGIBLE;
                reusable=retry!=null&&retry.getText().toString().equals(t(attachmentRetryLabel(state)))&&retry.isEnabled()==enabled;
            }
            if(!reusable&&row!=null&&old!=null&&!dirtyAttachmentRows.contains(key)){
                reusable=sameActiveAttachmentMessage(old,message)&&row.findViewWithTag("attachmentProgress")!=null;
            }
            if(!reusable){row=createBubble(message);layoutChanged=true;}
            desired.add(row);
        }
        Set<View> retained=new HashSet<>(desired);for(int i=bubbles.getChildCount()-1;i>=0;i--)if(!retained.contains(bubbles.getChildAt(i))){bubbles.removeViewAt(i);layoutChanged=true;}
        for(int i=0;i<desired.size();i++){View view=desired.get(i);if(i<bubbles.getChildCount()&&bubbles.getChildAt(i)==view)continue;if(view.getParent()==bubbles)bubbles.removeView(view);bubbles.addView(view,i);layoutChanged=true;}
        dirtyAttachmentRows.clear();
        // Waiting rows have no changing measurement; avoid formatting all queued files at every tick.
        for(ChatStore.Message message:messages){AttachmentRecord record=message.attachment;if(record==null||!record.active()||record.state.equals("queued")||record.state.equals("preparing")||record.state.equals("offered"))continue;
            View row=existing.get(messageKey(message));if(row==null||row.getParent()!=bubbles)continue;
            TransferProgress measurement=controller.transferProgress(controller.selectedId,record);
            TextView summary=row.findViewWithTag("attachmentSummary");
            if(summary!=null){String bytes=android.text.format.Formatter.formatShortFileSize(this,record.info.size);if(record.transferred>0)bytes=android.text.format.Formatter.formatShortFileSize(this,record.transferred)+" / "+bytes;summary.setText(t("attachmentSummary",bytes,attachmentState(record)));}
            TextView feedback=row.findViewWithTag("attachmentFeedback");if(feedback!=null){String text=attachmentFeedback(record,measurement);feedback.setText(text);feedback.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
            ProgressBar progress=row.findViewWithTag("attachmentProgress");if(progress!=null)updateAttachmentProgress(progress,record,measurement);
        }
        if(layoutChanged){pendingTimelinePosition=position;rebuildingMessages=true;++messageGeneration;messageScroll.removeCallbacks(restoreTimelinePosition);messageScroll.post(restoreTimelinePosition);}
        return true;
    }
    private static final class TimelinePosition {
        final String peer;final Object anchor;final int y,offset;final boolean bottom;
        TimelinePosition(String peer,Object anchor,int y,int offset,boolean bottom){this.peer=peer;this.anchor=anchor;this.y=y;this.offset=offset;this.bottom=bottom;}
    }
    private TimelinePosition captureTimelinePosition(){int y=messageScroll.getScrollY();View anchor=null;for(int i=0;i<bubbles.getChildCount();i++){View row=bubbles.getChildAt(i);if(row.getTop()<=y&&row.getTag()!=null)anchor=row;else if(row.getTop()>y)break;}return new TimelinePosition(renderedPeer,anchor==null?null:anchor.getTag(),y,anchor==null?0:y-anchor.getTop(),atBottom());}
    private void restoreTimelinePosition(){TimelinePosition position=pendingTimelinePosition;pendingTimelinePosition=null;if(position==null||isDestroyed()||!Objects.equals(position.peer,renderedPeer))return;View anchor=position.anchor==null?null:bubbles.findViewWithTag(position.anchor);int y=position.bottom?Math.max(0,bubbles.getHeight()-messageScroll.getHeight()):anchor==null?position.y:anchor.getTop()+Math.min(Math.max(0,position.offset),Math.max(0,anchor.getHeight()-1));messageScroll.scrollTo(0,y);rebuildingMessages=false;}
    private boolean markThumbnailDirty(String key){if(controller==null||!Objects.equals(renderedPeer,controller.selectedId))return false;boolean found=false;for(ChatStore.Message message:controller.messages)if(message.attachment!=null&&thumbnailKey(controller.selectedId,message.attachment).equals(key)){dirtyAttachmentRows.add(messageKey(message));found=true;}return found;}
    private void attachmentVisualChanged(String peer,String id){if(isDestroyed()||controller==null||!Objects.equals(peer,controller.selectedId)||!Objects.equals(peer,renderedPeer))return;boolean found=false;for(ChatStore.Message message:controller.messages)if(message.attachment!=null&&message.id.equals(id)){dirtyAttachmentRows.add(messageKey(message));found=true;}if(found)renderChat();}
    private String attachmentFeedback(AttachmentRecord record,TransferProgress measurement){
        if(record.state.equals("checking"))return measurement.checking()?t("attachmentCheckingProgress",android.text.format.Formatter.formatShortFileSize(this,measurement.checkedBytes()),android.text.format.Formatter.formatShortFileSize(this,measurement.checkingTotalBytes()),measurement.checkingTotalBytes()==0?100:100*measurement.checkedBytes()/measurement.checkingTotalBytes()):t("attachmentWaitingResume");
        return record.state.equals("transferring")&&measurement.bytesPerSecond()>0?t("attachmentSpeedEta",android.text.format.Formatter.formatShortFileSize(this,measurement.bytesPerSecond()),measurement.remainingSeconds()):"";
    }
    private void updateAttachmentProgress(ProgressBar progress,AttachmentRecord record,TransferProgress measurement){
        boolean measuredChecking=record.state.equals("checking")&&measurement.checking();
        int percent=measuredChecking?(measurement.checkingTotalBytes()==0?100:(int)(100*measurement.checkedBytes()/measurement.checkingTotalBytes())):record.info.size==0?0:(int)(100*record.transferred/record.info.size);
        progress.setProgress(percent);progress.setIndeterminate(record.state.equals("preparing")||record.state.equals("verifying")||record.state.equals("checking")&&!measuredChecking);progress.setContentDescription(t("attachmentProgress",percent,attachmentState(record)));
    }
    private void addBubble(ChatStore.Message message) {bubbles.addView(createBubble(message));}
    private View createBubble(ChatStore.Message message) {
        LinearLayout row = horizontal(); row.setGravity(message.outgoing ? Gravity.END : Gravity.START); row.setPadding(0, dp(4), 0, dp(4));
        row.setTag("message:"+message.id+":"+message.outgoing);
        LinearLayout bubble = vertical(); bubble.setBackground(shape(message.outgoing ? tonal : surface, 18)); bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        AttachmentRecord attachment=message.attachment;
        if(attachment==null){TextView body=label(message.text,16,ink);body.setTag("messageBody");body.setTextIsSelectable(true);bubble.addView(body);}
        else{
            String peer=controller.selectedId;
            bubble.setTag("attachment:"+attachment.info.id);
            String previewKey=thumbnailKey(peer,attachment);
            if(attachment.info.mime.startsWith("image/")&&photoAvailable(attachment)&&!failedThumbnails.contains(previewKey)&&(thumbnailTargets.contains(previewKey)||thumbnails.containsKey(previewKey))){
                bubble.setPadding(dp(4),dp(4),dp(4),dp(6));
                ImageView image=new ImageView(this);image.setTag("photo:"+attachment.info.id);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(message.outgoing?tonal:surface,14));image.setClipToOutline(true);
                image.setContentDescription(t("attachmentPhotoPreview",attachment.info.name));image.setImageResource(R.drawable.outline_photo_24);
                int width=Math.min(dp(240),Math.max(dp(100),Math.round((Math.max(dp(160),messageScroll.getWidth())-dp(48))*.78f)));
                Bitmap cached=thumbnails.get(previewKey);int height=cached==null?dp(160):Math.max(dp(100),Math.min(dp(300),Math.round(width*(float)cached.getHeight()/cached.getWidth())));
                bubble.addView(image,new LinearLayout.LayoutParams(width,height));image.setOnClickListener(v->openAttachment(peer,attachment));image.setOnLongClickListener(v->{attachmentMenu(image,peer,attachment);return true;});
                if(cached!=null)image.setImageBitmap(cached);else if(thumbnailTargets.contains(previewKey))thumbnail(peer,attachment,image);
                if(!attachment.outgoing){
                    GalleryPublisher.Result exported=gallery==null?null:gallery.cached(peer,attachment);
                    if(gallery!=null&&exported==null&&thumbnailTargets.contains(previewKey))gallery.inspect(peer,attachment);
                    String status=exported==null||exported.state()==null?null:exported.state()==dev.ghost.nearbyim.core.PhotoExportStore.State.SAVED?"gallerySaved":exported.state()==dev.ghost.nearbyim.core.PhotoExportStore.State.PENDING||exported.state()==dev.ghost.nearbyim.core.PhotoExportStore.State.COPIED?"gallerySaving":exported.state()==dev.ghost.nearbyim.core.PhotoExportStore.State.FAILED?"gallerySaveFailed":null;
                    if(status!=null){TextView outcome=label(t(status),12,muted);outcome.setMaxWidth(dp(240));bubble.addView(outcome);}
                    Button save=button(t("gallerySave"),false);save.setBackground(ripple(Color.TRANSPARENT,16));save.setOnClickListener(v->saveToGallery(peer,attachment));bubble.addView(save,new LinearLayout.LayoutParams(-1,-2));
                }
            }else{
                LinearLayout card=horizontal();card.setGravity(Gravity.CENTER_VERTICAL);ImageView symbol=icon(attachment.info.mime.startsWith("image/")?R.drawable.outline_photo_24:R.drawable.outline_description_24,accent,"");
                card.addView(symbol,new LinearLayout.LayoutParams(dp(36),dp(40)));LinearLayout description=vertical();description.setPadding(dp(10),0,0,0);
                TextView name=label(attachment.info.name,14,ink);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setTag("messageBody");description.addView(name);
                String bytes=android.text.format.Formatter.formatShortFileSize(this,attachment.info.size);if(attachment.active()&&attachment.transferred>0)bytes=android.text.format.Formatter.formatShortFileSize(this,attachment.transferred)+" / "+bytes;
                TextView status=label(t("attachmentSummary",bytes,failedThumbnails.contains(previewKey)?t("photoUnavailable"):attachmentState(attachment)),12,muted);status.setTag("attachmentSummary");description.addView(status);card.addView(description,new LinearLayout.LayoutParams(0,-2,1));bubble.addView(card,new LinearLayout.LayoutParams(Math.min(dp(260),Math.max(dp(160),Math.max(dp(160),messageScroll.getWidth())-dp(80))),-2));
                if(photoAvailable(attachment))card.setOnClickListener(v->openAttachment(peer,attachment));
                if(photoAvailable(attachment)||retryableAttachment(attachment))card.setOnLongClickListener(v->{attachmentMenu(card,peer,attachment);return true;});
            }
            TransferPath route=controller==null?TransferPath.forAttachment("",false,attachment):controller.attachmentRoute(peer,attachment);
            TextView path=label(t(route.key()),11,muted);path.setTag("attachmentPath");path.setPadding(0,dp(4),0,dp(4));path.setFocusable(true);path.setOnClickListener(v->showAttachmentTransferPath(peer,attachment));bubble.addView(path);
            if(attachment.active()||attachment.resumable()){
                TransferProgress measurement=controller==null?TransferProgress.UNKNOWN:controller.transferProgress(peer,attachment);
                String feedback=attachmentFeedback(attachment,measurement);TextView measurementView=label(feedback,11,muted);measurementView.setTag("attachmentFeedback");measurementView.setVisibility(feedback.isEmpty()?View.GONE:View.VISIBLE);bubble.addView(measurementView);
                LinearLayout progressRow=horizontal();progressRow.setGravity(Gravity.CENTER_VERTICAL);
                ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setTag("attachmentProgress");progress.setMax(100);updateAttachmentProgress(progress,attachment,measurement);progress.setProgressTintList(ColorStateList.valueOf(accent));progressRow.addView(progress,new LinearLayout.LayoutParams(0,dp(4),1));
                if(attachment.info.version==2){boolean paused=attachment.resumable();Button toggle=iconButton(paused?R.drawable.outline_play_arrow_24:R.drawable.outline_pause_24,t(paused?"attachmentResume":"attachmentPause"),muted);toggle.setOnClickListener(v->{if(controller!=null)controller.attachmentAction(peer,message.id,message.outgoing,paused?"resume":"pause");});progressRow.addView(toggle,new LinearLayout.LayoutParams(dp(48),dp(48)));}
                Button cancel=iconButton(R.drawable.outline_close_24,t("cancel"),muted);cancel.setOnClickListener(v->{if(controller!=null)controller.attachmentAction(peer,message.id,message.outgoing,"cancel");});progressRow.addView(cancel,new LinearLayout.LayoutParams(dp(48),dp(48)));bubble.addView(progressRow);
            }
            if(retryableAttachment(attachment)){
                AttachmentBatchQueue.RetryState state=controller.attachmentRetryState(peer,message.id);String key=attachmentRetryLabel(state);
                Button retry=button(t(key),false);retry.setTag("attachmentRetry");retry.setContentDescription(t(key));retry.setBackground(ripple(Color.TRANSPARENT,16));
                android.graphics.drawable.Drawable symbol=getDrawable(R.drawable.outline_refresh_24).mutate();symbol.setTint(accent);symbol.setBounds(0,0,dp(20),dp(20));retry.setCompoundDrawables(symbol,null,null,null);retry.setCompoundDrawablePadding(dp(6));
                retry.setEnabled(controller.canSendAttachment()&&state!=AttachmentBatchQueue.RetryState.RETRYING&&state!=AttachmentBatchQueue.RetryState.RETRIED&&state!=AttachmentBatchQueue.RetryState.INELIGIBLE);retry.setOnClickListener(v->retryAttachment(peer,message.id));bubble.addView(retry,new LinearLayout.LayoutParams(-2,dp(48)));
            }
            if(photoAvailable(attachment)){
                if(!attachment.outgoing&&fileLocations!=null){
                    AttachmentLocations.SaveStatus exported=fileLocations.savedStatus(peer,attachment);
                    if(exported==null)fileLocations.inspectReceived(peer,attachment);
                    dev.ghost.nearbyim.core.AttachmentDirectoryStore.State state=exported==null?null:exported.state();
                    if(state!=null){String status=state==dev.ghost.nearbyim.core.AttachmentDirectoryStore.State.SAVED?"receiveFolderSaved":state==dev.ghost.nearbyim.core.AttachmentDirectoryStore.State.PENDING||state==dev.ghost.nearbyim.core.AttachmentDirectoryStore.State.COPIED?"receiveFolderSaving":"receiveFolderFailed";
                        TextView outcome=label(t(status),12,muted);outcome.setTag("receiveFolderStatus");outcome.setMaxWidth(dp(260));bubble.addView(outcome);
                    }
                    if(state==dev.ghost.nearbyim.core.AttachmentDirectoryStore.State.FAILED||state==dev.ghost.nearbyim.core.AttachmentDirectoryStore.State.MISSING||state==null&&fileLocations.directory()!=null){
                        Button retry=button(t(state==null?"receiveFolderSave":"receiveFolderRetry"),false);retry.setTag("receiveFolderRetry");retry.setBackground(ripple(Color.TRANSPARENT,16));retry.setOnClickListener(v->saveToReceiveDirectory(peer,attachment));bubble.addView(retry,new LinearLayout.LayoutParams(-2,dp(48)));
                    }
                }
                Button location=button(t("attachmentShowInFolder"),false);location.setTag("attachmentLocation");location.setBackground(ripple(Color.TRANSPARENT,16));location.setOnClickListener(v->showAttachmentLocation(peer,attachment));bubble.addView(location,new LinearLayout.LayoutParams(-2,dp(48)));
            }
            bubble.setOnLongClickListener(v->{attachmentMenu(bubble,peer,attachment);return true;});
        }
        String time=AndroidText.date(this,message.time,"jm");TextView meta=label(message.outgoing?t("outgoingMessageMeta",time,attachment==null?AndroidText.messageState(this,message.state):attachmentState(attachment)):time,10,muted);
        meta.setTag("messageMeta");meta.setGravity(Gravity.END);meta.setPadding(dp(6),dp(4),dp(6),0);bubble.addView(meta);row.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
        if(messageWidth>0){TextView body=row.findViewWithTag("messageBody");if(body!=null)body.setMaxWidth(messageWidth);meta.setMaxWidth(messageWidth);}return row;
    }
    private String attachmentState(AttachmentRecord record){return t(record.stateKey());}
    private boolean photoAvailable(AttachmentRecord record){return record.outgoing?record.state.equals("delivered")&&record.info.mime.startsWith("image/"):record.state.equals("received");}
    private boolean retryableAttachment(AttachmentRecord record){return record.outgoing&&record.info.version==2&&record.state.equals("failed");}
    private String attachmentRetryLabel(AttachmentBatchQueue.RetryState state){return state==AttachmentBatchQueue.RetryState.RETRYING?"attachmentRetrying":state==AttachmentBatchQueue.RetryState.RETRIED?"attachmentRetried":state==AttachmentBatchQueue.RetryState.INELIGIBLE?"attachmentRetryIneligible":"attachmentRetry";}
    private void attachmentMenu(View anchor,String peer,AttachmentRecord record){
        if(retryableAttachment(record)){
            if(controller==null)return;if(attachmentActions!=null)attachmentActions.dismiss();PopupMenu menu=new PopupMenu(this,anchor);attachmentActions=menu;
            AttachmentBatchQueue.RetryState state=controller.attachmentRetryState(peer,record.info.id);boolean ready=controller.canSendAttachment();
            menu.getMenu().add(0,4,0,t(attachmentRetryLabel(state))).setEnabled(ready&&state!=AttachmentBatchQueue.RetryState.RETRYING&&state!=AttachmentBatchQueue.RetryState.RETRIED&&state!=AttachmentBatchQueue.RetryState.INELIGIBLE);
            if(controller.hasRetryableAttachmentBatch(peer,record.info.id))menu.getMenu().add(0,5,1,t("attachmentRetryBatch")).setEnabled(ready&&state!=AttachmentBatchQueue.RetryState.RETRYING);
            if(state==AttachmentBatchQueue.RetryState.AVAILABLE||state==AttachmentBatchQueue.RetryState.UNAVAILABLE)menu.getMenu().add(0,6,2,t("attachmentReselect")).setEnabled(ready);
            menu.setOnMenuItemClickListener(item->{if(item.getItemId()==4)retryAttachment(peer,record.info.id);else if(item.getItemId()==5)retryAttachmentBatch(peer,record.info.id);else if(item.getItemId()==6&&controller!=null)chooseRetryReplacement(peer,record.info.id,controller.attachmentSessionToken(),controller.attachmentSelectionSession(peer));return true;});menu.show();return;
        }
        if(!photoAvailable(record))return;if(attachmentActions!=null)attachmentActions.dismiss();PopupMenu menu=new PopupMenu(this,anchor);attachmentActions=menu;if(record.info.mime.startsWith("image/")||record.info.canOpenExternally())menu.getMenu().add(0,1,0,t("attachmentOpen"));menu.getMenu().add(0,2,1,t("attachmentSaveAs"));if(record.info.mime.startsWith("image/"))menu.getMenu().add(0,3,2,t("gallerySave"));
        menu.getMenu().add(0,7,3,t("attachmentShowInFolder"));
        menu.setOnMenuItemClickListener(item->{if(item.getItemId()==1)openAttachment(peer,record);else if(item.getItemId()==3)saveToGallery(peer,record);else if(item.getItemId()==7)showAttachmentLocation(peer,record);else saveAttachment(peer,record);return true;});menu.show();
    }
    private Button attachmentChoice(int resource,String text,Runnable action){
        Button button=button(text,false);button.setBackground(ripple(Color.TRANSPARENT,18));button.setContentDescription(text);android.graphics.drawable.Drawable drawable=getDrawable(resource).mutate();drawable.setTint(accent);drawable.setBounds(0,0,dp(28),dp(28));button.setCompoundDrawables(null,drawable,null,null);button.setCompoundDrawablePadding(dp(4));button.setOnClickListener(v->action.run());return button;
    }
    private void showEmoji(){
        if(!composer.isEnabled())return;GridLayout grid=new GridLayout(this);grid.setColumnCount(4);grid.setPadding(dp(12),dp(8),dp(12),dp(8));AlertDialog dialog=new AdaptiveDialogBuilder().setTitle(t("emoji")).setView(grid).create();
        for(String emoji:new String[]{"😀","😂","🥰","😎","😭","🤔","👍","👋","❤️","🎉","🙏","✨"}){
            Button choice=button(emoji,false);choice.setTextSize(26);choice.setContentDescription(emoji);choice.setBackground(ripple(Color.TRANSPARENT,18));choice.setOnClickListener(v->{int start=Math.max(0,composer.getSelectionStart()),end=Math.max(start,composer.getSelectionEnd());composer.getText().replace(start,end,emoji);dialog.dismiss();composer.requestFocus();});GridLayout.LayoutParams params=new GridLayout.LayoutParams();params.width=dp(64);params.height=dp(56);grid.addView(choice,params);
        }dialog.show();
    }
    private void chooseAttachment(boolean photo){
        if(controller==null||!controller.canSendAttachment())return;attachmentTray.setVisibility(View.GONE);pendingAttachmentPeer=controller.connectedPeerId;pendingAttachmentToken=controller.attachmentSessionToken();
        Intent intent=photo?AttachmentSelection.photosIntent(this):AttachmentSelection.filesIntent();
        try{startActivityForResult(intent,20);}catch(ActivityNotFoundException e){pendingAttachmentPeer=null;toast(t("attachmentFailed"));}
    }
    private boolean retrySelectionCurrent(ChatController target,String peer,long token,String session){return !isDestroyed()&&controller==target&&session!=null&&token==target.attachmentSessionToken()&&session.equals(target.attachmentSelectionSession(peer));}
    private void retryAttachment(String peer,String id){
        ChatController target=controller;if(target==null)return;long token=target.attachmentSessionToken();String session=target.attachmentSelectionSession(peer);
        AttachmentBatchQueue.RetryState state=target.attachmentRetryState(peer,id);if(state==AttachmentBatchQueue.RetryState.RETRYING||state==AttachmentBatchQueue.RetryState.RETRIED||state==AttachmentBatchQueue.RetryState.INELIGIBLE)return;
        if(state==AttachmentBatchQueue.RetryState.UNAVAILABLE){offerRetryReplacement(peer,id,token,session);return;}
        target.retryAttachment(peer,token,id).whenComplete((value,failure)->ui.post(()->{
            if(!retrySelectionCurrent(target,peer,token,session))return;
            if(failure!=null&&ChatController.attachmentRetryNeedsSelection(failure))offerRetryReplacement(peer,id,token,session);else toast(t(failure==null?UiText.of("attachmentRetryQueued"):ChatController.attachmentError(failure)));
        }));
    }
    private void retryAttachmentBatch(String peer,String id){
        ChatController target=controller;if(target==null)return;long token=target.attachmentSessionToken();String session=target.attachmentSelectionSession(peer);
        target.retryFailedAttachmentBatch(peer,token,id).whenComplete((result,failure)->ui.post(()->{if(retrySelectionCurrent(target,peer,token,session))toast(t(failure==null?ChatController.attachmentRetryBatchFeedback(result):ChatController.attachmentError(failure)));}));
    }
    private void offerRetryReplacement(String peer,String id,long token,String session){
        ChatController target=controller;if(target==null||!retrySelectionCurrent(target,peer,token,session))return;
        new AdaptiveDialogBuilder().setMessage(t("attachmentRetrySelectAgain")).setPositiveButton(t("attachmentReselect"),(dialog,which)->chooseRetryReplacement(peer,id,token,session)).setNegativeButton(t("cancel"),null).show();
    }
    private void chooseRetryReplacement(String peer,String id,long token,String session){
        ChatController target=controller;if(target==null||!retrySelectionCurrent(target,peer,token,session)){toast(t("notConnected"));return;}
        if(pendingRetryId!=null)return;pendingRetryPeer=peer;pendingRetryId=id;pendingRetryToken=token;pendingRetrySession=session;
        Intent intent=AttachmentSelection.filesIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false);
        try{startActivityForResult(intent,23);}catch(ActivityNotFoundException missing){clearPendingAttachmentRetry();toast(t("attachmentFailed"));}
    }
    private void clearPendingAttachmentRetry(){pendingRetryPeer=null;pendingRetryId=null;pendingRetrySession=null;pendingRetryToken=0;selectedRetryUri=null;}
    private void saveToGallery(String peer,AttachmentRecord record){
        if(gallery==null||controller==null){toast(t("gallerySaveFailed"));return;}
        if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){try{pendingGalleryRecord=record.encode();}catch(java.io.IOException invalid){toast(t("gallerySaveFailed"));return;}pendingGalleryPeer=peer;requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},968);return;}
        controller.attachmentPath(peer,record).thenCompose(path->gallery.export(peer,record,path,true)).whenComplete((value,error)->ui.post(()->{if(!isDestroyed())toast(t(error==null?"gallerySaved":"gallerySaveFailed"));}));
    }
    private void saveAttachment(String peer,AttachmentRecord record){
        try{pendingExportPeer=peer;pendingExportRecord=record.encode();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(record.info.mime);intent.putExtra(Intent.EXTRA_TITLE,AttachmentInfo.safeName(record.info.name));startActivityForResult(intent,21);}
        catch(Exception e){pendingExportPeer=null;pendingExportRecord=null;toast(t("attachmentFailed"));}
    }
    private void showAttachmentLocation(String peer,AttachmentRecord record){
        ChatController target=controller;if(target==null)return;
        target.attachmentLocation(peer,record).whenComplete((location,error)->ui.post(()->{
            if(isDestroyed()||controller!=target||!Objects.equals(peer,target.selectedId))return;
            if(error!=null){toast(t("attachmentLocationUnavailable"));return;}
            String key=switch(location.availability()){
                case MISSING -> "attachmentLocationMissing";
                case ACCESS_LOST -> "attachmentLocationAccessLost";
                case UNAVAILABLE -> "attachmentLocationUnavailable";
                case AVAILABLE -> switch(location.kind()){
                    case DOCUMENT -> "attachmentLocationDocument";
                    case GALLERY -> "attachmentLocationGallery";
                    case APP_PRIVATE -> "attachmentLocationPrivate";
                };
            };
            LinearLayout content=vertical();content.setPadding(dp(20),dp(8),dp(20),dp(8));
            content.addView(label(location.displayName(),15,ink));TextView description=label(t(key),13,muted);description.setPadding(0,dp(12),0,dp(12));content.addView(description);
            AlertDialog dialog=new AdaptiveDialogBuilder().setTitle(t("attachmentLocationTitle")).setView(content).setNegativeButton(t("close"),null).create();
            Intent browse=AttachmentLocations.browseIntent(location);
            if(browse!=null){Button find=button(t("attachmentBrowseSaved"),false);find.setOnClickListener(v->{dialog.dismiss();try{startActivityForResult(browse,25);}catch(ActivityNotFoundException|SecurityException unavailable){toast(t("attachmentLocationUnavailable"));}});content.addView(find);}
            Intent saved=AttachmentLocations.openSavedIntent(location,record.info.mime);
            if(saved!=null&&(record.info.mime.startsWith("image/")||record.info.canOpenExternally())){Button open=button(t("attachmentOpenSaved"),false);open.setOnClickListener(v->{dialog.dismiss();try{startActivity(saved);}catch(ActivityNotFoundException|SecurityException unavailable){toast(t("attachmentLocationUnavailable"));}});content.addView(open);}
            if(record.info.mime.startsWith("image/")||record.info.canOpenExternally()){Button original=button(t("attachmentViewOriginal"),false);original.setOnClickListener(v->{dialog.dismiss();openAttachment(peer,record);});content.addView(original);}
            Button save=button(t(location.availability()==AttachmentLocations.Availability.AVAILABLE?"attachmentSaveAs":"attachmentSaveAgain"),false);save.setOnClickListener(v->{dialog.dismiss();saveAttachment(peer,record);});content.addView(save);dialog.show();
        }));
    }
    private void openAttachment(String peer,AttachmentRecord record){
        if(!record.info.mime.startsWith("image/")&&!record.info.canOpenExternally()){saveAttachment(peer,record);return;}
        if(controller==null)return;controller.attachmentPath(peer,record).whenComplete((path,error)->ui.post(()->{
            if(isDestroyed())return;if(error!=null){toast(t("attachmentUnavailable"));return;}
            Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".attachments").appendPath(peer).appendPath(path.getFileName().toString()).appendQueryParameter("name",record.info.name).appendQueryParameter("mime",record.info.mime).build();
            try{Intent intent=record.info.mime.startsWith("image/")?new Intent(this,PhotoActivity.class).setData(uri).putExtra("peer",peer).putExtra("record",record.encode()):new Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(record.info.name,uri));
            startActivity(intent);}catch(ActivityNotFoundException|java.io.IOException e){toast(t("attachmentFailed"));}
        }));
    }
    private void thumbnail(String peer,AttachmentRecord record,ImageView view){
        String key=thumbnailKey(peer,record);Bitmap cached=thumbnails.get(key);if(cached!=null){view.setImageBitmap(cached);return;}
        if(controller==null||failedThumbnails.contains(key)||!loadingThumbnails.add(key))return;
        try{controller.attachmentPath(peer,record).thenApplyAsync(path->{
            Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".attachments").appendPath(peer).appendPath(path.getFileName().toString()).build();
            try{return PhotoDecoder.decode(getContentResolver(),uri,512);}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}
        },imageWorker).whenComplete((bitmap,error)->ui.post(()->{
            loadingThumbnails.remove(key);if(isDestroyed())return;boolean dirty=markThumbnailDirty(key);
            if(bitmap==null){failedThumbnails.add(key);while(failedThumbnails.size()>16){String expired=failedThumbnails.iterator().next();failedThumbnails.remove(expired);dirty|=markThumbnailDirty(expired);}}
            else{thumbnails.put(key,bitmap);while(thumbnails.size()>12){String expired=thumbnails.keySet().iterator().next();thumbnails.remove(expired);dirty|=markThumbnailDirty(expired);}}
            if(dirty)renderChat();
        }));}catch(java.util.concurrent.RejectedExecutionException e){loadingThumbnails.remove(key);}
    }
    private String thumbnailKey(String peer,AttachmentRecord record){return peer+":"+record.info.id+":"+record.outgoing;}
    private void updateMessageWidths() {
        if (messageScroll == null || bubbles == null) return;
        int available = messageScroll.getWidth() - messageScroll.getPaddingLeft() - messageScroll.getPaddingRight(); if (available <= 0) return;
        messageWidth = Math.max(dp(64), Math.min(dp(560),Math.round(available * .78f) - dp(28)));
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
        approvalDialog = new AdaptiveDialogBuilder().setTitle(t("requestNamedTitle", safe(controller.approvalName))).setMessage(detailText)
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
            new AdaptiveDialogBuilder().setTitle(t("connectionSwitchTitle")).setMessage(t("connectionSwitchBody"))
                .setPositiveButton(t("disconnectAndConnect"), (d, w) -> { if (controller != null) { pendingAction = action; pendingStage = CLOSE_STAGE; controller.disconnect(); } })
                .setNegativeButton(t("cancel"), null).show();
        } else withPermissions(action);
    }
    private void manualConnect() {
        EditText address = new EditText(this); address.setSingleLine(true); address.setHint("192.168.1.20:54321"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = vertical(); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(address); CheckBox remember = new CheckBox(this); remember.setText(t("rememberConnectedDevice")); remember.setChecked(true); box.addView(remember);
        new AdaptiveDialogBuilder().setTitle(t("direct")).setMessage(t("androidDirectBody")).setView(box)
            .setPositiveButton(t("reconnect"), (d, w) -> requestConnection(new UiAction(UiAction.ADDRESS, Peer.LAN, null, address.getText().toString(), null, remember.isChecked())))
            .setNegativeButton(t("cancel"), null).show();
    }
    private void startTransport(int chosen) {
        if (controller == null) return; startForegroundService(new Intent(this, ChatService.class).setAction(ChatService.START)); controller.start(chosen);
    }
    private void withPermissions(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        if (action.mode == Peer.WIFI_DIRECT && !controller.directAvailable()) { pendingAction = null; pendingStage = 0; toast(t("directUnavailable")); render(); return; }
        if (action.mode == Peer.BLUETOOTH && !controller.bluetoothAvailable()) { pendingAction = null; pendingStage = 0; toast(t("unsupportedBluetooth")); return; }
        Set<String> granted = new HashSet<>(); for (String permission : requiredPermissions(action)) if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) granted.add(permission);
        List<String> request = new ArrayList<>(missingPermissionsFor(Build.VERSION.SDK_INT, action.mode, action.type, granted));
        SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsAsked", false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            request.add(Manifest.permission.POST_NOTIFICATIONS); prefs.edit().putBoolean("notificationsAsked", true).apply();
        }
        if (!request.isEmpty()) { pendingAction = action; pendingStage = PERMISSION_STAGE; requestPermissions(request.toArray(new String[0]), 7); }
        else ensureEnabled(action);
    }
    private List<String> requiredPermissions(UiAction action) {
        return requiredPermissionsFor(Build.VERSION.SDK_INT, action.mode, action.type);
    }
    static List<String> requiredPermissionsFor(int sdk, int transport, int action) {
        if (transport == Peer.WIFI_DIRECT) {
            if (sdk >= 33) return Collections.singletonList(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (sdk >= 31) return List.of(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
            return Collections.singletonList(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (transport != Peer.BLUETOOTH) return Collections.emptyList();
        if (sdk >= 31) {
            List<String> permissions = new ArrayList<>(); permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (action == UiAction.SEARCH || action == UiAction.PEER) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (action == UiAction.DISCOVERABLE) permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE); return permissions;
        }
        return action == UiAction.SEARCH ? Collections.singletonList(Manifest.permission.ACCESS_FINE_LOCATION) : Collections.emptyList();
    }
    static List<String> missingPermissionsFor(int sdk, int transport, int action, Set<String> granted) {
        if (transport == Peer.WIFI_DIRECT && sdk >= 31 && sdk <= 32) {
            // Android 12 requires the pair even when only precise access is missing.
            if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION)) return Collections.emptyList();
            return List.of(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> request = new ArrayList<>();
        for (String permission : requiredPermissionsFor(sdk, transport, action)) if (!granted.contains(permission)) request.add(permission);
        return request;
    }
    static List<String> fileRoutePermissionsFor(int sdk,Set<String> granted){return missingPermissionsFor(sdk,Peer.WIFI_DIRECT,UiAction.START,granted);}
    private void requestFileRoutePermissions(){
        if(controller==null||!controller.directAvailable()||pendingAction!=null)return;
        SharedPreferences prefs=getSharedPreferences("ui",MODE_PRIVATE);if(prefs.getBoolean("fileRoutePermissionsAsked",false))return;
        Set<String> granted=new HashSet<>();for(String permission:requiredPermissionsFor(Build.VERSION.SDK_INT,Peer.WIFI_DIRECT,UiAction.START))if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)granted.add(permission);
        List<String> missing=fileRoutePermissionsFor(Build.VERSION.SDK_INT,granted);prefs.edit().putBoolean("fileRoutePermissionsAsked",true).apply();
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),969);
    }
    private boolean bluetoothPermissionsGranted() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private boolean bluetoothEnabled() { try { return controller != null && controller.bluetoothEnabled(); } catch (SecurityException e) { return false; } }
    private boolean directEnabled() { try { return controller != null && controller.directEnabled(); } catch (SecurityException e) { return false; } }
    private boolean directPermissionsGranted() { for (String permission : requiredPermissionsFor(Build.VERSION.SDK_INT, Peer.WIFI_DIRECT, UiAction.START)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false; return true; }
    private boolean legacyDirectLocationEnabled() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        try { if (Build.VERSION.SDK_INT >= 28) { android.location.LocationManager location = getSystemService(android.location.LocationManager.class); return location != null && location.isLocationEnabled(); }
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (RuntimeException unavailable) { return false; }
    }
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if(requestCode==969){render();return;}
        if(requestCode==968){String peer=pendingGalleryPeer,encoded=pendingGalleryRecord;pendingGalleryPeer=null;pendingGalleryRecord=null;if(peer!=null&&encoded!=null&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED){try{saveToGallery(peer,AttachmentRecord.decode(encoded));}catch(java.io.IOException invalid){toast(t("gallerySaveFailed"));}}else toast(t("gallerySavePermission"));return;}
        if (requestCode != 7 || pendingAction == null || pendingStage != PERMISSION_STAGE) return; UiAction action = pendingAction;
        for (String permission : requiredPermissions(action)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = null; pendingStage = 0;
            new AdaptiveDialogBuilder().setTitle(t(action.mode == Peer.WIFI_DIRECT ? "directPermissionTitle" : "bluetoothPermissionTitle")).setMessage(t(action.mode == Peer.WIFI_DIRECT ? "directPermissionRequired" : "bluetoothPermissionBody"))
                .setPositiveButton(t("appSettings"), (d, w) -> appSettings()).setNegativeButton(t("close"), null).show(); render(); return;
        }
        ensureEnabled(action);
    }
    private void ensureEnabled(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        try {
            if (action.mode == Peer.WIFI_DIRECT && !controller.directEnabled()) { pendingAction = action; pendingStage = WIFI_STAGE; wifiSettingsReturned = false; openWifiSettings(); }
            else if (action.mode == Peer.BLUETOOTH && !controller.bluetoothEnabled()) { pendingAction = action; pendingStage = ENABLE_STAGE; startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8); }
            else { pendingAction = null; pendingStage = 0; execute(action); }
        } catch (SecurityException e) { pendingAction = null; pendingStage = 0; toast(t(action.mode == Peer.WIFI_DIRECT ? "directPermissionRequired" : "bluetoothPermissionRevoked")); }
    }
    private void openWifiSettings() {
        if (Build.VERSION.SDK_INT >= 29) try { startActivityForResult(new Intent(Settings.Panel.ACTION_WIFI), 10); return; } catch (ActivityNotFoundException unavailable) {}
        try { startActivityForResult(new Intent(Settings.ACTION_WIFI_SETTINGS), 10); }
        catch (ActivityNotFoundException unavailable) { pendingAction = null; pendingStage = 0; toast(t("directSettingsUnavailable")); }
    }
    private void openLocationSettings() { try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (ActivityNotFoundException unavailable) { toast(t("directSettingsUnavailable")); } }
    private void resumePending() {
        if (pendingAction == null || controller == null) return;
        boolean granted = true; for (String permission : requiredPermissions(pendingAction)) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) granted = false;
        if (pendingStage == CLOSE_STAGE) { if (!controller.hasSession() && !controller.connecting) withPermissions(pendingAction); }
        else if (pendingStage == BIND_STAGE || pendingStage == PERMISSION_STAGE && granted) withPermissions(pendingAction);
        else if (pendingStage == ENABLE_STAGE && granted && bluetoothEnabled()) ensureEnabled(pendingAction);
        else if (pendingStage == WIFI_STAGE && wifiSettingsReturned) {
            wifiSettingsReturned = false;
            if (directEnabled()) withPermissions(pendingAction);
            else { pendingAction = null; pendingStage = 0; toast(t("directEnableCanceled")); render(); }
        } else if (pendingStage == WIFI_STAGE && directEnabled()) withPermissions(pendingAction);
    }
    private void execute(UiAction action) {
        if (controller == null) { pendingAction = action; pendingStage = BIND_STAGE; return; }
        switch (action.type) {
            case UiAction.START: startTransport(action.mode); break;
            case UiAction.SEARCH:
                if (!controller.running(action.mode)) startTransport(action.mode);
                controller.search(action.mode); break;
            case UiAction.DISCOVERABLE:
                if (!controller.bluetoothRunning) startTransport(Peer.BLUETOOTH);
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 9); break;
            case UiAction.PEER:
            case UiAction.ADDRESS:
            case UiAction.RECONNECT:
                if (!controller.running(action.mode)) startTransport(action.mode);
                outgoingRequest = true;
                if (action.type == UiAction.PEER) controller.connect(action.peer, action.remember);
                else if (action.type == UiAction.ADDRESS) controller.connectAddress(action.address, action.remember);
                else controller.reconnect(action.peerId);
                break;
        }
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==26){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&fileLocations!=null)fileLocations.selectDirectory(data.getData(),data.getFlags()).whenComplete((value,error)->ui.post(()->{if(isDestroyed())return;if(error!=null)toast(t("receiveFolderUnavailable"));receiveDirectoryChanged();}));return;
        }
        if(requestCode==955){verificationBusy=false;if(resultCode==RESULT_OK&&data!=null){pendingVerificationCode=data.getStringExtra("code");resumeVerificationResult();}else pendingVerification=null;updateVerificationDialog();return;}
        if(requestCode==956){
            IdentityVerification expected=pendingVerification;android.app.Dialog expectedPage=verificationDialog;pendingVerification=null;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&expected!=null){android.net.Uri uri=data.getData();verificationBusy=true;updateVerificationDialog();
                verificationWork(()->{String decoded=null;try{Bitmap image=PhotoDecoder.decode(getContentResolver(),uri,1024);if(image!=null){int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);decoded=dev.ghost.nearbyim.verification.QrCodes.decode(pixels,w,h);image.recycle();}}catch(Exception invalid){}
                    String scanned=decoded;ui.post(()->verificationImageResult(expectedPage,expected,scanned));
                });
            }else{verificationBusy=false;updateVerificationDialog();}return;
        }
        if(requestCode==957){IdentityVerification expected=pendingVerification;pendingVerification=null;verificationBusy=false;updateVerificationDialog();if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&expected!=null){Uri uri=data.getData();verificationWork(()->{try{Bitmap qr=verificationBitmap(expected);try(java.io.OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null||!qr.compress(Bitmap.CompressFormat.PNG,100,output))throw new java.io.IOException("QR export failed");}finally{qr.recycle();}}catch(Exception error){ui.post(()->{if(!isDestroyed())toast(t("verificationReadFailed"));});}});}return;}
        if(requestCode==20){
            try{List<Uri> uris=resultCode==RESULT_OK?AttachmentSelection.from(data):List.of();
                if(uris.isEmpty()){pendingAttachmentPeer=null;return;}
                Intent preview=new Intent(this,AttachmentSelectionActivity.class).putParcelableArrayListExtra(AttachmentSelection.URIS,new ArrayList<>(uris));preview.setClipData(AttachmentSelection.result(uris).getClipData());preview.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(preview,22);
            }catch(IllegalArgumentException|ActivityNotFoundException invalid){pendingAttachmentPeer=null;toast(t("attachmentFailed"));}return;
        }
        if(requestCode==22){try{selectedAttachmentUris=new ArrayList<>(resultCode==RESULT_OK?AttachmentSelection.from(data):List.of());}catch(IllegalArgumentException invalid){selectedAttachmentUris=null;toast(t("attachmentFailed"));}
            if(selectedAttachmentUris==null||selectedAttachmentUris.isEmpty())pendingAttachmentPeer=null;resumeAttachmentSelection();return;
        }
        if(requestCode==23){
            try{List<Uri> uris=resultCode==RESULT_OK?AttachmentSelection.from(data):List.of();if(uris.size()!=1||pendingRetryId==null){clearPendingAttachmentRetry();return;}
                Intent preview=new Intent(this,AttachmentSelectionActivity.class).putParcelableArrayListExtra(AttachmentSelection.URIS,new ArrayList<>(uris));preview.setClipData(AttachmentSelection.result(uris).getClipData());preview.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(preview,24);
            }catch(IllegalArgumentException|ActivityNotFoundException invalid){clearPendingAttachmentRetry();toast(t("attachmentFailed"));}return;
        }
        if(requestCode==24){
            try{List<Uri> uris=resultCode==RESULT_OK?AttachmentSelection.from(data):List.of();selectedRetryUri=uris.size()==1?uris.get(0):null;}catch(IllegalArgumentException invalid){selectedRetryUri=null;toast(t("attachmentFailed"));}
            if(selectedRetryUri==null)clearPendingAttachmentRetry();resumeAttachmentSelection();return;
        }
        if(requestCode==21){
            selectedExportUri=resultCode==RESULT_OK&&data!=null?data.getData():null;
            selectedExportFlags=resultCode==RESULT_OK&&data!=null?data.getFlags():0;
            if(selectedExportUri==null){pendingExportPeer=null;pendingExportRecord=null;}
            resumeAttachmentSelection();return;
        }
        if(requestCode==25)return; // Browsing a saved location must never enqueue the selected file.
        if (requestCode == 10 && pendingAction != null && pendingStage == WIFI_STAGE) {
            // Full Settings can return before the asynchronous service rebind.
            wifiSettingsReturned = true; resumePending();
        } else if (requestCode == 8 && pendingAction != null && pendingStage == ENABLE_STAGE) {
            UiAction action = pendingAction; if (resultCode == RESULT_OK) { pendingStage = BIND_STAGE; withPermissions(action); }
            else { pendingAction = null; pendingStage = 0; toast(t("bluetoothEnableCanceled")); }
        } else if (requestCode == 9) { discoverableUntil = resultCode > 0 ? SystemClock.elapsedRealtime() + resultCode * 1000L : 0; updateDiscoverability(); }
    }
    private void resumeAttachmentSelection(){
        if(controller==null)return;
        if(selectedRetryUri!=null&&pendingRetryPeer!=null&&pendingRetryId!=null){
            ChatController target=controller;Uri uri=selectedRetryUri;String peer=pendingRetryPeer,id=pendingRetryId,session=pendingRetrySession;long token=pendingRetryToken;
            clearPendingAttachmentRetry();target.retryAttachmentWithSelection(peer,token,session,id,uri).whenComplete((value,failure)->ui.post(()->{if(!retrySelectionCurrent(target,peer,token,session))return;if(failure!=null&&ChatController.attachmentRetryNeedsSelection(failure))offerRetryReplacement(peer,id,token,session);else toast(t(failure==null?UiText.of("attachmentRetryQueued"):ChatController.attachmentError(failure)));}));
        }
        if(selectedAttachmentUris!=null&&!selectedAttachmentUris.isEmpty()&&pendingAttachmentPeer!=null){
            List<Uri> uris=List.copyOf(selectedAttachmentUris);String peer=pendingAttachmentPeer;long token=pendingAttachmentToken;
            selectedAttachmentUris=null;pendingAttachmentPeer=null;controller.sendAttachments(peer,token,uris);
        }
        if(selectedExportUri!=null&&pendingExportPeer!=null&&pendingExportRecord!=null){
            Uri uri=selectedExportUri;String peer=pendingExportPeer,encoded=pendingExportRecord;int flags=selectedExportFlags;
            selectedExportUri=null;selectedExportFlags=0;pendingExportPeer=null;pendingExportRecord=null;
            try{AttachmentRecord record=AttachmentRecord.decode(encoded);controller.exportAttachment(peer,record,uri,flags).whenComplete((v,e)->{if(e!=null)ui.post(()->{if(!isDestroyed())toast(t("attachmentFailed"));});});}
            catch(Exception e){toast(t("attachmentFailed"));}
        }
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
        menu.getMenu().add(0,5,0,t("verifyIdentity"));
        if(controller!=null&&controller.hasActiveAttachments()){menu.getMenu().add(0,6,5,t("attachmentBatchPause"));menu.getMenu().add(0,7,6,t("attachmentBatchResume"));menu.getMenu().add(0,8,7,t("attachmentBatchCancel"));}
        menu.getMenu().add(0, 1, 0, t("deviceInfo"));
        if (controller != null && controller.hasSession()) menu.getMenu().add(0, 2, 1, t("disconnect"));
        menu.getMenu().add(0, 3, 2, t("clearConversation"));
        if(controller!=null&&controller.hasSession())menu.getMenu().add(0,4,3,t("transferDiagnostics"));
        menu.setOnMenuItemClickListener(item -> {
            if (controller == null) return true;
            switch (item.getItemId()) {
                case 1: deviceInfo(controller.selectedId, controller.selectedName); break;
                case 2: controller.disconnect(); break;
                case 3: clearConversation(); break;
                case 4: ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(t("transferDiagnostics"),controller.transferDiagnostics()));toast(t("diagnosticsCopied"));break;
                case 5: openVerification();break;
                case 6: controller.pauseSendQueue();break;
                case 7: controller.resumeAttachments();break;
                case 8: controller.cancelSendQueue();break;
            }
            return true;
        }); menu.show();
    }

    private Bitmap verificationBitmap(IdentityVerification snapshot){
        int edge=512;boolean[] cells=dev.ghost.nearbyim.verification.QrCodes.encode(snapshot.code.qrPayload(),edge);int[] pixels=new int[cells.length];for(int i=0;i<pixels.length;i++)pixels[i]=cells[i]?Color.BLACK:Color.WHITE;
        return Bitmap.createBitmap(pixels,edge,edge,Bitmap.Config.ARGB_8888);
    }
    private void verificationWork(Runnable action){
        try{imageWorker.execute(action);}catch(java.util.concurrent.RejectedExecutionException busy){verificationBusy=false;if(verificationNotice!=null)verificationNotice.setText(t("verificationReadFailed"));updateVerificationDialog();}
    }
    private Button verificationAction(String key,int icon,Runnable action){Button button=button(t(key),false);android.graphics.drawable.Drawable drawable=getDrawable(icon);if(drawable!=null){drawable=drawable.mutate();drawable.setTint(ink);drawable.setBounds(0,0,dp(22),dp(22));button.setCompoundDrawables(drawable,null,null,null);button.setCompoundDrawablePadding(dp(10));}button.setOnClickListener(v->action.run());return button;}
    private void openVerification(){
        if(controller==null)return;IdentityVerification expected=controller.verification(controller.selectedId);
        if(expected==null){toast(t("verificationConnectFirst"));return;}
        if(verificationDialog!=null)verificationDialog.dismiss();verificationSnapshot=expected;verificationBusy=false;
        android.app.Dialog dialog=new android.app.Dialog(this);verificationDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(background);LinearLayout centered=vertical();centered.setGravity(Gravity.CENTER_HORIZONTAL);LinearLayout body=vertical();body.setPadding(dp(20),dp(16),dp(20),dp(24));
        int width=Math.max(dp(160),Math.min(dp(640),root.dialogBounds().width()));centered.addView(body,new LinearLayout.LayoutParams(width,-2));scroll.addView(centered);
        LinearLayout toolbar=horizontal();toolbar.setGravity(Gravity.CENTER_VERTICAL);Button back=iconButton(R.drawable.outline_arrow_back_24,t("close"),ink);back.setOnClickListener(v->dialog.dismiss());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));toolbar.addView(label(t("verificationTitle"),20,ink),new LinearLayout.LayoutParams(0,-2,1));body.addView(toolbar);
        TextView name=label(safe(controller.selectedName),18,ink);name.setTypeface(null,Typeface.BOLD);body.addView(name);
        verificationConnection=label(t("encryptionActive"),13,accent);verificationConnection.setCompoundDrawablePadding(dp(6));verificationConnection.setCompoundDrawablesWithIntrinsicBounds(R.drawable.outline_lock_24,0,0,0);verificationConnection.setCompoundDrawableTintList(ColorStateList.valueOf(accent));body.addView(verificationConnection);
        verificationIdentity=label(t(expected.verified?"identityVerified":"identityUnverified"),14,muted);body.addView(verificationIdentity);
        LinearLayout card=vertical();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(dp(18),dp(20),dp(18),dp(20));card.setBackground(shape(tonal,24));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(20);cardParams.bottomMargin=dp(16);body.addView(card,cardParams);
        ImageView qr=new ImageView(this);qr.setContentDescription(t("verificationQrImage"));qr.setBackgroundColor(Color.WHITE);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);int edge=Math.max(dp(128),Math.min(dp(260),width-dp(80)));card.addView(qr,new LinearLayout.LayoutParams(edge,edge));
        verificationWork(()->{Bitmap bitmap=verificationBitmap(expected);ui.post(()->{if(verificationDialog==dialog&&dialog.isShowing())qr.setImageBitmap(bitmap);else bitmap.recycle();});});
        TextView code=label(expected.code.display(),17,ink);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setGravity(Gravity.CENTER);code.setPadding(0,dp(20),0,dp(20));card.addView(code,new LinearLayout.LayoutParams(-1,-2));
        verificationMark=button(t("verificationMark"),true);verificationMark.setOnClickListener(v->new AdaptiveDialogBuilder().setTitle(t("verifyIdentity")).setMessage(t("verificationConfirm")).setPositiveButton(t("verificationMark"),(d,w)->saveVerification(expected,null)).setNegativeButton(t("cancel"),null).show());card.addView(verificationMark,new LinearLayout.LayoutParams(-1,-2));
        verificationScan=verificationAction("verificationScan",R.drawable.outline_qr_code_scanner_24,()->{pendingVerification=expected;verificationBusy=true;updateVerificationDialog();startActivityForResult(new Intent(this,QrScanActivity.class),955);});body.addView(verificationScan,new LinearLayout.LayoutParams(-1,-2));
        verificationImage=verificationAction("verificationImage",R.drawable.outline_photo_24,()->new AdaptiveDialogBuilder().setTitle(t("verificationImage")).setMessage(t("verificationExplain")).setPositiveButton(t("gotIt"),(d,w)->{pendingVerification=expected;verificationBusy=true;updateVerificationDialog();Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,956);}).setNegativeButton(t("cancel"),null).show());body.addView(verificationImage,new LinearLayout.LayoutParams(-1,-2));
        Button copy=iconButton(R.drawable.outline_content_copy_24,t("verificationCopy"),ink);copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(t("verificationTitle"),expected.code.display()));toast(t("verificationCopyDone"));});toolbar.addView(copy,new LinearLayout.LayoutParams(dp(44),dp(48)));
        verificationExport=iconButton(R.drawable.outline_file_download_24,t("verificationSaveQr"),ink);verificationExport.setOnClickListener(v->{pendingVerification=expected;verificationBusy=true;updateVerificationDialog();startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"wozai-safety-code.png"),957);});toolbar.addView(verificationExport,new LinearLayout.LayoutParams(dp(44),dp(48)));
        verificationNotice=label("",14,muted);body.addView(verificationNotice);body.addView(label(t("verificationExplain"),14,muted));body.addView(label(t("verificationLocalOnly"),13,muted));
        dialog.setContentView(scroll);dialog.setOnDismissListener(d->{qr.setImageDrawable(null);if(verificationDialog==dialog){verificationDialog=null;verificationSnapshot=null;verificationConnection=null;verificationIdentity=null;verificationNotice=null;verificationMark=null;verificationScan=null;verificationImage=null;verificationExport=null;pendingVerification=null;pendingVerificationCode=null;verificationBusy=false;}});dialog.show();adaptiveDialogs.put(dialog,true);fitAdaptiveDialog(dialog,true);dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));updateVerificationDialog();
    }
    private void updateVerificationDialog(){
        if(verificationDialog==null||verificationSnapshot==null||verificationMark==null)return;boolean current=controller!=null&&controller.verificationCurrent(verificationSnapshot),verified=current&&controller.identityVerified(verificationSnapshot.peerId);
        verificationConnection.setText(t(current?"encryptionActive":"verificationConnectionChanged"));verificationIdentity.setText(t(verified?"identityVerified":"identityUnverified"));verificationMark.setEnabled(current&&!verificationBusy&&!verified);verificationScan.setEnabled(current&&!verificationBusy);verificationImage.setEnabled(current&&!verificationBusy);if(verificationExport!=null)verificationExport.setEnabled(!verificationBusy);
    }
    private void saveVerification(IdentityVerification expected,String scanned){
        if(isDestroyed())return;
        if(verificationDialog==null||verificationSnapshot==null||!expected.sameIdentity(verificationSnapshot)||controller==null||!controller.verificationCurrent(expected)){toast(t("verificationConnectionChanged"));updateVerificationDialog();return;}
        if(scanned!=null&&!expected.code.matchesQr(scanned)){verificationNotice.setText(t("verificationMismatch"));return;}
        android.app.Dialog expectedPage=verificationDialog;verificationBusy=true;updateVerificationDialog();controller.verifyIdentity(expected,scanned,ok->{if(isDestroyed()||verificationDialog!=expectedPage||!expected.sameIdentity(verificationSnapshot))return;verificationBusy=false;if(verificationNotice!=null)verificationNotice.setText(t(ok?"verificationDone":"verificationConnectionChanged"));updateVerificationDialog();});
    }
    private void verificationImageResult(android.app.Dialog expectedPage,IdentityVerification expected,String scanned){
        if(isDestroyed()||expectedPage==null||verificationDialog!=expectedPage||!expected.sameIdentity(verificationSnapshot))return;
        verificationBusy=false;
        if(scanned==null){if(verificationNotice!=null)verificationNotice.setText(t("verificationReadFailed"));updateVerificationDialog();}
        else{pendingVerification=expected;pendingVerificationCode=scanned;resumeVerificationResult();}
    }
    private void resumeVerificationResult(){
        if(controller==null||pendingVerificationCode==null)return;IdentityVerification expected=pendingVerification;String scanned=pendingVerificationCode;pendingVerification=null;pendingVerificationCode=null;verificationBusy=false;
        if(expected==null){toast(t("verificationConnectionChanged"));return;}saveVerification(expected,scanned);
    }
    private void clearConversation() {
        if (controller == null || controller.selectedId == null) return; String selected = controller.selectedId;
        new AdaptiveDialogBuilder().setTitle(t("clearAndroidTitle", safe(controller.selectedName))).setMessage(t("clearHistoryWarning"))
            .setPositiveButton(t("clearHistoryAction"), (d, w) -> { if (controller != null && Objects.equals(selected, controller.selectedId)) { deferredScrollPeer = null; historyPositions.remove(selected); controller.clearConversation(); } })
            .setNegativeButton(t("cancel"), null).show();
    }
    private void deviceInfo(String id, String name) {
        if (controller == null || id == null) return; boolean trusted = controller.isTrusted(id);
        AlertDialog.Builder builder = new AdaptiveDialogBuilder().setTitle(safe(name)).setMessage(t(trusted ? "deviceInfoTrusted" : "deviceInfoUntrusted", id)).setNegativeButton(t("close"), null);
        LinearLayout identity=vertical();identity.setGravity(Gravity.CENTER_HORIZONTAL);identity.setPadding(dp(24),dp(12),dp(24),dp(12));identity.addView(avatar(id,name,88),new LinearLayout.LayoutParams(dp(88),dp(88)));builder.setView(identity);
        if (trusted) {
            builder.setPositiveButton(t("revoke"), (d, w) -> confirmRevoke(id, name, false));
            if (Objects.equals(id, controller.connectedPeerId)) builder.setNeutralButton(t("revokeAndDisconnect"), (d, w) -> confirmRevoke(id, name, true));
        }
        builder.show();
    }
    private void confirmRevoke(String id, String name, boolean disconnect) {
        new AdaptiveDialogBuilder().setTitle(t("revokeAndroidTitle", safe(name))).setMessage(t("revokeAndroidBody"))
            .setPositiveButton(disconnect ? t("revokeAndDisconnect") : t("revoke"), (d, w) -> { if (controller != null) { controller.revokeTrust(id); if (disconnect && Objects.equals(id, controller.connectedPeerId)) controller.disconnect(); } })
            .setNegativeButton(t("back"), null).show();
    }
    private void chooseLanguage() {
        List<String> selections = new ArrayList<>(), labels = new ArrayList<>();
        selections.add(LanguageRegistry.SYSTEM); labels.add(t("systemLanguage"));
        for (LanguageRegistry.Language language : LanguageRegistry.languages()) { selections.add(language.tag); labels.add(language.nativeName); }
        int selected = selections.indexOf(AppLanguage.selection(this));
        new AdaptiveDialogBuilder().setTitle(t("language")).setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
            String selection = selections.get(which); dialog.dismiss();
            if (selection.equals(AppLanguage.selection(this))) return;
            saveDraftAndPosition(); AppLanguage.select(this, selection);
            if (service != null) service.refreshLanguage();
            if (Build.VERSION.SDK_INT < 33) recreate();
            else renderSettings();
        }).setNegativeButton(t("cancel"), null).show();
    }
    private void chooseTheme() {
        List<String> choices = List.of(AppAppearance.SYSTEM, AppAppearance.LIGHT, AppAppearance.DARK);
        new AdaptiveDialogBuilder().setTitle(t("theme"))
            .setSingleChoiceItems(new String[]{t("systemTheme"), t("lightTheme"), t("darkTheme")},
                choices.indexOf(AppAppearance.selection(this)), (dialog, which) -> {
                    dialog.dismiss(); selectTheme(choices.get(which));
                })
            .setNegativeButton(t("cancel"), null).show();
    }
    private void selectTheme(String choice) {
        if (choice.equals(AppAppearance.selection(this))) return;
        saveDraftAndPosition(); AppAppearance.select(this, choice); recreate();
    }
    private String peerName(Peer peer) { return peer.name == null || peer.name.trim().isEmpty() ? t("unnamedDevice") : peer.name; }
    private void editNickname() {
        if (controller == null) return; EditText name = new EditText(this); name.setText(controller.nickname); name.setSingleLine(true); name.setSelectAllOnFocus(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        LinearLayout box = vertical(); box.setPadding(dp(24), dp(8), dp(24), 0); box.addView(name);
        new AdaptiveDialogBuilder().setTitle(t("yourNickname")).setView(box).setPositiveButton(t("save"), (d, w) -> { if (controller != null) controller.setNickname(name.getText().toString()); }).setNegativeButton(t("cancel"), null).show();
    }
    private void showHelp() {
        new AdaptiveDialogBuilder().setTitle(t("help")).setMessage(t("androidHelpBody"))
            .setPositiveButton(t("gotIt"), null).show();
    }
    private LinearLayout settingsRow(String title, TextView detailView, Runnable action) {
        LinearLayout row = vertical(); row.setMinimumHeight(dp(64)); row.setPadding(dp(2), dp(12), dp(2), dp(12)); TextView heading = label(title, 16, ink); row.addView(heading); if (detailView != null) row.addView(detailView);
        row.setFocusable(true); row.setClickable(true); row.setBackground(ripple(background, 12)); row.setOnClickListener(v -> action.run()); return row;
    }
    private AvatarView avatar(String id, String name, int size) {
        AvatarView avatar=new AvatarView(this);AndroidAvatars.get(this).bind(avatar,controller==null?null:controller.avatarRoot(id));return avatar;
    }
    private void animatePage(View target) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return; target.animate().cancel(); target.setAlpha(.75f); target.setTranslationY(dp(5)); target.animate().alpha(1f).translationY(0).setDuration(170).start();
    }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); root.requestFocus(); }
    private void appSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private final class AdaptiveDialogBuilder extends AlertDialog.Builder {
        AdaptiveDialogBuilder(){super(MainActivity.this);}
        public AlertDialog create(){AlertDialog dialog=super.create();dialog.setOnShowListener(ignored->{adaptiveDialogs.put(dialog,false);fitAdaptiveDialog(dialog,false);});return dialog;}
        public AlertDialog show(){AlertDialog dialog=create();dialog.show();return dialog;}
    }
    private void fitOpenDialogs(){for(var entry:new ArrayList<>(adaptiveDialogs.entrySet()))if(entry.getKey()!=null&&entry.getKey().isShowing())fitAdaptiveDialog(entry.getKey(),entry.getValue());}
    private void fitAdaptiveDialog(android.app.Dialog dialog,boolean full){
        Window window=dialog.getWindow();if(window==null||root==null||root.getWidth()==0)return;
        android.graphics.Rect safe=root.dialogBounds();int margin=full?0:dp(12),width=Math.max(1,Math.min(full?safe.width():dp(560),safe.width()-2*margin)),maxHeight=Math.max(1,safe.height()-2*margin);
        View decor=window.getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,full?View.MeasureSpec.EXACTLY:View.MeasureSpec.AT_MOST));
        WindowManager.LayoutParams params=window.getAttributes();params.gravity=Gravity.CENTER;params.x=safe.centerX()-root.getWidth()/2;params.y=safe.centerY()-root.getHeight()/2;params.width=width;params.height=full?maxHeight:Math.min(maxHeight,decor.getMeasuredHeight());window.setAttributes(params);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(String value) { return Color.parseColor(value); }
    private String safe(String value) { return value == null ? "" : value; }
    private String modeName(int value) { return t(ChatController.modeKey(value)); }
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

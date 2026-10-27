package dev.ghost.nearbyim;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.service.notification.StatusBarNotification;
import android.widget.EditText;
import android.widget.ScrollView;
import dev.ghost.nearbyim.core.DeviceIdentity;
import dev.ghost.nearbyim.core.AttachmentInfo;
import dev.ghost.nearbyim.core.AttachmentRecord;
import dev.ghost.nearbyim.core.AttachmentTransfer;
import dev.ghost.nearbyim.core.AttachmentTransferV2;
import dev.ghost.nearbyim.core.TransferPacket;
import dev.ghost.nearbyim.core.TransferProgress;
import dev.ghost.nearbyim.core.FileAttachmentSource;
import dev.ghost.nearbyim.core.Frame;
import dev.ghost.nearbyim.core.FramedSession;
import dev.ghost.nearbyim.core.StreamConnection;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.Peer;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free device regression: adb shell am instrument -w dev.ghost.nearbyim.test/dev.ghost.nearbyim.LocalizationInstrumentation. */
public final class LocalizationInstrumentation extends Instrumentation {
    private final BlockingQueue<MainActivity> resumed = new LinkedBlockingQueue<>();
    private Application application;
    private String originalLanguage;
    private MainActivity activity;
    private FramedSession remote;
    private volatile AttachmentTransferV2 remoteTransfers;
    private volatile CountDownLatch resumePauseBlocked,resumePauseRelease;
    private final BlockingQueue<AttachmentRecord> remoteFileStates=new LinkedBlockingQueue<>();
    private ChatController liveController;
    private final AtomicReference<MainActivity> latestActivity = new AtomicReference<>();
    private int checks;
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity activity, Bundle saved) { if (activity instanceof MainActivity) latestActivity.set((MainActivity) activity); }
        public void onActivityStarted(Activity activity) {}
        public void onActivityResumed(Activity activity) { if (activity instanceof MainActivity) resumed.add((MainActivity) activity); }
        public void onActivityPaused(Activity activity) {}
        public void onActivityStopped(Activity activity) {}
        public void onActivitySaveInstanceState(Activity activity, Bundle saved) {}
        public void onActivityDestroyed(Activity activity) {}
    };
    public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    public void onStart() {
        Bundle results = new Bundle(); int code = Activity.RESULT_OK;
        try {
            application = (Application) getTargetContext().getApplicationContext();
            originalLanguage = onMain(() -> AppLanguage.selection(application));
            onMain(() -> { application.registerActivityLifecycleCallbacks(lifecycle); AppLanguage.select(application, "en"); return null; });
            testNativeFormatting();
            checks+=AndroidAttachmentChecks.run(getTargetContext());
            checks+=AndroidAttachmentSourceChecks.run(getTargetContext());
            checks+=AndroidAttachmentExperienceChecks.run(getTargetContext());
            checks+=AndroidTextInputChecks.run(this);
            checks+=AndroidGalleryExportChecks.run(this);
            checks+=AndroidAttachmentLocationChecks.run(getTargetContext());
            checks+=AndroidReceiveDirectoryChecks.run(getTargetContext());
            checks+=AndroidAttachmentV2Checks.run(getTargetContext());
            checks+=dev.ghost.nearbyim.noise.NoiseLibraryChecks.run();
            checks+=dev.ghost.nearbyim.noise.NoiseChannelChecks.run();
            checks+=AndroidNoiseIdentityChecks.run(getTargetContext());
            checks+=AndroidWifiDirectSessionChecks.run(this);
            checks+=AndroidVerificationChecks.run(getTargetContext());
            checks+=dev.ghost.nearbyim.transport.AndroidLanNetworkChecks.run();
            testScannerFocusControls();
            checks+=AndroidWifiDirectUiChecks.run(this);
            testRecreationWithLiveSession();
            results.putString("stream", "NearbyIM Android localization: " + checks + " checks passed\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED; StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            results.putString("stream", trace.toString());
        } finally {
            if (remote != null) remote.close(UiText.of("connectionEnded"));
            if(remoteTransfers!=null)try{remoteTransfers.shutdown().get(10,TimeUnit.SECONDS);}catch(Exception failure){code=Activity.RESULT_CANCELED;results.putString("stream",results.getString("stream","")+"Remote file cleanup failed: "+failure.getClass().getSimpleName()+"\n");}
            try {
                onMain(() -> {
                    if (liveController != null) liveController.stopAll();
                    MainActivity current = latestActivity.get();
                    if (current != null) current.finish();
                    if (application != null) {
                        application.unregisterActivityLifecycleCallbacks(lifecycle);
                        if (originalLanguage != null) AppLanguage.select(application, originalLanguage);
                    }
                    return null;
                });
            } catch (Throwable cleanup) { results.putString("cleanup", cleanup.toString()); }
        }
        finish(code, results);
    }
    private void testNativeFormatting() throws Exception {
        Context context = getTargetContext();
        check("附近的朋友".equals(AndroidText.initialNickname(context, true)), "An existing identity keeps the legacy default nickname");
        check("Nearby friend".equals(AndroidText.initialNickname(context, false)), "New identities use and persist the current-language default");
        check("1 device".equals(AndroidText.get(context, "deviceCount", 1)), "English singular ICU plural");
        check("2 devices".equals(AndroidText.get(context, "deviceCount", 2)), "English plural ICU plural");
        check("Connected · Bluetooth".equals(AndroidText.get(context, UiText.of("connectedVia", UiText.of("bluetooth")))), "Nested UiText resolves at display time");
        String name = "O'Brien {0} \\ archive";
        check(AndroidText.get(context, "requestNamedTitle", name).equals(name + " wants to chat"), "Nicknames preserve apostrophes, braces and backslashes");
        check(AndroidText.get(context, "myNickname", name).equals("I'm " + name), "ICU literal apostrophe survives Android resource compilation");
        check(AndroidText.get(context, "androidHelpBody").contains("\n\n"), "Android resources preserve paragraph breaks");
        check(AndroidText.get(context, "unknownRuntimeMessage").equals(AndroidText.get(context, "error")), "Unknown runtime keys fall back to a translated generic error");
        check(AndroidText.messageState(context, ChatStore.PENDING).equals("Pending"), "Stored status code resolves to English");
        Context localized = AppLanguage.wrap(context);
        check(AppLanguage.wrap(localized) == localized, "An already localized context is reused");
        java.util.TimeZone originalZone = java.util.TimeZone.getDefault();
        try {
            java.util.Date date = new java.util.Date(1_700_000_000_000L);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            String before = AndroidText.get(localized, "messageTime", date);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+09:00"));
            String after = AndroidText.get(localized, "messageTime", date);
            check(!before.equals(after), "Cached native formatting follows time zone changes");
            String pattern = localized.getString(dev.ghost.nearbyim.i18n.I18nResources.id("messageTime"));
            check(after.equals(new android.icu.text.MessageFormat(pattern, java.util.Locale.ENGLISH).format(new Object[]{date})), "Cached native time equals a fresh ICU formatter");
        } finally { java.util.TimeZone.setDefault(originalZone); }
        java.util.concurrent.ExecutorService formatting = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<String>> values = new java.util.ArrayList<>();
            for (int i = 0; i < 40; ++i) { final int count = i % 2 + 1; values.add(formatting.submit(() -> AndroidText.get(localized, "deviceCount", count))); }
            for (int i = 0; i < values.size(); ++i) check(values.get(i).get().equals(i % 2 == 0 ? "1 device" : "2 devices"), "Cached ICU formatting remains isolated across threads");
        } finally { formatting.shutdownNow(); }
        check(!AndroidText.date(context, 1700000000000L, "MMMdjm").isEmpty(), "Native locale date formatter");
        for (String[] expected : new String[][]{{"zh-Hant", "傳送", "2 台裝置"},
                {"ja", "送信", "2 台のデバイス"}, {"ko", "보내기", "기기 2대"}}) {
            onMain(() -> { AppLanguage.select(application, expected[0]); return null; });
            android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
            java.util.Locale locale = java.util.Locale.forLanguageTag(expected[0]);
            config.setLocale(locale);
            Context translated = context.createConfigurationContext(config);
            check(AndroidText.get(translated, "send").equals(expected[1]), "New locale loads its native resources: " + expected[0]);
            check(AndroidText.get(translated, "deviceCount", 2).equals(expected[2]), "New locale formats native ICU counts: " + expected[0]);
            check(AndroidText.get(translated, "requestDetails", name, "fingerprint").contains(name), "New locale preserves literal names: " + expected[0]);
            check(AndroidText.get(translated, "androidHelpBody").contains("\n\n"), "New locale keeps help paragraphs: " + expected[0]);
            check(!AndroidText.date(translated, 1700000000000L, "MMMdjm").isEmpty(), "New locale formats native dates: " + expected[0]);
        }
        onMain(() -> { AppLanguage.select(application, "en"); return null; });
    }
    private void testScannerFocusControls()throws Exception {
        try(android.os.ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand("pm grant "+getTargetContext().getPackageName()+" android.permission.CAMERA");InputStream input=new java.io.FileInputStream(descriptor.getFileDescriptor())){byte[] buffer=new byte[128];while(input.read(buffer)!=-1){}}
        Activity scan=startActivitySync(new Intent().setClassName(getTargetContext(),"dev.ghost.nearbyim.QrScanActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{waitForIdleSync();onMain(()->{
            android.view.View root=scan.getWindow().getDecorView();android.widget.Button refocus=root.findViewWithTag("qrRefocus");android.widget.TextView hint=root.findViewWithTag("qrFocusHint");android.view.View preview=root.findViewWithTag("qrPreview");
            check(refocus!=null&&refocus.isShown()&&refocus.getText().toString().equals(AndroidText.get(scan,"verificationRefocus")),"Scanner exposes a visible translated manual Refocus button");
            check(hint!=null&&hint.isShown()&&hint.getText().toString().equals(AndroidText.get(scan,"verificationFocusHint")),"Scanner explains tap-to-focus");
            check(preview!=null&&preview.isClickable()&&preview.hasOnClickListeners(),"Preview has an accessible real manual focus action");
            if(refocus.isEnabled())check(refocus.performClick(),"Native Refocus button dispatches its focus request");
            check(!scan.isFinishing(),"Optional focus request leaves scanner alive");return null;
        });}finally{onMain(()->{scan.finish();return null;});waitForIdleSync();}
    }
    private void testRecreationWithLiveSession() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> field(activity, "controller") != null), "Activity binds its service");
        ChatController controller = onMain(() -> field(activity, "controller"));
        liveController = controller;
        ChatService service = onMain(() -> field(activity, "service"));
        testEmptyNearbyRestoration(controller);
        testEmptySettingsRestoration(controller);
        testEmptyHistoryRestoration(controller);
        onMain(() -> { invoke(activity, "startTransport", new Class<?>[]{int.class}, Peer.LAN); return null; });
        await(() -> onMain(() -> controller.lanListening), "Real LAN reception starts");
        String peerId = UUID.randomUUID().toString(), name = "O'Brien {draft}";
        String draft = "Draft keeps braces {0}, quotes ' and 中文"+String.join("",java.util.Collections.nCopies(2001,"中👩🏽‍💻e\u0301"));
        onMain(() -> {
            controller.selectConversation(new ChatStore.Conversation(peerId, name, 0));
            setField(activity, "detail", true); setField(activity, "page", 0);
            invoke(activity, "render", new Class<?>[0]);
            EditText composer = field(activity, "composer"); composer.setText(draft); composer.setSelection(5, 12);
            return null;
        });
        BlockingQueue<String> receipts = new LinkedBlockingQueue<>();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<FramedSession> reference = new AtomicReference<>();
        AtomicReference<UiText> remoteClosed = new AtomicReference<>();
        DeviceIdentity remoteRoot=DeviceIdentity.generate();
        try (ServerSocket socketServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket remoteSocket = new Socket(InetAddress.getLoopbackAddress(), socketServer.getLocalPort());
            Socket localSocket = socketServer.accept();
            byte[] remoteKey=new byte[32];new java.security.SecureRandom().nextBytes(remoteKey);
            remote = FramedSession.secure(connection(remoteSocket), peerId, name, remoteRoot,remoteKey,true, new FramedSession.Listener() {
                public void onHello(Frame frame) {
                    try {FramedSession wire=reference.get();remoteTransfers=new AttachmentTransferV2(new File(getTargetContext().getCacheDir(),"native-remote-attachments-"+peerId).toPath(),remoteRoot.fingerprint(),DeviceIdentity.fingerprint(wire.remotePublicKey()),wire.connectionGeneration(),false,new AttachmentTransferV2.Wire(){
                        public boolean send(TransferPacket packet){return wire.sendTransfer(packet);}public void abort(){wire.close(UiText.of("attachmentFailed"));}
                    },record->remoteFileStates.add(record));wire.approve();}catch(Exception failure){throw new IllegalStateException("Remote encrypted fixture failed",failure);}
                }
                public void onTransfer(TransferPacket packet){try{
                    CountDownLatch blocked=resumePauseBlocked,release=resumePauseRelease;
                    if(packet.kind==TransferPacket.Kind.DATA&&packet.offset>=2L*1024*1024&&blocked!=null&&blocked.getCount()>0){blocked.countDown();if(!release.await(20,TimeUnit.SECONDS))throw new IOException("Synthetic pause barrier timed out");}
                    remoteTransfers.receive(packet,true);
                }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}}
                public void onReady() { ready.countDown(); }
                public void onText(Frame frame) { reference.get().acknowledge(frame.id); }
                public void onAck(String id) { receipts.add(id); }
                public void onClosed(UiText reason) { remoteClosed.compareAndSet(null,reason); }
            });
            reference.set(remote); remote.start();
            onMain(() -> { controller.onConnection(Peer.LAN, connection(localSocket), true); return null; });
        }
        try {
            await(() -> onMain(() -> controller.approvalId != null && field(activity, "approvalDialog") != null), "Signed incoming session awaits native consent");
        } catch (AssertionError failure) {
            String state=onMain(()->"controllerAttached="+(field(activity,"controller")==controller)+", serviceController="+(service.controller==controller)+", bound="+field(activity,"bound")+", finishing="+activity.isFinishing()+", destroyed="+activity.isDestroyed()+", windowFocus="+activity.hasWindowFocus()+", observer="+(field(controller,"observer")!=null)+", identity="+(controller.localAvatarRoot()!=null)+", lanRunning="+controller.lanRunning+", lanListening="+controller.lanListening+", connecting="+controller.connecting+", connected="+controller.connected+", hasSession="+controller.hasSession()+", mode="+controller.sessionMode+", approval="+(controller.approvalId!=null)+", dialog="+(field(activity,"approvalDialog")!=null)+", status="+controller.status.key+", error="+controller.error.key);
            throw new AssertionError(failure.getMessage()+": "+state+", remoteReady="+remote.isReady()+", remoteIdentity="+(remote.remotePublicKey()!=null)+", remoteClosed="+(remoteClosed.get()==null?"none":remoteClosed.get().key),failure);
        }
        String approval = onMain(() -> controller.approvalId);
        Object pending = queuedAction(peerId, name);
        // CLOSE_STAGE remains queued while the current signed session awaits approval.
        onMain(() -> { setField(activity, "pendingAction", pending); setField(activity, "pendingStage", 4); return null; });
        Bundle saved = onMain(() -> { Bundle state = new Bundle(); callActivityOnSaveInstanceState(activity, state); return state; });
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(saved); parcel.setDataPosition(0);
            Bundle restored = parcel.readBundle(MainActivity.class.getClassLoader());
            Object restoredPending = restored.getSerializable("pendingAction");
            Peer serializedPeer = field(restoredPending, "peer");
            check(peerId.equals(field(restoredPending, "peerId")) && name.equals(serializedPeer.name), "Queued action survives real Bundle parcel serialization");
        } finally { parcel.recycle(); }
        switchLanguage("zh-Hans");
        verifyState(controller, service, peerId, draft, 5, 12);
        check(onMain(() -> approval.equals(controller.approvalId) && field(activity, "approvalDialog") != null), "Pending approval is re-shown without rejecting the session");
        Object restoredAction = onMain(() -> field(activity, "pendingAction"));
        check(restoredAction != null && peerId.equals(field(restoredAction, "peerId")), "Pending connection action survives Bundle serialization");
        check(onMain(() -> (int) field(activity, "pendingStage") == 4), "Pending action stage survives recreation");
        check(AndroidText.get(activity, "pending").equals("待确认"), "Stable pending code renders in Chinese");
        check(onMain(() -> ((android.widget.TextView) field(activity, "pageTitle")).getText().toString().equals(AndroidText.get(activity, "app"))), "Recreated UI uses Chinese resources");
        screenshot("zh-Hans-pending-approval");
        onMain(() -> { setField(activity, "pendingAction", null); setField(activity, "pendingStage", 0); controller.approve(approval, true); return null; });
        check(ready.await(10, TimeUnit.SECONDS), "Live peer receives consent after locale recreation");
        await(() -> onMain(() -> controller.connected), "Controller remains connected");
        check(onMain(() -> controller.error.key.isEmpty()), "Chat consent does not report a false storage failure");
        testIdentityVerification(controller,peerId,remoteRoot.publicKey());
        testFileTransfers(controller,peerId);
        String readingMessageId=null;
        for (int i = 0; i < 35; i++) {
            String id = UUID.randomUUID().toString();
            if(i==12)readingMessageId=id;
            check(remote.send(new Frame(Frame.TEXT, id, "Message " + i + "\nSaved before locale recreation", System.currentTimeMillis())), "Peer sends timeline text " + i);
            check(id.equals(receipts.poll(10, TimeUnit.SECONDS)), "Android saves and receipts timeline text " + i);
        }
        await(() -> onMain(() -> controller.messages.size() >= 35), "Saved timeline loads");
        testLiveAttachmentFeedback(controller,peerId);
        testBatchRefreshResponsiveness(controller,peerId);
        testBatchPhotoRetention(controller,peerId);
        String readingTag="message:"+readingMessageId+":false";
        // Wait for deferred timeline restoration as well as the Activity and row layout.
        // Synthetic path fixtures and avatar Activity transitions run after this assertion.
        try{await(()->onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);
            ScrollView view=field(activity,"messageScroll");
            return field(activity,"controller")==controller&&activity.hasWindowFocus()&&row!=null&&row.getHeight()>0
                &&field(activity,"renderedMessages")==controller.messages&&!(boolean)field(activity,"rebuildingMessages")
                &&field(activity,"pendingTimelinePosition")==null&&field(activity,"restoredTimelinePosition")==null
                &&!rows.isLayoutRequested()&&!view.isLayoutRequested();
        }),"Original chat has finished layout and deferred reading-position restoration");}
        catch(AssertionError failure){throw new AssertionError("Reading fixture not ready; "+onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);
            return "controller="+(field(activity,"controller")==controller)+",focused="+activity.hasWindowFocus()+",tag="+readingTag+",row="+(row==null?"missing":row.getHeight())+",children="+rows.getChildCount()+",selected="+controller.selectedId+",records="+controller.messages.size();
        }),failure);}
        int readingOffset=onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);
            check(row!=null&&row.getHeight()>0,"Reading message is laid out before recreation");
            ScrollView view=field(activity,"messageScroll");
            int inset=Math.min(row.getHeight()-1,Math.max(1,Math.round(12*activity.getResources().getDisplayMetrics().density)));
            view.scrollTo(0,row.getTop()+inset);
            check(view.getScrollY()==row.getTop()+inset,"Timeline fixture reads inside the chosen message");
            check(rows.getHeight()-view.getHeight()-view.getScrollY()>48*activity.getResources().getDisplayMetrics().density,"Reading fixture is away from the auto-follow bottom");
            return view.getScrollY()-row.getTop();
        });
        String readingBefore=onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);ScrollView view=field(activity,"messageScroll");
            return "scroll="+view.getScrollY()+",rowTop="+row.getTop()+",rowHeight="+row.getHeight()+",density="+activity.getResources().getDisplayMetrics().density;
        });
        switchLanguage("en");
        verifyState(controller, service, peerId, draft, 5, 12);
        // Locale reflow can move preceding rows; preserve the message and its dp offset.
        try{await(()->onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);
            ScrollView view=field(activity,"messageScroll");int tolerance=(int)Math.ceil(activity.getResources().getDisplayMetrics().density);
            return row!=null&&view.getScrollY()>=row.getTop()&&view.getScrollY()<row.getBottom()
                &&Math.abs(view.getScrollY()-row.getTop()-readingOffset)<=tolerance;
        }),"Timeline reading message and offset survive locale recreation");}
        catch(AssertionError failure){throw new AssertionError("Reading position tag="+readingTag+",offset="+readingOffset+",before={"+readingBefore+"}; after: "+onMain(()->{
            android.view.ViewGroup rows=field(activity,"bubbles");android.view.View row=rows.findViewWithTag(readingTag);ScrollView view=field(activity,"messageScroll");
            return "scroll="+view.getScrollY()+",rowTop="+(row==null?-1:row.getTop())+",rowHeight="+(row==null?-1:row.getHeight())+",density="+activity.getResources().getDisplayMetrics().density;
        }),failure);}
        check(onMain(() -> controller.connected && peerId.equals(controller.connectedPeerId)), "Active signed session survives language change");
        NotificationManager notifications = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            await(() -> notificationTitleMatches(notifications, "NearbyIM is running"), "Actually posted notification changes to English");
        } catch (AssertionError failure) {
            Notification actual=posted(notifications);
            System.out.println("Notification diagnostic: enabled="+notifications.areNotificationsEnabled()
                    +", title="+(actual==null?"<missing>":actual.extras.getString(Notification.EXTRA_TITLE))
                    +", language="+onMain(()->AppLanguage.selection(application))
                    +", foreground="+onMain(()->field(service,"foreground"))
                    +", needed="+onMain(()->controller.needsForeground()));
            throw failure;
        }
        Notification notification = posted(notifications);
        check(notification.extras.getString(Notification.EXTRA_TITLE).equals("NearbyIM is running"), "Foreground notification refreshes its language");
        check(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().equals("Connected · LAN"), "Notification formats nested status in current language");
        check(notifications.getNotificationChannel("nearby-connection").getName().toString().equals("Chat connections"), "Existing notification channel is renamed on language change");
        // These suites replace the timeline or leave this Activity; keep them outside the locale anchor fixture.
        checks+=AndroidAvatarChecks.run(this,activity);
        checks+=AndroidTransferPathChecks.run(this,activity);
        checks+=AndroidWifiDirectUiChecks.run(this,activity);
        screenshot("en-live-chat");
        check(onMain(() -> controller.send("Message after language change")), "Live connection still sends after recreation");
        await(() -> onMain(() -> controller.messages.stream().anyMatch(message -> message.outgoing && message.text.equals("Message after language change") && ChatStore.DELIVERED.equals(message.state))), "Receipt still updates a language-independent delivery code");
        onMain(() -> { activity.moveTaskToBack(true); return null; });
        await(() -> onMain(() -> field(activity, "controller") == null), "Activity unbinds while the foreground connection stays active");
        onMain(() -> { AppLanguage.select(application, "zh-Hans"); return null; });
        await(() -> notificationMatches(notifications, "zh-Hans", "zh-CN"), "Already posted notification changes to Chinese while activity is backgrounded");
        onMain(() -> { AppLanguage.select(application, "en"); return null; });
        await(() -> notificationMatches(notifications, "en", "en-US"), "Already posted notification changes back to English while backgrounded");
        check(onMain(() -> controller.connected && controller.lanListening && (boolean) field(service, "foreground")), "Background language changes preserve the same live foreground connection");
    }
    private static Notification posted(NotificationManager manager) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) if (notification.getId() == 1) return notification.getNotification();
        return null;
    }
    private static boolean notificationTitleMatches(NotificationManager manager, String title) {
        Notification notification = posted(manager);
        return notification != null && title.equals(notification.extras.getString(Notification.EXTRA_TITLE));
    }
    private boolean notificationMatches(NotificationManager manager, String language, String localeTag) {
        android.content.res.Configuration configuration = new android.content.res.Configuration(application.getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag(localeTag));
        Context context = application.createConfigurationContext(configuration);
        String title = context.getString(dev.ghost.nearbyim.i18n.I18nResources.id("notificationTitle"));
        return language.equals(AppLanguage.selection(application)) && notificationTitleMatches(manager, title);
    }
    private void screenshot(String name) throws IOException {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IOException("Could not capture native screenshot: " + name);
        File directory = new File(getTargetContext().getExternalFilesDir(null), "i18n");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create screenshot directory");
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Could not save screenshot");
            }
        } finally { bitmap.recycle(); }
    }
    private void testEmptyNearbyRestoration(ChatController controller) throws Exception {
        int previousMode=onMain(()->field(activity,"mode"));
        onMain(() -> {
            controller.peers.clear(); setField(activity,"mode",Peer.LAN); setField(activity, "page", 1);
            setField(activity, "restoredNearbyY", 160); setField(activity, "peerSignature", null);
            invoke(activity, "render", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredNearbyY") == -1), "Attached empty nearby list consumes restored offset");
        onMain(() -> { ((ScrollView) field(activity, "nearbyScroll")).scrollTo(0, 0); controller.lanSearching = true; invoke(activity, "renderNearby", new Class<?>[0]); return null; });
        waitForIdleSync();
        check(onMain(() -> ((ScrollView) field(activity, "nearbyScroll")).getScrollY() == 0), "Search state change does not reapply stale nearby offset");
        onMain(() -> { controller.lanSearching = false; setField(activity,"mode",previousMode); setField(activity, "page", 0); invoke(activity, "render", new Class<?>[0]); return null; });
    }
    private void testEmptySettingsRestoration(ChatController controller) throws Exception {
        onMain(() -> {
            setField(activity, "controller", null); setField(activity, "page", 2);
            setField(activity, "restoredSettingsY", 160); setField(activity, "renderedTrusted", null);
            invoke(activity, "renderSettings", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredSettingsY") == 160), "Settings retains restored offset until controller attaches");
        onMain(() -> {
            setField(activity, "controller", controller); controller.trustedDevices = new ArrayList<>();
            invoke(activity, "render", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredSettingsY") == -1), "Attached empty trusted list consumes restored settings offset");
        onMain(() -> {
            ((ScrollView) field(activity, "settingsScroll")).scrollTo(0, 0);
            controller.trustedDevices = new ArrayList<>();
            invoke(activity, "renderSettings", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> ((ScrollView) field(activity, "settingsScroll")).getScrollY() == 0), "Empty trusted list refresh preserves user's settings scroll position");
        onMain(() -> { setField(activity, "page", 0); invoke(activity, "render", new Class<?>[0]); return null; });
    }
    private void testEmptyHistoryRestoration(ChatController controller) throws Exception {
        onMain(() -> {
            setField(activity, "controller", null); setField(activity, "page", 0);
            setField(activity, "restoredHistoryY", 160); setField(activity, "renderedConversations", null);
            invoke(activity, "renderHistory", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredHistoryY") == 160), "History retains restored offset until controller attaches");
        onMain(() -> {
            setField(activity, "controller", controller); controller.conversations = new ArrayList<>();
            invoke(activity, "render", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredHistoryY") == -1), "Attached empty history consumes restored offset");
        onMain(() -> {
            ((ScrollView) field(activity, "historyScroll")).scrollTo(0, 0);
            controller.conversations = new ArrayList<>();
            invoke(activity, "renderHistory", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> ((ScrollView) field(activity, "historyScroll")).getScrollY() == 0), "Empty history refresh preserves user's scroll position");
    }
    private void verifyState(ChatController controller, ChatService service, String peerId, String draft, int start, int end) throws Exception {
        check(onMain(() -> field(activity, "controller") == controller && field(activity, "service") == service), "The service and controller survive activity recreation");
        check(onMain(() -> controller.lanListening && (boolean) field(service, "foreground")), "Reception stays in the same foreground service");
        check(onMain(() -> peerId.equals(controller.selectedId) && (boolean) field(activity, "detail")), "Selected peer and chat page survive recreation");
        check(onMain(() -> ((EditText) field(activity, "composer")).getText().toString().equals(draft)), "Draft survives recreation unchanged");
        check(onMain(() -> ((EditText) field(activity, "composer")).getSelectionStart() == start && ((EditText) field(activity, "composer")).getSelectionEnd() == end), "Draft selection survives recreation");
    }
    private void switchLanguage(String language) throws Exception {
        MainActivity previous = activity; resumed.clear();
        onMain(() -> { AppLanguage.select(application, language); if (Build.VERSION.SDK_INT < 33) previous.recreate(); return null; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        MainActivity next = null;
        while (System.nanoTime() < deadline) {
            MainActivity candidate = resumed.poll(1, TimeUnit.SECONDS);
            if (candidate != null && candidate != previous) { next = candidate; break; }
        }
        check(next != null, "Locale selection recreates the activity"); activity = next;
        await(() -> onMain(() -> field(activity, "controller") != null), "Recreated activity rebinds existing service");
        waitForIdleSync();
    }
    private static Object queuedAction(String id, String name) throws Exception {
        Class<?> action = Class.forName("dev.ghost.nearbyim.MainActivity$UiAction");
        Constructor<?> constructor = action.getDeclaredConstructor(int.class, int.class, Peer.class, String.class, String.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(6, Peer.LAN, new Peer(Peer.LAN, "test", name, "192.168.1.2:1234", null, 1234, null, id), null, id, true);
    }
    private FileAttachmentSource testFileSource(byte[] bytes)throws IOException {
        java.nio.file.Path path=java.nio.file.Files.createTempFile(getTargetContext().getCacheDir().toPath(),"native-selected-",".bin");java.nio.file.Files.write(path,bytes);return new FileAttachmentSource(path);
    }
    private AttachmentRecord remoteFileState(String state)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){AttachmentRecord record=remoteFileStates.poll(100,TimeUnit.MILLISECONDS);if(record!=null&&record.state.equals(state))return record;}
        throw new AssertionError("No remote file state "+state);
    }
    private void testIdentityVerification(ChatController controller,String peer,String remoteRoot)throws Exception {
        dev.ghost.nearbyim.core.IdentityVerification initial=onMain(()->controller.verification(peer));
        check(initial!=null&&!initial.verified,"Actual encrypted Android consent does not auto-verify identity");
        dev.ghost.nearbyim.core.SafetyCode other=new dev.ghost.nearbyim.core.SafetyCode(remoteRoot,remote.remotePublicKey());
        check(initial.code.hex().equals(other.hex()),"Actual Android and peer root identities produce the same full safety code");
        int cameraPermission=getTargetContext().checkSelfPermission(android.Manifest.permission.CAMERA);
        onMain(()->{invoke(activity,"openVerification",new Class<?>[]{});return null;});
        android.app.Dialog dialog=onMain(()->field(activity,"verificationDialog"));
        check(dialog!=null&&onMain(dialog::isShowing),"Native Android identity verification page opens");
        check(cameraPermission==getTargetContext().checkSelfPermission(android.Manifest.permission.CAMERA),"Viewing a safety code does not request camera permission");
        await(()->onMain(()->{
            java.util.ArrayList<android.view.View> views=new java.util.ArrayList<>();collectVerificationViews(dialog.getWindow().getDecorView(),views);
            for(android.view.View view:views)if(view instanceof android.widget.ImageView image&&image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable bitmap){
                Bitmap qr=bitmap.getBitmap();int w=qr.getWidth(),h=qr.getHeight();int[] pixels=new int[w*h];qr.getPixels(pixels,0,w,0,0,w,h);
                if(initial.code.qrPayload().equals(dev.ghost.nearbyim.verification.QrCodes.decode(pixels,w,h)))return true;
            }return false;
        }),"Native page displays a decodable identity QR");
        screenshot("zh-Hans-identity-unverified");
        onMain(()->{invoke(activity,"saveVerification",new Class<?>[]{dev.ghost.nearbyim.core.IdentityVerification.class,String.class},initial,"WOZAI-ID1:"+"0".repeat(64));return null;});
        check(onMain(()->!controller.identityVerified(peer)),"Wrong scanned code does not mark Android identity verified");
        check(onMain(()->((android.widget.TextView)field(activity,"verificationNotice")).getText().toString().equals(AndroidText.get(activity,"verificationMismatch"))),"Wrong QR has visible native mismatch feedback");
        onMain(()->{dialog.dismiss();invoke(activity,"openVerification",new Class<?>[]{});invoke(activity,"verificationImageResult",new Class<?>[]{android.app.Dialog.class,dev.ghost.nearbyim.core.IdentityVerification.class,String.class},dialog,initial,other.qrPayload());return null;});
        check(onMain(()->!controller.identityVerified(peer)),"An old page image result cannot verify a newly reopened page with the same identity");
        onMain(()->{invoke(activity,"saveVerification",new Class<?>[]{dev.ghost.nearbyim.core.IdentityVerification.class,String.class},initial,other.qrPayload());return null;});
        await(()->onMain(()->controller.identityVerified(peer)),"Matching opposite endpoint code marks the exact Android roots verified");
        check(onMain(()->!((android.widget.Button)field(activity,"verificationMark")).isEnabled()),"Verified identity disables repeated manual marking");
        screenshot("zh-Hans-identity-verified");
        onMain(()->{((android.app.Dialog)field(activity,"verificationDialog")).dismiss();return null;});
        check(onMain(()->field(activity,"verificationDialog")==null&&field(activity,"verificationMark")==null&&field(activity,"verificationSnapshot")==null),"Closing Android verification releases its page and identity snapshot");
    }
    private static void collectVerificationViews(android.view.View view,java.util.List<android.view.View> result){result.add(view);if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)collectVerificationViews(group.getChildAt(i),result);}
    private void testFileTransfers(ChatController controller,String peer)throws Exception {
        check(onMain(()->controller.endToEndEncrypted(peer)),"Ready Android production session reports authenticated end-to-end encryption");
        check(onMain(()->((android.widget.TextView)field(activity,"chatStatus")).getText().toString().contains(AndroidText.get(activity,"encryptedVia",AndroidText.get(activity,"lan")))),"Chat header visibly identifies end-to-end encryption");
        check(onMain(()->((android.widget.Button)field(activity,"fileButton")).isEnabled()&&((android.widget.Button)field(activity,"photoButton")).isEnabled()),"Native file and photo selectors are enabled for the ready peer");
        check(onMain(()->((android.view.View)field(activity,"attachmentTray")).getVisibility()==android.view.View.GONE),"Composer attachment choices start collapsed");
        onMain(()->{((android.view.View)field(activity,"attachmentToggle")).performClick();return null;});
        check(onMain(()->((android.view.View)field(activity,"photoButton")).isShown()&&((android.view.View)field(activity,"fileButton")).isShown()),"Paperclip reveals file and photo choices");
        onMain(()->{invoke(activity,"handleBack",new Class<?>[0]);return null;});
        check(onMain(()->(boolean)field(activity,"detail")&&((android.view.View)field(activity,"attachmentTray")).getVisibility()==android.view.View.GONE),"Back closes attachment choices before leaving chat");
        check(onMain(()->{android.widget.Button button=field(activity,"sendButton");return button.getText().length()==0&&button.getWidth()==button.getHeight()&&button.getCompoundDrawables()[0]!=null;}),"Composer uses a circular Material send icon");
        Bitmap photo=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);int[] pixels=new int[80000];java.util.Random random=new java.util.Random(73);for(int i=0;i<pixels.length;i++){int y=i/400;pixels[i]=0xff000000|((40+y/2+random.nextInt(8))<<16)|((90+y/2+random.nextInt(8))<<8)|(180-y/3+random.nextInt(8));}photo.setPixels(pixels,0,400,0,0,400,200);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();photo.compress(Bitmap.CompressFormat.PNG,100,bytes);photo.recycle();byte[] data=bytes.toByteArray();
        String id=remoteTransfers.offer(testFileSource(data),"native-photo.png","image/png").get(10,TimeUnit.SECONDS);
        remoteFileState("delivered");
        await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(id)&&m.attachment!=null&&m.attachment.state.equals("received"))),"Android persists received photo");
        check(onMain(()->controller.connected&&controller.error.isEmpty()),"Approved Android chat receives photos without another confirmation");
        AttachmentRecord received=onMain(()->controller.messages.stream().filter(m->m.id.equals(id)).findFirst().get().attachment);
        java.nio.file.Path file=controller.attachmentPath(peer,received.info).get(10,TimeUnit.SECONDS);
        check(java.util.Arrays.equals(data,java.nio.file.Files.readAllBytes(file)),"Android preserves original photo bytes across encrypted chunks");
        await(()->onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");android.widget.ImageView image=bubbles.findViewWithTag("photo:"+id);return image!=null&&image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable;}),"Received photo renders inside its chat bubble");
        check(onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");return bubbles.findViewWithTag("photo:"+id).performLongClick();}),"Photo press opens attachment actions on the image itself");
        check(onMain(()->{android.widget.PopupMenu menu=field(activity,"attachmentActions");boolean found=menu.getMenu().getItem(1).getTitle().toString().equals(AndroidText.get(activity,"attachmentSaveAs"));menu.dismiss();return found;}),"Photo long-press offers Save as");
        screenshot("zh-Hans-photo-chat");testPhotoViewer(id);
        android.net.Uri uri=new android.net.Uri.Builder().scheme("content").authority(getTargetContext().getPackageName()+".attachments").appendPath(peer).appendPath(file.getFileName().toString()).appendQueryParameter("name",received.info.name).appendQueryParameter("mime",received.info.mime).build();
        onMain(()->{
            setField(activity,"pendingAttachmentPeer",peer);setField(activity,"pendingAttachmentToken",controller.attachmentSessionToken());setField(activity,"controller",null);
            activity.onActivityResult(22,Activity.RESULT_OK,new Intent().setData(uri));
            return null;
        });
        check(onMain(()->peer.equals(field(activity,"pendingAttachmentPeer"))),"Picker result waits for asynchronous service rebinding");
        onMain(()->{setField(activity,"controller",controller);invoke(activity,"resumeAttachmentSelection",new Class<?>[0]);return null;});remoteFileState("offered");AttachmentRecord offer=remoteFileState("received");
        await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(offer.info.id)&&m.outgoing&&m.attachment!=null&&m.attachment.state.equals("delivered"))),"Android sends content URI and records the save receipt");
        check(offer.info.hash.equals(received.info.hash),"Content URI source retains the original digest");
        java.nio.file.Path sent=controller.attachmentPath(peer,new AttachmentRecord(offer.info,true,"delivered",offer.info.size)).get(10,TimeUnit.SECONDS);
        check(java.util.Arrays.equals(data,java.nio.file.Files.readAllBytes(sent)),"Android keeps sent photo bytes for chat preview");
        await(()->onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");android.widget.ImageView image=bubbles.findViewWithTag("photo:"+offer.info.id);return image!=null&&image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable;}),"Sent photo also renders in the chat timeline");
        screenshot("zh-Hans-photo-both-directions");
        byte[] document="A document received automatically".getBytes(java.nio.charset.StandardCharsets.UTF_8);String documentId=remoteTransfers.offer(testFileSource(document),"note.txt","text/plain").get(10,TimeUnit.SECONDS);remoteFileState("delivered");
        await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(documentId)&&m.attachment!=null&&m.attachment.state.equals("received"))),"Ordinary files also arrive without another confirmation");
        check(onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles"),bubble=bubbles.findViewWithTag("attachment:"+documentId);return bubble!=null&&bubble.getChildAt(0).performLongClick();}),"File card press opens actions on its clickable content");
        check(onMain(()->{android.widget.PopupMenu menu=field(activity,"attachmentActions");android.view.Menu actions=menu.getMenu();boolean found=actions.size()==3&&actions.findItem(1)!=null&&actions.findItem(2)!=null&&actions.findItem(7)!=null&&actions.findItem(1).getTitle().toString().equals(AndroidText.get(activity,"attachmentOpen"))&&actions.findItem(2).getTitle().toString().equals(AndroidText.get(activity,"attachmentSaveAs"))&&actions.findItem(7).getTitle().toString().equals(AndroidText.get(activity,"attachmentShowInFolder"));menu.dismiss();return found;}),"File long-press exposes Open, Save as and the actual saved-location action");
        screenshot("zh-Hans-photo-and-file-chat");
        testContentUriResume(controller,peer);
        testTransferDiagnostics(controller,peer);
        testAttachmentQueueRejection(controller,peer,received,uri);
        android.net.Uri another=uri.buildUpon().appendQueryParameter("selection","second").build();onMain(()->{controller.sendAttachments(peer,controller.attachmentSessionToken(),List.of(uri,another));return null;});
        AttachmentRecord firstBatch=remoteFileState("received"),secondBatch=remoteFileState("received");check(!firstBatch.info.id.equals(secondBatch.info.id)&&firstBatch.info.hash.equals(received.info.hash)&&secondBatch.info.hash.equals(received.info.hash),"Android batches two URI originals through encrypted transfer tasks");
        await(()->onMain(()->controller.messages.stream().filter(m->m.outgoing&&m.attachment!=null&&m.attachment.state.equals("delivered")&&(m.id.equals(firstBatch.info.id)||m.id.equals(secondBatch.info.id))).count()==2),"Android batch completes both saved receipts");
        testReopenedProviderBatch(controller,peer);
        testFailedAttachmentRetry(controller,peer);
        long token=onMain(()->controller.attachmentSessionToken());onMain(()->{controller.sendAttachment(peer,token-1,uri);return null;});check(onMain(()->controller.error.key.equals("notConnected")),"Stale picker result cannot cross session generations");
    }
    private void testReopenedProviderBatch(ChatController controller,String peer)throws Exception {
        android.net.Uri base=android.net.Uri.parse("content://dev.ghost.nearbyim.test.sources");getTargetContext().getContentResolver().call(base,"reset",null,null);
        List<android.net.Uri> sources=List.of(base.buildUpon().path("/reopened/first").build(),base.buildUpon().path("/file").build(),base.buildUpon().path("/reopened/third").build());
        Set<String> previous=onMain(()->controller.messages.stream().map(m->m.id).collect(java.util.stream.Collectors.toSet()));
        onMain(()->{controller.sendAttachments(peer,controller.attachmentSessionToken(),sources);return null;});
        await(()->onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null&&!m.attachment.active()).count()==3),"Provider batch reaches three terminal rows");
        List<String> states=onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null).map(m->m.attachment.state).collect(java.util.stream.Collectors.toList()));
        check(states.size()==3&&states.stream().allMatch("delivered"::equals),"Provider batch must deliver all three files; observed "+states);
        AttachmentRecord[] received=new AttachmentRecord[3];for(int i=0;i<received.length;i++)received[i]=remoteFileState("received");
        Set<String> ids=new java.util.HashSet<>();for(AttachmentRecord record:received){ids.add(record.info.id);java.nio.file.Path target=AttachmentTransfer.file(new File(getTargetContext().getCacheDir(),"native-remote-attachments-"+peer).toPath(),record.info);check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(target),new byte[]{0,1,2,3,4,5,6,7}),"Reopened provider batch preserves each original through Noise");}
        check(ids.size()==3,"Reopened provider batch has three distinct transfer tasks");
        await(()->onMain(()->controller.messages.stream().filter(m->m.outgoing&&ids.contains(m.id)&&m.attachment!=null&&m.attachment.state.equals("delivered")).count()==3),"Android batch delivers all selected provider views, not just the stable-inode file");
    }
    private android.widget.Button attachmentButton(android.view.View view,String description){
        if(view instanceof android.widget.Button button&&description.contentEquals(button.getContentDescription()==null?"":button.getContentDescription()))return button;
        if(view instanceof android.view.ViewGroup group)for(int n=0;n<group.getChildCount();n++){android.widget.Button found=attachmentButton(group.getChildAt(n),description);if(found!=null)return found;}return null;
    }
    private void testFailedAttachmentRetry(ChatController controller,String peer)throws Exception {
        android.net.Uri base=android.net.Uri.parse("content://dev.ghost.nearbyim.test.sources"),retry=base.buildUpon().path("/retry").build(),denied=base.buildUpon().path("/denied").build(),replacement=base.buildUpon().path("/file").build();
        getTargetContext().getContentResolver().call(base,"reset",null,null);getTargetContext().getContentResolver().call(base,"retry-deny",null,null);
        Set<String> previous=onMain(()->controller.messages.stream().map(m->m.id).collect(java.util.stream.Collectors.toSet()));
        try{
            onMain(()->{controller.sendAttachments(peer,controller.attachmentSessionToken(),List.of(retry,denied));return null;});
            await(()->onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null&&m.attachment.state.equals("failed")).count()==2),"Revoked provider batch retains both failed history rows");
            List<ChatStore.Message> failed=onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null).collect(java.util.stream.Collectors.toList()));
            String readableId=failed.get(0).id,deniedId=failed.get(1).id;
            check(onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");return attachmentButton(bubbles.findViewWithTag("attachment:"+readableId),AndroidText.get(activity,"attachmentRetry"))!=null;}),"Failed outgoing bubble exposes Retry instead of Resume");
            screenshot("zh-Hans-attachment-retry-failed");
            onMain(()->{controller.sendAttachments(peer,controller.attachmentSessionToken(),List.of(retry));return null;});
            await(()->onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null&&m.attachment.state.equals("failed")).count()==3),"Separate provider batch reaches a failed history row");
            String otherBatchId=onMain(()->controller.messages.stream().filter(m->m.outgoing&&!previous.contains(m.id)&&m.attachment!=null&&!m.id.equals(readableId)&&!m.id.equals(deniedId)).findFirst().get().id);
            getTargetContext().getContentResolver().call(base,"retry-allow",null,null);
            check(onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");return bubbles.findViewWithTag("attachment:"+readableId).performLongClick();}),"Failed file card opens its retry actions");
            screenshot("zh-Hans-attachment-retry-batch-menu");
            check(onMain(()->{android.widget.PopupMenu menu=field(activity,"attachmentActions");for(int i=0;i<menu.getMenu().size();i++){android.view.MenuItem item=menu.getMenu().getItem(i);if(item.getTitle().toString().equals(AndroidText.get(activity,"attachmentRetryBatch")))return menu.getMenu().performIdentifierAction(item.getItemId(),0);}return false;}),"Failed file popup dispatches this batch's failed-item retry");
            AttachmentRecord queued=remoteFileState("received");
            await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(queued.info.id)&&m.outgoing&&m.attachment!=null&&m.attachment.state.equals("delivered"))),"Readable failed item creates a distinct delivered retry task");
            check(!queued.info.id.equals(readableId)&&!queued.info.id.equals(deniedId)&&!queued.info.id.equals(otherBatchId),"Retry uses a new transfer ID");
            check(onMain(()->controller.messages.stream().filter(m->m.outgoing&&(m.id.equals(readableId)||m.id.equals(deniedId)||m.id.equals(otherBatchId))&&m.attachment.state.equals("failed")).count()==3),"Retry retains failed history and leaves the other batch untouched");
            String retriedId=onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");for(String candidate:List.of(readableId,deniedId)){android.widget.Button button=attachmentButton(bubbles.findViewWithTag("attachment:"+candidate),AndroidText.get(activity,"attachmentRetried"));if(button!=null&&!button.isEnabled())return candidate;}return null;});
            check(retriedId!=null,"Retry parent displays a disabled Retried status");String reselectId=retriedId.equals(readableId)?deniedId:readableId;
            screenshot("zh-Hans-attachment-retry-partial");
            check(onMain(()->LocalizationInstrumentation.<java.util.concurrent.CompletableFuture<String>>invoke(controller,"retryAttachment",new Class<?>[]{String.class,long.class,String.class},peer,controller.attachmentSessionToken(),retriedId)).get(10,TimeUnit.SECONDS).equals(queued.info.id),"Repeated retry reuses the admitted child instead of creating another task");
            onMain(()->{setField(activity,"pendingRetryPeer",peer);setField(activity,"pendingRetryId",reselectId);setField(activity,"pendingRetryToken",controller.attachmentSessionToken());setField(activity,"pendingRetrySession",invoke(controller,"attachmentSelectionSession",new Class<?>[]{String.class},peer));setField(activity,"controller",null);activity.onActivityResult(24,Activity.RESULT_OK,new Intent().setData(replacement));return null;});
            check(onMain(()->reselectId.equals(field(activity,"pendingRetryId"))&&replacement.equals(field(activity,"selectedRetryUri"))),"Retry replacement waits for asynchronous service rebinding with its failed parent");
            Bundle staged=new Bundle();onMain(()->{activity.onSaveInstanceState(staged);return null;});
            check(reselectId.equals(staged.getString("pendingRetryId"))&&replacement.toString().equals(staged.getString("selectedRetryUri"))&&staged.getString("pendingRetrySession")!=null,"Retry replacement, parent and authenticated session survive saved activity state");
            MainActivity beforeRotation=activity;resumed.clear();onMain(()->{setField(activity,"controller",controller);beforeRotation.recreate();return null;});
            long rotationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);MainActivity rotated=null;while(System.nanoTime()<rotationDeadline&&rotated==null){MainActivity candidate=resumed.poll(1,TimeUnit.SECONDS);if(candidate!=null&&candidate!=beforeRotation)rotated=candidate;}
            check(rotated!=null,"Retry picker recreation produces a restored activity");activity=rotated;await(()->onMain(()->field(activity,"controller")==controller),"Retry picker recreation rebinds the surviving transfer controller");AttachmentRecord rebound=remoteFileState("received");
            check(!rebound.info.id.equals(reselectId)&&java.util.Arrays.equals(java.nio.file.Files.readAllBytes(AttachmentTransfer.file(new File(getTargetContext().getCacheDir(),"native-remote-attachments-"+peer).toPath(),rebound.info)),new byte[]{0,1,2,3,4,5,6,7}),"Reselected retry preserves original bytes under a new child ID");
            onMain(()->{setField(activity,"pendingRetryPeer",peer);setField(activity,"pendingRetryId",otherBatchId);setField(activity,"pendingRetryToken",controller.attachmentSessionToken()-1);setField(activity,"pendingRetrySession",invoke(controller,"attachmentSelectionSession",new Class<?>[]{String.class},peer));activity.onActivityResult(24,Activity.RESULT_OK,new Intent().setData(replacement));return null;});
            check(onMain(()->controller.error.key.equals("notConnected")),"Stale retry picker cannot enter a new session generation");
            String currentSession=onMain(()->invoke(controller,"attachmentSelectionSession",new Class<?>[]{String.class},peer));
            rejectedRetry(onMain(()->retryReplacement(controller,peer,controller.attachmentSessionToken(),currentSession+"stale",otherBatchId,replacement)),"Changed identity/session snapshot cannot reuse a picker result");
            rejectedRetry(onMain(()->retryReplacement(controller,java.util.UUID.randomUUID().toString(),controller.attachmentSessionToken(),currentSession,otherBatchId,replacement)),"Retry replacement cannot cross peers");
            rejectedRetry(onMain(()->retryReplacement(controller,peer,controller.attachmentSessionToken(),currentSession,java.util.UUID.randomUUID().toString(),replacement)),"Unknown UUID retry cannot bypass stored failed-history validation");
            rejectedRetry(onMain(()->retryReplacement(controller,peer,controller.attachmentSessionToken(),currentSession,queued.info.id,replacement)),"Delivered outgoing history cannot be rebound as a failed retry");
            onMain(()->{controller.error=UiText.EMPTY;return null;});
        }finally{getTargetContext().getContentResolver().call(base,"retry-allow",null,null);}
    }
    private void rejectedRetry(java.util.concurrent.CompletableFuture<?> future,String message)throws Exception {
        try{future.get(10,TimeUnit.SECONDS);throw new AssertionError(message+": unexpectedly admitted");}catch(java.util.concurrent.ExecutionException expected){check(true,message);}
    }
    private java.util.concurrent.CompletableFuture<?> retryReplacement(ChatController controller,String peer,long token,String session,String id,android.net.Uri uri)throws Exception {return invoke(controller,"retryAttachmentWithSelection",new Class<?>[]{String.class,long.class,String.class,String.class,android.net.Uri.class},peer,token,session,id,uri);}
    private void testContentUriResume(ChatController controller,String peer)throws Exception {
        android.net.Uri uri=android.net.Uri.parse("content://dev.ghost.nearbyim.test.sources/file");getTargetContext().getContentResolver().call(uri,"large",null,null);
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream input=getTargetContext().getContentResolver().openInputStream(uri)){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        String expected=AttachmentInfo.hex(digest.digest()),generation=remote.connectionGeneration();
        resumePauseRelease=new CountDownLatch(1);resumePauseBlocked=new CountDownLatch(1);
        try {
            onMain(()->{controller.sendAttachment(peer,controller.attachmentSessionToken(),uri);return null;});AttachmentRecord offer=remoteFileState("offered");String id=offer.info.id;
            check(resumePauseBlocked.await(15,TimeUnit.SECONDS),"Real Android content URI reaches a nonempty pause prefix");
            onMain(()->{controller.attachmentAction(peer,id,true,"pause");return null;});
            await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(id)&&m.outgoing&&m.attachment!=null&&m.attachment.resumable())),"Android pause becomes actionable without reconnecting");
            resumePauseRelease.countDown();remoteFileState("paused");
            check(onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");android.widget.Button button=attachmentButton(bubbles.findViewWithTag("attachment:"+id),AndroidText.get(activity,"attachmentResume"));return button!=null&&button.performClick();}),"Native Resume button starts content URI recovery");
            AttachmentRecord completed=remoteFileState("received");
            await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(id)&&m.outgoing&&m.attachment!=null&&m.attachment.state.equals("delivered"))),"Android content URI resumes and saves the completion receipt");
            check(generation.equals(remote.connectionGeneration())&&expected.equals(completed.info.hash),"Android Resume retains Noise generation and full content digest");
        }finally{resumePauseRelease.countDown();resumePauseBlocked=null;resumePauseRelease=null;getTargetContext().getContentResolver().call(uri,"reset",null,null);}
    }
    private void testTransferDiagnostics(ChatController controller,String peer)throws Exception {
        onMain(()->{android.view.ViewGroup page=field(activity,"chatPage");android.widget.Button more=attachmentButton(page,AndroidText.get(activity,"more"));if(more==null||!more.performClick())throw new AssertionError("Chat overflow button unavailable");return null;});
        java.util.concurrent.atomic.AtomicReference<android.view.accessibility.AccessibilityNodeInfo> target=new java.util.concurrent.atomic.AtomicReference<>();
        await(()->{android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(AndroidText.get(activity,"transferDiagnostics"));if(nodes.isEmpty())return false;target.set(nodes.get(0));return true;},"Native chat menu offers copy transfer diagnostics");
        android.view.accessibility.AccessibilityNodeInfo choice=target.get();boolean clicked=false;
        for(android.view.accessibility.AccessibilityNodeInfo node=choice;node!=null&&!clicked;node=node.getParent())clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        check(clicked,"Native diagnostics menu dispatches copy action");waitForIdleSync();
        String report=onMain(()->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);android.content.ClipData data=clipboard.getPrimaryClip();return data==null?"":data.getItemAt(0).coerceToText(activity).toString();});
        check(report.contains("endpoint=android")&&report.contains("end_to_end_encrypted=true")&&report.contains("source_read")&&report.contains("socket_write"),"Native clipboard contains actual source and encrypted socket timing");
        check(!report.contains(peer)&&!report.contains("native-photo.png")&&!report.contains("content://")&&!report.contains(getTargetContext().getFilesDir().toString()),"Clipboard diagnostic excludes peer, filename, URI and private path");
    }
    private void testLiveAttachmentFeedback(ChatController controller,String peer)throws Exception {
        Class<?> feedbackType=Class.forName("dev.ghost.nearbyim.ChatController$Feedback");
        var constructor=feedbackType.getDeclaredConstructor(AttachmentRecord.class,TransferProgress.class);constructor.setAccessible(true);
        java.util.Map<String,Object> feedback=field(controller,"transferProgress");
        java.util.List<ChatStore.Message> history=onMain(()->controller.messages);
        AttachmentInfo info=AttachmentInfo.v2(java.util.UUID.randomUUID().toString(),"checking.bin","application/octet-stream",4L*1024*1024,null,System.currentTimeMillis());String key=peer+":"+info.id+":false";
        try {onMain(()->{
            android.view.View bubble=null;
            for(int percent:new int[]{25,75}) {
                AttachmentRecord record=new AttachmentRecord(info,false,"checking",1024*1024);
                Object value=constructor.newInstance(record,new TransferProgress(0,-1,percent*info.size/100,info.size));
                android.view.View previous=bubble;
                feedback.put(key,value);java.util.ArrayList<ChatStore.Message> messages=new java.util.ArrayList<>(history);messages.add(new ChatStore.Message(info.id,info.name,"received",false,info.time,record));controller.messages=messages;invoke(activity,"renderChat",new Class<?>[0]);
                android.view.ViewGroup bubbles=field(activity,"bubbles");bubble=bubbles.findViewWithTag("attachment:"+info.id);android.widget.ProgressBar bar=bubble.findViewWithTag("attachmentProgress");
                check(bar!=null&&!bar.isIndeterminate()&&bar.getProgress()==percent,"Android resume verification displays changing checked bytes");
                if(previous!=null)check(previous==bubble,"Live file progress rebuilds the saved timeline");
            }
            return null;
        });}finally{onMain(()->{feedback.remove(key);controller.messages=history;invoke(activity,"renderChat",new Class<?>[0]);return null;});}
    }
    private void testBatchRefreshResponsiveness(ChatController controller,String peer)throws Exception {
        java.util.List<String> failures=new ArrayList<>();
        java.util.List<ChatStore.Message> saved=onMain(()->controller.messages);
        onMain(()->{
            try {
                ArrayList<ChatStore.Message> timeline=new ArrayList<>();
                long time=System.currentTimeMillis();
                for(int i=0;i<80;i++)timeline.add(new ChatStore.Message(UUID.randomUUID().toString(),"Existing text "+i,ChatStore.DELIVERED,true,time+i));
                for(int i=0;i<20;i++){
                    AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"batch-"+i+".bin","application/octet-stream",16L*1024*1024,null,time+80+i);
                    timeline.add(new ChatStore.Message(info.id,info.name,ChatStore.PENDING,true,info.time,new AttachmentRecord(info,true,"queued",0)));
                }
                controller.messages=timeline;invoke(activity,"renderChat",new Class<?>[0]);
                android.view.ViewGroup bubbles=field(activity,"bubbles");
                int replaced=0;String[] states={"preparing","offered","transferring","paused","checking","transferring","awaitingReceipt","delivered"};
                for(String state:states){
                    android.view.View before=bubbles.findViewWithTag("message:"+timeline.get(0).id+":true");
                    ArrayList<ChatStore.Message> next=new ArrayList<>();
                    for(int i=0;i<timeline.size();i++){
                        ChatStore.Message message=timeline.get(i);AttachmentRecord record=message.attachment;
                        if(record!=null)record=AttachmentRecord.decode(record.encode()); // SQLite reload produces new value objects.
                        if(i==80){
                            AttachmentInfo info=record.info;
                            if(state.equals("delivered"))info=AttachmentInfo.v2(info.id,info.name,info.mime,info.size,"0000000000000000000000000000000000000000000000000000000000000000",info.time);
                            record=new AttachmentRecord(info,true,state,state.equals("delivered")?info.size:1024*1024);
                        }
                        next.add(new ChatStore.Message(message.id,message.text,i==80?(state.equals("delivered")?ChatStore.DELIVERED:record.active()?ChatStore.PENDING:ChatStore.UNKNOWN):message.state,message.outgoing,message.time,record));
                    }
                    controller.messages=next;invoke(activity,"renderChat",new Class<?>[0]);
                    if(before!=bubbles.findViewWithTag("message:"+timeline.get(0).id+":true"))replaced++;
                    android.view.View row=bubbles.findViewWithTag("message:"+next.get(80).id+":true");
                    check(row!=null&&((android.widget.TextView)row.findViewWithTag("messageMeta")).getText().toString().contains(AndroidText.get(activity,next.get(80).attachment.stateKey())),"Changed batch row shows its current state: "+state);
                    timeline=next;
                }
                if(replaced!=0)failures.add("unrelated saved rows rebuilt "+replaced+"/"+states.length+" times during one file's states");
            }finally{controller.messages=saved;invoke(activity,"renderChat",new Class<?>[0]);}
            return null;
        });
        java.util.concurrent.ExecutorService storage=field(controller,"storage");
        storage.submit(()->{}).get(10,TimeUnit.SECONDS);waitForIdleSync();
        onMain(()->{
            ChatStore.Message text=saved.stream().filter(m->m.attachment==null).findFirst().orElseThrow(()->new AssertionError("Missing saved text fixture"));
            ChatStore.Message photo=saved.stream().filter(m->m.attachment!=null&&!m.outgoing&&m.attachment.info.mime.startsWith("image/")&&m.attachment.state.equals("received")).findFirst().orElseThrow(()->new AssertionError("Missing saved photo fixture"));
            android.view.ViewGroup bubbles=field(activity,"bubbles");android.view.View row=bubbles.findViewWithTag("message:"+text.id+":"+text.outgoing);
            GalleryPublisher.Listener listener=field(activity,"galleryListener");listener.changed(peer,photo.id);
            if(row!=bubbles.findViewWithTag("message:"+text.id+":"+text.outgoing))failures.add("gallery result rebuilt unrelated saved text");
            return null;
        });
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger snapshots=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<java.util.List<ChatStore.Message>> last=new java.util.concurrent.atomic.AtomicReference<>(onMain(()->controller.messages));
        Runnable observer=onMain(()->field(controller,"observer"));
        storage.execute(()->{started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        check(started.await(10,TimeUnit.SECONDS),"Storage gate starts before durable-state notification burst");
        try {
            onMain(()->{controller.observe(()->{if(last.getAndSet(controller.messages)!=controller.messages)snapshots.incrementAndGet();if(observer!=null)observer.run();});snapshots.set(0);for(int i=0;i<100;i++)invoke(controller,"refresh",new Class<?>[0]);return null;});
            release.countDown();
            for(int i=0;i<4;i++){storage.submit(()->{}).get(10,TimeUnit.SECONDS);waitForIdleSync();}
            if(snapshots.get()>2)failures.add("100 simultaneous durable-state requests published "+snapshots.get()+" full history snapshots");
            ChatStore store=field(controller,"store");java.util.List<ChatStore.Message> expected=storage.submit(()->store.messages(peer)).get(10,TimeUnit.SECONDS);
            check(onMain(()->controller.messages.stream().map(m->m.id).collect(java.util.stream.Collectors.toList()).equals(expected.stream().map(m->m.id).collect(java.util.stream.Collectors.toList()))),"Coalesced history still publishes all latest saved records");
        }finally{release.countDown();onMain(()->{controller.observe(observer);return null;});}
        check(failures.isEmpty(),"Batch transfer UI responsiveness: "+String.join("; ",failures));
    }
    private void testBatchPhotoRetention(ChatController controller,String peer)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.GREEN);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,encoded);bitmap.recycle();byte[] bytes=encoded.toByteArray();List<String> ids=new ArrayList<>();
        for(int i=0;i<13;i++){
            String id=remoteTransfers.offer(testFileSource(bytes),"batch-preview-"+i+".png","image/png").get(10,TimeUnit.SECONDS);ids.add(id);
            AttachmentRecord delivered=remoteFileState("delivered");check(id.equals(delivered.info.id),"Encrypted photo batch gets its matching saved receipt");
            await(()->onMain(()->controller.messages.stream().anyMatch(m->m.id.equals(id)&&m.attachment!=null&&m.attachment.state.equals("received"))),"Batch photo appears in the saved native timeline");
        }
        await(()->onMain(()->{java.util.Set<?> loading=field(activity,"loadingThumbnails");java.util.concurrent.ThreadPoolExecutor worker=field(activity,"imageWorker");return loading.isEmpty()&&worker.getActiveCount()==0&&worker.getQueue().isEmpty();}),"Batch thumbnail work finishes without a repeated rebuild loop");
        onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");int retained=0;for(String id:ids){android.widget.ImageView image=bubbles.findViewWithTag("photo:"+id);if(image!=null&&image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)retained++;}java.util.Map<?,?> cache=field(activity,"thumbnails");check(retained>0&&retained<=12,"Photo rows retain the original bounded bitmap budget after batch eviction: "+retained);check(cache.size()<=12,"Thumbnail cache stays bounded after thirteen real encrypted photos");return null;});
    }
    private void rejectedAttachmentFuture(java.util.concurrent.CompletableFuture<?> future,String message)throws Exception {
        try{future.get(1,TimeUnit.SECONDS);throw new AssertionError(message+": unexpectedly succeeded");}
        catch(java.util.concurrent.ExecutionException error){check(error.getCause() instanceof java.util.concurrent.RejectedExecutionException,message);}
    }
    private void testAttachmentQueueRejection(ChatController controller,String peer,AttachmentRecord received,android.net.Uri uri)throws Exception {
        java.util.concurrent.ThreadPoolExecutor worker=field(controller,"fileSelection");
        await(()->worker.getActiveCount()==0&&worker.getQueue().isEmpty(),"Document worker is idle before saturation test");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            worker.execute(()->{started.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            check(started.await(5,TimeUnit.SECONDS),"Document worker is occupied while the UI submits paths");
            worker.execute(()->{});worker.execute(()->{});
            check(worker.getMaximumPoolSize()==1&&worker.getQueue().size()==2,"Document worker retains its bounded capacity");
            rejectedAttachmentFuture(onMain(()->controller.attachmentPath(peer,received.info)),"Info path returns a failed future on saturation");
            rejectedAttachmentFuture(onMain(()->controller.attachmentPath(peer,received)),"Record path returns a failed future on saturation");
            rejectedAttachmentFuture(onMain(()->controller.exportAttachment(peer,received.info,uri)),"Info export propagates rejection through its future");
            rejectedAttachmentFuture(onMain(()->controller.exportAttachment(peer,received,uri)),"Record export propagates rejection through its future");
            onMain(()->{invoke(activity,"openAttachment",new Class<?>[]{String.class,AttachmentRecord.class},peer,received);return null;});waitForIdleSync();
            check(onMain(()->!activity.isDestroyed()&&!activity.isFinishing()&&controller.connected),"Opening an attachment on saturation leaves chat alive");
        }finally{release.countDown();}
        await(()->worker.getActiveCount()==0&&worker.getQueue().isEmpty(),"Document worker drains after saturation");
        check(java.nio.file.Files.isRegularFile(controller.attachmentPath(peer,received.info).get(10,TimeUnit.SECONDS)),"Info path recovers after saturation");
        check(java.nio.file.Files.isRegularFile(controller.attachmentPath(peer,received).get(10,TimeUnit.SECONDS)),"Record path recovers after saturation");
    }
    private void testPhotoViewer(String id)throws Exception {
        ActivityMonitor monitor=addMonitor("dev.ghost.nearbyim.PhotoActivity",null,false);Activity viewer=null;
        try{
            onMain(()->{android.view.ViewGroup bubbles=field(activity,"bubbles");bubbles.findViewWithTag("photo:"+id).performClick();return null;});
            viewer=waitForMonitorWithTimeout(monitor,10000);check(viewer!=null,"Tapping a photo opens the internal viewer");Activity current=viewer;
            await(()->onMain(()->{android.widget.ImageView image=field(current,"image");return image.getWidth()>0&&image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable;}),"Internal viewer decodes its photo without another app");
            onMain(()->{Object image=field(current,"image");invoke(image,"zoom",new Class<?>[]{float.class},2f);return null;});
            check(onMain(()->(float)field(field(current,"image"),"factor")>1.9f),"Native viewer zoom controls enlarge the image");
            screenshot("zh-Hans-internal-photo-viewer");
            check(onMain(()->liveController.connected),"Photo viewer preserves the established chat session");
            testPhotoExportTitles(current);
        }finally{if(viewer!=null){Activity closing=viewer;onMain(()->{closing.finish();return null;});}removeMonitor(monitor);}
        await(()->onMain(()->field(activity,"controller")!=null),"Chat rebinds after the native viewer closes");
    }
    private void testPhotoExportTitles(Activity viewer)throws Exception {
        android.net.Uri source=onMain(()->field(viewer,"photo"));AtomicReference<Intent> requested=new AtomicReference<>();
        ActivityMonitor monitor=new ActivityMonitor(){
            public ActivityResult onStartActivity(Intent intent){
                if(!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()))return null;
                requested.set(new Intent(intent));return new ActivityResult(Activity.RESULT_CANCELED,null);
            }
        };
        addMonitor(monitor);
        String[] names={"dir/unsafe\\photo.png. ","CON.png","...","\u202eimage.png","日本語 图像.png",null};
        String[] expected={"dir_unsafe_photo.png","attachment","attachment","image.png","日本語 图像.png",source.getLastPathSegment()};
        try{
            for(int i=0;i<names.length;i++){
                android.net.Uri.Builder builder=source.buildUpon().clearQuery().appendQueryParameter("mime","image/png");
                if(names[i]!=null)builder.appendQueryParameter("name",names[i]);android.net.Uri candidate=builder.build();requested.set(null);
                onMain(()->{setField(viewer,"photo",candidate);invoke(viewer,"save",new Class<?>[0]);return null;});
                Intent intent=requested.get();check(intent!=null&&expected[i].equals(intent.getStringExtra(Intent.EXTRA_TITLE))&&"image/png".equals(intent.getType())&&intent.hasCategory(Intent.CATEGORY_OPENABLE),"Photo save intent sanitizes its title and keeps document type: "+names[i]);
            }
        }finally{removeMonitor(monitor);onMain(()->{setField(viewer,"photo",source);return null;});}
    }
    private static StreamConnection connection(Socket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "Instrumentation TCP"; }
            public void close() throws IOException { socket.close(); }
        };
    }
    private void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) { if (condition.call()) { checks++; return; } Thread.sleep(25); }
        throw new AssertionError(message);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new Exception("Main-thread operation failed", failure.get());
        return value.get();
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(target);
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return (T) method.invoke(target, arguments);
    }
}

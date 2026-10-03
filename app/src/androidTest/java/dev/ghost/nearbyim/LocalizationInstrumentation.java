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
import java.util.UUID;
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
            testRecreationWithLiveSession();
            results.putString("stream", "NearbyIM Android localization: " + checks + " checks passed\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED; StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            results.putString("stream", trace.toString());
        } finally {
            if (remote != null) remote.close(UiText.of("connectionEnded"));
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
    private void testRecreationWithLiveSession() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> field(activity, "controller") != null), "Activity binds its service");
        ChatController controller = onMain(() -> field(activity, "controller"));
        liveController = controller;
        ChatService service = onMain(() -> field(activity, "service"));
        testEmptyNearbyRestoration(controller);
        onMain(() -> { invoke(activity, "startTransport", new Class<?>[]{int.class}, Peer.LAN); return null; });
        await(() -> onMain(() -> controller.lanListening), "Real LAN reception starts");
        String peerId = UUID.randomUUID().toString(), name = "O'Brien {draft}";
        String draft = "Draft keeps braces {0}, quotes ' and 中文";
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
        try (ServerSocket socketServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket remoteSocket = new Socket(InetAddress.getLoopbackAddress(), socketServer.getLocalPort());
            Socket localSocket = socketServer.accept();
            remote = new FramedSession(connection(remoteSocket), peerId, name, DeviceIdentity.generate(), new FramedSession.Listener() {
                public void onHello(Frame frame) { reference.get().approve(); }
                public void onReady() { ready.countDown(); }
                public void onText(Frame frame) { reference.get().acknowledge(frame.id); }
                public void onAck(String id) { receipts.add(id); }
                public void onClosed(UiText reason) {}
            });
            reference.set(remote); remote.start();
            onMain(() -> { controller.onConnection(Peer.LAN, connection(localSocket), true); return null; });
        }
        await(() -> onMain(() -> controller.approvalId != null && field(activity, "approvalDialog") != null), "Signed incoming session awaits native consent");
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
        for (int i = 0; i < 35; i++) {
            String id = UUID.randomUUID().toString();
            check(remote.send(new Frame(Frame.TEXT, id, "Message " + i + "\nSaved before locale recreation", System.currentTimeMillis())), "Peer sends timeline text " + i);
            check(id.equals(receipts.poll(10, TimeUnit.SECONDS)), "Android saves and receipts timeline text " + i);
        }
        await(() -> onMain(() -> controller.messages.size() >= 35), "Saved timeline loads");
        Thread.sleep(150); waitForIdleSync();
        int scroll = onMain(() -> { ScrollView view = field(activity, "messageScroll"); view.scrollTo(0, 120); return view.getScrollY(); });
        check(scroll > 0, "Timeline is scrollable before recreation");
        switchLanguage("en");
        verifyState(controller, service, peerId, draft, 5, 12);
        await(() -> onMain(() -> ((ScrollView) field(activity, "messageScroll")).getScrollY() == scroll), "Timeline scroll survives locale recreation");
        check(onMain(() -> controller.connected && peerId.equals(controller.connectedPeerId)), "Active signed session survives language change");
        NotificationManager notifications = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        await(() -> notificationTitleMatches(notifications, "NearbyIM is running"), "Actually posted notification changes to English");
        Notification notification = posted(notifications);
        check(notification.extras.getString(Notification.EXTRA_TITLE).equals("NearbyIM is running"), "Foreground notification refreshes its language");
        check(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().equals("Connected · LAN"), "Notification formats nested status in current language");
        check(notifications.getNotificationChannel("nearby-connection").getName().toString().equals("Chat connections"), "Existing notification channel is renamed on language change");
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
        onMain(() -> {
            controller.peers.clear(); setField(activity, "page", 1);
            setField(activity, "restoredNearbyY", 160); setField(activity, "peerSignature", null);
            invoke(activity, "render", new Class<?>[0]); return null;
        });
        waitForIdleSync();
        check(onMain(() -> (int) field(activity, "restoredNearbyY") == -1), "Attached empty nearby list consumes restored offset");
        onMain(() -> { ((ScrollView) field(activity, "nearbyScroll")).scrollTo(0, 0); controller.lanSearching = true; invoke(activity, "renderNearby", new Class<?>[0]); return null; });
        waitForIdleSync();
        check(onMain(() -> ((ScrollView) field(activity, "nearbyScroll")).getScrollY() == 0), "Search state change does not reapply stale nearby offset");
        onMain(() -> { controller.lanSearching = false; setField(activity, "page", 0); invoke(activity, "render", new Class<?>[0]); return null; });
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

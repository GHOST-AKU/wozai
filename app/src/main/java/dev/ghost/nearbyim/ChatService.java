package dev.ghost.nearbyim;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.*;
import java.util.Objects;

public final class ChatService extends Service {
    public static final String START = "dev.ghost.nearbyim.START", STOP = "dev.ghost.nearbyim.STOP";
    private static final String PAUSE="dev.ghost.nearbyim.PAUSE_TRANSFERS",CANCEL="dev.ghost.nearbyim.CANCEL_TRANSFERS";
    private static final String CHANNEL = "nearby-connection";
    private final LocalBinder binder = new LocalBinder();
    public ChatController controller;
    private boolean foreground;
    private final Handler notificationHandler=new Handler(Looper.getMainLooper());
    private final Runnable notificationRefresh=this::syncNotification;
    private NotificationState lastNotificationState;
    private long lastPostedAt;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener languageChanged = (prefs, key) -> {
        if (AppLanguage.KEY.equals(key)) refreshLanguage();
    };
    public final class LocalBinder extends Binder { public ChatService service() { return ChatService.this; } }
    protected void attachBaseContext(Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    public void onCreate() {
        super.onCreate(); updateChannel();
        preferences = getSharedPreferences(AppLanguage.PREFERENCES, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(languageChanged);
        controller = new ChatController(this, this::syncNotification);
    }
    private void updateChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, AndroidText.get(this, "notificationChannel"), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(AndroidText.get(this, "notificationChannelDescription"));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    public void refreshLanguage() { updateChannel(); syncNotification(); }
    public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); refreshLanguage(); }
    public IBinder onBind(Intent intent) { return binder; }
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { controller.stopAll(); clearNotification(); stopSelf(); }
        else if(intent!=null&&(PAUSE.equals(intent.getAction())||CANCEL.equals(intent.getAction()))){if(PAUSE.equals(intent.getAction()))controller.pauseAttachments();else controller.cancelAttachments();syncNotification();}
        else {
            NotificationState state=notificationState();
            Notification notification = notification(state);
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, notification);
            foreground = true;
            lastNotificationState=state;lastPostedAt=SystemClock.elapsedRealtime();
            if (!controller.needsForeground()) { clearNotification(); stopSelf(); }
        }
        return START_NOT_STICKY;
    }
    private static final class NotificationState {
        final String title,text,stop,pause,cancel;
        final boolean active;
        NotificationState(String title,String text,String stop,String pause,String cancel,boolean active){this.title=title;this.text=text;this.stop=stop;this.pause=pause;this.cancel=cancel;this.active=active;}
        boolean same(NotificationState other){return other!=null&&active==other.active&&Objects.equals(title,other.title)&&Objects.equals(text,other.text)&&Objects.equals(stop,other.stop)&&Objects.equals(pause,other.pause)&&Objects.equals(cancel,other.cancel);}
    }
    private NotificationState notificationState() {
        String text=controller!=null&&controller.approvalName!=null?AndroidText.get(this,"notificationRequest",controller.approvalName)
                :controller!=null&&controller.connected?AndroidText.get(this,controller.status):AndroidText.get(this,"notificationReceiving");
        boolean active=controller!=null&&controller.hasActiveAttachments();
        return new NotificationState(AndroidText.get(this,"notificationTitle"),text,AndroidText.get(this,"stopAll"),
                active?AndroidText.get(this,"attachmentPause"):"",active?AndroidText.get(this,"cancel"):"",active);
    }
    private Notification notification(NotificationState state) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ChatService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(AppLanguage.wrap(this), CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle(state.title)
                .setContentText(state.text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if(state.active) {
            PendingIntent pause=PendingIntent.getService(this,2,new Intent(this,ChatService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent cancel=PendingIntent.getService(this,3,new Intent(this,ChatService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,state.pause,pause).build()).addAction(new Notification.Action.Builder(null,state.cancel,cancel).build());
        }
        return builder.addAction(new Notification.Action.Builder(null,state.stop,stop).build()).build();
    }
    private void syncNotification() {
        notificationHandler.removeCallbacks(notificationRefresh);
        if (controller == null || !foreground) return;
        if (!controller.needsForeground()) { clearNotification(); stopSelf(); return; }
        NotificationState state=notificationState();
        if(state.same(lastNotificationState))return;
        // Android silently sheds updates above its enqueue rate. Keep the latest
        // state while limiting updates to two per second, including locale changes.
        long remaining=500-(SystemClock.elapsedRealtime()-lastPostedAt);
        if(remaining>0){notificationHandler.postDelayed(notificationRefresh,remaining);return;}
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification(state));
        lastNotificationState=state;lastPostedAt=SystemClock.elapsedRealtime();
    }
    private void clearNotification() {
        notificationHandler.removeCallbacks(notificationRefresh);
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;lastNotificationState=null;
    }
    public void onTaskRemoved(Intent rootIntent) { controller.stopAll(); stopSelf(); }
    public void onDestroy() {
        notificationHandler.removeCallbacks(notificationRefresh);foreground=false;lastNotificationState=null;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(languageChanged);
        controller.destroy(); super.onDestroy();
    }
}

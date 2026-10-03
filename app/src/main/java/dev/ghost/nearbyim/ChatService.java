package dev.ghost.nearbyim;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.*;

public final class ChatService extends Service {
    public static final String START = "dev.ghost.nearbyim.START", STOP = "dev.ghost.nearbyim.STOP";
    private static final String CHANNEL = "nearby-connection";
    private final LocalBinder binder = new LocalBinder();
    public ChatController controller;
    private boolean foreground;
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
        if (intent != null && STOP.equals(intent.getAction())) { controller.stopAll(); stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
        else {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, notification);
            foreground = true;
            if (!controller.needsForeground()) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ChatService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = controller != null && controller.approvalName != null ? AndroidText.get(this, "notificationRequest", controller.approvalName)
                : controller != null && controller.connected ? AndroidText.get(this, controller.status) : AndroidText.get(this, "notificationReceiving");
        return new Notification.Builder(AppLanguage.wrap(this), CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle(AndroidText.get(this, "notificationTitle"))
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, AndroidText.get(this, "stopAll"), stop).build()).build();
    }
    private void syncNotification() {
        if (controller == null || !foreground) return;
        if (controller.needsForeground()) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification());
        else { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
    }
    public void onTaskRemoved(Intent rootIntent) { controller.stopAll(); stopSelf(); }
    public void onDestroy() {
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(languageChanged);
        controller.destroy(); super.onDestroy();
    }
}

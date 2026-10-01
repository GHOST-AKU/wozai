package dev.ghost.nearbyim;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class ChatService extends Service {
    public static final String START = "dev.ghost.nearbyim.START", STOP = "dev.ghost.nearbyim.STOP";
    private static final String CHANNEL = "nearby-connection";
    private final LocalBinder binder = new LocalBinder();
    public ChatController controller;
    private boolean foreground;
    public final class LocalBinder extends Binder { public ChatService service() { return ChatService.this; } }
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "聊天连接", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        controller = new ChatController(this, this::syncNotification);
    }
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
        String text = controller != null && controller.approvalName != null ? controller.approvalName + " 请求聊天 · 点此确认"
                : controller != null && controller.connected ? controller.status : "接收已开启 · 点此返回我在";
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("我在正在运行")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止所有连接", stop).build()).build();
    }
    private void syncNotification() {
        if (controller == null || !foreground) return;
        if (controller.needsForeground()) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification());
        else { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
    }
    public void onTaskRemoved(Intent rootIntent) { controller.stopAll(); stopSelf(); }
    public void onDestroy() { controller.destroy(); super.onDestroy(); }
}

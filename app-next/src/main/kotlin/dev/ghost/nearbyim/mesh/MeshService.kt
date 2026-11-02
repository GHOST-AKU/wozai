package dev.ghost.nearbyim.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.ghost.nearbyim.AppLanguage
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R

/** Explicit opt-in only: no binding, boot receiver, sticky restart, or automatic relay restoration. */
class MeshService : Service() {
    private var runtime: MeshRuntime? = null
    private var ownerToken = -1L
    private val main = Handler(Looper.getMainLooper())

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val token = intent.getLongExtra(TOKEN, -1)
                if (!MeshClient.isCurrent(token)) { if (runtime == null) stopSelf(startId); return START_NOT_STICKY }
                val mode = intent.getStringExtra(MODE) ?: ""
                val relay = intent.getBooleanExtra(RELAY, false)
                try { foreground() }
                catch (_: RuntimeException) {
                    MeshClient.publish(token, MeshClient.state.value.copy(starting = false, running = false,
                        relay = false, error = "mesh_service_unavailable"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                ownerToken = token
                val active = runtime ?: MeshRuntime(MeshAndroidPlatform(this), MeshClient.state.value,
                    {}, {}).also { runtime = it }
                // Reuse one actor so teardown completes before another core opens mesh-v1.
                active.start(mode, relay, { next -> MeshClient.publish(token, next) }, {
                        main.post {
                            if (ownerToken == token && MeshClient.isCurrent(token)) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    })
            }
            STOP -> {
                MeshClient.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            SEND, SET_RELAY -> {
                val token = intent.getLongExtra(TOKEN, -1)
                if (ownerToken == token && MeshClient.isCurrent(token)) {
                    if (intent.action == SEND) runtime?.send(intent.getStringExtra(TEXT) ?: "")
                    else runtime?.setRelay(intent.getBooleanExtra(RELAY, false))
                }
                if (runtime == null) stopSelf(startId)
            }
            else -> { if (runtime == null) stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.mesh_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 42, Intent(this, MeshService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(getString(R.string.mesh_notification_text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.mesh_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, notification)
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        MeshClient.serviceEnded(ownerToken)
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val START = "dev.ghost.nearbyim.mesh.START"
        internal const val STOP = "dev.ghost.nearbyim.mesh.STOP"
        internal const val SEND = "dev.ghost.nearbyim.mesh.SEND"
        internal const val SET_RELAY = "dev.ghost.nearbyim.mesh.RELAY"
        internal const val TOKEN = "mesh_token"
        internal const val MODE = "mesh_mode"
        internal const val RELAY = "mesh_relay"
        internal const val TEXT = "mesh_text"
        private const val CHANNEL = "nearby-mesh-public-v1"
        private const val NOTIFICATION = 41
    }
}

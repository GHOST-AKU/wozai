package dev.ghost.nearbyim.next

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.ghost.nearbyim.AndroidText
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R

/** Local reception alerts. Android controls the channel's sound, vibration and lock-screen visibility. */
internal object MessageNotifications {
    const val NEARBY = "nearby"
    const val PRIVATE_CHANNEL = "private-messages"
    const val NEARBY_CHANNEL = "nearby-messages"
    private const val ACTION = "dev.ghost.nearbyim.OPEN_MESSAGE_NOTIFICATION"
    private const val SCHEME = "wozai-message"
    private const val ID = 2
    private val peerId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private var viewing: Pair<Int, String>? = null

    fun visible(context: Context, owner: Int, destination: String?) {
        if (destination != null) {
            viewing = owner to destination
            context.getSystemService(NotificationManager::class.java).cancel(destination, ID)
        } else if (viewing?.first == owner) viewing = null
    }

    fun destination(intent: Intent): String? = intent.data?.takeIf {
        intent.action == ACTION && it.scheme == SCHEME
    }?.schemeSpecificPart?.takeIf { it == NEARBY || peerId.matches(it) }

    fun settings(context: Context, channel: String): Intent {
        registerChannels(context)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    }

    fun registerChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val privateChannel = NotificationChannel(PRIVATE_CHANNEL, context.getString(R.string.next_private_notifications), NotificationManager.IMPORTANCE_DEFAULT)
        privateChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        manager.createNotificationChannels(listOf(
            privateChannel,
            NotificationChannel(NEARBY_CHANNEL, context.getString(R.string.next_nearby_notifications), NotificationManager.IMPORTANCE_LOW),
        ))
    }

    fun post(context: Context, destination: String, name: String = "") {
        if (viewing?.second == destination) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val nearby = destination == NEARBY
        if (!nearby && !peerId.matches(destination)) return
        val channel = if (nearby) NEARBY_CHANNEL else PRIVATE_CHANNEL
        if (manager.getNotificationChannel(channel) == null) registerChannels(context)
        val open = Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.fromParts(SCHEME, destination, null))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
        val body = context.getString(if (nearby) R.string.next_nearby_notification_body else R.string.next_private_notification_body)
        val publicVersion = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_app)
            .setContentTitle(context.getString(R.string.next_app_name)).setContentText(body).build()
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_app)
            .setContentTitle(name.ifBlank { AndroidText.get(context, if (nearby) "nearby" else "chats") })
            .setContentText(body).setContentIntent(contentIntent).setPublicVersion(publicVersion)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(nearby).build()
        try { manager.notify(destination, ID, notification) }
        catch (_: SecurityException) { /* The user can revoke notification access concurrently. */ }
    }
}

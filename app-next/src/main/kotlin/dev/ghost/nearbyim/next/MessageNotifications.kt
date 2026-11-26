package dev.ghost.nearbyim.next

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.ghost.nearbyim.AndroidText
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import java.util.UUID

/** Android owns alert policy. Only bounded previews of recent alerts are kept in memory. */
internal object MessageNotifications {
    const val NEARBY = "nearby"
    const val PRIVATE_CHANNEL = "private-messages-v2"
    const val NEARBY_CHANNEL = "nearby-messages-v2"
    const val REPLY = "dev.ghost.nearbyim.REPLY_MESSAGE_NOTIFICATION"
    const val READ = "dev.ghost.nearbyim.READ_MESSAGE_NOTIFICATION"
    const val REPLY_TEXT = "reply"
    private const val OPEN = "dev.ghost.nearbyim.OPEN_MESSAGE_NOTIFICATION"
    private const val SCHEME = "wozai-message"
    private const val ID = 2
    private val peerId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private var viewing: Pair<Int, String>? = null

    private data class Line(val id: String, val author: String, val authorId: String, val body: String, val time: Long)
    internal data class Conversation(
        val destination: String,
        val token: String,
        val generation: String?,
        val meshToken: Long?,
        val incomingIds: List<String>,
    )
    private data class Posted(val token: String = UUID.randomUUID().toString(), val generation: String?, val meshToken: Long?,
                              val lines: ArrayDeque<Line> = ArrayDeque())
    private val previews = LinkedHashMap<String, Posted>()

    fun visible(context: Context, owner: Int, destination: String?) {
        if (destination != null) {
            viewing = owner to destination
            clear(context, destination)
        } else if (viewing?.first == owner) viewing = null
    }

    fun destination(intent: Intent): String? = intent.data?.takeIf {
        intent.action == OPEN && it.scheme == SCHEME
    }?.schemeSpecificPart?.takeIf { it == NEARBY || peerId.matches(it) }

    fun open(context: Context, target: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN).setData(Uri.fromParts(SCHEME, target, null))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun settings(context: Context, channel: String): Intent {
        registerChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId(manager, channel == NEARBY_CHANNEL))
    }

    fun registerChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            channel(PRIVATE_CHANNEL, context.getString(R.string.next_private_notifications)),
            channel(NEARBY_CHANNEL, context.getString(R.string.next_nearby_notifications)),
        ))
    }

    private fun channel(id: String, label: String) = NotificationChannel(id, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        enableVibration(true)
    }

    private fun channelId(manager: NotificationManager, nearby: Boolean): String {
        val old = manager.getNotificationChannel(if (nearby) "nearby-messages" else "private-messages")
        // Keep settings the owner explicitly changed on the previous channel.
        val previousDefault = if (nearby) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
        if (old != null && (old.importance != previousDefault || old.shouldVibrate() ||
                    Build.VERSION.SDK_INT >= 29 && old.hasUserSetImportance() ||
                    Build.VERSION.SDK_INT >= 30 && old.hasUserSetSound() ||
                    !nearby && old.sound != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))) return old.id
        return if (nearby) NEARBY_CHANNEL else PRIVATE_CHANNEL
    }

    fun post(context: Context, destination: String, name: String, body: String, id: String,
             generation: String? = null, meshToken: Long? = null, authorId: String = destination, alert: Boolean = true) {
        if (viewing?.second == destination || destination != NEARBY && !peerId.matches(destination)) return
        if (!allowed(context)) return
        val previous = previews.remove(destination)
        val item = previous?.takeIf { it.generation == generation && it.meshToken == meshToken }
            ?: Posted(generation = generation, meshToken = meshToken)
        if (item.lines.any { it.id == id }) {
            previews[destination] = item
            return
        }
        item.lines.addLast(Line(id, name.take(96), authorId, body.take(512), System.currentTimeMillis()))
        if (item.lines.size > 6) item.lines.removeFirst()
        previews[destination] = item
        while (previews.size > 32) previews.remove(previews.keys.first())
        if (alert) render(context, destination, item)
    }

    fun conversation(intent: Intent): Conversation? {
        val uri = intent.data ?: return null
        if (uri.scheme != SCHEME || uri.authority != "actions" || uri.pathSegments.size != 2) return null
        val (target, token) = uri.pathSegments
        val current = previews[target]?.takeIf { it.token == token } ?: return null
        return Conversation(target, token, current.generation, current.meshToken, current.lines.map { it.id })
    }

    fun completeAction(context: Context, conversation: Conversation, unsent: String? = null) {
        val target = conversation.destination
        val current = previews[target]?.takeIf { it.token == conversation.token } ?: return
        if (unsent == null) clear(context, target) else if (allowed(context)) render(context, target, current, unsent)
    }

    private fun clear(context: Context, target: String) {
        previews.remove(target)
        context.getSystemService(NotificationManager::class.java).cancel(target, ID)
    }

    private fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun render(context: Context, target: String, item: Posted, unsent: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val nearby = target == NEARBY
        val channel = channelId(manager, nearby)
        if (manager.getNotificationChannel(channel) == null) registerChannels(context)
        val last = item.lines.last()
        val appName = context.getString(R.string.next_app_name)
        @Suppress("DEPRECATION")
        val style = if (Build.VERSION.SDK_INT >= 28)
            Notification.MessagingStyle(Person.Builder().setName(appName).setKey("local").build())
        else Notification.MessagingStyle(appName)
        if (nearby) style.setConversationTitle(AndroidText.get(context, "nearby"))
        if (Build.VERSION.SDK_INT >= 28) style.setGroupConversation(nearby)
        for (line in item.lines) {
            if (Build.VERSION.SDK_INT >= 28) {
                val author = Person.Builder().setName(line.author).setKey(line.authorId).build()
                style.addMessage(line.body, line.time, author)
            } else {
                @Suppress("DEPRECATION")
                style.addMessage(line.body, line.time, line.author)
            }
        }
        if (unsent != null) {
            val failed = context.getString(R.string.next_notification_reply_failed, unsent.take(512))
            if (Build.VERSION.SDK_INT >= 28) style.addMessage(failed, System.currentTimeMillis(),
                Person.Builder().setName(appName).setKey("local").build())
            else {
                @Suppress("DEPRECATION")
                style.addMessage(failed, System.currentTimeMillis(), appName)
            }
        }
        val generic = context.getString(if (nearby) R.string.next_nearby_notification_body else R.string.next_private_notification_body)
        val publicVersion = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_app)
            .setContentTitle(context.getString(R.string.next_app_name)).setContentText(generic).build()
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_app).setStyle(style)
            .setContentTitle(last.author).setContentText(last.body).setWhen(last.time).setContentIntent(open(context, target))
            .setPublicVersion(publicVersion).setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(unsent != null)
        val markRead = action(context, target, item.token, READ, false)
        val mark = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.outline_chat_bubble_24),
            context.getString(R.string.next_notification_mark_read), markRead)
        if (Build.VERSION.SDK_INT >= 28) mark.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        if (Build.VERSION.SDK_INT >= 31) mark.setAuthenticationRequired(true)
        builder.addAction(mark.build())
        if (item.generation != null || item.meshToken != null) {
            val reply = context.getString(R.string.next_notification_reply)
            val directReply = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.outline_send_24), reply,
                action(context, target, item.token, REPLY, true))
                .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel(reply).build())
            if (Build.VERSION.SDK_INT >= 28) directReply.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            if (Build.VERSION.SDK_INT >= 31) directReply.setAuthenticationRequired(true)
            builder.addAction(directReply.build())
        }
        try { manager.notify(target, ID, builder.build()) }
        catch (_: SecurityException) { /* The user can revoke notification access concurrently. */ }
    }

    private fun action(context: Context, destination: String, token: String, action: String, mutable: Boolean): PendingIntent {
        val uri = Uri.Builder().scheme(SCHEME).authority("actions").appendPath(destination).appendPath(token).build()
        val intent = Intent(context, MessageNotificationReceiver::class.java).setAction(action).setData(uri)
        val flags = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}

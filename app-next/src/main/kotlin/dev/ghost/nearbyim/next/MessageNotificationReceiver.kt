package dev.ghost.nearbyim.next

import android.app.KeyguardManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import dev.ghost.nearbyim.AndroidText
import dev.ghost.nearbyim.ChatService
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.TextLimits
import dev.ghost.nearbyim.mesh.MeshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Notification actions address an existing alert and the session which produced it. */
class MessageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val markRead = intent.action == MessageNotifications.READ
        if (!markRead && intent.action != MessageNotifications.REPLY) return
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val reply = if (markRead) null else (RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifications.REPLY_TEXT)?.toString() ?: return)
        val conversation = MessageNotifications.conversation(intent)
        if (conversation == null) {
            if (reply != null) Toast.makeText(context, R.string.next_notification_reply_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val app = context.applicationContext
        if (reply != null && !TextLimits.canSend(reply, TextLimits.EXTENDED_BYTES)) {
            MessageNotifications.completeAction(app, conversation, reply)
            return
        }
        if (conversation.destination == MessageNotifications.NEARBY) {
            if (reply == null) MessageNotifications.completeAction(app, conversation)
            else {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val nickname = app.getSharedPreferences("identity", Context.MODE_PRIVATE).getString("nickname", null)
                            ?: AndroidText.initialNickname(app, true)
                        val queued = MeshClient.reply(app, reply, nickname, conversation.meshToken)
                        MessageNotifications.completeAction(app, conversation, reply.takeUnless { queued })
                    } finally {
                        pending.finish()
                    }
                }
            }
        } else applyToPrivateChat(app, conversation, reply)
    }

    private fun applyToPrivateChat(context: Context, conversation: MessageNotifications.Conversation, reply: String?) {
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val connection = object : ServiceConnection {
            private var finished = false
            private val timeout = Runnable { finish(reply) }

            fun start() {
                val bound = try { context.bindService(Intent(context, ChatService::class.java), this, 0) }
                catch (_: RuntimeException) { false }
                if (bound) main.postDelayed(timeout, 2_000) else finish(reply, unbind = false)
            }

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                val controller = (binder as ChatService.LocalBinder).service().controller
                if (reply == null) {
                    controller.observedNotification(conversation.destination, conversation.generation, conversation.incomingIds)
                    finish(null)
                } else finish(reply.takeUnless {
                    controller.replyFromNotification(conversation.destination, conversation.generation, it)
                })
            }

            override fun onServiceDisconnected(name: ComponentName) = finish(reply)
            override fun onNullBinding(name: ComponentName) = finish(reply)
            override fun onBindingDied(name: ComponentName) = finish(reply)

            private fun finish(unsent: String?, unbind: Boolean = true) {
                if (finished) return
                finished = true
                main.removeCallbacks(timeout)
                try {
                    if (unbind) context.unbindService(this)
                    MessageNotifications.completeAction(context, conversation, unsent)
                } finally {
                    pending.finish()
                }
            }
        }
        connection.start()
    }
}

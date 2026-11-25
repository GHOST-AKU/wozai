package dev.ghost.nearbyim.next

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageNotificationsTest {
    @Test fun notificationsUseSystemChannelsAndOpenTheExactDestination() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val peer = UUID.randomUUID().toString()
        MessageNotifications.post(app, peer, "Sender")
        MessageNotifications.post(app, MessageNotifications.NEARBY)
        val private = manager.activeNotifications.single { it.tag == peer }.notification
        val nearby = manager.activeNotifications.single { it.tag == MessageNotifications.NEARBY }.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, private.visibility)
        assertNotEquals("Sender", private.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(peer, MessageNotifications.destination(shadowOf(private.contentIntent).savedIntent))
        assertEquals(MessageNotifications.NEARBY, MessageNotifications.destination(shadowOf(nearby.contentIntent).savedIntent))
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            manager.getNotificationChannel(private.channelId).sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(nearby.channelId).importance)

        MessageNotifications.visible(app, 42, peer)
        try {
            MessageNotifications.post(app, peer, "Sender")
            assertEquals(listOf(MessageNotifications.NEARBY), manager.activeNotifications.map { it.tag })
        } finally {
            MessageNotifications.visible(app, 42, null)
        }
    }
}

package dev.ghost.nearbyim.next

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.RingtoneManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class MessageNotificationsTest {
    @Test fun notificationsUseSystemChannelsAndOpenTheExactDestination() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val peer = UUID.randomUUID().toString()
        MessageNotifications.post(app, peer, "Sender", "First message", "first", generation = "live-session")
        MessageNotifications.post(app, peer, "Sender", "Latest message", "latest", generation = "live-session")
        MessageNotifications.post(app, MessageNotifications.NEARBY, "Neighbor", "Nearby message", "nearby-id", meshToken = 7)
        val private = manager.activeNotifications.single { it.tag == peer }.notification
        val nearby = manager.activeNotifications.single { it.tag == MessageNotifications.NEARBY }.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, private.visibility)
        assertNotEquals("Sender", private.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNotEquals("Latest message", private.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(2, private.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size)
        assertEquals(1, nearby.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size)
        val action = shadowOf(private.actions.single { !it.remoteInputs.isNullOrEmpty() }.actionIntent).savedIntent
        assertEquals("live-session", MessageNotifications.conversation(action)?.generation)
        assertEquals(listOf("first", "latest"), MessageNotifications.conversation(action)?.incomingIds)
        assertEquals(peer, MessageNotifications.destination(shadowOf(private.contentIntent).savedIntent))
        assertEquals(MessageNotifications.NEARBY, MessageNotifications.destination(shadowOf(nearby.contentIntent).savedIntent))
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            manager.getNotificationChannel(private.channelId).sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(nearby.channelId).importance)
        assertTrue(manager.getNotificationChannel(nearby.channelId).shouldVibrate())
        assertTrue(manager.getNotificationChannel(private.channelId).shouldVibrate())

        MessageNotifications.visible(app, 42, peer)
        try {
            MessageNotifications.post(app, peer, "Sender", "Still viewing", "quiet", generation = "another-session")
            assertEquals(listOf(MessageNotifications.NEARBY), manager.activeNotifications.map { it.tag })
            assertEquals(null, MessageNotifications.conversation(action))
        } finally {
            MessageNotifications.visible(app, 42, null)
        }
    }
}

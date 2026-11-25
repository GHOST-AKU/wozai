package dev.ghost.nearbyim

import dev.ghost.nearbyim.core.AttachmentInfo
import dev.ghost.nearbyim.core.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoReceiptStoreTest {
    @Test fun onlyCompletedPhotosCanEnterTheReadOutboxAndKeepReadAcrossReplay() {
        val peer = UUID.randomUUID().toString()
        val photo = AttachmentInfo.v2(UUID.randomUUID().toString(), "image.jpg", "image/jpeg", 10, "0".repeat(64), 0)
        val file = AttachmentInfo.v2(UUID.randomUUID().toString(), "document.pdf", "application/pdf", 10, "0".repeat(64), 0)
        val incoming = AttachmentRecord(photo, false, "received", 10)
        val outgoing = AttachmentRecord(photo, true, "delivered", 10)
        ChatStore(RuntimeEnvironment.getApplication()).use { store ->
            store.attachment(peer, "Peer", AttachmentRecord(photo, false, "offered", 0))
            store.observed(peer, listOf(photo.id))
            assertEquals(emptyList<String>(), store.pendingReads(peer))
            store.attachment(peer, "Peer", incoming)
            store.attachment(peer, "Peer", AttachmentRecord(file, false, "received", 10))
            store.observed(peer, listOf(photo.id, file.id))
            store.attachment(peer, "Peer", incoming)
            assertEquals(listOf(photo.id), store.pendingReads(peer))
            store.readAcknowledged(peer, listOf(photo.id))
            store.observed(peer, listOf(photo.id))
            assertEquals(emptyList<String>(), store.pendingReads(peer))

            store.attachment(peer, "Peer", AttachmentRecord(photo, true, "offered", 0))
            store.read(peer, listOf(photo.id))
            assertEquals("pending", store.messages(peer).single { it.outgoing }.state)
            store.attachment(peer, "Peer", AttachmentRecord(photo, true, "awaitingReceipt", 10))
            store.read(peer, listOf(photo.id))
            store.attachment(peer, "Peer", outgoing)
            store.delivered(peer, photo.id)
            assertEquals("read", store.messages(peer).single { it.outgoing }.state)
        }
    }
}

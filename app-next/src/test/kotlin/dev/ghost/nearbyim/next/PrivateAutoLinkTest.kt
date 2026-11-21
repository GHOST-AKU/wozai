package dev.ghost.nearbyim.next

import org.junit.Assert.*
import org.junit.Test

class PrivateAutoLinkTest {
    @Test fun simultaneousOpenStaggersCallsAndBoundsRetries() {
        val lower = PrivateAutoLink()
        val higher = PrivateAutoLink()
        lower.select("b", "a", 100)
        higher.select("a", "b", 100)
        assertTrue(lower.nextAttempt < higher.nextAttempt)
        var due = lower.nextAttempt
        for (interval in listOf(2_000, 4_000, 8_000, 16_000)) {
            lower.attempted(due, 250)
            assertEquals(due + interval + 250, lower.nextAttempt)
            due = lower.nextAttempt
        }
        assertTrue(lower.exhausted)
        lower.select("b", "a", due)
        assertTrue(lower.exhausted)
        lower.resume(due)
        assertFalse(lower.exhausted)
        assertEquals(due + 750L, lower.nextAttempt)
        lower.observeReady(true, due)
        assertFalse(lower.exhausted)
        lower.pause()
        lower.resume(due)
        assertTrue(lower.paused)
        lower.select("c", "a", due)
        assertFalse(lower.paused)
        assertTrue(stopsAutomaticReconnect("peerEnded", ""))
        assertTrue(stopsAutomaticReconnect("identityChangedRejected", ""))
        assertFalse(stopsAutomaticReconnect("disconnected", ""))
    }

    @Test fun queuedTextRequiresSameSelectionDraftAndProofUntilItExpires() {
        val remembered = ConnectAndSend("peer", "pinned-root", null, "原文", 1000)
        assertTrue(remembered.stillPending("peer", "原文", "pinned-root", null, 999))
        assertFalse(remembered.stillPending("other", "原文", "pinned-root", null, 999))
        assertFalse(remembered.stillPending("peer", "新草稿", "pinned-root", null, 999))
        assertFalse(remembered.stillPending("peer", "原文", null, null, 999))
        assertFalse(remembered.stillPending("peer", "原文", "pinned-root", null, 1000))
        assertFalse(remembered.matches("changed-root", "generation"))
        assertFalse(remembered.matches("pinned-root", null))
        val once = remembered.copy(generation = "one-time")
        assertTrue(once.stillPending("peer", "原文", null, "one-time", 999))
        assertFalse(once.stillPending("peer", "原文", "pinned-root", "next-generation", 999))
        assertFalse(once.matches("pinned-root", "next-generation"))
        assertTrue(once.matches("pinned-root", "one-time"))
    }
}

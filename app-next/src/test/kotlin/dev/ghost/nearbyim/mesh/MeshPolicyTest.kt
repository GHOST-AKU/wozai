package dev.ghost.nearbyim.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshPolicyTest {
    @Test fun textBudgetIsUtf8AndRejectsMalformedSurrogates() {
        assertTrue(MeshText.valid("你".repeat(682)))
        assertFalse(MeshText.valid("你".repeat(683)))
        assertTrue(MeshText.valid("a".repeat(2048)))
        assertFalse(MeshText.valid("a".repeat(2049)))
        assertFalse(MeshText.valid("bad\uD83D"))
        assertFalse(MeshText.valid(" \n "))
    }

    @Test fun historyDeduplicatesFullIdsAndPreservesActualPath() {
        val history = MeshHistory()
        val first = "a".repeat(63) + "0"
        val second = "a".repeat(63) + "1"
        val origin = "1".repeat(64)
        history.receive(first, "first", listOf(origin, "2".repeat(64), "3".repeat(64)), 12)
        history.receive(first, "duplicate", listOf(origin), 13)
        history.receive(second, "second", listOf(origin, "3".repeat(64)), 14)
        assertEquals(2, history.messages.size)
        assertEquals("first", history.messages[0].body)
        assertEquals(origin, history.messages[0].origin)
        assertEquals(2, history.messages[0].hops)
        assertEquals(1, history.messages[1].hops)
    }

    @Test fun historyRetainsOnlyTwoHundredAndNeverCallsQueuedDelivered() {
        val history = MeshHistory()
        for (i in 0..200) history.queue(i.toString(16).padStart(64, '0'), "text", "a".repeat(64), i.toLong())
        assertEquals(200, history.messages.size)
        assertEquals(1L, history.messages.first().createdMillis)
        assertEquals("queued", history.messages.last().state)
        val id = history.messages.last().id
        history.transition(id, "handed")
        assertEquals("handed", history.messages.last().state)
        history.transition(id, "expired")
        assertEquals("expired", history.messages.last().state)
        history.stop()
        assertEquals("stopped_unconfirmed", history.messages.first().state)
        assertEquals("expired", history.messages.last().state)
    }

    @Test fun staleGenerationCannotPublishAfterStopOrNewStart() {
        val gate = MeshGeneration()
        val first = gate.next()
        assertTrue(gate.accepts(first))
        val stopped = gate.next()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(stopped))
        val next = gate.next()
        assertFalse(gate.accepts(stopped))
        assertTrue(gate.accepts(next))
    }
}

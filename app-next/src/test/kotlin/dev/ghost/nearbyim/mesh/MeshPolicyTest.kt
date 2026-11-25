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

    @Test fun relayedMessageKeepsTheOriginalNicknameAndBody() {
        val history = MeshHistory()
        val body = "第一行\n[WoZai Mesh] 留在正文"
        val signedBody = MeshText.withNickname(body, "小林 🐈")
        history.receive("id", signedBody, listOf("origin", "relay", "local"), 12)
        val received = history.messages.single()
        assertEquals("小林 🐈", received.nickname)
        assertEquals(body, received.body)
        assertEquals("origin", received.origin)
        assertEquals(2, received.hops)
        history.queue("own", signedBody, "local", 13, "s1")
        assertEquals(body, history.messages.last().body)
        assertEquals("s1", history.messages.last().sendRequest)
    }

    @Test fun bareOrMalformedHeadersRemainVisibleAndUseTheOriginFallback() {
        val history = MeshHistory()
        for ((index, body) in listOf("regular message", "[WoZai Mesh] \nhello",
                "[WoZai Mesh] bad\u202Ename\nhello", "[WoZai Mesh] name\n").withIndex()) {
            history.receive(index.toString(), body, listOf("origin", "local"), 12)
            assertNull(history.messages.last().nickname)
            assertEquals(body, history.messages.last().body)
        }
        assertEquals("hello", MeshText.withNickname("hello", "invalid\nname"))
    }

    @Test fun multibyteNicknameIsIncludedInTheExistingWireBudget() {
        val nickname = "中文😀"
        val budget = MeshText.bodyLimit(nickname)
        assertTrue(budget < MeshText.MAX_BODY)
        assertTrue(MeshText.valid(MeshText.withNickname("a".repeat(budget), nickname)))
        assertFalse(MeshText.valid(MeshText.withNickname("a".repeat(budget + 1), nickname)))
        assertEquals(MeshText.MAX_BODY, MeshText.bodyLimit(""))
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

    @Test fun stopAndRecreatedRuntimeRetainTimelineButFreshProcessStartsEmpty() {
        val history = MeshHistory()
        history.queue("own", "pending", "local", 1)
        history.receive("incoming", "hello", listOf("sender", "local"), 2)
        history.stop()
        val recreated = MeshHistory(history.messages)
        assertEquals(listOf("pending", "hello"), recreated.messages.map { it.body })
        assertEquals("stopped_unconfirmed", recreated.messages.first().state)
        assertEquals("received", recreated.messages.last().state)
        assertTrue(MeshHistory().messages.isEmpty())
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

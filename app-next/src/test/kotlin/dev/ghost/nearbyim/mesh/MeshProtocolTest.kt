package dev.ghost.nearbyim.mesh

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MeshProtocolTest {
    private val id = "a".repeat(64)
    private val peer = "b".repeat(64)

    @Test fun frameReaderHasAnExactByteLimitAndRejectsTruncationAndInvalidUtf8() {
        assertEquals("x".repeat(32768), MeshFrames.read(ByteArrayInputStream(("x".repeat(32768) + "\n").toByteArray())))
        for (bytes in listOf(("x".repeat(32769) + "\n").toByteArray(), "truncated".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28, 10))) {
            try { MeshFrames.read(ByteArrayInputStream(bytes)); fail("accepted malformed frame") }
            catch (_: MeshProtocolException) { }
        }
        assertNull(MeshFrames.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun parserValidatesIdsStatesBodyAndActualRoute() {
        val event = MeshProtocol.parse("""{"event":"message","id":"$id","body":"hello","path":["$peer","$id"]}""") as MeshEvent.Message
        assertEquals(peer, event.path.first())
        assertEquals(2, event.path.size)
        val longestPath = (1..9).joinToString(",") { "\"${it.toString(16).repeat(64)}\"" }
        assertEquals(9, (MeshProtocol.parse("""{"event":"message","id":"$id","body":"valid peer route","path":[$longestPath]}""") as MeshEvent.Message).path.size)
        val invalid = listOf(
            """{"event":"message","id":"short","body":"hello","path":["$peer"]}""",
            """{"event":"message","id":"$id","body":7,"path":["$peer"]}""",
            """{"event":"message","id":"$id","body":"${"x".repeat(2049)}","path":["$peer"]}""",
            """{"event":"message","id":"$id","body":"hello","path":["$peer","$peer"]}""",
            """{"event":"status","extra":${"[".repeat(16)}0${"]".repeat(16)}}""",
            """{"event":"relay","enabled":true}trailing""",
            """{"event":"handed","id":"$id","state":"delivered"}""",
            """{"event":"ready","key":"$id","address":"192.0.2.1:39770","relay":false}""",
            """{"event":"relay","enabled":"true"}"""
        )
        for (frame in invalid) {
            try { MeshProtocol.parse(frame); fail("accepted malformed event") }
            catch (_: MeshProtocolException) { }
        }
    }

    @Test fun duplicateAndRejectedCorePacketsAreNonFatalTelemetry() {
        assertEquals(MeshEvent.Metadata, MeshProtocol.parse("""{"event":"duplicate","id":"$id"}"""))
        assertEquals(MeshEvent.Metadata, MeshProtocol.parse("""{"event":"rejected","reason":"expired"}"""))
        assertEquals(MeshEvent.Metadata, MeshProtocol.parse("""{"event":"link_authenticated","peer":"$peer","address":"127.0.0.1:12345","public_identity_only":true}"""))
    }

    @Test fun queuedAcceptanceMatchesExactOrderedRequestAndDropsRejectedBody() {
        val pending = MeshPending()
        assertTrue(pending.add("s1", "first"))
        assertTrue(pending.add("s2", "second"))
        assertNull(pending.accept("s2"))
        assertEquals("first", pending.accept("s1"))
        pending.reject()
        assertNull(pending.accept("s2"))
        repeat(16) { assertTrue(pending.add("s$it", "bounded")) }
        assertFalse(pending.add("overflow", "not queued"))
        pending.clear()
        assertNull(pending.accept("s0"))
    }
}

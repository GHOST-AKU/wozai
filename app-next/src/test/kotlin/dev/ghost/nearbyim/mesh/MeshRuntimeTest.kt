package dev.ghost.nearbyim.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MeshRuntimeTest {
    private val key = "a".repeat(64)

    private class FakeProcess : Process() {
        private val source = PipedInputStream(65536)
        private val frames = PipedOutputStream(source)
        val commands = ByteArrayOutputStream()
        val ended = CountDownLatch(1)
        var destroyedOn = ""
        override fun getInputStream() = source
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = commands
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = ended.await(timeout, unit)
        override fun exitValue(): Int { if (ended.count != 0L) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { destroyedOn = Thread.currentThread().name; ended.countDown(); frames.close(); source.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun emit(frame: String) { frames.write((frame + "\n").toByteArray()); frames.flush() }
    }

    private class Platform : MeshPlatform {
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val radioFailures = CopyOnWriteArrayList<() -> Unit>()
        val closedRadios = AtomicInteger()
        var guard: String? = null
        var launchBlocked: CountDownLatch? = null
        val launching = CountDownLatch(1)
        override fun check(mode: String) = guard
        override fun launch(relay: Boolean): Process {
            launching.countDown()
            launchBlocked?.await(3, TimeUnit.SECONDS)
            return FakeProcess().also { processes.add(it) }
        }
        override fun startRadio(mode: String, port: Int, failed: () -> Unit): AutoCloseable {
            assertEquals(39770, port)
            radioFailures.add(failed)
            return AutoCloseable { closedRadios.incrementAndGet() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("condition not reached", condition())
    }

    @Test fun acceptedOwnBodyComesOnlyFromMatchingQueuedIdAndStopCleansOffCaller() {
        val platform = Platform()
        val state = AtomicReference(MeshSnapshot())
        val runtime = MeshRuntime(platform, MeshSnapshot(), { state.set(it) }, {})
        try {
            runtime.start("ble", false)
            await { platform.processes.size == 1 }
            val process = platform.processes[0]
            process.emit("""{"event":"ready","key":"$key","address":"127.0.0.1:39770","relay":false}""")
            await { state.get().running }
            runtime.send("public text")
            await { process.commands.toString().contains("\"request\":\"s1\"") }
            assertTrue(state.get().messages.isEmpty())
            val id = "b".repeat(64)
            process.emit("""{"event":"queued","id":"$id","state":"pending","request":"s1"}""")
            await { state.get().messages.size == 1 }
            assertEquals("public text", state.get().messages.single().body)
            assertEquals(id, state.get().messages.single().id)
            assertEquals("queued", state.get().messages.single().state)
            val caller = Thread.currentThread().name
            runtime.stop()
            await { process.ended.count == 0L && platform.closedRadios.get() == 1 }
            assertNotEquals(caller, process.destroyedOn)
            assertFalse(state.get().running)
            assertEquals("stopped_unconfirmed", state.get().messages.single().state)
        } finally { runtime.close() }
    }

    @Test fun staleRadioFailureCannotStopNewSessionAndPermissionGuardNeverLaunchesCore() {
        val platform = Platform()
        val state = AtomicReference(MeshSnapshot())
        val runtime = MeshRuntime(platform, MeshSnapshot(), { state.set(it) }, {})
        try {
            platform.guard = "mesh_permission_required"
            runtime.start("ble", false)
            await { state.get().error == "mesh_permission_required" }
            assertTrue(platform.processes.isEmpty())
            platform.guard = null
            runtime.start("ble", false)
            await { platform.processes.size == 1 }
            platform.processes[0].emit("""{"event":"ready","key":"$key","address":"127.0.0.1:39770","relay":false}""")
            await { state.get().running }
            runtime.stop()
            runtime.start("aware", false)
            await { platform.processes.size == 2 }
            platform.processes[1].emit("""{"event":"ready","key":"$key","address":"127.0.0.1:39770","relay":false}""")
            await { state.get().running && state.get().mode == "aware" }
            platform.radioFailures[0]()
            Thread.sleep(50)
            assertTrue(state.get().running)
            assertEquals("", state.get().error)
        } finally { runtime.close() }
    }

    @Test fun stoppingWhileProcessStartsClosesLateProcessWithoutPublishingReady() {
        val platform = Platform()
        val release = CountDownLatch(1)
        platform.launchBlocked = release
        val state = AtomicReference(MeshSnapshot())
        val runtime = MeshRuntime(platform, MeshSnapshot(), { state.set(it) }, {})
        try {
            runtime.start("ble", false)
            assertTrue(platform.launching.await(2, TimeUnit.SECONDS))
            runtime.stop()
            release.countDown()
            await { platform.processes.size == 1 && platform.processes[0].ended.count == 0L }
            assertFalse(state.get().running)
            assertFalse(state.get().starting)
            assertTrue(platform.radioFailures.isEmpty())
        } finally { release.countDown(); runtime.close() }
    }

    @Test fun relayWaitsForCoreAckAndMalformedFrameStopsWithoutKeepingPayload() {
        val platform = Platform()
        val state = AtomicReference(MeshSnapshot())
        val runtime = MeshRuntime(platform, MeshSnapshot(), { state.set(it) }, {})
        try {
            runtime.start("ble", false)
            await { platform.processes.size == 1 }
            val process = platform.processes[0]
            process.emit("""{"event":"ready","key":"$key","address":"127.0.0.1:39770","relay":false}""")
            await { state.get().running }
            runtime.setRelay(true)
            await { process.commands.toString().contains("\"enabled\":true") }
            assertFalse(state.get().relay)
            process.emit("""{"event":"relay","enabled":true}""")
            await { state.get().relay }
            process.emit("""{"event":"message","id":"bad-id","body":"sensitive malformed body","path":["$key"]}""")
            await { state.get().error == "mesh_protocol_error" }
            assertFalse(state.get().running)
            assertTrue(state.get().messages.isEmpty())
            runtime.close()
            assertEquals("mesh_protocol_error", state.get().error)
        } finally { runtime.close() }
    }
}

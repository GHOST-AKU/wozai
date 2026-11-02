package dev.ghost.nearbyim.mesh

import java.io.BufferedInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android and process creation are injected so ownership/cancellation can be tested without radios. */
internal interface MeshPlatform {
    fun check(mode: String): String?
    fun launch(relay: Boolean): Process
    fun startRadio(mode: String, port: Int, failed: () -> Unit): AutoCloseable
}

/** One bounded actor owns the core session. Invalidation happens before asynchronous cleanup. */
internal class MeshRuntime(
    private val platform: MeshPlatform,
    initial: MeshSnapshot,
    private val update: (MeshSnapshot) -> Unit,
    private val terminal: () -> Unit,
) : AutoCloseable {
    private val generation = MeshGeneration()
    private val control = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { runnable -> Thread(runnable, "mesh-control").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mesh-monitor").apply { isDaemon = true }
    }
    private val tickPending = AtomicBoolean()
    private val history = MeshHistory(initial.messages)
    private val pending = MeshPending()
    @Volatile private var snapshot = initial
    @Volatile private var closed = false
    private var session: Session? = null
    private var requestSequence = 0L
    private var startedNanos = 0L
    private var publication = update
    private var termination = terminal

    init {
        clock.scheduleWithFixedDelay({
            if (tickPending.compareAndSet(false, true)) {
                val token = synchronized(this) { activeGeneration }
                if (!enqueue(token) {
                    try {
                        if (snapshot.running || snapshot.starting) {
                            val error = platform.check(snapshot.mode)
                            if (error != null) end(error, token, true)
                            else if (snapshot.starting && System.nanoTime() - startedNanos > TimeUnit.SECONDS.toNanos(30))
                                end("mesh_start_failed", token, true)
                        }
                    } finally { tickPending.set(false) }
                }) tickPending.set(false)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private var activeGeneration = 0L

    @Synchronized fun start(mode: String, relay: Boolean,
        publication: (MeshSnapshot) -> Unit = update, termination: () -> Unit = terminal) {
        if (closed) return
        val token = generation.next()
        // Invalidate old callbacks before replacing the service command's publication owner.
        this.publication = publication
        this.termination = termination
        activeGeneration = token
        tickPending.set(false)
        publish(token, snapshot.copy(running = false, starting = true, mode = mode, relay = relay,
            localKey = "", neighbors = emptyList(), error = "", notice = "mesh_hardware_pending", diagnostics = ""))
        enqueue(token) {
            cleanup()
            if (!generation.accepts(token)) return@enqueue
            history.stop()
            val guard = platform.check(mode)
            if (guard != null) { end(guard, token, true); return@enqueue }
            startedNanos = System.nanoTime()
            val process = try { platform.launch(relay) }
            catch (_: Exception) { end("mesh_start_failed", token, true); return@enqueue }
            if (!generation.accepts(token)) {
                MeshCoreProcess(process, { false }, {}).close()
                return@enqueue
            }
            val pipe = MeshCoreProcess(process, { frame -> consume(token, frame) }, { code -> end(code, token, true) })
            session = Session(pipe)
            pipe.start()
        }
    }

    fun send(text: String) {
        val token = synchronized(this) { activeGeneration }
        if (!MeshText.valid(text)) {
            enqueue(token) { publish(token, snapshot.copy(error = "mesh_send_invalid")) }
            return
        }
        enqueue(token) {
            val current = session
            if (!snapshot.running || current == null) return@enqueue
            val request = "s${++requestSequence}"
            if (!pending.add(request, text)) {
                publish(token, snapshot.copy(error = "mesh_command_busy")); return@enqueue
            }
            if (!current.pipe.offer(MeshProtocol.send(text, request))) {
                pending.remove(request)
                publish(token, snapshot.copy(error = "mesh_command_busy"))
            } else publish(token, snapshot.copy(error = ""))
        }
    }

    fun setRelay(enabled: Boolean) {
        val token = synchronized(this) { activeGeneration }
        enqueue(token) {
            if (snapshot.running && session?.pipe?.offer(MeshProtocol.relay(enabled)) != true)
                publish(token, snapshot.copy(error = "mesh_command_busy"))
        }
    }

    fun stop() = end("", null, false)

    /** This method never waits for a radio, pipe, process, or worker on its caller's thread. */
    @Synchronized private fun end(error: String, expected: Long?, notify: Boolean) {
        if (closed || (expected != null && !generation.accepts(expected))) return
        val token = generation.next()
        val onTerminal = termination
        activeGeneration = token
        publish(token, snapshot.copy(running = false, starting = false, relay = false, neighbors = emptyList(), error = error,
            messages = snapshot.messages.map { if (it.own && it.state == "queued") it.copy(state = "stopped_unconfirmed") else it }))
        control.queue.clear()
        tickPending.set(false)
        try {
            control.execute {
                cleanup()
                history.stop()
                publish(token, snapshot.copy(messages = history.messages))
                if (notify) generation.ifCurrent(token) { onTerminal() }
            }
        } catch (_: RejectedExecutionException) { /* close() has already enqueued teardown. */ }
    }

    private fun enqueue(token: Long, action: () -> Unit): Boolean {
        if (closed || !generation.accepts(token)) return false
        return try {
            control.execute {
                if (generation.accepts(token)) {
                    try { action() }
                    catch (_: Exception) { end("mesh_core_failed", token, true) }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            end("mesh_command_busy", token, true)
            false
        }
    }

    /** A reader may have only one outstanding event; a flooding child cannot grow the actor queue. */
    private fun consume(token: Long, frame: String): Boolean {
        val processed = CountDownLatch(1)
        if (!enqueue(token) {
            try {
                val event = try { MeshProtocol.parse(frame) }
                catch (_: MeshProtocolException) { end("mesh_protocol_error", token, true); return@enqueue }
                onEvent(token, event)
            } finally { processed.countDown() }
        }) return false
        return try {
            if (!processed.await(5, TimeUnit.SECONDS)) { end("mesh_core_failed", token, true); false }
            else generation.accepts(token)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    private fun onEvent(token: Long, event: MeshEvent) {
        val current = session ?: return
        if (event is MeshEvent.Ready) {
            if (current.radio != null || !snapshot.starting || event.relay != snapshot.relay) {
                end("mesh_protocol_error", token, true); return
            }
            current.radio = try { platform.startRadio(snapshot.mode, event.port) { end("mesh_radio_failed", token, true) } }
            catch (_: Exception) { end("mesh_radio_failed", token, true); return }
            publish(token, snapshot.copy(running = true, starting = false, localKey = event.key, messages = history.messages))
            return
        }
        if (!snapshot.running) { end("mesh_protocol_error", token, true); return }
        var next = snapshot
        when (event) {
            is MeshEvent.Queued -> {
                val body = pending.accept(event.request)
                if (body == null) { end("mesh_protocol_error", token, true); return }
                history.queue(event.id, body, snapshot.localKey, System.currentTimeMillis())
                next = next.copy(error = "")
            }
            is MeshEvent.Message -> {
                if (event.path.last() != snapshot.localKey) { end("mesh_protocol_error", token, true); return }
                history.receive(event.id, event.body, event.path, System.currentTimeMillis())
            }
            is MeshEvent.State -> history.transition(event.id, event.state)
            is MeshEvent.Neighbor -> {
                val peers = next.neighbors.toMutableSet()
                if (event.up) peers.add(event.key) else peers.remove(event.key)
                if (peers.size > 8) { end("mesh_protocol_error", token, true); return }
                next = next.copy(neighbors = peers.sorted())
            }
            is MeshEvent.Neighbors -> next = next.copy(neighbors = event.peers.sorted())
            is MeshEvent.Relay -> next = next.copy(relay = event.enabled)
            MeshEvent.Rejected -> { pending.reject(); next = next.copy(error = "mesh_send_rejected") }
            MeshEvent.RestartLost -> next = next.copy(notice = "mesh_restart_lost")
            MeshEvent.Metadata -> return
            is MeshEvent.Ready -> return
        }
        publish(token, next.copy(messages = history.messages))
    }

    private fun publish(token: Long, next: MeshSnapshot) {
        generation.ifCurrent(token) { snapshot = next; publication(next) }
    }

    private fun cleanup() {
        val old = session
        session = null
        pending.clear()
        try { old?.radio?.close() } catch (_: Exception) { }
        old?.pipe?.close()
    }

    @Synchronized override fun close() {
        if (closed) return
        end(snapshot.error, null, false)
        closed = true
        clock.shutdownNow()
        control.shutdown()
    }

    private class Session(val pipe: MeshCoreProcess, var radio: AutoCloseable? = null)
}

/** The only pipe writer, one stdout reader and one stderr drainer have fixed memory budgets. */
private class MeshCoreProcess(
    private val process: Process,
    private val frame: (String) -> Boolean,
    private val failed: (String) -> Unit,
) : AutoCloseable {
    private val closing = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val commands = ArrayBlockingQueue<String>(16)
    private val threads = mutableListOf<Thread>()

    fun offer(command: String): Boolean = !stopping.get() && command.toByteArray(Charsets.UTF_8).size <= 16383 && commands.offer(command)

    fun start() {
        worker("mesh-core-write") {
            try {
                while (!closing.get()) {
                    val command = commands.take()
                    process.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                }
            } catch (_: Exception) { if (!closing.get()) failed("mesh_core_failed") }
        }
        worker("mesh-core-read") {
            try {
                val input = BufferedInputStream(process.inputStream, 4096)
                while (!closing.get()) {
                    val line = MeshFrames.read(input) ?: break
                    if (!frame(line)) return@worker
                }
                if (!closing.get()) failed("mesh_core_failed")
            } catch (_: MeshProtocolException) { if (!closing.get()) failed("mesh_protocol_error") }
            catch (_: Exception) { if (!closing.get()) failed("mesh_core_failed") }
        }
        worker("mesh-core-stderr") {
            // Drain without retaining or logging exception text, addresses, keys or message bodies.
            try {
                val buffer = ByteArray(4096)
                while (!closing.get() && process.errorStream.read(buffer) != -1) { }
            } catch (_: Exception) { }
        }
    }

    private fun worker(name: String, action: () -> Unit) {
        Thread(action, name).apply { isDaemon = true; threads.add(this); start() }
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        commands.clear()
        commands.offer("{\"cmd\":\"quit\"}")
        // Only the bounded writer touches stdin; a wedged child gets a short exit deadline.
        try { process.waitFor(300, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        closing.set(true)
        process.destroy()
        try { process.outputStream.close() } catch (_: Exception) { }
        try { process.inputStream.close() } catch (_: Exception) { }
        try { process.errorStream.close() } catch (_: Exception) { }
        try {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(300, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) { process.destroyForcibly() }
        for (thread in threads) thread.interrupt()
        for (thread in threads) {
            if (thread !== Thread.currentThread()) {
                try { thread.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
    }
}

package dev.ghost.nearbyim.mesh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class MeshMessage(
    val id: String, val body: String, val origin: String, val hops: Int,
    val own: Boolean, val state: String, val createdMillis: Long,
)

data class MeshSnapshot(
    val running: Boolean = false,
    val starting: Boolean = false,
    val mode: String = "ble",
    val relay: Boolean = false,
    val localKey: String = "",
    val neighbors: List<String> = emptyList(),
    val messages: List<MeshMessage> = emptyList(),
    val error: String = "",
    val notice: String = "",
    val diagnostics: String = "",
)

internal object MeshText {
    fun valid(text: String): Boolean = text.isNotBlank() && validBody(text)

    fun validBody(text: String): Boolean {
        if (text.isEmpty() || text.length > 2048) return false
        return try {
            val encoded: ByteBuffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            encoded.remaining() <= 2048
        } catch (_: java.nio.charset.CharacterCodingException) { false }
    }
}

/** The public timeline is process-local and bounded; IDs are never shortened for deduplication. */
internal class MeshHistory(initial: List<MeshMessage> = emptyList()) {
    private val entries = LinkedHashMap<String, MeshMessage>()
    init { initial.takeLast(200).forEach { entries[it.id] = it } }
    val messages: List<MeshMessage> get() = entries.values.toList()

    fun queue(id: String, body: String, origin: String, time: Long) =
        append(MeshMessage(id, body, origin, 0, true, "queued", time))

    fun receive(id: String, body: String, path: List<String>, time: Long) {
        append(MeshMessage(id, body, path.first(), path.size - 1, false, "received", time))
    }

    private fun append(message: MeshMessage) {
        if (entries.containsKey(message.id)) return
        entries[message.id] = message
        while (entries.size > 200) entries.remove(entries.keys.first())
    }

    fun transition(id: String, state: String) {
        val old = entries[id] ?: return
        if (!old.own || old.state == "expired" || old.state == "stopped_unconfirmed") return
        if (state == "handed" || state == "expired") entries[id] = old.copy(state = state)
    }

    fun stop() {
        for ((id, message) in entries.toMap()) {
            if (message.own && message.state == "queued") entries[id] = message.copy(state = "stopped_unconfirmed")
        }
    }
}

internal class MeshGeneration {
    private var value = 0L
    @Synchronized fun next(): Long = ++value
    @Synchronized fun accepts(candidate: Long): Boolean = candidate == value
    @Synchronized fun ifCurrent(candidate: Long, action: () -> Unit): Boolean {
        if (candidate != value) return false
        action()
        return true
    }
}

/** At most sixteen bodies await core acceptance, in the same order as stdin commands. */
internal class MeshPending {
    private val entries = LinkedHashMap<String, String>()
    fun add(request: String, body: String): Boolean {
        if (entries.size >= 16 || entries.containsKey(request)) return false
        entries[request] = body
        return true
    }
    fun accept(request: String): String? {
        if (entries.keys.firstOrNull() != request) return null
        return entries.remove(request)
    }
    fun reject() { entries.keys.firstOrNull()?.let { entries.remove(it) } }
    fun remove(request: String) { entries.remove(request) }
    fun clear() { entries.clear() }
}

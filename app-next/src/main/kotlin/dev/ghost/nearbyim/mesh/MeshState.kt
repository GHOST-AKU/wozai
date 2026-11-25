package dev.ghost.nearbyim.mesh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class MeshMessage(
    val id: String, val body: String, val origin: String, val hops: Int,
    val own: Boolean, val state: String, val createdMillis: Long,
    val sendRequest: String? = null,
    val nickname: String? = null,
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
    val rejectedRequest: String? = null,
) {
    internal val directDeviceCount: Int?
        get() = if (running) neighbors.size else null
}

internal object MeshText {
    const val MAX_BODY = 2048
    private const val NICKNAME_PREFIX = "[WoZai Mesh] "
    private val unsafeNickname = Regex("""[\p{Cc}\p{Zl}\p{Zp}\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]""")

    fun valid(text: String): Boolean = text.isNotBlank() && validBody(text)

    fun validBody(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_BODY && (utf8Bytes(text) ?: Int.MAX_VALUE) <= MAX_BODY

    private fun utf8Bytes(text: String): Int? =
        try {
            val encoded: ByteBuffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            encoded.remaining()
        } catch (_: java.nio.charset.CharacterCodingException) { null }

    private fun validNickname(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 && Character.codePointCount(name, 0, name.length) <= 32 &&
            !unsafeNickname.containsMatchIn(name) && (utf8Bytes(name) ?: Int.MAX_VALUE) <= 128

    // Relay the readable header inside the existing origin-signed text body.
    private fun nicknameHeader(nickname: String): String =
        nickname.takeIf(::validNickname)?.let { "$NICKNAME_PREFIX$it\n" }.orEmpty()

    fun withNickname(body: String, nickname: String): String = nicknameHeader(nickname) + body

    fun bodyLimit(nickname: String): Int = MAX_BODY - nicknameHeader(nickname).toByteArray(Charsets.UTF_8).size

    fun decodeNickname(message: MeshMessage): MeshMessage {
        if (!message.body.startsWith(NICKNAME_PREFIX)) return message
        val headerEnd = message.body.indexOf('\n', NICKNAME_PREFIX.length)
        if (headerEnd < 0 || headerEnd == message.body.lastIndex) return message
        val name = message.body.substring(NICKNAME_PREFIX.length, headerEnd)
        if (!validNickname(name)) return message
        return message.copy(body = message.body.substring(headerEnd + 1), nickname = name)
    }
}

/** The public timeline is process-local and bounded; IDs are never shortened for deduplication. */
internal class MeshHistory(initial: List<MeshMessage> = emptyList()) {
    private val entries = LinkedHashMap<String, MeshMessage>()
    init { initial.takeLast(200).forEach { entries[it.id] = it } }
    val messages: List<MeshMessage> get() = entries.values.toList()

    fun queue(id: String, body: String, origin: String, time: Long, request: String? = null) =
        append(MeshMessage(id, body, origin, 0, true, "queued", time, request))

    fun receive(id: String, body: String, path: List<String>, time: Long) {
        append(MeshMessage(id, body, path.first(), path.size - 1, false, "received", time))
    }

    private fun append(message: MeshMessage) {
        if (entries.containsKey(message.id)) return
        entries[message.id] = MeshText.decodeNickname(message)
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
    fun reject(): String? = entries.keys.firstOrNull()?.also { entries.remove(it) }
    fun remove(request: String) { entries.remove(request) }
    fun clear() { entries.clear() }
}

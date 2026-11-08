package dev.ghost.nearbyim.mesh

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class MeshProtocolException : Exception()

internal object MeshFrames {
    fun read(input: InputStream): String? {
        val bytes = ByteArrayOutputStream(512)
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (bytes.size() == 0) return null
                throw MeshProtocolException()
            }
            if (next == 10) break
            if (bytes.size() >= 32768) throw MeshProtocolException()
            bytes.write(next)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw MeshProtocolException() }
    }
}

internal sealed class MeshEvent {
    data class Ready(val key: String, val port: Int, val relay: Boolean) : MeshEvent()
    data class Queued(val id: String, val request: String) : MeshEvent()
    data class Message(val id: String, val body: String, val path: List<String>) : MeshEvent()
    data class State(val id: String, val state: String) : MeshEvent()
    data class Neighbor(val key: String, val up: Boolean) : MeshEvent()
    data class Neighbors(val peers: List<String>) : MeshEvent()
    data class Relay(val enabled: Boolean) : MeshEvent()
    data object Rejected : MeshEvent()
    data object RestartLost : MeshEvent()
    data object Metadata : MeshEvent()
}

internal object MeshProtocol {
    private val keyPattern = Regex("[0-9a-f]{64}")
    private fun token(value: Any?): String = (value as? String)?.takeIf { keyPattern.matches(it) } ?: throw MeshProtocolException()
    private fun string(obj: JSONObject, field: String): String = obj.opt(field) as? String ?: throw MeshProtocolException()
    private fun bool(obj: JSONObject, field: String): Boolean = obj.opt(field) as? Boolean ?: throw MeshProtocolException()
    private fun peers(value: Any?, max: Int): List<String> {
        val array = value as? JSONArray ?: throw MeshProtocolException()
        if (array.length() > max) throw MeshProtocolException()
        val peers = (0 until array.length()).map { token(array.opt(it)) }
        if (peers.distinct().size != peers.size) throw MeshProtocolException()
        return peers
    }

    fun parse(frame: String): MeshEvent {
        if (frame.length > 32768) throw MeshProtocolException()
        boundedNesting(frame)
        try {
            val input = JSONTokener(frame)
            val obj = input.nextValue() as? JSONObject ?: throw MeshProtocolException()
            if (input.nextClean() != '\u0000') throw MeshProtocolException()
            return when (string(obj, "event")) {
                "ready" -> {
                    val address = string(obj, "address")
                    if (!Regex("127\\.0\\.0\\.1:[0-9]{1,5}").matches(address)) throw MeshProtocolException()
                    val port = address.substringAfter(':').toInt()
                    if (port !in 1024..65535) throw MeshProtocolException()
                    MeshEvent.Ready(token(obj.opt("key")), port, bool(obj, "relay"))
                }
                "queued" -> {
                    if (string(obj, "state") != "pending") throw MeshProtocolException()
                    val request = string(obj, "request")
                    if (!Regex("s[0-9]{1,18}").matches(request)) throw MeshProtocolException()
                    MeshEvent.Queued(token(obj.opt("id")), request)
                }
                "message" -> {
                    val body = string(obj, "body")
                    if (!MeshText.validBody(body)) throw MeshProtocolException()
                    val path = peers(obj.opt("path"), 9)
                    if (path.isEmpty()) throw MeshProtocolException()
                    MeshEvent.Message(token(obj.opt("id")), body, path)
                }
                "handed", "expired" -> {
                    val handed = string(obj, "event") == "handed"
                    if (string(obj, "state") != if (handed) "handed_to_neighbor_unconfirmed" else "expired_unconfirmed") throw MeshProtocolException()
                    MeshEvent.State(token(obj.opt("id")), if (handed) "handed" else "expired")
                }
                "neighbor_up", "neighbor_down" ->
                    MeshEvent.Neighbor(token(obj.opt("peer")), string(obj, "event") != "neighbor_down")
                "link_authenticated" -> { token(obj.opt("peer")); MeshEvent.Metadata }
                "duplicate" -> { token(obj.opt("id")); MeshEvent.Metadata }
                "rejected" -> {
                    if (!Regex("[a-z_]{1,80}").matches(string(obj, "reason"))) throw MeshProtocolException()
                    MeshEvent.Metadata
                }
                "neighbors" -> MeshEvent.Neighbors(peers(obj.opt("peers"), 8))
                "relay" -> MeshEvent.Relay(bool(obj, "enabled"))
                "error" -> MeshEvent.Rejected
                "failed_restart" -> MeshEvent.RestartLost
                // The core emits hop paths and aggregate diagnostics; only authenticated
                // message events populate the public timeline, never raw diagnostic JSON.
                "hop_queued", "status", "diagnostics" -> MeshEvent.Metadata
                else -> throw MeshProtocolException()
            }
        } catch (error: MeshProtocolException) { throw error }
        catch (_: Exception) { throw MeshProtocolException() }
    }

    private fun boundedNesting(frame: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in frame) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                    character < ' ' -> throw MeshProtocolException()
                }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { if (++depth > 8) throw MeshProtocolException() }
                '}', ']' -> { if (--depth < 0) throw MeshProtocolException() }
            }
        }
        if (depth != 0 || quoted) throw MeshProtocolException()
    }

    fun send(body: String, request: String): String = JSONObject().put("cmd", "send")
        .put("text", body).put("ttl", 600).put("hops", 3).put("request", request).toString()
    fun relay(enabled: Boolean): String = JSONObject().put("cmd", "relay").put("enabled", enabled).toString()
}

package dev.ghost.nearbyim.next
import dev.ghost.nearbyim.mesh.MeshMessage

/** Queue acceptance survives StateFlow conflation; an old identical body is never an acknowledgement. */
internal fun acceptedMeshSend(before: Set<String>, submitted: String, messages: List<MeshMessage>): Boolean =
    messages.any { it.own && it.id !in before && it.body == submitted &&
        it.state in setOf("queued", "handed", "expired", "stopped_unconfirmed") }

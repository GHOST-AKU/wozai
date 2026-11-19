package dev.ghost.nearbyim.next
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ghost.nearbyim.mesh.MeshMessage

/** Queue acceptance survives StateFlow conflation; an old identical body is never an acknowledgement. */
internal fun acceptedMeshSend(before: Set<String>, submitted: String, messages: List<MeshMessage>): Boolean =
    messages.any { it.own && it.id !in before && it.body == submitted &&
        it.state in setOf("queued", "handed", "expired", "stopped_unconfirmed") }

/** Owned by the navigation root so a temporary page change cannot discard a send in progress. */
internal class MeshComposer(
    text: MutableState<String>,
    pending: MutableState<String?>,
    ids: MutableState<ArrayList<String>>,
) {
    var text by text
    var pending by pending
    var baselineIds by ids
}

@Composable
internal fun rememberMeshComposer(): MeshComposer {
    val text = rememberSaveable { mutableStateOf("") }
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val ids = rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    return remember(text, pending, ids) { MeshComposer(text, pending, ids) }
}

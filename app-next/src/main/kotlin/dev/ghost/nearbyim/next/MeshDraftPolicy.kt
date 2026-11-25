package dev.ghost.nearbyim.next

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ghost.nearbyim.mesh.MeshClient
import dev.ghost.nearbyim.mesh.MeshSnapshot
import java.io.Serializable
import kotlinx.coroutines.flow.collect

internal data class MeshSubmission(val request: String, val body: String) : Serializable

/** Returns null while waiting. Core acceptance clears only the draft that was submitted. */
internal fun resolvedMeshDraft(draft: String, submitted: MeshSubmission, snapshot: MeshSnapshot): String? = when {
    snapshot.messages.any { it.own && it.sendRequest == submitted.request } ->
        if (draft == submitted.body) "" else draft
    snapshot.rejectedRequest == submitted.request || (!snapshot.running && !snapshot.starting) -> draft
    else -> null
}

/** Owned by the navigation root so a temporary page change cannot discard a send in progress. */
internal class MeshComposer(
    text: MutableState<String>,
    pending: MutableState<MeshSubmission?>,
) {
    var text by text
    var pending by pending
}

@Composable
internal fun rememberMeshComposer(): MeshComposer {
    val text = rememberSaveable { mutableStateOf("") }
    val pending = key("mesh-send-request") {
        rememberSaveable { mutableStateOf<MeshSubmission?>(null) }
    }
    val composer = remember(text, pending) { MeshComposer(text, pending) }
    LaunchedEffect(composer) {
        MeshClient.state.collect { snapshot ->
            val submission = composer.pending ?: return@collect
            val resolved = resolvedMeshDraft(composer.text, submission, snapshot) ?: return@collect
            composer.text = resolved
            composer.pending = null
        }
    }
    return composer
}

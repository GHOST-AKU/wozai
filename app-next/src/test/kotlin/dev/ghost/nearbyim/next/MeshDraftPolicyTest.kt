package dev.ghost.nearbyim.next

import dev.ghost.nearbyim.mesh.MeshMessage
import dev.ghost.nearbyim.mesh.MeshSnapshot
import org.junit.Assert.*
import org.junit.Test

class MeshDraftPolicyTest {
    private val submission = MeshSubmission("s2", "same body")

    private fun message(request: String?, state: String = "queued") =
        MeshMessage(request ?: "incoming-id", "same body", "a".repeat(64), 0, request != null, state, 1, request)

    @Test fun identicalBodyFromAnotherSendDoesNotClearDraft() {
        val snapshot = MeshSnapshot(running = true, messages = listOf(message("s1"), message(null, "received")))
        assertNull(resolvedMeshDraft("same body", submission, snapshot))
    }

    @Test fun acceptanceSurvivesConflationAndKeepsLaterEdits() {
        for (state in listOf("queued", "handed", "expired", "stopped_unconfirmed")) {
            val snapshot = MeshSnapshot(messages = listOf(message("s2", state)))
            assertEquals("", resolvedMeshDraft("same body", submission, snapshot))
            assertEquals("edited draft", resolvedMeshDraft("edited draft", submission, snapshot))
        }
    }

    @Test fun onlyMatchingRejectionOrStoppedRuntimeReleasesPendingDraft() {
        assertNull(resolvedMeshDraft("draft", submission, MeshSnapshot(running = true,
            error = "mesh_command_busy", rejectedRequest = "s1")))
        assertEquals("draft", resolvedMeshDraft("draft", submission, MeshSnapshot(running = true,
            rejectedRequest = "s2")))
        assertEquals("draft", resolvedMeshDraft("draft", submission, MeshSnapshot()))
    }
}

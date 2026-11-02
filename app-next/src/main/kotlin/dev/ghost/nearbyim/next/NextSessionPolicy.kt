package dev.ghost.nearbyim.next

import dev.ghost.nearbyim.core.TextLimits

data class AttachmentSelectionSession(val peer: String, val token: Long, val identity: String) {
    fun accepts(currentPeer: String?, currentToken: Long, currentIdentity: String?, canSend: Boolean): Boolean =
        canSend && peer == currentPeer && token == currentToken && identity == currentIdentity
}

fun draftAfterSave(currentPeer: String?, currentText: String, savedPeer: String?, savedText: String?): String =
    if (currentPeer == savedPeer && currentText == savedText) "" else currentText

fun canSubmitText(text: String, byteLimit: Int, connected: Boolean, loading: Boolean): Boolean =
    connected && !loading && TextLimits.canSend(text, byteLimit)

fun shouldHandleSave(currentPeer: String?, savedPeer: String?, loading: Boolean): Boolean =
    !loading || currentPeer != savedPeer

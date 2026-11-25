package dev.ghost.nearbyim.next

import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.core.TransferProgress
import dev.ghost.nearbyim.core.IdentityVerification
import dev.ghost.nearbyim.core.TextLimits
import dev.ghost.nearbyim.transport.Peer

internal val ChatStore.Message.timelineKey: String
    get() = "$id:$outgoing"

data class NextState(
    val ready: Boolean = false,
    val nickname: String = "",
    val conversations: List<ChatStore.Conversation> = emptyList(),
    val trusted: List<ChatStore.TrustedDevice> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val messages: List<ChatStore.Message> = emptyList(),
    val transferMeasurements: Map<String, TransferProgress> = emptyMap(),
    val selectedId: String? = null,
    val selectedName: String = "",
    val connectedId: String? = null,
    val connected: Boolean = false,
    val connectedMode: Int? = null,
    val connecting: Boolean = false,
    val status: String = "",
    val error: String = "",
    val lanRunning: Boolean = false,
    val bluetoothRunning: Boolean = false,
    val lanSearching: Boolean = false,
    val bluetoothSearching: Boolean = false,
    val lanInfo: String = "",
    val bluetoothInfo: String = "",
    val bluetoothAvailable: Boolean = true,
    val openedPrivateChat: String? = null,
    val connectingSelected: Boolean = false,
    val lanAutoReception: Boolean = false,
    val bluetoothAutoReception: Boolean = false,
    val lanListening: Boolean = false,
    val bluetoothListening: Boolean = false,
    val canConnectOnSend: Boolean = false,
    val sendingAfterConnect: Boolean = false,
    val waitingForIdleSession: Boolean = false,
    val canSwitchPrivateSession: Boolean = true,
    val privateRetryPaused: Boolean = false,
    val approvalId: String? = null,
    val approvalName: String = "",
    val canSend: Boolean = false,
    val canAttach: Boolean = false,
    val saving: Boolean = false,
    val textLimit: Int = TextLimits.LEGACY_BYTES,
    val draft: String = "",
    val draftLoading: Boolean = false,
    val notice: String = "",
    val readReceipts: Boolean = true,
    val encrypted: Boolean = false,
    val verification: IdentityVerification? = null,
    val identityBusy: Boolean = false,
    val receiveFolder: String = "",
    val revision: Long = 0,
) {
    internal fun withoutService(): NextState = NextState(
        nickname = nickname,
        conversations = conversations,
        trusted = trusted,
        messages = messages,
        selectedId = selectedId,
        selectedName = selectedName,
        bluetoothAvailable = bluetoothAvailable,
        lanAutoReception = lanAutoReception,
        bluetoothAutoReception = bluetoothAutoReception,
        draft = draft,
        draftLoading = draftLoading,
        notice = notice,
        readReceipts = readReceipts,
        receiveFolder = receiveFolder,
    )
}

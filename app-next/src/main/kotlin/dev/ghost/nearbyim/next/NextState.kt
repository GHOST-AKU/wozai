package dev.ghost.nearbyim.next

import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.core.IdentityVerification
import dev.ghost.nearbyim.transport.Peer

data class NextState(
    val ready: Boolean = false,
    val nickname: String = "",
    val localId: String = "",
    val conversations: List<ChatStore.Conversation> = emptyList(),
    val trusted: List<ChatStore.TrustedDevice> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val messages: List<ChatStore.Message> = emptyList(),
    val selectedId: String? = null,
    val selectedName: String = "",
    val connectedId: String? = null,
    val connected: Boolean = false,
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
    val approvalId: String? = null,
    val approvalName: String = "",
    val canSend: Boolean = false,
    val canAttach: Boolean = false,
    val saving: Boolean = false,
    val textLimit: Int = 8192,
    val draft: String = "",
    val draftLoading: Boolean = false,
    val notice: String = "",
    val readReceipts: Boolean = true,
    val encrypted: Boolean = false,
    val verification: IdentityVerification? = null,
    val receiveFolder: String = "",
    val revision: Long = 0,
)

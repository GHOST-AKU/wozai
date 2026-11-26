package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.transport.Peer

@Composable
internal fun NetworkStatusButton(
    state: NextState?,
    meshDeviceCount: Int?,
    onClick: () -> Unit,
    peerId: String? = null,
) {
    val connectedMode = state?.takeIf { it.connected && (peerId == null || it.connectedId == peerId) }?.connectedMode
    val connecting = if (peerId == null) state?.connecting == true else state?.connectingSelected == true
    val privateStatus = when (connectedMode) {
        Peer.BLUETOOTH -> t("bluetooth") + " " + t("connectedIndicator")
        Peer.LAN -> t("lan") + " " + t("connectedIndicator")
        Peer.WIFI_DIRECT -> t("wifiDirect") + " " + t("connectedIndicator")
        else -> t(if (connecting) "connecting" else "idle")
    }
    val meshStatus = meshDeviceCount?.let { stringResource(R.string.mesh_neighbors, it) }
    val label = stringResource(R.string.mesh_private_devices)
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag("network-status").semantics {
            contentDescription = label
            stateDescription = if (meshStatus == null) privateStatus else "$privateStatus · $meshStatus"
        },
    ) {
        BadgedBox(badge = {
            if (meshDeviceCount != null) {
                Badge(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                    Text(meshDeviceCount.toString(), Modifier.clearAndSetSemantics {})
                }
            }
        }) {
            if (connecting && connectedMode == null) {
                CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
            } else {
                CompositionLocalProvider(LocalContentColor provides if (connectedMode == null) scheme.onSurfaceVariant else scheme.primary) {
                    AppIcon(when (connectedMode) {
                        Peer.BLUETOOTH -> R.drawable.outline_bluetooth_24
                        Peer.LAN, Peer.WIFI_DIRECT -> R.drawable.outline_wifi_24
                        else -> R.drawable.outline_wifi_tethering_24
                    })
                }
            }
        }
    }
}

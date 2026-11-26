package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.transport.Peer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NearbyScreen(state: NextState, host: MainActivity, showTitle: Boolean = true) {
    var mode by rememberSaveable { mutableIntStateOf(Peer.LAN) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var rememberDevice by rememberSaveable { mutableStateOf(true) }
    val listening = if (mode == Peer.LAN) state.lanListening else state.bluetoothListening
    val automatic = if (mode == Peer.LAN) state.lanAutoReception else state.bluetoothAutoReception
    val running = if (mode == Peer.LAN) state.lanRunning else state.bluetoothRunning
    val searching = if (mode == Peer.LAN) state.lanSearching else state.bluetoothSearching
    val available = mode == Peer.LAN || state.bluetoothAvailable
    val peers = state.peers.filter { it.mode == mode }
    LaunchedEffect(mode, automatic, listening) {
        if (automatic && listening && host.discoverPrivateDevices(mode)) {
            try { delay(15_000) }
            finally { host.stopPrivatePickerSearch(mode) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            if (showTitle) MainPageTitle(stringResource(R.string.mesh_private_devices))
            else Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                listOf(Peer.LAN to "lan", Peer.BLUETOOTH to "bluetooth").forEachIndexed { index, (value, key) ->
                    SegmentedButton(selected = mode == value, onClick = { mode = value },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(t(key)) }
                }
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(when {
                        automatic && listening -> R.string.next_private_reception_active
                        automatic && running -> R.string.next_private_reception_starting
                        automatic -> R.string.next_private_reception_waiting
                        else -> R.string.next_private_reception
                    }))
                },
                supportingContent = {
                    Text(stringResource(if (automatic && listening) R.string.next_private_reception_known
                        else R.string.next_private_reception_hint))
                },
                trailingContent = {
                    Switch(automatic, onCheckedChange = { if (it) host.start(mode) else host.stop(mode) },
                        enabled = state.ready && (available || automatic))
                },
            )
            if (!automatic && running) TextButton(onClick = { host.stop(mode) },
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(t("stopReception")) }
            if (automatic && !running) TextButton(onClick = { host.start(mode) }, enabled = state.ready && available,
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(t("reconnect")) }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (searching) host.stopSearch(mode) else host.search(mode) },
                    enabled = state.ready && !state.connecting && available) {
                    Text(t(if (searching) "stopSearch" else "searchDevices"))
                }
                if (mode == Peer.LAN) TextButton(onClick = { manual = true }, enabled = state.ready) {
                    Text(t("direct"))
                } else TextButton(onClick = { host.discoverable() }, enabled = state.ready && available) {
                    Text(t("allowDiscoverable"))
                }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            if (state.connecting && state.approvalId == null) ListItem(headlineContent = { Text(state.status) },
                trailingContent = { TextButton(onClick = host::disconnect) { Text(t("cancelConnection")) } })
            SectionLabel(t("discovered"))
            if (peers.isNotEmpty()) Text(stringResource(R.string.next_private_pick_device),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (peers.isEmpty()) item {
            EmptyState(R.drawable.outline_wifi_tethering_24, t(if (searching) "searchingNearby" else "noDevicesTitle"),
                t(if (mode == Peer.LAN) "lanNoDevicesBody" else "bluetoothNoDevicesBody"))
        }
        items(peers, key = { it.key }) { peer ->
            val name = peer.name?.takeIf { it.isNotBlank() } ?: t("unnamedDevice")
            ListItem(leadingContent = { InitialAvatar(name) }, headlineContent = { Text(name) },
                supportingContent = { Text(peer.detail ?: "", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { TextButton(onClick = { host.connect(peer, true) }, enabled = !state.connecting) {
                    Text(stringResource(R.string.next_private_start_request))
                } })
        }
    }
    if (manual) AlertDialog(onDismissRequest = { manual = false },
        title = { Text(t("direct")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(address, { address = it }, singleLine = true,
                    label = { Text(t("androidDirectBody")) }, placeholder = { Text("192.168.1.20:54321") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberDevice, { rememberDevice = it })
                    Text(t("rememberConnectedDevice"))
                }
            }
        },
        confirmButton = { TextButton(enabled = address.isNotBlank() && !state.connecting, onClick = {
            host.connectAddress(address, rememberDevice)
            manual = false
        }) { Text(stringResource(R.string.next_private_start_request)) } },
        dismissButton = { TextButton(onClick = { manual = false }) { Text(t("cancel")) } },
    )
}

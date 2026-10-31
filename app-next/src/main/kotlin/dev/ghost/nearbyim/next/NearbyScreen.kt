package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.transport.Peer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(state: NextState, host: MainActivity) {
    var mode by rememberSaveable { mutableIntStateOf(Peer.LAN) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var rememberDevice by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Peer?>(null) }
    val running = if (mode == Peer.LAN) state.lanRunning else state.bluetoothRunning
    val searching = if (mode == Peer.LAN) state.lanSearching else state.bluetoothSearching
    val info = if (mode == Peer.LAN) state.lanInfo else state.bluetoothInfo
    val peers = state.peers.filter { it.mode == mode }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Text(t("nearby"), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                listOf(Peer.LAN to "lan", Peer.BLUETOOTH to "bluetooth").forEachIndexed { index, (value, key) ->
                    SegmentedButton(selected = mode == value, onClick = { mode = value },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(t(key)) }
                }
            }
            Card(Modifier.fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(if (mode == Peer.LAN) R.drawable.outline_wifi_24 else R.drawable.outline_bluetooth_24)
                        Column(Modifier.weight(1f)) {
                            Text(t(if (running) "receiving" else "notListening"), style = MaterialTheme.typography.titleMedium)
                            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(running, onCheckedChange = { if (it) host.start(mode) else host.stop(mode) },
                            enabled = state.ready && (mode == Peer.LAN || state.bluetoothAvailable))
                    }
                    Text(t(if (mode == Peer.LAN) "lanNoDevicesBody" else "androidBluetoothHint"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { if (searching) host.stopSearch(mode) else host.search(mode) },
                        enabled = state.ready && !state.connecting && (mode == Peer.LAN || state.bluetoothAvailable),
                        modifier = Modifier.fillMaxWidth()) {
                        AppIcon(if (searching) R.drawable.outline_close_24 else R.drawable.outline_search_24)
                        Spacer(Modifier.width(8.dp)); Text(t(if (searching) "stopSearch" else "searchDevices"))
                    }
                    if (mode == Peer.LAN) TextButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth(), enabled = state.ready) {
                        Text(t("direct"))
                    } else TextButton(onClick = { host.discoverable() }, modifier = Modifier.fillMaxWidth(), enabled = state.ready && state.bluetoothAvailable) {
                        Text(t("allowDiscoverable"))
                    }
                }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            if (state.connecting) ListItem(headlineContent = { Text(state.status) },
                trailingContent = { TextButton(onClick = host::disconnect) { Text(t("cancelConnection")) } })
            SectionLabel(t("discovered"))
        }
        if (peers.isEmpty()) item {
            EmptyState(R.drawable.outline_wifi_tethering_24, t(if (searching) "searchingNearby" else "noDevicesTitle"),
                t(if (mode == Peer.LAN) "lanNoDevicesBody" else "bluetoothNoDevicesBody"))
        }
        items(peers, key = { it.key }) { peer ->
            val name = peer.name?.takeIf { it.isNotBlank() } ?: t("unnamedDevice")
            ListItem(leadingContent = { InitialAvatar(name) }, headlineContent = { Text(name) },
                supportingContent = { Text(peer.detail ?: "", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { TextButton(onClick = { selected = peer }, enabled = !state.connecting) { Text(t("reconnect")) } })
        }
    }
    if (manual || selected != null) {
        val peer = selected
        AlertDialog(onDismissRequest = { manual = false; selected = null },
            title = { Text(if (manual) t("direct") else peer?.name?.takeIf { it.isNotBlank() } ?: t("unnamedDevice")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (manual) OutlinedTextField(address, { address = it }, singleLine = true,
                        label = { Text(t("androidDirectBody")) }, placeholder = { Text("192.168.1.20:54321") })
                    if (state.connected || state.connecting) Text(t("connectionSwitchBody"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberDevice, { rememberDevice = it })
                        Text(t("rememberConnectedDevice"))
                    }
                }
            },
            confirmButton = { TextButton(enabled = !manual || address.isNotBlank(), onClick = {
                if (state.connected || state.connecting) host.disconnect()
                if (manual) host.connectAddress(address, rememberDevice) else peer?.let { host.connect(it, rememberDevice) }
                manual = false; selected = null
            }) { Text(t(if (state.connected) "disconnectAndConnect" else "reconnect")) } },
            dismissButton = { TextButton(onClick = { manual = false; selected = null }) { Text(t("cancel")) } },
        )
    }
}

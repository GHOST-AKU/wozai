package dev.ghost.nearbyim.next

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.mesh.MeshDiagnostics
import dev.ghost.nearbyim.mesh.MeshClient

/** UI observes the independent service; it never owns radio links or NIM4 sessions. */
@Composable
fun MeshNearbyScreen(privateState: NextState, host: MainActivity) {
    var privateDevices by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (privateDevices) {
            TextButton(onClick = { privateDevices = false }) { Text(stringResource(R.string.mesh_back_public)) }
            NearbyScreen(privateState, host)
        } else MeshScreen(onPrivate = { privateDevices = true })
    }
}

private fun radioPermissions(mode: String): List<String> = when {
    mode == "ble" && Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    mode == "ble" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
private fun MeshScreen(onPrivate: () -> Unit) {
    val context = LocalContext.current
    val state by MeshClient.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("ble") }
    var relay by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    var bluetoothError by remember { mutableStateOf(false) }
    var requestedText by rememberSaveable { mutableStateOf<String?>(null) }
    var baselineIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingStart by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val required = radioPermissions(mode)
    fun begin() {
        val request = pendingStart ?: return
        pendingStart = null
        MeshClient.start(context, request.first, request.second)
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) begin()
        else { pendingStart = null; bluetoothError = true }
    }
    fun afterPermissions() {
        val request = pendingStart ?: return
        if (request.first == "ble") {
            try {
                val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                if (manager?.adapter?.isEnabled == false) { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
            } catch (_: SecurityException) { permissionError = true; pendingStart = null; return }
        }
        begin()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingStart
        if (request != null && radioPermissions(request.first).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) afterPermissions()
        else { permissionError = true; pendingStart = null }
    }
    LaunchedEffect(state.messages) {
        val pending = requestedText
        if (pending != null && acceptedMeshSend(baselineIds.toSet(), pending, state.messages)) {
            if (draft == pending) draft = ""
            requestedText = null
        }
    }
    LaunchedEffect(state.error, state.running, state.starting) {
        if (state.error.isNotEmpty() || (!state.running && !state.starting)) requestedText = null
    }
    val busy = state.running || state.starting
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mesh_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onPrivate) { Text(stringResource(R.string.mesh_private_devices)) }
        }
        Text(stringResource(R.string.mesh_preview), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("ble" to R.string.mesh_ble, "aware" to R.string.mesh_aware).forEachIndexed { index, (value, text) ->
                SegmentedButton(selected = (if (busy) state.mode else mode) == value,
                    onClick = { mode = value; permissionError = false; bluetoothError = false }, enabled = !busy,
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(stringResource(text)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(when { state.starting -> R.string.mesh_starting; state.running -> R.string.mesh_running; else -> R.string.mesh_idle }))
                Text(stringResource(R.string.mesh_neighbors, state.neighbors.size), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                if (busy) { MeshClient.stop(context); requestedText = null }
                else consent = true
            }) { Text(stringResource(if (busy) R.string.mesh_stop else R.string.mesh_start)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mesh_relay), Modifier.weight(1f))
            Switch(checked = if (busy) state.relay else relay, enabled = !state.starting,
                onCheckedChange = { relay = it; if (state.running) MeshClient.setRelay(context, it) })
        }
        Text(stringResource(R.string.mesh_relay_hint), style = MaterialTheme.typography.labelSmall)
        if (permissionError || bluetoothError || state.error.isNotEmpty()) {
            val error = when {
                permissionError || state.error == "mesh_permission_required" -> stringResource(R.string.mesh_permission)
                bluetoothError -> stringResource(R.string.mesh_enable_bt)
                state.error == "mesh_location_required" -> stringResource(R.string.mesh_location)
                state.error.startsWith("mesh_unsupported") || state.error == "mesh_core_missing" -> stringResource(R.string.mesh_unavailable)
                else -> stringResource(R.string.mesh_error_generic)
            }
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (state.error == "mesh_location_required") TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) { Text(stringResource(R.string.mesh_location_settings)) }
        }
            } }
            if (state.messages.isEmpty()) item {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    Column { Text(stringResource(R.string.mesh_empty)); Text(stringResource(R.string.mesh_empty_hint), style = MaterialTheme.typography.bodySmall) }
                }
            } else {
            items(state.messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (message.own) stringResource(R.string.mesh_own) else message.origin.take(12), style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(message.body) }
                        val status = when (message.state) {
                            "queued" -> R.string.mesh_queued
                            "handed" -> R.string.mesh_handed
                            "expired" -> R.string.mesh_expired
                            "received" -> R.string.mesh_received
                            "stopped_unconfirmed" -> R.string.mesh_stopped
                            else -> R.string.mesh_unknown
                        }
                        Text(stringResource(status) + " · " + stringResource(R.string.mesh_hops, message.hops), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            }
        }
        val bytes = draft.toByteArray(Charsets.UTF_8).size
        OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) draft = it },
            label = { Text(stringResource(R.string.mesh_input)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
            isError = bytes > 2048, supportingText = { Text("$bytes / 2048") })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { diagnosticReport = MeshDiagnostics.report(context, state); diagnostics = true }, Modifier.weight(1f)) { Text(stringResource(R.string.mesh_details)) }
            Button(enabled = state.running && requestedText == null && draft.isNotBlank() && bytes <= 2048,
                onClick = { baselineIds = ArrayList(state.messages.filter { it.own }.map { it.id }); requestedText = draft; MeshClient.send(context, draft) }) { Text(stringResource(R.string.mesh_send)) }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text(stringResource(R.string.mesh_confirm_title)) },
        text = { Text(stringResource(R.string.mesh_public_notice)) },
        confirmButton = { TextButton(onClick = {
            consent = false; permissionError = false; bluetoothError = false; pendingStart = mode to relay
            val missing = required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
            if (missing.isEmpty()) afterPermissions() else permissions.launch(missing.toTypedArray())
        }) { Text(stringResource(R.string.mesh_confirm)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.mesh_cancel)) } })
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text(stringResource(R.string.mesh_details)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(stringResource(R.string.mesh_diagnostics_hint)); SelectionContainer { Text(diagnosticReport) } } },
        dismissButton = { TextButton(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, diagnosticReport)
            }, null))
        }) { Text(stringResource(R.string.mesh_share_diagnostics)) } },
        confirmButton = { TextButton(onClick = { diagnostics = false }) { Text(stringResource(R.string.mesh_close)) } })
}

package dev.ghost.nearbyim.next

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.ghost.nearbyim.mesh.MeshCapabilities
import dev.ghost.nearbyim.mesh.MeshConsent

/** UI observes the independent service; it never owns radio links or NIM4 sessions. */
@Composable
internal fun MeshNearbyScreen(privateState: NextState, host: MainActivity, composer: MeshComposer) {
    var privateDevices by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        androidx.activity.compose.BackHandler(privateDevices) { privateDevices = false }
        if (privateDevices) {
            TextButton(onClick = { privateDevices = false }) { Text(stringResource(R.string.mesh_back_public)) }
            NearbyScreen(privateState, host)
        } else MeshScreen(composer, onPrivate = { privateDevices = true })
    }
}

private fun radioPermissions(mode: String): List<String> = when {
    mode == "ble" && Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    mode == "ble" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshScreen(composer: MeshComposer, onPrivate: () -> Unit) {
    val context = LocalContext.current
    val state by MeshClient.state.collectAsStateWithLifecycle()
    val capabilities = remember(context) { MeshCapabilities.read(context) }
    var mode by rememberSaveable { mutableStateOf(
        MeshConsent.selectedMode(context)?.takeIf(capabilities::supports) ?: capabilities.defaultMode ?: "ble")
    }
    var options by rememberSaveable { mutableStateOf(false) }
    var historyRules by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    var bluetoothError by remember { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    val exportScope = rememberCoroutineScope()
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val saveDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = pendingExport
        pendingExport = null
        if (uri != null && report != null) {
            exportScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw java.io.IOException("document_unavailable")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                        true
                    } catch (_: java.io.IOException) { false }
                    catch (_: SecurityException) { false }
                }
                exporting = false
                Toast.makeText(context, if (saved) R.string.mesh_export_saved else R.string.mesh_export_failed, Toast.LENGTH_LONG).show()
            }
        } else exporting = false
    }
    val required = radioPermissions(mode)
    fun begin() {
        val request = pendingStart ?: return
        pendingStart = null
        MeshConsent.enable(context, request)
        MeshClient.start(context, request, false)
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) begin()
        else { pendingStart = null; bluetoothError = true }
    }
    fun afterPermissions() {
        val request = pendingStart ?: return
        if (request == "ble") {
            try {
                val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                if (manager?.adapter?.isEnabled == false) { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
            } catch (_: SecurityException) { permissionError = true; pendingStart = null; return }
        }
        begin()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingStart
        if (request != null && radioPermissions(request).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) afterPermissions()
        else { permissionError = true; pendingStart = null }
    }
    LaunchedEffect(state.messages) {
        val pending = composer.pending
        if (pending != null && acceptedMeshSend(composer.baselineIds.toSet(), pending, state.messages)) {
            if (composer.text == pending) composer.text = ""
            composer.pending = null
        }
    }
    LaunchedEffect(state.error, state.running, state.starting) {
        if (state.error.isNotEmpty() || (!state.running && !state.starting)) composer.pending = null
    }
    val busy = state.running || state.starting
    val automatic = MeshConsent.automaticMode(context) != null
    fun requestStart() {
        permissionError = false
        bluetoothError = false
        pendingStart = mode
        val missing = required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) afterPermissions() else permissions.launch(missing.toTypedArray())
    }
    Column(Modifier.fillMaxSize()) {
        val error = when {
            permissionError || state.error == "mesh_permission_required" -> R.string.mesh_permission
            bluetoothError -> R.string.mesh_enable_bt
            state.error == "mesh_location_required" -> R.string.mesh_location
            state.error == "mesh_unsupported_platform" -> R.string.mesh_platform_unsupported
            state.error.startsWith("mesh_unsupported") || state.error == "mesh_core_missing" -> R.string.mesh_unavailable
            state.error == "mesh_send_invalid" -> R.string.mesh_too_long
            state.error in listOf("mesh_send_rejected", "mesh_command_busy") -> R.string.mesh_send_busy
            state.error == "mesh_radio_failed" -> R.string.mesh_temporarily_unavailable
            state.error.isNotEmpty() -> R.string.mesh_error_generic
            !busy && capabilities.modes.isEmpty() -> if (capabilities.platformSupported) R.string.mesh_unavailable else R.string.mesh_platform_unsupported
            else -> null
        }
        if (error != null) {
            Text(stringResource(error), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (permissionError || state.error == "mesh_permission_required") TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.packageName)))
            }) { Text(stringResource(R.string.mesh_open_settings)) }
            if (state.error == "mesh_location_required") TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) { Text(stringResource(R.string.mesh_location_settings)) }
        }
        MeshChatContent(state, composer.text, composer.pending != null,
            onDraft = { composer.text = it },
            onSend = {
                composer.baselineIds = ArrayList(state.messages.filter { it.own }.map { it.id })
                composer.pending = composer.text
                MeshClient.send(context, composer.text)
            },
            onToggle = {
                if (busy || automatic) { pendingStart = null; MeshClient.stop(context); composer.pending = null }
                else if (MeshConsent.acknowledged(context)) requestStart()
                else consent = true
            },
            onOptions = { options = true }, onPrivate = onPrivate,
            canStart = capabilities.supports(mode) && pendingStart == null, automatic = automatic)
    }
    if (historyRules) AlertDialog(onDismissRequest = { historyRules = false },
        title = { Text(stringResource(R.string.mesh_history_short)) },
        text = { Text(stringResource(R.string.mesh_history_rule), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { historyRules = false }) { Text(stringResource(R.string.mesh_close)) } })
    if (consent) MeshConsentDialog(onDismiss = { consent = false }, onConfirm = {
        consent = false
        MeshConsent.acknowledge(context)
        requestStart()
    })
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mesh_options), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.mesh_transport), style = MaterialTheme.typography.titleMedium)
            if (!capabilities.platformSupported) Text(stringResource(R.string.mesh_platform_unsupported))
            listOf("ble" to R.string.mesh_ble, "aware" to R.string.mesh_aware).forEach { (value, label) ->
                if (capabilities.supports(value)) FilterChip(
                    selected = (if (busy) state.mode else mode) == value,
                    enabled = !busy && pendingStart == null,
                    onClick = { mode = value; permissionError = false; bluetoothError = false },
                    label = { Text(stringResource(label)) })
                else if (capabilities.platformSupported) Text(stringResource(R.string.mesh_mode_unsupported, stringResource(label)),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(if ((if (busy) state.mode else mode) == "ble") R.string.mesh_ble_hint else R.string.mesh_aware_hint),
                style = MaterialTheme.typography.bodySmall)
            if (state.running && state.mode == "ble") TextButton(onClick = {
                try { discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) }
                catch (_: RuntimeException) { bluetoothError = true }
            }) { Text(stringResource(R.string.mesh_visible)) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mesh_relay))
                    Text(stringResource(R.string.mesh_relay_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.relay, enabled = state.running,
                    onCheckedChange = { MeshClient.setRelay(context, it) })
            }
            Text(stringResource(R.string.mesh_neighbors, state.neighbors.size), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mesh_public_notice), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mesh_receive_rule), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { options = false; historyRules = true }) {
                Text(stringResource(R.string.mesh_history_short))
            }
            TextButton(onClick = { options = false; diagnosticReport = MeshDiagnostics.report(context, state); diagnostics = true }) {
                Text(stringResource(R.string.mesh_details))
            }
        }
    }
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text(stringResource(R.string.mesh_details)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(stringResource(R.string.mesh_diagnostics_hint)); SelectionContainer { Text(diagnosticReport) } } },
        dismissButton = { TextButton(onClick = {
            pendingExport = diagnosticReport
            exporting = true
            saveDiagnostics.launch("nearby-mesh-${System.currentTimeMillis()}.txt")
        }, enabled = !exporting && pendingExport == null) { Text(stringResource(R.string.mesh_export_diagnostics)) } },
        confirmButton = { TextButton(onClick = { diagnostics = false }) { Text(stringResource(R.string.mesh_close)) } })
}

@Composable
internal fun MeshConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mesh_confirm_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.mesh_public_notice))
                Text(stringResource(R.string.mesh_receive_rule))
                Text(stringResource(R.string.mesh_history_rule))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.mesh_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mesh_cancel)) } })
}

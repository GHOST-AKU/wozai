package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.mesh.MeshSnapshot

/** Chat presentation contains no radio or service ownership. */
@Composable
internal fun MeshChatContent(
    state: MeshSnapshot, draft: String, sending: Boolean,
    onDraft: (String) -> Unit, onSend: () -> Unit, onToggle: () -> Unit,
    onOptions: () -> Unit, onPrivate: () -> Unit,
    canStart: Boolean = true, automatic: Boolean = false,
) {
    val busy = state.running || state.starting
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mesh_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onPrivate) { Text(stringResource(R.string.mesh_private_devices)) }
            IconButton(onClick = onOptions) { AppIcon(R.drawable.outline_more_vert_24, stringResource(R.string.mesh_options)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when {
                    state.starting -> stringResource(R.string.mesh_starting)
                    state.running && state.neighbors.isNotEmpty() -> stringResource(R.string.mesh_connected, state.neighbors.size)
                    state.running -> stringResource(R.string.mesh_running)
                    automatic -> stringResource(R.string.mesh_waiting_restore)
                    else -> stringResource(R.string.mesh_idle)
                }, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (state.relay) R.string.mesh_relay_on else R.string.mesh_public_short),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onToggle, enabled = busy || automatic || canStart) {
                Text(stringResource(if (busy || automatic) R.string.mesh_stop else R.string.mesh_start))
            }
        }
        if (state.messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mesh_empty))
                Text(stringResource(R.string.mesh_empty_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.messages.asReversed(), key = { it.id }) { message ->
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (message.own) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(Modifier.widthIn(max = minOf(520.dp, maxWidth * 0.9f)),
                        colors = CardDefaults.cardColors(containerColor = if (message.own)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        val bytes = remember(draft) { draft.toByteArray(Charsets.UTF_8).size }
        OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) onDraft(it) },
            label = { Text(stringResource(R.string.mesh_input)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
            isError = bytes > 2048,
            supportingText = if (bytes > 1800) { { Text(if (bytes > 2048) stringResource(R.string.mesh_too_long) else "$bytes / 2048") } } else null,
            trailingIcon = {
                IconButton(enabled = state.running && !sending && draft.isNotBlank() && bytes <= 2048, onClick = onSend) {
                    AppIcon(R.drawable.outline_send_24, stringResource(R.string.mesh_send))
                }
            })
        Spacer(Modifier.height(4.dp))
    }
}

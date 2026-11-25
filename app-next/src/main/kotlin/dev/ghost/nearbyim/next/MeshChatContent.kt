package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.mesh.MeshSnapshot
import dev.ghost.nearbyim.mesh.MeshText

/** Chat presentation contains no radio or service ownership. */
@Composable
internal fun MeshChatContent(
    state: MeshSnapshot, draft: String, sending: Boolean,
    onDraft: (String) -> Unit, onSend: () -> Unit, onToggle: () -> Unit,
    onOptions: () -> Unit, onPrivate: () -> Unit,
    canStart: Boolean = true, automatic: Boolean = false,
    bodyLimit: Int = MeshText.MAX_BODY,
    privateState: NextState? = null,
) {
    val busy = state.running || state.starting
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainPageTitle(
                stringResource(R.string.mesh_title),
                Modifier.weight(1f).align(Alignment.Top).testTag("nearby-title"),
            )
            NetworkStatusButton(privateState, state.directDeviceCount, onPrivate)
            IconButton(onClick = onOptions) {
                AppIcon(R.drawable.outline_more_vert_24, stringResource(R.string.mesh_options))
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                                Text(
                                    if (message.own) stringResource(R.string.mesh_own) else message.nickname ?: message.origin.take(12),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextMessageBody(message.body)
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
                label = { Text(stringResource(R.string.mesh_input)) },
                modifier = Modifier.fillMaxWidth(), maxLines = 4,
                isError = bytes > bodyLimit,
                supportingText = if (bytes > bodyLimit - 248) {
                    { Text(if (bytes > bodyLimit) stringResource(R.string.mesh_too_long, bodyLimit) else "$bytes / $bodyLimit") }
                } else null,
                trailingIcon = {
                    IconButton(enabled = state.running && !sending && draft.isNotBlank() && bytes <= bodyLimit, onClick = onSend) {
                        AppIcon(R.drawable.outline_send_24, stringResource(R.string.mesh_send))
                    }
                })
            Spacer(Modifier.height(4.dp))
        }
    }
}

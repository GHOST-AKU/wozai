package dev.ghost.nearbyim.next

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AndroidText
import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    state: NextState,
    host: MainActivity,
    currentPane: String?,
    onSelect: (ChatStore.Conversation) -> Unit,
    meshDeviceCount: Int?,
    onOpenPrivateDevices: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val conversations = remember(state.conversations, query) {
        state.conversations.filter { it.name.contains(query, true) || it.preview.contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MainPageTitle(t("app"), Modifier.weight(1f).align(Alignment.Top))
            NetworkStatusButton(state, meshDeviceCount, onOpenPrivateDevices)
        }
        DockedSearchBar(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            inputField = {
                SearchBarDefaults.InputField(
                    query = query, onQueryChange = { query = it }, onSearch = { focus.clearFocus() },
                    expanded = false, onExpandedChange = {},
                    placeholder = { Text(t("searchChats")) },
                    leadingIcon = { AppIcon(R.drawable.outline_search_24) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            AppIcon(R.drawable.outline_close_24, t("clearSearch"))
                        }
                    },
                )
            },
            expanded = false, onExpandedChange = {},
            colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {}
        Spacer(Modifier.height(8.dp))
        if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (conversations.isEmpty()) {
                EmptyState(R.drawable.outline_chat_bubble_24,
                    t(if (query.isEmpty()) "noChatsTitle" else "noSearchResultsTitle"),
                    t(if (query.isEmpty()) "noChatsBody" else "noSearchResultsBody"),
                    Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation, host, currentPane,
                            connected = state.connected && state.connectedId == conversation.id,
                            onClick = { onSelect(conversation) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ChatStore.Conversation,
    host: MainActivity,
    currentPane: String?,
    connected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val selected = currentPane == conversation.id
    val interaction = if (currentPane == null) Modifier.clickable(onClick = onClick)
        else Modifier.selectable(selected = selected, onClick = onClick)
    val preview = when {
        conversation.preview.isBlank() -> t("noMessageTitle")
        conversation.outgoing && conversation.state.isNotEmpty() ->
            t("chatPreviewState", AndroidText.messageState(context, conversation.state), conversation.preview)
        else -> conversation.preview
    }
    ListItem(
        modifier = interaction.testTag("conversation-${conversation.id}"),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surface),
        leadingContent = { DeviceAvatar(host.avatarRoot(conversation.id), conversation.name) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conversation.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium)
                if (conversation.time > 0) {
                    val format = if (DateUtils.isToday(conversation.time)) "jm" else "MMMd"
                    Text(AndroidText.date(context, conversation.time, format), maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (connected) Text(t("connectedIndicator"), maxLines = 1, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

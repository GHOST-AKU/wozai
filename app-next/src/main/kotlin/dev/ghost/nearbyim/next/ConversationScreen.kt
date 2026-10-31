package dev.ghost.nearbyim.next

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AndroidText
import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.MainActivity
import androidx.compose.ui.platform.LocalContext

@Composable
fun ConversationScreen(state: NextState, host: MainActivity, onSelect: (ChatStore.Conversation) -> Unit, onNearby: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val conversations = state.conversations.filter { it.name.contains(query, true) || it.preview.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("我在next", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(t("chats"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalIconButton(onClick = onNearby) { AppIcon(R.drawable.outline_add_24, t("newChat")) }
        }
        OutlinedTextField(query, { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp), placeholder = { Text(t("searchChats")) },
            leadingIcon = { AppIcon(R.drawable.outline_search_24) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { AppIcon(R.drawable.outline_close_24, t("clearSearch")) } })
        Spacer(Modifier.height(16.dp))
        if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if (conversations.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EmptyState(R.drawable.outline_chat_bubble_24,
                t(if (query.isEmpty()) "noChatsTitle" else "noSearchResultsTitle"),
                t(if (query.isEmpty()) "noChatsBody" else "noSearchResultsBody"),
                action = { if (query.isEmpty()) Button(onClick = onNearby) { Text(t("searchDevices")) } })
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                val online = state.connected && state.connectedId == conversation.id
                ListItem(
                    modifier = Modifier.clickable { onSelect(conversation) },
                    colors = ListItemDefaults.colors(containerColor = if (state.selectedId == conversation.id)
                        MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface),
                    leadingContent = { DeviceAvatar(host.avatarRoot(conversation.id), conversation.name) },
                    headlineContent = { Text(conversation.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(conversation.preview.ifEmpty { AndroidText.get(context, "firstMessageHint") },
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(AndroidText.date(context, conversation.time, "MMMd"), style = MaterialTheme.typography.labelSmall)
                            if (online) AppIcon(R.drawable.outline_wifi_24, t("connectedIndicator"), Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
}

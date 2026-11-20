package dev.ghost.nearbyim.next

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R

@Composable
fun NextApp(state: NextState, host: MainActivity, contentWindowInsets: WindowInsets = WindowInsets.safeDrawing) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf(false) }
    val publicComposer = rememberMeshComposer()
    val preferences = remember(host) { host.getSharedPreferences("ui", 0) }
    var split by remember { mutableFloatStateOf(preferences.getFloat("nextPaneSplit", Float.NaN)) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice, state.error) {
        val message = state.notice.ifEmpty { state.error }
        if (message.isNotEmpty()) { snackbar.showSnackbar(message); host.clearNotice() }
    }
    LaunchedEffect(state.connectedId, state.connected) {
        if (state.connected && state.connectedId != null) { page = 0; detail = true }
    }
    Surface(Modifier.fillMaxSize().testTag("next-root"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The IME changes the content height, but must not recategorize the window.
            val widthPx = with(density) { maxWidth.roundToPx() } -
                contentWindowInsets.getLeft(density, direction) - contentWindowInsets.getRight(density, direction)
            val heightPx = with(density) { maxHeight.roundToPx() }
            val widths = remember(widthPx, heightPx, density, direction) {
                PaneWidths(widthPx, heightPx, density.density, density.fontScale, direction == LayoutDirection.Rtl)
            }
            val wide = maxWidth >= 840.dp && widths.dual
            val position = if (wide) widths.divider(split) else 0f
            val listWidth = with(density) {
                ((if (widths.rtl) widthPx - position else position) - widths.railWidth).toDp()
            }
            val showChat = page == 0 && (detail || wide) && state.selectedId != null
            BackHandler(detail) { detail = false }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!wide && !showChat) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        NavigationItems(page) { page = it; detail = false }
                    }
                },
                contentWindowInsets = contentWindowInsets,
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                // Scaffold owns safe drawing (including IME); descendants must not apply it again.
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    Row(Modifier.fillMaxSize()) {
                        if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.width(with(density) { widths.railWidth.toDp() }).fillMaxHeight()) {
                            Spacer(Modifier.height(24.dp))
                            AppIcon(R.drawable.ic_app, "我在next")
                            Spacer(Modifier.height(28.dp))
                            RailItems(page) { page = it; detail = false }
                        }
                        if (!showChat || wide) {
                            Column(if (wide && page == 0) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f)) {
                                when (page) {
                                    0 -> ConversationScreen(state, host, onSelect = { host.select(it); detail = true },
                                        onNearby = { page = 1 })
                                    1 -> MeshNearbyScreen(state, host, publicComposer)
                                    else -> SettingsScreen(state, host)
                                }
                            }
                        }
                        if (showChat) ChatScreen(state, host, Modifier.weight(1f), showBack = !wide, onBack = { detail = false })
                        else if (wide && page == 0) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            EmptyState(R.drawable.outline_chat_bubble_24, "我在next", t("emptyChat"))
                        }
                    }
                    if (wide && page == 0) PaneDivider(widths, position,
                        onMove = { split = widths.fraction(it) },
                        onCommit = {
                            split = widths.fraction(it)
                            preferences.edit().putFloat("nextPaneSplit", split).apply()
                        })
                }
            }
        }
    }
    val token = state.approvalId
    if (token != null) key(token) {
        var remember by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { host.reject(token) },
            icon = { AppIcon(R.drawable.outline_front_hand_24) },
            title = { Text(t("requestNamedTitle", state.approvalName)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(t("privacy"))
                    Row(Modifier.fillMaxWidth().toggleable(remember, onValueChange = { remember = it }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, onCheckedChange = null)
                        Text(t("rememberConnectedDevice"), Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { host.approve(token, remember) }) { Text(t(if (remember) "remember" else "once")) } },
            dismissButton = { TextButton(onClick = { host.reject(token) }) { Text(t("reject")) } },
        )
    }
}

private val navIcons = listOf(R.drawable.outline_chat_bubble_24, R.drawable.outline_wifi_tethering_24, R.drawable.outline_settings_24)
private val navKeys = listOf("chats", "nearby", "settings")

@Composable
private fun RowScope.NavigationItems(page: Int, select: (Int) -> Unit) {
    navKeys.forEachIndexed { index, key ->
        NavigationBarItem(selected = page == index, onClick = { select(index) },
            icon = { AppIcon(navIcons[index]) }, label = { Text(t(key)) })
    }
}

@Composable
private fun RailItems(page: Int, select: (Int) -> Unit) {
    navKeys.forEachIndexed { index, key ->
        NavigationRailItem(selected = page == index, onClick = { select(index) },
            icon = { AppIcon(navIcons[index]) }, label = { Text(t(key)) })
    }
}

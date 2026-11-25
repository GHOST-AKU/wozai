package dev.ghost.nearbyim.next

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.mesh.MeshClient
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextApp(state: NextState, host: MainActivity, contentWindowInsets: WindowInsets = WindowInsets.safeDrawing) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf(false) }
    var showPrivateDevices by rememberSaveable { mutableStateOf(false) }
    var handledPrivateOpening by rememberSaveable { mutableStateOf<String?>(null) }
    val conversations = rememberSaveableStateHolder()
    val publicComposer = rememberMeshComposer()
    val meshDeviceCount by remember { MeshClient.state.map { it.directDeviceCount }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = MeshClient.state.value.directDeviceCount)
    val openPrivateDevices = { showPrivateDevices = true }
    val preferences = remember(host) { host.getSharedPreferences("ui", 0) }
    var split by remember { mutableFloatStateOf(preferences.getFloat("nextPaneSplit", Float.NaN)) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice, state.error) {
        val message = state.notice.ifEmpty { state.error }
        if (message.isNotEmpty()) { snackbar.showSnackbar(message); host.clearNotice() }
    }
    LaunchedEffect(state.openedPrivateChat) {
        val event = state.openedPrivateChat
        if (event != null && handledPrivateOpening != event) {
            handledPrivateOpening = event
            showPrivateDevices = false
            page = 0
            detail = true
        }
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
            val showChat = !showPrivateDevices && page == 0 && (detail || wide) && state.selectedId != null
            val visibleChat = state.selectedId.takeIf { showChat }
            LaunchedEffect(visibleChat) { host.showPrivateConversation(visibleChat) }
            BackHandler(detail || showPrivateDevices) {
                if (showPrivateDevices) showPrivateDevices = false else detail = false
            }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!wide && !showChat && !showPrivateDevices) NavigationBar(
                        modifier = Modifier.testTag("main-navigation"),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ) {
                        NavigationItems(page) { page = it; detail = false }
                    }
                },
                contentWindowInsets = contentWindowInsets,
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                // Scaffold owns safe drawing (including IME); descendants must not apply it again.
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    state.approvalId?.let { token ->
                        PrivateRequestBanner(state.approvalName, { host.reject(token) },
                            { remember -> host.approve(token, remember) })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (showPrivateDevices) Column(Modifier.fillMaxSize()) {
                            TopAppBar(title = { Text(stringResource(R.string.mesh_private_devices)) },
                                navigationIcon = {
                                    IconButton(onClick = { showPrivateDevices = false }) {
                                        AppIcon(R.drawable.outline_arrow_back_24,
                                            if (page == 1) stringResource(R.string.mesh_back_public) else t("backToChats"))
                                    }
                                }, windowInsets = WindowInsets(0))
                            meshDeviceCount?.let {
                                Text(stringResource(R.string.mesh_neighbors, it),
                                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            NearbyScreen(state, host, showTitle = false)
                        } else Row(Modifier.fillMaxSize()) {
                            if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.width(with(density) { widths.railWidth.toDp() }).fillMaxHeight()) {
                                Spacer(Modifier.height(24.dp))
                                RailItems(page) { page = it; detail = false }
                            }
                            if (!showChat || wide) {
                                Column(if (wide && page == 0) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f)) {
                                    when (page) {
                                        0 -> conversations.SaveableStateProvider("conversations") {
                                            ConversationScreen(state, host, currentPane = state.selectedId.takeIf { wide && showChat },
                                                onSelect = { host.select(it); detail = true },
                                                meshDeviceCount = meshDeviceCount, onOpenPrivateDevices = openPrivateDevices)
                                        }
                                        1 -> MeshNearbyScreen(state, publicComposer, onPrivate = openPrivateDevices)
                                        else -> SettingsScreen(state, host)
                                    }
                                }
                            }
                            if (showChat) ChatScreen(state, host, Modifier.weight(1f), showBack = !wide, onBack = { detail = false },
                                meshDeviceCount = meshDeviceCount, onOpenPrivateDevices = openPrivateDevices)
                            else if (wide && page == 0) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                EmptyState(R.drawable.outline_chat_bubble_24, t("chats"), t("emptyChat"))
                            }
                        }
                        if (wide && page == 0 && !showPrivateDevices) PaneDivider(widths, position,
                            onMove = { split = widths.fraction(it) },
                            onCommit = {
                                split = widths.fraction(it)
                                preferences.edit().putFloat("nextPaneSplit", split).apply()
                            })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateRequestBanner(name: String, reject: () -> Unit, accept: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("private-request"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.next_private_request),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(name.ifBlank { t("unnamedDevice") }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.next_private_request_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = reject) { Text(t("reject")) }
                OutlinedButton(onClick = { accept(false) }) { Text(t("once")) }
                Button(onClick = { accept(true) }) { Text(stringResource(R.string.next_private_accept_remember)) }
            }
        }
    }
}

private val navItems = listOf(
    "chats" to R.drawable.outline_chat_bubble_24,
    "nearby" to R.drawable.outline_wifi_tethering_24,
    "settings" to R.drawable.outline_settings_24,
)

@Composable
private fun RowScope.NavigationItems(page: Int, select: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.onSurface,
        indicatorColor = scheme.primaryContainer,
    )
    navItems.forEachIndexed { index, (key, icon) ->
        val selected = page == index
        NavigationBarItem(selected = selected, onClick = { select(index) },
            colors = colors, icon = { AppIcon(icon) }, label = { NavigationLabel(key, selected) })
    }
}

@Composable
private fun RailItems(page: Int, select: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.onSurface,
        indicatorColor = scheme.primaryContainer,
    )
    navItems.forEachIndexed { index, (key, icon) ->
        val selected = page == index
        NavigationRailItem(selected = selected, onClick = { select(index) },
            colors = colors, icon = { AppIcon(icon) }, label = { NavigationLabel(key, selected) })
    }
}

@Composable
private fun NavigationLabel(key: String, selected: Boolean) {
    Text(t(key), style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1)
}

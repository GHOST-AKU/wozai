package dev.ghost.nearbyim.next

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AppLanguage
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.EmojiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Replace the selected range without moving the cursor to the end of the draft. */
fun insertEmoji(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    return TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length))
}

private val categoryIcons = listOf(
    R.drawable.outline_history_24, R.drawable.outline_emoji_emotions_24, R.drawable.outline_front_hand_24,
    R.drawable.outline_pets_24, R.drawable.outline_restaurant_24, R.drawable.outline_directions_car_24,
    R.drawable.outline_sports_soccer_24, R.drawable.outline_lightbulb_24, R.drawable.outline_tag_24,
    R.drawable.outline_flag_24,
)

private data class EmojiBubbleSelection(
    val anchor: Rect,
    val entry: EmojiCatalog.Entry,
    val variants: List<EmojiCatalog.Entry>,
)

@Composable
internal fun EmojiPanel(height: Dp, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ui", 0) }
    var catalog by remember { mutableStateOf<EmojiCatalog?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var category by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf(preferences.getString("emojiRecent", "") ?: "") }
    var bubble by remember { mutableStateOf<EmojiBubbleSelection?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(bubble != null) { bubble = null }
    val searchFocus = remember { FocusRequester() }
    val locale = AppLanguage.locale(context)
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) {
            runCatching { context.resources.openRawResource(R.raw.emoji_catalog).use(::EmojiCatalog) }.getOrNull()
        }
        failed = catalog == null
    }
    fun pick(entry: EmojiCatalog.Entry) {
        val data = catalog ?: return
        recent = data.remember(recent, entry)
        preferences.edit().putString("emojiRecent", recent).apply()
        onPick(entry.text)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height).testTag("emoji-dock")
            .onGloballyPositioned { origin = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        OutlinedTextField(query, { query = it }, singleLine = true,
                            placeholder = { Text(t("emojiSearch")) },
                            modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("emoji-search"))
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    } else Text(t("emoji"), Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { searching = !searching; query = "" }) {
                        AppIcon(if (searching) R.drawable.outline_close_24 else R.drawable.outline_search_24,
                            t(if (searching) "clearSearch" else "emojiSearch"))
                    }
                }
                val data = catalog
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        failed -> Text(t("emojiLoadFailed"), Modifier.padding(horizontal = 16.dp))
                        data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> {
                            val entries = remember(data, query, category, recent, locale) {
                                val group = if (category == 0) "recent" else EmojiCatalog.CATEGORIES[category - 1]
                                val choices = data.browse(group, query, locale, recent)
                                if (category == 0 && query.isBlank()) {
                                    (choices + data.browse(EmojiCatalog.CATEGORIES.first(), "", locale, recent)).distinctBy { it.text }
                                } else choices
                            }
                            if (entries.isEmpty()) Text(t("emojiNoResults"), Modifier.padding(horizontal = 16.dp))
                            key(category) {
                                LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    items(entries, key = { it.text }) { entry ->
                                        EmojiCell(entry, locale, onPick = { pick(entry) }) { rect ->
                                            bubble = EmojiBubbleSelection(rect.translate(-origin), entry, data.variants(entry))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!searching) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(categoryIcons.size) { index ->
                        IconToggleButton(checked = index == category, onCheckedChange = { category = index },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                            AppIcon(categoryIcons[index], t(if (index == 0) "emojiRecent" else "emojiCategory${index - 1}"))
                        }
                    }
                }
            }
            bubble?.let { selection ->
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { bubble = null })
                })
                EmojiNameBubble(selection, locale, maxWidth, Modifier.matchParentSize()) { variant ->
                    bubble = null
                    pick(variant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(entry: EmojiCatalog.Entry, locale: Locale, onPick: () -> Unit, onLongPress: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.height(48.dp).fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(role = Role.Button, onClick = onPick, onLongClick = { onLongPress(bounds) })
            .semantics { contentDescription = entry.label(locale) },
        contentAlignment = Alignment.Center,
    ) {
        Text(entry.text, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun EmojiNameBubble(
    selection: EmojiBubbleSelection,
    locale: Locale,
    availableWidth: Dp,
    modifier: Modifier,
    onPick: (EmojiCatalog.Entry) -> Unit,
) {
    val margin = 8.dp
    val widthLimit = (availableWidth - margin * 2).coerceAtLeast(0.dp)
    val cellWidth = 60.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val columns = minOf(6, selection.variants.size,
        ((widthLimit - margin * 2) / cellWidth).toInt().coerceAtLeast(1))
    val hasChoices = selection.variants.size > 1
    val width = if (hasChoices) (cellWidth * columns + margin * 2).coerceAtMost(widthLimit)
        else minOf(224.dp, widthLimit)
    val title = selection.entry.label(locale)
    val base = selection.variants.firstOrNull { it.text == selection.entry.base }?.label(locale) ?: title

    val bubbleWidth = if (hasChoices) Modifier.width(width) else Modifier.widthIn(max = width)
    Layout(content = {
        Surface(
            modifier = bubbleWidth.testTag("emoji-variants").semantics { paneTitle = title },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = margin, vertical = 6.dp)) {
                Text(title, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall)
                if (hasChoices) selection.variants.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        row.forEach { entry ->
                            val name = entry.label(locale)
                            val caption = name.removePrefix(base).trimStart(':', '：', ' ')
                            TextButton(
                                modifier = Modifier.width(cellWidth).heightIn(min = 56.dp)
                                    .semantics { contentDescription = name },
                                onClick = { onPick(entry) },
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(entry.text, style = MaterialTheme.typography.headlineSmall)
                                    Text(caption.ifEmpty { stringResource(R.string.next_emoji_default) },
                                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }, modifier = modifier) { children, constraints ->
        val edge = margin.roundToPx()
        val placeable = children.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = (constraints.maxWidth - 2 * edge).coerceAtLeast(0),
            maxHeight = (constraints.maxHeight - 2 * edge).coerceAtLeast(0)))
        val maxX = (constraints.maxWidth - placeable.width - edge).coerceAtLeast(edge)
        val maxY = (constraints.maxHeight - placeable.height - edge).coerceAtLeast(edge)
        val x = (selection.anchor.center.x - placeable.width / 2f).roundToInt().coerceIn(edge, maxX)
        val above = selection.anchor.top.roundToInt() - placeable.height - edge
        val below = selection.anchor.bottom.roundToInt() + edge
        val y = (if (above >= edge) above else if (below <= maxY) below else above).coerceIn(edge, maxY)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
    }
}

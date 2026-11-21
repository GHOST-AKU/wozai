package dev.ghost.nearbyim.next

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AppLanguage
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.EmojiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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

@OptIn(ExperimentalFoundationApi::class)
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
        Column(Modifier.fillMaxWidth().height(height).testTag("emoji-dock")) {
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
                                    EmojiCell(entry, data.variants(entry), locale, onPick = ::pick)
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(
    entry: EmojiCatalog.Entry,
    variants: List<EmojiCatalog.Entry>,
    locale: Locale,
    onPick: (EmojiCatalog.Entry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val variantsLabel = t("emojiVariants")
    val columns = minOf(variants.size, 6,
        ((LocalConfiguration.current.screenWidthDp - 32) / 48).coerceAtLeast(1))
    Box {
        Box(Modifier.height(48.dp).fillMaxWidth().combinedClickable(
            role = Role.Button, onClick = { onPick(entry) }, onLongClickLabel = variantsLabel,
            onLongClick = if (variants.size > 1) ({ expanded = true }) else null,
        ).semantics { contentDescription = entry.label(locale) }, contentAlignment = Alignment.Center) {
            Text(entry.text, style = MaterialTheme.typography.headlineSmall)
        }
        if (expanded && columns > 0) DropdownMenu(
            expanded = true, onDismissRequest = { expanded = false },
            modifier = Modifier.width((columns * 48).dp).testTag("emoji-variants")
                .semantics { paneTitle = variantsLabel },
        ) {
            variants.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    row.forEach { variant ->
                        TextButton(
                            onClick = { expanded = false; onPick(variant) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(variant.text, Modifier.semantics { contentDescription = variant.label(locale) },
                                style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

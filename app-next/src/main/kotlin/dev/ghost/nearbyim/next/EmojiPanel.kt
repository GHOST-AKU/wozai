package dev.ghost.nearbyim.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AppLanguage
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.EmojiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Replace the selected range without moving the cursor to the end of the draft. */
fun insertEmoji(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    return TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPanel(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ui", 0) }
    var catalog by remember { mutableStateOf<EmojiCatalog?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf(preferences.getString("emojiRecent", "") ?: "") }
    var variants by remember { mutableStateOf<EmojiCatalog.Entry?>(null) }
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) {
            try { EmojiCatalog(context.resources.openRawResource(R.raw.emoji_catalog)) }
            catch (_: Exception) { null }
        }
        failed = catalog == null
    }
    fun pick(entry: EmojiCatalog.Entry) {
        val data = catalog ?: return
        recent = data.remember(recent, entry)
        preferences.edit().putString("emojiRecent", recent).apply()
        onPick(entry.text)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
            OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(t("emojiSearch")) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("emoji-search"))
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(10) { index ->
                    FilterChip(selected = category == index, onClick = { category = index },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(t(if (index == 0) "emojiRecent" else "emojiCategory${index - 1}")) })
                }
            }
            val data = catalog
            if (failed) Text(t("emojiLoadFailed"), Modifier.padding(16.dp))
            else if (data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val locale = AppLanguage.locale(context)
                val entries = remember(data, query, category, recent, locale) {
                    data.browse(if (category == 0) "recent" else EmojiCatalog.CATEGORIES[category - 1], query, locale, recent)
                }
                if (entries.isEmpty()) Text(t("emojiNoResults"), Modifier.padding(16.dp))
                LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(8.dp)) {
                    items(entries, key = { it.text }) { entry ->
                        Column {
                            TextButton(onClick = { pick(entry) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = entry.label(locale) }) {
                                Text(entry.text, style = MaterialTheme.typography.headlineSmall)
                            }
                            if (data.variants(entry).size > 1) TextButton(onClick = { variants = entry },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(t("emojiVariants"), maxLines = 2, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
    variants?.let { entry ->
        AlertDialog(onDismissRequest = { variants = null }, title = { Text(t("emojiVariants")) },
            text = { LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.heightIn(max = 240.dp)) {
                items(catalog!!.variants(entry), key = { it.text }) { choice ->
                    TextButton(onClick = { pick(choice); variants = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(choice.text, modifier = Modifier.semantics { contentDescription = choice.label(AppLanguage.locale(context)) })
                    }
                }
            } }, confirmButton = { TextButton(onClick = { variants = null }) { Text(t("close")) } })
    }
}

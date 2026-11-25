package dev.ghost.nearbyim.next

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R

internal fun Modifier.messageLongPress(label: String, onOpen: () -> Unit): Modifier =
    pointerInput(onOpen) { detectTapGestures(onLongPress = { onOpen() }) }
        .semantics { onLongClick(label) { onOpen(); true } }

internal fun copySensitiveText(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

@Composable
internal fun TextMessageBody(body: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = stringResource(R.string.next_message_actions)
    var menu by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    val openMenu = remember { { menu = true } }
    Box {
        Text(body, modifier.messageLongPress(label, openMenu), style = MaterialTheme.typography.bodyLarge)
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.next_copy_message)) },
                leadingIcon = { AppIcon(R.drawable.outline_content_copy_24) }, onClick = {
                    menu = false
                    copySensitiveText(context, label, body)
                })
            DropdownMenuItem(text = { Text(stringResource(R.string.next_select_message_text)) }, onClick = {
                menu = false
                select = true
            })
        }
    }
    if (select) AlertDialog(onDismissRequest = { select = false }, title = { Text(stringResource(R.string.next_select_message_text)) },
        text = { SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(body) } },
        confirmButton = { TextButton(onClick = { select = false }) { Text(t("close")) } })
}

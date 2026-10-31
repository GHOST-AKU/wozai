package dev.ghost.nearbyim.next

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.*
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.i18n.LanguageRegistry

@Composable
fun SettingsScreen(state: NextState, host: MainActivity) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var nickname by rememberSaveable(state.nickname) { mutableStateOf(state.nickname) }
    var revoke by remember { mutableStateOf<ChatStore.TrustedDevice?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(t("settings"), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                DeviceAvatar(host.avatarRoot(null), state.nickname, Modifier.clickable(onClick = host::openAvatar), large = true)
                Column(Modifier.weight(1f).clickable { dialog = "nickname" }.padding(vertical = 12.dp)) {
                    Text(state.nickname, style = MaterialTheme.typography.titleLarge)
                    Text(t("nickname"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { dialog = "nickname" }, enabled = state.ready) { AppIcon(R.drawable.outline_more_vert_24, t("nickname")) }
            }
            SectionLabel(t("appearanceSection"))
            SettingRow(t("theme"), t(when (AppAppearance.selection(host)) {
                "dark" -> "darkTheme"; "light" -> "lightTheme"; else -> "systemTheme"
            }), R.drawable.outline_lightbulb_24) { dialog = "theme" }
            SettingRow(t("language"), LanguageRegistry.languages().find { it.tag == AppLanguage.selection(host) }?.nativeName
                ?: t("systemLanguage"), R.drawable.outline_chat_bubble_24) { dialog = "language" }
            if (Build.VERSION.SDK_INT >= 31) ListItem(
                headlineContent = { Text(stringResource(R.string.next_dynamic_color)) },
                supportingContent = { Text(stringResource(R.string.next_dynamic_color_hint)) },
                trailingContent = { Switch(host.getSharedPreferences("ui", 0).getBoolean("nextDynamicColor", false), host::setDynamicColor) },
            )
            SectionLabel(t("connectionsSection"))
            ListItem(headlineContent = { Text(t("readReceipts")) }, supportingContent = { Text(t("readReceiptsSummary")) },
                trailingContent = { Switch(state.readReceipts, host::setReadReceipts, enabled = state.ready) })
            SettingRow(t("receiveFolder"), state.receiveFolder, R.drawable.outline_file_download_24) { dialog = "folder" }
            SettingRow(t("appPermissions"), t("permissionsSummary"), R.drawable.outline_front_hand_24, host::openAppSettings)
            SectionLabel(t("trustedDevices"))
        }
        if (state.trusted.isEmpty()) item {
            Text(t("noTrustedDevices"), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.trusted, key = { it.id }) { device ->
            ListItem(headlineContent = { Text(device.name) }, supportingContent = { Text(t("trustedConnect")) },
                leadingContent = { DeviceAvatar(device.publicKey, device.name) }, trailingContent = {
                    IconButton(onClick = { revoke = device }) { AppIcon(R.drawable.outline_more_vert_24, t("deviceInfo")) }
                })
        }
        item {
            SectionLabel(t("appSection"))
            SettingRow("我在next", LanguageRegistry.VERSION, R.drawable.ic_app) { dialog = "about" }
            Text(t("privacy"), Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (dialog) {
        "nickname" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("yourNickname")) },
            text = { OutlinedTextField(nickname, { nickname = it }, singleLine = true, label = { Text(t("nickname")) }) },
            confirmButton = { TextButton(onClick = { host.setNickname(nickname); dialog = null }, enabled = state.ready) { Text(t("save")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(t("cancel")) } })
        "theme" -> ChoiceDialog(t("theme"), listOf("system", "light", "dark").map {
            it to t(when(it) { "dark" -> "darkTheme"; "light" -> "lightTheme"; else -> "systemTheme" })
        }, AppAppearance.selection(host), { dialog = null }) { dialog = null; host.setTheme(it) }
        "language" -> ChoiceDialog(t("language"), listOf("system" to t("systemLanguage")) +
            LanguageRegistry.languages().map { it.tag to it.nativeName }, AppLanguage.selection(host),
            { dialog = null }) { dialog = null; host.setLanguage(it) }
        "folder" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(t("receiveFolder")) },
            text = { Text(t("receiveFolderSummary")) },
            confirmButton = { TextButton(onClick = { dialog = null; host.chooseFolder() }) { Text(t("receiveFolderChoose")) } },
            dismissButton = { TextButton(onClick = { dialog = null; host.stopFolder() }) { Text(t("receiveFolderStop")) } })
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("我在next") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(LanguageRegistry.VERSION)
                Text(stringResource(R.string.next_experiment))
                Text(stringResource(R.string.next_independent_profile))
            } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(t("close")) } })
    }
    revoke?.let { device ->
        AlertDialog(onDismissRequest = { revoke = null }, title = { Text(device.name) }, text = { Text(t("trustHint")) },
            confirmButton = { TextButton(onClick = { host.revokeTrust(device.id); revoke = null }) { Text(t("revoke")) } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text(t("cancel")) } })
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick), headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) }, leadingContent = { AppIcon(icon) },
        trailingContent = { AppIcon(R.drawable.outline_keyboard_arrow_down_24) })
}

@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, selected: String, dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (value, name) ->
                Row(Modifier.fillMaxWidth().clickable { select(value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == value, onClick = null)
                    Text(name, Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(t("cancel")) } })
}

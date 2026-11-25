package dev.ghost.nearbyim.next

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.IdentityVerification

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerificationScreen(state: NextState, host: MainActivity, modifier: Modifier, onBack: () -> Unit) {
    val snapshot = state.verification
    var confirm by remember { mutableStateOf<IdentityVerification?>(null) }
    var options by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().testTag("verification-page")) {
        TopAppBar(title = { Text(t("verificationTitle")) }, windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.outline_arrow_back_24, t("backToChats")) } },
            actions = {
                Box {
                    IconButton(onClick = { options = true }) { AppIcon(R.drawable.outline_more_vert_24, t("more")) }
                    DropdownMenu(options, onDismissRequest = { options = false }) {
                        DropdownMenuItem(text = { Text(t("transferDiagnostics")) }, onClick = {
                            options = false
                            host.copyTransferDiagnostics(snapshot)
                        })
                        if (state.connected) DropdownMenuItem(text = { Text(t("disconnect")) }, onClick = {
                            options = false
                            host.disconnect()
                        })
                    }
                }
            })
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.TopCenter) {
            val columns = maxWidth >= 640.dp
            val bodyWidth = minOf(maxWidth, if (columns) 960.dp else 540.dp)
            Column(Modifier.width(bodyWidth).verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                VerificationIdentity(state, host)
                val result = state.notice.ifEmpty { state.error }
                if (result.isNotEmpty()) Text(result, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium)
                if (snapshot == null) {
                    Text(t("verificationConnectFirst"), style = MaterialTheme.typography.bodyLarge)
                } else {
                    if (state.identityBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (columns) {
                        Row(Modifier.fillMaxWidth().testTag("verification-columns"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            VerificationScan(snapshot, state.identityBusy, host, Modifier.weight(1f))
                            VerificationCode(snapshot, state.identityBusy, host, Modifier.weight(1.3f)) { confirm = snapshot }
                        }
                    } else {
                        VerificationScan(snapshot, state.identityBusy, host, Modifier.fillMaxWidth())
                        VerificationCode(snapshot, state.identityBusy, host, Modifier.fillMaxWidth()) { confirm = snapshot }
                    }
                    Text(t("verificationLocalOnly"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val confirmation = confirm
    if (confirmation != null) {
        val current = confirmation.sameIdentity(snapshot)
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(t("verificationTitle")) },
            text = { Text(t(if (current) "verificationConfirm" else "verificationConnectionChanged")) },
            confirmButton = {
                if (current) Button(onClick = { host.verifyIdentity(confirmation); confirm = null }, enabled = !state.identityBusy) {
                    Text(t("verificationMark"))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("cancel")) } })
    }
}

@Composable
private fun VerificationIdentity(state: NextState, host: MainActivity) {
    val verified = state.verification?.verified == true
    Surface(shape = RoundedCornerShape(24.dp),
        color = if (verified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DeviceAvatar(host.avatarRoot(state.selectedId), state.selectedName, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.selectedName.ifBlank { t("unnamedDevice") }, style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.encrypted) Text(t("encryptionActive"), style = MaterialTheme.typography.bodyMedium)
                Text(t(if (verified) "identityVerified" else "identityUnverified"),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (verified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VerificationScan(snapshot: IdentityVerification, busy: Boolean, host: MainActivity, modifier: Modifier) {
    OutlinedCard(modifier.testTag("verification-scan")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VerificationQr(snapshot)
            Button(onClick = { host.scanIdentity(snapshot) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                AppIcon(R.drawable.outline_qr_code_scanner_24)
                Spacer(Modifier.width(8.dp))
                Text(t("verificationScan"))
            }
            TextButton(onClick = { host.pickIdentityImage(snapshot) }, enabled = !busy) { Text(t("verificationImage")) }
            TextButton(onClick = { host.saveIdentityQr(snapshot) }, enabled = !busy) { Text(t("verificationSaveQr")) }
        }
    }
}

@Composable
private fun VerificationCode(snapshot: IdentityVerification, busy: Boolean, host: MainActivity, modifier: Modifier,
                             onConfirm: () -> Unit) {
    OutlinedCard(modifier.testTag("verification-code")) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.next_safety_code), style = MaterialTheme.typography.titleLarge)
            Text(t("verificationExplain"), style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                SelectionContainer {
                    Text(snapshot.code.display(), modifier = Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { host.copyIdentityCode(snapshot) }) { Text(t("verificationCopy")) }
            if (!snapshot.verified) TextButton(onClick = onConfirm, enabled = !busy) { Text(t("verificationMark")) }
        }
    }
}

package dev.ghost.nearbyim.next

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.*
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: NextState, host: MainActivity, modifier: Modifier = Modifier, showBack: Boolean, onBack: () -> Unit) {
    val peer = state.selectedId ?: return
    var info by rememberSaveable(peer) { mutableStateOf(false) }
    var confirmVerification by remember { mutableStateOf<IdentityVerification?>(null) }
    var switchConnection by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val bodyBounds = remember(peer) { mutableStateMapOf<String, Rect>() }
    var viewport by remember(peer) { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val messages = remember(state.messages) { state.messages.asReversed() }
    LaunchedEffect(peer) { listState.scrollToItem(0) }
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex < 2) listState.animateScrollToItem(0)
    }
    DisposableEffect(peer) { onDispose { host.visibleMessages(null, emptyList()) } }
    LaunchedEffect(peer, messages) {
        while (true) {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo.mapNotNull { item ->
                val message = messages.getOrNull(item.index) ?: return@mapNotNull null
                val bounds = bodyBounds[message.id] ?: return@mapNotNull null
                if (message.outgoing || message.attachment != null || !ReadVisibility.visible(
                        (bounds.top - viewport.top).toDouble(), bounds.height.toDouble(), viewport.height.toDouble())) null else message.id
            }
            host.visibleMessages(peer, visible)
            delay(200)
        }
    }
    Column(modifier.fillMaxHeight().imePadding()) {
        TopAppBar(
            title = { Column {
                Text(state.selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(if (state.encrypted) t("encryptedVia", t(if (host.preferredMode(peer) == 2) "bluetooth" else "lan"))
                    else t("localHistoryDisconnected"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            navigationIcon = { if (showBack) IconButton(onClick = onBack) { AppIcon(R.drawable.outline_arrow_back_24, t("backToChats")) } },
            actions = { IconButton(onClick = { info = true }) { AppIcon(R.drawable.outline_more_vert_24, t("deviceInfo")) } },
        )
        if (!state.canSend) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.connecting) state.status else t("draftRetainedHint"), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.connecting) TextButton(onClick = host::disconnect) { Text(t("cancel")) }
                else if (state.trusted.any { it.id == peer }) TextButton(onClick = {
                    if (state.connected) switchConnection = true else host.reconnect(peer)
                }, enabled = state.ready) { Text(t("reconnect")) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(R.drawable.outline_chat_bubble_24, t("noMessageTitle"), t("firstMessageHint"))
            }
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()
                .onGloballyPositioned { viewport = it.boundsInWindow() },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages, key = { "${it.id}:${it.outgoing}" }) { message ->
                    MessageBubble(message, host, state.canAttach) { bounds ->
                        if (!message.outgoing) {
                            if (bounds == null) bodyBounds.remove(message.id) else bodyBounds[message.id] = bounds
                        }
                    }
                }
            }
            if (listState.firstVisibleItemIndex > 3) SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { AppIcon(R.drawable.outline_keyboard_arrow_down_24, t("newMessages")) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.draftLoading) TextButton(onClick = host::retryDraft) { Text(t("draftLoadRetry")) }
        if (state.draft.toByteArray(Charsets.UTF_8).size > state.textLimit)
            Text(t("messageTooLong"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { attachments = true }, enabled = state.canAttach && state.ready) {
                    AppIcon(R.drawable.outline_add_24, t("attachments"))
                }
                DropdownMenu(expanded = attachments, onDismissRequest = { attachments = false }) {
                    DropdownMenuItem(text = { Text(t("sendPhoto")) }, leadingIcon = { AppIcon(R.drawable.outline_photo_24) },
                        onClick = { attachments = false; host.pickAttachments(true) })
                    DropdownMenuItem(text = { Text(t("sendFile")) }, leadingIcon = { AppIcon(R.drawable.outline_attach_file_24) },
                        onClick = { attachments = false; host.pickAttachments(false) })
                }
            }
            OutlinedTextField(state.draft, host::editDraft, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp), placeholder = { Text(t("androidComposer")) },
                enabled = state.ready && !state.draftLoading, minLines = 1, maxLines = 5,
                isError = state.draft.toByteArray(Charsets.UTF_8).size > state.textLimit)
            FilledIconButton(onClick = host::sendText, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                enabled = canSubmitText(state.draft, state.textLimit, state.canSend, state.draftLoading)) {
                if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.outline_send_24, t("send"))
            }
        }
    }
    if (info) {
        val verification = state.verification
        AlertDialog(onDismissRequest = { info = false }, title = { Text(state.selectedName) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(t(if (verification?.verified == true) "identityVerified" else "identityUnverified"),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                if (verification != null) {
                    Text(t("verificationExplain"), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(verification.code.display(), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) }
                    if (!verification.verified) OutlinedButton(onClick = { confirmVerification = verification; info = false }) {
                        Text(t("verificationMark"))
                    }
                } else Text(t("verificationConnectFirst"))
                Text(t("verificationLocalOnly"), style = MaterialTheme.typography.bodySmall)
                if (state.connectedId == peer) TextButton(onClick = { host.disconnect(); info = false }) { Text(t("disconnect")) }
            } },
            confirmButton = { TextButton(onClick = { info = false }) { Text(t("close")) } })
    }
    confirmVerification?.let { snapshot ->
        AlertDialog(onDismissRequest = { confirmVerification = null }, title = { Text(t("verificationTitle")) },
            text = { Text(t("verificationConfirm")) },
            confirmButton = { TextButton(onClick = { host.verifyIdentity(snapshot); confirmVerification = null }) { Text(t("verificationMark")) } },
            dismissButton = { TextButton(onClick = { confirmVerification = null }) { Text(t("cancel")) } })
    }
    if (switchConnection) AlertDialog(onDismissRequest = { switchConnection = false }, title = { Text(t("connectionSwitchTitle")) },
        text = { Text(t("connectionSwitchBody")) },
        confirmButton = { TextButton(onClick = { host.disconnect(); host.reconnect(peer); switchConnection = false }) { Text(t("disconnectAndConnect")) } },
        dismissButton = { TextButton(onClick = { switchConnection = false }) { Text(t("cancel")) } })
}

@Composable
private fun MessageBubble(message: ChatStore.Message, host: MainActivity, canAttach: Boolean, bodyPosition: (Rect?) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(message.id) { onDispose { bodyPosition(null) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
            bottomStart = if (message.outgoing) 20.dp else 5.dp, bottomEnd = if (message.outgoing) 5.dp else 20.dp),
            color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(.88f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val record = message.attachment
                if (record == null) SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.onGloballyPositioned {
                        bodyPosition(Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
                    }) }
                else AttachmentContent(record, host, canAttach)
            }
        }
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AndroidText.date(context, message.time, "jm"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.outgoing && message.attachment == null) ReceiptMark(message.state)
        }
    }
}

@Composable
private fun ReceiptMark(state: String) {
    val receipt = MessageReceipt.of(state)
    val label = t(receipt.textKey())
    val color = if (receipt == MessageReceipt.READ) Color(0xFF66CCFF) else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp, 16.dp).semantics { contentDescription = label }) {
        val sx = size.width / 20; val sy = size.height / 16
        receipt.draw(object : MessageReceipt.Ink {
            override fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(color, Offset(x1*sx, y1*sy), Offset(x2*sx, y2*sy), 1.5.dp.toPx(), StrokeCap.Round)
            }
            override fun circle(x: Float, y: Float, radius: Float) {
                drawCircle(color, radius*sx, Offset(x*sx, y*sy), style = Stroke(1.5.dp.toPx()))
            }
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentContent(record: AttachmentRecord, host: MainActivity, canAttach: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(if (record.info.mime.startsWith("image/")) R.drawable.outline_photo_24 else R.drawable.outline_description_24)
        Column(Modifier.weight(1f)) {
            Text(AttachmentInfo.displayName(record.info.name), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, record.info.size), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(t(record.stateKey()), style = MaterialTheme.typography.labelMedium)
    val route = host.attachmentRoute(record)
    if (route.isNotEmpty()) Text(route, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (record.active() && record.info.size > 0) LinearProgressIndicator(
        progress = { (record.transferred.toFloat() / record.info.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!record.outgoing && record.state == "offered") {
            TextButton(onClick = { host.attachmentAction(record, "accept") }, enabled = canAttach) { Text(t("attachmentAccept")) }
            TextButton(onClick = { host.attachmentAction(record, "reject") }, enabled = canAttach) { Text(t("attachmentReject")) }
        } else if (record.state in listOf("received", "delivered")) {
            TextButton(onClick = { host.openAttachment(record) }) { Text(t("attachmentOpen")) }
            TextButton(onClick = { host.saveAttachment(record) }) { Text(t("attachmentSaveAs")) }
        } else if (record.resumable()) {
            TextButton(onClick = { host.attachmentAction(record, "resume") }, enabled = canAttach) { Text(t("attachmentResume")) }
        } else if (record.info.version == 2 && record.state == "failed" && record.outgoing) {
            TextButton(onClick = { host.attachmentAction(record, "retry") }, enabled = canAttach) { Text(t("attachmentRetry")) }
        } else if (record.info.version == 2 && record.active() && record.state != "offered") {
            TextButton(onClick = { host.attachmentAction(record, "pause") }, enabled = canAttach) { Text(t("attachmentPause")) }
        }
        if (record.active() || record.resumable()) TextButton(onClick = { host.attachmentAction(record, "cancel") }, enabled = canAttach) { Text(t("cancel")) }
    }
}

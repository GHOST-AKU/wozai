package dev.ghost.nearbyim.next

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.*
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: NextState,
    host: MainActivity,
    modifier: Modifier = Modifier,
    showBack: Boolean,
    onBack: () -> Unit,
    meshDeviceCount: Int? = null,
    onOpenPrivateDevices: (() -> Unit)? = null,
    onVerifyIdentity: () -> Unit = {},
) {
    val peer = state.selectedId ?: return
    var attachments by remember { mutableStateOf(false) }
    var emoji by rememberSaveable(peer) { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(emoji) { emoji = false }
    var editor by remember(peer) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    // Controller save/load changes replace text; ordinary selection changes stay local.
    if (editor.text != state.draft) editor = TextFieldValue(state.draft, TextRange(editor.selection.end.coerceAtMost(state.draft.length)))
    val listState = rememberLazyListState()
    val bodyBounds = remember(peer) { mutableStateMapOf<String, Rect>() }
    var viewport by remember(peer) { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val messages = remember(state.messages) { state.messages.asReversed() }
    val messagesByKey = remember(messages) { messages.associateBy { it.timelineKey } }
    LaunchedEffect(peer) { listState.scrollToItem(0) }
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex < 2) listState.animateScrollToItem(0)
    }
    DisposableEffect(peer) { onDispose { host.visibleMessages(null, emptyList()) } }
    LaunchedEffect(peer, state.ready, messagesByKey) {
        if (!state.ready) {
            host.visibleMessages(null, emptyList())
            return@LaunchedEffect
        }
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val key = item.key as? String ?: return@mapNotNull null
                val message = messagesByKey[key] ?: return@mapNotNull null
                val bounds = bodyBounds[key] ?: return@mapNotNull null
                if (message.outgoing || !ReadVisibility.visible(
                        (bounds.top - viewport.top).toDouble(), bounds.height.toDouble(), viewport.height.toDouble())) null else message.id
            }
        }.collect { host.visibleMessages(peer, it) }
    }
    BoxWithConstraints(modifier.fillMaxHeight().testTag("chat-root")) {
        val emojiHeight = (maxHeight * .44f).coerceIn(148.dp, 264.dp)
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                modifier = Modifier.testTag("chat-header"),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeviceAvatar(host.avatarRoot(peer), state.selectedName, Modifier.size(36.dp))
                        Column {
                            Text(state.selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (state.encrypted) t("encryptionActive") + " · " + t(if (state.verification?.verified == true) "identityVerified" else "identityUnverified")
                                else t("localHistoryDisconnected"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { if (showBack) IconButton(onClick = onBack) { AppIcon(R.drawable.outline_arrow_back_24, t("backToChats")) } },
                actions = {
                    if (onOpenPrivateDevices != null) {
                        NetworkStatusButton(state, meshDeviceCount, onOpenPrivateDevices, peerId = peer)
                    }
                    IconButton(onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        emoji = false
                        onVerifyIdentity()
                    }) { AppIcon(R.drawable.outline_lock_24, t("verificationTitle")) }
                },
            )
            val connectedHere = state.connected && state.connectedId == peer
            val remembered = state.trusted.firstOrNull { it.id == peer }
            val offerAuto = remembered != null && !state.lanAutoReception &&
                (remembered.bluetoothAddress == null || !state.bluetoothAutoReception)
            if (!connectedHere && !state.canSend) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        state.sendingAfterConnect -> stringResource(R.string.next_private_queued_draft)
                        state.connectingSelected -> state.status
                        state.waitingForIdleSession -> stringResource(R.string.next_private_busy)
                        state.canConnectOnSend && !state.privateRetryPaused -> stringResource(R.string.next_private_finding)
                        state.privateRetryPaused && state.status.isNotEmpty() -> state.status
                        offerAuto -> stringResource(R.string.next_private_reception_hint)
                        else -> t("draftRetainedHint")
                    }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        state.sendingAfterConnect -> TextButton(onClick = host::cancelDeferredSend) { Text(t("cancel")) }
                        state.connectingSelected -> TextButton(onClick = host::disconnect) { Text(t("cancel")) }
                        offerAuto -> TextButton(
                            onClick = { host.start(remembered.mode) }, enabled = state.ready) {
                            Text(stringResource(R.string.next_private_enable_auto))
                        }
                        (!state.canConnectOnSend || state.privateRetryPaused) && remembered != null ->
                            TextButton(onClick = { host.reconnect(peer) }, enabled = state.ready && state.canSwitchPrivateSession) { Text(t("reconnect")) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("chat-messages")) {
                if (messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(R.drawable.outline_chat_bubble_24, t("noMessageTitle"), t("firstMessageHint"))
                }
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()
                    .onGloballyPositioned { viewport = it.boundsInWindow() },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(messages, key = { it.timelineKey }) { message ->
                        val key = message.timelineKey
                        MessageBubble(message, host, peer, state.ready, state.canAttach, state.transferMeasurements[key] ?: TransferProgress.UNKNOWN) { bounds ->
                            if (!message.outgoing) {
                                if (bounds == null) bodyBounds.remove(key) else bodyBounds[key] = bounds
                            }
                        }
                    }
                }
                if (listState.firstVisibleItemIndex > 3) SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) { AppIcon(R.drawable.outline_keyboard_arrow_down_24, t("newMessages")) }
            }
            if (state.draftLoading) TextButton(onClick = host::retryDraft) { Text(t("draftLoadRetry")) }
            if (state.draft.toByteArray(Charsets.UTF_8).size > state.textLimit)
                Text(t("messageTooLong"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().testTag("chat-composer").padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                val scheme = MaterialTheme.colorScheme
                TextField(editor, { value ->
                    editor = value
                    if (value.text != state.draft) host.editDraft(value.text)
                }, modifier = Modifier.weight(1f).focusRequester(composerFocus).onFocusChanged {
                    if (it.isFocused) emoji = false
                },
                    shape = RoundedCornerShape(28.dp), placeholder = { Text(t("androidComposer")) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = scheme.surfaceContainerHigh,
                        unfocusedContainerColor = scheme.surfaceContainer,
                        disabledContainerColor = scheme.surfaceContainer,
                        errorContainerColor = scheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                    ),
                    leadingIcon = {
                        IconButton(onClick = {
                            emoji = !emoji
                            if (emoji) {
                                focus.clearFocus()
                                keyboard?.hide()
                            } else {
                                composerFocus.requestFocus()
                                keyboard?.show()
                            }
                        }, enabled = state.ready && !state.draftLoading) {
                            AppIcon(if (emoji) R.drawable.outline_keyboard_24 else R.drawable.outline_emoji_emotions_24,
                                t(if (emoji) "emojiKeyboard" else "emoji"))
                        }
                    },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { attachments = true }, enabled = state.canAttach && state.ready) {
                                AppIcon(R.drawable.outline_attach_file_24, t("attachments"))
                            }
                            DropdownMenu(expanded = attachments, onDismissRequest = { attachments = false }) {
                                DropdownMenuItem(text = { Text(t("sendPhoto")) }, leadingIcon = { AppIcon(R.drawable.outline_photo_24) },
                                    onClick = { attachments = false; host.pickAttachments(true) })
                                DropdownMenuItem(text = { Text(t("sendFile")) }, leadingIcon = { AppIcon(R.drawable.outline_attach_file_24) },
                                    onClick = { attachments = false; host.pickAttachments(false) })
                            }
                        }
                    },
                    enabled = state.ready && !state.draftLoading, minLines = 1, maxLines = 5,
                    isError = state.draft.toByteArray(Charsets.UTF_8).size > state.textLimit)
                FilledIconButton(onClick = host::sendText, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    enabled = !state.sendingAfterConnect && canSubmitText(state.draft, state.textLimit,
                        state.canSend || state.canConnectOnSend, state.draftLoading)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else AppIcon(R.drawable.outline_send_24, t("send"))
                }
            }
            if (emoji) EmojiPanel(emojiHeight) { text ->
                if (host.state.selectedId == peer && !host.state.draftLoading) {
                    val inserted = insertEmoji(editor, text)
                    host.editDraft(inserted.text)
                    if (host.state.draft == inserted.text) editor = inserted
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatStore.Message, host: MainActivity, peer: String, ready: Boolean, canAttach: Boolean, measurement: TransferProgress, bodyPosition: (Rect?) -> Unit) {
    val context = LocalContext.current
    val record = message.attachment
    val finished = record?.state == "received" || record?.state == "delivered"
    val timestamp = AndroidText.date(context, message.time, "jm")
    var thumbnail by remember(peer, message.timelineKey, finished, ready) { mutableStateOf<Bitmap?>(null) }
    if (record != null && record.info.isPreviewablePhoto() && finished && ready) {
        DisposableEffect(host, peer, message.timelineKey) {
            val subscription = host.bindAttachmentThumbnail(peer, record) { thumbnail = it }
            onDispose { subscription.close() }
        }
    }
    DisposableEffect(message.timelineKey, thumbnail != null) { onDispose { bodyPosition(null) } }
    val trackBody = Modifier.onGloballyPositioned { coordinates ->
        bodyPosition(Rect(coordinates.positionInWindow(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }
    val image = thumbnail
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleCap = minOf(520.dp, maxWidth * .88f)
        val alignment = if (message.outgoing) Alignment.End else Alignment.Start
        val bubble = Modifier.widthIn(max = bubbleCap).testTag("bubble-${message.id}")
        Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
            if (image != null && record != null) {
                var photoMenu by remember { mutableStateOf(false) }
                Box(bubble) {
                    AttachmentPhotoPreview(image, record.info.name, trackBody, timestamp,
                        message.state.takeIf { message.outgoing }, onLongPress = { photoMenu = true }) {
                        host.openAttachment(record)
                    }
                    AttachmentActionsMenu(photoMenu, { photoMenu = false }, record, host, includeOpen = true)
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (message.outgoing) 20.dp else 5.dp, bottomEnd = if (message.outgoing) 5.dp else 20.dp),
                    color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = bubble,
                ) {
                    if (record == null) TextMessageBody(message.text, trackBody.padding(horizontal = 16.dp, vertical = 12.dp))
                    else Column(Modifier.width(bubbleCap).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AttachmentContent(record, host, canAttach, measurement)
                    }
                }
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.outgoing && record == null) ReceiptMark(message.state)
                    if (message.outgoing && record?.info?.isPreviewablePhoto() == true && finished) ReceiptMark(message.state)
                }
            }
        }
    }
}

@Composable
private fun ReceiptMark(state: String, onPhoto: Boolean = false) {
    val receipt = MessageReceipt.of(state)
    val label = t(receipt.textKey())
    val color = if (receipt == MessageReceipt.READ) Color(0xFF66CCFF) else if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val glow = androidx.compose.foundation.isSystemInDarkTheme() && receipt == MessageReceipt.READ
    Canvas(Modifier.size(20.dp, 16.dp).semantics { contentDescription = label }) {
        val sx = size.width / 20; val sy = size.height / 16
        fun paintReceipt(width: Float, opacity: Float) {
        receipt.draw(object : MessageReceipt.Ink {
            override fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(color.copy(alpha = opacity), Offset(x1*sx, y1*sy), Offset(x2*sx, y2*sy), width.dp.toPx(), StrokeCap.Round)
            }
            override fun circle(x: Float, y: Float, radius: Float) {
                drawCircle(color.copy(alpha = opacity), radius*sx, Offset(x*sx, y*sy), style = Stroke(width.dp.toPx()))
            }
        })
        }
        // Match the original native drawable's subtle dark-mode halo.
        if (glow) {
            paintReceipt(4.5f, .08f)
            paintReceipt(3f, .14f)
        }
        paintReceipt(1.5f, 1f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentContent(record: AttachmentRecord, host: MainActivity, canAttach: Boolean, measurement: TransferProgress) {
    val finished = record.state == "received" || record.state == "delivered"
    val photo = record.info.isPreviewablePhoto()
    var expanded by remember(record.info.id) { mutableStateOf(false) }
    val openMenu = remember { { expanded = true } }
    val actionLabel = stringResource(R.string.next_message_actions)
    Box {
        Row(Modifier.messageLongPress(actionLabel, openMenu),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(if (photo) R.drawable.outline_photo_24 else R.drawable.outline_description_24)
            Column(Modifier.weight(1f)) {
                Text(AttachmentInfo.displayName(record.info.name), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, record.info.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!finished) AttachmentActionsMenu(expanded, { expanded = false }, record, host)
    }
    Text(t(record.stateKey()), style = MaterialTheme.typography.labelMedium)
    if (!finished) TransferMeasurements(record, measurement)
    val route = host.attachmentRoute(record)
    if (route.isNotEmpty()) Text(route, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (finished) {
        if (photo || record.info.canOpenExternally()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { host.openAttachment(record) }) { Text(t("attachmentOpen")) }
                Box {
                    IconButton(onClick = openMenu) { AppIcon(R.drawable.outline_more_vert_24, t("more")) }
                    AttachmentActionsMenu(expanded, { expanded = false }, record, host)
                }
            }
        } else {
            Box {
                TextButton(onClick = { host.saveAttachment(record) }) { Text(t("attachmentSaveAs")) }
                AttachmentActionsMenu(expanded, { expanded = false }, record, host)
            }
        }
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!record.outgoing && record.state == "offered") {
            TextButton(onClick = { host.attachmentAction(record, "accept") }, enabled = canAttach) { Text(t("attachmentAccept")) }
            TextButton(onClick = { host.attachmentAction(record, "reject") }, enabled = canAttach) { Text(t("attachmentReject")) }
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

@Composable
internal fun AttachmentPhotoPreview(
    bitmap: Bitmap,
    name: String,
    modifier: Modifier = Modifier,
    timestamp: String? = null,
    receipt: String? = null,
    onLongPress: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints {
        val ratio = (bitmap.width.toFloat() / bitmap.height).coerceIn(.38f, 3f)
        val width = minOf(maxWidth, 344.dp, 360.dp * ratio)
        Box(modifier.size(width, width / ratio).clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClickLabel = t("attachmentOpen"),
                onLongClickLabel = stringResource(R.string.next_message_actions), onClick = onOpen,
                onLongClick = onLongPress)) {
            Image(bitmap = image, contentDescription = t("attachmentPhotoPreview", AttachmentInfo.displayName(name)),
                contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (timestamp != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(42.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                Row(Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(timestamp, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    if (receipt != null) ReceiptMark(receipt, onPhoto = true)
                }
            }
        }
    }
}

@Composable
private fun AttachmentActionsMenu(expanded: Boolean, onDismiss: () -> Unit, record: AttachmentRecord,
                                  host: MainActivity, includeOpen: Boolean = false) {
    val complete = record.state == "received" || record.state == "delivered"
    val context = LocalContext.current
    val name = AttachmentInfo.displayName(record.info.name)
    val label = stringResource(R.string.next_copy_filename)
    val route = host.attachmentRoute(record)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (route.isNotEmpty()) Text(route, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (includeOpen) DropdownMenuItem(text = { Text(t("attachmentOpen")) }, onClick = {
            onDismiss()
            host.openAttachment(record)
        })
        DropdownMenuItem(text = { Text(label) }, onClick = {
            onDismiss()
        copySensitiveText(context, label, name)
        })
        if (complete) DropdownMenuItem(text = { Text(t("attachmentSaveAs")) }, onClick = {
            onDismiss()
            host.saveAttachment(record)
        })
    }
}

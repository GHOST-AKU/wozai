package dev.ghost.nearbyim

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ghost.nearbyim.core.*
import dev.ghost.nearbyim.next.*
import dev.ghost.nearbyim.mesh.MeshAndroidPlatform
import dev.ghost.nearbyim.mesh.MeshClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import dev.ghost.nearbyim.transport.Peer
import java.io.Serializable
import java.util.concurrent.Executors
import kotlin.random.Random
import dev.ghost.nearbyim.verification.QrCodes

/** Compose is an observer of the existing service; it never owns a wire session. */
class MainActivity : ComponentActivity() {
    var state by mutableStateOf(NextState())
        private set
    internal var accent by mutableStateOf(NextAccent.PURPLE)
        private set
    private var controller: ChatController? = null
    private var bound = false
    private var resumed = false
    internal var notificationDestination by mutableStateOf<String?>(null)
        private set
    private var viewingDestination: String? = null
    private val notificationOwner = System.identityHashCode(this)
    private val autoLink = PrivateAutoLink()
    private var observedPrivatePeer: String? = null
    private var observedPrivateStatus = ""
    private var queuedText: ConnectAndSend? = null
    private val privateWork = Runnable { advancePrivateConnection() }
    private var restoredPeer: String? = null
    private var restoredName: String? = null
    private var draftPeer: String? = null
    private var draft = ""
    private var draftLoading = false
    private var draftGeneration = 0
    private var notice = ""
    private var handledSave: String? = null
    private var revision = 0L
    private var pending: Action? = null
    private var pendingStage: String? = null
    private var pickerSearchMode: Int? = null
    private var selection: AttachmentSelectionSession? = null
    private var selectionUris: ArrayList<Uri>? = null
    private var selectionConfirmed = false
    private val attachmentThumbnails = lazy { SelectionThumbnails(contentResolver, 512) }
    private var retryRecordId: String? = null
    private var routePermissionPending = false
    private var pickAfterRoute: Boolean? = null
    private var exportPeer: String? = null
    private var exportRecord: AttachmentRecord? = null
    private var exportUri: Uri? = null
    private var exportFlags = 0
    private var visiblePeer: String? = null
    private var visibleIds = emptyList<String>()
    private val visibility = ReadVisibility()
    private val main = Handler(Looper.getMainLooper())
    // Coalesce service notifications only. Draft edits and action callbacks stay immediate.
    private var observerScheduled = false
    private var lastObservedAt = 0L
    private val observedRefresh = Runnable {
        observerScheduled = false
        lastObservedAt = SystemClock.uptimeMillis()
        if (bound) refresh()
    }
    private val observer = Runnable {
        val c = controller
        val saved = c?.savedMessageId != null && c.savedMessageId != handledSave &&
            shouldHandleSave(draftPeer, c.savedMessagePeer, draftLoading)
        if (c != null && (c.approvalId != state.approvalId || c.openedPrivateChat != state.openedPrivateChat ||
                observerNeedsImmediateRefresh(state, c.selectedId, c.connectedPeerId,
                    c.connected, c.connecting, c.savingMessage, saved))) {
            main.removeCallbacks(observedRefresh); observerScheduled = false
            lastObservedAt = SystemClock.uptimeMillis()
            refresh()
        } else if (!observerScheduled) {
            observerScheduled = true
            main.postDelayed(observedRefresh, (250L - (SystemClock.uptimeMillis() - lastObservedAt)).coerceAtLeast(0L))
        }
    }
    private val identityWorker = Executors.newSingleThreadExecutor()
    private var identityRequest: IdentityVerification? = null
    private var identityResult: String? = null
    private var identityImage: Uri? = null
    private var identityExport: Uri? = null
    private var identityWorking = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as ChatService.LocalBinder).service()
            service.usePrivateRequestActions()
            service.onIncomingMessages { peer, name -> MessageNotifications.post(service, peer, name) }
            controller = service.controller
            val peer = restoredPeer
            if (controller?.selectedId == null && peer != null)
                controller?.selectConversation(ChatStore.Conversation(peer, restoredName ?: "", 0))
            controller?.observe(observer)
            resumeAttachmentPicker()
            resumeAttachmentResult()
            resumeExport()
            resumeIdentityResult()
            if (pendingStage == "bluetoothResult") finishBluetoothEnable()
            else if (pendingStage == null || pendingStage == "ready") pending?.let { prepare(it) }
            restorePrivateReception()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            controller = null
            queuedText = null
            visibility.clear()
            main.removeCallbacks(privateWork)
            main.removeCallbacks(observedRefresh); observerScheduled = false
            refresh()
        }
    }

    private data class Action(val kind: String, val mode: Int = Peer.LAN, val peer: Peer? = null,
                              val address: String? = null, val id: String? = null,
                              val remember: Boolean = true) : Serializable

    private val permissionResult = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending ?: return@registerForActivityResult
        pendingStage = null
        if (required(action).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) prepare(action)
        else { pending = null; showNotice(text("bluetoothPermissionBody")) }
    }
    private val bluetoothResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingStage = "bluetoothResult"
        finishBluetoothEnable()
    }
    private val discoverableResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode <= 0) showNotice(text("bluetoothEnableCanceled"))
    }
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selectedCount = result.data?.clipData?.itemCount ?: 0
        if (result.resultCode == RESULT_OK && selectedCount > AttachmentBatchQueue.MAX_PENDING) {
            clearAttachmentSelection()
            showNotice(text("attachmentBatchLimit", AttachmentBatchQueue.MAX_PENDING))
            return@registerForActivityResult
        }
        val uris = try {
            if (result.resultCode == RESULT_OK) AttachmentSelection.from(result.data) else emptyList()
        } catch (_: IllegalArgumentException) {
            clearAttachmentSelection()
            showNotice(text("attachmentFailed"))
            return@registerForActivityResult
        }
        if (uris.isEmpty()) { clearAttachmentSelection(); return@registerForActivityResult }
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some providers offer a temporary grant only. */ }
        }
        selectionUris = ArrayList(uris)
        selectionConfirmed = false
        resumeAttachmentResult()
    }
    private val routePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        routePermissionPending = false
        resumeAttachmentPicker()
    }
    private val retryDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) clearAttachmentSelection()
        else {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* The engine can use a temporary read grant. */ }
            selectionUris = arrayListOf(uri); selectionConfirmed = true
            resumeAttachmentResult()
        }
    }
    private val attachmentPreview = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && selection != null) {
            try { selectionUris = ArrayList(AttachmentSelection.from(result.data)); selectionConfirmed = true; resumeAttachmentResult() }
            catch (_: IllegalArgumentException) { clearAttachmentSelection(); showNotice(text("attachmentFailed")) }
        } else clearAttachmentSelection()
    }
    private val export = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val peer = exportPeer
        val record = exportRecord
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && peer != null && record != null && uri != null) {
            exportUri = uri; exportFlags = result.data?.flags ?: 0
            resumeExport()
        } else clearAttachmentExport()
    }
    private val folder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            try {
                AttachmentLocations.get(this).selectDirectory(uri, result.data?.flags ?: 0).whenComplete { _, error ->
                    runOnUiThread { if (error != null) showNotice(text("receiveFolderUnavailable")) else refresh() }
                }
            } catch (_: Exception) { showNotice(text("receiveFolderUnavailable")) }
        }
    }

    private val identityScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            identityResult = result.data?.getStringExtra("code")
            if (identityResult == null) { clearIdentityRequest(); showNotice(text("verificationReadFailed")) }
            else resumeIdentityResult()
        } else clearIdentityRequest()
    }
    private val identityPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) clearIdentityRequest()
        else { identityImage = uri; resumeIdentityResult() }
    }
    private val identitySave = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) clearIdentityRequest()
        else { identityExport = uri; resumeIdentityResult() }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppAppearance.wrap(AppLanguage.wrap(base)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationDestination = savedInstanceState?.getString("notificationDestination")
            ?: if (savedInstanceState == null) MessageNotifications.destination(intent) else null
        MessageNotifications.registerChannels(this)
        // The Compose navigation surface extends into the system navigation inset.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        restoredPeer = savedInstanceState?.getString("peer")
        restoredName = savedInstanceState?.getString("peerName")
        handledSave = savedInstanceState?.getString("handledSave")
        @Suppress("DEPRECATION")
        pending = savedInstanceState?.getSerializable("pending") as? Action
        pendingStage = savedInstanceState?.getString("pendingStage")
        savedInstanceState?.getString("selectionPeer")?.let {
            selection = AttachmentSelectionSession(it, savedInstanceState.getLong("selectionToken"),
                savedInstanceState.getString("selectionIdentity") ?: "")
        }
        @Suppress("DEPRECATION")
        val savedUris = savedInstanceState?.getParcelableArrayList<Uri>("selectionUris")
        selectionUris = savedUris
        if (savedInstanceState?.containsKey("pickAfterRoute") == true) {
            pickAfterRoute = savedInstanceState.getBoolean("pickAfterRoute")
            routePermissionPending = savedInstanceState.getBoolean("routePermissionPending")
        }
        selectionConfirmed = savedInstanceState?.getBoolean("selectionConfirmed") ?: false
        retryRecordId = savedInstanceState?.getString("retryRecordId")
        exportPeer = savedInstanceState?.getString("exportPeer")
        exportUri = savedInstanceState?.getString("exportUri")?.let(Uri::parse)
        exportFlags = savedInstanceState?.getInt("exportFlags") ?: 0
        savedInstanceState?.getString("exportRecord")?.let {
            try { exportRecord = AttachmentRecord.decode(it) } catch (_: Exception) { exportPeer = null }
        }
        savedInstanceState?.getString("identityPeer")?.let { peer ->
            try { identityRequest = IdentityVerification(peer, savedInstanceState.getString("identityLocal")!!,
                savedInstanceState.getString("identityRemote")!!, savedInstanceState.getString("identityGeneration")!!, false) }
            catch (_: Exception) { identityRequest = null }
        }
        identityResult = savedInstanceState?.getString("identityResult")
        identityImage = savedInstanceState?.getString("identityImage")?.let(Uri::parse)
        identityExport = savedInstanceState?.getString("identityExport")?.let(Uri::parse)
        accent = NextAccent.read(this)
        setContent { NextTheme(accent) { NextApp(state, this) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val radios = MeshAndroidPlatform.radioChanges(this@MainActivity).shareIn(this, SharingStarted.Eagerly, replay = 1)
                launch { radios.collect { restorePrivateReception() } }
                MeshClient.restoreReception(this@MainActivity, radios)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ChatService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        syncNotificationVisibility()
        autoLink.resume(SystemClock.uptimeMillis())
        restorePrivateReception()
        refresh()
        main.post(readTick)
    }
    override fun onPause() {
        resumed = false
        syncNotificationVisibility()
        visibility.clear()
        main.removeCallbacks(readTick)
        main.removeCallbacks(privateWork)
        super.onPause()
    }
    override fun onStop() {
        restoredPeer = state.selectedId; restoredName = state.selectedName
        queuedText = null
        controller?.unobserve(observer)
        main.removeCallbacks(observedRefresh); observerScheduled = false
        if (bound) unbindService(connection)
        bound = false; controller = null
        super.onStop()
    }
    override fun onDestroy() {
        if (attachmentThumbnails.isInitialized()) attachmentThumbnails.value.close()
        identityWorker.shutdownNow()
        draftGeneration++
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("notificationDestination", notificationDestination)
        outState.putString("peer", state.selectedId)
        outState.putString("peerName", state.selectedName)
        outState.putString("handledSave", handledSave)
        outState.putSerializable("pending", pending)
        outState.putString("pendingStage", pendingStage)
        selection?.let {
            outState.putString("selectionPeer", it.peer); outState.putLong("selectionToken", it.token)
            outState.putString("selectionIdentity", it.identity)
        }
        pickAfterRoute?.let {
            outState.putBoolean("pickAfterRoute", it)
            outState.putBoolean("routePermissionPending", routePermissionPending)
        }
        outState.putParcelableArrayList("selectionUris", selectionUris)
        outState.putBoolean("selectionConfirmed", selectionConfirmed)
        outState.putString("retryRecordId", retryRecordId)
        outState.putString("exportPeer", exportPeer)
        outState.putString("exportRecord", exportRecord?.encode())
        outState.putString("exportUri", exportUri?.toString())
        outState.putInt("exportFlags", exportFlags)
        identityRequest?.let {
            outState.putString("identityPeer", it.peerId); outState.putString("identityLocal", it.localRoot)
            outState.putString("identityRemote", it.remoteRoot); outState.putString("identityGeneration", it.generation)
        }
        outState.putString("identityResult", identityResult)
        outState.putString("identityImage", identityImage?.toString())
        outState.putString("identityExport", identityExport?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = MessageNotifications.destination(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        syncNotificationVisibility()
    }

    internal fun showNotificationDestination(destination: String?) {
        viewingDestination = destination
        syncNotificationVisibility()
    }

    private fun syncNotificationVisibility() {
        MessageNotifications.visible(this, notificationOwner, viewingDestination.takeIf { resumed && hasWindowFocus() })
    }

    internal fun consumeNotificationDestination() { notificationDestination = null }

    internal fun openNotificationSettings(channel: String) {
        try { startActivity(MessageNotifications.settings(this, channel)) }
        catch (_: ActivityNotFoundException) { openAppSettings() }
    }

    private fun refresh() {
        val c = controller
        if (c == null) {
            state = state.withoutService()
            return
        }
        if (draftPeer != c.selectedId) loadDraft(c.selectedId)
        if (c.savedMessageId != null && c.savedMessageId != handledSave && shouldHandleSave(draftPeer, c.savedMessagePeer, draftLoading)) {
            handledSave = c.savedMessageId
            AndroidDrafts.get(this).clearIfUnchanged(c.savedMessagePeer, c.savedMessageBody) { error ->
                if (error != null) showNotice(text("draftSaveFailed"))
            }
            draft = draftAfterSave(draftPeer, draft, c.savedMessagePeer, c.savedMessageBody)
        }
        val folderName = try { AttachmentLocations.get(this).directory()?.name() ?: text("receiveFolderPrivate") }
            catch (_: Exception) { text("receiveFolderPrivate") }
        val selected = c.selectedId
        val connectingHere = selected != null && c.connecting && c.connectionPeer() == selected
        val reconnectPossible = selected != null && c.rememberedRoot(selected) != null && automaticModes(c, selected).isNotEmpty()
        state = NextState(
            ready = true, nickname = c.nickname,
            conversations = c.conversations.toList(), trusted = c.trustedDevices.toList(), peers = c.peers.values.toList(),
            transferMeasurements = c.messages.mapNotNull { message -> message.attachment?.let {
                message.timelineKey to c.transferProgress(c.selectedId, it)
            } }.toMap(),
            messages = c.messages.toList(), selectedId = c.selectedId, selectedName = c.selectedName ?: "",
            connectedId = c.connectedPeerId, connected = c.connected, connecting = c.connecting,
            connectedMode = c.sessionMode.takeIf { c.connected && c.connectedPeerId != null },
            status = AndroidText.get(this, c.status), error = AndroidText.get(this, c.error),
            lanRunning = c.lanRunning, bluetoothRunning = c.bluetoothRunning,
            lanSearching = c.lanSearching, bluetoothSearching = c.bluetoothSearching,
            lanInfo = AndroidText.get(this, c.lanInfo), bluetoothInfo = AndroidText.get(this, c.bluetoothInfo),
            bluetoothAvailable = c.bluetoothAvailable(), approvalId = c.approvalId, approvalName = c.approvalName ?: "",
            canSend = c.canSend(), canAttach = c.canSendAttachment(), saving = c.savingMessage,
            textLimit = if (c.connectedPeerId == selected) c.textLimit() else TextLimits.LEGACY_BYTES,
            openedPrivateChat = c.openedPrivateChat, connectingSelected = connectingHere,
            lanAutoReception = PrivateReception.enabled(this, Peer.LAN),
            bluetoothAutoReception = PrivateReception.enabled(this, Peer.BLUETOOTH),
            lanListening = c.lanListening, bluetoothListening = c.bluetoothListening,
            canConnectOnSend = selected != null && selected == autoLink.peer && !c.savingMessage &&
                (reconnectPossible || connectingHere && c.privateProof(selected) != null),
            sendingAfterConnect = queuedText?.peer != null && queuedText?.peer == selected,
            waitingForIdleSession = reconnectPossible && !connectingHere && c.hasSession() &&
                c.connectedPeerId != selected && !c.canYieldPrivateSession(),
            canSwitchPrivateSession = !c.hasSession() && !c.connecting || c.canYieldPrivateSession(),
            privateRetryPaused = autoLink.paused || autoLink.exhausted,
            draft = draft, draftLoading = draftLoading, notice = notice, readReceipts = c.readReceiptsEnabled(),
            encrypted = c.endToEndEncrypted(c.selectedId), verification = c.verification(c.selectedId),
            identityBusy = identityRequest != null || identityWorking,
            receiveFolder = folderName, revision = ++revision,
        )
        schedulePrivateWork(c)
    }

    private fun loadDraft(peer: String?) {
        draftPeer = peer; draft = ""; draftLoading = peer != null
        val generation = ++draftGeneration
        if (peer == null) return
        AndroidDrafts.get(this).load(peer) { value, error ->
            if (generation == draftGeneration && draftPeer == peer && !isDestroyed) {
                draft = value; draftLoading = error != null
                if (error != null) notice = text("draftLoadRetry")
                refresh()
            }
        }
    }
    fun editDraft(value: String) {
        val peer = draftPeer ?: return
        if (!draftSelectionCurrent(state.selectedId, draftPeer, controller?.selectedId, draftLoading)) return
        if (TextLimits.draftBytes(value) > TextLimits.DRAFT_BYTES) {
            showNotice(text("draftEditTooLarge", TextLimits.DRAFT_BYTES)); return
        }
        if (queuedText?.body != value) queuedText = null
        draft = value
        AndroidDrafts.get(this).save(peer, value) { error -> if (error != null) showNotice(text("draftSaveFailed")) }
        refresh()
    }
    fun retryDraft() { loadDraft(draftPeer); refresh() }
    fun sendText() {
        val c = controller ?: return
        val peer = c.selectedId ?: return
        if (!draftSelectionCurrent(state.selectedId, draftPeer, peer, draftLoading) || queuedText != null) return
        if (canSubmitText(draft, c.textLimit(), c.canSend(), draftLoading)) {
            c.send(draft)
            return
        }
        if (!resumed || autoLink.peer != peer || !state.canConnectOnSend ||
            !TextLimits.canSend(draft, TextLimits.LEGACY_BYTES)) return
        val savedRoot = c.rememberedRoot(peer)
        val proof = c.privateProof(peer)
        val root = savedRoot ?: proof?.root() ?: return
        val now = SystemClock.uptimeMillis()
        queuedText = ConnectAndSend(peer, root, proof?.generation().takeIf { savedRoot == null }, draft, now + 60_000)
        if (!c.connecting && (autoLink.paused || autoLink.exhausted)) autoLink.reset(now)
        refresh()
    }

    internal fun showPrivateConversation(peer: String?) {
        val old = autoLink.peer
        if (old == peer) return
        autoLink.select(peer, controller?.localId.orEmpty(), SystemClock.uptimeMillis())
        observedPrivatePeer = null
        observedPrivateStatus = ""
        if (queuedText?.peer != peer) queuedText = null
        if (old != null) controller?.cancelAutomaticReconnect(old)
        refresh()
    }

    internal fun cancelDeferredSend() {
        queuedText = null
        refresh()
    }

    private fun automaticModes(c: ChatController, peer: String): List<Int> {
        val preferred = c.preferredMode(peer)
        return listOf(preferred, if (preferred == Peer.LAN) Peer.BLUETOOTH else Peer.LAN).filter { mode ->
            PrivateReception.enabled(this, mode) && c.listening(mode) &&
                (mode == Peer.LAN || c.trustedDevices.any { it.id == peer && it.bluetoothAddress != null })
        }
    }

    private fun stillPending(c: ChatController, request: ConnectAndSend, now: Long): Boolean =
        request.stillPending(autoLink.peer.takeIf { it == c.selectedId && it == draftPeer && !draftLoading },
            draft, c.rememberedRoot(request.peer), c.privateProof(request.peer)?.generation(), now)

    private fun schedulePrivateWork(c: ChatController) {
        main.removeCallbacks(privateWork)
        val peer = autoLink.peer ?: return
        if (!resumed || c.selectedId != peer) return
        val now = SystemClock.uptimeMillis()
        val here = c.connectionPeer() == peer
        val ready = here && c.connected
        autoLink.observeReady(ready, now)
        if (here && (c.connecting || ready)) {
            observedPrivatePeer = peer
            observedPrivateStatus = c.status.key
        } else if (observedPrivatePeer == peer && !c.connecting) {
            // Older receivers can reject by closing while we wait for their approval.
            if (stopsAutomaticReconnect(c.status.key, c.error.key) ||
                (c.status.key == "disconnected" && observedPrivateStatus == "waitingPeerConsent")) autoLink.pause()
            observedPrivatePeer = null
            observedPrivateStatus = ""
        }

        val request = queuedText
        var whenToRun = request?.let {
            if (!stillPending(c, it, now) || autoLink.paused || c.canSend()) now else it.expiresAt
        } ?: Long.MAX_VALUE
        if (!ready && !autoLink.paused && !autoLink.exhausted && pending == null &&
            automaticModes(c, peer).any { c.canReconnectAutomatically(peer, it) }) {
            whenToRun = minOf(whenToRun, maxOf(now, autoLink.nextAttempt))
        }
        if (whenToRun != Long.MAX_VALUE) main.postAtTime(privateWork, whenToRun)
    }

    private fun advancePrivateConnection() {
        val c = controller ?: return
        val peer = autoLink.peer ?: return
        if (!resumed || c.selectedId != peer) return
        val now = SystemClock.uptimeMillis()
        val request = queuedText
        if (request != null) {
            if (!stillPending(c, request, now) || autoLink.paused) {
                queuedText = null
                if (draft == request.body && draftPeer == request.peer)
                    showNotice(getString(R.string.next_private_draft_kept))
                refresh()
            } else if (c.canSend()) {
                queuedText = null
                val proof = c.privateProof(peer)
                if (request.matches(proof?.root(), proof?.generation()) && TextLimits.canSend(request.body, c.textLimit()))
                    c.send(request.body)
                else showNotice(getString(R.string.next_private_draft_kept))
                refresh()
                return
            }
        }
        if (!autoLink.paused && !autoLink.exhausted && pending == null && now >= autoLink.nextAttempt) {
            val candidates = automaticModes(c, peer).filter { c.canReconnectAutomatically(peer, it) }
            if (candidates.isNotEmpty()) {
                val mode = candidates[autoLink.attempts % candidates.size]
                autoLink.attempted(now, Random.nextLong(250, 1_001))
                c.reconnectAutomatically(peer, mode)
            }
        }
        refresh()
    }

    private fun restorePrivateReception() {
        val c = controller ?: return
        if (!resumed) return
        for (mode in listOf(Peer.LAN, Peer.BLUETOOTH)) {
            if (!PrivateReception.enabled(this, mode) || c.running(mode) || pending?.mode == mode) continue
            if (mode == Peer.BLUETOOTH && (!c.bluetoothAvailable() || !c.bluetoothEnabled() ||
                    required(Action("start", mode)).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })) continue
            try { ensureListening(c, mode) }
            catch (_: RuntimeException) { showNotice(getString(R.string.next_private_restore_failed)) }
        }
    }

    private fun ensureListening(c: ChatController, mode: Int) {
        if (c.running(mode)) return
        startForegroundService(Intent(this, ChatService::class.java).setAction(ChatService.START))
        c.start(mode)
    }

    fun start(mode: Int) = prepare(Action("autoReceive", mode))
    fun stop(mode: Int) {
        PrivateReception.setEnabled(this, mode, false)
        queuedText = null
        if (pending?.mode == mode) { pending = null; pendingStage = null }
        controller?.stop(mode)
        refresh()
    }
    fun search(mode: Int) { if (pickerSearchMode == mode) pickerSearchMode = null; prepare(Action("search", mode)) }
    fun stopSearch(mode: Int) { if (pickerSearchMode == mode) pickerSearchMode = null; controller?.stopSearch(mode) }
    internal fun discoverPrivateDevices(mode: Int): Boolean {
        val c = controller ?: return false
        if (!resumed || !c.listening(mode) || c.searching(mode) || c.connecting ||
            required(Action("search", mode)).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return false
        pickerSearchMode = mode
        c.search(mode)
        return true
    }
    internal fun stopPrivatePickerSearch(mode: Int) { if (pickerSearchMode == mode) stopSearch(mode) }
    fun discoverable() = prepare(Action("discoverable", Peer.BLUETOOTH))
    fun connect(peer: Peer, remember: Boolean) = prepare(Action("peer", peer.mode, peer = peer, remember = remember))
    fun connectAddress(address: String, remember: Boolean) = prepare(Action("address", address = address, remember = remember))
    fun reconnect(peer: String) {
        val c = controller ?: return
        if (autoLink.peer == peer && automaticModes(c, peer).isNotEmpty()) {
            autoLink.reset(SystemClock.uptimeMillis())
            refresh()
        } else prepare(Action("reconnect", c.preferredMode(peer), id = peer))
    }
    internal fun isCurrentPrivateDevice(peer: Peer): Boolean {
        val c = controller ?: return false
        if (!c.connected) return false
        val id = peer.peerId ?: c.trustedDevices.firstOrNull { peer.bluetoothAddress != null && it.bluetoothAddress == peer.bluetoothAddress }?.id
        return id != null && id == c.connectedPeerId
    }
    fun attachmentRoute(record: AttachmentRecord): String = controller?.let { c ->
        if (c.selectedId != state.selectedId) return@let ""
        c.attachmentRoute(state.selectedId, record).key().takeIf { it.isNotEmpty() }?.let { text(it) }
    } ?: ""
    fun disconnect() {
        pending = null
        pendingStage = null
        queuedText = null
        autoLink.pause()
        controller?.disconnectPrivately()
        refresh()
    }
    fun select(conversation: ChatStore.Conversation) { controller?.selectConversation(conversation); refresh() }
    fun approve(token: String, remember: Boolean) { controller?.acceptPrivateRequest(token, remember) }
    fun reject(token: String) { controller?.reject(token) }
    fun setNickname(value: String) {
        val c = controller ?: return
        c.setNickname(value)
        refresh()
    }
    fun setReadReceipts(value: Boolean) { controller?.setReadReceiptsEnabled(value) }
    fun revokeTrust(peer: String) { controller?.revokeTrust(peer) }

    private fun required(action: Action): List<String> = when {
        action.mode != Peer.BLUETOOTH -> emptyList()
        Build.VERSION.SDK_INT >= 31 -> buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (action.kind in listOf("search", "peer", "autoReceive")) add(Manifest.permission.BLUETOOTH_SCAN)
            if (action.kind == "discoverable") add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        action.kind in listOf("search", "autoReceive") -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    private fun prepare(action: Action) {
        val c = controller
        if (c == null) { pending = action; pendingStage = "ready"; return }
        if (action.mode == Peer.BLUETOOTH && !c.bluetoothAvailable()) { pending = null; showNotice(text("unsupportedBluetooth")); return }
        pending = action
        val missing = required(action).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsAsked", false)) {
            prefs.edit().putBoolean("notificationsAsked", true).apply()
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            when {
                missing.isNotEmpty() -> { pendingStage = "permissions"; permissionResult.launch(missing.toTypedArray()) }
                action.mode == Peer.BLUETOOTH && !c.bluetoothEnabled() -> {
                    pendingStage = "bluetooth"
                    bluetoothResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                else -> execute(action)
            }
        } catch (_: RuntimeException) { pending = null; pendingStage = null; showNotice(text("bluetoothPermissionBody")) }
    }

    private fun finishBluetoothEnable() {
        val c = controller ?: return
        val action = pending ?: return
        pendingStage = null
        if (c.bluetoothEnabled()) execute(action)
        else { pending = null; showNotice(text("bluetoothEnableCanceled")) }
    }

    private fun execute(action: Action) {
        val c = controller ?: return
        pending = null; pendingStage = null
        try {
            if (action.kind in listOf("peer", "address", "reconnect") && c.hasSession()) {
                if (action.peer?.let(::isCurrentPrivateDevice) == true) {
                    c.requestPrivateChat(action.peer, action.remember)
                    return
                }
                if (!c.canYieldPrivateSession()) {
                    showNotice(getString(R.string.next_private_request_busy))
                    return
                }
                c.disconnectPrivately()
            }
            ensureListening(c, action.mode)
            when (action.kind) {
                "autoReceive" -> {
                    if (c.running(action.mode)) {
                        PrivateReception.setEnabled(this, action.mode, true)
                        autoLink.reset(SystemClock.uptimeMillis())
                        refresh()
                    }
                }
                "search" -> c.search(action.mode)
                "peer" -> action.peer?.let { c.requestPrivateChat(it, action.remember) }
                "address" -> c.requestPrivateAddress(action.address, action.remember)
                "reconnect" -> c.reconnect(action.id)
                "discoverable" -> discoverableResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }
        } catch (_: RuntimeException) { showNotice(text("bluetoothFailed")) }
    }

    fun pickAttachments(photos: Boolean) {
        val c = controller ?: return
        val peer = c.selectedId ?: return
        if (!draftSelectionCurrent(state.selectedId, draftPeer, peer, draftLoading) || routePermissionPending) return
        val identity = c.attachmentSelectionSession(peer) ?: return
        if (!c.canSendAttachment()) return
        selection = AttachmentSelectionSession(peer, c.attachmentSessionToken(), identity)
        retryRecordId = null
        if (!requestFileRoutePermissions(photos)) launchAttachmentPicker(photos)
    }

    private fun resumeAttachmentPicker() {
        val photos = pickAfterRoute ?: return
        if (controller == null || routePermissionPending) return
        pickAfterRoute = null
        launchAttachmentPicker(photos)
    }

    private fun launchAttachmentPicker(photos: Boolean) {
        if (!selectionCurrent()) {
            clearAttachmentSelection()
            showNotice(text("notConnected"))
            return
        }
        try {
            attachmentPicker.launch(if (photos) AttachmentSelection.photosIntent(this) else AttachmentSelection.filesIntent())
        } catch (_: ActivityNotFoundException) {
            clearAttachmentSelection()
            showNotice(text("attachmentFailed"))
        }
    }

    private fun clearAttachmentSelection() {
        selection = null
        selectionUris = null
        selectionConfirmed = false
        retryRecordId = null
    }

    private fun selectionCurrent(): Boolean {
        val c = controller ?: return false
        return selection?.accepts(c.selectedId, c.attachmentSessionToken(),
            c.attachmentSelectionSession(c.selectedId), c.canSendAttachment()) == true
    }
    private fun resumeAttachmentResult() {
        val c = controller ?: return
        val uris = selectionUris ?: return
        val captured = selection
        selectionUris = null
        if (captured == null || !selectionCurrent()) {
            clearAttachmentSelection()
            showNotice(text("notConnected"))
            return
        }
        if (selectionConfirmed) {
            val retry = retryRecordId
            clearAttachmentSelection()
            if (retry == null) c.sendAttachments(captured.peer, captured.token, uris)
            else if (uris.size == 1) c.retryAttachmentWithSelection(captured.peer, captured.token, captured.identity, retry, uris.single())
            else showNotice(text("attachmentRetrySelectAgain"))
        } else {
            attachmentPreview.launch(AttachmentSelection.result(uris)
                .setClass(this, AttachmentSelectionActivity::class.java)
                .putParcelableArrayListExtra(AttachmentSelection.URIS, uris))
        }
    }
    fun attachmentAction(record: AttachmentRecord, action: String) {
        val c = controller ?: return
        val peer = c.selectedId ?: return
        if (state.selectedId != peer) return
        if (action == "retry") {
            if (c.attachmentRetryState(peer, record.info.id) in listOf(AttachmentBatchQueue.RetryState.RETRYING,
                    AttachmentBatchQueue.RetryState.RETRIED, AttachmentBatchQueue.RetryState.INELIGIBLE)) return
            val token = c.attachmentSessionToken()
            val identity = c.attachmentSelectionSession(peer) ?: return
            c.retryAttachment(peer, token, record.info.id).whenComplete { _, failure -> runOnUiThread {
                if (failure != null && ChatController.attachmentRetryNeedsSelection(failure) &&
                    controller === c && c.attachmentSelectionSession(peer) == identity && c.attachmentSessionToken() == token) {
                    selection = AttachmentSelectionSession(peer, token, identity)
                    retryRecordId = record.info.id
                    try {
                        retryDocument.launch(arrayOf(record.info.mime))
                    } catch (_: ActivityNotFoundException) {
                        clearAttachmentSelection()
                        showNotice(text("attachmentFailed"))
                    }
                }
            } }
        }
        else c.attachmentAction(peer, record.info.id, record.outgoing, action)
    }
    private fun attachmentUri(peer: String, name: String): Uri = Uri.Builder()
        .scheme("content").authority("$packageName.attachments").appendPath(peer).appendPath(name).build()

    internal fun bindAttachmentThumbnail(peer: String, record: AttachmentRecord, receive: (Bitmap?) -> Unit): AutoCloseable {
        val c = controller ?: return AutoCloseable {}
        if (c.selectedId != peer) return AutoCloseable {}
        var canceled = false
        var subscription: AutoCloseable? = null
        val pathLookup = c.attachmentPath(peer, record)
        pathLookup.whenComplete { path, error -> runOnUiThread {
            if (canceled || isDestroyed || controller !== c || c.selectedId != peer) return@runOnUiThread
            if (error != null || path == null) receive(null)
            else subscription = attachmentThumbnails.value.bind(attachmentUri(peer, path.fileName.toString())) {
                if (!canceled && !isDestroyed && controller === c && c.selectedId == peer) receive(it)
            }
        } }
        return AutoCloseable {
            canceled = true
            pathLookup.cancel(false)
            subscription?.close()
        }
    }

    fun openAttachment(record: AttachmentRecord) {
        if (!record.info.isPreviewablePhoto() && !record.info.canOpenExternally()) {
            saveAttachment(record); return
        }
        val c = controller ?: return
        val peer = c.selectedId ?: return
        if (state.selectedId != peer) return
        c.attachmentPath(peer, record).whenComplete { path, error -> runOnUiThread {
            if (isDestroyed || controller !== c || c.selectedId != peer || state.selectedId != peer) return@runOnUiThread
            if (error != null) { showNotice(text("attachmentUnavailable")); return@runOnUiThread }
            val uri = attachmentUri(peer, path.fileName.toString()).buildUpon().appendQueryParameter("name", record.info.name)
                .appendQueryParameter("mime", record.info.mime).build()
            try {
                val intent = if (record.info.isPreviewablePhoto()) Intent(this, PhotoActivity::class.java)
                    .setData(uri).putExtra("peer", peer).putExtra("record", record.encode())
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(AttachmentInfo.displayName(record.info.name), uri)
                startActivity(intent)
            } catch (_: ActivityNotFoundException) { showNotice(text("attachmentSaveBeforeOpening")) }
            catch (_: java.io.IOException) { showNotice(text("attachmentUnavailable")) }
        } }
    }
    fun saveAttachment(record: AttachmentRecord) {
        if (state.selectedId == null || state.selectedId != controller?.selectedId) return
        exportPeer = state.selectedId; exportRecord = record
        try {
            export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(record.info.mime)
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, AttachmentInfo.safeName(record.info.name)))
        } catch (_: ActivityNotFoundException) {
            clearAttachmentExport()
            showNotice(text("attachmentFailed"))
        }
    }

    private fun clearAttachmentExport() {
        exportPeer = null
        exportRecord = null
        exportUri = null
        exportFlags = 0
    }

    private fun resumeExport() {
        val c = controller ?: return
        val peer = exportPeer ?: return
        val record = exportRecord ?: return
        val uri = exportUri ?: return
        val flags = exportFlags
        clearAttachmentExport()
        c.exportAttachment(peer, record, uri, flags).whenComplete { _, error ->
            runOnUiThread { if (error != null) showNotice(text("attachmentFailed")) }
        }
    }
    fun copyTransferDiagnostics(snapshot: IdentityVerification?) {
        val c = controller
        if (c == null) { showNotice(text("notConnected")); return }
        if (snapshot == null || state.selectedId != snapshot.peerId || c.selectedId != snapshot.peerId ||
            !c.verificationCurrent(snapshot)) { showNotice(text("verificationConnectionChanged")); return }
        // Preserve the controller's diagnostic schema and privacy rules; do not attribute global values to a file.
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText(text("transferDiagnostics"), c.transferDiagnostics()))
        showNotice(text("diagnosticsCopied"))
    }
    fun verifyIdentity(snapshot: IdentityVerification) = verifyIdentityCode(snapshot, null)
    internal fun copyIdentityCode(snapshot: IdentityVerification) {
        if (controller?.verificationCurrent(snapshot) != true || controller?.selectedId != snapshot.peerId) {
            showNotice(text("verificationConnectionChanged"))
            return
        }
        copySensitiveText(this, text("verificationTitle"), snapshot.code.display())
        showNotice(text("verificationCopyDone"))
    }
    private fun verifyIdentityCode(snapshot: IdentityVerification, code: String?) {
        val c = controller
        if (c == null || c.selectedId != snapshot.peerId || !c.verificationCurrent(snapshot)) {
            showNotice(text("verificationConnectionChanged")); return
        }
        if (code != null && !snapshot.code.matchesQr(code)) { showNotice(text("verificationMismatch")); return }
        c.verifyIdentity(snapshot, code) { success ->
            if (!isDestroyed) showNotice(text(if (success) "verificationDone" else "verificationConnectionChanged"))
        }
    }
    private fun beginIdentity(snapshot: IdentityVerification): Boolean {
        if (identityRequest != null || identityWorking) return false
        if (controller?.selectedId != snapshot.peerId || controller?.verificationCurrent(snapshot) != true) {
            showNotice(text("verificationConnectionChanged")); return false
        }
        identityRequest = snapshot
        state = state.copy(identityBusy = true)
        return true
    }
    fun scanIdentity(snapshot: IdentityVerification) {
        if (beginIdentity(snapshot)) try { identityScan.launch(Intent(this, QrScanActivity::class.java)) }
        catch (_: ActivityNotFoundException) { clearIdentityRequest(); showNotice(text("verificationReadFailed")) }
    }
    fun pickIdentityImage(snapshot: IdentityVerification) {
        if (beginIdentity(snapshot)) try { identityPhoto.launch(arrayOf("image/*")) }
        catch (_: ActivityNotFoundException) { clearIdentityRequest(); showNotice(text("verificationReadFailed")) }
    }
    fun saveIdentityQr(snapshot: IdentityVerification) {
        if (beginIdentity(snapshot)) try { identitySave.launch("wozai-safety-code.png") }
        catch (_: ActivityNotFoundException) { clearIdentityRequest(); showNotice(text("verificationReadFailed")) }
    }
    private fun clearIdentityRequest() {
        identityRequest = null; identityResult = null; identityImage = null; identityExport = null
        state = state.copy(identityBusy = false)
    }
    private fun resumeIdentityResult() {
        val c = controller ?: return // Pickers may return before service rebind.
        val expected = identityRequest ?: return
        if (identityWorking) return
        if (c.selectedId != expected.peerId || !c.verificationCurrent(expected)) {
            clearIdentityRequest(); showNotice(text("verificationConnectionChanged")); return
        }
        identityResult?.let { code -> clearIdentityRequest(); verifyIdentityCode(expected, code); return }
        val imageUri = identityImage
        val saveUri = identityExport
        if (imageUri == null && saveUri == null) return
        identityWorking = true
        identityWorker.execute {
            var decoded: String? = null
            var saved = false
            try {
                if (imageUri != null) {
                    val bitmap = PhotoDecoder.decode(contentResolver, imageUri, 1024)
                    if (bitmap != null) try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        decoded = QrCodes.decode(pixels, bitmap.width, bitmap.height)
                    } finally { bitmap.recycle() }
                } else if (saveUri != null) {
                    val bitmap = identityQrBitmap(expected)
                    try { contentResolver.openOutputStream(saveUri)?.use { saved = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            } catch (_: Exception) { /* Report unsupported images and provider failures below. */ }
            runOnUiThread {
                identityWorking = false
                if (isDestroyed || !expected.sameIdentity(identityRequest)) return@runOnUiThread
                if (saveUri != null) {
                    clearIdentityRequest()
                    showNotice(if (saved) getString(R.string.next_qr_saved) else text("verificationReadFailed"))
                } else if (decoded == null) {
                    clearIdentityRequest(); showNotice(text("verificationReadFailed"))
                } else {
                    identityImage = null; identityResult = decoded
                    resumeIdentityResult() // Recheck current roots and generation after decoding.
                }
            }
        }
    }
    fun setTheme(value: String) { AppAppearance.select(this, value); recreate() }
    fun setLanguage(value: String) { AppLanguage.select(this, value); if (Build.VERSION.SDK_INT < 33) recreate() }
    internal fun setAccent(value: NextAccent) {
        accent = value
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("nextAccent", value.name).apply()
    }
    fun chooseFolder() {
        try { folder.launch(AttachmentLocations.directoryPicker(AttachmentLocations.get(this).directory())) }
        catch (_: Exception) { showNotice(text("receiveFolderUnavailable")) }
    }
    fun stopFolder() { try { AttachmentLocations.get(this).stopDirectory(); refresh() } catch (_: Exception) { showNotice(text("receiveFolderUnavailable")) } }
    fun openAvatar() { startActivity(Intent(this, AvatarActivity::class.java)) }
    fun avatarRoot(peer: String?): String? = if (peer == null) controller?.localAvatarRoot() else controller?.avatarRoot(peer)
    fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    fun text(key: String, vararg args: Any): String = AndroidText.get(this, key, *args)
    fun clearNotice() { notice = ""; state = state.copy(notice = "") }
    private fun showNotice(value: String) { if (!isDestroyed) { notice = value; state = state.copy(notice = value) } }

    private fun requestFileRoutePermissions(photos: Boolean): Boolean {
        val c = controller ?: return false
        if (!resumed || !c.canSendAttachment() || !c.directAvailable() || pending != null || routePermissionPending) return false
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("fileRoutePermissionsAsked", false)) return false
        val required = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        prefs.edit().putBoolean("fileRoutePermissionsAsked", true).apply()
        if (missing.isEmpty()) return false
        routePermissionPending = true
        pickAfterRoute = photos
        return try {
            routePermissions.launch(required.toTypedArray())
            true
        } catch (_: RuntimeException) {
            routePermissionPending = false
            pickAfterRoute = null
            false
        }
    }

    fun visibleMessages(peer: String?, ids: List<String>) { visiblePeer = peer; visibleIds = ids }
    private val readTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            val c = controller
            val eligible = hasWindowFocus() && !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                c != null && visiblePeer == c.selectedId && c.canObserveMessages(visiblePeer)
            val ready = visibility.update(visiblePeer, visibleIds, eligible, System.nanoTime())
            if (ready.isNotEmpty()) c?.observedMessages(visiblePeer, ready)
            main.postDelayed(this, 250)
        }
    }
}

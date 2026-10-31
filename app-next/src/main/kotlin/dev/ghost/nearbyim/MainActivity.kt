package dev.ghost.nearbyim

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ghost.nearbyim.core.*
import dev.ghost.nearbyim.next.*
import dev.ghost.nearbyim.transport.Peer
import java.io.Serializable

/** Compose is an observer of the existing service; it never owns a wire session. */
class MainActivity : ComponentActivity() {
    var state by mutableStateOf(NextState())
        private set
    private var controller: ChatController? = null
    private var bound = false
    private var resumed = false
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
    private var selection: AttachmentSelectionSession? = null
    private var selectionUris: ArrayList<Uri>? = null
    private var selectionConfirmed = false
    private var retryRecordId: String? = null
    private var routePermissionPending = false
    private var exportPeer: String? = null
    private var exportRecord: AttachmentRecord? = null
    private var exportUri: Uri? = null
    private var exportFlags = 0
    private var visiblePeer: String? = null
    private var visibleIds = emptyList<String>()
    private val visibility = ReadVisibility()
    private val main = Handler(Looper.getMainLooper())
    private val observer = Runnable { refresh() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            controller = (binder as ChatService.LocalBinder).service().controller
            val peer = restoredPeer
            if (controller?.selectedId == null && peer != null)
                controller?.selectConversation(ChatStore.Conversation(peer, restoredName ?: "", 0))
            controller?.observe(observer)
            resumeAttachmentResult()
            resumeExport()
            if (pendingStage == "bluetoothResult") finishBluetoothEnable()
            else if (pendingStage == null || pendingStage == "ready") pending?.let { prepare(it) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            controller = null
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
    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { selection = null; return@registerForActivityResult }
        if (uris.size > AttachmentBatchQueue.MAX_PENDING) {
            selection = null; showNotice(text("attachmentBatchLimit", AttachmentBatchQueue.MAX_PENDING))
            return@registerForActivityResult
        }
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
    }
    private val retryDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { selection = null; retryRecordId = null }
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
            catch (_: IllegalArgumentException) { selection = null; selectionUris = null; showNotice(text("attachmentFailed")) }
        } else { selection = null; selectionUris = null }
    }
    private val export = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val peer = exportPeer
        val record = exportRecord
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && peer != null && record != null && uri != null) {
            exportUri = uri; exportFlags = result.data?.flags ?: 0
            resumeExport()
        } else { exportPeer = null; exportRecord = null; exportUri = null }
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

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppAppearance.wrap(AppLanguage.wrap(base)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        selectionConfirmed = savedInstanceState?.getBoolean("selectionConfirmed") ?: false
        retryRecordId = savedInstanceState?.getString("retryRecordId")
        exportPeer = savedInstanceState?.getString("exportPeer")
        exportUri = savedInstanceState?.getString("exportUri")?.let(Uri::parse)
        exportFlags = savedInstanceState?.getInt("exportFlags") ?: 0
        savedInstanceState?.getString("exportRecord")?.let {
            try { exportRecord = AttachmentRecord.decode(it) } catch (_: Exception) { exportPeer = null }
        }
        setContent { NextTheme { NextApp(state, this) } }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ChatService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onResume() { super.onResume(); resumed = true; main.post(readTick); requestFileRoutePermissions() }
    override fun onPause() {
        resumed = false; visibility.clear(); main.removeCallbacks(readTick)
        super.onPause()
    }
    override fun onStop() {
        restoredPeer = state.selectedId; restoredName = state.selectedName
        controller?.unobserve(observer)
        if (bound) unbindService(connection)
        bound = false; controller = null
        super.onStop()
    }
    override fun onDestroy() { draftGeneration++; main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("peer", state.selectedId)
        outState.putString("peerName", state.selectedName)
        outState.putString("handledSave", handledSave)
        outState.putSerializable("pending", pending)
        outState.putString("pendingStage", pendingStage)
        selection?.let {
            outState.putString("selectionPeer", it.peer); outState.putLong("selectionToken", it.token)
            outState.putString("selectionIdentity", it.identity)
        }
        outState.putParcelableArrayList("selectionUris", selectionUris)
        outState.putBoolean("selectionConfirmed", selectionConfirmed)
        outState.putString("retryRecordId", retryRecordId)
        outState.putString("exportPeer", exportPeer)
        outState.putString("exportRecord", exportRecord?.encode())
        outState.putString("exportUri", exportUri?.toString())
        outState.putInt("exportFlags", exportFlags)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        val c = controller
        if (c == null) { state = state.copy(ready = false, canSend = false, canAttach = false); return }
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
        state = NextState(
            ready = true, nickname = c.nickname, localId = c.localId,
            conversations = c.conversations.toList(), trusted = c.trustedDevices.toList(), peers = c.peers.values.toList(),
            messages = c.messages.toList(), selectedId = c.selectedId, selectedName = c.selectedName ?: "",
            connectedId = c.connectedPeerId, connected = c.connected, connecting = c.connecting,
            status = AndroidText.get(this, c.status), error = AndroidText.get(this, c.error),
            lanRunning = c.lanRunning, bluetoothRunning = c.bluetoothRunning,
            lanSearching = c.lanSearching, bluetoothSearching = c.bluetoothSearching,
            lanInfo = AndroidText.get(this, c.lanInfo), bluetoothInfo = AndroidText.get(this, c.bluetoothInfo),
            bluetoothAvailable = c.bluetoothAvailable(), approvalId = c.approvalId, approvalName = c.approvalName ?: "",
            canSend = c.canSend(), canAttach = c.canSendAttachment(), saving = c.savingMessage, textLimit = c.textLimit(),
            draft = draft, draftLoading = draftLoading, notice = notice, readReceipts = c.readReceiptsEnabled(),
            encrypted = c.endToEndEncrypted(c.selectedId), verification = c.verification(c.selectedId),
            receiveFolder = folderName, revision = ++revision,
        )
        requestFileRoutePermissions()
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
        if (draftLoading) return
        if (TextLimits.draftBytes(value) > TextLimits.DRAFT_BYTES) {
            showNotice(text("draftEditTooLarge", TextLimits.DRAFT_BYTES)); return
        }
        draft = value
        AndroidDrafts.get(this).save(peer, value) { error -> if (error != null) showNotice(text("draftSaveFailed")) }
        refresh()
    }
    fun retryDraft() { loadDraft(draftPeer); refresh() }
    fun sendText() {
        val c = controller ?: return
        if (canSubmitText(draft, c.textLimit(), c.canSend(), draftLoading)) c.send(draft)
    }

    fun start(mode: Int) = prepare(Action("start", mode))
    fun stop(mode: Int) { controller?.stop(mode) }
    fun search(mode: Int) = prepare(Action("search", mode))
    fun stopSearch(mode: Int) { controller?.stopSearch(mode) }
    fun discoverable() = prepare(Action("discoverable", Peer.BLUETOOTH))
    fun connect(peer: Peer, remember: Boolean) = prepare(Action("peer", peer.mode, peer = peer, remember = remember))
    fun connectAddress(address: String, remember: Boolean) = prepare(Action("address", address = address, remember = remember))
    fun reconnect(peer: String) = prepare(Action("reconnect", controller?.preferredMode(peer) ?: Peer.LAN, id = peer))
    fun preferredMode(peer: String): Int = controller?.sessionMode?.takeIf { state.connectedId == peer }
        ?: controller?.preferredMode(peer) ?: Peer.LAN
    fun attachmentRoute(record: AttachmentRecord): String = controller?.let { c ->
        c.attachmentRoute(c.selectedId, record).key().takeIf { it.isNotEmpty() }?.let { text(it) }
    } ?: ""
    fun disconnect() { pending = null; pendingStage = null; controller?.disconnect() }
    fun select(conversation: ChatStore.Conversation) { controller?.selectConversation(conversation) }
    fun approve(token: String, remember: Boolean) { controller?.approve(token, remember) }
    fun reject(token: String) { controller?.reject(token) }
    fun setNickname(value: String) { controller?.setNickname(value) }
    fun setReadReceipts(value: Boolean) { controller?.setReadReceiptsEnabled(value) }
    fun revokeTrust(peer: String) { controller?.revokeTrust(peer) }

    private fun required(action: Action): List<String> = when {
        action.mode != Peer.BLUETOOTH -> emptyList()
        Build.VERSION.SDK_INT >= 31 -> buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (action.kind == "search" || action.kind == "peer") add(Manifest.permission.BLUETOOTH_SCAN)
            if (action.kind == "discoverable") add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        action.kind == "search" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
            if (!c.running(action.mode)) {
                startForegroundService(Intent(this, ChatService::class.java).setAction(ChatService.START))
                c.start(action.mode)
            }
            when (action.kind) {
                "search" -> c.search(action.mode)
                "peer" -> action.peer?.let { c.connect(it, action.remember) }
                "address" -> c.connectAddress(action.address, action.remember)
                "reconnect" -> c.reconnect(action.id)
                "discoverable" -> discoverableResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }
        } catch (_: RuntimeException) { showNotice(text("bluetoothFailed")) }
    }

    fun pickAttachments(photos: Boolean) {
        val c = controller ?: return
        val peer = c.selectedId ?: return
        val identity = c.attachmentSelectionSession(peer) ?: return
        if (!c.canSendAttachment()) return
        selection = AttachmentSelectionSession(peer, c.attachmentSessionToken(), identity)
        retryRecordId = null
        documents.launch(arrayOf(if (photos) "image/*" else "*/*"))
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
            selection = null; showNotice(text("notConnected")); return
        }
        if (selectionConfirmed) {
            selection = null
            val retry = retryRecordId
            retryRecordId = null
            if (retry == null) c.sendAttachments(captured.peer, captured.token, uris)
            else if (uris.size == 1) c.retryAttachmentWithSelection(captured.peer, captured.token, captured.identity, retry, uris.single())
            else showNotice(text("attachmentRetrySelectAgain"))
        } else {
            attachmentPreview.launch(Intent(this, AttachmentSelectionActivity::class.java)
                .putParcelableArrayListExtra(AttachmentSelection.URIS, uris))
        }
    }
    fun attachmentAction(record: AttachmentRecord, action: String) {
        val c = controller ?: return
        val peer = c.selectedId ?: return
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
                    retryDocument.launch(arrayOf(record.info.mime))
                }
            } }
        }
        else c.attachmentAction(peer, record.info.id, record.outgoing, action)
    }
    fun openAttachment(record: AttachmentRecord) {
        if (!record.info.mime.startsWith("image/") && !record.info.canOpenExternally()) {
            saveAttachment(record); return
        }
        val c = controller ?: return
        val peer = c.selectedId ?: return
        c.attachmentPath(peer, record).whenComplete { path, error -> runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (error != null) { showNotice(text("attachmentUnavailable")); return@runOnUiThread }
            val uri = Uri.Builder().scheme("content").authority("$packageName.attachments")
                .appendPath(peer).appendPath(path.fileName.toString()).appendQueryParameter("name", record.info.name)
                .appendQueryParameter("mime", record.info.mime).build()
            try {
                val intent = if (record.info.mime.startsWith("image/")) Intent(this, PhotoActivity::class.java)
                    .setData(uri).putExtra("peer", peer).putExtra("record", record.encode())
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(AttachmentInfo.displayName(record.info.name), uri)
                startActivity(intent)
            } catch (_: ActivityNotFoundException) { showNotice(text("attachmentSaveBeforeOpening")) }
            catch (_: java.io.IOException) { showNotice(text("attachmentUnavailable")) }
        } }
    }
    fun saveAttachment(record: AttachmentRecord) {
        exportPeer = state.selectedId; exportRecord = record
        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(record.info.mime)
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, AttachmentInfo.safeName(record.info.name)))
    }
    private fun resumeExport() {
        val c = controller ?: return
        val peer = exportPeer ?: return
        val record = exportRecord ?: return
        val uri = exportUri ?: return
        val flags = exportFlags
        exportPeer = null; exportRecord = null; exportUri = null
        c.exportAttachment(peer, record, uri, flags).whenComplete { _, error ->
            runOnUiThread { if (error != null) showNotice(text("attachmentFailed")) }
        }
    }
    fun verifyIdentity(snapshot: IdentityVerification) {
        controller?.verifyIdentity(snapshot, null) { success ->
            showNotice(text(if (success) "verificationDone" else "verificationConnectionChanged"))
        }
    }
    fun setTheme(value: String) { AppAppearance.select(this, value); recreate() }
    fun setLanguage(value: String) { AppLanguage.select(this, value); if (Build.VERSION.SDK_INT < 33) recreate() }
    fun setDynamicColor(value: Boolean) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("nextDynamicColor", value).apply(); recreate()
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

    private fun requestFileRoutePermissions() {
        val c = controller ?: return
        if (!resumed || !c.canSendAttachment() || !c.directAvailable() || pending != null || routePermissionPending) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("fileRoutePermissionsAsked", false)) return
        val required = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        prefs.edit().putBoolean("fileRoutePermissionsAsked", true).apply()
        if (missing.isNotEmpty()) { routePermissionPending = true; routePermissions.launch(required.toTypedArray()) }
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

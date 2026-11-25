package dev.ghost.nearbyim.mesh

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** UI-facing commands only. MeshService exclusively owns processes, radios, and workers. */
object MeshClient {
    private val mutableState = MutableStateFlow(MeshSnapshot())
    val state: StateFlow<MeshSnapshot> = mutableState.asStateFlow()
    private val gate = Any()
    private var request = 0L
    private var sendSequence = SystemClock.elapsedRealtimeNanos() / 1_000

    fun start(context: Context, mode: String, relay: Boolean) {
        val token = synchronized(gate) {
            if (mutableState.value.running || mutableState.value.starting) return
            val error = MeshAndroidPlatform.guard(context, mode)
            if (error != null) {
                mutableState.value = mutableState.value.copy(error = error)
                return
            }
            request += 1
            mutableState.value = mutableState.value.copy(starting = true, mode = mode, relay = relay,
                localKey = "", neighbors = emptyList(), error = "", notice = "mesh_hardware_pending", rejectedRequest = null)
            request
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java)
                .setAction(MeshService.START).putExtra(MeshService.TOKEN, token)
                .putExtra(MeshService.MODE, mode).putExtra(MeshService.RELAY, relay))
        } catch (_: RuntimeException) {
            publish(token, state.value.copy(starting = false, running = false, relay = false, error = "mesh_service_unavailable"))
        }
    }

    internal suspend fun restoreReception(context: Context, radios: Flow<Unit>) {
        // The caller is the resumed Activity. Runtime reconnection within a running radio stays with the service.
        val availability = combine(MeshConsent.automaticModes(context), radios) { mode, _ ->
            mode to mode?.let { MeshAndroidPlatform.guard(context, it) }
        }.distinctUntilChanged()
        var retries = 0
        var lastAvailability: Pair<String?, String?>? = null
        combine(
            availability,
            state.map { it.running to it.starting }.distinctUntilChanged(),
        ) { available, phase -> available to phase }.collectLatest { (available, phase) ->
            val (mode, blocked) = available
            val (running, starting) = phase
            if (running || available != lastAvailability) retries = 0
            lastAvailability = available
            if (mode == null || running || starting || retries >= 4) return@collectLatest
            if (blocked == null && retries > 0) delay(2_000L shl (retries - 1))
            if (MeshConsent.automaticMode(context) != mode || state.value.running || state.value.starting) return@collectLatest
            if (blocked == null) retries++
            start(context, mode, false)
        }
    }

    fun stop(context: Context) {
        MeshConsent.pause(context)
        synchronized(gate) {
            request += 1
            mutableState.value = stopped(mutableState.value)
        }
        context.stopService(Intent(context, MeshService::class.java))
    }

    fun send(context: Context, text: String, nickname: String): String? {
        val body = MeshText.withNickname(text, nickname)
        if (text.isBlank() || !MeshText.validBody(body)) {
            synchronized(gate) { mutableState.value = mutableState.value.copy(error = "mesh_send_invalid") }
            return null
        }
        val sendRequest = synchronized(gate) { "s${++sendSequence}" }
        return if (command(context, MeshService.SEND) {
            putExtra(MeshService.TEXT, body)
            putExtra(MeshService.SEND_REQUEST, sendRequest)
        }) sendRequest else null
    }

    fun setRelay(context: Context, enabled: Boolean) {
        command(context, MeshService.SET_RELAY) { putExtra(MeshService.RELAY, enabled) }
    }

    private fun command(context: Context, action: String, extras: Intent.() -> Unit): Boolean {
        val token = synchronized(gate) {
            if (!mutableState.value.running) return false
            request
        }
        val started = try {
            context.startService(Intent(context, MeshService::class.java).setAction(action)
                .putExtra(MeshService.TOKEN, token).apply(extras)) != null
        } catch (_: RuntimeException) {
            false
        }
        if (!started) synchronized(gate) {
            if (token == request) mutableState.value = mutableState.value.copy(error = "mesh_service_unavailable")
        }
        return started
    }

    internal fun isCurrent(token: Long): Boolean = synchronized(gate) { token == request }
    internal fun publish(token: Long, snapshot: MeshSnapshot) {
        synchronized(gate) { if (token == request) mutableState.value = snapshot }
    }
    internal fun serviceEnded(token: Long) {
        synchronized(gate) { if (token == request) mutableState.value = stopped(mutableState.value) }
    }
    private fun stopped(snapshot: MeshSnapshot): MeshSnapshot = snapshot.copy(running = false, starting = false,
        relay = false, neighbors = emptyList(), messages = snapshot.messages.map {
            if (it.own && it.state == "queued") it.copy(state = "stopped_unconfirmed") else it
        })
}

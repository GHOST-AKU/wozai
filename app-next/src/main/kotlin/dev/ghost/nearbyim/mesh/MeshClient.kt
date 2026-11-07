package dev.ghost.nearbyim.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing commands only. MeshService exclusively owns processes, radios, and workers. */
object MeshClient {
    private val mutableState = MutableStateFlow(MeshSnapshot())
    val state: StateFlow<MeshSnapshot> = mutableState.asStateFlow()
    private val gate = Any()
    private var request = 0L

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
                localKey = "", neighbors = emptyList(), error = "", notice = "mesh_hardware_pending")
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

    fun stop(context: Context) {
        synchronized(gate) {
            request += 1
            mutableState.value = stopped(mutableState.value)
        }
        context.stopService(Intent(context, MeshService::class.java))
    }

    fun send(context: Context, text: String) {
        if (!MeshText.valid(text)) {
            synchronized(gate) { mutableState.value = mutableState.value.copy(error = "mesh_send_invalid") }
            return
        }
        command(context, MeshService.SEND) { putExtra(MeshService.TEXT, text) }
    }

    fun setRelay(context: Context, enabled: Boolean) {
        command(context, MeshService.SET_RELAY) { putExtra(MeshService.RELAY, enabled) }
    }

    private fun command(context: Context, action: String, extras: Intent.() -> Unit) {
        val token = synchronized(gate) {
            if (!mutableState.value.running) return
            request
        }
        try {
            context.startService(Intent(context, MeshService::class.java).setAction(action)
                .putExtra(MeshService.TOKEN, token).apply(extras))
        } catch (_: RuntimeException) {
            synchronized(gate) {
                if (token == request) mutableState.value = mutableState.value.copy(error = "mesh_service_unavailable")
            }
        }
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

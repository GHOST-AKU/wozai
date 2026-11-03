package dev.ghost.nearbyim.mesh

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Bounded, process-local metadata only. Never retain peer labels, payloads or raw exceptions. */
internal object MeshDiagnostics {
    private val safeReason = Regex("[a-z_]{1,64}|(?:gatt|server)_connection_-?[0-9]{1,4}_-?[0-9]{1,4}|(?:cccd_write|att_ack)_-?[0-9]{1,4}|attempt_[1-4]|mtu_[0-9]{2,3}")
    private val safeEvent = Regex("[a-z_]{1,48}")
    private var generation = 0L
    private var started = 0L
    private var sessionMode = "none"
    private var total = 0L
    private val events = ArrayDeque<JSONObject>()
    @Synchronized fun begin(mode: String = "ble"): Long {
        sessionMode = if (mode == "ble" || mode == "aware") mode else "unknown"
        generation++
        started = SystemClock.elapsedRealtime()
        total = 0
        events.clear()
        return generation
    }
    @Synchronized fun record(token: Long, event: String, reason: String, tx: Long, rx: Long) {
        if (token != generation) return
        total++
        // Only internal symbolic codes survive. Free-form platform exception text is omitted.
        val code = if (reason.matches(safeReason)) reason else "redacted"
        val name = if (event.matches(safeEvent)) event else "unknown"
        events.addLast(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("event", name).put("reason_code", code)
            .put("tx_bytes_reported", tx.coerceAtLeast(0)).put("rx_bytes_reported", rx.coerceAtLeast(0)))
        while (events.size > 64) events.removeFirst()
    }
    @Synchronized internal fun radio(): JSONObject = JSONObject()
        .put("session", generation).put("mode", sessionMode).put("started_elapsed_ms", started).put("events_observed", total)
        .put("events_dropped_from_history", (total - events.size).coerceAtLeast(0))
        .put("events", JSONArray(events.toList()))

    fun report(context: Context, state: MeshSnapshot): String {
        val pm = context.packageManager
        val permissions = JSONObject()
        for (permission in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) {
            val introduced = when (permission) {
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE -> 31
                Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS -> 33
                else -> 1
            }
            permissions.put(permission.substringAfterLast('.'), if (Build.VERSION.SDK_INT < introduced) "not_applicable"
                else if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "not_granted")
        }
        val features = JSONObject()
        for (feature in listOf(PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_WIFI_AWARE,
            PackageManager.FEATURE_WIFI_DIRECT, PackageManager.FEATURE_WIFI)) features.put(feature, pm.hasSystemFeature(feature))
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val version = pm.getPackageInfo(context.packageName, 0)
        return JSONObject().put("schema", "nearby-mesh-diagnostics-v1")
            .put("captured_at_ms", System.currentTimeMillis())
            .put("app", JSONObject().put("package", context.packageName).put("version", version.versionName))
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER.take(80)).put("model", Build.MODEL.take(80))
                .put("hardware", Build.HARDWARE.take(80)).put("android_release", Build.VERSION.RELEASE.take(40))
                .put("sdk", Build.VERSION.SDK_INT).put("security_patch", Build.VERSION.SECURITY_PATCH)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("cpu_cores", runtime.availableProcessors()))
            .put("features", features).put("permissions", permissions)
            .put("system", JSONObject().put("power_save", power?.isPowerSaveMode)
                .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else JSONObject.NULL)
                .put("app_process_cpu_ms", android.os.Process.getElapsedCpuTime())
                .put("interactive", power?.isInteractive).put("device_idle", power?.isDeviceIdleMode)
                .put("battery_optimization_exempt", power?.isIgnoringBatteryOptimizations(context.packageName))
                .put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                .put("battery_scale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
                .put("battery_temperature_tenths_c", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                .put("system_available_memory_bytes", memory.availMem).put("system_low_memory", memory.lowMemory)
                .put("app_java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory()))
            .put("mesh", JSONObject().put("mode", state.mode).put("running", state.running).put("starting", state.starting)
                .put("relay", state.relay).put("direct_neighbors", state.neighbors.size).put("error_code", state.error.take(80))
                .put("messages", JSONArray(state.messages.takeLast(200).map { message ->
                    JSONObject().put("id", message.id.take(128)).put("hops", message.hops)
                        .put("own", message.own).put("state", message.state).put("created_at_ms", message.createdMillis)
                })))
            .put("radio", radio())
            .put("measurement_notes", "Event byte counters are adapter-reported snapshots, not additive totals or throughput. Java heap excludes native Core. No RSSI, RF distance, end-to-end latency or hardware validation is inferred. Message IDs can correlate reports; no bodies, keys, peer labels, MAC, IP, SSID or serial numbers.")
            .toString(2)
    }
}

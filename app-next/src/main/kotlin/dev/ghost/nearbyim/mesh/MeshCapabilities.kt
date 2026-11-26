package dev.ghost.nearbyim.mesh

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Hardware eligibility is separate from permission, radio availability and an authenticated link. */
internal data class MeshCapabilities(val api: Int, val arm64Process: Boolean, val ble: Boolean, val aware: Boolean) {
    val platformSupported: Boolean get() = api >= 29 && arm64Process
    val modes: List<String> get() = if (!platformSupported) emptyList() else buildList {
        if (ble) add("ble")
        if (aware) add("aware")
    }
    val defaultMode: String? get() = modes.firstOrNull()
    fun supports(mode: String): Boolean = mode in modes

    companion object {
        fun read(context: Context) = MeshCapabilities(Build.VERSION.SDK_INT,
            Process.is64Bit() && Build.SUPPORTED_ABIS.contains("arm64-v8a"),
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
    }
}

/** Notice and saved reception are separate; older acknowledgments never opt into automatic startup. */
internal object MeshConsent {
    private const val VERSION = 2
    fun acknowledged(context: Context): Boolean = preferences(context).getInt("public-notice-version", 0) >= VERSION
    fun acknowledge(context: Context) { preferences(context).edit().putInt("public-notice-version", VERSION).apply() }
    fun automaticMode(context: Context): String? =
        if (acknowledged(context)) preferences(context).getString("public-auto-mode", null) else null
    fun automaticModes(context: Context) = callbackFlow {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "public-auto-mode" || key == "public-notice-version" || key == null)
                trySend(automaticMode(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(automaticMode(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun selectedMode(context: Context): String? = preferences(context).getString("public-last-mode", null)
    fun enable(context: Context, mode: String) {
        if (!acknowledged(context) || mode !in listOf("ble", "aware")) return
        preferences(context).edit().putString("public-auto-mode", mode).putString("public-last-mode", mode).apply()
    }
    fun pause(context: Context) { preferences(context).edit().remove("public-auto-mode").apply() }
    private fun preferences(context: Context) = context.getSharedPreferences("mesh-preview", Context.MODE_PRIVATE)
}

package dev.ghost.nearbyim.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

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

/** Acknowledgment never enables a service or restores relay intent. */
internal object MeshConsent {
    private const val VERSION = 1
    fun acknowledged(context: Context): Boolean = preferences(context).getInt("public-notice-version", 0) >= VERSION
    fun acknowledge(context: Context) { preferences(context).edit().putInt("public-notice-version", VERSION).apply() }
    private fun preferences(context: Context) = context.getSharedPreferences("mesh-preview", Context.MODE_PRIVATE)
}

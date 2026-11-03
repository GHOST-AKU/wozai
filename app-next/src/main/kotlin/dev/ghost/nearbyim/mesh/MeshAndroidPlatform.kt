package dev.ghost.nearbyim.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process as AndroidProcess
import android.os.Looper
import android.net.wifi.aware.WifiAwareManager
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import org.nearbymesh.radio.AwareAdapter
import org.nearbymesh.radio.BleAdapter
import org.nearbymesh.radio.RadioAdapter
import org.nearbymesh.radio.RadioConfig
import org.nearbymesh.radio.RadioEvents

internal class MeshAndroidPlatform(context: Context) : MeshPlatform {
    private val context = context.applicationContext

    override fun check(mode: String): String? = guard(context, mode)

    override fun launch(relay: Boolean): Process {
        val executable = File(context.applicationInfo.nativeLibraryDir, "libmesh_core.so")
        val profile = File(context.filesDir, "mesh-v1")
        if (!profile.isDirectory && !profile.mkdirs()) throw IOException("mesh_profile_unavailable")
        val arguments = mutableListOf(executable.absolutePath, "run", "--state", profile.absolutePath,
            "--listen", "127.0.0.1:0")
        if (relay) arguments.add("--relay")
        // A stop/restart can recreate the Service before its old teardown actor finishes.
        // Keep the private core profile exclusive across those service instances as well.
        if (!coreOwner.tryAcquire(5, TimeUnit.SECONDS)) throw IOException("mesh_core_still_stopping")
        return try { OwnedCoreProcess(ProcessBuilder(arguments).directory(context.filesDir).start()) { coreOwner.release() } }
        catch (error: Exception) { coreOwner.release(); throw error }
    }

    override fun startRadio(mode: String, port: Int, failed: () -> Unit): AutoCloseable {
        val config = RadioConfig.publicMesh(mode, label(), port)
        val thread = HandlerThread("mesh-radio-callbacks").apply { start() }
        val handler = Handler(thread.looper)
        val started = CountDownLatch(1)
        val startupFailed = AtomicBoolean()
        val terminalSent = AtomicBoolean()
        val closed = AtomicBoolean()
        var adapter: RadioAdapter? = null
        val owner = AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                val cleaned = CountDownLatch(1)
                if (!handler.post {
                    try { adapter?.close() } catch (_: Exception) { }
                    finally {
                        // Adapter close() can enqueue teardown on the same radio Handler.
                        if (!handler.post { cleaned.countDown() }) cleaned.countDown()
                    }
                }) cleaned.countDown()
                try { cleaned.await(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                thread.quitSafely()
                try { thread.join(1000) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        val diagnosticSession = MeshDiagnostics.begin(mode)
        val events = RadioEvents { event, peer, reason, tx, rx ->
            MeshDiagnostics.record(diagnosticSession, event, reason ?: "", tx, rx)
            // Every adapter callback belongs to this session's radio Handler. Ignore labels
            // and raw reasons: only the core's authenticated keys populate neighbor state.
            val terminal = event == "terminal_failure" || event == "unsupported" || event == "unavailable" ||
                (event == "ble_error" && peer.isNullOrEmpty())
            if (terminal && !closed.get() && terminalSent.compareAndSet(false, true)) {
                if (Looper.myLooper() == handler.looper) failed()
                else handler.post { if (!closed.get()) failed() }
            }
        }
        if (!handler.post {
            try {
                if (!closed.get()) {
                    adapter = if (mode == "ble") BleAdapter(context, handler, config, events)
                    else AwareAdapter(context, handler, config, events)
                    adapter?.start()
                }
            } catch (_: Exception) { startupFailed.set(true) }
            finally { started.countDown() }
        }) startupFailed.set(true)
        val completed = try { started.await(5, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!completed || startupFailed.get()) {
            owner.close()
            throw IOException("mesh_radio_start_failed")
        }
        return owner
    }

    private fun label(): String {
        val preferences = context.getSharedPreferences("mesh-preview", Context.MODE_PRIVATE)
        val old = preferences.getString("label", null)
        if (old != null && Regex("n[0-9a-f]{7}").matches(old)) return old
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val label = "n" + bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.take(7)
        if (!preferences.edit().putString("label", label).commit()) throw IOException("mesh_label_unavailable")
        return label
    }

    companion object {
        private val coreOwner = Semaphore(1)
        @SuppressLint("MissingPermission") // Every mode's permissions are checked below before querying its radio.
        fun guard(context: Context, mode: String): String? {
            if (mode != "ble" && mode != "aware") return "mesh_unsupported_radio"
            if (Build.VERSION.SDK_INT < 29 || !AndroidProcess.is64Bit() || !Build.SUPPORTED_ABIS.contains("arm64-v8a"))
                return "mesh_unsupported_platform"
            val feature = if (mode == "ble") PackageManager.FEATURE_BLUETOOTH_LE else PackageManager.FEATURE_WIFI_AWARE
            if (!context.packageManager.hasSystemFeature(feature)) return "mesh_unsupported_radio"
            val permissions = if (mode == "ble") {
                if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return "mesh_permission_required"
            legacyLocationError(context, mode)?.let { return it }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return "mesh_permission_required"
            try {
                if (mode == "ble") {
                    val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
                    if (bluetooth == null || !bluetooth.isEnabled) return "mesh_radio_failed"
                } else {
                    val aware = context.getSystemService(WifiAwareManager::class.java)
                    if (aware == null || !aware.isAvailable) return "mesh_radio_failed"
                }
            } catch (_: SecurityException) { return "mesh_permission_required" }
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: return "mesh_core_missing"
            val executable = File(nativeDir, "libmesh_core.so")
            if (!executable.isFile || !executable.canExecute()) return "mesh_core_missing"
            return null
        }

        internal fun legacyLocationError(context: Context, mode: String): String? {
            if (mode != "ble" || (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 30)) return null
            val location = context.getSystemService(LocationManager::class.java)
            return if (location?.isLocationEnabled == true) null else "mesh_location_required"
        }
    }
}

/** Release the app-level profile owner only after the executable has actually exited. */
private class OwnedCoreProcess(private val child: Process, private val release: () -> Unit) : Process() {
    private val released = AtomicBoolean()
    private fun exited() { if (released.compareAndSet(false, true)) release() }
    override fun getInputStream() = child.inputStream
    override fun getOutputStream() = child.outputStream
    override fun getErrorStream() = child.errorStream
    override fun waitFor(): Int = child.waitFor().also { exited() }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = child.waitFor(timeout, unit).also { if (it) exited() }
    override fun exitValue(): Int = child.exitValue().also { exited() }
    override fun destroy() = child.destroy()
    override fun destroyForcibly(): Process { child.destroyForcibly(); return this }
}

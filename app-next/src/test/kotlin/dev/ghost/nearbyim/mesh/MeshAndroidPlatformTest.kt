package dev.ghost.nearbyim.mesh

import android.content.Context
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class MeshAndroidPlatformTest {
    @Test fun legacyBleRequiresEnabledSystemLocationAndCanRetryAfterEnabling() {
        val context: Context = RuntimeEnvironment.getApplication()
        val location = context.getSystemService(LocationManager::class.java)
        shadowOf(location).setLocationEnabled(false)
        assertEquals("mesh_location_required", MeshAndroidPlatform.legacyLocationError(context, "ble"))
        shadowOf(location).setLocationEnabled(true)
        assertNull(MeshAndroidPlatform.legacyLocationError(context, "ble"))
    }

    @Test fun legacyBleLocationGateDoesNotChangeAwarePolicy() {
        val context: Context = RuntimeEnvironment.getApplication()
        val location = context.getSystemService(LocationManager::class.java)
        shadowOf(location).setLocationEnabled(false)
        assertNull(MeshAndroidPlatform.legacyLocationError(context, "aware"))
    }
}

package dev.ghost.nearbyim.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshCapabilitiesTest {
    @Test fun hardwareModesRequireAndroidTenAndArm64Process() {
        assertTrue(MeshCapabilities(28, true, true, true).modes.isEmpty())
        assertTrue(MeshCapabilities(36, false, true, true).modes.isEmpty())
        assertEquals(listOf("ble", "aware"), MeshCapabilities(29, true, true, true).modes)
    }

    @Test fun onlyAdvertisedHardwareCanBeSelectedAndAwareCanBeDefault() {
        val ble = MeshCapabilities(34, true, true, false)
        assertEquals(listOf("ble"), ble.modes)
        assertFalse(ble.supports("aware"))
        val aware = MeshCapabilities(34, true, false, true)
        assertEquals("aware", aware.defaultMode)
        assertFalse(aware.supports("ble"))
        assertFalse(aware.supports("lan"))
        assertNull(MeshCapabilities(34, true, false, false).defaultMode)
    }
}

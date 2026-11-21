package dev.ghost.nearbyim.mesh

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshConsentTest {
    @Test fun restoreRequiresNewOptInAndStopDisablesIt() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("mesh-preview", Context.MODE_PRIVATE)
        preferences.edit().putInt("public-notice-version", 1).putString("label", "n1234567").commit()
        assertFalse(MeshConsent.acknowledged(context))
        MeshConsent.enable(context, "ble")
        assertNull(MeshConsent.automaticMode(context))
        MeshConsent.acknowledge(context)
        assertNull(MeshConsent.automaticMode(context))
        MeshConsent.enable(context, "aware")
        assertEquals("aware", MeshConsent.automaticMode(context.createConfigurationContext(context.resources.configuration)))
        MeshClient.stop(context)
        assertNull(MeshConsent.automaticMode(context))
        assertEquals("aware", MeshConsent.selectedMode(context))
        assertEquals("n1234567", preferences.getString("label", null))
    }

    @Test fun noticeAcknowledgmentPersistsWithoutStartingOrReplacingExistingProfileSettings() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("mesh-preview", Context.MODE_PRIVATE)
        preferences.edit().putString("label", "n1234567").commit()
        val before = MeshClient.state.value
        assertFalse(MeshConsent.acknowledged(context))
        MeshConsent.acknowledge(context)
        assertTrue(MeshConsent.acknowledged(context.createConfigurationContext(context.resources.configuration)))
        assertEquals("n1234567", preferences.getString("label", null))
        assertEquals(before, MeshClient.state.value)
    }
}

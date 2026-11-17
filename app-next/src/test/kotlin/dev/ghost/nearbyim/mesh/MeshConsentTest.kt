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

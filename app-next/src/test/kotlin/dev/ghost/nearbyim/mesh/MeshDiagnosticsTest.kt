package dev.ghost.nearbyim.mesh

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class MeshDiagnosticsTest {
    @Test fun historyIsBoundedAndOldSessionsCannotContaminateNewOnes() {
        val old = MeshDiagnostics.begin()
        repeat(1000) { MeshDiagnostics.record(old, "ble_retry", "private text 192.168.1.1", 12, 5) }
        val radio = MeshDiagnostics.radio()
        assertEquals(64, radio.getJSONArray("events").length())
        assertEquals(936, radio.getInt("events_dropped_from_history"))
        assertFalse(radio.toString().contains("192.168"))
        MeshDiagnostics.begin()
        MeshDiagnostics.record(old, "ble_retry", "stale", 1, 1)
        assertEquals(0, MeshDiagnostics.radio().getInt("events_observed"))
    }
    @Test fun exportIncludesCapabilitiesButExcludesBodiesAndIdentities() {
        val state = MeshSnapshot(localKey = "secret_identity", neighbors = listOf("peer_identity"),
            messages = listOf(MeshMessage("message_id", "private_payload", "origin_key", 2, false, "received", 123)))
        val report = MeshDiagnostics.report(RuntimeEnvironment.getApplication(), state)
        for (secret in listOf("secret_identity", "peer_identity", "private_payload", "origin_key")) assertFalse(report.contains(secret))
        val json = JSONObject(report)
        assertEquals(30, json.getJSONObject("device").getInt("sdk"))
        assertEquals(1, json.getJSONObject("mesh").getInt("direct_neighbors"))
        assertEquals(2, json.getJSONObject("mesh").getJSONArray("messages").getJSONObject(0).getInt("hops"))
        assertTrue(json.has("permissions"))
        assertTrue(json.has("features"))
    }
}

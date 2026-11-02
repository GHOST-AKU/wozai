package dev.ghost.nearbyim.next

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.ChatService
import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NextFeatureUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // Robolectric's default bindService callback supplies a null binder. These
    // tests exercise the real UI with the external Android service unavailable.
    @get:Rule val rules: TestRule = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).declareComponentUnbindable(ComponentName(application, ChatService::class.java))
        }
    }).around(compose)

    @Test fun emojiEntryIsAvailableWhileOffline() {
        compose.activity.setContent { NextTheme { ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture"), compose.activity, showBack = true, onBack = {}) } }
        compose.onNodeWithContentDescription("表情").assertIsDisplayed().performClick()
        compose.onNodeWithTag("emoji-search").assertIsDisplayed()
    }
    @Test fun shortBubbleShrinksAndLongBubbleStaysWithinViewport() {
        fun message(id: String, body: String): ChatStore.Message {
            val constructor = ChatStore.Message::class.java.getDeclaredConstructor(String::class.java, String::class.java,
                String::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, AttachmentRecord::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(id, body, "delivered", true, 0L, null)
        }
        compose.activity.setContent { NextTheme {
            ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture",
                messages = listOf(message("short", "Hi"), message("long", "Long text ".repeat(40)))),
                compose.activity, showBack = true, onBack = {})
        } }
        val short = compose.onNodeWithTag("bubble-short").fetchSemanticsNode().boundsInRoot
        val long = compose.onNodeWithTag("bubble-long").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
        assertTrue("short text should size to content", short.width < long.width / 2)
        assertTrue("long text must stay inside viewport", long.left >= viewport.left && long.right <= viewport.right)
    }

    @Test fun measurementChangesRenderWithoutReplacingAttachment() {
        val info = AttachmentInfo.v2("01234567-1234-1234-1234-0123456789ab", "fixture", "text/plain", 1000, null, 0)
        val record = AttachmentRecord(info, true, "transferring", 250)
        val measurement = mutableStateOf(TransferProgress.UNKNOWN)
        val constructor = ChatStore.Message::class.java.getDeclaredConstructor(String::class.java, String::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, AttachmentRecord::class.java)
        constructor.isAccessible = true
        val message = constructor.newInstance("attachment", "", "pending", true, 0L, record)
        val messages = listOf(message)
        compose.activity.setContent { NextTheme {
            ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture", messages = messages,
                transferMeasurements = mapOf("attachment:true" to measurement.value)), compose.activity, showBack = true, onBack = {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.next_measurement_pending)).assertIsDisplayed()
        compose.runOnIdle { measurement.value = TransferProgress(125, 6) }
        compose.onAllNodesWithText("125", substring = true).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.next_measurement_pending)).assertDoesNotExist()
    }

    @Test fun fullCatalogSupportsLocalizedSearchAndRecentVariantPersistence() {
        val context = compose.activity
        val data = EmojiCatalog(context.resources.openRawResource(R.raw.emoji_catalog))
        assertTrue(data.all().size > 3500)
        val results = data.browse("recent", "火箭", java.util.Locale.SIMPLIFIED_CHINESE, "")
        assertTrue(results.any { it.text == "🚀" })
        val variant = data.all().first { it.text == "👩🏽‍💻" }
        assertTrue(data.variants(variant).size > 1)
        assertEquals(variant.text, data.recent(data.remember("", variant)).first().text)
        var picked = ""
        compose.activity.setContent { NextTheme { EmojiPanel(onDismiss = {}, onPick = { picked = it }) } }
        compose.onNodeWithTag("emoji-search").performTextInput("火箭")
        compose.waitUntil(10000) { compose.onAllNodesWithText("🚀").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("🚀").performClick()
        compose.runOnIdle { assertEquals("🚀", picked) }
        assertTrue(data.recent(context.getSharedPreferences("ui", 0).getString("emojiRecent", "")).any { it.text == "🚀" })
    }

    @Test fun pendingQrSnapshotSurvivesActivityRecreation() {
        val snapshot = IdentityVerification("fixture", DeviceIdentity.generate().publicKey(), DeviceIdentity.generate().publicKey(), "generation-one", false)
        val field = MainActivity::class.java.getDeclaredField("identityRequest").apply { isAccessible = true }
        compose.runOnIdle { field.set(compose.activity, snapshot) }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertTrue(snapshot.sameIdentity(field.get(compose.activity) as IdentityVerification)) }
    }

    @Test fun manualVerificationRejectsUnavailableSession() {
        val local = DeviceIdentity.generate().publicKey()
        val remote = DeviceIdentity.generate().publicKey()
        val snapshot = IdentityVerification("fixture", local, remote, "generation-one", false)
        compose.runOnIdle { compose.activity.verifyIdentity(snapshot) }
        assertEquals(compose.activity.text("verificationConnectionChanged"), compose.activity.state.notice)
        assertTrue(!snapshot.sameIdentity(IdentityVerification("fixture", local, remote, "generation-two", false)))
        assertTrue(!snapshot.sameIdentity(IdentityVerification("other", local, remote, "generation-one", false)))
        val bitmap = identityQrBitmap(snapshot)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertEquals(snapshot.code.qrPayload(), dev.ghost.nearbyim.verification.QrCodes.decode(pixels, bitmap.width, bitmap.height))
        bitmap.recycle()
    }

    @Test fun existingTransferDiagnosticsEntryIsDiscoverable() {
        compose.activity.setContent { NextTheme { ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture"),
            compose.activity, showBack = true, onBack = {}) } }
        compose.onNodeWithContentDescription(compose.activity.text("deviceInfo")).performClick()
        compose.onNodeWithText(compose.activity.text("transferDiagnostics"), substring = true).performScrollTo().assertIsDisplayed().performClick()
        // The service is deliberately unavailable: copying must fail clearly, never fabricate diagnostics.
        compose.runOnIdle { assertEquals(compose.activity.text("notConnected"), compose.activity.state.notice) }
    }

    @Test fun verificationResultRemainsVisibleInsideDeviceInfoDialog() {
        compose.activity.setContent { NextTheme { ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture",
            notice = "QR verification result"), compose.activity, showBack = true, onBack = {}) } }
        compose.onNodeWithContentDescription(compose.activity.text("deviceInfo")).performClick()
        compose.onNodeWithText("QR verification result").performScrollTo().assertIsDisplayed()
    }

}

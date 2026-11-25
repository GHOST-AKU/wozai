package dev.ghost.nearbyim.next

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.PhotoDecoder
import dev.ghost.nearbyim.ChatService
import dev.ghost.nearbyim.ChatStore
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.*
import dev.ghost.nearbyim.transport.Peer
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

    @Test fun projectLicenseCanBeReadFromSettingsWithoutOpeningABrowser() {
        val host = compose.activity
        host.setContent { NextTheme { SettingsScreen(NextState(ready = true), host) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("开源许可证"))
        compose.onNodeWithText("开源许可证").assertIsDisplayed().performClick()
        compose.onNodeWithText("WoZai · AGPL-3.0-only").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("ZXing · Apache-2.0").assertIsDisplayed()
    }

    @Test fun emojiEntryIsAvailableWhileOffline() {
        compose.activity.setContent { NextTheme { ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture"), compose.activity, showBack = true, onBack = {}) } }
        compose.onNodeWithContentDescription("表情").assertIsDisplayed().performClick()
        val composer = compose.onNodeWithTag("chat-composer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("emoji-dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The dock must fit below the visible composer", dock.top >= composer.bottom && dock.height > 0)
        compose.onNodeWithTag("emoji-search").assertDoesNotExist()
        compose.onNodeWithContentDescription("键盘").performClick()
        compose.onNodeWithTag("emoji-dock").assertDoesNotExist()
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

    @Test fun deliveredImageKeepsExportInTheOverflowMenu() {
        val host = compose.activity
        val info = AttachmentInfo.v2("01234567-1234-1234-1234-0123456789ab", "photo.jpg", "image/jpeg", 6697, null, 0)
        val record = AttachmentRecord(info, true, "delivered", 6697)
        val constructor = ChatStore.Message::class.java.getDeclaredConstructor(String::class.java, String::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, AttachmentRecord::class.java)
        constructor.isAccessible = true
        val message = constructor.newInstance("photo", "", "delivered", true, 0L, record)
        host.setContent { NextTheme {
            ChatScreen(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture", messages = listOf(message)),
                host, showBack = true, onBack = {})
        } }
        compose.onNodeWithText(host.text("attachmentOpen")).assertIsDisplayed()
        compose.onNodeWithText(host.text("attachmentSaveAs")).assertDoesNotExist()
        compose.onNodeWithContentDescription(host.text("more")).performClick()
        compose.onNodeWithText(host.text("attachmentSaveAs")).assertIsDisplayed()
    }

    @Test fun decodedImageThumbnailOpensOnTap() {
        val host = compose.activity
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        val png = java.io.File.createTempFile("attachment", ".png", host.cacheDir)
        try {
            png.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val bitmap = PhotoDecoder.decode(host.contentResolver, Uri.fromFile(png), 512)
            assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(0, 0))
            var opened = false
            host.setContent { NextTheme { AttachmentPhotoPreview(bitmap, "photo.png") { opened = true } } }
            compose.onNodeWithContentDescription(host.text("attachmentPhotoPreview", "photo.png")).assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(opened) }
        } finally {
            png.delete()
            source.recycle()
        }
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
        compose.activity.setContent { NextTheme { EmojiPanel(264.dp, onPick = { picked = it }) } }
        compose.onNodeWithContentDescription("搜索表情").performClick()
        compose.onNodeWithTag("emoji-search").performTextInput("火箭")
        compose.waitUntil(10000) { compose.onAllNodesWithText("🚀").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("🚀").performClick()
        compose.runOnIdle { assertEquals("🚀", picked) }
        assertTrue(data.recent(context.getSharedPreferences("ui", 0).getString("emojiRecent", "")).any { it.text == "🚀" })
    }

    @Test fun emojiLongPressOpensVariantsWithoutInsertingTheBase() {
        var picked = ""
        // Use the recent section to exercise the gesture without invoking Robolectric's text magnifier.
        compose.runOnIdle { compose.activity.getSharedPreferences("ui", 0).edit().putString("emojiRecent", "👍").apply() }
        compose.activity.setContent { NextTheme { EmojiPanel(264.dp, onPick = { picked = it }) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("👍").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("👍").performTouchInput { longClick() }
        val bubble = compose.onNodeWithTag("emoji-variants").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("emoji-dock")))
        compose.onNodeWithText("拇指向上", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("中等肤色", useUnmergedTree = true).assertIsDisplayed()
        val dockBounds = compose.onNodeWithTag("emoji-dock").fetchSemanticsNode().boundsInRoot
        val bubbleBounds = bubble.fetchSemanticsNode().boundsInRoot
        assertTrue(bubbleBounds.top >= dockBounds.top && bubbleBounds.bottom <= dockBounds.bottom)
        compose.runOnIdle { assertEquals("", picked) }
        compose.onNodeWithContentDescription("拇指向上: 中等肤色").performClick()
        compose.runOnIdle { assertEquals("👍🏽", picked) }
        compose.onNodeWithTag("emoji-variants").assertDoesNotExist()
        compose.onNodeWithTag("emoji-dock").assertIsDisplayed()
    }

    @Test fun emojiWithoutVariantsShowsItsLocalizedNameOnLongPress() {
        compose.runOnIdle { compose.activity.getSharedPreferences("ui", 0).edit().putString("emojiRecent", "😀").apply() }
        compose.activity.setContent { NextTheme { EmojiPanel(264.dp, onPick = { error("Long press must not insert") }) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("😀").performTouchInput { longClick() }
        compose.onNodeWithText("嘿嘿").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("emoji-variants").assertDoesNotExist()
    }

    @Test fun leavingCompactChatKeepsHistoryWithoutASelectedRow() {
        val host = compose.activity
        compose.activity.setContent { NextTheme {
            NextApp(NextState(ready = true, selectedId = "peer", selectedName = "Fixture",
                connected = true, connectedId = "peer", openedPrivateChat = "test-opening",
                conversations = listOf(ChatStore.Conversation("peer", "Fixture", 1L, "Hello", false, ""))), host)
        } }
        compose.onNodeWithContentDescription(host.text("backToChats")).performClick()
        compose.onNodeWithText(host.text("app")).assertIsDisplayed()
        compose.onNodeWithTag("conversation-peer").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        compose.onNodeWithText(host.text("localHistoryDisconnected")).assertDoesNotExist()
    }

    @Test fun networkIconOpensPrivateDevicesAndPreservesConversationSearch() {
        val host = compose.activity
        compose.activity.setContent { NextTheme {
            NextApp(NextState(ready = true, connected = true, connectedMode = Peer.BLUETOOTH, connectedId = "peer",
                conversations = listOf(ChatStore.Conversation("peer", "Fixture", 1L))), host)
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Fix")
        compose.onNodeWithTag("network-status").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                host.text("bluetooth") + " " + host.text("connectedIndicator")))
            .performClick()
        compose.onNodeWithText(host.getString(R.string.mesh_private_devices)).assertIsDisplayed()
        compose.onNodeWithTag("nearby-title").assertDoesNotExist()
        compose.onNodeWithContentDescription(host.text("backToChats")).performClick()
        compose.onNodeWithTag("conversation-peer").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Fix")
    }

    @Test fun bottomNavigationFillsTheSystemGestureInset() {
        val host = compose.activity
        val root = compose.onNodeWithTag("next-root")
        val bar = compose.onNodeWithTag("main-navigation")
        val before = bar.fetchSemanticsNode().boundsInRoot.height
        val inset = (24 * host.resources.displayMetrics.density).toInt()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(host.window.decorView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, inset))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        val bounds = bar.fetchSemanticsNode().boundsInRoot
        assertEquals(root.fetchSemanticsNode().boundsInRoot.bottom, bounds.bottom, 1f)
        assertEquals(before + inset, bounds.height, 1f)
    }

    @Test fun aPrivateRequestDoesNotTakeOverOtherPagesAndNeedsAnExplicitChoice() {
        val host = compose.activity
        val connection = mutableStateOf(NextState(ready = true))
        host.setContent { NextTheme { NextApp(connection.value, host) } }
        compose.onNodeWithText("设置").performClick()
        compose.runOnIdle {
            connection.value = connection.value.copy(approvalId = "one", approvalName = "旁边的平板")
        }
        compose.onNodeWithTag("private-request").assertIsDisplayed()
        compose.onNodeWithText("旁边的平板").assertIsDisplayed()
        compose.onNodeWithText("仅本次").assertIsDisplayed()
        compose.onNodeWithText("接受并记住").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithTag("chat-root").assertDoesNotExist()
        compose.runOnIdle {
            connection.value = connection.value.copy(approvalId = null, connected = true,
                selectedId = "previous", connectedId = "trusted")
        }
        compose.onNodeWithTag("private-request").assertDoesNotExist()
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithTag("chat-root").assertDoesNotExist()
    }

    @Test fun theRequestActionsDistinguishOneTimeConsentFromRemembering() {
        val decisions = mutableListOf<Boolean>()
        compose.activity.setContent { NextTheme {
            PrivateRequestBanner("附近的设备", {}, decisions::add)
        } }
        compose.onNodeWithText("仅本次").performClick()
        compose.onNodeWithText("接受并记住").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), decisions) }
    }

    @Test fun accentSettingUpdatesAndPersists() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("强调色").performClick()
        compose.onNodeWithText("青绿").performClick()
        compose.onNodeWithText("青绿").assertIsDisplayed()
        compose.runOnIdle { assertEquals(NextAccent.TEAL, compose.activity.accent) }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertEquals(NextAccent.TEAL, compose.activity.accent) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1280dp-h700dp")
    fun dividerDragsAndCanBeCenteredWithAccessibilityAction() {
        val host = compose.activity
        compose.activity.setContent { NextTheme {
            NextApp(NextState(ready = true, selectedId = "fixture", selectedName = "Fixture",
                conversations = listOf(ChatStore.Conversation("fixture", "Fixture", 1L))), host)
        } }
        compose.onNodeWithTag("conversation-fixture").assertIsSelected()
        val divider = compose.onNodeWithTag("pane-divider").assertIsDisplayed()
        fun chatLeft() = compose.onNodeWithTag("chat-root").fetchSemanticsNode().boundsInRoot.left
        val original = chatLeft()
        val density = host.resources.displayMetrics.density
        divider.performTouchInput {
            down(center)
            moveBy(Offset(72 * density, 0f))
            moveBy(Offset(72 * density, 0f))
            up()
        }
        assertTrue("Dragging must widen the list", chatLeft() > original + 80 * density)
        val prefs = host.getSharedPreferences("ui", 0)
        assertTrue(prefs.getFloat("nextPaneSplit", Float.NaN).isFinite())
        divider.performSemanticsAction(SemanticsActions.OnClick)
        val root = compose.onNodeWithTag("next-root").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, chatLeft(), 1f)
        assertEquals(.5f, prefs.getFloat("nextPaneSplit", Float.NaN), .002f)
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

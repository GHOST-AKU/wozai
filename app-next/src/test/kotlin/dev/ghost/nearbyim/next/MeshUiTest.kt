package dev.ghost.nearbyim.next

import android.content.ComponentName
import androidx.activity.compose.setContent
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.mesh.MeshSnapshot
import dev.ghost.nearbyim.mesh.MeshMessage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.ghost.nearbyim.ChatService
import dev.ghost.nearbyim.MainActivity
import org.junit.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="zh-rCN-w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MeshUiTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:TestRule=RuleChain.outerRule(object:ExternalResource(){
        override fun before(){val app=RuntimeEnvironment.getApplication();
            Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app,ChatService::class.java))
            Shadows.shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true)
            org.robolectric.shadows.ShadowVMRuntime.setIs64Bit(true)
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        }
    }).around(compose)
    @Test fun unsentPublicTextSurvivesVisitingSettingsAndPrivateDevices() {
        compose.onNodeWithText("附近").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("未发出的公共消息")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("附近").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("未发出的公共消息")
        compose.onNodeWithText("私聊设备").performClick()
        compose.onNodeWithText("返回公共 Mesh").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("未发出的公共消息")
    }

    @Test fun cancelFirstStartDoesNotAskForRadioPermissionsOrStartService() {
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("开启接收").performClick()
        compose.onNodeWithText("先了解公共聊天").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        Assert.assertNull(Shadows.shadowOf(compose.activity).lastRequestedPermission)
        Assert.assertFalse(dev.ghost.nearbyim.mesh.MeshClient.state.value.running)
        Assert.assertFalse(dev.ghost.nearbyim.mesh.MeshConsent.acknowledged(compose.activity))
    }
    @Test fun nearbyKeepsTransportAndRelayControlsInsideOptions(){
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("公共聊天").assertIsDisplayed()
        compose.onNodeWithText("允许为其他节点中继").assertDoesNotExist()
        compose.onNodeWithContentDescription("聊天选项").performClick()
        compose.onNodeWithText("允许为其他节点中继").assertExists()
        compose.onNodeWithText("临时历史 · 最多 200 条").assertExists()
    }
    @Test fun firstNoticeExplainsReceivingAndCanBeCanceledWithoutStarting(){
        var confirmed = false
        var dismissed = false
        compose.activity.setContent { NextTheme {
            MeshConsentDialog(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.mesh_receive_rule)).assertExists()
        compose.onNodeWithText("取消").performClick()
        Assert.assertTrue(dismissed)
        Assert.assertFalse(confirmed)
    }
    @Test fun connectedStatusAndHandoffNeverClaimDeliveryOrIdentityTrust(){
        compose.activity.setContent { NextTheme {
            MeshChatContent(MeshSnapshot(running = true, neighbors = listOf("peer"), messages = listOf(
                MeshMessage("id", "hello", "local", 0, true, "handed", 0)
            )), "", false, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("已连接 1 台附近设备").assertIsDisplayed()
        compose.onNodeWithText("已交给邻居 · 未确认送达").assertIsDisplayed()
        compose.onNodeWithText("0 跳").assertDoesNotExist()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/reports/next-screenshots/public-chat-internal-fixture.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun chatOptionsOpenRetentionRules() {
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("临时历史 · 最多 200 条").assertDoesNotExist()
        compose.onNodeWithContentDescription("聊天选项").performClick()
        compose.onNodeWithText("临时历史 · 最多 200 条").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.mesh_history_rule)).assertIsDisplayed()
    }
    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp")
    fun publicComposerRemainsReachableAtLargeFontInShortWindow() {
        compose.activity.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.8f)
            ) { NextTheme { NextApp(NextState(), compose.activity) } }
        }
        compose.onNodeWithText("Nearby").performClick()
        compose.onNodeWithText("Say something…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
    }
    @Test fun diagnosticExportUsesSystemDocumentPickerWithTxtFile() {
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithContentDescription("聊天选项").performClick()
        compose.onNodeWithText("查看诊断").performScrollTo().performClick()
        compose.onNodeWithText("导出 TXT").performClick()
        val intent = Shadows.shadowOf(compose.activity).nextStartedActivityForResult.intent
        Assert.assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
        Assert.assertEquals("text/plain", intent.type)
        Assert.assertTrue(intent.getStringExtra(android.content.Intent.EXTRA_TITLE)!!.endsWith(".txt"))
    }
    @Test fun privateDeviceControlsRemainAvailable(){
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("私聊设备").performClick()
        compose.onNodeWithText("搜索设备").assertIsDisplayed()
        compose.onNodeWithText("返回公共 Mesh").performClick()
        compose.onNodeWithText("开启接收").assertIsDisplayed()
    }
}

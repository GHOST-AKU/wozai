package dev.ghost.nearbyim.next

import android.content.ComponentName
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
            Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app,ChatService::class.java))}
    }).around(compose)
    @Test fun nearbyShowsPublicMeshAndRequiresConsentBeforeStarting(){
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("0.4.0 preview · 无线验收待完成").assertIsDisplayed()
        compose.onNodeWithText("开启 Mesh").performClick()
        compose.onNodeWithText("公共消息可被附近节点和中继阅读，不提供端到端加密。开启不等于信任私聊设备。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("开启 Mesh").assertIsDisplayed()
    }
    @Test fun diagnosticExportUsesSystemDocumentPickerWithTxtFile() {
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("查看诊断").performClick()
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
        compose.onNodeWithText("开启 Mesh").assertIsDisplayed()
    }
}

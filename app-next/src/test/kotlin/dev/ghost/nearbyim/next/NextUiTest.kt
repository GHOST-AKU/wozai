package dev.ghost.nearbyim.next

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import dev.ghost.nearbyim.MainActivity
import dev.ghost.nearbyim.ChatService
import android.content.ComponentName
import dev.ghost.nearbyim.transport.Peer
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
class NextUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // Robolectric's default bindService callback supplies a null binder. These
    // tests exercise the real UI with the external Android service unavailable.
    @get:Rule val rules: TestRule = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).declareComponentUnbindable(ComponentName(application, ChatService::class.java))
        }
    }).around(compose)

    @Test fun launcherNavigatesAcrossThreeNativeSurfaces() {
        compose.onNodeWithText("还没有聊天").assertIsDisplayed()
        screenshot("conversations")
        compose.onNodeWithText("附近").performClick()
        compose.onNodeWithText("搜索设备").assertIsDisplayed()
        screenshot("nearby")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("主题").assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText("聊天").performClick()
        compose.onNodeWithText("还没有聊天").assertIsDisplayed()
    }

    @Test fun unnamedBluetoothDeviceCanBeRendered() {
        val device = Peer(Peer.BLUETOOTH, "bt:00:11:22:33:44:55", null, "00:11:22:33:44:55", null, 0, "00:11:22:33:44:55")
        compose.activity.setContent { NextTheme { NearbyScreen(NextState(ready = true, peers = listOf(device)), compose.activity) } }
        compose.onNodeWithText("蓝牙").performClick()
        compose.onNodeWithText("未命名设备").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h360dp")
    fun languageChoicesRemainReachableAtLargeFontInLandscape() {
        compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                NextTheme { NextApp(NextState(), compose.activity) }
            }
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("界面语言").performScrollTo().performClick()
        compose.onNodeWithText("한국어").performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File("build/reports/next-screenshots").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

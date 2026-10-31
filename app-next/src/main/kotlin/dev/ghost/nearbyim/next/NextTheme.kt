package dev.ghost.nearbyim.next

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF496B59), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E8DA), onPrimaryContainer = Color(0xFF173526),
    secondary = Color(0xFF53665A), secondaryContainer = Color(0xFFE5EDE6),
    background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7),
    surfaceContainer = Color(0xFFF0F3EE), surfaceContainerLow = Color(0xFFF2F5F0),
    surfaceContainerHigh = Color(0xFFE9EEE7), onSurface = Color(0xFF1A211C),
    onSurfaceVariant = Color(0xFF667168), outlineVariant = Color(0xFFDDE4DB),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9D0B6), onPrimary = Color(0xFF183727),
    primaryContainer = Color(0xFF304D3D), onPrimaryContainer = Color(0xFFD4E8DA),
    secondary = Color(0xFFBCCCBD), secondaryContainer = Color(0xFF303C33),
    background = Color(0xFF111612), surface = Color(0xFF111612),
    surfaceContainer = Color(0xFF1B221D), surfaceContainerLow = Color(0xFF171E19),
    surfaceContainerHigh = Color(0xFF252D26), onSurface = Color(0xFFE2E8E0),
    onSurfaceVariant = Color(0xFFADB9AD), outlineVariant = Color(0xFF354137),
)

@Composable
fun NextTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dynamic = context.getSharedPreferences("ui", 0).getBoolean("nextDynamicColor", false)
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

package dev.ghost.nearbyim.next

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Neutral Material 3 surfaces; wallpaper color remains an explicit preference.
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

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

package dev.ghost.nearbyim.next

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.ghost.nearbyim.R

internal enum class NextAccent(@get:StringRes val label: Int, val light: Long, val dark: Long, val lightFill: Long, val darkFill: Long) {
    PURPLE(R.string.next_accent_purple, 0xFF624398, 0xFFD8BFFF, 0xFFEBDDFF, 0xFF49316E),
    BLUE(R.string.next_accent_blue, 0xFF185BAA, 0xFFAAC9FF, 0xFFD8E6FF, 0xFF16447E),
    TEAL(R.string.next_accent_teal, 0xFF006B61, 0xFF85D6C8, 0xFFC0F1E7, 0xFF005149),
    ORANGE(R.string.next_accent_orange, 0xFF984500, 0xFFFFB783, 0xFFFFDFC8, 0xFF713300),
    WALLPAPER(R.string.next_dynamic_color, 0xFF624398, 0xFFD8BFFF, 0xFFEBDDFF, 0xFF49316E);

    companion object {
        fun read(context: Context): NextAccent {
            val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
            val saved = prefs.getString("nextAccent", null)?.let { name -> entries.firstOrNull { it.name == name } }
            val accent = saved ?: if (prefs.getBoolean("nextDynamicColor", false)) WALLPAPER else PURPLE
            return if (accent == WALLPAPER && Build.VERSION.SDK_INT < 31) PURPLE else accent
        }
    }
}

private val white = Color.White
private val black = Color.Black
private val lightInk = Color(0xFF181818)
private val darkInk = Color(0xFFF2F2F2)

private val lightSurfaces = lightColorScheme(
    background = white, onBackground = lightInk,
    surface = white, onSurface = lightInk, surfaceTint = white,
    surfaceVariant = Color(0xFFEEEEEE), onSurfaceVariant = Color(0xFF515151),
    surfaceDim = Color(0xFFE9E9E9), surfaceBright = white, surfaceContainerLowest = white,
    surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEFEFEF), surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFF767676), outlineVariant = Color(0xFFD1D1D1),
    inverseSurface = Color(0xFF303030), inverseOnSurface = darkInk,
    secondary = Color(0xFF545454), onSecondary = white,
    secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = lightInk,
    tertiary = Color(0xFF545454), onTertiary = white,
    tertiaryContainer = Color(0xFFE8E8E8), onTertiaryContainer = lightInk,
)

private val darkSurfaces = darkColorScheme(
    background = black, onBackground = darkInk,
    surface = black, onSurface = darkInk, surfaceTint = black,
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFC9C9C9),
    surfaceDim = black, surfaceBright = Color(0xFF333333), surfaceContainerLowest = black,
    surfaceContainerLow = Color(0xFF101010), surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF202020), surfaceContainerHighest = Color(0xFF292929),
    outline = Color(0xFF919191), outlineVariant = Color(0xFF414141),
    inverseSurface = darkInk, inverseOnSurface = lightInk,
    secondary = Color(0xFFCACACA), onSecondary = Color(0xFF252525),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = darkInk,
    tertiary = Color(0xFFCACACA), onTertiary = Color(0xFF252525),
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = darkInk,
)

internal fun nextColors(dark: Boolean, accent: NextAccent, wallpaper: ColorScheme? = null): ColorScheme {
    val neutral = if (dark) darkSurfaces else lightSurfaces
    return neutral.copy(
        primary = wallpaper?.primary ?: Color(if (dark) accent.dark else accent.light),
        onPrimary = wallpaper?.onPrimary ?: if (dark) black else white,
        primaryContainer = wallpaper?.primaryContainer ?: Color(if (dark) accent.darkFill else accent.lightFill),
        onPrimaryContainer = wallpaper?.onPrimaryContainer ?: neutral.onSurface,
        inversePrimary = wallpaper?.inversePrimary ?: Color(if (dark) accent.light else accent.dark),
    )
}

@Composable
internal fun NextTheme(accent: NextAccent = NextAccent.PURPLE, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = remember(context, accent, dark) {
        val wallpaper = if (accent == NextAccent.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
        nextColors(dark, accent, wallpaper)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

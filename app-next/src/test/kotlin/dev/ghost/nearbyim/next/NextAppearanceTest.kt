package dev.ghost.nearbyim.next

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class NextAppearanceTest {
    @Test fun accentsKeepNeutralSurfacesAndReadableForegrounds() {
        for (dark in listOf(false, true)) for (accent in NextAccent.entries) {
            val colors = nextColors(dark, accent)
            assertEquals(if (dark) Color.Black else Color.White, colors.background)
            for (surface in listOf(colors.surface, colors.surfaceContainer, colors.surfaceContainerHigh)) {
                assertEquals(surface.red, surface.green, 0f)
                assertEquals(surface.red, surface.blue, 0f)
            }
            fun contrast(first: Color, second: Color): Float {
                val one = first.luminance()
                val two = second.luminance()
                return (maxOf(one, two) + .05f) / (minOf(one, two) + .05f)
            }
            assertTrue(contrast(colors.primary, colors.onPrimary) >= 4.5f)
            assertTrue(contrast(colors.primary, colors.surface) >= 4.5f)
            assertTrue(contrast(colors.primaryContainer, colors.onPrimaryContainer) >= 4.5f)
        }
    }

    @Test fun dividerUsesJavaBoundsSnappingAndMirrorsSavedWidths() {
        val ltr = PaneWidths(1280, 700, 1f, 1f, false)
        val rtl = PaneWidths(1280, 700, 1f, 1f, true)
        assertTrue(ltr.dual && rtl.dual)
        assertEquals(368f, ltr.min, 0f)
        assertEquals(920f, ltr.max, 0f)
        assertEquals(ltr.initial, ltr.snap(ltr.initial + 12f), 0f)
        assertEquals(ltr.center, ltr.snap(ltr.center + 12f), 0f)
        assertEquals(ltr.center, ltr.snap(ltr.center + 24f, ltr.center), 0f)
        assertEquals(1280 - ltr.divider(.4f), rtl.divider(.4f), .01f)
        assertEquals(.4f, rtl.fraction(rtl.divider(.4f)), .001f)
        assertFalse(PaneWidths(900, 700, 1f, 1.8f, false).dual)
    }
}

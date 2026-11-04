package com.suixing.agent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePaletteTest {
    @Test
    fun `Claude uses one typeface for body titles and UI labels while preserving the size setting`() {
        val normal = suixingTypography(3)
        val claude = suixingTypography(3, serif = true)
        assertEquals(FontFamily.Serif, claude.bodyMedium.fontFamily)
        assertEquals(FontFamily.Serif, claude.titleLarge.fontFamily)
        assertEquals(normal.bodyMedium.fontSize, claude.bodyMedium.fontSize)
        assertEquals(FontFamily.Serif, claude.labelLarge.fontFamily)
        assertEquals(normal.labelLarge.fontSize, claude.labelLarge.fontSize)
    }

    @Test
    fun `only light Claude uses the warm user bubble colour`() {
        // 用户给的截图：Claude 纸底 #FAF9F5 上的气泡是 #EAE4D5。
        // 其它配色与深色 Claude 不受影响，仍沿用 primaryContainer。
        assertEquals(Color(0xFFEAE4D5), AppearancePalette.CLAUDE.userBubbleColor(dark = false))
        assertEquals(null, AppearancePalette.CLAUDE.userBubbleColor(dark = true))
        for (palette in listOf(
            AppearancePalette.SUIXING,
            AppearancePalette.WALLPAPER,
            AppearancePalette.CODEX,
        )) {
            for (dark in listOf(false, true)) {
                assertEquals("$palette dark=$dark", null, palette.userBubbleColor(dark))
            }
        }
    }

    @Test
    fun `saved palette ids retain their meaning`() {
        assertEquals(AppearancePalette.SUIXING, AppearancePalette.fromId(0))
        assertEquals(AppearancePalette.WALLPAPER, AppearancePalette.fromId(1))
        assertEquals(AppearancePalette.CLAUDE, AppearancePalette.fromId(2))
        assertEquals(AppearancePalette.CODEX, AppearancePalette.fromId(3))
        assertEquals(AppearancePalette.SUIXING, AppearancePalette.fromId(99))
    }

    @Test
    fun `new palettes keep main text and accents legible in light and dark`() {
        for (palette in listOf(AppearancePalette.CLAUDE, AppearancePalette.CODEX)) {
            for (dark in listOf(false, true)) {
                val scheme = palette.scheme(dark)
                val label = "$palette dark=$dark"
                assertContrast("$label body", scheme.onBackground, scheme.background)
                assertContrast("$label card", scheme.onSurface, scheme.surfaceContainerHighest)
                assertContrast("$label supporting text", scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
                assertContrast("$label accent", scheme.primary, scheme.surfaceContainerHighest)
                assertContrast("$label button", scheme.onPrimary, scheme.primary)
                assertContrast("$label chip", scheme.onPrimaryContainer, scheme.primaryContainer)
            }
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color) {
        val first = foreground.luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("$label contrast was $ratio", ratio >= 4.5f)
    }
}

package com.suixing.agent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePaletteTest {
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

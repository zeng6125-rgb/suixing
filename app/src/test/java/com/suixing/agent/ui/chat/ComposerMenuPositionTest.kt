package com.suixing.agent.ui.chat

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.suixing.agent.ui.components.ShadowPopupPositionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMenuPositionTest {
    private val provider = ShadowPopupPositionProvider(
        margin = 24,
        delegate = ComposerMenuPositionProvider(edgePx = 16f, offsetYPx = -44),
        alignment = Alignment.BottomStart,
        offset = IntOffset.Zero,
    )

    @Test fun preservesDefaultBottomStartGeometryAndItsSingleShadowMargin() {
        data class Case(val left: Int, val direction: LayoutDirection, val nudge: Int, val x: Int)
        val cases = listOf(
            Case(100, LayoutDirection.Ltr, 0, 76),
            Case(8, LayoutDirection.Ltr, 8, -8),
            Case(350, LayoutDirection.Ltr, -166, 160),
            Case(0, LayoutDirection.Ltr, 0, -24),
            Case(100, LayoutDirection.Rtl, 0, -88),
            Case(350, LayoutDirection.Rtl, -166, 328),
        )
        val window = IntSize(400, 900)
        val content = IntSize(248, 198)
        for (case in cases) {
            val anchor = IntRect(case.left, 600, case.left + 36, 636)
            val previous = ShadowPopupPositionProvider(
                24, null, Alignment.BottomStart, IntOffset(case.nudge, -44),
            ).calculatePosition(anchor, window, case.direction, content)
            val current = provider.calculatePosition(anchor, window, case.direction, content)

            assertEquals("legacy geometry for $case", previous, current)
            assertEquals("outer popup window for $case", IntOffset(case.x, 418), current)
        }
    }

    @Test fun eachPositionReadsTheCurrentAnchorCardAndWindowDimensions() {
        assertEquals(
            IntOffset(76, 418),
            provider.calculatePosition(
                IntRect(100, 600, 136, 636), IntSize(400, 900), LayoutDirection.Ltr, IntSize(248, 198),
            ),
        )
        val moved = IntRect(250, 620, 286, 656)
        val resizedCard = IntSize(268, 238)
        assertEquals(
            IntOffset(140, 398),
            provider.calculatePosition(moved, IntSize(400, 900), LayoutDirection.Ltr, resizedCard),
        )
        assertEquals(
            IntOffset(60, 398),
            provider.calculatePosition(moved, IntSize(320, 900), LayoutDirection.Ltr, resizedCard),
        )
        assertEquals(
            IntOffset(40, 378),
            provider.calculatePosition(moved, IntSize(320, 900), LayoutDirection.Ltr, IntSize(288, 258)),
        )
    }
}

package com.suixing.agent.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The popup window is the card **plus** a margin reserved for its shadow, so two things must
 * stay true: the card keeps the position its caller asked for, and the motion layer's pivot
 * still lands on the anchor now that the layer is the padded box rather than the card.
 */
class PopupShadowPositionTest {
    private val margin = 24
    private val content = IntSize(248, 148) // card 200x100 + 24px margin on every side
    private val anchor = IntRect(100, 250, 248, 298) // 148x48 at (100,250); centre (174, 274)
    private val window = IntSize(1080, 1920)

    private fun providerAt(position: IntOffset) = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset {
            // The delegate positions the card, so it must be handed the card's own size.
            assertEquals(IntSize(200, 100), popupContentSize)
            return position
        }
    }

    @Test fun `delegate keeps the card position and reports the box pivot`() {
        var captured = TransformOrigin.Center
        val position = ShadowPopupPositionProvider(margin, providerAt(IntOffset(100, 200)), Alignment.Center, IntOffset.Zero) {
            captured = it
        }.calculatePosition(anchor, window, LayoutDirection.Ltr, content)

        assertEquals(IntOffset(76, 176), position) // card at (100,200) minus the 24px margin
        // Box at (76,176), 248x148; anchor centre (174,274).
        assertEquals(0.395161f, captured.pivotFractionX, 1e-5f)
        assertEquals(0.662162f, captured.pivotFractionY, 1e-5f)
    }

    @Test fun `alignment path centres the card inside the padded box`() {
        var captured = TransformOrigin.Center
        val position = ShadowPopupPositionProvider(margin, null, Alignment.Center, IntOffset.Zero) {
            captured = it
        }.calculatePosition(IntRect(0, 0, 100, 40), window, LayoutDirection.Ltr, content)

        assertEquals(IntOffset(-74, -54), position) // (50,20)-(100,50) then shifted by the margin
        assertEquals(0.5f, captured.pivotFractionX, 1e-6f)
        assertEquals(0.5f, captured.pivotFractionY, 1e-6f)
    }

    @Test fun `empty content reports the centre instead of dividing by zero`() {
        val atOrigin = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset.Zero
        }
        var captured = TransformOrigin(0f, 1f)
        ShadowPopupPositionProvider(0, atOrigin, Alignment.Center, IntOffset.Zero) {
            captured = it
        }.calculatePosition(IntRect(0, 0, 0, 0), window, LayoutDirection.Ltr, IntSize.Zero)

        assertTrue(captured.pivotFractionX == 0.5f && captured.pivotFractionY == 0.5f)
    }
}

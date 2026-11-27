package com.suixing.agent.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.PopupPositionProvider

@RunWith(AndroidJUnit4::class)
class MiuixOverlayMenuPositionTest {
    @Test fun menuLeavesSpaceAtBothSafeAreaEdges() {
        val window = IntRect(12, 24, 412, 824)
        assertEquals(IntOffset(28, 88), position(IntRect(12, 40, 52, 80), window, LayoutDirection.Rtl, menuWidth = 208))
        assertEquals(IntOffset(188, 88), position(IntRect(372, 40, 412, 80), window, LayoutDirection.Ltr, menuWidth = 208))
    }

    @Test fun menuMeasuredToTheInsetViewportStillLeavesBothMargins() {
        val window = IntRect(12, 24, 232, 824)
        assertEquals(IntOffset(28, 88), position(IntRect(192, 40, 232, 80), window, LayoutDirection.Ltr, menuWidth = 188))
    }

    private fun position(anchor: IntRect, window: IntRect, direction: LayoutDirection, menuWidth: Int): IntOffset {
        val margins = MiuixOverlayMenuPositionProvider.getMargins()
        val marginPx = with(Density(1f)) {
            IntRect(
                margins.calculateLeftPadding(direction).roundToPx(),
                margins.calculateTopPadding().roundToPx(),
                margins.calculateRightPadding(direction).roundToPx(),
                margins.calculateBottomPadding().roundToPx(),
            )
        }
        return MiuixOverlayMenuPositionProvider.calculatePosition(
            anchorBounds = anchor,
            windowBounds = window,
            layoutDirection = direction,
            popupContentSize = IntSize(menuWidth, 100),
            popupMargin = marginPx,
            alignment = PopupPositionProvider.Align.Start,
        )
    }
}

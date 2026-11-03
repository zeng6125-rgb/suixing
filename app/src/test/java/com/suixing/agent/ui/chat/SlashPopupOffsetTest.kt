package com.suixing.agent.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidates are a popup window now, so where they land is geometry rather than layout: the
 * card must sit [gapPx] above the field it belongs to and stay on screen when the field has been
 * pushed high by extra lines, attachments or queued rows.
 */
class SlashPopupOffsetTest {
    private val windowWidth = 1104
    private val inset = 12
    private val gap = 6
    // A field inside the composer's 16dp padding, 1048px wide (1104 - 2*16 at 3x), at y 2000.
    private val field = IntRect(48, 2000, 1096, 2200)

    /** The card as it is drawn: the field's width less one inset on each side. */
    private fun card(field: IntRect = this.field, rows: Int = 3) =
        IntSize(field.width - inset * 2, rows * 45)

    @Test
    fun `the card hangs gap above the field, inset the same amount on both sides`() {
        val size = card()
        val at = slashPopupOffset(field, windowWidth, size, inset, gap)

        assertEquals(field.left + inset, at.x)
        assertEquals(field.top - size.height - gap, at.y)
        // Same inset on both sides: the card is centred inside the field's width.
        assertEquals(field.right - inset, at.x + size.width)
    }

    @Test
    fun `a field pushed up by extra rows keeps the card on screen`() {
        val size = card(rows = 5)
        val high = IntRect(48, 120, 1096, 320)
        val at = slashPopupOffset(high, windowWidth, size, inset, gap)

        assertEquals(inset, at.y)
        assertTrue("never above the window's top", at.y >= 0)
    }

    @Test
    fun `a full bleed anchor still gets its edge air`() {
        val full = IntRect(0, 2000, windowWidth, 2200)
        val at = slashPopupOffset(full, windowWidth, card(full), inset, gap)

        assertEquals(inset, at.x)
    }

    @Test
    fun `a card wider than the window is clamped instead of overflowing`() {
        // Wider than the window: the upper clamp collapses onto the lower one — the card starts at
        // the window's edge air and its right side is cut, which is the only placement left.
        val at = slashPopupOffset(field, windowWidth, IntSize(windowWidth + 200, 150), inset, gap)

        assertEquals(inset, at.x)
    }
}

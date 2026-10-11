package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets to hand the follow back after a gesture.
 *
 * The follow is what keeps a streaming answer pinned to the end of the transcript. A tap must not
 * end it — tapping a card to open it is not the user reaching into the position. Everything else
 * must: the long press that starts a selection, a drag in either direction, and anything the list
 * itself scrolled. The distinction that was wrong is a drag *downward*: it is the gesture that reads
 * older messages, its finger travels the opposite way to the one the block measured, so a short one
 * looked exactly like a tap — the follow came back on release and the next publish pulled the
 * transcript down to its end again, "the position chasing the bottom" on every scroll.
 */
class FollowReleaseTest {

    /** A quick tap on a card, with the list still sitting at its end. */
    @Test
    fun `a tap that never moved the list hands the follow back`() {
        assertTrue(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 120))
    }

    /** The reported bug: a flick downward (reading older messages), finger travelling the other way. */
    @Test
    fun `a quick drag downward is a scroll, not a tap`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = true, atEnd = false, heldMs = 140))
    }

    /** A fling can send the list far with almost no finger travel, so travel alone cannot decide. */
    @Test
    fun `a fling under the drag threshold does not hand it back`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = true, atEnd = true, heldMs = 90))
    }

    @Test
    fun `a drag upward does not hand it back`() {
        assertFalse(restoresFollow(movedUp = true, scrolled = true, atEnd = false, heldMs = 100))
    }

    /**
     * The long press that starts a selection is the user reaching into the content. The figure is
     * the platform's own 500ms, which is when the gesture system starts a selection — the fix that
     * moved this boundary up from 450, a value *below* the platform's that left a 50ms window in
     * which a real selection press was still counted as a tap and handed the follow back.
     */
    @Test
    fun `a long press does not hand it back`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 520))
    }

    /** A tap that ends anywhere but the end cannot re-arm a follow the reader deliberately left. */
    @Test
    fun `a tap away from the end does not hand it back`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = false, heldMs = 80))
    }

    /** The boundary is exactly the long press threshold, so a tap just under it still counts. */
    @Test
    fun `the long press threshold is the boundary`() {
        assertTrue(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 499))
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 500))
    }

    /**
     * The threshold is a parameter because the caller passes the platform's live value rather than
     * this file's constant: a device (or an accessibility setting) whose long press is shorter must
     * not have that window treated as a tap.
     */
    @Test
    fun `the caller's own threshold decides`() {
        assertFalse(
            restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 300, holdMs = 250),
        )
        assertTrue(
            restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 200, holdMs = 250),
        )
    }

    // --------------------------------------------------- 「往上滑一点点，又追底」 (0.8.86 回归)

    /**
     * The regression, at the level the release decision can see it: a drag of a few pixels — past
     * the touch slop, under the 12dp travel threshold — moves the list, and a moved list must not
     * get the follow back. The scroll-progress flag may never have been sampled; the position pair
     * cannot miss it.
     */
    @Test
    fun `a drag too small for the travel threshold still counts as a scroll`() {
        assertFalse(
            restoresFollowAfterPress(
                movedUp = false,
                scrolledDuringPress = false,
                downIndex = 7,
                downOffset = 0,
                nowIndex = 7,
                nowOffset = 6,
                atVeryEnd = false,
                heldMs = 90,
            ),
        )
    }

    /**
     * The tolerance that caused it: a few pixels off the end is *not* the end. Read as "still at
     * the end" it re-armed the follow, and the next streamed frame hauled the reader back down.
     */
    @Test
    fun `a few pixels off the end is not the end`() {
        assertFalse(
            restoresFollowAfterPress(
                movedUp = false,
                scrolledDuringPress = false,
                downIndex = 7,
                downOffset = 0,
                nowIndex = 7,
                nowOffset = 0,
                atVeryEnd = false,
                heldMs = 80,
            ),
        )
    }

    /** The tap that must keep working: nothing moved, and the list really is at its end. */
    @Test
    fun `a tap that moved nothing at the absolute end still hands it back`() {
        assertTrue(
            restoresFollowAfterPress(
                movedUp = false,
                scrolledDuringPress = false,
                downIndex = 7,
                downOffset = 0,
                nowIndex = 7,
                nowOffset = 0,
                atVeryEnd = true,
                heldMs = 120,
            ),
        )
    }

    /** Reading in the middle of the history: no restore, whatever the gesture looked like. */
    @Test
    fun `a press while reading in the middle of the history never hands it back`() {
        assertFalse(
            restoresFollowAfterPress(
                movedUp = false,
                scrolledDuringPress = false,
                downIndex = 3,
                downOffset = 240,
                nowIndex = 3,
                nowOffset = 240,
                atVeryEnd = false,
                heldMs = 90,
            ),
        )
    }

    // ------------------------------------------------ the stationary long press (position restore)

    /**
     * The real log, as the rule sees it: at the very end, a finger down that never travels past the
     * touch slop, and 1124ms later the list has moved 636px. Below the slop the finger cannot have
     * scrolled the list at all, so that movement is the selection machinery bringing the selected
     * line into view — a stationary long press is a request to select, so the position goes back.
     */
    @Test
    fun `a stationary long press that the list moved by itself is undone`() {
        assertTrue(
            restoresPositionAfterLongPress(
                fingerDragged = false,
                listMoved = true,
                heldMs = 1124,
            ),
        )
    }

    /** A plain tap: nothing moved, so there is nothing to put back. */
    @Test
    fun `a tap has no position to restore`() {
        assertFalse(
            restoresPositionAfterLongPress(
                fingerDragged = false,
                listMoved = false,
                heldMs = 120,
            ),
        )
    }

    /** The user dragged the transcript: that movement is theirs and is never undone. */
    @Test
    fun `a drag is never undone`() {
        assertFalse(
            restoresPositionAfterLongPress(
                fingerDragged = true,
                listMoved = true,
                heldMs = 800,
            ),
        )
    }

    /**
     * A short *downward* drag (reading older) can stay under the 12dp travel threshold while moving
     * the list a long way: past the touch slop it is the finger's scroll, so it is not undone.
     */
    @Test
    fun `a short drag that moved the list is not undone either`() {
        assertFalse(
            restoresPositionAfterLongPress(
                fingerDragged = true,
                listMoved = true,
                heldMs = 600,
            ),
        )
    }

    /** The threshold is the platform's long-press timeout, inclusive: 499 is still a tap. */
    @Test
    fun `the long press threshold is where the restore starts`() {
        assertFalse(
            restoresPositionAfterLongPress(
                fingerDragged = false,
                listMoved = true,
                heldMs = 499,
            ),
        )
        assertTrue(
            restoresPositionAfterLongPress(
                fingerDragged = false,
                listMoved = true,
                heldMs = 500,
            ),
        )
    }
}

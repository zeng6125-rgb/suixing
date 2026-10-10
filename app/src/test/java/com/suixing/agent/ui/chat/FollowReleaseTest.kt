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

    /** The long press that starts a selection is the user reaching into the content. */
    @Test
    fun `a long press does not hand it back`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 460))
    }

    /** A tap that ends anywhere but the end cannot re-arm a follow the reader deliberately left. */
    @Test
    fun `a tap away from the end does not hand it back`() {
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = false, heldMs = 80))
    }

    /** The boundary is exactly the long press threshold, so a tap just under it still counts. */
    @Test
    fun `the long press threshold is the boundary`() {
        assertTrue(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 449))
        assertFalse(restoresFollow(movedUp = false, scrolled = false, atEnd = true, heldMs = 450))
    }
}

package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #73：先横后竖仍须停跟，且两个 one-shot 标记每次手势各最多触发一次。 */
class DragGestureStateTest {

    private class Gesture {
        val state = DragGestureState()
        var movedEffects = 0
        var followEffects = 0

        fun move(dx: Float, dy: Float, pressed: Boolean = true) {
            val intent = dragIntent(dx, dy)
            if (state.markMoved(intent, pressed)) movedEffects++
            if (state.markFollowCancelled(intent, pressed)) followEffects++
        }
    }

    @Test
    fun `horizontal then vertical cancels follow on the first vertical frame`() {
        val gesture = Gesture()

        gesture.move(5f, 1f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(0, gesture.followEffects)
        assertTrue(gesture.state.moved)
        assertFalse(gesture.state.followCancelled)

        gesture.move(5f, 40f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
        assertTrue(gesture.state.followCancelled)

        gesture.move(5f, 90f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
    }

    @Test
    fun `diagonal then vertical cancels follow without re-cancelling press`() {
        val gesture = Gesture()

        gesture.move(30f, 20f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(0, gesture.followEffects)

        gesture.move(40f, 60f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)

        gesture.move(44f, 90f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
    }

    @Test
    fun `pure horizontal never cancels follow`() {
        val gesture = Gesture()

        gesture.move(60f, 5f)
        gesture.move(120f, 20f)
        gesture.move(200f, 0f)

        assertEquals(1, gesture.movedEffects)
        assertEquals(0, gesture.followEffects)
        assertTrue(gesture.state.moved)
        assertFalse(gesture.state.followCancelled)
    }

    @Test
    fun `pure vertical cancels follow once`() {
        val gesture = Gesture()

        gesture.move(0f, 8f)
        gesture.move(-2f, 30f)
        gesture.move(4f, 80f)

        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
        assertTrue(gesture.state.moved)
        assertTrue(gesture.state.followCancelled)
    }

    @Test
    fun `movement up to four pixels stays idle`() {
        val gesture = Gesture()

        gesture.move(3.9f, 0f)
        gesture.move(0f, 3.9f)
        gesture.move(3f, 3f)
        gesture.move(4f, 4f)

        assertEquals(0, gesture.movedEffects)
        assertEquals(0, gesture.followEffects)
        assertFalse(gesture.state.moved)
        assertFalse(gesture.state.followCancelled)

        gesture.move(0f, 4.0001f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
    }

    @Test
    fun `vertical then horizontal keeps follow cancelled without another write`() {
        val gesture = Gesture()

        gesture.move(0f, 8f)
        assertEquals(1, gesture.followEffects)

        gesture.move(60f, 12f)
        gesture.move(200f, 20f)
        assertEquals(1, gesture.movedEffects)
        assertEquals(1, gesture.followEffects)
        assertTrue(gesture.state.followCancelled)
    }

    @Test
    fun `unpressed events cannot move or cancel follow`() {
        val gesture = Gesture()
        gesture.move(5f, 1f)
        gesture.move(5f, 40f, pressed = false)
        gesture.move(0f, 80f, pressed = false)

        assertEquals(1, gesture.movedEffects)
        assertEquals(0, gesture.followEffects)
        assertTrue(gesture.state.moved)
        assertFalse(gesture.state.followCancelled)

        val neverPressed = Gesture()
        neverPressed.move(0f, 40f, pressed = false)
        assertEquals(0, neverPressed.movedEffects)
        assertEquals(0, neverPressed.followEffects)
        assertFalse(neverPressed.state.moved)
        assertFalse(neverPressed.state.followCancelled)
    }

    @Test
    fun `a new gesture starts with fresh once-only gates`() {
        val previous = Gesture()
        previous.move(5f, 1f)
        previous.move(5f, 40f)
        previous.move(5f, 80f)
        assertEquals(1, previous.followEffects)

        val next = Gesture()
        assertFalse(next.state.moved)
        assertFalse(next.state.followCancelled)

        next.move(0f, 8f)
        assertEquals(1, next.movedEffects)
        assertEquals(1, next.followEffects)
    }
}

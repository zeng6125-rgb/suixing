package com.suixing.agent.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared clock is now opacity only; restoration and rail mounting keep their own tests. */
class ConversationEntranceTest {
    private val oldFade = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    private fun oldAlpha(t: Float): Float = oldFade.transform((t * 3f).coerceIn(0f, 1f))
    private fun at(t: Float): Float = ConversationEntrance.EASING.transform(t.coerceIn(0f, 1f))

    @Test
    fun `restored content does not spend over 200ms in an entrance transition`() {
        assertTrue("The restored conversation should be ready to read promptly", ConversationEntrance.DURATION_MS in 1..200)
    }

    @Test
    fun `opacity starts hidden and finishes visible without reversing`() {
        assertEquals(0f, ConversationEntrance.alpha(at(0f)), 1e-4f)
        assertEquals(1f, ConversationEntrance.alpha(at(1f)), 1e-4f)
        var previous = 0f
        for (i in 0..100) {
            val value = ConversationEntrance.alpha(at(i / 100f))
            assertTrue("opacity reversed at $i%", value >= previous)
            previous = value
        }
    }

    @Test
    fun `all sampled frames remain within the opacity range`() {
        for (frameCount in listOf(12, 24, 100)) {
            for (frame in 0..frameCount) {
                assertTrue(ConversationEntrance.alpha(at(frame.toFloat() / frameCount)) in 0f..1f)
            }
        }
    }

    @Test
    fun `fade does not restore the old abrupt first third reveal`() {
        for (i in 0..100) {
            val t = i / 100f
            assertTrue("reveal is too abrupt at $i%", ConversationEntrance.alpha(at(t)) <= oldAlpha(t) + 1e-4f)
        }
    }

    @Test
    fun `opacity is not already complete before the clock ends`() {
        for (i in 0 until 100) {
            assertTrue("clock keeps running after opacity completed at $i%", ConversationEntrance.alpha(at(i / 100f)) < 1f)
        }
    }

    @Test
    fun `last frame does not visibly jump into place`() {
        val remainingAlpha = 1f - ConversationEntrance.alpha(at(0.99f))
        assertTrue("large final opacity step: $remainingAlpha", remainingAlpha in 0f..0.01f)
    }

    @Test
    fun `halfway through the short fade text is already readable`() {
        assertTrue("text is too faint halfway through", ConversationEntrance.alpha(at(0.5f)) > 0.8f)
    }

    @Test
    fun `opacity follows the shared clock without a second completion phase`() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.87f, 1f)) {
            assertEquals(progress, ConversationEntrance.alpha(progress), 1e-4f)
        }
    }

    @Test
    fun `out of range progress cannot make the layer invalid`() {
        assertEquals(0f, ConversationEntrance.alpha(-0.1f), 0f)
        assertEquals(1f, ConversationEntrance.alpha(1.1f), 0f)
    }
}

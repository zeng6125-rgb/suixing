package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a model is given has to be filed under the id every reader asks by: the ring,
 * the engine and the view model all pass the raw model id, so a window stored under a display
 * name or an alias is written and then never found — the user sees 「设了不生效」.
 */
class ContextWindowTest {

    @Test
    fun `a window is filed under the bare model id, and a zero takes the entry out`() {
        val windows = emptyMap<String, Int>().withContextWindow("qwen3.8-flash", 32_000)

        assertEquals(mapOf("qwen3.8-flash" to 32_000), windows)
        assertEquals(
            "the id written is the id read back",
            32_000,
            AppSettings(modelContexts = windows).contextWindowFor("qwen3.8-flash"),
        )
        assertEquals(
            "a window filed under the display name would never be read",
            0,
            AppSettings(modelContexts = windows).contextWindowFor("Qwen3.8-Flash"),
        )
        assertTrue(
            "zero means 不限, so the entry goes away",
            windows.withContextWindow("qwen3.8-flash", 0).isEmpty(),
        )
        assertTrue(
            "and a negative count does the same",
            windows.withContextWindow("qwen3.8-flash", -1).isEmpty(),
        )
    }

    @Test
    fun `the suggestion table knows the families by name and stays quiet about the rest`() {
        assertEquals(32_000, suggestedContextWindow("Qwen3.8-Flash"))
        assertEquals(128_000, suggestedContextWindow("gpt-4o"))
        assertNull("an unrecognisable name gets no suggestion at all", suggestedContextWindow("whatever-unknown"))
    }
}

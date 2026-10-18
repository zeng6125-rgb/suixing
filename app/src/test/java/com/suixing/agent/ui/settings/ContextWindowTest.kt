package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.DEFAULT_CONTEXT_WINDOW
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
            // 未登记的模型不再返回 0：0 会让压缩阈值失效，现在落回全局默认 256k。
            "an unregistered model falls back to the global default",
            DEFAULT_CONTEXT_WINDOW,
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

    @Test
    fun `upstream windows fill the gaps and never overwrite a window the user set`() {
        val mine = mapOf("mine/model" to 4_096)

        assertEquals(
            "a window the user set is never overwritten, however often the list is re-fetched",
            mapOf("mine/model" to 4_096, "new/model" to 180_000),
            mine.withUpstreamDefaults(mapOf("mine/model" to 999_999, "new/model" to 180_000)),
        )
        assertEquals(
            "an advertised key is written only when it is missing",
            mapOf("mine/model" to 4_096, "new/model" to 180_000),
            mine.withUpstreamDefaults(mapOf("new/model" to 180_000)),
        )
        assertEquals(
            "a blank id or a non-positive count is ignored, not invented",
            mine,
            mine.withUpstreamDefaults(mapOf("" to 5_000, "zero" to 0, "negative" to -1)),
        )
        assertEquals("an empty advertisement changes nothing", mine, mine.withUpstreamDefaults(emptyMap()))
        assertTrue(
            "and nothing comes out of nothing",
            emptyMap<String, Int>().withUpstreamDefaults(emptyMap()).isEmpty(),
        )
    }

    @Test
    fun `a second fetch leaves a window the user raised alone`() {
        val upstream = mapOf("Qwen3.8-Flash" to 180_000)

        val filled = emptyMap<String, Int>().withUpstreamDefaults(upstream)
        assertEquals("the first fetch fills the window in", 180_000, filled["Qwen3.8-Flash"])

        val raised = filled.withContextWindow("Qwen3.8-Flash", 400_000)
        assertEquals(
            "and re-fetching the list cannot undo the user's own number",
            400_000,
            raised.withUpstreamDefaults(upstream)["Qwen3.8-Flash"],
        )
    }
}

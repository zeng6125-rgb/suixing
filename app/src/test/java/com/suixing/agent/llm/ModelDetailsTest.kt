package com.suixing.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `GET /models` read for the context window as well as the id. The field is not standardised,
 * so the body below is a trimmed copy of what the gateway this app runs against really sends
 * (`context_window`), including an entry that advertises none.
 */
class ModelDetailsTest {

    private val body = """{"object":"list","data":[
        {"id":"Qwen3.8-Flash","object":"model","context_window":180000,"display_name":"Qwen3.8-Flash"},
        {"id":"Auto","object":"model","context_window":200000},
        {"id":"no-window-model","object":"model","display_name":"无窗口字段"}]}"""

    @Test
    fun `reads the ids in order, each with the window it advertises`() {
        val models = parseModelDetails(body)

        assertEquals(
            listOf("Qwen3.8-Flash", "Auto", "no-window-model"),
            models.map { it.id },
        )
        assertEquals(180_000, models[0].contextLength)
        assertEquals(200_000, models[1].contextLength)
        assertNull("an entry that advertises no window carries null, not a guess", models[2].contextLength)
    }

    @Test
    fun `accepts a bare array and the models and result envelopes`() {
        assertEquals(listOf("a"), parseModelDetails("""["a"]""").map { it.id })
        assertEquals(listOf("x"), parseModelDetails("""{"models":[{"id":"x","context_window":8000}]}""")
            .map { it.id })
        assertEquals(8_000, parseModelDetails("""{"result":[{"id":"x","context_window":8000}]}""")[0].contextLength)
    }

    @Test
    fun `a repeated id keeps the first entry, like the plain model list does`() {
        val models = parseModelDetails(
            """{"data":[{"id":"dup","context_window":1000},{"id":"dup","context_window":2000}]}""",
        )

        assertEquals(1, models.size)
        assertEquals(1_000, models[0].contextLength)
    }

    @Test
    fun `rejects an empty body and a body that is not json`() {
        assertThrows(LlmException::class.java) { parseModelDetails("   ") }
        assertThrows(LlmException::class.java) { parseModelDetails("<html>502 Bad Gateway</html>") }
    }
}

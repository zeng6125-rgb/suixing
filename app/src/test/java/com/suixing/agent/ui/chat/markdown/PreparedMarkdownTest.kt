package com.suixing.agent.ui.chat.markdown

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMarkdownTest {
    @Test fun `prepared long documents equal the public parser without filling the global cache`() = runBlocking {
        val texts = listOf(
            ("prepared paragraph\n\n- first\n  - nested\n\n").repeat(30),
            "# prepared heading\r\n\r\n```kotlin\r\n" + "val item = 1\r\n".repeat(80) + "```",
            "| a | b |\n| --- | --- |\n" + "| left | right |\n".repeat(80),
        )
        val cacheSize = SettledBlocks.sizeForTest()
        val result = prepareMarkdownTexts(texts + "short")
        assertEquals(texts.toSet(), result.keys)
        for (text in texts) assertEquals(parseMarkdown(text), result[text])
        assertEquals(cacheSize, SettledBlocks.sizeForTest())
    }

    @Test fun `short text does not enqueue background preparation`() = runBlocking {
        assertTrue(prepareMarkdownTexts(listOf("small", "")).isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `long code parsing checks cancellation within the block`() {
        var checks = 0
        parseMarkdown("```\n" + "line\n".repeat(1_000) + "```") {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }

    @Test(expected = CancellationException::class)
    fun `long table parsing checks cancellation within the block`() {
        var checks = 0
        parseMarkdown("| a | b |\n| --- | --- |\n" + "| a | b |\n".repeat(1_000)) {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }

    @Test(expected = CancellationException::class)
    fun `nested list parsing checks cancellation within child items`() {
        var checks = 0
        parseMarkdown("- parent\n" + "  - child\n".repeat(1_000)) {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }
}

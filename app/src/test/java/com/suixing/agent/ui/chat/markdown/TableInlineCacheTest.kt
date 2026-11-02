package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TableInlineCacheTest {
    @Test fun `repeated formatted cells share annotations but caret and exact source remain distinct`() {
        val cache = TableInlineCache()
        var parses = 0
        fun parse(text: String, caret: Boolean = false) = cache.getOrParse(text, caret) {
            parses++
            if (caret) inlineMarkdownWithCaret(text) else inlineMarkdown(text)
        }
        val result = parse("**ready**")
        repeat(1_000) { assertSame(result, parse("**ready**")) }
        assertEquals(1, parses)
        assertEquals("ready", result.text)
        assertNotSame(result, parse("**ready**", true))
        // Equal string hashes are not cache identities.
        assertEquals("FB".hashCode(), "Ea".hashCode())
        assertEquals("FB", parse("FB").text)
        assertEquals("Ea", parse("Ea").text)
        assertEquals(4, parses)
    }

    @Test fun `entry source and estimated tree budgets all evict without changing output`() {
        for (cache in listOf(
            TableInlineCache(maxEntries = 1),
            TableInlineCache(maxChars = 3),
            TableInlineCache(maxEstimatedBytes = 105),
        )) {
            var parses = 0
            fun parse(text: String) = cache.getOrParse(text, false) { parses++; AnnotatedString(text) }
            assertEquals("ab", parse("ab").text)
            assertEquals("cd", parse("cd").text)
            assertEquals("ab", parse("ab").text)
            assertEquals(3, parses)
        }
        val oversized = TableInlineCache(maxChars = 1)
        var parses = 0
        repeat(2) { assertEquals("large", oversized.getOrParse("large", false) { parses++; AnnotatedString("large") }.text) }
        assertEquals(2, parses)
    }
}

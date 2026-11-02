package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineLiteralSourceTest {
    @Test fun `plain appends bypass the builder and retain the exact text reference`() {
        val tracker = InlineLiteralSource()
        val source = "普通文字 with punctuation (a), : 123. 😃\ncontinued"
        for (end in 0..source.length) {
            val prefix = source.take(end)
            assertTrue(tracker.accepts(prefix))
            val result = inlineMarkdown(prefix)
            assertSame(prefix, result.text)
            assertEquals(AnnotatedString(prefix), result)
        }
        assertEquals(FontWeight.Bold, inlineMarkdown("plain", baseWeight = FontWeight.Bold)
            .spanStyles.single().item.fontWeight)
    }

    @Test fun `syntax url and path transitions always leave the literal path`() {
        for (source in listOf("**bold**", "_italic_", "~~strike~~", "`code`", "[x](https://x.dev)",
            "https://x.dev", "/sdcard/a.txt", "escaped \\*text\\*")) {
            val tracker = InlineLiteralSource()
            for (end in 0..source.length) {
                val prefix = source.take(end)
                assertEquals(literalInlineSource(prefix), tracker.accepts(prefix))
            }
            assertFalse(tracker.accepts(source))
        }
    }

    @Test fun `edits truncation and source replacement recheck the complete source`() {
        val tracker = InlineLiteralSource()
        assertTrue(tracker.accepts("hello"))
        assertFalse(tracker.accepts("he*lo"))
        assertFalse(tracker.accepts("he*lo more"))
        assertTrue(tracker.accepts("plain again"))
        assertTrue(tracker.accepts("short"))
        assertFalse(tracker.accepts("/path"))
        assertTrue(tracker.accepts(""))
    }
}

package com.suixing.agent.ui.chat.markdown

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineDelimiterSearchTest {
    /** Previous scanner's uncached search, kept as a behavior oracle for generated ranges. */
    private fun previousSearch(text: String, marker: Char, len: Int, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to) {
                i += 2
                continue
            }
            if (c == marker) {
                var end = i
                while (end < to && text[end] == c) end++
                if ((i == 0 || text[i - 1] != marker) &&
                    (if (len == 1) end - i == 1 else end - i >= len)
                ) return i
                i = end
                continue
            }
            i++
        }
        return -1
    }

    @Test fun `unmatched runs preserve literal text after valid inline code`() {
        val unmatched = "``code ".repeat(2_000)
        assertEquals(unmatched, inlineMarkdown(unmatched).text)
        // Without a separator, the closing single backtick joins the next double
        // run. The prior parser also keeps that entire sequence literal.
        val adjacent = "`real code`" + unmatched
        assertEquals(-1, previousSearch(adjacent, '`', 1, 1, adjacent.length))
        assertEquals(adjacent, inlineMarkdown(adjacent).text)
        assertEquals(
            "\u2009real code\u2009 " + unmatched,
            inlineMarkdown("`real code` " + unmatched).text,
        )
    }

    @Test fun `long unmatched emphasis runs preserve all characters`() {
        for (marker in listOf('*', '_')) {
            val text = marker.toString().repeat(20_000)
            assertEquals(text, inlineMarkdown(text).text)
        }
    }

    @Test fun `failed ranges do not leak between nesting ends widths or marker kinds`() {
        val text = "`code` *italic* **bold** _other_ __strong__ ~~gone~~"
        val search = InlineDelimiterSearch(text)
        for ((marker, width) in listOf('`' to 1, '*' to 1, '*' to 2, '_' to 1, '_' to 2, '~' to 2)) {
            for (end in text.length downTo 1) {
                // Increasing origins exercise failed-range reuse; reversed ranges test invalidation.
                for (at in 0 until end) {
                    if (text[at] != marker || at + width > end) continue
                    if ((at until at + width).any { text[it] != marker }) continue
                    assertEquals(
                        previousSearch(text, marker, width, at + width, end),
                        search.findClosing(marker, width, at + width, end),
                    )
                }
            }
        }
    }

    @Test fun `cached search agrees with prior search for escaped mixed streaming ranges`() {
        val random = Random(537)
        val alphabet = charArrayOf('a', ' ', '\\', '`', '*', '_', '~', '[', ']', '\n')
        repeat(300) {
            val text = buildString { repeat(random.nextInt(1, 150)) { append(alphabet.random(random)) } }
            val search = InlineDelimiterSearch(text)
            repeat(8) {
                val end = random.nextInt(1, text.length + 1)
                for (at in 0 until end) {
                    val marker = text[at]
                    if (marker !in "`*_~") continue
                    val width = if (marker == '~' ||
                        (marker != '`' && at + 1 < end && text[at + 1] == marker)
                    ) 2 else 1
                    if (at + width > end || (at until at + width).any { text[it] != marker }) continue
                    assertEquals(
                        "text=$text marker=$marker width=$width from=${at + width} end=$end",
                        previousSearch(text, marker, width, at + width, end),
                        search.findClosing(marker, width, at + width, end),
                    )
                }
            }
        }
    }

    @Test fun `escaped closers and nested emphasis retain their visible text`() {
        assertEquals("**unfinished\\* text", inlineMarkdown("**unfinished\\\\* text").text)
        assertEquals("a b c", inlineMarkdown("*a **b** c*").text)
        assertEquals("\u2009a\\`b\u2009", inlineMarkdown("`a\\`b`").text)
    }
}

package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSearchProgressTest {
    private class CountingText(private val value: String) : CharSequence {
        var indexedReads: Int = 0
            private set

        override val length: Int get() = value.length

        override fun get(index: Int): Char {
            indexedReads++
            return value[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            value.subSequence(startIndex, endIndex)
    }

    @Test
    fun `unclosed emphasis search reads each appended suffix once and finds a later closer`() {
        var value = "**" + "x".repeat(4_000)
        var source = CountingText(value)
        val search = InlineDelimiterSearch(source)

        assertEquals(-1, search.findClosing('*', 2, 2, source.length))
        assertTrue("initial query reads the unmatched tail once", source.indexedReads <= 4_002)

        repeat(24) {
            val addition = "tail"
            value += addition
            source = CountingText(value)
            search.update(source, appendOnly = true)
            assertEquals(-1, search.findClosing('*', 2, 2, source.length))
            assertTrue(
                "only the new suffix and a small boundary check are read",
                source.indexedReads <= addition.length + 2,
            )
        }

        value += "**"
        source = CountingText(value)
        search.update(source, appendOnly = true)
        assertEquals(value.length - 2, search.findClosing('*', 2, 2, source.length))
        assertTrue("the appended delimiter run is the only searched text", source.indexedReads <= 4)
    }

    @Test
    fun `an appended marker can extend a previously incomplete run`() {
        val before = CountingText("x*")
        val search = InlineDelimiterSearch(before)
        assertEquals(-1, search.findClosing('*', 2, 0, before.length))

        val after = CountingText("x**")
        search.update(after, appendOnly = true)
        assertEquals(1, search.findClosing('*', 2, 0, after.length))
    }

    @Test
    fun `a trailing escape remains effective when its escaped character arrives later`() {
        var value = "**word\\"
        var source = CountingText(value)
        val search = InlineDelimiterSearch(source)
        assertEquals(-1, search.findClosing('*', 2, 2, source.length))

        value += "*"
        source = CountingText(value)
        search.update(source, appendOnly = true)
        assertEquals(-1, search.findClosing('*', 2, 2, source.length))

        value += "*"
        source = CountingText(value)
        search.update(source, appendOnly = true)
        assertEquals(
            previousSearch(value, '*', 2, 2, value.length),
            search.findClosing('*', 2, 2, source.length),
        )
    }

    @Test
    fun `unclosed link label and URL resume at their previous search frontier`() {
        var value = "[document](https://example.test/" + "x".repeat(1_500)
        var source = CountingText(value)
        val search = InlineLinkSearch(source)
        val labelClose = value.indexOf(']')
        assertEquals(labelClose, search.findLabelClose(0, source.length))
        val urlFrom = labelClose + 2
        assertEquals(-1, search.findUrlClose(0, urlFrom, source.length))
        assertEquals(labelClose + value.length - urlFrom, source.indexedReads)

        repeat(20) {
            val addition = "more"
            value += addition
            source = CountingText(value)
            search.update(source, appendOnly = true)
            assertEquals(labelClose, search.findLabelClose(0, source.length))
            assertEquals(-1, search.findUrlClose(0, urlFrom, source.length))
            assertEquals("URL search reads only the appended suffix", addition.length, source.indexedReads)
        }

        value += ")"
        source = CountingText(value)
        search.update(source, appendOnly = true)
        assertEquals(labelClose, search.findLabelClose(0, source.length))
        assertEquals(value.length - 1, search.findUrlClose(0, urlFrom, source.length))
        assertEquals(1, source.indexedReads)
    }

    @Test
    fun `shared search state matches a fresh scan at every formatted prefix and after rewrite`() {
        val codeBackground = Color(0xFF102030)
        val codeColor = Color(0xFF203040)
        val linkColor = Color(0xFF304050)
        val sourceTracker = InlineLiteralSource()
        val searchState = InlineSearchState("")

        fun compare(prefix: String) {
            val literal = sourceTracker.accepts(prefix)
            val appendOnly = sourceTracker.lastWasAppend
            val actual = if (literal) {
                searchState.update(prefix, appendOnly)
                AnnotatedString(prefix)
            } else {
                scanInlineMarkdown(
                    text = prefix,
                    codeBackground = codeBackground,
                    codeColor = codeColor,
                    linkColor = linkColor,
                    searchState = searchState,
                    appendOnly = appendOnly,
                )
            }
            val expected = inlineMarkdown(
                prefix,
                codeBackground = codeBackground,
                codeColor = codeColor,
                linkColor = linkColor,
            )
            assertEquivalent(expected, actual)
        }

        for (whole in listOf(
            "**be\\*ta** after",
            "[doc](https://example.test/a\\) and then )",
            "~~*nested **style** text*~~",
            "[unfinished nested [label",
        )) {
            for (end in 0..whole.length) compare(whole.substring(0, end))
        }
        compare("rewritten **bold** and [url](https://x.dev)")
    }

    private fun assertEquivalent(expected: AnnotatedString, actual: AnnotatedString) {
        assertEquals(expected.text, actual.text)
        assertEquals(expected.spanStyles, actual.spanStyles)
        assertEquals(linkShape(expected), linkShape(actual))
    }

    private fun linkShape(text: AnnotatedString): List<String> =
        text.getLinkAnnotations(0, text.length).map { range ->
            val target = when (val link = range.item) {
                is LinkAnnotation.Url -> link.url
                is LinkAnnotation.Clickable -> link.tag
                else -> link.toString()
            }
            "${range.start}:${range.end}:$target"
        }

    private fun previousSearch(text: String, marker: Char, len: Int, from: Int, to: Int): Int {
        var index = from
        while (index < to) {
            val char = text[index]
            if (char == '\\' && index + 1 < to) {
                index += 2
                continue
            }
            if (char == marker) {
                var runEnd = index + 1
                while (runEnd < to && text[runEnd] == marker) runEnd++
                val startsRun = index == 0 || text[index - 1] != marker
                val available = runEnd - index
                if (startsRun && (if (len == 1) available == 1 else available >= len)) return index
                index = runEnd
                continue
            }
            index++
        }
        return -1
    }
}

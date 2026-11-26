package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeWebHtmlTest {

    @Test
    fun `htmlToText handles unclosed tags comments entities and blank line collapsing`() {
        val sample = "<p>First</p><!-- hide --><script>x</script>\n\n\n\n<ul><li>Item &amp; 1</li></ul><>keep"
        assertEquals("First\n\n- Item & 1\n<>keep", htmlToText(sample))
    }

    @Test
    fun `decodeEntities leaves overflowing hex intact and drops overrange unicode`() {
        assertEquals("&#x80000000;", decodeEntities("&#x80000000;"))
        assertEquals("A", decodeEntities("&#x0041;&#x200000;"))
        assertEquals(" X", decodeEntities("&NbSp;X"))
        assertEquals("<>plain<\ntail", htmlToText("<>plain<<br>tail</p>"))
    }
}

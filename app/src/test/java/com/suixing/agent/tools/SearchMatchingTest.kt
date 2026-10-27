package com.suixing.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchingTest {
    @Test fun `literal default ignores case`() {
        assertTrue(searchLineMatches("Hello WORLD", "world", false, null))
    }

    @Test fun `literal explicit case distinguishes spelling`() {
        assertFalse(searchLineMatches("WORLD", "world", true, null))
        assertTrue(searchLineMatches("world", "world", true, null))
    }

    @Test fun `regex uses the same case contract`() {
        assertTrue(searchLineMatches("WORLD", "w.*d", false, searchPattern("w.*d", false)))
        assertFalse(searchLineMatches("WORLD", "w.*d", true, searchPattern("w.*d", true)))
        assertTrue(searchLineMatches("world", "w.*d", true, searchPattern("w.*d", true)))
    }

    @Test fun `literal mode keeps regex punctuation literal`() {
        assertFalse(searchLineMatches("abc", "a.c", false, null))
        assertTrue(searchLineMatches("a.c", "a.c", false, null))
    }
}

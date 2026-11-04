package com.suixing.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic for the single-session PTY owner; JNI itself is device-only. */
class TerminalSessionTest {
    @Test
    fun `output stays within the bound and keeps the newest tail`() {
        var text = ""
        repeat(100) { text = appendBounded(text, "abcdefghij", limit = 64) }
        assertTrue(text.length <= 64)
        assertEquals("abcdefghij", text.takeLast(10))
    }

    @Test
    fun `a single oversized chunk is truncated to its tail`() {
        val chunk = "x".repeat(500)
        val result = appendBounded("old", chunk, limit = 64)
        assertEquals(64, result.length)
    }

    @Test
    fun `small appends are concatenated in order`() {
        val result = appendBounded(appendBounded("a", "b", limit = 64), "c", limit = 64)
        assertEquals("abc", result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive bound is rejected`() {
        appendBounded("a", "b", limit = 0)
    }

    @Test
    fun `signal constants match the Linux values the JNI bridge accepts`() {
        assertEquals(2, SIGINT)
        assertEquals(15, SIGTERM)
        assertEquals(9, SIGKILL)
    }
}

package com.suixing.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalStreamsTest {
    @Test fun utf8SurvivesEveryByteBoundaryIncludingEmoji() {
        val decoder = TerminalUtf8Decoder()
        val original = "English中文测试🙂结束"
        val result = buildString {
            for (byte in original.toByteArray()) append(decoder.decode(byteArrayOf(byte)))
            append(decoder.decode(byteArrayOf(), end = true))
        }
        assertEquals(original, result)
        assertFalse(result.contains('\uFFFD'))
    }
    @Test fun incompleteFinalCharacterProducesOneReplacementOnlyAtEof() {
        val decoder = TerminalUtf8Decoder()
        assertEquals("", decoder.decode(byteArrayOf(0xe4.toByte(), 0xb8.toByte())))
        assertEquals("\uFFFD", decoder.decode(byteArrayOf(), end = true))
    }
    @Test fun twoHundredKilobytesRemainReadableIncrementally() {
        val ring = TerminalOutputRing()
        val original = "line\n".repeat(40_000)
        ring.append(original)
        var cursor = 0L
        val result = buildString {
            while (cursor < ring.end) {
                val read = ring.read(cursor, 16 * 1024)
                assertFalse(read.truncated)
                assertTrue(read.output.toByteArray().size <= 16 * 1024)
                append(read.output); cursor = read.cursor
            }
        }
        assertEquals(original, result)
    }
    @Test fun evictedCursorIsReportedAndUnicodeByteBudgetIsRespected() {
        val ring = TerminalOutputRing(32)
        ring.append("旧".repeat(100) + "🙂中文abc")
        val read = ring.read(0, 7)
        assertTrue(read.truncated)
        assertTrue(read.output.toByteArray().size <= 7)
        val tail = ring.read(maxBytes = 10)
        assertEquals("中文abc", tail.output)
        assertEquals(ring.end, tail.cursor)
    }
    @Test fun clearingOutputKeepsCursorMonotonic() {
        val ring = TerminalOutputRing()
        ring.append("old")
        val cursor = ring.end
        ring.clear(); ring.append("new")
        assertEquals("new", ring.read(cursor).output)
        assertEquals(cursor + 3, ring.end)
    }
}

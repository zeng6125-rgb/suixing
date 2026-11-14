package com.suixing.agent.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeJsonTreeTest {
    @Test fun `strings preserve Unicode and isolated surrogates across the bulk boundary`() {
        val units = charArrayOf('中', 'é', '\ud800', 'x', '\udfff', '\ud83d', '\ude00', '\n')
        for (length in listOf(31, 32, 1024)) {
            val text = CharArray(length) { units[it % units.size] }.concatToString()
            val input = input(text)
            assertEquals(text, NativeJsonTree.string(input))
            assertEquals(7 + length * 2, input.position())
            assertEquals(3, input.remaining())
            assertEquals(0x4b, input.get().toInt().and(0xff))
            assertEquals(0x16, input.get().toInt().and(0xff))
            assertEquals(0x7d, input.get().toInt().and(0xff))
        }
    }

    @Test fun `custom limit is accepted exactly and rejected before reading string data`() {
        val text = "a".repeat(32)
        assertEquals(text, NativeJsonTree.string(input(text), maxUnits = 32))
        val input = input(text)
        val error = assertThrows(IOException::class.java) {
            NativeJsonTree.string(input, maxUnits = 31)
        }
        assertEquals("原生 JSON 树响应无效", error.message)
        assertEquals(7, input.position())
    }

    @Test fun `truncated length and string data retain the existing error and position`() {
        val shortHeader = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        val headerError = assertThrows(IOException::class.java) { NativeJsonTree.string(shortHeader) }
        assertEquals("原生 JSON 树响应无效", headerError.message)
        assertEquals(0, shortHeader.position())
        val input = input("a".repeat(32))
        input.limit(7 + 32 * 2 - 1)
        val dataError = assertThrows(IOException::class.java) { NativeJsonTree.string(input) }
        assertEquals("原生 JSON 树响应无效", dataError.message)
        assertEquals(7, input.position())
    }

    private fun input(text: String): ByteBuffer =
        ByteBuffer.allocate(3 + 4 + text.length * 2 + 3).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(3)
            putInt(text.length)
            text.forEach { putChar(it) }
            put(byteArrayOf(0x4b, 0x16, 0x7d))
            position(3)
        }
}

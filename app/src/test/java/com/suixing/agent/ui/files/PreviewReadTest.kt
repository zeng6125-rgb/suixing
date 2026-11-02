package com.suixing.agent.ui.files

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreviewReadTest {
    @Test fun `a growing file never reads beyond the preview limit and sentinel`() = runBlocking {
        var bytesRead = 0
        val stream = object : InputStream() {
            override fun read(): Int { bytesRead++; return 65 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(65, offset, offset + length)
                bytesRead += length
                return length
            }
        }
        assertNull(boundedPreviewBytes(stream, 20 * 1024))
        assertEquals(20 * 1024 + 1, bytesRead)
    }

    @Test fun `exact limit and multibyte text remain intact`() = runBlocking {
        val text = "中文\n🙂".repeat(100)
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, boundedPreviewBytes(ByteArrayInputStream(bytes), bytes.size))
        assertEquals(text, boundedPreviewBytes(ByteArrayInputStream(bytes), bytes.size + 8)!!.toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(), boundedPreviewBytes(ByteArrayInputStream(byteArrayOf()), 20))
    }

    @Test fun `cancelled preview stops between chunks and caller closes input`() = runBlocking {
        var reads = 0
        var closed = false
        val reader = launch {
            val self = coroutineContext[Job]!!
            val input = object : InputStream() {
                override fun read(): Int = error("bulk reads expected")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    self.cancel()
                    return length
                }
                override fun close() { closed = true }
            }
            input.use { boundedPreviewBytes(it, 20 * 1024) }
            fail("cancelled preview must not publish")
        }
        reader.join()
        assertTrue(reader.isCancelled)
        assertEquals(1, reads)
        assertTrue(closed)
    }
}

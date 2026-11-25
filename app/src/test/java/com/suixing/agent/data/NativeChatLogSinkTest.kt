package com.suixing.agent.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChatLogSinkTest {
    @Test
    fun `reused native batch memory leaves earlier messages intact`() {
        val sink = NativeChatLogSink("test", Json, null)
        val batch = ByteBuffer.allocateDirect(512)
        val tree = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        fun writeString(value: String) {
            tree.putInt(value.length)
            for (unit in value) tree.putChar(unit)
        }
        tree.put(6).putInt(3)
        for ((key, value) in mapOf("id" to "u1", "role" to "user", "text" to "你好🙂")) {
            writeString(key)
            tree.put(3)
            writeString(value)
        }

        fun accept(offset: Long, raw: Boolean, bytes: ByteArray) {
            batch.clear()
            batch.order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).putLong(offset).putInt(bytes.size)
                .putInt(if (raw) 1 else 0).putInt(bytes.size).put(bytes)
            batch.flip()
            batch.order(ByteOrder.BIG_ENDIAN)
            sink.accept(batch)
        }
        accept(17, false, tree.array().copyOf(tree.position()))
        accept(99, true, """{"id":"a1","role":"assistant","text":"回复"}""".encodeToByteArray())

        assertEquals(listOf(17L, 99L), sink.entries.map { it.offset })
        assertEquals(listOf("u1", "a1"), sink.entries.map { it.message.id })
        assertEquals(listOf("你好🙂", "回复"), sink.entries.map { it.message.text })
    }
}

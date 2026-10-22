package com.suixing.agent.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatStore.isLineStart` —— 分块扫描里的"行首"判据（评审 S4）。
 *
 * 原来的写法是 `pos + i == 0L || buf[i - 1] == NEWLINE`：当 needle 起点正好落在**块边界**上
 * （`i == 0` 且 `pos > 0`）时会去读 `buf[-1]` ⇒ **数组越界崩溃**。这条分支只有
 * 「1MB 边界与消息起点重合」才走到，靠真实日志几乎碰不到 —— 所以必须直接测判据。
 *
 * 而它是 `readWire`（发消息）与「加载更早」唯一的位置来源，崩了就是"发送失败"。
 */
class OffsetOfLineStartTest {

    private val store = ChatStore(java.io.File("unused"))
    private val newline = '\n'.code.toByte()

    @Test
    fun `the very first byte of the file is a line start`() {
        assertTrue(store.isLineStart(pos = 0L, i = 0, buf = byteArrayOf('{'.code.toByte()), previousChunkLast = null))
        // 即使上一块"最后字节"是 null（首块没有上一块）也不该崩。
        assertTrue(store.isLineStart(0L, 0, byteArrayOf(1), null))
    }

    @Test
    fun `inside a chunk a preceding newline decides`() {
        val buf = byteArrayOf('x'.code.toByte(), newline, '{'.code.toByte())
        assertTrue("前一个字节是换行 ⇒ 行首", store.isLineStart(0L, 2, buf, null))
        assertFalse("前一个字节不是换行 ⇒ 不是行首", store.isLineStart(0L, 1, buf, null))
    }

    @Test
    fun `at a chunk boundary the previous chunk's last byte decides`() {
        // i == 0 且 pos > 0：这就是原来会崩的情形（buf[-1]）。
        val buf = byteArrayOf('{'.code.toByte())
        assertTrue("上一块以换行结尾 ⇒ 是行首", store.isLineStart(1L shl 20, 0, buf, newline))
        assertFalse("上一块不是换行结尾 ⇒ 不是行首", store.isLineStart(1L shl 20, 0, buf, 'x'.code.toByte()))
        // 关键：不越界、不抛异常。
        assertFalse(store.isLineStart(1L shl 20, 0, buf, null))
    }
}

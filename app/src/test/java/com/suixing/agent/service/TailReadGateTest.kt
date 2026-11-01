package com.suixing.agent.service

import com.suixing.agent.data.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #69 P1-A 的判据：**无意义的列表发射不该触发尾部读取**。
 *
 * 用纯计数代替：`shouldRead` 返回 true 的次数 = 实际 `readTail` 调用次数。
 */
class TailReadGateTest {

    private fun chat(id: String, count: Int = 3, updated: Long = 100L) =
        Conversation(id = id, title = id, messageCount = count, updatedAt = updated)

    @Test fun `repeated emissions with the same content do not re-read`() {
        val gate = TailReadGate()
        var reads = 0
        repeat(100) { if (gate.shouldRead(chat("a"))) reads++ }
        assertTrue("100 次同内容发射只该读 1 次，实际 $reads", reads == 1)
    }

    @Test fun `a new message or a newer timestamp re-reads`() {
        val gate = TailReadGate()
        assertTrue(gate.shouldRead(chat("a", count = 3)))
        assertTrue("条数变了要重读", gate.shouldRead(chat("a", count = 4)))
        assertTrue("更新时间变了要重读", gate.shouldRead(chat("a", count = 4, updated = 200L)))
        assertFalse("同内容不重读", gate.shouldRead(chat("a", count = 4, updated = 200L)))
    }

    @Test fun `switching conversations re-reads even with identical counters`() {
        val gate = TailReadGate()
        assertTrue(gate.shouldRead(chat("a")))
        assertTrue("换了会话必须重读", gate.shouldRead(chat("b")))
        assertTrue("换回来也要重读（上次的签名已失效）", gate.shouldRead(chat("a")))
    }
}

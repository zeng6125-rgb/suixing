package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 误点「新建」留下的空会话要在切走时回收，但**有内容的绝不能删**。
 *
 * 判据抽成纯函数就是为了把这些边界一次说清：删错了是丢用户数据，比留个空壳严重得多。
 */
class EmptyChatCleanupTest {

    private fun discard(
        stored: Int = 0,
        inMemory: Int = 0,
        running: Boolean = false,
        draft: Boolean = false,
    ) = shouldDiscardEmptyChat(stored, inMemory, running, draft)

    @Test
    fun `完全没用过的会话会被回收`() {
        assertTrue(discard())
    }

    @Test
    fun `盘上或内存里有消息就不动`() {
        assertFalse("盘上 3 条消息", discard(stored = 3))
        assertFalse("内存里 1 条", discard(inMemory = 1))
    }

    @Test
    fun `正在跑的会话不删`() {
        assertFalse("run 还在跑，删了结果就丢了", discard(running = true))
    }

    @Test
    fun `有草稿的保留`() {
        assertFalse("用户打了字没发，那是有内容的", discard(draft = true))
    }

    @Test
    fun `多个条件同时成立仍然保留`() {
        assertFalse(discard(stored = 1, inMemory = 2, running = true, draft = true))
    }
}

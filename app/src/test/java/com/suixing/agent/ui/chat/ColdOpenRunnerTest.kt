package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #67 P1-3 的最后一块：**读完的那一刻世界已经变了**。
 *
 * 用真实协程 + 真实 `delay`（不需要 coroutines-test）构造"读盘期间用户切走"的时序。
 */
class ColdOpenRunnerTest {

    private val messages = listOf(StoredMessage(id = "m1", role = Roles.ASSISTANT, text = "正文"))

    @Test
    fun `a read that finishes after the user switched away never paints`() = runBlocking {
        var wanted = true
        var applied = 0
        var discarded = 0
        val job = launch {
            runColdOpen(
                read = { delay(50); messages },      // 模拟 50ms 的读盘
                stillWanted = { wanted },
                canApply = { true },
                onApply = { applied += 1 },
                onDiscard = { discarded += 1 },
            )
        }
        delay(10)
        wanted = false                                // 读盘还没回来，用户切走了
        job.join()
        assertEquals("过期结果不许上屏", 0, applied)
        assertEquals(1, discarded)
    }

    @Test
    fun `a read that is still wanted applies exactly once`() = runBlocking {
        var applied = 0
        val result = runColdOpen(
            read = { delay(20); messages },
            stillWanted = { true },
            canApply = { true },
            onApply = { applied += 1 },
        )
        assertTrue(result)
        assertEquals(1, applied)
    }

    @Test
    fun `an empty read clears loading without painting`() = runBlocking {
        var applied = 0
        var discarded = 0
        val result = runColdOpen(
            read = { delay(5); null },
            stillWanted = { true },
            canApply = { true },
            onApply = { applied += 1 },
            onDiscard = { discarded += 1 },
        )
        assertFalse(result)
        assertEquals(0, applied)
        assertEquals(1, discarded)
    }

    @Test
    fun `a page that got content meanwhile is not overwritten`() = runBlocking {
        var applied = 0
        val result = runColdOpen(
            read = { messages },
            stillWanted = { true },
            canApply = { false },                     // 已有正文（热缓存上屏了）
            onApply = { applied += 1 },
        )
        assertFalse(result)
        assertEquals(0, applied)
    }
}

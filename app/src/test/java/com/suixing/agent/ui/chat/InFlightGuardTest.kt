package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InFlightGuard] —— 合并草稿的"同一会话只跑一个"守卫。
 *
 * 守的是三件事：同一 key 不能并发进入 / 不同 key 互不影响 / 离开后能重新进入
 * （最后一条最要紧：守卫如果漏了 `finally`，一次异常就会让该会话**永远**合并不了）。
 */
class InFlightGuardTest {

    @Test
    fun `a second entry for the same key is refused`() {
        val guard = InFlightGuard()
        assertTrue(guard.tryEnter("c1"))
        assertFalse("同一个会话不能同时跑两个合并", guard.tryEnter("c1"))
        assertEquals(1, guard.sizeForTest())
    }

    @Test
    fun `different conversations do not block each other`() {
        val guard = InFlightGuard()
        assertTrue(guard.tryEnter("c1"))
        assertTrue(guard.tryEnter("c2"))
        assertEquals(2, guard.sizeForTest())
    }

    @Test
    fun `leaving lets the same key run again`() {
        val guard = InFlightGuard()
        assertTrue(guard.tryEnter("c1"))
        guard.leave("c1")
        assertFalse(guard.isRunning("c1"))
        assertTrue("离开之后必须能重新进入（模拟 finally 释放）", guard.tryEnter("c1"))
    }

    @Test
    fun `leaving twice is harmless`() {
        val guard = InFlightGuard()
        guard.tryEnter("c1")
        guard.leave("c1")
        guard.leave("c1")
        assertEquals(0, guard.sizeForTest())
    }
}

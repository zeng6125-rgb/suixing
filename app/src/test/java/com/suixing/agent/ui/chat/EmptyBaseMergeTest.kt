package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shouldSkipEmptBaseMerge` —— "base 为空时要不要放弃这次合并"。
 *
 * 守两件相反的事：
 * - 空 base **不能**照旧写盘（否则会把只含恢复消息的**截短历史**当成整份写进缓存并标"已落盘"，
 *   切回来命中缓存、不再读盘 ⇒ 屏幕只显示那几条）；
 * - 但"盘上本来就没有消息"时**必须放过**（那是"新建会话首发后被杀"的自愈路径，
 *   写回不可能截断任何东西）。
 */
class EmptyBaseMergeTest {

    @Test
    fun `a non-empty base is never skipped`() {
        assertFalse(shouldSkipEmptBaseMerge(baseEmpty = false, baseFromDisk = false, listMessageCount = 5))
        assertFalse(shouldSkipEmptBaseMerge(baseEmpty = false, baseFromDisk = true, listMessageCount = 0))
    }

    @Test
    fun `an empty base that did not come from disk is skipped`() {
        // 用户切走导致 shown 变 null：这时写盘会把截短的历史当成整份。
        assertTrue(shouldSkipEmptBaseMerge(baseEmpty = true, baseFromDisk = false, listMessageCount = 5))
    }

    @Test
    fun `an empty base with messages on disk is skipped`() {
        assertTrue(shouldSkipEmptBaseMerge(baseEmpty = true, baseFromDisk = true, listMessageCount = 5))
    }

    @Test
    fun `an empty base over a genuinely empty log may heal`() {
        // 新建会话首发后被杀：盘上确实 0 条 ⇒ 自愈是安全的。
        assertFalse(shouldSkipEmptBaseMerge(baseEmpty = true, baseFromDisk = true, listMessageCount = 0))
    }

    @Test
    fun `an unknown disk count is treated as not-empty`() {
        // 拿不准就保守（不写盘）—— 误写会截断历史，误跳过只是把草稿多留一会儿。
        assertTrue(shouldSkipEmptBaseMerge(baseEmpty = true, baseFromDisk = true, listMessageCount = null))
    }
}

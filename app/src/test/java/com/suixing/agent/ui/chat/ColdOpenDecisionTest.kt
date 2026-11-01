package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #67 P1-3 的确定性回归：**过期的冷读不许覆盖新页面**。
 *
 * 这三种情况在真机上都是"手速快一点"才会撞到的时序，靠人工点不出来；
 * 这里把判据拆成纯函数，三条各自可断言。
 */
class ColdOpenDecisionTest {

    @Test
    fun `the latest intent on an empty page may apply`() {
        assertTrue(canApplyColdOpen("a", "a", intentCurrent = true, currentIsEmpty = true))
    }

    @Test
    fun `a superseded intent never applies even for the same chat`() {
        // 新建挂起期间用户点了别的会话：代次已过期
        assertFalse(canApplyColdOpen("a", "a", intentCurrent = false, currentIsEmpty = true))
    }

    @Test
    fun `a late read for another chat never paints over the current page`() {
        assertFalse(canApplyColdOpen("a", "b", intentCurrent = true, currentIsEmpty = true))
        // 代次过期 + 会话也不同（最坏情况）
        assertFalse(canApplyColdOpen("a", "b", intentCurrent = false, currentIsEmpty = true))
    }

    @Test
    fun `a page that already has content keeps it`() {
        // 热缓存已经上屏，冷读只是补位 ⇒ 不覆盖
        assertFalse(canApplyColdOpen("a", "a", intentCurrent = true, currentIsEmpty = false))
    }
}

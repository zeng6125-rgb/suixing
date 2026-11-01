package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #67 P0-1 的确定性回归：后来者让先来者失效。
 *
 * 这三条对应的真实场景是「新建挂起时点了 B」「快速 A→B→A」「启动恢复与新意图竞争」——
 * ViewModel 整条链要注入假仓库才能测，而"谁的代次还算数"本身是纯逻辑。
 */
class NavigationIntentsTest {

    @Test
    fun `a newer intent supersedes the pending one`() {
        val intents = NavigationIntents()
        val newConversation = intents.begin()
        val openB = intents.begin()
        assertFalse("点了 B 之后，新建就是过期工作", intents.isCurrent(newConversation))
        assertTrue(intents.isCurrent(openB))
    }

    @Test
    fun `rapid switching keeps only the last one current`() {
        val intents = NavigationIntents()
        val a = intents.begin()
        val b = intents.begin()
        val backToA = intents.begin()
        assertFalse(intents.isCurrent(a))
        assertFalse(intents.isCurrent(b))
        assertTrue("最后一次点击说了算", intents.isCurrent(backToA))
    }

    @Test
    fun `tokens are strictly increasing so equality is unambiguous`() {
        val intents = NavigationIntents()
        val first = intents.begin()
        val second = intents.begin()
        assertTrue(second > first)
        assertTrue(intents.current() == second)
    }
}

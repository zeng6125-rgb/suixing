package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeltaBuffer] 的三条沉默规则：不丢、不漏、到点放行。
 *
 * 反复出现的三种形态（都不会报错，只会让用户看到"回答少一截"或"新回答开头多出旧话"）：
 *  - 节流把文字永久攒住（一直不动）；
 *  - 会话结束时缓冲没清（下一轮被取走）；
 *  - 取走后没清空（同一段文字发两次）。
 */
class DeltaBufferTest {

    @Test
    fun `what goes in comes out exactly once`() {
        val b = DeltaBuffer()
        b.locked {
            b.append("c1", "你好", null)
            b.append("c1", "世界", "思考")
        }
        val first = b.locked { b.take("c1") }
        assertEquals("你好世界", first.first)
        assertEquals("思考", first.second)
        val second = b.locked { b.take("c1") }
        assertEquals("取走后必须清空（否则同一段文字会被发两次）", "", second.first)
        assertEquals("", second.second)
    }

    @Test
    fun `chats do not see each other's text`() {
        val b = DeltaBuffer()
        b.locked {
            b.append("a", "甲的", null)
            b.append("b", "乙的", null)
        }
        assertEquals("甲的", b.locked { b.take("a") }.first)
        assertEquals("乙的", b.locked { b.take("b") }.first)
        assertNull("取空后再取不该有内容", b.locked { b.take("a").first.ifEmpty { null } })
    }

    @Test
    fun `throttling holds the text until the interval is over`() {
        val b = DeltaBuffer()
        // 按**真实调用顺序**来：append → 判断 → 放行就 take（漏掉 take 就是攒着不发）。
        val firstPull = b.locked {
            b.append("c", "一", null)
            if (b.mayPublish("c", 1_000, 80)) b.take("c") else null
        }
        assertEquals("到点必须放行并取走", "一", firstPull?.first)

        // 间隔内：不放行，文字留在缓冲里（攒到下次一起发），这期间**不能丢**。
        val throttled = b.locked {
            b.append("c", "二", null)
            if (b.mayPublish("c", 1_040, 80)) b.take("c") else null
        }
        assertNull("间隔内不该发布", throttled)
        assertEquals("但文字必须还在", "二", b.locked { b.take("c") }.first)

        // 到点：放行。
        assertTrue(b.locked { b.mayPublish("c", 1_100, 80) })
    }

    @Test
    fun `dropping a finished chat also forgets its throttle clock`() {
        val b = DeltaBuffer()
        b.locked { b.append("c", "残留", null); b.mayPublish("c", 5_000, 80) }
        // 会话结束：残字必须丢掉，否则会在**下一轮**被取走、拼到新内容前面。
        b.drop("c")
        assertEquals("残字必须丢弃", "", b.locked { b.take("c") }.first)
        // 节流记账也清掉了：下一轮的第一段 delta 立刻可以发（不必等满 80ms）。
        assertTrue("新的一轮不该继承上一轮的发布时刻", b.locked { b.mayPublish("c", 5_010, 80) })
    }

    @Test
    fun `empty deltas are ignored`() {
        val b = DeltaBuffer()
        b.locked { b.append("c", "", ""); b.append("c", null, null) }
        assertEquals("", b.locked { b.take("c") }.first)
    }

    @Test
    fun `peek shows the pending tail without taking it`() {
        // 草稿（进程被杀后的恢复副本）需要看到**还没发布**的这段：后台会话的 delta 只进缓冲、
        // 不进 sessions，不补的话草稿是空的，进程一死就真丢输出。
        val b = DeltaBuffer()
        b.locked { b.append("c", "还没发出去的", "思考尾巴") }
        val peeked = b.locked { b.peek("c") }
        assertEquals("还没发出去的", peeked.first)
        assertEquals("思考尾巴", peeked.second)
        // **只读**：peek 之后内容仍在（否则下一次 take 会漏）。
        assertEquals("还没发出去的", b.locked { b.take("c") }.first)
        assertEquals("取走之后 peek 应为空", "", b.locked { b.peek("c") }.first)
    }

    @Test
    fun `peek on an empty chat is empty`() {
        val b = DeltaBuffer()
        assertEquals("", b.locked { b.peek("nobody") }.first)
    }
}

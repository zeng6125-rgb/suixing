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

    /**
     * 多会话并行：一个会话的节流时钟与内容不能影响另一个（issue #59 A4 的验收项之一）。
     * 尾部补发定时器是**按 chatId** 排的，时钟若串了，切走的会话会提前发布、或永远不发。
     */
    @Test
    fun `tail flush bookkeeping is per chat`() {
        val buffer = DeltaBuffer()
        val now = 1_000L
        buffer.locked {
            buffer.append("a", "A1", null)
            assertTrue("a 的首拍该放行", buffer.mayPublish("a", now, 16))
            buffer.append("b", "B1", null)
            assertTrue("b 有自己的时钟，不该被 a 挡住", buffer.mayPublish("b", now, 16))
            buffer.append("a", "A2", null)
            assertFalse("a 在窗口内不该再放行", buffer.mayPublish("a", now + 5, 16))
            assertTrue("a 到点后放行", buffer.mayPublish("a", now + 16, 16))
        }
        assertEquals("A1A2", buffer.locked { buffer.take("a") }.first)
        assertEquals("B1", buffer.locked { buffer.take("b") }.first)
    }

    /**
     * 切会话 / 停止时 `drop` 必须连定时记账一起清掉：残留的 `lastPublishAt` 会让
     * **下一轮**的首拍被无谓地压住 16ms（表现为"新回答开头顿一下才出字"）。
     */
    @Test
    fun `dropping a chat clears its throttle clock and content`() {
        val buffer = DeltaBuffer()
        buffer.locked {
            buffer.append("c", "text", "think")
            assertTrue(buffer.mayPublish("c", 500L, 16))
            buffer.drop("c")
            assertEquals("内容清空", "" to "", buffer.take("c"))
            assertTrue("时钟也清了：立刻就能再发", buffer.mayPublish("c", 500L, 16))
        }
    }
}

package com.suixing.agent.ui.drawer

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #66 P0-1 的确定性判据：**旧快照不许后到覆盖新顺序**。
 *
 * 不是靠时序碰运气：这里人为让第一次写卡住，再提交第二次，然后放行第一次 ——
 * 乱序完成是构造出来的。
 */
class OrderWriteQueueTest {

    private fun order(vararg ids: String) = DrawerOrder(normal = ids.toList())

    @Test
    fun `an older enqueue never overwrites a newer one`() = runBlocking {
        val written = mutableListOf<List<String>>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val queue = OrderWriteQueue { order ->
            if (written.isEmpty()) gate.await()   // 第一次写卡住，制造乱序
            written += order.normal
        }

        val first = launch { queue.enqueue(order("old")) }
        yield()
        val second = launch { queue.enqueue(order("new")) }
        yield()
        gate.complete(Unit)                        // 放行第一次
        first.join(); second.join()

        // 不变式：**最后一次落盘一定是最新顺序**，旧快照不许排在它后面
        assertEquals("最后一次落盘必须是最新顺序", listOf("new"), written.last())
        val newAt = written.indexOfFirst { it == listOf("new") }
        val oldAfterNew = written.drop(newAt + 1).any { it == listOf("old") }
        assertTrue("旧快照不许在新顺序之后再落盘", !oldAfterNew)
    }

    @Test
    fun `a single enqueue is written`() = runBlocking {
        val written = mutableListOf<List<String>>()
        val queue = OrderWriteQueue { written += it.normal }
        assertTrue(queue.enqueue(order("a", "b")))
        assertEquals(listOf(listOf("a", "b")), written)
    }

    @Test
    fun `a failed write can be retried with the same content`() = runBlocking {
        var attempts = 0
        val queue = OrderWriteQueue {
            attempts += 1
            if (attempts == 1) error("disk full")
        }
        runCatching { queue.enqueue(order("x")) }
        // 失败不该把这次顺序标记成"已写" —— 同内容再交一次仍然要落盘
        queue.enqueue(order("x"))
        assertEquals(2, attempts)
    }
}

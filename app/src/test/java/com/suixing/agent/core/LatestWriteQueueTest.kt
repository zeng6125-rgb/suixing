package com.suixing.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #66 P0-1 / #68 P1-B 共用判据：旧快照不许后到覆盖新状态。 */
class LatestWriteQueueTest {

    @Test fun `an older enqueue never lands after a newer one`() = runBlocking {
        val written = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val queue = LatestWriteQueue<String> { value ->
            if (written.isEmpty()) gate.await()   // 第一次写卡住，制造乱序
            written += value
        }
        val first = launch { queue.enqueue("old") }
        yield()
        val second = launch { queue.enqueue("new") }
        yield()
        gate.complete(Unit)
        first.join(); second.join()

        assertEquals("最后一次落盘必须是最新内容", "new", written.last())
        val atNew = written.indexOf("new")
        assertTrue("旧内容不许排在新内容之后", written.drop(atNew + 1).none { it == "old" })
    }

    @Test fun `a failed write can be retried`() = runBlocking {
        var attempts = 0
        val queue = LatestWriteQueue<String> {
            attempts += 1
            if (attempts == 1) error("disk full")
        }
        runCatching { queue.enqueue("x") }
        queue.enqueue("x")
        assertEquals(2, attempts)
    }

    @Test fun `a single enqueue writes once`() = runBlocking {
        val written = mutableListOf<String>()
        val queue = LatestWriteQueue<String> { written += it }
        assertTrue(queue.enqueue("only"))
        assertEquals(listOf("only"), written)
    }
}

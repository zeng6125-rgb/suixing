package com.suixing.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val request = queue.submit("x")
        runCatching { queue.commit(request) }
        assertTrue("a failed current request remains retryable", queue.isCurrent(request))
        queue.commit(request)
        assertEquals(2, attempts)
    }

    @Test fun `a single enqueue writes once`() = runBlocking {
        val written = mutableListOf<String>()
        val queue = LatestWriteQueue<String> { written += it }
        assertTrue(queue.enqueue("only"))
        assertEquals(listOf("only"), written)
    }

    @Test fun `a newer synchronous submission invalidates an older late commit`() = runBlocking {
        val written = mutableListOf<String>()
        val queue = LatestWriteQueue<String> { written += it }
        val older = queue.submit("old intent")
        val newer = queue.submit("new intent")

        assertFalse(queue.isCurrent(older))
        assertTrue(queue.isCurrent(newer))
        assertFalse(queue.commit(older))
        assertTrue(queue.commit(newer))
        assertEquals(listOf("new intent"), written)
    }

    @Test fun `await idle waits for an active writer`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val queue = LatestWriteQueue<String> {
            started.complete(Unit)
            gate.await()
        }
        val request = queue.submit("write")
        val writer = launch { queue.commit(request) }
        started.await()
        var idle = false
        val waiter = launch {
            queue.awaitIdle()
            idle = true
        }
        yield()
        assertFalse("load must wait while a write is active", idle)
        gate.complete(Unit)
        writer.join()
        waiter.join()
        assertTrue(idle)
    }

    @Test fun `cancellation releases the queue without marking the request written`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var attempts = 0
        val queue = LatestWriteQueue<String> {
            attempts += 1
            if (attempts == 1) {
                started.complete(Unit)
                gate.await()
            }
        }
        val request = queue.submit("retry")
        val first = launch { queue.commit(request) }
        started.await()
        first.cancelAndJoin()

        assertTrue(queue.isCurrent(request))
        assertTrue(queue.commit(request))
        assertEquals(2, attempts)
    }

    @Test fun `recovery lock keeps a newer write and alarm last`() = runBlocking {
        val events = mutableListOf<String>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val queue = LatestWriteQueue<String> { value ->
            events += "write:$value"
            events += "arm:$value"
        }
        val failed = queue.submit("old")
        val recovery = launch {
            assertTrue(queue.recoverIfCurrent(failed) {
                events += "recovery-read:old"
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
                events += "recovery-arm:old"
            })
        }
        recoveryStarted.await()
        val newest = queue.submit("new")
        val commit = launch { queue.commit(newest) }
        yield()
        releaseRecovery.complete(Unit)
        recovery.join()
        commit.join()

        assertEquals(
            listOf("recovery-read:old", "recovery-arm:old", "write:new", "arm:new"),
            events,
        )
    }

    @Test fun `stale request skips recovery`() = runBlocking {
        val queue = LatestWriteQueue<String> { }
        val stale = queue.submit("old")
        queue.submit("new")
        var recovered = false

        assertFalse(queue.recoverIfCurrent(stale) { recovered = true })
        assertFalse(recovered)
    }
}

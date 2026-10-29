package com.suixing.agent.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SubagentsTest {
    @Test fun `parallel work is bounded and results retain requested order and isolated inputs`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val pool = SubagentPool(this, parallelism = 2) { task ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            if (now == 2) started.complete(Unit)
            try { release.await(); task.task + ":" + task.context } finally { active.decrementAndGet() }
        }
        try {
            val ids = (1..4).map { pool.spawn("task$it", "context$it").id }
            withTimeout(2000) { started.await() }
            assertEquals(2, pool.snapshots().count { it.status == SubagentStatus.RUNNING })
            assertEquals(2, pool.snapshots().count { it.status == SubagentStatus.QUEUED })
            release.complete(Unit)
            val results = pool.waitFor(ids.reversed(), 2000)
            assertEquals(ids.reversed(), results.map { it.id })
            assertEquals((4 downTo 1).map { "task$it:context$it" }, results.map { it.result })
            assertEquals(2, peak.get())
        } finally { pool.close() }
    }

    @Test fun `wait timeout leaves work alive and later wait collects its result`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val pool = SubagentPool(this) { release.await(); "done" }
        try {
            val id = pool.spawn("task").id
            assertNotEquals(SubagentStatus.CANCELLED, pool.waitFor(listOf(id), 5).single().status)
            release.complete(Unit)
            assertEquals("done", pool.waitFor(listOf(id), 2000).single().result)
        } finally { pool.close() }
    }

    @Test fun `queued cancellation never runs and running cancellation releases its resources`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val pool = SubagentPool(this, parallelism = 1) {
            calls.incrementAndGet()
            started.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        }
        try {
            val first = pool.spawn("first").id
            val second = pool.spawn("second").id
            withTimeout(2000) { started.await() }
            assertTrue(pool.cancel(second))
            assertTrue(pool.cancel(first))
            withTimeout(2000) { cleaned.await() }
            assertTrue(pool.waitFor(timeoutMs = 2000).all { it.status == SubagentStatus.CANCELLED })
            assertEquals(1, calls.get())
            assertFalse(pool.cancel("unknown"))
        } finally { pool.close() }
    }

    @Test fun `one failed task does not cancel siblings and task cap holds`() = runBlocking {
        val pool = SubagentPool(this, maximumTasks = 2) { if (it.task == "bad") error("failure") else "ok" }
        try {
            pool.spawn("bad")
            pool.spawn("good")
            assertThrows(IllegalStateException::class.java) { pool.spawn("excess") }
            val results = pool.waitFor(timeoutMs = 2000)
            assertEquals(listOf(SubagentStatus.FAILED, SubagentStatus.COMPLETED), results.map { it.status })
            assertEquals("failure", results.first().result)
            assertEquals("ok", results.last().result)
        } finally { pool.close() }
    }

    @Test fun `deadline fails task and closes runner`() = runBlocking {
        val cleaned = CompletableDeferred<Unit>()
        val pool = SubagentPool(this) { try { awaitCancellation() } finally { cleaned.complete(Unit) } }
        try {
            pool.spawn("task", timeoutMs = 20)
            assertEquals(SubagentStatus.FAILED, pool.waitFor(timeoutMs = 2000).single().status)
            assertTrue(cleaned.isCompleted)
        } finally { pool.close() }
    }

    @Test fun `close cancels pending children and rejects more work`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val pool = SubagentPool(this, parallelism = 1) { started.complete(Unit); awaitCancellation() }
        pool.spawn("running")
        pool.spawn("queued")
        withTimeout(2000) { started.await() }
        pool.close()
        assertTrue(pool.snapshots().isEmpty())
        assertThrows(IllegalStateException::class.java) { pool.spawn("late") }
        Unit
    }

    @Test fun `cancellation between reservation and worker attachment cannot start task`() = runBlocking {
        var ran = false
        lateinit var pool: SubagentPool
        pool = SubagentPool(this, onChanged = { snapshots ->
            snapshots.filter { it.status == SubagentStatus.QUEUED }.forEach { pool.cancel(it.id) }
        }) { ran = true; "unexpected" }
        try {
            pool.spawn("task")
            assertEquals(SubagentStatus.CANCELLED, pool.waitFor(timeoutMs = 2000).single().status)
            assertFalse(ran)
        } finally { pool.close() }
    }

    @Test fun `parent cancellation ends independent worker scope`() = runBlocking {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val pool = SubagentPool(CoroutineScope(parent + Dispatchers.Default)) { started.complete(Unit); awaitCancellation() }
        pool.spawn("task")
        withTimeout(2000) { started.await() }
        parent.cancel()
        pool.close()
        assertTrue(pool.snapshots().isEmpty())
    }

    @Test fun `close releases completed result snapshots and publishes the empty pool`() = runBlocking {
        var latest = emptyList<SubagentSnapshot>()
        val pool = SubagentPool(this, onChanged = { latest = it }) { "x".repeat(64_000) }
        pool.spawn("task")
        assertEquals(64_000, pool.waitFor(timeoutMs = 2000).single().result.length)
        pool.close()
        assertTrue(pool.snapshots().isEmpty())
        assertTrue(latest.isEmpty())
    }
}

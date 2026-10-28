package com.suixing.agent.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallbackTest {
    private fun assertPropagated(expected: Throwable, actual: Throwable?) {
        assertEquals("exception type", expected.javaClass, actual?.javaClass)
        assertEquals("exception message", expected.message, actual?.message)
        // Coroutine stack-trace recovery may copy an exception and keep the original
        // as its cause. Check that provenance without depending on recovery being off.
        assertTrue(
            "original exception must remain in the cause chain",
            generateSequence(actual) { it.cause }.take(8).any { it === expected },
        )
    }

    private class Queue {
        val pending = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()

        fun post(action: Runnable): Boolean {
            pending += action
            return true
        }

        fun remove(action: Runnable) {
            removed += action
            pending.remove(action)
        }

        fun take(): Runnable = pending.removeAt(0)
    }

    @Test
    fun `queued action returns its value once dispatched`() = runBlocking {
        val queue = Queue()
        var writes = 0
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            awaitToolCallback(queue::post, queue::remove) { ++writes; "written" }
        }
        assertFalse(call.isCompleted)
        assertEquals(0, writes)

        queue.take().run()

        assertEquals("written", call.await())
        assertEquals(1, writes)
        assertTrue(queue.pending.isEmpty())
        assertTrue(queue.removed.isEmpty())
    }

    @Test
    fun `canceling removes pending work and preserves the cancellation`() = runBlocking {
        val queue = Queue()
        var writes = 0
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            awaitToolCallback(queue::post, queue::remove) { writes++ }
        }
        val action = queue.pending.single()
        val stopped = CancellationException("tool stopped")

        call.cancel(stopped)

        assertTrue(queue.pending.isEmpty())
        assertSame(action, queue.removed.single())
        assertEquals(0, writes)
        assertPropagated(stopped, runCatching { call.await() }.exceptionOrNull())
    }

    @Test
    fun `already dequeued work skips its side effect after cancellation`() = runBlocking {
        val queue = Queue()
        var writes = 0
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            awaitToolCallback(queue::post, queue::remove) { writes++ }
        }
        val dequeued = queue.take()

        call.cancel()
        dequeued.run()
        call.join()

        assertEquals(0, writes)
        assertSame(dequeued, queue.removed.single())
    }

    @Test
    fun `cancellation during posting still removes the newly queued action`() = runBlocking {
        val queue = Queue()
        val stopped = CancellationException("stopped while posting")
        var writes = 0
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            val caller = currentCoroutineContext()[Job]!!
            awaitToolCallback(
                post = { action ->
                    queue.post(action)
                    caller.cancel(stopped)
                    true
                },
                remove = queue::remove,
            ) { writes++ }
        }

        assertTrue(queue.pending.isEmpty())
        assertEquals(1, queue.removed.size)
        assertEquals(0, writes)
        assertPropagated(stopped, runCatching { call.await() }.exceptionOrNull())
    }

    @Test
    fun `immediate dispatch completes without waiting for cancellation registration`() = runBlocking {
        var writes = 0
        val value = awaitToolCallback(
            post = { action -> action.run(); true },
            remove = { error("completed work should not be removed") },
        ) { ++writes; "written" }

        assertEquals("written", value)
        assertEquals(1, writes)
    }

    @Test
    fun `block failure preserves its type message and original cause`() = runBlocking {
        val queue = Queue()
        val failure = IllegalArgumentException("clipboard failure")
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { awaitToolCallback(queue::post, queue::remove) { throw failure } }
        }

        queue.take().run()

        assertPropagated(failure, call.await().exceptionOrNull())
    }

    @Test
    fun `block cancellation preserves its type message and original cause`() = runBlocking {
        val stopped = CancellationException("operation canceled")

        val result = runCatching {
            awaitToolCallback(
                post = { action -> action.run(); true },
                remove = {},
            ) { throw stopped }
        }

        assertPropagated(stopped, result.exceptionOrNull())
    }

    @Test
    fun `rejected post fails promptly without running the block`() = runBlocking {
        var writes = 0
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { awaitToolCallback(post = { false }, remove = {}) { writes++ } }
        }

        assertTrue(call.isCompleted)
        assertTrue(call.await().exceptionOrNull() is IllegalStateException)
        assertEquals(0, writes)
    }
}

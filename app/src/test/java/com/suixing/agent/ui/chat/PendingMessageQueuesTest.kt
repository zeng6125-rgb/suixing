package com.suixing.agent.ui.chat

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PendingMessageQueuesTest {
    private data class Input(val id: String, val images: List<String> = emptyList())

    private fun queue() = PendingMessageQueues<Input> { it.id }

    @Test fun `background completion preserves input and reopening drains it once`() {
        val queue = queue()
        val input = Input("next", listOf("full-image-payload"))
        queue.add("a", input)
        var shown = "b"
        val sent = ArrayList<Input>()
        fun drain() = queue.drain("a", { shown == "a" }) { sent += it; true }
        assertEquals(PendingMessageQueues.DrainResult.BACKGROUND, drain())
        assertSame(input, queue.list("a").single())
        assertTrue(sent.isEmpty())
        shown = "a"
        assertEquals(PendingMessageQueues.DrainResult.SENT, drain())
        assertEquals(listOf(input), sent)
        assertEquals(PendingMessageQueues.DrainResult.EMPTY, drain())
        assertEquals(0, queue.conversationCount)
    }

    @Test fun `refused input returns before newer input with images intact`() {
        val queue = queue()
        val first = Input("first", listOf("image"))
        queue.add("a", first)
        queue.add("a", Input("second"))
        assertEquals(PendingMessageQueues.DrainResult.REFUSED, queue.drain("a", { true }) {
            queue.add("a", Input("third"))
            false
        })
        assertEquals(listOf("first", "second", "third"), queue.list("a").map { it.id })
        assertSame(first, queue.list("a").first())
    }

    @Test fun `reentrant completion cannot send the next turn at the same time`() {
        val queue = queue()
        queue.add("a", Input("one"))
        queue.add("a", Input("two"))
        val result = queue.drain("a", { true }) {
            assertEquals("one", it.id)
            assertEquals(listOf("two"), queue.list("a").map { it.id })
            assertEquals(PendingMessageQueues.DrainResult.BUSY, queue.drain("a", { true }) {
                fail("a second completion must not send"); true
            })
            true
        }
        assertEquals(PendingMessageQueues.DrainResult.SENT, result)
        assertEquals(listOf("two"), queue.list("a").map { it.id })
    }

    @Test fun `consuming or retracting the last item releases its conversation entry`() {
        val queue = queue()
        repeat(200) {
            queue.add("c$it", Input("i$it", listOf("large-image")))
            assertNotNull(queue.take("c$it", "i$it"))
        }
        assertEquals(0, queue.conversationCount)
        queue.add("kept", Input("valid"))
        queue.add("deleted", Input("discard"))
        queue.forget("deleted")
        assertEquals(listOf(Input("valid")), queue.list("kept"))
        assertEquals(1, queue.conversationCount)
    }

    @Test fun `a thrown sender restores input and releases the drain guard`() {
        val queue = queue()
        queue.add("a", Input("next"))
        val error = IllegalStateException("send failed")
        try {
            queue.drain("a", { true }) { throw error }
            fail("expected sender failure")
        } catch (caught: IllegalStateException) {
            assertSame(error, caught)
        }
        assertEquals(listOf(Input("next")), queue.list("a"))
        assertEquals(PendingMessageQueues.DrainResult.SENT, queue.drain("a", { true }) { true })
        assertEquals(0, queue.conversationCount)
    }

    @Test fun `concurrent enqueue and take operations preserve every distinct input`() {
        val queue = queue()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val adds = (0 until 4).map { worker -> workers.submit {
                repeat(100) { queue.add("a", Input("$worker-$it")) }
            } }
            adds.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(400, queue.list("a").size)
            val takes = (0 until 4).map { worker -> workers.submit {
                repeat(100) { assertNotNull(queue.take("a", "$worker-$it")) }
            } }
            takes.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(0, queue.conversationCount)
        } finally {
            workers.shutdownNow()
        }
    }
}

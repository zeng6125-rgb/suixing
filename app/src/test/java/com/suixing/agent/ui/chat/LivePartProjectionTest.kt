package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LivePartProjectionTest {
    @Test fun `text deltas retain unchanged tool lists without freezing shared text buffers`() {
        val projection = LivePartProjection("run")
        val tools = LivePart.Tools(listOf(LiveTool("run_shell", "id", 0L)))
        val before = listOf<LivePart>(tools, LivePart.Text("hello"))
        val first = projection.project(before, true)
        val next = projection.project(before.appendText(" world", 0), true)
        assertSame(first[0], next[0])
        assertSame((first[0] as Part.Tools).calls, (next[0] as Part.Tools).calls)
        assertNotSame(first[1], next[1])
        assertEquals("hello world", (next[1] as Part.Text).text)
    }

    @Test fun `a finished tool replaces its old row while preceding prose is retained`() {
        val projection = LivePartProjection("run")
        val text = LivePart.Text("checking")
        val call = LiveTool("run_shell", "id", 0L)
        val first = projection.project(listOf(text, LivePart.Tools(listOf(call))), true)
        val next = projection.project(listOf(text, LivePart.Tools(listOf(call.copy(done = true)))), true)
        assertSame(first[0], next[0])
        assertEquals(true, (next[1] as Part.Tools).calls.single().done)
    }

    @Test fun `reasoning visibility changes without replacing unrelated blocks`() {
        val projection = LivePartProjection("run")
        val source = listOf(LivePart.Thinking("think"), LivePart.Text("answer"))
        val shown = projection.project(source, true)
        val hidden = projection.project(source, false)
        assertEquals(1, hidden.size)
        assertSame(shown[1], hidden[0])
        val restored = projection.project(source, true)
        assertEquals("run", (restored[0] as Part.Thinking).runId)
        assertSame(hidden[0], restored[1])
    }

    @Test fun `a changed thinking wrapper refreshes content without structural list comparisons`() {
        val projection = LivePartProjection("run")
        val source = listOf<LivePart>(LivePart.Thinking("a"))
        projection.project(source, true)
        val next = projection.project(source.appendThinking("b", 0), true)
        assertEquals("ab", (next.single() as Part.Thinking).text)
    }

    @Test fun `new list wrappers containing identical parts reuse the rendered list`() {
        val projection = LivePartProjection("run")
        val text = LivePart.Text("same")
        val first = projection.project(listOf(text), true)
        assertSame(first, projection.project(listOf(text), true))
        assertEquals(emptyList<Part>(), projection.project(emptyList(), true))
    }
}

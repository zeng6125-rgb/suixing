package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldHandoverTest {
    @Test fun `single pass fold identities preserve raw run ordinals and cumulative calls`() {
        val firstCall = ToolLine("run-shell", "{}", false, true, 0)
        val source = listOf(
            Part.Thinking("legacy", 0, 0, ""),
            Part.Tools(listOf(firstCall, firstCall)),
            Part.Thinking("named", 0, 0, "fallback"),
            Part.Thinking("legacy again", 0, 0, ""),
            Part.Text("body"),
            Part.Tools(listOf(firstCall)),
        )
        val expected = source.indices.map { processFoldSlot(source, it, "fallback") }
        assertEquals(expected, processFoldSlots(source, "fallback"))
        assertEquals(listOf("fallback:0", "fallback:tools:0", "fallback:0", "fallback:1", null,
            "fallback:tools:2"), expected)
    }

    @Test fun `fold identity indexing reads a long process once`() {
        var reads = 0
        val source = object : AbstractList<Part>() {
            override val size = 10_000
            override fun get(index: Int): Part {
                reads++
                return Part.Thinking("think", 0, 0, "run")
            }
        }
        val slots = processFoldSlots(source, "run")
        assertEquals(10_000, reads)
        assertEquals("run:9999", slots.last())
    }

    private val think = Part.Thinking("already read", 10, 20, "run")
    private val call = ToolLine("run-shell", "{}", false, true, 0)
    private val parts = listOf(think, Part.Tools(listOf(call)), Part.Text("answer"))

    @Test fun `untouched completed process can close while live process starts open`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        assertTrue(state.summaryExpanded(parts, "run", streaming = true))
        assertFalse(state.summaryExpanded(parts, "run", streaming = false))
    }

    @Test fun `manually opened thinking keeps completed process open until explicitly reclosed`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        val slot = thinkSlot(parts, 0, "run")
        state.set(slot, true)
        assertTrue(state.summaryExpanded(parts, "run", streaming = false))
        state.set(slot, false)
        assertFalse(state.summaryExpanded(parts, "run", streaming = false))
        state.set(slot, true)
        assertTrue(state.summaryExpanded(parts, "run", streaming = false))
    }

    @Test fun `opened tool detail preserves visible process but a closed tool parent wins`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        val slot = processFoldSlot(parts, 1, "run")!!
        state.set("$slot:call:0", true)
        assertTrue(state.hasOpenFold(parts, "run"))
        state.set(slot, false)
        assertFalse(state.hasOpenFold(parts, "run"))
        state.set(slot, true)
        assertTrue(state.hasOpenFold(parts, "run"))
    }

    @Test fun `explicit summary close overrides both open descendants and reading latch`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        state.set(thinkSlot(parts, 0, "run"), true)
        state.set(summarySlot("run"), false)
        assertFalse(state.summaryExpanded(parts, "run", streaming = true))
        assertFalse(state.summaryExpanded(parts, "run", streaming = false))
        state.set(summarySlot("run"), true)
        assertTrue(state.summaryExpanded(parts, "run", streaming = false))
    }

    @Test fun `reading does not count as manually opening a fold`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        assertTrue(state.summaryExpanded(parts, "run", streaming = true))
        assertNull(state.stored(thinkSlot(parts, 0, "run")))
        assertFalse(state.summaryExpanded(parts, "run", streaming = false))
    }

    @Test fun `legacy reading latch cannot prevent automatic completion collapse`() {
        val state = ReasoningExpansion("chat", mutableMapOf())
        state.set("run:reading", true)
        assertFalse(state.summaryExpanded(parts, "run", streaming = false))
    }

    @Test fun `different conversations and runs never share manual choices`() {
        val entries = mutableMapOf<String, Boolean>()
        val first = ReasoningExpansion("first", entries)
        val second = ReasoningExpansion("second", entries)
        first.set(summarySlot("run"), true)
        assertNull(second.stored(summarySlot("run")))
        assertNull(first.stored(summarySlot("other")))
    }

    @Test fun `tool identity stays the same when result arrives and distinguishes later bundles`() {
        val later = parts + Part.Tools(listOf(call))
        val completed = later.map { part ->
            if (part is Part.Tools) Part.Tools(part.calls.map { it.copy(done = true, elapsedMs = 30) }) else part
        }
        assertEquals(processFoldSlot(later, 1, "run"), processFoldSlot(completed, 1, "run"))
        assertEquals(processFoldSlot(later, 3, "run"), processFoldSlot(completed, 3, "run"))
        assertNotEquals(processFoldSlot(later, 1, "run"), processFoldSlot(later, 3, "run"))
    }

    @Test fun `final prose and all following parts remain in source order outside process`() {
        val source = parts + Part.Tools(listOf(call)) + Part.Trace("retry")
        val tail = processTailStart(source)
        assertEquals(2, tail)
        assertEquals(source, source.take(tail) + source.drop(tail))
        assertEquals(listOf(parts.last(), source[3], source[4]), source.drop(tail))
        assertEquals(1, processTailStart(listOf(think)))
        assertEquals(0, processTailStart(emptyList()))
    }

    @Test fun `new prose moves the previous tail into prefix without losing or reordering its parts`() {
        val previous = parts + Part.Tools(listOf(call)) + Part.Trace("retry")
        val finalProse = Part.Text("new answer after the tool")
        val next = previous + finalProse
        val boundary = processTailStart(next)
        assertEquals(previous, next.take(boundary))
        assertEquals(listOf(finalProse), next.drop(boundary))
        assertEquals(previous.drop(processTailStart(previous)), next.take(boundary).drop(processTailStart(previous)))
    }
}

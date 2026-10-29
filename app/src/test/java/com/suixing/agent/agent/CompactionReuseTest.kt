package com.suixing.agent.agent

import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.*
import org.junit.Test

class CompactionReuseTest {
    private fun history(count: Int) = (1..count).map { StoredMessage(id = "m$it", role = Roles.USER, text = "original-$it") }
    private fun checkpoint(from: String = "m1", to: String = "m8", summary: String = "saved decisions") =
        ContextCheckpoint("cp-$to", 1L, from, to, summary, 8, 100)

    @Test fun `manual compression reuses prior summary and only new foldable rows`() {
        val plan = planCompaction(history(20), checkpoints = listOf(checkpoint()))!!
        assertTrue(plan.transcript.startsWith("system: [上下文摘要] saved decisions\n"))
        assertFalse(plan.transcript.contains("original-8"))
        assertFalse(plan.transcript.contains("original-1\n"))
        assertTrue(plan.transcript.contains("original-9"))
        assertTrue(plan.transcript.contains("original-14"))
        assertFalse(plan.transcript.contains("original-15"))
        assertEquals("m1", plan.coveredIds.first())
        assertEquals("m14", plan.coveredIds.last())
        assertEquals(14, plan.foldedMessages)
    }

    @Test fun `repeated compression with no new foldable messages has no request`() {
        assertNull(planCompaction(history(14), checkpoints = listOf(checkpoint())))
        assertNull(planCompaction(history(12), checkpoints = listOf(checkpoint())))
    }

    @Test fun `invalid latest checkpoint falls back to valid earlier checkpoint`() {
        val good = checkpoint()
        val invalid = listOf(checkpoint(to = "deleted"), checkpoint(from = "m10", to = "m8"), checkpoint(summary = " "))
        assertEquals(good, reusableCheckpoint(history(20), listOf(good) + invalid))
        assertNull(reusableCheckpoint(history(20), invalid))
    }

    @Test fun `summary validation rejects incomplete oversized and unhelpful output`() {
        assertFalse(checkedSummary("half", "length", 5000).commit)
        assertFalse(checkedSummary("half", "cut", 5000).commit)
        assertFalse(checkedSummary("half", "max_tokens", 5000).commit)
        assertFalse(checkedSummary(" ", "stop", 5000).commit)
        assertFalse(checkedSummary("x".repeat(32001), "stop", 100000).commit)
        assertFalse(checkedSummary("x".repeat(2500), "stop", 100).commit)
        assertFalse(checkedSummary("x".repeat(1000), "stop", 2000).commit)
        assertEquals("useful", checkedSummary(" useful ", "stop", 5000).text)
        assertTrue(checkedSummary("useful", "stop", 5000).commit)
        assertTrue(checkedSummary("short history summary", "stop", 10).commit)
    }

    @Test fun `rejected automatic compression waits for meaningful growth and manual bypasses`() {
        val gate = CompactionRetryGate()
        assertTrue(gate.allows(20, 100000, false))
        gate.rejected(20, 100000)
        assertFalse(gate.allows(20, 100000, false))
        assertFalse(gate.allows(22, 105000, false))
        assertTrue(gate.allows(24, 100000, false))
        assertTrue(gate.allows(21, 110000, false))
        assertTrue(gate.allows(20, 100000, true))
        gate.reset()
        assertTrue(gate.allows(2, 100, false))
    }
}

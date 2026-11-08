package com.suixing.agent.ui.chat

import com.suixing.agent.agent.ChildRunSummary
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.data.ChildRunConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentQueueTest {
    private fun task(id: String, run: String, status: SubagentStatus = SubagentStatus.RUNNING) =
        ChildRunSummary(id, "chat", run, id, status, "", ChildRunConfig(), 0, 0)

    @Test fun queueContainsOnlyTheCurrentRunAndDisappearsWhenItStops() {
        val old = task("old", "previous")
        val current = task("current", "active")
        assertEquals(listOf(current), subagentQueueFor(listOf(old, current), "active", true))
        assertTrue(subagentQueueFor(listOf(old, current), "active", false).isEmpty())
        assertTrue(subagentQueueFor(listOf(old, current), "", true).isEmpty())
    }

    @Test fun completedChildrenRemainAvailableUntilTheirParentRunEnds() {
        val done = task("done", "active", SubagentStatus.COMPLETED)
        val running = task("running", "active")
        assertEquals(listOf(done, running), subagentQueueFor(listOf(done, running), "active", true))
    }
}

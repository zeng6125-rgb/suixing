package com.suixing.agent.ui.chat

import com.suixing.agent.agent.ChildRunRecord
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTranscriptTest {
    private val input = StoredMessage("child_input", Roles.USER, "inspect the assigned file")
    private fun record(messages: List<StoredMessage> = listOf(input)) = ChildRunRecord(
        id = "child", parentChatId = "parent", parentRunId = "parent-run", name = "inspect",
        task = input.text, context = "", config = ChildRunConfig(), messages = messages,
        status = SubagentStatus.RUNNING,
    )
    private fun rows(value: ChildRunRecord) = subagentTranscript(value, subagentHistory(value.messages))
    private fun toolParts(rows: List<ChatRow>) = rows.flatMap { it.parts }.filterIsInstance<Part.Tools>().flatMap { it.calls }

    @Test fun liveRoundExtendsCommittedReplyAndSettlesWithoutDuplicatingText() {
        val first = StoredMessage("round1", Roles.ASSISTANT, "I will inspect it.", runId = "child-run")
        val tool = StoredMessage("result", Roles.TOOL, toolName = "read_file", toolArgs = "{}", runId = "child-run")
        val live = record(listOf(input, first, tool)).copy(liveReasoning = "Checking the result", liveText = "Found it")
        val drawing = rows(live)
        assertEquals(2, drawing.size)
        assertEquals(listOf("I will inspect it.", "Found it"), drawing.last().parts.filterIsInstance<Part.Text>().map { it.text })
        assertEquals(1, toolParts(drawing).size)
        val final = StoredMessage("round2", Roles.ASSISTANT, "Found it", reasoning = "Checking the result", runId = "child-run")
        val settled = rows(live.copy(messages = live.messages + final, liveText = "", liveReasoning = "",
            status = SubagentStatus.COMPLETED, result = "Found it"))
        assertEquals(drawing.last().parts.filterIsInstance<Part.Text>(), settled.last().parts.filterIsInstance<Part.Text>())
        assertEquals(2, settled.size)
        assertTrue(settled.all { it.branch == null })
    }

    @Test fun toolResultHandoverShowsOneToolAndAllowsANewIdenticalCall() {
        val tool = StoredMessage("result", Roles.TOOL, toolName = "read_file", toolArgs = "{}")
        val received = record(listOf(input, tool)).copy(currentTool = "read_file", currentToolArgs = "{}")
        assertEquals(1, toolParts(rows(received)).size)
        assertTrue(toolParts(rows(received)).single().done)
        val request = StoredMessage("round2", Roles.ASSISTANT,
            toolCalls = listOf(StoredToolCall("call2", "read_file", "{}")))
        val repeated = toolParts(rows(received.copy(messages = received.messages + request)))
        assertEquals(2, repeated.size)
        assertFalse(repeated.last().done)
    }

    @Test fun silentToolRoundHasTheSameConversationReplyBeforeAndAfterResult() {
        val pending = record().copy(currentTool = "read_file", currentToolArgs = "{}")
        val before = rows(pending)
        assertEquals(2, before.size)
        assertEquals(input, before.first().message)
        assertFalse(toolParts(before).single().done)
        val tool = StoredMessage("result", Roles.TOOL, toolName = "read_file", toolArgs = "{}")
        val after = rows(pending.copy(messages = listOf(input, tool), currentTool = "", currentToolArgs = ""))
        assertEquals(before.size, after.size)
        assertTrue(toolParts(after).single().done)
    }

    @Test fun switchingChildrenUsesOnlyThatChildsHistoryAndLiveText() {
        val a = record().copy(liveText = "child A response")
        val bInput = StoredMessage("b_input", Roles.USER, "different task")
        val b = record(listOf(bInput)).copy(id = "child-b", liveText = "child B response")
        assertEquals(listOf("child A response"), rows(a).last().parts.filterIsInstance<Part.Text>().map { it.text })
        assertEquals(bInput, rows(b).first().message)
        assertEquals(listOf("child B response"), rows(b).last().parts.filterIsInstance<Part.Text>().map { it.text })
    }

    @Test fun terminalFallbackRetainsResultAndCancelledPartialText() {
        val completed = record().copy(status = SubagentStatus.COMPLETED, result = "Saved result")
        assertEquals("Saved result", rows(completed).last().parts.filterIsInstance<Part.Text>().single().text)
        val cancelled = completed.copy(status = SubagentStatus.CANCELLED, liveText = "partial response", result = "Cancelled")
        assertEquals("partial response", rows(cancelled).last().parts.filterIsInstance<Part.Text>().single().text)
        val failed = record().copy(status = SubagentStatus.FAILED, result = "Connection failed")
        assertTrue(rows(failed).none { row -> row.parts.any { it is Part.Text } })
    }
}

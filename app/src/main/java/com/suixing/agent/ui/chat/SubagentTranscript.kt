package com.suixing.agent.ui.chat

import com.suixing.agent.agent.ChildRunRecord
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage

/** Group committed rounds once per message revision, independently of live token updates. */
internal fun subagentHistory(messages: List<StoredMessage>): List<ChatRow> =
    groupRows(messages, emptyMap(), running = true)

/** The live round extends the same reply that its stored rounds already occupy. */
internal fun subagentTranscript(record: ChildRunRecord, history: List<ChatRow>): List<ChatRow> {
    val active = record.status.isActive()
    val additions = buildList {
        if (record.liveReasoning.isNotBlank()) add(Part.Thinking(record.liveReasoning, 0, 0, record.id))
        if (record.liveText.isNotBlank()) add(Part.Text(record.liveText))
        // ToolMessage can publish just before ToolFinished clears the running tool.
        val last = record.messages.lastOrNull()
        val toolAlreadyStored = last?.role == Roles.TOOL && last.toolName == record.currentTool &&
            last.toolArgs.orEmpty() == record.currentToolArgs
        if (active && record.currentTool.isNotBlank() && !toolAlreadyStored) {
            add(Part.Tools(listOf(ToolLine(record.currentTool, record.currentToolArgs, false, true, 0))))
        }
        if (!active && record.status == SubagentStatus.COMPLETED &&
            record.result.isNotBlank() && history.none { row -> row.parts.any { it is Part.Text } } &&
            record.liveText.isBlank()) add(Part.Text(record.result))
    }
    val tail = history.lastOrNull()
    val reply = tail?.takeIf { it.message.role == Roles.ASSISTANT || it.message.role == Roles.TOOL }
    if (reply != null) {
        if (additions.isEmpty()) return history
        return history.dropLast(1) + reply.copy(parts = reply.parts + additions)
    }
    if (additions.isEmpty() && !active) return history
    return history + ChatRow(
        message = StoredMessage(id = record.id + "_reply", role = Roles.ASSISTANT, runId = record.id),
        showTimeLabel = false,
        parts = additions,
    )
}

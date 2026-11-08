package com.suixing.agent.ui.chat

import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TodoItem

/** Conversation metadata and configuration; independent of incoming token text. */
data class ChatSessionState(
    val conversationId: String = "",
    val title: String = "新会话",
    val loading: Boolean = true,
    val assistantId: String = "",
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val approvalMode: ApprovalMode? = null,
    val contextOverhead: Pair<Int, Int>? = null,
)

/** Stored window and its revision. Collection identity avoids walking screenshots in equals. */
data class ChatTranscriptState(
    val messages: List<StoredMessage> = emptyList(),
    val messagesRevision: Long = 0,
    val messageCount: Int = 0,
    val turnCount: Int = 0,
    val activeRuns: Map<String, String> = emptyMap(),
    val checkpoints: List<ContextCheckpoint> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = this === other || other is ChatTranscriptState &&
        messages === other.messages && messagesRevision == other.messagesRevision &&
        messageCount == other.messageCount && turnCount == other.turnCount &&
        activeRuns === other.activeRuns && checkpoints === other.checkpoints

    override fun hashCode(): Int {
        var result = System.identityHashCode(messages)
        result = 31 * result + messagesRevision.hashCode()
        result = 31 * result + messageCount
        result = 31 * result + turnCount
        result = 31 * result + System.identityHashCode(activeRuns)
        return 31 * result + System.identityHashCode(checkpoints)
    }
}

/** Current run controls and live content. Mutable text buffers are never marked stable. */
data class ChatRunState(
    val streaming: String = "",
    val reasoningRevision: Int = 0,
    val runningHere: Boolean = false,
    val compressingSince: Long = 0L,
    val status: String = "",
    val runningTool: String? = null,
    val liveParts: List<LivePart> = emptyList(),
    val liveRunId: String = "",
    val error: String? = null,
)

/** Unsent and queued input, plus the conversation's retained todo board. */
data class ChatInputState(
    val draft: Draft = Draft(),
    val pendingSteers: List<PendingSteer> = emptyList(),
    val pendingTurns: List<PendingTurn> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
)

package com.lingshu.agent.data

import kotlinx.serialization.Serializable

@Serializable
data class StoredMessage(
    val id: String,
    val role: String,
    val text: String = "",
    /** The model's thinking, when the endpoint streams it apart from the answer. */
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolOutput: String? = null,
    val toolOk: Boolean? = null,
    val toolCallId: String? = null,
    val imageBase64: String? = null,
    val toolCalls: List<StoredToolCall> = emptyList(),
    /**
     * Which candidate answer this message belongs to inside its turn. Everything
     * one engine run produced shares a value, so re-answering a question appends a
     * second run instead of overwriting the first. Null on user messages and on
     * transcripts written before branching existed.
     */
    val runId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/** The tool calls an assistant turn asked for; replayed back to the API verbatim. */
@Serializable
data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String = "{}",
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<StoredMessage> = emptyList(),
    /** The persona this conversation speaks as; empty falls back to the active one. */
    val assistantId: String = "",
    /**
     * Which run of each turn is on screen, keyed by the id of the user message that
     * opened the turn. Missing entries mean "the newest run", which is what a
     * freshly answered question should show.
     */
    val activeRuns: Map<String, String> = emptyMap(),
)

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

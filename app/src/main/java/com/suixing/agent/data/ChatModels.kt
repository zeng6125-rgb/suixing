package com.suixing.agent.data

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
    /**
     * What this message cost, as the endpoint reported it. Only assistant messages
     * carry it, and only when the endpoint sent a usage block at all.
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** How long the model took, wall clock, for the line under the bubble. */
    val elapsedMs: Long = 0,
    /** True once the user edited the text by hand, so the UI can say so. */
    val edited: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    /** True when the endpoint never told us what this message cost. */
    val usageKnown: Boolean get() = promptTokens > 0 || completionTokens > 0
}

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

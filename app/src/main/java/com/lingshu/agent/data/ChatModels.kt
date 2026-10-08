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
)

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

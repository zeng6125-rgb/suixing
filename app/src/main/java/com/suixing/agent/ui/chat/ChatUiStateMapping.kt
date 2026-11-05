package com.suixing.agent.ui.chat

import com.suixing.agent.data.Conversation
import com.suixing.agent.data.ApprovalMode

/**
 * `Conversation` → `ChatUiState` 的映射（issue #69 P2-C：从 `ChatViewModel` 物理迁出）。
 *
 * 纯数据映射，不碰协程与 Android；三个调用点都在 ViewModel 内（`1045` / `1341` / `1624`），
 * 所以把可见性由 `private` 提到 `internal` 不影响任何外部使用方，但它现在可以被单测直接调用。
 */

internal fun Conversation.toUiState(
    fallbackAssistantId: String,
    approvalOverride: ApprovalMode? = null,
): ChatUiState = ChatUiState(
    conversationId = id,
    title = title,
    messages = messages,
    // **总条数来自 meta**（#30）：`messages` 只是窗口（打开会话读最近 N 轮），
    // 调用方判断"屏幕上的历史是不是完整"就是比这两个数（见 send() 的 wire 分支）。
    messageCount = messageCount,
    turnCount = turnCount,
    activeRuns = activeRuns,
    assistantId = assistantId.ifBlank { fallbackAssistantId },
    providerId = providerId,
    model = model,
    reasoningEffort = reasoningEffort,
    approvalMode = approvalOverride ?: approvalMode,
    checkpoints = checkpoints,
    // A conversation read from disk is not "still opening": this state is what the skeleton
    // is replaced by, so it must not leave the flag set.
    loading = false,
)

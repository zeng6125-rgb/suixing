package com.suixing.agent.ui.chat

import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage

/** A run keeps writing to its owning conversation while the screen can show another one. */
internal data class RunSession(
    val chatId: String,
    val title: String,
    val assistantId: String,
    /** The conversation's own model pick; carried so a save cannot drop it. */
    val providerId: String? = null,
    val model: String? = null,
    /** The conversation's own thinking level; carried so a save cannot drop it. */
    val reasoningEffort: String? = null,
    val approvalMode: ApprovalMode? = null,
    /** The conversation's compression record; carried so a save cannot drop it. */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
    val runId: String,
    val messages: List<StoredMessage>,
    /** Pages loaded during this run; copied independently of in-flight stream snapshots. */
    val loadedPrefix: List<StoredMessage> = emptyList(),
    val activeRuns: Map<String, String>,
    val streaming: String = "",
    val reasoningRevision: Int = 0,
    val status: String = "",
    val runningTool: String? = null,
    /** When the still-open thinking block started, so its clock is its own. */
    val thinkingStartedAt: Long = 0L,
    /**
     * 当前「段」在 [RunSession.parts] 里的起点下标。段＝最后一个工具行/插话行/轮落盘之后的那一段
     * 流。同一段里思考与正文各自只持一个块（交错流不再把一句话劈开）；跨过切点才是新的一段。
     */
    val segmentStart: Int = 0,
    /** What the live bubble draws, in arrival order. */
    val parts: List<LivePart> = emptyList(),
    val error: String? = null,
)

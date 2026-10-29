package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.llm.FINISH_CUT

/**
 * 上下文压缩的**共用规则**：把会话历史切成「要被折叠的一段」与「原样保留的尾巴」。
 *
 * 为什么单独放这里：run 内的压缩（AgentEngine 的 RunHandle）与**没有 run 时的手动压缩**
 * 必须用同一套切法，否则同一个会话在两条路上会压出不同的边界，模型看到的历史也会不一致。
 * 这里只做纯计算（不碰网络、不落盘），所以可以直接测。
 */
internal data class CompactionPlan(
    /** 交给摘要模型的那段历史（每条按 `角色: 内容` 渲染）。 */
    val transcript: String,
    /** 被折叠的历史消息 id，按原顺序；用它写 ContextCheckpoint 的起止。 */
    val coveredIds: List<String>,
    /** 被折叠的消息条数（给用户看的「N 条消息 → 摘要」）。 */
    val foldedMessages: Int,
)

/**
 * 切一段可折叠的历史。
 *
 * 两条硬约束，与 run 内压缩一致：
 * - 末尾 [keepRecent] 条**永远保留原文**：那是模型正在干活的上下文，压掉它等于让它重新探路。
 * - 切点不能落在 `tool` 消息上：单独一条工具结果（没有配对的 tool_calls）在每个
 *   OpenAI 兼容端点上都是 400。
 *
 * @return null 表示没有可折叠的内容（历史太短）。
 */
internal fun planCompaction(
    history: List<StoredMessage>,
    keepRecent: Int = KEEP_RECENT_MESSAGES,
    minMessages: Int = MIN_MESSAGES_TO_COMPACT,
    checkpoints: List<ContextCheckpoint> = emptyList(),
): CompactionPlan? {
    if (history.size <= minMessages) return null
    var cut = history.size - keepRecent
    while (cut > 0 && history[cut].role == Roles.TOOL) cut--
    if (cut <= 0) return null
    val covered = history.subList(0, cut)
    if (covered.isEmpty()) return null
    val previous = reusableCheckpoint(history, checkpoints)
    val resumeAt = previous?.let { saved -> history.indexOfFirst { it.id == saved.toMessageId } } ?: -1
    // Nothing new can be folded without touching the recent tail. Reuse the saved result.
    if (resumeAt >= cut - 1) return null
    val fresh = covered.subList(resumeAt + 1, covered.size)
    val from = previous?.let { saved -> history.indexOfFirst { it.id == saved.fromMessageId } } ?: 0
    return CompactionPlan(
        transcript = buildString {
            if (previous != null) append("system: [上下文摘要] ").append(previous.summary).append('\n')
            append(fresh.joinToString("\n") { renderForCompaction(it) })
        },
        coveredIds = history.subList(from, cut).map { it.id },
        foldedMessages = cut - from,
    )
}

/** A stale checkpoint must not hide an earlier usable one after a branch/edit. */
internal fun reusableCheckpoint(history: List<StoredMessage>, checkpoints: List<ContextCheckpoint>): ContextCheckpoint? {
    if (checkpoints.isEmpty()) return null
    val endpoints = HashSet<String>()
    checkpoints.forEach { endpoints += it.fromMessageId; endpoints += it.toMessageId }
    val positions = HashMap<String, Int>(endpoints.size)
    history.forEachIndexed { index, message -> if (message.id in endpoints) positions[message.id] = index }
    return checkpoints.asReversed().firstOrNull {
        val from = positions[it.fromMessageId]
        val to = positions[it.toMessageId]
        it.summary.isNotBlank() && from != null && to != null && from <= to
    }
}

internal data class SummaryAttempt(val text: String, val commit: Boolean, val why: String = "")

internal fun checkedSummary(text: String, finishReason: String?, sourceChars: Int): SummaryAttempt {
    val summary = text.trim()
    val reason = when {
        summary.isBlank() -> "模型没有返回摘要"
        finishReason == FINISH_CUT || finishReason == "length" || finishReason == "max_tokens" ||
            finishReason == "content_filter" -> "摘要被截断或未完整返回"
        summary.length > 32_000 || (sourceChars < 2000 && summary.length > 2000) -> "摘要过长"
        sourceChars >= 2000 && summary.length.toLong() * 2 >= sourceChars -> "摘要没有比原文更小"
        else -> ""
    }
    return SummaryAttempt(summary, reason.isEmpty(), reason)
}

/** Failed summaries retain the original wire. Retry after useful growth, or an explicit request. */
internal class CompactionRetryGate {
    private var rejectedRows = -1
    private var rejectedChars = 0
    fun allows(rows: Int, chars: Int, manual: Boolean): Boolean = manual || rejectedRows < 0 ||
        rows >= rejectedRows + 4 || chars.toLong() >= rejectedChars.toLong() + maxOf(2048, rejectedChars / 10)
    fun rejected(rows: Int, chars: Int) { rejectedRows = rows; rejectedChars = chars }
    fun reset() { rejectedRows = -1; rejectedChars = 0 }
}

/**
 * 一条消息怎么进摘要请求。
 *
 * 工具输出只取前 [TOOL_OUTPUT_IN_SUMMARY] 字符：摘要不需要整份 dump，而几十条完整 dump 会让
 * 这一请求本身变得很大（pi 的 compaction 同样只取每条工具结果的前 2000 字符）。
 */
internal fun renderForCompaction(message: StoredMessage): String = buildString {
    append(message.role).append(": ")
    append(message.text)
    if (message.toolCalls.isNotEmpty()) {
        message.toolCalls.forEach { call ->
            append("\n  [调用工具 ").append(call.name).append(' ').append(call.arguments).append(']')
        }
    }
    val output = message.toolOutput.orEmpty()
    if (output.isNotEmpty()) {
        append("\n  [工具结果] ").append(output.take(TOOL_OUTPUT_IN_SUMMARY))
        if (output.length > TOOL_OUTPUT_IN_SUMMARY) append(" …（共 ${output.length} 字符）")
    }
    if (message.imageCount > 0) append("\n  （含截图，已省略）")
}

/** 压缩时保留的尾部条数：一轮的量。 */
internal const val KEEP_RECENT_MESSAGES = 6

/** 少于这么多条消息就没有值得压缩的东西。 */
internal const val MIN_MESSAGES_TO_COMPACT = 8

/** 摘要请求里每条工具结果的字符上限。 */
internal const val TOOL_OUTPUT_IN_SUMMARY = 2000

/**
 * 摘要提示词。run 内与手动压缩共用同一份 —— 两条路压出来的摘要必须读起来是同一个东西，
 * 否则用户看到的压缩块会像两次不同功能的产物。
 */
internal const val COMPRESS_PROMPT =
    "Compress the following agent execution history into a concise summary. " +
        "Keep: conclusions already reached, file paths and commands involved, decisions made and why, " +
        "and anything still unfinished. " +
        "Drop: pleasantries, repeated attempts, and intermediate steps that were later overturned. " +
        "Output the summary body only — no preamble, no headings."

package com.suixing.agent.agent

import com.suixing.agent.data.StoredMessage
import com.suixing.agent.plugins.PluginSpec
import com.suixing.agent.llm.Usage
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.tools.ToolSpec

/**
 * What a tool card says when the user stopped the run before the tool answered.
 *
 * It is a result, not a placeholder: the transcript has to answer the call it recorded, or the card
 * draws empty and the next run's replay drops the whole assistant turn for being unanswered.
 */
internal const val INTERRUPTED_TOOL_OUTPUT = "（已中断：任务被停止，这次调用没有结果）"

/**
 * Stamps what the round cost onto the message it produced. Endpoints that send no
 * usage block leave the counts at zero, which the UI reads as "unknown" rather than
 * "free".
 *
 * [firstTokenMs] is the wait before anything came back — the number a slow endpoint is
 * judged by, and the one the usage block never carries: it is measured here, between the
 * request going out and the first delta arriving.
 */
internal fun StoredMessage.withUsage(
    usage: Usage?,
    elapsedMs: Long,
    model: String,
    firstTokenMs: Long = 0L,
): StoredMessage {
    if (usage == null) {
        return copy(elapsedMs = elapsedMs, model = model, firstTokenMs = firstTokenMs)
    }
    return copy(
        promptTokens = usage.promptTokens,
        completionTokens = usage.completionTokens,
        reasoningTokens = usage.reasoningTokens,
        cachedTokens = usage.cachedTokens,
        elapsedMs = elapsedMs,
        model = model,
        firstTokenMs = firstTokenMs,
    )
}

/** 工具表拼装的结果对（Default 块里一次算完带回，避免在后台线程写引擎字段）。 */
internal class ToolTable(
    val plugins: List<PluginSpec>,
    val specs: List<ToolSpec>,
    val estimatedTokens: Int,
) {
    val hooks = PluginRuntime.hooksFor(plugins)
}

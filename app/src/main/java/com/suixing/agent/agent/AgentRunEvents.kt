package com.suixing.agent.agent

import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TodoItem

/** Everything the UI needs to render while the agent is working. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent

    /** The model's thinking, when the endpoint streams it apart from the answer. */
    data class ReasoningDelta(val text: String) : AgentEvent

    data class AssistantMessage(val message: StoredMessage) : AgentEvent

    data class ToolStarted(val name: String, val args: String) : AgentEvent

    data class ToolMessage(val message: StoredMessage) : AgentEvent

    data class ToolFinished(
        val name: String,
        val ok: Boolean,
        val output: String,
        val todoSnapshot: List<TodoItem>? = null,
    ) : AgentEvent

    data class Failed(val message: String) : AgentEvent

    data class Finished(val text: String) : AgentEvent

    /**
     * One line the user slipped in has just been written into the prompt, in this order.
     *
     * The UI needs this to know a queued line was actually read: until it lands the line is still
     * retractable and belongs nowhere in the transcript, and after it the bubble is history.
     */
    data class Steered(
        val id: String,
        val text: String,
        /**
         * The picture that came with the line, when there was one.
         *
         * It travels because the bubble the user sees is built from this event: without it the
         * transcript would show a steer that silently lost its attachment, while the model had
         * been sent it.
         */
        val imagesBase64: List<String> = emptyList(),
    ) : AgentEvent

    /**
     * An attempt failed and the same request is going out again in [delayMs].
     *
     * The run is still Running — it has not stopped, it is waiting — so the screen needs to say so:
     * a run that goes quiet for the length of a retry delay reads as one that ended, which is how
     * 「发出去，几秒之后就停止」 was reported while the requests were in fact being retried.
     */
    data class Retrying(
        /** Which attempt failed (1 = the first try). */
        val attempt: Int,
        /** How many retries the settings allow. */
        val maxAttempts: Int,
        val delayMs: Long,
        /** Short and user-facing: why the attempt is being repeated. */
        val reason: String,
        /** The HTTP status that failed; null for a transport failure. For the trace, not the UI. */
        val code: Int? = null,
    ) : AgentEvent

    /**
     * 这一轮作废重来：已经流到屏幕上的内容不算数了。
     *
     * 只在"收到内容之后流断了、还能重试"时发出（[LlmClient][com.suixing.agent.llm.LlmClient] 的
     * `onRestart`）。界面收到它就把本段 live parts 撤掉，否则重发的那一轮会把同一段开头再画一遍。
     */
    data object RoundRestarted : AgentEvent

    /**
     * 压缩摘要正在生成（true＝开始，false＝结束）。
     *
     * 没有它，压缩那几秒里状态行停在上一句（「正在调用 xxx」），看着像卡住 —— 用户要求
     * run 中的压缩也要在状态行里说「正在压缩上下文」。
     */
    data class Compressing(val on: Boolean) : AgentEvent
}

/**
 * How fast a run is going, in the only unit a streaming UI can show live.
 *
 * The token counts are estimates: the exact numbers only exist on the server and
 * arrive once, with the last chunk. Counting decoded text instead means the speed
 * readout moves while the model is still talking, which is the whole point of it.
 */
data class StreamStats(
    val tokensOut: Int,
    val thinkingTokens: Int,
    val elapsedMs: Long,
    val tokPerSec: Float,
    /**
     * The prompt size the endpoint itself reported for the last round; 0 until it
     * reports one. This is the only honest measure of 「上下文用了多少」 — counting
     * the wire's characters would be a guess, and this number is handed to us.
     */
    val lastPromptTokens: Int = 0,
    /**
     * **当前这一轮请求**的实际大小（端点报过的真实值 + 自上次上报以来的增量估算）。
     *
     * 与 [lastPromptTokens]（上一轮上报值）的区别：wire 每轮在长，而 lastPromptTokens 要等本轮
     * usage 回来才更新 —— 显示层拿它当「现在多大」会在轮内停在旧值、轮末跳一下。这个字段在
     * 每轮请求定型时（wire 最终、工具表拼完）刷新，轮内稳定、跨轮单调，显示不再一会大一会小
     * （用户报的「上下文窗口一会大一会小」）。首轮还没有任何端点读数时它是 chars 估算。
     */
    val currentPrompt: Int = 0,
    /**
     * Where the prompt goes, split three ways, for the breakdown bar in the UI.
     *
     * Estimated from characters, so the three do not have to add up to [lastPromptTokens]:
     * one is counted and the other is reported. They are still worth having, because the
     * question the bar answers — 「是历史太长，还是工具太多」 — only needs proportions.
     */
    val breakSystem: Int = 0,
    val breakTools: Int = 0,
    val breakHistory: Int = 0,
    /**
     * One line about the run that is not a statistic, e.g. that the context was just
     * compressed. It rides here rather than in a new event because this flow is
     * already the live channel both the chat screen and the floating console read.
     */
    val note: String = "",
)

/** What the engine is doing right now, for anything outside the chat screen. */
sealed interface RunState {
    data object Idle : RunState

    /** Emitted continuously while a run streams. */
    data class Running(val stats: StreamStats) : RunState

    data class Done(val stats: StreamStats) : RunState

    data class Cancelled(val stats: StreamStats) : RunState

    data class Failed(val message: String) : RunState
}

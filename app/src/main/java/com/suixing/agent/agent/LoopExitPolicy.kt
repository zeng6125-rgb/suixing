package com.suixing.agent.agent

import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.llm.LlmException
import kotlin.coroutines.cancellation.CancellationException

/** Explicit terminal reasons; tool rejection is a completed attempt, not an engine failure. */
internal sealed interface LoopExit {
    data class Done(val text: String) : LoopExit
    data object GoalStopped : LoopExit
    data object DirectToolRejected : LoopExit
    data class StepLimit(val rounds: Int) : LoopExit
    data object MissingGoalSummary : LoopExit
    data class Cancelled(
        val partial: CharSequence,
        val reasoning: CharSequence = "",
        val unanswered: List<StoredToolCall> = emptyList(),
    ) : LoopExit
    data class CallerCancelled(
        val cause: CancellationException,
        val partial: CharSequence,
        val reasoning: CharSequence,
        val unanswered: List<StoredToolCall>,
    ) : LoopExit
    data class Failed(val error: Throwable, val retries: Int) : LoopExit
}

/** Owns event/state ordering, while transcript preparation remains with its run handle. */
internal class LoopExitPolicy(
    private val onEvent: (AgentEvent) -> Unit,
    private val publish: (RunState) -> Unit,
    private val stats: () -> StreamStats,
    private val prepareCancelled: (LoopExit.Cancelled) -> Boolean,
    private val prepareCallerCancellation: (LoopExit.CallerCancelled) -> Unit,
) {
    fun finish(exit: LoopExit) {
        when (exit) {
            is LoopExit.Done -> complete(exit.text)
            LoopExit.GoalStopped, LoopExit.DirectToolRejected -> complete("")
            is LoopExit.StepLimit -> fail("已达到最大工具轮数（${exit.rounds}），任务未完成。")
            LoopExit.MissingGoalSummary -> fail("目标运行已停止，但模型没有返回总结正文。")
            is LoopExit.Cancelled -> if (prepareCancelled(exit)) {
                onEvent(AgentEvent.Finished(""))
                publish(RunState.Cancelled(stats()))
            }
            is LoopExit.CallerCancelled -> {
                prepareCallerCancellation(exit)
                publish(RunState.Cancelled(stats()))
                throw exit.cause
            }
            is LoopExit.Failed -> {
                val retryNote = if (exit.retries > 0) "（已重试 ${exit.retries} 次）" else ""
                val message = (exit.error.message ?: exit.error::class.java.simpleName) + retryNote
                publish(RunState.Failed(message))
                // Keep the original cause and the existing caller-owned error event path.
                throw if (exit.retries > 0) LlmException(message, exit.error) else exit.error
            }
        }
    }

    private fun complete(text: String) {
        onEvent(AgentEvent.Finished(text))
        publish(RunState.Done(stats()))
    }

    private fun fail(message: String) {
        onEvent(AgentEvent.Failed(message))
        publish(RunState.Failed(message))
    }
}

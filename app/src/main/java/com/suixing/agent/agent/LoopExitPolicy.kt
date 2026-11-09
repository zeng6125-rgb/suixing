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
        val reason = when (exit) {
            is LoopExit.Done -> NativeLoopPolicy.DONE
            LoopExit.GoalStopped -> NativeLoopPolicy.GOAL_STOPPED
            LoopExit.DirectToolRejected -> NativeLoopPolicy.DIRECT_TOOL_REJECTED
            is LoopExit.StepLimit -> NativeLoopPolicy.STEP_LIMIT
            LoopExit.MissingGoalSummary -> NativeLoopPolicy.MISSING_GOAL_SUMMARY
            is LoopExit.Cancelled -> NativeLoopPolicy.CANCELLED
            is LoopExit.CallerCancelled -> NativeLoopPolicy.CALLER_CANCELLED
            is LoopExit.Failed -> NativeLoopPolicy.FAILED
        }
        val retries = (exit as? LoopExit.Failed)?.retries ?: 0
        val plan = NativeLoopPolicy.plan(reason, retries)
        // The ordered slots retain all platform effects and Throwable identity in Kotlin.
        when (plan.prepare) {
            NativeLoopPolicy.PREPARE_CANCELLED -> if (!prepareCancelled(exit as LoopExit.Cancelled)) return
            NativeLoopPolicy.PREPARE_CALLER -> prepareCallerCancellation(exit as LoopExit.CallerCancelled)
        }
        val message = when (plan.message) {
            NativeLoopPolicy.MESSAGE_STEP_LIMIT -> "已达到最大工具轮数（${(exit as LoopExit.StepLimit).rounds}），任务未完成。"
            NativeLoopPolicy.MESSAGE_MISSING_SUMMARY -> "目标运行已停止，但模型没有返回总结正文。"
            NativeLoopPolicy.MESSAGE_ERROR -> (exit as LoopExit.Failed).let {
                it.error.message ?: it.error::class.java.simpleName
            }
            NativeLoopPolicy.MESSAGE_ERROR_WITH_RETRIES -> (exit as LoopExit.Failed).let {
                val retryNote = "（已重试 ${it.retries} 次）"
                (it.error.message ?: it.error::class.java.simpleName) + retryNote
            }
            else -> null
        }
        when (plan.event) {
            NativeLoopPolicy.EVENT_FINISHED_TEXT -> onEvent(AgentEvent.Finished((exit as LoopExit.Done).text))
            NativeLoopPolicy.EVENT_FINISHED_EMPTY -> onEvent(AgentEvent.Finished(""))
            NativeLoopPolicy.EVENT_FAILED -> onEvent(AgentEvent.Failed(checkNotNull(message)))
        }
        when (plan.state) {
            NativeLoopPolicy.STATE_DONE -> publish(RunState.Done(stats()))
            NativeLoopPolicy.STATE_CANCELLED -> publish(RunState.Cancelled(stats()))
            NativeLoopPolicy.STATE_FAILED -> publish(RunState.Failed(checkNotNull(message)))
        }
        when (plan.throwAction) {
            NativeLoopPolicy.THROW_CALLER -> throw (exit as LoopExit.CallerCancelled).cause
            // The caller owns the failure event path; preserve the Throwable rather than recreate it.
            NativeLoopPolicy.THROW_ERROR -> throw (exit as LoopExit.Failed).error
            NativeLoopPolicy.THROW_WRAPPED_ERROR -> throw LlmException(checkNotNull(message), (exit as LoopExit.Failed).error)
        }
    }
}

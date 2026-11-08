package com.suixing.agent.agent

import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.llm.LlmException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LoopExitPolicyTest {
    @Test fun aNormalAnswerEmitsFinishedBeforeItsDoneState() {
        val fixture = Fixture()
        fixture.policy.finish(LoopExit.Done("answer"))
        assertEquals(listOf("finished:answer", "done"), fixture.order)
        assertEquals(AgentEvent.Finished("answer"), fixture.events.single())
        assertEquals(RunState.Done(fixture.stats), fixture.states.single())
    }

    @Test fun goalStopAndRejectedDirectToolAreCompletedAttempts() {
        listOf(LoopExit.GoalStopped, LoopExit.DirectToolRejected).forEach { exit ->
            val fixture = Fixture()
            fixture.policy.finish(exit)
            assertEquals(listOf("finished:", "done"), fixture.order)
            assertEquals(AgentEvent.Finished(""), fixture.events.single())
            assertTrue(fixture.states.single() is RunState.Done)
        }
    }

    @Test fun stepLimitReportsTheConfiguredRoundCountWithoutCompleting() {
        val fixture = Fixture()
        fixture.policy.finish(LoopExit.StepLimit(4))
        val message = "已达到最大工具轮数（4），任务未完成。"
        assertEquals(listOf("failed-event:$message", "failed-state:$message"), fixture.order)
        assertEquals(AgentEvent.Failed(message), fixture.events.single())
        assertEquals(RunState.Failed(message), fixture.states.single())
    }

    @Test fun aMissingGoalSummaryCannotMasqueradeAsSuccess() {
        val fixture = Fixture()
        fixture.policy.finish(LoopExit.MissingGoalSummary)
        assertTrue(fixture.events.single() is AgentEvent.Failed)
        assertTrue(fixture.states.single() is RunState.Failed)
        assertEquals("目标运行已停止，但模型没有返回总结正文。", (fixture.events.single() as AgentEvent.Failed).message)
    }

    @Test fun aUserStopPreparesTheTranscriptAndPublishesOnlyOnce() {
        val fixture = Fixture()
        val open = listOf(StoredToolCall("call", "echo", "{}"))
        val exit = LoopExit.Cancelled("partial", "reasoning", open)
        fixture.policy.finish(exit)
        fixture.policy.finish(exit)
        assertEquals(listOf("partial:partial:reasoning:1", "finished:", "cancelled"), fixture.order)
        assertEquals(1, fixture.events.size)
        assertEquals(1, fixture.states.size)
    }

    @Test fun callerCancellationPreservesContentAndRethrowsTheSameCause() {
        val fixture = Fixture()
        val cause = CancellationException("caller scope ended")
        try {
            fixture.policy.finish(LoopExit.CallerCancelled(cause, "partial", "reasoning", emptyList()))
            fail("Caller cancellation must be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cause, actual)
        }
        assertEquals(listOf("caller-partial:partial:reasoning:0", "cancelled"), fixture.order)
        assertTrue(fixture.events.isEmpty())
        assertEquals(RunState.Cancelled(fixture.stats), fixture.states.single())
    }

    @Test fun aRetriedFailureKeepsItsOriginalCauseAndCallerOwnedErrorEvent() {
        val fixture = Fixture()
        val cause = IOException("connection unavailable")
        try {
            fixture.policy.finish(LoopExit.Failed(cause, 2))
            fail("Engine failures must be rethrown")
        } catch (actual: LlmException) {
            assertSame(cause, actual.cause)
            assertEquals("connection unavailable（已重试 2 次）", actual.message)
        }
        assertTrue(fixture.events.isEmpty())
        assertEquals(RunState.Failed("connection unavailable（已重试 2 次）"), fixture.states.single())
    }

    @Test fun aFailureWithoutRetriesIsNotWrapped() {
        val fixture = Fixture()
        val cause = IOException("failed")
        try {
            fixture.policy.finish(LoopExit.Failed(cause, 0))
            fail("Engine failures must be rethrown")
        } catch (actual: IOException) {
            assertSame(cause, actual)
        }
        assertTrue(fixture.events.isEmpty())
        assertEquals(RunState.Failed("failed"), fixture.states.single())
    }

    private class Fixture {
        val stats = StreamStats(10, 2, 100, 1f)
        val events = mutableListOf<AgentEvent>()
        val states = mutableListOf<RunState>()
        val order = mutableListOf<String>()
        private val cancellationPrepared = AtomicBoolean()
        val policy = LoopExitPolicy(
            onEvent = { event ->
                events += event
                order += when (event) {
                    is AgentEvent.Finished -> "finished:${event.text}"
                    is AgentEvent.Failed -> "failed-event:${event.message}"
                    else -> error("Not a terminal event: $event")
                }
            },
            publish = { state ->
                states += state
                order += when (state) {
                    is RunState.Done -> "done"
                    is RunState.Cancelled -> "cancelled"
                    is RunState.Failed -> "failed-state:${state.message}"
                    else -> error("Not a terminal state: $state")
                }
            },
            stats = { stats },
            prepareCancelled = { exit ->
                if (cancellationPrepared.compareAndSet(false, true)) {
                    order += "partial:${exit.partial}:${exit.reasoning}:${exit.unanswered.size}"
                    true
                } else false
            },
            prepareCallerCancellation = { exit ->
                order += "caller-partial:${exit.partial}:${exit.reasoning}:${exit.unanswered.size}"
            },
        )
    }
}

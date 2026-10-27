package com.suixing.agent.agent

import com.suixing.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionEventTest {
    private val stats = StreamStats(tokensOut = 1, thinkingTokens = 0, elapsedMs = 10, tokPerSec = 1f)

    @Test fun `idle running failed and cancelled states do not report a completed answer`() {
        val completed = mutableListOf<String>()
        val engine = AgentEngine(onCompleted = { completed += it })
        val handle = engine.RunHandle("a", "chat a", AppSettings())
        handle.publish(RunState.Idle)
        handle.publish(RunState.Running(stats))
        handle.publish(RunState.Failed("unavailable"))
        handle.publish(RunState.Cancelled(stats))
        assertEquals(emptyList<String>(), completed)
    }

    @Test fun `completion is reported once per handle and keeps parallel chat identities`() {
        val completed = mutableListOf<String>()
        val engine = AgentEngine(onCompleted = { completed += it })
        val first = engine.RunHandle("a", "chat a", AppSettings())
        val second = engine.RunHandle("b", "chat b", AppSettings())
        first.publish(RunState.Done(stats))
        first.publish(RunState.Done(stats.copy(tokensOut = 2)))
        second.publish(RunState.Done(stats))
        engine.RunHandle("a", "next run in a", AppSettings()).publish(RunState.Done(stats))
        assertEquals(listOf("a", "b", "a"), completed)
    }
}

package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChatStore
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.ui.chat.runElapsedById
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRunElapsedTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun waitingForAChildSurvivesCompletionAndTranscriptReloadInTheDisplayedDuration() = runBlocking {
        val parentCalls = AtomicInteger()
        lateinit var engine: AgentEngine
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) when (parentCalls.incrementAndGet()) {
                    1 -> AssistantTurn("", listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"child work"}""")), "tool_calls")
                    2 -> AssistantTurn("", listOf(ToolCallRequest("wait", "wait_agents", "{}")), "tool_calls")
                    else -> AssistantTurn("parent summary", emptyList(), "stop")
                } else {
                    // Advance the real run's monotonic clock while its child owns execution.
                    engine.runsFlow.value.getValue("parent").runtime.startedAt -= 60_000_000_000L
                    AssistantTurn("child result", emptyList(), "stop")
                }
            }
        }
        engine = AgentEngine(llm = client)
        val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
        val skills = mockk<SkillStore> {
            coEvery { listHeaders() } returns emptyList<Skill>()
            every { catalog(any()) } returns ""
        }
        val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
        val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
        val registry = ToolRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val produced = withTimeout(5_000) {
            engine.startRun("parent", "parent", listOf(StoredMessage("input", Roles.USER, "work")),
                AppSettings(subagentsEnabled = true, approvalMode = ApprovalMode.AUTO), registry,
                capabilities, skills, memory, profile, ApprovalGate(), {}, runId = "run")
        }
        val elapsed = (engine.stateOf("parent").value as RunState.Done).stats.elapsedMs
        assertTrue("the parent clock must include the child interval", elapsed >= 60_000L)
        assertTrue("request timings must remain independent of the child interval", produced.sumOf { it.elapsedMs } < elapsed)
        val messages = produced.map { it.copy(runId = "run") }
        val store = ChatStore(folder.root)
        val meta = store.append(ChatStore.ConversationMeta("parent", "parent", 1, 1), messages)
        val restored = ChatStore(folder.root).all(meta)
        assertEquals("the stored reply must retain the full duration, including waiting", elapsed, runElapsedById(restored)["run"])
        assertEquals(elapsed, meta.totals.wallElapsedMs)
        assertEquals(produced.sumOf { it.elapsedMs }, meta.totals.elapsedMs)
    }
}

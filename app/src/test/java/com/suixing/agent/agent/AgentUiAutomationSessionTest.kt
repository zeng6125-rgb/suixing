package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.service.UiOperationOwner
import com.suixing.agent.service.UiOperationStatus
import com.suixing.agent.service.floatingConsoleVisible
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.terminal.TerminalCaller
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AgentUiAutomationSessionTest {
    private val status = UiOperationStatus.shared
    private val owners = CopyOnWriteArrayList<UiOperationOwner>()
    private val capabilities = mockk<CapabilityHub> {
        every { state } returns MutableStateFlow(CapabilityState())
    }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> {
        every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>())
    }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
    private val settings = AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false)

    private fun registry(): ToolRegistry =
        ToolRegistry::class.java.getDeclaredConstructor().let {
            it.isAccessible = true
            it.newInstance()
        }.also { registry ->
            registry.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec("ui_tap", "fixture", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject): ToolResult {
                    val caller = requireNotNull(currentCoroutineContext()[TerminalCaller])
                    val owner = UiOperationOwner(caller.chatId, caller.runId)
                    owners += owner
                    val token = status.begin("正在点击", owner, caller.cancelRun)
                    status.end(token, completed = true, now = 100)
                    return ToolResult.success("已点击")
                }
            })
        }

    private fun client(afterTool: suspend () -> AssistantTurn): LlmClient {
        val rounds = AtomicInteger()
        return mockk {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) {
                    AssistantTurn("", listOf(ToolCallRequest("tap", "ui_tap", "{}")), "tool_calls")
                } else afterTool()
            }
        }
    }

    private suspend fun run(engine: AgentEngine, chatId: String = "main"): List<StoredMessage> =
        engine.startRun(chatId, chatId, emptyList(), settings, registry(),
            capabilities, skills, memory, profile, ApprovalGate(), {},
            runId = "transcript-$chatId")

    private fun assertConsoleHidden() {
        assertFalse(floatingConsoleVisible(foreground = false, resident = true,
            whileRunning = true, running = true, automationActive = status.hasSession))
    }

    @After fun clearOwnedSessions() { owners.forEach(status::finish) }

    @Test fun thinkingKeepsFeedbackUntilTheActualRunFinallyCompletes() = runBlocking<Unit> {
        val thinking = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = AgentEngine(llm = client {
            thinking.complete(Unit)
            finish.await()
            AssistantTurn("完成", emptyList(), "stop")
        })
        val running = async(Dispatchers.Default) { run(engine) }
        try {
            withTimeout(5_000) { thinking.await() }
            val owner = owners.single()
            assertNotEquals("transcript-main", owner.runId)
            assertEquals(owner, engine.runsFlow.value.getValue("main").terminalCaller.let {
                UiOperationOwner(it.chatId, it.runId)
            })
            assertEquals("正在思考", status.current(20_000))
            assertEquals(owner, status.snapshot(20_000)?.owner)
            assertConsoleHidden()

            finish.complete(Unit)
            assertEquals("完成", withTimeout(5_000) { running.await() }.last().text)
            assertFalse(status.hasSession)
            assertNull(status.snapshot(20_001))
            assertTrue(floatingConsoleVisible(false, true, true, false, status.hasSession))
        } finally {
            finish.complete(Unit)
            running.cancelAndJoin()
        }
    }

    @Test fun aModelFailureAfterUiWorkClearsItsFeedback() = runBlocking<Unit> {
        val engine = AgentEngine(llm = client {
            assertTrue(status.hasSession)
            error("模型请求失败")
        })

        val error = runCatching { run(engine) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("模型请求失败", error?.message)
        assertTrue(engine.stateOf("main").value is RunState.Failed)
        assertFalse(status.hasSession)
        assertNull(status.snapshot(20_000))
    }

    @Test fun thePromptStopCallbackCancelsTheActualRun() = runBlocking<Unit> {
        val thinking = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = AgentEngine(llm = client {
            thinking.complete(Unit)
            finish.await()
            AssistantTurn("完成", emptyList(), "stop")
        })
        val running = async(Dispatchers.Default) { run(engine) }
        try {
            withTimeout(5_000) { thinking.await() }
            val stop = requireNotNull(status.snapshot(20_000)?.cancelRun)

            assertTrue(stop())
            withTimeout(5_000) { running.await() }

            assertTrue(engine.stateOf("main").value is RunState.Cancelled)
            assertFalse(status.hasSession)
            assertNull(status.snapshot(20_001))
            assertFalse(stop())
        } finally {
            finish.complete(Unit)
            running.cancelAndJoin()
        }
    }

    @Test fun callerCancellationStillClearsTheSession() = runBlocking<Unit> {
        val thinking = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = AgentEngine(llm = client {
            thinking.complete(Unit)
            finish.await()
            AssistantTurn("完成", emptyList(), "stop")
        })
        val running = async(Dispatchers.Default) { run(engine) }
        try {
            withTimeout(5_000) { thinking.await() }
            assertTrue(status.hasSession)

            running.cancelAndJoin()

            assertTrue(running.isCancelled)
            assertFalse(status.hasSession)
            assertNull(status.snapshot(20_000))
        } finally {
            finish.complete(Unit)
            running.cancelAndJoin()
        }
    }

    @Test fun stoppingAnIndependentChildKeepsTheOtherUiSessionAlive() = runBlocking<Unit> {
        val mainThinking = CompletableDeferred<Unit>()
        val childThinking = CompletableDeferred<Unit>()
        val mainFinish = CompletableDeferred<Unit>()
        val childFinish = CompletableDeferred<Unit>()
        val main = AgentEngine(llm = client {
            mainThinking.complete(Unit)
            mainFinish.await()
            AssistantTurn("主任务完成", emptyList(), "stop")
        })
        val child = AgentEngine(llm = client {
            childThinking.complete(Unit)
            childFinish.await()
            AssistantTurn("子任务完成", emptyList(), "stop")
        }, childConfig = ChildRunConfig(allowedTools = listOf("ui_tap")))
        val mainRun = async(Dispatchers.Default) { run(main) }
        var childRun: kotlinx.coroutines.Deferred<List<StoredMessage>>? = null
        try {
            withTimeout(5_000) { mainThinking.await() }
            val activeChildRun = async(Dispatchers.Default) { run(child, "child") }
            childRun = activeChildRun
            withTimeout(5_000) { childThinking.await() }
            val mainOwner = owners.single { it.chatId == "main" }
            val childOwner = owners.single { it.chatId == "child" }
            assertEquals(setOf(mainOwner, childOwner), status.ownersFlow.value)
            assertEquals(childOwner, status.snapshot(20_000)?.owner)
            assertConsoleHidden()

            assertTrue(requireNotNull(status.snapshot(20_000)?.cancelRun).invoke())
            withTimeout(5_000) { activeChildRun.await() }

            assertTrue(child.stateOf("child").value is RunState.Cancelled)
            assertTrue(main.isRunning("main"))
            assertEquals(setOf(mainOwner), status.ownersFlow.value)
            assertEquals(mainOwner, status.snapshot(20_001)?.owner)
            assertConsoleHidden()
            mainFinish.complete(Unit)
            withTimeout(5_000) { mainRun.await() }
            assertFalse(status.hasSession)
        } finally {
            mainFinish.complete(Unit)
            childFinish.complete(Unit)
            childRun?.cancelAndJoin()
            mainRun.cancelAndJoin()
        }
    }
}

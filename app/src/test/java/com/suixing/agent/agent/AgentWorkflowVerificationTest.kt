package com.suixing.agent.agent

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.service.TouchFx
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.BashTools
import com.suixing.agent.tools.ShellTools
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real loop and approval boundary, rather than duplicating ledger/config helpers. */
class AgentWorkflowVerificationTest {
    private val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }

    private fun registry(toolName: String = "echo", execute: (JsonObject) -> ToolResult = { ToolResult.success("same result") }): ToolRegistry =
        ToolRegistry::class.java.getDeclaredConstructor().let { it.isAccessible = true; it.newInstance() }.also { registry ->
            registry.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec(toolName, "fixture", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject) = execute(args)
            })
        }

    private suspend fun run(engine: AgentEngine, registry: ToolRegistry, settings: AppSettings,
        events: (AgentEvent) -> Unit = {}, runId: String = java.util.UUID.randomUUID().toString()): List<StoredMessage> =
        engine.startRun("verification", "Verification", emptyList(), settings, registry,
            capabilities, skills, memory, profile, ApprovalGate(), events, runId = runId)

    private fun nativeBashRegistry(): ToolRegistry = registry().also {
        it.add(BashTools(ShellTools(mockk<Context>(relaxed = true), capabilities)))
    }

    @Test fun `failed tool outcome preserves independently verified file mutations`() = runBlocking {
        val activity = com.suixing.agent.data.FileActivity("/sdcard/partial.txt",
            com.suixing.agent.data.FileActivityKind.CREATED, addedLines = 1, removedLines = 0)
        val rounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(ToolCallRequest("partial", "echo", "{}")), "tool_calls")
                else AssistantTurn("one step failed after writing", emptyList(), "stop")
            }
        }
        val events = mutableListOf<AgentEvent>()
        val produced = run(AgentEngine(llm = client), registry(execute = {
            ToolResult(false, "later step failed", fileActivities = listOf(activity))
        }), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false), events = { events += it })
        val tool = produced.single { it.toolName == "echo" }
        assertEquals(false, tool.toolOk)
        assertEquals(listOf(activity), tool.fileActivities)
        assertEquals(listOf(activity), events.filterIsInstance<AgentEvent.ToolMessage>().single().message.fileActivities)
    }

    @Test fun `main run shell and bash report actual files even when a later command fails`() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("main-shell-files").toFile()
        mockkObject(Shell, TouchFx)
        try {
            val workspace = java.io.File(folder, "workspace").apply { mkdirs() }
            val context = mockk<Context>()
            every { context.getExternalFilesDir(null) } returns workspace
            every { capabilities.shellTier() } returns ShellTier.SHIZUKU
            every { Shell.bundledBash() } returns "/native/libbash.so"
            every { TouchFx.traceCall(any(), tool = any(), detail = any(), matched = any()) } just Runs
            val created = java.io.File(workspace, "from-script.txt")
            val edited = java.io.File(workspace, "edited.txt").apply { writeText("before\n") }
            coEvery { Shell.local("run arbitrary script", "auto", null) } answers {
                created.writeText("made by script\n")
                ShellResult(true, "", "", 0)
            }
            coEvery { Shell.local("edit then fail", "bash", null) } answers {
                edited.writeText("after\nextra\n")
                ShellResult(false, "", "later command failed", 1)
            }
            val tools = registry().also { it.add(ShellTools(context, capabilities)); it.add(BashTools(ShellTools(context, capabilities))) }
            val rounds = AtomicInteger()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    when (rounds.incrementAndGet()) {
                        1 -> AssistantTurn("", listOf(ToolCallRequest("shell", "run_shell", """{"command":"run arbitrary script","mode":"local"}""")), "tool_calls")
                        2 -> AssistantTurn("", listOf(ToolCallRequest("bash", "bash", """{"command":"edit then fail"}""")), "tool_calls")
                        else -> AssistantTurn("done", emptyList(), "stop")
                    }
                }
            }
            val messages = run(AgentEngine(llm = client), tools, AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false))
            assertEquals(listOf(created.canonicalPath, edited.canonicalPath), messages.flatMap { it.fileActivities }.map { it.path })
            assertEquals(false, messages.single { it.toolName == "bash" }.toolOk)
            assertEquals(com.suixing.agent.data.FileActivityKind.MODIFIED, messages.single { it.toolName == "bash" }.fileActivities.single().kind)
        } finally { unmockkObject(Shell, TouchFx); folder.deleteRecursively() }
    }

    @Test fun `stopping main bash retains a verified mutation in the cancellation transcript`() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("cancel-shell-files").toFile()
        val started = CompletableDeferred<Unit>()
        mockkObject(Shell, TouchFx)
        try {
            val context = mockk<Context>()
            every { context.getExternalFilesDir(null) } returns folder
            every { capabilities.shellTier() } returns ShellTier.LOCAL
            every { Shell.bundledBash() } returns "/native/libbash.so"
            every { TouchFx.traceCall(any(), tool = any(), detail = any(), matched = any()) } just Runs
            val target = java.io.File(folder, "written-before-stop.txt")
            coEvery { Shell.local("write and wait", "bash", null) } coAnswers {
                target.writeText("already written\n")
                started.complete(Unit)
                awaitCancellation()
            }
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    AssistantTurn("", listOf(ToolCallRequest("bash", "bash", """{"command":"write and wait"}""")), "tool_calls")
            }
            val events = CopyOnWriteArrayList<AgentEvent>()
            val engine = AgentEngine(llm = client)
            val running = async(Dispatchers.Default) { run(engine, registry().also { it.add(BashTools(ShellTools(context, capabilities))) },
                AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, toolParallel = 1), events = { events += it }) }
            withTimeout(5000) { started.await() }
            assertTrue(engine.cancel("verification"))
            withTimeout(5000) { running.await() }
            val activity = events.filterIsInstance<AgentEvent.ToolMessage>().flatMap { it.message.fileActivities }.single()
            assertEquals(target.canonicalPath, activity.path)
            assertEquals(com.suixing.agent.data.FileActivityKind.CREATED, activity.kind)
        } finally { unmockkObject(Shell, TouchFx); folder.deleteRecursively() }
    }

    @Test fun `parent and child execute native bash without Shizuku and return child evidence to the parent`() = runBlocking {
        mockkObject(Shell, TouchFx)
        val release = CompletableDeferred<Unit>()
        try {
            every { capabilities.shellTier() } returns ShellTier.LOCAL
            every { Shell.bundledBash() } returns "/native/libbash.so"
            every { TouchFx.traceCall(any(), tool = any(), detail = any(), matched = any()) } just Runs
            coEvery { Shell.local("printf parent", "bash", null) } returns ShellResult(true, "native-parent-output", "", 0)
            coEvery { Shell.local("printf child", "bash", null) } returns ShellResult(true, "native-child-output", "", 0)
            val parentRounds = AtomicInteger()
            val childRounds = AtomicInteger()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    assertTrue(thirdArg<List<ToolSpec>>().any { it.name == "bash" })
                    if (firstArg<AppSettings>().subagentsEnabled) {
                        when (parentRounds.incrementAndGet()) {
                            1 -> AssistantTurn("", listOf(ToolCallRequest("parent-bash", "bash", """{"command":"printf parent"}""")), "tool_calls")
                            2 -> {
                                assertTrue(secondArg<List<ChatMessage>>().last { it.role == "tool" }.text.contains("native-parent-output"))
                                AssistantTurn("", listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"run the child Bash command"}""")), "tool_calls")
                            }
                            3 -> {
                                release.complete(Unit)
                                AssistantTurn("premature answer", emptyList(), "stop")
                            }
                            else -> {
                                val feedback = secondArg<List<ChatMessage>>().last { it.role == "tool" }.text
                                assertTrue(feedback.contains("native-child-output"))
                                AssistantTurn("parent integrated native Bash evidence", emptyList(), "stop")
                            }
                        }
                    } else if (childRounds.incrementAndGet() == 1) {
                        AssistantTurn("", listOf(ToolCallRequest("child-bash", "bash", """{"command":"printf child"}""")), "tool_calls")
                    } else {
                        release.await()
                        assertTrue(secondArg<List<ChatMessage>>().last { it.role == "tool" }.text.contains("native-child-output"))
                        AssistantTurn("child done: native-child-output", emptyList(), "stop")
                    }
                }
            }
            val result = withTimeout(10_000) {
                run(AgentEngine(llm = client), nativeBashRegistry(),
                    AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true))
            }
            assertEquals("parent integrated native Bash evidence", result.last().text)
            assertEquals(4, parentRounds.get())
            assertEquals(2, childRounds.get())
            coVerify(exactly = 1) { Shell.local("printf parent", "bash", null) }
            coVerify(exactly = 1) { Shell.local("printf child", "bash", null) }
            coVerify(exactly = 0) { Shell.root(any()) }
            coVerify(exactly = 0) { Shell.shizuku(any()) }
        } finally {
            release.complete(Unit)
            unmockkObject(Shell, TouchFx)
        }
    }

    @Test fun `native bash still obeys the engine approval gate before starting a process`() = runBlocking {
        mockkObject(Shell)
        try {
            val rounds = AtomicInteger()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    if (rounds.incrementAndGet() == 1) {
                        AssistantTurn("", listOf(ToolCallRequest("bash", "bash", """{"command":"printf denied"}""")), "tool_calls")
                    } else {
                        assertTrue(secondArg<List<ChatMessage>>().last { it.role == "tool" }.text.contains("用户拒绝"))
                        AssistantTurn("Bash was not executed", emptyList(), "stop")
                    }
                }
            }
            val result = withTimeout(10_000) {
                run(AgentEngine(llm = client), nativeBashRegistry(),
                    AppSettings(approvalMode = ApprovalMode.DENY, subagentsEnabled = false))
            }
            assertEquals("Bash was not executed", result.last().text)
            coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
        } finally {
            unmockkObject(Shell)
        }
    }

    @Test fun `a repeated parallel tool batch is fully answered before warning and the loop still finishes`() = runBlocking {
        val rounds = AtomicInteger()
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<AgentEvent>()
        var prefix = emptyList<ChatMessage>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val wire = secondArg<List<ChatMessage>>()
                if (rounds.incrementAndGet() == 1) {
                    prefix = wire.toList()
                    AssistantTurn("", (1..3).map { ToolCallRequest("call-$it", "echo", "{}") }, "tool_calls")
                } else {
                    assertEquals(prefix, wire.take(prefix.size))
                    assertEquals(listOf("tool", "tool", "tool", "user"), wire.takeLast(4).map { it.role })
                    assertTrue(wire.last().text.contains("重复调用提醒"))
                    assertTrue(wire.last().text.contains("3 次"))
                    AssistantTurn("finished after warning", emptyList(), "stop")
                }
            }
        }
        val result = withTimeout(10_000) {
            run(AgentEngine(llm = client), registry { calls.incrementAndGet(); ToolResult.success("same result") },
                AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, maxToolRounds = 30, toolParallel = 3), events::add)
        }
        assertEquals(3, calls.get())
        assertEquals(2, rounds.get())
        assertEquals("finished after warning", result.last().text)
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test fun `tool round exhaustion keeps committed results and reports one failed terminal state`() = runBlocking {
        val rounds = AtomicInteger()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                rounds.incrementAndGet()
                AssistantTurn("working", listOf(ToolCallRequest("call", "echo", "{}")), "tool_calls")
            }
        }
        val engine = AgentEngine(llm = client)
        val result = run(engine, registry(),
            AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, maxToolRounds = 1), events::add)
        assertEquals(1, rounds.get())
        assertEquals(listOf("assistant", "tool"), result.map { it.role })
        assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
        assertTrue(events.none { it is AgentEvent.Finished })
        assertEquals(RunState.Failed("已达到最大工具轮数（1），任务未完成。"), engine.stateOf("verification").value)
    }

    @Test fun `stopping after a committed tool emits one visible summary and retains the partial text`() = runBlocking {
        val rounds = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(ToolCallRequest("call", "echo", "{}")), "tool_calls")
                else {
                    arg<(String) -> Unit>(3)("partial answer")
                    waiting.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val running = async(Dispatchers.Default) {
            run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false), events::add)
        }
        withTimeout(5_000) { waiting.await() }
        assertTrue(engine.cancel("verification"))
        withTimeout(5_000) { running.await() }
        val summaries = events.filterIsInstance<AgentEvent.AssistantMessage>().filter { it.message.traceNote?.contains("本次尝试") == true }
        assertEquals(1, summaries.size)
        assertEquals("partial answer", summaries.single().message.text)
        assertTrue(summaries.single().message.traceNote.orEmpty().contains("1 次工具调用"))
        assertTrue(summaries.single().message.traceNote.orEmpty().contains("echo"))
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
    }

    @Test fun `a child denies forbidden actions and unknown tools before executing side effects`() = runBlocking {
        val actions = mutableListOf<String>()
        val rounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                assertEquals(listOf("ui_virtual_screen"), thirdArg<List<ToolSpec>>().map { it.name })
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(
                    ToolCallRequest("allowed", "ui_virtual_screen", """{"action":"status"}"""),
                    ToolCallRequest("blocked", "ui_virtual_screen", """{"action":"close"}"""),
                    ToolCallRequest("hidden", "write_file", """{"path":"should-not-exist"}"""),
                ), "tool_calls") else {
                    val results = secondArg<List<ChatMessage>>().filter { it.role == "tool" }
                    assertEquals(3, results.size)
                    assertTrue(results[1].text.contains("不允许"))
                    assertTrue(results[2].text.contains("不允许"))
                    AssistantTurn("done", emptyList(), "stop")
                }
            }
        }
        val child = AgentEngine(llm = client, childConfig = ChildRunConfig(
            allowedTools = listOf("ui_virtual_screen"), allowedActions = mapOf("ui_virtual_screen" to listOf("status"))))
        withTimeout(10_000) {
            run(child, registry("ui_virtual_screen") { args ->
                actions += (args["action"] as JsonPrimitive).content
                ToolResult.success("status response")
            }, AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false))
        }
        assertEquals(listOf("status"), actions)
    }

    @Test fun `normal parent completion collects child output and asks the parent to integrate it without an explicit wait tool`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parentRounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    if (parentRounds.incrementAndGet() == 1) AssistantTurn("",
                        listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"independent"}""")), "tool_calls")
                    else if (parentRounds.get() == 2) AssistantTurn("parent answer before child", emptyList(), "stop")
                    else {
                        val wire = secondArg<List<ChatMessage>>()
                        assertTrue("预算耗尽后的汇总不能继续提供工具", thirdArg<List<ToolSpec>>().isEmpty())
                        val feedback = wire.last { it.role == "tool" }
                        assertTrue(feedback.text.contains("child answer"))
                        assertTrue(feedback.text.contains("completed"))
                        assertEquals("wait_agents", wire.last { it.toolCalls.isNotEmpty() }.toolCalls.single().name)
                        AssistantTurn("parent integrated child answer", emptyList(), "stop")
                    }
                } else {
                    childStarted.complete(Unit)
                    release.await()
                    AssistantTurn("child answer", emptyList(), "stop")
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val running = async(Dispatchers.Default) {
            run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true, maxToolRounds = 2),
                runId = "bound-parent-run")
        }
        try {
            withTimeout(5_000) { childStarted.await() }
            val childId = engine.subagentsFlow.value.getValue("verification").single().id
            assertEquals("bound-parent-run", SubagentRecords.record(childId).value?.parentRunId)
            withTimeout(5_000) {
                engine.runsFlow.value.getValue("verification").state.first {
                    it is RunState.Running && it.stats.note == "等待子任务完成"
                }
            }
            assertFalse(running.isCompleted)
            assertFalse(engine.canCreateSubagent("verification"))
            release.complete(Unit)
            val result = withTimeout(5_000) { running.await() }
            assertEquals("parent integrated child answer", result.last().text)
            assertEquals(3, parentRounds.get())
            assertTrue(result.any { it.toolName == "wait_agents" && it.toolOutput.orEmpty().contains("child answer") })
        } finally { release.complete(Unit); running.cancel() }
    }

    @Test fun `completed child reports at a tool boundary once while the parent continues working`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parentRounds = AtomicInteger()
        lateinit var engine: AgentEngine
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    when (parentRounds.incrementAndGet()) {
                        1 -> AssistantTurn("", listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"independent"}""")), "tool_calls")
                        2 -> {
                            childStarted.await()
                            release.complete(Unit)
                            withTimeout(5_000) {
                                engine.subagentsFlow.first { children -> children["verification"]?.singleOrNull()?.status == SubagentStatus.COMPLETED }
                            }
                            AssistantTurn("", listOf(ToolCallRequest("work", "echo", "{}")), "tool_calls")
                        }
                        3 -> {
                            val wire = secondArg<List<ChatMessage>>()
                            assertTrue(wire.last { it.role == "tool" }.text.contains("independent finding"))
                            assertEquals(listOf("tool", "assistant", "tool"), wire.takeLast(3).map { it.role })
                            AssistantTurn("", listOf(ToolCallRequest("more-work", "echo", "{}")), "tool_calls")
                        }
                        else -> {
                            val wire = secondArg<List<ChatMessage>>()
                            assertEquals(1, wire.count { it.role == "tool" && it.text.contains("independent finding") })
                            AssistantTurn("integrated after more work", emptyList(), "stop")
                        }
                    }
                } else {
                    childStarted.complete(Unit)
                    release.await()
                    AssistantTurn("independent finding", emptyList(), "stop")
                }
            }
        }
        engine = AgentEngine(llm = client)
        try {
            val result = withTimeout(10_000) {
                run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true))
            }
            assertEquals("integrated after more work", result.last().text)
            assertEquals(4, parentRounds.get())
        } finally { release.complete(Unit) }
    }

    @Test fun `failed child is returned to the parent before its final summary`() = runBlocking {
        val parentRounds = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    when (parentRounds.incrementAndGet()) {
                        1 -> AssistantTurn("", listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"unavailable resource"}""")), "tool_calls")
                        2 -> {
                            release.complete(Unit)
                            AssistantTurn("premature answer", emptyList(), "stop")
                        }
                        else -> {
                            val feedback = secondArg<List<ChatMessage>>().last { it.role == "tool" }.text
                            assertTrue(feedback.contains("failed"))
                            assertTrue(feedback.contains("child resource unavailable"))
                            AssistantTurn("parent reports the child failure", emptyList(), "stop")
                        }
                    }
                } else {
                    release.await()
                    error("child resource unavailable")
                }
            }
        }
        try {
            val result = withTimeout(10_000) {
                run(AgentEngine(llm = client), registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true))
            }
            assertEquals("parent reports the child failure", result.last().text)
            assertEquals(3, parentRounds.get())
        } finally { release.complete(Unit) }
    }

    @Test fun `a cancelled child is reported to the parent without cancelling its own run`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val parentRounds = AtomicInteger()
        lateinit var engine: AgentEngine
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    when (parentRounds.incrementAndGet()) {
                        1 -> AssistantTurn("", listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"cancel this child"}""")), "tool_calls")
                        2 -> {
                            started.await()
                            val child = engine.subagentsFlow.value.getValue("verification").single()
                            assertTrue(engine.cancelSubagent("verification", child.id))
                            AssistantTurn("premature answer", emptyList(), "stop")
                        }
                        else -> {
                            val feedback = secondArg<List<ChatMessage>>().last { it.role == "tool" }.text
                            assertTrue(feedback.contains("cancelled"))
                            AssistantTurn("parent explains the child cancellation", emptyList(), "stop")
                        }
                    }
                } else {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        engine = AgentEngine(llm = client)
        val result = withTimeout(10_000) {
            run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true))
        }
        assertEquals("parent explains the child cancellation", result.last().text)
        assertEquals(3, parentRounds.get())
    }

    @Test fun `spawning on the last tool round still collects the child for a tool-free final summary`() = runBlocking {
        val parentRounds = AtomicInteger()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    if (parentRounds.incrementAndGet() == 1) AssistantTurn("",
                        listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"last round child"}""")), "tool_calls")
                    else {
                        assertTrue(thirdArg<List<ToolSpec>>().isEmpty())
                        assertTrue(secondArg<List<ChatMessage>>().last { it.role == "tool" }.text.contains("last round finding"))
                        AssistantTurn("final summary with child evidence", emptyList(), "stop")
                    }
                } else AssistantTurn("last round finding", emptyList(), "stop")
            }
        }
        val result = withTimeout(10_000) {
            run(AgentEngine(llm = client), registry(),
                AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true, maxToolRounds = 1), events::add)
        }
        assertEquals(2, parentRounds.get())
        assertEquals("final summary with child evidence", result.last().text)
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
        assertTrue(events.none { it is AgentEvent.Failed })
    }
}

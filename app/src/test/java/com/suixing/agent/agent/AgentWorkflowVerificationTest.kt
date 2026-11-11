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
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
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
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
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
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Exercise the real loop and approval boundary, rather than duplicating ledger/config helpers. */
class AgentWorkflowVerificationTest {
    @get:Rule val folder = TemporaryFolder()
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

    private fun assertEncodedToolPairs(settings: AppSettings, request: List<ChatMessage>): JsonObject {
        val method = LlmClient::class.java.getDeclaredMethod("requestBody",
            AppSettings::class.java, List::class.java, List::class.java, String::class.java).apply { isAccessible = true }
        val body = method.invoke(LlmClient(), settings, request, emptyList<ToolSpec>(), settings.protocol) as JsonObject
        val pending = ArrayDeque<String>()
        val announced = mutableListOf<String>()
        for (item in body["messages"] as JsonArray) {
            val message = item as JsonObject
            val role = (message["role"] as JsonPrimitive).content
            if (role == Roles.TOOL) {
                assertFalse("工具结果必须有当前调用声明", pending.isEmpty())
                assertEquals(pending.removeFirst(), (message["tool_call_id"] as JsonPrimitive).content)
            } else {
                assertTrue("工具结果齐全前不能插入 $role 消息；仍待 ${pending.toList()}", pending.isEmpty())
                if (role == Roles.ASSISTANT) {
                    val calls = message["tool_calls"] as? JsonArray
                    calls?.forEach {
                        val id = ((it as JsonObject)["id"] as JsonPrimitive).content
                        pending.addLast(id)
                        announced += id
                    }
                }
            }
        }
        assertTrue("全部调用都必须有结果", pending.isEmpty())
        assertEquals("完整工具组不能丢失", request.flatMap { it.toolCalls }.map { it.id }, announced)
        return body
    }

    @Test fun `tool screenshots follow the complete result group in live and stored replay requests`(): Unit = runBlocking {
        val requests = mutableListOf<List<ChatMessage>>()
        val calls = listOf(ToolCallRequest("image-call", "echo", "{\"image\":true}"),
            ToolCallRequest("text-call", "echo", "{}"))
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                requests += secondArg<List<ChatMessage>>().toList()
                if (requests.size == 1) AssistantTurn("", calls, "tool_calls")
                else AssistantTurn("完成", emptyList(), "stop")
            }
        }
        val engine = AgentEngine(llm = client)
        val tools = registry(execute = { args ->
            if (args["image"] == JsonPrimitive(true)) ToolResult.image("截图", "aW1hZ2U=")
            else ToolResult.success("文本结果")
        })
        val settings = AppSettings(imageInput = true, approvalMode = ApprovalMode.AUTO, subagentsEnabled = false)
        val question = StoredMessage("question", Roles.USER, "读取页面截图和状态")
        val produced = engine.startRun("pairs", "Pairs", listOf(question), settings, tools,
            capabilities, skills, memory, profile, ApprovalGate(), {})
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val repository = ChatRepository(context)
        assertEquals(ChatRepository.SaveResult.Written, repository.save(Conversation(
            id = "pairs", title = "Pairs", messages = listOf(question) + produced,
        )))
        val saved = repository.readWire("pairs")
        engine.startRun("pairs", "Pairs", saved.messages + StoredMessage("next", Roles.USER, "继续"),
            settings, tools, capabilities, skills, memory, profile, ApprovalGate(), {}, resumeCheckpoint = saved.checkpoint)

        assertEquals(3, requests.size)
        for (request in requests.drop(1)) {
            val body = assertEncodedToolPairs(settings, request)
            assertTrue("编码后的截图内容不能丢失", body.toString().contains("aW1hZ2U="))
        }
    }

    @Test fun `cancellation summaries follow unanswered tool results in the next saved request`(): Unit = runBlocking {
        val requests = mutableListOf<List<ChatMessage>>()
        val calls = (1..3).map { ToolCallRequest("call-$it", "echo", "{}") }
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                requests += secondArg<List<ChatMessage>>().toList()
                if (requests.size == 1) AssistantTurn("", calls, "tool_calls")
                else AssistantTurn("完成", emptyList(), "stop")
            }
        }
        val engine = AgentEngine(llm = client)
        val tools = registry()
        val settings = AppSettings(toolParallel = 1, approvalMode = ApprovalMode.AUTO, subagentsEnabled = false)
        val transcript = mutableListOf(StoredMessage("question", Roles.USER, "依次读取三项状态"))
        var completed = 0
        engine.startRun("cancel-pairs", "Pairs", transcript.toList(), settings, tools,
            capabilities, skills, memory, profile, ApprovalGate(), { event ->
                when (event) {
                    is AgentEvent.AssistantMessage -> transcript += event.message
                    is AgentEvent.ToolMessage -> transcript += event.message
                    is AgentEvent.ToolFinished -> if (++completed == 2) engine.cancel("cancel-pairs")
                    else -> Unit
                }
            })
        assertEquals(2, completed)
        assertEquals(3, transcript.count { it.role == Roles.TOOL })
        assertTrue("停止后的执行总结已落入真实事件转录", transcript.any {
            it.role == Roles.ASSISTANT && it.toolCalls.isEmpty() && it.text.isNotBlank()
        })
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val repository = ChatRepository(context)
        assertEquals(ChatRepository.SaveResult.Written, repository.save(Conversation(
            id = "cancel-pairs", title = "Pairs", messages = transcript,
        )))
        val saved = repository.readWire("cancel-pairs")
        engine.startRun("cancel-pairs", "Pairs", saved.messages + StoredMessage("next", Roles.USER, "继续"),
            settings, tools, capabilities, skills, memory, profile, ApprovalGate(), {}, resumeCheckpoint = saved.checkpoint)

        assertEquals(2, requests.size)
        assertEncodedToolPairs(settings, requests.last())
    }

    @Test fun `compression cannot split a result group at a tool screenshot`(): Unit = runBlocking {
        val requests = mutableListOf<List<ChatMessage>>()
        val calls = (1..7).map { ToolCallRequest("call-$it", "echo", if (it == 1) "{\"image\":true}" else "{}") }
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                requests += secondArg<List<ChatMessage>>().toList()
                if (requests.size == 1) AssistantTurn("", calls, "tool_calls")
                else AssistantTurn("有效摘要", emptyList(), "stop")
            }
        }
        val engine = AgentEngine(llm = client)
        val tools = registry(execute = { args ->
            if (args["image"] == JsonPrimitive(true)) ToolResult.image("截图", "aW1hZ2U=")
            else ToolResult.success("文本结果")
        })
        val settings = AppSettings(imageInput = true, toolParallel = 1,
            approvalMode = ApprovalMode.AUTO, subagentsEnabled = false)
        var completed = 0
        val checkpoints = mutableListOf<ContextCheckpoint>()
        engine.startRun("compression-pairs", "Pairs", listOf(StoredMessage("question", Roles.USER, "读取七项状态")),
            settings, tools, capabilities, skills, memory, profile, ApprovalGate(), { event ->
                if (event is AgentEvent.ToolFinished && ++completed == calls.size)
                    assertTrue(engine.requestCompress("compression-pairs"))
            }, onCheckpoint = { checkpoints += it })

        assertEquals(3, requests.size)
        assertEquals(1, checkpoints.size)
        assertEquals("近期结果组的调用声明必须一起保留", calls.map { it.id }, requests.last().flatMap { it.toolCalls }.map { it.id })
        val body = assertEncodedToolPairs(settings, requests.last())
        assertTrue("编码后的截图内容不能丢失", body.toString().contains("aW1hZ2U="))
    }

    @Test fun `tool execution and ledger commit reuse one parsed arguments object`() = runBlocking {
        val raw = """ {"command":"echo exact","nested":{"path":"a"}} """
        var executedArgs: JsonObject? = null
        var recordedArgs: JsonObject? = null
        val rounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(ToolCallRequest("call", "echo", raw)), "tool_calls")
                else {
                    assertEquals("executed", secondArg<List<ChatMessage>>().last { it.role == "tool" }.text)
                    AssistantTurn("done", emptyList(), "stop")
                }
            }
        }
        mockkStatic(::parseToolArguments)
        mockkConstructor(RunLedger::class)
        try {
            every { parseToolArguments(raw) } answers { callOriginal() }
            every { anyConstructed<RunLedger>().record(any(), any(), any(), any()) } answers {
                recordedArgs = thirdArg()
                callOriginal()
            }
            val result = run(AgentEngine(llm = client), registry(execute = {
                executedArgs = it
                ToolResult.success("executed")
            }), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false))
            assertEquals("echo exact", (executedArgs?.get("command") as JsonPrimitive).content)
            assertSame(executedArgs, recordedArgs)
            assertEquals(raw, result.single { it.toolName == "echo" }.toolArgs)
            verify(exactly = 1) { parseToolArguments(raw) }
            verify(exactly = 1) { anyConstructed<RunLedger>().record(1, "echo", any(), any()) }
        } finally {
            unmockkConstructor(RunLedger::class)
            unmockkStatic(::parseToolArguments)
        }
    }

    @Test fun `malformed tool arguments fail before execution and commit without parsing again`() = runBlocking {
        val raw = "{broken"
        var recordedArgs: JsonObject? = null
        val executed = AtomicInteger()
        val rounds = AtomicInteger()
        val events = mutableListOf<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(ToolCallRequest("call", "echo", raw)), "tool_calls")
                else {
                    assertTrue(secondArg<List<ChatMessage>>().last { it.role == "tool" }.text.contains("合法 JSON 对象"))
                    AssistantTurn("done", emptyList(), "stop")
                }
            }
        }
        mockkStatic(::parseToolArguments)
        mockkConstructor(RunLedger::class)
        try {
            every { parseToolArguments(raw) } answers { callOriginal() }
            every { anyConstructed<RunLedger>().record(any(), any(), any(), any()) } answers {
                recordedArgs = thirdArg()
                callOriginal()
            }
            val result = run(AgentEngine(llm = client), registry(execute = {
                executed.incrementAndGet()
                ToolResult.success("should not execute")
            }), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false), events::add)
            assertEquals(0, executed.get())
            assertFalse(result.single { it.toolName == "echo" }.toolOk!!)
            assertTrue(events.none { it is AgentEvent.ToolStarted })
            assertEquals(JsonObject(mapOf("_invalid_arguments" to JsonPrimitive(raw))), recordedArgs)
            verify(exactly = 1) { parseToolArguments(raw) }
            verify(exactly = 1) { anyConstructed<RunLedger>().record(1, "echo", any(), any()) }
        } finally {
            unmockkConstructor(RunLedger::class)
            unmockkStatic(::parseToolArguments)
        }
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
            coEvery { Shell.shizuku("edit then fail", "bash") } answers {
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

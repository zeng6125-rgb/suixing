package com.suixing.agent.ui.chat

import android.os.SystemClock
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.Roles
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.llm.LlmClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingToolPreparationTest {
    @Test fun chatCompletionsPublishesTheToolBeforeArgumentsFinish() = assertPreparation(
        protocol = PROTOCOL_OPENAI,
        reasoning = """{"choices":[{"delta":{"reasoning_content":"thinking before tool"}}]}""",
        tool = """{"choices":[{"delta":{"tool_calls":[{"index":3,"id":"call_write","function":{"name":"write_file","arguments":"{\"content\":\""}}]}}]}""",
        arguments = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":3,"id":"","function":{"name":"","arguments":"abc"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":3,"function":{"arguments":"def\"}"}}]}}]}""",
        ),
    )

    @Test fun anthropicPublishesTheToolBeforeArgumentsFinish() = assertPreparation(
        protocol = PROTOCOL_ANTHROPIC,
        reasoning = """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thinking before tool"}}""",
        tool = """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_write","name":"write_file","input":{}}}""",
        arguments = listOf(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"content\":\"abc"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"def\"}"}}""",
        ),
    )

    @Test fun responsesPublishesTheToolBeforeArgumentsFinish() = assertPreparation(
        protocol = PROTOCOL_RESPONSES,
        reasoning = """{"type":"response.reasoning_text.delta","delta":"thinking before tool"}""",
        tool = """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_write","call_id":"call_write","name":"write_file","arguments":""}}""",
        arguments = listOf(
            """{"type":"response.function_call_arguments.delta","item_id":"fc_write","delta":"{\"content\":\"abc"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_write","delta":"def\"}"}""",
        ),
    )

    @Test fun sameNamedSlotsKeepTheirOwnStartsAndResults() = withFixture { fixture ->
        val stream = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(stream, """{"choices":[{"delta":{"tool_calls":[{"index":5,"id":"call_b","function":{"name":"read_file","arguments":"{\"path\":\"b\"}"}},{"index":1,"id":"call_a","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]}}]}""")
        assertEquals(listOf(5, 1), fixture.calls().map { it.streamIndex })
        assertTrue(fixture.calls().all { it.preparing })
        fixture.commit(stream)
        assertEquals(listOf("call_b", "call_a"), fixture.calls().map { it.callId })

        fixture.runtime.handleEvent("chat", AgentEvent.ToolStarted("read_file", "{\"path\":\"a\"}", "call_a", 1))
        assertEquals("starting the second announced slot must reuse its row", 2, fixture.calls().size)
        assertFalse(fixture.calls().single { it.streamIndex == 1 }.preparing)
        assertTrue(fixture.calls().single { it.streamIndex == 5 }.preparing)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolStarted("read_file", "{\"path\":\"b\"}", "call_b", 5))

        val resultB = StoredMessage("result_b", Roles.TOOL, toolName = "read_file", toolArgs = "{\"path\":\"b\"}",
            toolCallId = "call_b", toolOutput = "B failed", toolOk = false)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(resultB, 5))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("read_file", false, "B failed", callId = "call_b", streamIndex = 5))
        val callB = fixture.calls().single { it.streamIndex == 5 }
        assertTrue(callB.done)
        assertFalse(callB.preparing)
        assertFalse(callB.ok)
        assertEquals(resultB.id, callB.message?.id)
        assertEquals("{\"path\":\"b\"}", callB.args)
        assertFalse("finishing one name-matched call must not close the other", fixture.calls().single { it.streamIndex == 1 }.done)

        val resultA = StoredMessage("result_a", Roles.TOOL, toolName = "read_file", toolArgs = "{\"path\":\"a\"}",
            toolCallId = "call_a", toolOutput = "A", toolOk = true)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(resultA, 1))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("read_file", true, "A", callId = "call_a", streamIndex = 1))
        assertEquals(2, fixture.calls().size)
        assertEquals(resultA.id, fixture.calls().single { it.streamIndex == 1 }.message?.id)
        assertTrue(fixture.calls().all { it.done && !it.preparing })
    }

    @Test fun validationRejectionEndsThePreparedRowWithoutAStart() = withFixture { fixture ->
        val stream = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(stream, """{"choices":[{"delta":{"tool_calls":[{"index":2,"id":"invalid_call","function":{"name":"write_file","arguments":"{"}}]}}]}""")
        fixture.commit(stream)
        val rejection = StoredMessage("invalid_result", Roles.TOOL, toolName = "write_file", toolArgs = "{",
            toolCallId = "invalid_call", toolOutput = "invalid arguments", toolOk = false)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(rejection, 2))
        val call = fixture.calls().single()
        assertTrue("a result must end preparation even when execution never started", call.done)
        assertFalse(call.preparing)
        assertFalse(call.ok)
        assertEquals(0L, call.startedAt)
        assertEquals(0L, call.elapsedMs)
        assertEquals("{", call.args)
        assertEquals(rejection.id, call.message?.id)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("write_file", false, "invalid arguments", callId = "invalid_call", streamIndex = 2))
        assertEquals("rejection must not append an execution row", 1, fixture.calls().size)
        assertFalse(fixture.calls().single().preparing)
    }

    @Test fun retryOfAToolOnlyRoundKeepsCompletedHistoryAndDropsItsPreparedCall() = withFixture { fixture ->
        val first = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(first, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"completed_call","function":{"name":"read_file","arguments":"{}"}}]}}]}""")
        fixture.commit(first, "first_assistant")
        fixture.runtime.handleEvent("chat", AgentEvent.ToolStarted("read_file", "{}", "completed_call", 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage("completed_result", Roles.TOOL,
            toolName = "read_file", toolArgs = "{}", toolCallId = "completed_call", toolOutput = "first result"), 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("read_file", true, "first result", callId = "completed_call", streamIndex = 0))
        val completedParts = fixture.runtime.currentLiveContent().parts

        val abandoned = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(abandoned, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"abandoned_call","function":{"name":"write_file","arguments":"{\"content\":\"unfinished"}}]}}]}""")
        assertEquals(2, fixture.calls().size)
        assertTrue(fixture.calls().last().preparing)
        fixture.runtime.handleEvent("chat", AgentEvent.RoundRestarted)
        assertEquals("a retry must restore the entire completed prefix", completedParts, fixture.runtime.currentLiveContent().parts)
        assertSame(completedParts.first(), fixture.runtime.currentLiveContent().parts.first())
        assertEquals("completed_call", fixture.calls().single().callId)
        assertTrue(fixture.calls().single().done)

        val retry = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(retry, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"retry_call","function":{"name":"read_file","arguments":"{}"}}]}}]}""")
        fixture.commit(retry, "retry_assistant")
        assertEquals(listOf("completed_call", "retry_call"), fixture.calls().map { it.callId })
        assertEquals(1, fixture.calls().count { it.preparing })
    }

    @Test fun adjacentToolOnlyRoundsShareTheirDisplayedGroupAndKeepItsOwnership() = withFixture { fixture ->
        val first = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(first, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"first_call","function":{"name":"read_file","arguments":"{}"}}]}}]}""")
        fixture.commit(first, "first_assistant")
        fixture.runtime.handleEvent("chat", AgentEvent.ToolStarted("read_file", "{}", "first_call", 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage("first_result", Roles.TOOL,
            toolName = "read_file", toolArgs = "{}", toolCallId = "first_call", toolOutput = "first result"), 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("read_file", true, "first result", callId = "first_call", streamIndex = 0))
        val projection = LivePartProjection("run")
        val firstDisplay = projection.project(fixture.runtime.currentLiveContent().parts, showReasoning = true)
        val firstCall = firstDisplay.filterIsInstance<Part.Tools>().single().calls.single()
        val firstSlot = processFoldSlots(firstDisplay, "run").single()!!
        val expansion = ReasoningExpansion("chat", mutableMapOf())
        expansion.set(firstSlot, true)
        expansion.set("$firstSlot:call:0", true)

        val second = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(second, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"second_call","function":{"name":"write_file","arguments":"{\"content\":\""}}]}}]}""")
        val raw = fixture.runtime.currentLiveContent().parts
        assertEquals("raw round boundaries remain separate for retry rollback", 2, raw.filterIsInstance<LivePart.Tools>().size)
        val preparedDisplay = projection.project(raw, showReasoning = true)
        assertEquals("adjacent tool rounds must appear in one displayed group", 1, preparedDisplay.filterIsInstance<Part.Tools>().size)
        val displayedCalls = preparedDisplay.filterIsInstance<Part.Tools>().single().calls
        assertEquals(listOf("read_file", "write_file"), displayedCalls.map { it.name })
        assertSame("the original row keeps its projected call", firstCall, displayedCalls.first())
        val mergedSlot = processFoldSlots(preparedDisplay, "run").single()!!
        assertEquals(firstSlot, mergedSlot)
        assertEquals(true, expansion.stored(mergedSlot))
        assertEquals(true, expansion.stored("$mergedSlot:call:0"))

        fixture.accept(second, """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"done\"}"}}]}}]}""")
        fixture.commit(second, "second_assistant")
        fixture.runtime.handleEvent("chat", AgentEvent.ToolStarted("write_file", "{\"content\":\"done\"}", "second_call", 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage("second_result", Roles.TOOL,
            toolName = "write_file", toolArgs = "{\"content\":\"done\"}", toolCallId = "second_call", toolOutput = "second result"), 0))
        fixture.runtime.handleEvent("chat", AgentEvent.ToolFinished("write_file", true, "second result", callId = "second_call", streamIndex = 0))
        val completedDisplay = projection.project(fixture.runtime.currentLiveContent().parts, showReasoning = true)
        val completedTools = completedDisplay.filterIsInstance<Part.Tools>().single()
        fixture.runtime.handleEvent("chat", AgentEvent.TextDelta("after tools"))
        fixture.runtime.flushDeltas("chat")
        val withProse = projection.project(fixture.runtime.currentLiveContent().parts, showReasoning = true)
        assertSame("new prose must reuse the merged tools object", completedTools, withProse.filterIsInstance<Part.Tools>().single())
        fixture.runtime.handleEvent("chat", AgentEvent.TextDelta(" continues"))
        fixture.runtime.flushDeltas("chat")
        val moreProse = projection.project(fixture.runtime.currentLiveContent().parts, showReasoning = true)
        assertEquals("after tools continues", moreProse.filterIsInstance<Part.Text>().single().text)
        assertSame("later prose updates must reuse the same tools object", completedTools, moreProse.filterIsInstance<Part.Tools>().single())
    }

    @Test fun roundRestartClearsOnlyTheToolPreparationStatus() = withFixture { fixture ->
        fixture.runtime.handleEvent("chat", AgentEvent.Compressing(true))
        fixture.runtime.compressStartedAt = System.currentTimeMillis() - 1200L
        fixture.runtime.handleEvent("chat", AgentEvent.Compressing(false))
        val completedCompression = fixture.runtime.state.value.status
        assertTrue(completedCompression.startsWith("已压缩上下文，用时 "))
        fixture.runtime.handleEvent("chat", AgentEvent.RoundRestarted)
        assertEquals("a round restart must preserve completed compression feedback", completedCompression,
            fixture.runtime.state.value.status)

        val stream = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(stream, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"prepared_call","function":{"name":"write_file","arguments":"{\"content\":\"unfinished"}}]}}]}""")
        assertTrue(fixture.runtime.state.value.status.startsWith("正在准备调用 "))
        assertTrue(fixture.calls().single().preparing)
        fixture.runtime.handleEvent("chat", AgentEvent.RoundRestarted)
        assertEquals("the abandoned tool's preparation status must clear", "", fixture.runtime.state.value.status)
        assertTrue("the abandoned tool must leave the live parts", fixture.calls().isEmpty())
    }

    @Test fun cancelledCompleteCallsResolveByBoundIdWithoutStartedOrFinished() = withFixture { fixture ->
        val stream = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(stream, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"cancel_a","function":{"name":"read_file","arguments":"{}"}},{"index":1,"id":"cancel_b","function":{"name":"read_file","arguments":"{}"}}]}}]}""")
        fixture.commit(stream)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage("cancel_result_b", Roles.TOOL,
            toolName = "read_file", toolArgs = "{}", toolCallId = "cancel_b", toolOutput = "cancelled", toolOk = false)))
        assertTrue(fixture.calls().single { it.callId == "cancel_b" }.done)
        assertFalse(fixture.calls().single { it.callId == "cancel_b" }.preparing)
        assertTrue("the other same-named call must retain its preparation", fixture.calls().single { it.callId == "cancel_a" }.preparing)
        fixture.runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage("cancel_result_a", Roles.TOOL,
            toolName = "read_file", toolArgs = "{}", toolCallId = "cancel_a", toolOutput = "cancelled", toolOk = false)))
        assertTrue(fixture.calls().all { it.done && !it.preparing && !it.ok && it.elapsedMs == 0L })
    }

    @Test fun cancellingBeforeArgumentsFinishClearsThePreparedLiveState() = withFixture { fixture ->
        val stream = fixture.stream(PROTOCOL_OPENAI)
        fixture.accept(stream, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"unfinished_call","function":{"name":"write_file","arguments":"{\"content\":\"unfinished"}}]}}]}""")
        assertTrue(fixture.calls().single().preparing)
        assertEquals(0L, fixture.calls().single().startedAt)
        fixture.runtime.endSession("chat", "任务已中断")
        assertTrue(fixture.runtime.currentLiveContent().parts.isEmpty())
        assertEquals("", fixture.runtime.currentLiveContent().runId)
        assertNull(fixture.runtime.state.value.runningTool)
        assertFalse(fixture.runtime.sessions.containsKey("chat"))
    }

    private fun assertPreparation(protocol: String, reasoning: String, tool: String, arguments: List<String>) = withFixture { fixture ->
        val stream = fixture.stream(protocol)
        fixture.accept(stream, reasoning)
        val before = fixture.runtime.currentLiveContent().parts.single() as LivePart.Thinking
        val publicationsBeforeTool = fixture.publications.size

        fixture.accept(stream, tool)
        assertNull("the model's streamed turn has not finished", stream.finishReason)
        val prepared = fixture.runtime.currentLiveContent().parts
        assertEquals("a known tool name must be published before its arguments finish", 1,
            prepared.filterIsInstance<LivePart.Tools>().size)
        val call = prepared.filterIsInstance<LivePart.Tools>().single().calls.single()
        assertEquals("write_file", call.name)
        assertTrue(call.preparing)
        assertEquals(0L, call.startedAt)
        assertEquals("preparation must not copy the growing arguments", "", call.args)
        assertNotSame("announcing the tool must seal the current thinking block", before, prepared.first())
        assertEquals("thinking before tool", (prepared.first() as LivePart.Thinking).text)
        assertSame("the runtime publishes the same parts to its state store", prepared, fixture.runtime.state.value.liveParts)
        assertSame(prepared, fixture.publications.last().parts)
        assertEquals(publicationsBeforeTool + 1, fixture.publications.size)

        val preparedPublications = fixture.publications.size
        for (frame in arguments) {
            fixture.accept(stream, frame)
            assertSame("argument chunks stay buffered until execution", prepared, fixture.runtime.currentLiveContent().parts)
            assertEquals("argument chunks must not republish the tool", preparedPublications, fixture.publications.size)
        }
        assertEquals("{\"content\":\"abcdef\"}", stream.toTurn().toolCalls.single().arguments)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 1000L
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
            unmockkAll()
        }
    }

    private class Fixture {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val chats = mockk<ChatRepository>(relaxed = true) {
            every { conversations } returns MutableStateFlow(emptyList<Conversation>())
        }
        private val settings = mockk<SettingsRepository>(relaxed = true) {
            every { flow } returns MutableStateFlow(AppSettings())
        }
        private val goals = mockk<GoalStore>(relaxed = true) {
            every { changes } returns MutableStateFlow(null)
        }
        val runtime = ChatRuntime(scope, mockk<ChatRuntimeEnvironment>(relaxed = true) {
            every { this@mockk.chats } returns this@Fixture.chats
            every { this@mockk.settings } returns this@Fixture.settings
            every { this@mockk.goals } returns this@Fixture.goals
            every { appScope } returns scope
        })
        val publications = mutableListOf<LiveContent>()

        init {
            runtime._state.value = ChatUiState(conversationId = "chat", loading = false)
            runtime.putSession(RunSession(chatId = "chat", title = "Chat", assistantId = "", runId = "run",
                messages = emptyList(), activeRuns = emptyMap()))
            scope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.liveContent.collect { publications += it } }
        }

        fun stream(protocol: String) = LlmClient.StreamTurn(protocol) { index, name ->
            runtime.handleEvent("chat", AgentEvent.ToolPrepared(index, name))
        }

        fun accept(stream: LlmClient.StreamTurn, frame: String) = stream.accept(
            Json.parseToJsonElement(frame).jsonObject,
            { runtime.handleEvent("chat", AgentEvent.TextDelta(it)) },
            { runtime.handleEvent("chat", AgentEvent.ReasoningDelta(it)) },
        )

        fun commit(stream: LlmClient.StreamTurn, messageId: String = "assistant") {
            val requests = stream.toTurn().toolCalls
            runtime.handleEvent("chat", AgentEvent.AssistantMessage(StoredMessage(messageId, Roles.ASSISTANT,
                toolCalls = requests.map { StoredToolCall(it.id, it.name, it.arguments) }), requests.map { it.streamIndex }))
        }

        fun calls() = runtime.currentLiveContent().parts.filterIsInstance<LivePart.Tools>().flatMap { it.calls }

        fun close() { scope.cancel() }
    }
}

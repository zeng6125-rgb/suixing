package com.lingshu.agent.agent

import com.lingshu.agent.capability.CapabilityHub
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.data.ApprovalMode
import com.lingshu.agent.data.Roles
import com.lingshu.agent.data.StoredMessage
import com.lingshu.agent.data.StoredToolCall
import com.lingshu.agent.llm.ChatMessage
import com.lingshu.agent.llm.LlmClient
import com.lingshu.agent.llm.ToolCallRequest
import com.lingshu.agent.tools.TIER_ACCESSIBILITY
import com.lingshu.agent.tools.TIER_ROOT
import com.lingshu.agent.tools.TIER_SHELL
import com.lingshu.agent.tools.ToolRegistry
import com.lingshu.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Everything the UI needs to render while the agent is working. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent

    data class AssistantMessage(val message: StoredMessage) : AgentEvent

    data class ToolStarted(val name: String, val args: String) : AgentEvent

    data class ToolMessage(val message: StoredMessage) : AgentEvent

    data class ToolFinished(val name: String, val ok: Boolean, val output: String) : AgentEvent

    data class Failed(val message: String) : AgentEvent

    data class Finished(val text: String) : AgentEvent
}

/**
 * Holds a tool call until the user answers. In ASK mode the agent coroutine
 * parks on a [CompletableDeferred], so the run resumes the moment the user taps
 * approve or reject in the UI.
 */
class ApprovalGate {
    data class Request(val id: String, val toolName: String, val args: String)

    private data class Decision(val approved: Boolean, val always: Boolean)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var waiter: CompletableDeferred<Decision>? = null
    private val alwaysAllowed = mutableSetOf<String>()

    suspend fun request(toolName: String, args: String, mode: ApprovalMode): Boolean {
        when (mode) {
            ApprovalMode.AUTO -> return true
            ApprovalMode.DENY -> return false
            ApprovalMode.ASK -> Unit
        }
        if (alwaysAllowed.contains(toolName)) return true
        val deferred = CompletableDeferred<Decision>()
        waiter = deferred
        _pending.value = Request(UUID.randomUUID().toString(), toolName, args)
        val decision = deferred.await()
        _pending.value = null
        waiter = null
        if (decision.always) alwaysAllowed.add(toolName)
        return decision.approved
    }

    fun resolve(approved: Boolean, always: Boolean = false) {
        waiter?.complete(Decision(approved, always))
    }

    /** Used when the user stops the run while a prompt is on screen. */
    fun cancelPending() {
        waiter?.complete(Decision(false, false))
    }

    fun forgetAlways() {
        alwaysAllowed.clear()
    }
}

/**
 * The agent loop: send the conversation to the model, execute whatever tools it
 * asks for, feed the results back, and repeat until it stops calling tools.
 */
class AgentEngine(private val llm: LlmClient = LlmClient()) {

    suspend fun run(
        history: List<StoredMessage>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
    ): List<StoredMessage> {
        val produced = mutableListOf<StoredMessage>()
        val wire = mutableListOf<ChatMessage>()
        wire += ChatMessage.system(systemPrompt(settings, registry))
        history.forEach { appendToWire(wire, it) }

        val maxRounds = settings.maxToolRounds.coerceIn(1, MAX_ROUNDS)
        var round = 0
        while (round < maxRounds) {
            round++
            val turn = llm.complete(settings, wire, registry.all().map { it.spec }) { piece ->
                onEvent(AgentEvent.TextDelta(piece))
            }
            if (turn.toolCalls.isEmpty()) {
                val assistant = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = turn.text,
                )
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                onEvent(AgentEvent.Finished(turn.text))
                return produced
            }
            val assistant = StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.ASSISTANT,
                text = turn.text,
                toolCalls = turn.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) },
            )
            produced += assistant
            onEvent(AgentEvent.AssistantMessage(assistant))
            wire += ChatMessage.assistant(turn.text, turn.toolCalls)

            for (call in turn.toolCalls) {
                val argsPreview = call.arguments.take(600)
                val tool = registry.find(call.name)
                val result = if (tool == null) {
                    ToolResult.failure("未知工具：${call.name}")
                } else {
                    val blocked = blockedReason(tool.tier, capabilities)
                    if (blocked != null) {
                        ToolResult.failure(blocked)
                    } else {
                        onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
                        if (!gate.request(call.name, argsPreview, settings.approvalMode)) {
                            ToolResult.failure("用户拒绝了这次调用。")
                        } else {
                            runCatching { tool.run(parseArgs(call.arguments)) }.getOrElse { error ->
                                ToolResult.failure("工具执行异常：${error.message ?: error::class.java.simpleName}")
                            }
                        }
                    }
                }
                val output = result.output.take(MAX_TOOL_OUTPUT)
                val stored = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.TOOL,
                    text = "",
                    toolName = call.name,
                    toolArgs = call.arguments,
                    toolOutput = output,
                    toolOk = result.ok,
                    toolCallId = call.id,
                    imageBase64 = result.imageBase64,
                )
                produced += stored
                onEvent(AgentEvent.ToolMessage(stored))
                wire += ChatMessage.tool(call.id, output)
                result.imageBase64?.let { base64 ->
                    wire += ChatMessage.user("（这是上一步 $call.name 返回的截图）", base64)
                }
                onEvent(AgentEvent.ToolFinished(call.name, result.ok, output))
            }
        }
        onEvent(AgentEvent.Failed("已达到最大工具轮数（$maxRounds），任务未完成。"))
        return produced
    }

    private fun appendToWire(wire: MutableList<ChatMessage>, message: StoredMessage) {
        when (message.role) {
            Roles.USER -> wire += ChatMessage.user(message.text, message.imageBase64)
            Roles.SYSTEM -> wire += ChatMessage.system(message.text)
            Roles.ASSISTANT -> wire += ChatMessage.assistant(
                message.text,
                message.toolCalls.map { ToolCallRequest(it.id, it.name, it.arguments) },
            )
            Roles.TOOL -> {
                wire += ChatMessage.tool(
                    message.toolCallId ?: message.toolName.orEmpty(),
                    message.toolOutput.orEmpty(),
                )
                message.imageBase64?.let { wire += ChatMessage.user("（上一步工具的截图）", it) }
            }
        }
    }

    private fun blockedReason(tier: String, capabilities: CapabilityHub): String? {
        val state = capabilities.state.value
        return when (tier) {
            TIER_ACCESSIBILITY ->
                if (state.accessibility) null else "无障碍服务未开启：请到「能力」页开启灵枢的无障碍服务后重试。"
            TIER_SHELL ->
                if (state.shellReady || state.rootReady) null else "缺少 Shizuku（或 root）权限：请到「能力」页授权后重试。"
            TIER_ROOT ->
                if (state.rootReady) null else "缺少 root 权限，当前无法执行该工具。"
            else -> null
        }
    }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun systemPrompt(settings: AppSettings, registry: ToolRegistry): String = buildString {
        append(settings.systemPrompt.trim())
        append("\n\n## 可用工具\n")
        append(registry.promptSummary())
        append(
            "\n\n调用工具时，参数必须是合法 JSON 对象。一次可以并行调用多个工具。" +
                "工具返回失败时先分析原因，不要重复同样的调用。" +
                "任务完成后，用简洁的中文向用户说明做了什么、结果如何。",
        )
    }

    companion object {
        private const val MAX_ROUNDS = 60
        private const val MAX_TOOL_OUTPUT = 8_000
    }
}

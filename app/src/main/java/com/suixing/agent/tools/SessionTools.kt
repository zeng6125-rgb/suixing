package com.suixing.agent.tools

import com.suixing.agent.core.Core
import kotlinx.serialization.json.JsonObject

/**
 * 会话状态查询（用户：「加一个获取会话状态的指令，例如获取会话当前使用模型等」）。
 *
 * 只读：模型、端点、上下文窗口、当前助手、会话规模。**不读也不改会话内容**。
 */
class SessionTools : ToolGroup() {

    override val group: String = "会话"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "get_session_status",
            description = "Report the chat session's own state: which model and endpoint are answering, " +
                "the context window, the active persona, and how large the conversation is. " +
                "Read-only. Use it when the user asks what model is in use or how big the context is.",
            parameters = Schema.objectOf(emptyList()),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        if (name != "get_session_status") return ToolResult.failure("未知工具：" + name)
        val s = Core.settings.current()
        val provider = s.activeProvider()
        val window = s.contextWindowFor(s.model)
        val conversations = Core.chats.conversations.value
        val latest = conversations.maxByOrNull { it.updatedAt }
        val assistant = s.activeAssistant()

        val endpointLabel = provider?.label ?: "未匹配"
        val protocol = provider?.protocol.orEmpty().ifBlank { "未声明" }
        val assistantLabel = assistant?.label ?: "默认"
        val lines = mutableListOf<String>()
        lines += "当前模型：" + s.model
        lines += "模型（不含端点前缀）：" + s.shortModel
        lines += "端点：" + endpointLabel + "（" + s.baseUrl + "）"
        lines += "协议：" + protocol
        lines += "上下文窗口：" + window + " tokens"
        lines += "当前助手：" + assistantLabel
        lines += "会话总数：" + conversations.size
        if (latest != null) {
            val title = latest.title.ifBlank { "（无标题）" }
            // **条数走 meta，不读正文**（#30）：`conversations` 现在只带 meta，messages 是空的；
            // 就算不为性能，这里读 `.messages.size` 也会永远是 0。
            val count = latest.messageCount.takeIf { it > 0 } ?: latest.messages.size
            lines += "最近会话：" + title + " · " + count + " 条消息"
        }
        return ToolResult.success(lines.joinToString("\n"))
    }
}

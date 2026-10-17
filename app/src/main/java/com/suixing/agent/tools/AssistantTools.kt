package com.suixing.agent.tools

import com.suixing.agent.core.Core
import com.suixing.agent.data.ASSISTANT_AVATARS
import com.suixing.agent.data.Assistant
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * 在**对话里**用自然语言生成助手（用户：「会话中可以自然语言生成的」）。
 *
 * 生成器就是模型自己：它带着整段会话的上下文（用户的口味、语气偏好、反复纠正过的习惯都在
 * wire 里），把人设蒸馏成一份档案再调 [execute] 落盘 —— 不需要第二次 LLM 调用，也不存在
 * 「描述框里那一句」与「会话里聊出来的口味」脱节的问题。它与助手页的「新建」是**同一件事的
 * 两种说法**：落同一张助手表、进同一个编辑器可改，没有第三个入口。
 *
 * 落盘语义与编辑器保存新助手完全同款（[com.suixing.agent.ui.chat.ChatViewModel.saveAssistant]）：
 * 进助手表 + 成为默认；**当前会话不受影响**（它跑在自己的人设上），所以对话中途建助手不会把
 * 正在进行的这一轮换掉。
 */
class AssistantTools : ToolGroup() {

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "create_assistant",
            description = "Create a persona (assistant profile) and save it. Call this when the user asks " +
                "for a persona, or asks you to derive one from this conversation: distil their taste — tone, " +
                "length, conclusion-first or reasoning-first, taboos — into system_prompt instead of " +
                "applying a template. It becomes the default for new chats; the current chat keeps its persona.",
            parameters = Schema.objectOf(
                listOf(
                    "name" to Schema.string("Persona name, 2–6 Chinese characters"),
                    "avatar" to Schema.string("One emoji, the closest fit from the allowed set; optional"),
                    "description" to Schema.string("One-line summary, ≤20 characters; optional"),
                    "system_prompt" to Schema.string(
                        "The persona's system prompt, 100–300 characters: role, tone and verbal habits, " +
                            "strengths, rules of conduct (ask before acting, say when unsure). It should " +
                            "reflect this conversation's user.",
                    ),
                ),
                listOf("name", "system_prompt"),
            ),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "create_assistant" -> create(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun create(args: JsonObject): ToolResult {
        val assistantName = args.str("name")?.trim().orEmpty()
        val systemPrompt = args.str("system_prompt")?.trim().orEmpty()
        if (assistantName.isEmpty() || systemPrompt.isEmpty()) {
            return ToolResult.failure("缺少参数 name / system_prompt")
        }
        val avatar = args.str("avatar")?.trim()?.firstOrNull()?.toString()
            ?.takeIf { it in ASSISTANT_AVATARS } ?: "🪶"
        val assistant = Assistant(
            id = UUID.randomUUID().toString(),
            name = assistantName,
            avatar = avatar,
            description = args.str("description")?.trim().orEmpty(),
            systemPrompt = systemPrompt,
        )
        Core.settings.update { settings ->
            settings.copy(
                assistants = settings.assistants + assistant,
                // 与编辑器保存新助手同款：新建即默认。当前会话跑在自己的人设上，不受这一行影响。
                activeAssistantId = assistant.id,
            )
        }
        return ToolResult.success(
            "助手「${assistant.label}」已创建并设为新会话的默认助手；当前会话不受影响。" +
                "要改名字、头像或提示词，去 设置 → 助手 里编辑它。",
        )
    }
}

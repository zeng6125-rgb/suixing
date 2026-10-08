package com.suixing.agent.tools

import com.suixing.agent.skills.SkillStore
import kotlinx.serialization.json.JsonObject

/**
 * Skills are instruction documents, not code: the model reads one and then
 * follows it with the ordinary tools.
 */
class SkillTools(private val skills: SkillStore) : ToolGroup() {
    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "skill_list",
            description = "列出本机已安装的技能（名称、用途、id）",
            parameters = Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            name = "skill_read",
            description = "读取某个技能的完整说明。打算用某个技能前先调用它。",
            parameters = Schema.objectOf(
                listOf("name" to Schema.string("技能名或 id")),
                listOf("name"),
            ),
        ),
        ToolSpec(
            name = "skill_save",
            description = "把一段可复用的操作流程保存成技能，之后可以用 skill_read 读回来复用。",
            parameters = Schema.objectOf(
                listOf(
                    "name" to Schema.string("技能 id（建议英文，会用作目录名）"),
                    "description" to Schema.string("一句话说明什么时候该用这个技能"),
                    "content" to Schema.string("技能正文，Markdown"),
                ),
                listOf("name", "description", "content"),
            ),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "skill_list" -> listSkills()
        "skill_read" -> readSkill(args)
        "skill_save" -> saveSkill(args)
        else -> ToolResult.failure("未知技能工具：$name")
    }

    private suspend fun listSkills(): ToolResult {
        val all = skills.list()
        if (all.isEmpty()) {
            return ToolResult.success("本机还没有技能。技能放在 ${skills.root.absolutePath}/<名称>/SKILL.md。")
        }
        val text = all.joinToString("\n") { "- ${it.name}（id: ${it.id}）：${it.description}" }
        return ToolResult.success("共 ${all.size} 个技能：\n$text")
    }

    private suspend fun readSkill(args: JsonObject): ToolResult {
        val wanted = args.str("name")?.trim().orEmpty()
        if (wanted.isEmpty()) return ToolResult.failure("缺少参数 name")
        val skill = skills.find(wanted)
            ?: return ToolResult.failure("找不到技能「$wanted」。先用 skill_list 看看有哪些。")
        return ToolResult.success("技能「${skill.name}」的完整说明：\n\n${skill.body}")
    }

    private suspend fun saveSkill(args: JsonObject): ToolResult {
        val id = args.str("name")?.trim().orEmpty()
        val description = args.str("description")?.trim().orEmpty()
        val content = args.str("content").orEmpty()
        if (id.isEmpty()) return ToolResult.failure("缺少参数 name")
        if (description.isEmpty()) return ToolResult.failure("缺少参数 description")
        if (content.isBlank()) return ToolResult.failure("缺少参数 content")
        val skill = skills.save(id, skills.compose(id, description, content))
        return ToolResult.success("已保存技能「${skill.name}」到 ${skill.path}")
    }
}

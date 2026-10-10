package com.suixing.agent.tools

import com.suixing.agent.skills.SkillStore
import kotlinx.serialization.json.JsonObject

/**
 * Skills are instruction documents, not code: the model reads one and then
 * follows it with the ordinary tools.
 *
 * **How a skill is loaded, which must stay the way it is.** Only the catalogue is resident:
 * for every *enabled* skill, its name and one line of purpose sit in the system prompt, so the
 * model knows what exists. The instructions themselves are pulled one at a time by
 * `skill_read`, on the turn where the model actually uses that skill. That is the shape DSH
 * uses for its own skills, and it is why a library of a hundred skills costs a hundred lines
 * instead of a hundred documents. Do not "optimise" it into pasting bodies into the prompt.
 *
 * Who writes a skill decides whether it starts switched on, and that line is the trust boundary.
 * A skill the model writes here (`skill_save`) arrives **enabled**: the user asked for it in the
 * conversation they are already having, and making them hunt for a switch to use what they just
 * dictated only teaches them to ignore switches. Everything fetched from outside
 * (`skill_download`, 「从链接导入」, a file manager) arrives **disabled**, because that text was
 * written by someone else: a skill's name and description go into the system prompt, and the
 * toolchain behind that prompt can run shell commands, delete files and tap the screen. The two
 * writing tools therefore end on different sentences, and each says which case it is — a model
 * that believes its new skill is already active would lean on it and then act as if the user had
 * agreed to it.
 */
class SkillTools(private val skills: SkillStore) : ToolGroup() {
    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "skill_list",
            description = "列出本机已开启的技能（名称、用途、id）",
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
            description = "把一段可复用的操作流程保存成技能。保存后**立即生效**（默认开启），" +
                "它会进入技能目录，skill_list 与 skill_read 马上就能看到；用户可以在「设置 → 工具 → 技能」页关掉它。",
            parameters = Schema.objectOf(
                listOf(
                    "name" to Schema.string("技能 id（建议英文，会用作目录名）"),
                    "description" to Schema.string("一句话说明什么时候该用这个技能"),
                    "content" to Schema.string("技能正文，Markdown"),
                ),
                listOf("name", "description", "content"),
            ),
        ),
        ToolSpec(
            name = "skill_download",
            description = "从一个网址下载 SKILL.md 存成本机技能（记为网络下载）。下载来的内容默认关闭，" +
                "要用户在技能页看过再开启——它不是这个对话里写出来的东西。",
            parameters = Schema.objectOf(
                listOf("url" to Schema.string("SKILL.md 的完整地址，以 http:// 或 https:// 开头")),
                listOf("url"),
            ),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "skill_list" -> listSkills()
        "skill_read" -> readSkill(args)
        "skill_save" -> saveSkill(args)
        "skill_download" -> downloadSkill(args)
        else -> ToolResult.failure("未知技能工具：$name")
    }

    private suspend fun listSkills(): ToolResult {
        // Only what the user left switched on: the prompt catalogue is filtered the
        // same way, and a tool that advertised a disabled skill would invite the
        // model to reach for something the user deliberately turned off.
        val all = skills.list()
        val usable = all.filter { it.enabled }
        if (usable.isEmpty()) {
            // New skills are saved switched off, so "none" usually means "none switched on
            // yet": saying only "本机还没有技能" would send the model looking for a bug
            // instead of telling the user which switch to flip.
            val waiting = all.count { !it.enabled }
            val hint = if (waiting == 0) {
                ""
            } else {
                "有 $waiting 个技能已保存但还没开启，请让用户到「设置 → 工具 → 技能」页打开开关。"
            }
            return ToolResult.success("现在没有可用的技能。$hint 技能放在 ${skills.root.absolutePath}/<名称>/SKILL.md。")
        }
        val text = usable.joinToString("\n") { "- ${it.name}（id: ${it.id}）：${it.description}" }
        return ToolResult.success("共 ${usable.size} 个可用技能：\n$text")
    }

    private suspend fun readSkill(args: JsonObject): ToolResult {
        val wanted = args.str("name")?.trim().orEmpty()
        if (wanted.isEmpty()) return ToolResult.failure("缺少参数 name")
        val skill = skills.find(wanted) ?: return missing(wanted)
        return ToolResult.success("技能「${skill.name}」的完整说明：\n\n${skill.body}")
    }

    /** A skill that exists but is switched off is a different answer from "no such thing". */
    private suspend fun missing(wanted: String): ToolResult {
        val off = skills.list().firstOrNull { it.id.equals(wanted, true) || it.name.equals(wanted, true) }
        return if (off == null) {
            ToolResult.failure("找不到技能「$wanted」。先用 skill_list 看看有哪些。")
        } else {
            ToolResult.failure(
                "技能「${off.name}」已保存但还没开启。" +
                    "请让用户到「设置 → 工具 → 技能」页打开它的开关，之后才能读到。",
            )
        }
    }

    private suspend fun saveSkill(args: JsonObject): ToolResult {
        val id = args.str("name")?.trim().orEmpty()
        val description = args.str("description")?.trim().orEmpty()
        val content = args.str("content").orEmpty()
        if (id.isEmpty()) return ToolResult.failure("缺少参数 name")
        if (description.isEmpty()) return ToolResult.failure("缺少参数 description")
        if (content.isBlank()) return ToolResult.failure("缺少参数 content")
        val skill = skills.save(id, skills.compose(id, description, content), enabled = true)
        return ToolResult.success(savedActive(skill.name, skill.path, "由你新建"))
    }

    private suspend fun downloadSkill(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.failure("缺少参数 url")
        val skill = runCatching { skills.importUrl(url) }.getOrElse { error ->
            return ToolResult.failure("下载技能失败：${error.message ?: error::class.java.simpleName}")
        }
        return ToolResult.success(savedPending(skill.name, skill.path, "从 $url 下载"))
    }

    /**
     * What `skill_save` ends on: the file is there **and already in the catalogue**.
     *
     * It has to say so, because the opposite sentence used to live here: a model told "saved but
     * switched off" reports a half-success to the user and then avoids the skill it just wrote.
     */
    private fun savedActive(name: String, path: String, how: String): String =
        "已保存并已启用技能「$name」（$how）到 $path。它已经进入模型的技能目录：" +
            "skill_list 与 skill_read 现在都能看到它，可以直接用。用户若想停用，" +
            "在「设置 → 工具 → 技能」页关掉开关即可。"

    /**
     * What `skill_download` ends on. It has to say three things: the file is there, it is **not**
     * active, and who can make it active — anything shorter and the model reports success to the
     * user and then tries to use a skill that is not in the catalogue.
     */
    private fun savedPending(name: String, path: String, how: String): String =
        "已保存技能「$name」（$how）到 $path。它默认处于关闭状态：现在还不在技能目录里，" +
            "skill_list 与 skill_read 都看不到它。请提示用户到「设置 → 工具 → 技能」页打开它，" +
            "开启后才会生效。"
}

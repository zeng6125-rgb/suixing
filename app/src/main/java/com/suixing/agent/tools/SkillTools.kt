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

    override val group: String = "技能"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "skill_list",
            description = "List the skills enabled on this device (name, purpose, id).",
            parameters = Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            name = "skill_read",
            description = "Read one skill's full instructions. Call it before you start using a skill.",
            parameters = Schema.objectOf(
                listOf("name" to Schema.string("Skill name or id")),
                listOf("name"),
            ),
        ),
        ToolSpec(
            name = "skill_save",
            description = "Save a reusable procedure as a skill. It takes effect **immediately** " +
                "(enabled by default): it joins the skill catalogue, so skill_list and skill_read " +
                "see it at once; the user can switch it off on the Settings → Tools → Skills page.",
            parameters = Schema.objectOf(
                listOf(
                    "name" to Schema.string("Skill id (ASCII is best; it becomes the directory name)"),
                    "description" to Schema.string("One line saying when this skill should be used"),
                    "content" to Schema.string("Skill body, Markdown"),
                ),
                listOf("name", "description", "content"),
            ),
        ),
        ToolSpec(
            name = "skill_download",
            description = "Download a SKILL.md from a URL and store it as a local skill (recorded as " +
                "a network download). Downloaded content starts switched off: the user has to review " +
                "it on the skills page first, because it was not written in this conversation.",
            parameters = Schema.objectOf(
                listOf("url" to Schema.string("Full address of the SKILL.md, starting with http:// or https://")),
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
        else -> ToolResult.failure("Unknown skill tool: $name")
    }

    private suspend fun listSkills(): ToolResult {
        // Only what the user left switched on: the prompt catalogue is filtered the
        // same way, and a tool that advertised a disabled skill would invite the
        // model to reach for something the user deliberately turned off.
        val all = skills.list()
        val usable = all.filter { it.enabled }
        if (usable.isEmpty()) {
            // New skills are saved switched off, so "none" usually means "none switched on
            // yet": saying only "there are no skills" would send the model looking for a bug
            // instead of telling the user which switch to flip.
            val waiting = all.count { !it.enabled }
            val hint = if (waiting == 0) {
                ""
            } else {
                "$waiting skill(s) are saved but not enabled; ask the user to turn them on under " +
                    "「设置 → 工具 → 技能」."
            }
            return ToolResult.success(
                "No skills are available right now. $hint Skills live at " +
                    "${skills.root.absolutePath}/<name>/SKILL.md.",
            )
        }
        val text = usable.joinToString("\n") { "- ${it.name} (id: ${it.id}): ${it.description}" }
        return ToolResult.success("${usable.size} skill(s) available:\n$text")
    }

    private suspend fun readSkill(args: JsonObject): ToolResult {
        val wanted = args.str("name")?.trim().orEmpty()
        if (wanted.isEmpty()) return ToolResult.failure("Missing argument: name")
        val skill = skills.find(wanted) ?: return missing(wanted)
        return ToolResult.success("Full instructions for skill \"${skill.name}\":\n\n${skill.body}")
    }

    /** A skill that exists but is switched off is a different answer from "no such thing". */
    private suspend fun missing(wanted: String): ToolResult {
        val off = skills.list().firstOrNull { it.id.equals(wanted, true) || it.name.equals(wanted, true) }
        return if (off == null) {
            ToolResult.failure("No such skill: \"$wanted\". Use skill_list to see what exists.")
        } else {
            ToolResult.failure(
                "Skill \"${off.name}\" is saved but not enabled. Ask the user to turn it on under " +
                    "「设置 → 工具 → 技能」; only then can it be read.",
            )
        }
    }

    private suspend fun saveSkill(args: JsonObject): ToolResult {
        val id = args.str("name")?.trim().orEmpty()
        val description = args.str("description")?.trim().orEmpty()
        val content = args.str("content").orEmpty()
        if (id.isEmpty()) return ToolResult.failure("Missing argument: name")
        if (description.isEmpty()) return ToolResult.failure("Missing argument: description")
        if (content.isBlank()) return ToolResult.failure("Missing argument: content")
        val skill = skills.save(id, skills.compose(id, description, content), enabled = true)
        return ToolResult.success(savedActive(skill.name, skill.path, "written by you"))
    }

    private suspend fun downloadSkill(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.failure("Missing argument: url")
        val skill = runCatching { skills.importUrl(url) }.getOrElse { error ->
            return ToolResult.failure("Skill download failed: ${error.message ?: error::class.java.simpleName}")
        }
        return ToolResult.success(savedPending(skill.name, skill.path, "downloaded from $url"))
    }

    /**
     * What `skill_save` ends on: the file is there **and already in the catalogue**.
     *
     * It has to say so, because the opposite sentence used to live here: a model told "saved but
     * switched off" reports a half-success to the user and then avoids the skill it just wrote.
     */
    private fun savedActive(name: String, path: String, how: String): String =
        "Saved and enabled skill \"$name\" ($how) at $path. It is already in the model's skill " +
            "catalogue: skill_list and skill_read both see it now, and it can be used directly. " +
            "The user can disable it on the 「设置 → 工具 → 技能」page."

    /**
     * What `skill_download` ends on. It has to say three things: the file is there, it is **not**
     * active, and who can make it active — anything shorter and the model reports success to the
     * user and then tries to use a skill that is not in the catalogue.
     */
    private fun savedPending(name: String, path: String, how: String): String =
        "Saved skill \"$name\" ($how) at $path. It is switched off by default: it is not in the " +
            "catalogue yet, and neither skill_list nor skill_read can see it. Tell the user to turn " +
            "it on under 「设置 → 工具 → 技能」; it takes effect only after that."
}

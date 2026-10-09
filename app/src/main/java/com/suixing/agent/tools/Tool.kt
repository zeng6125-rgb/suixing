package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.JobStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.ScheduleStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.TodoStore
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.voice.Speaker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

const val TIER_NONE = "none"
const val TIER_ACCESSIBILITY = "accessibility"
const val TIER_SHELL = "shell"
const val TIER_ROOT = "root"

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val imageBase64: String? = null,
) {
    companion object {
        fun success(output: String): ToolResult = ToolResult(true, output)

        fun failure(output: String): ToolResult = ToolResult(false, output)

        fun image(output: String, base64: String): ToolResult = ToolResult(true, output, base64)
    }
}

interface Tool {
    val spec: ToolSpec

    /** Privilege this tool needs; the UI groups tools by it. */
    val tier: String

    suspend fun run(args: JsonObject): ToolResult
}

/** A bundle of related tools sharing state (context, capabilities). */
abstract class ToolGroup {
    abstract val specs: List<ToolSpec>

    open val tier: String = TIER_NONE

    abstract suspend fun execute(name: String, args: JsonObject): ToolResult
}

/** Small builders so tool schemas stay readable. */
object Schema {
    fun string(description: String, enumValues: List<String>? = null, default: String? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enumValues != null) put("enum", JsonArray(enumValues.map { JsonPrimitive(it) }))
            if (default != null) put("default", default)
        }

    fun integer(
        description: String,
        default: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (default != null) put("default", default)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        if (default != null) put("default", default)
    }

    fun objectOf(
        properties: List<Pair<String, JsonObject>>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (key, value) -> put(key, value) } })
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    /**
     * An array whose elements are all shaped like [item].
     *
     * A list of objects rather than one packed string: a tool that takes the whole board at
     * once must not depend on the model getting newline escaping right, or a broken write
     * silently loses half the tasks.
     */
    fun arrayOf(description: String, item: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", item)
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String, fallback: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

class ToolRegistry private constructor() {
    private val builtin = LinkedHashMap<String, Tool>()
    private val dynamic = LinkedHashMap<String, Tool>()

    /** Tool names this view hides; empty for the registry the app builds at startup. */
    private var hidden: Set<String> = emptySet()

    constructor(
        context: Context,
        capabilities: CapabilityHub,
        skills: SkillStore,
        speaker: Speaker,
        settings: SettingsRepository,
        memory: MemoryStore,
        jobs: JobStore,
        todos: TodoStore,
        schedules: ScheduleStore,
    ) : this() {
        add(SystemTools(context, memory))
        add(FileTools(context))
        add(ShellTools(context, capabilities))
        add(UiTools(context, capabilities))
        add(JobTools(jobs))
        add(TodoTools(todos))
        add(ScheduleTools(context, schedules))
        add(SkillTools(skills))
        add(WebTools(context, settings))
        add(VoiceTools(speaker))
    }

    fun add(group: ToolGroup) {
        for (spec in group.specs) {
            check(!builtin.containsKey(spec.name)) { "工具名重复：${spec.name}" }
            builtin[spec.name] = object : Tool {
                override val spec: ToolSpec = spec
                override val tier: String = group.tier
                override suspend fun run(args: JsonObject): ToolResult = group.execute(spec.name, args)
            }
        }
    }

    /**
     * Replaces every runtime-contributed tool whose name starts with [prefix].
     * MCP servers connect and drop at runtime, so they never touch the builtin
     * map; a name that would collide with a builtin is rejected instead of
     * silently shadowing it.
     */
    fun replaceDynamic(prefix: String, tools: List<Tool>) {
        dynamic.keys.removeAll { it.startsWith(prefix) }
        for (tool in tools) {
            check(!builtin.containsKey(tool.spec.name)) { "工具名与内置工具冲突：${tool.spec.name}" }
            dynamic[tool.spec.name] = tool
        }
    }

    /**
     * A live view without [names], for a persona that must not reach for them.
     *
     * The maps are shared, not copied, so an MCP server that connects later still
     * shows up in the filtered view.
     */
    fun excluding(names: Set<String>): ToolRegistry {
        if (names.isEmpty()) return this
        return ToolRegistry().also {
            it.builtin.putAll(builtin)
            it.dynamic.putAll(dynamic)
            it.hidden = names
        }
    }

    fun all(): List<Tool> =
        (builtin.values + dynamic.values).filter { it.spec.name !in hidden }

    fun find(name: String): Tool? =
        if (name in hidden) null else builtin[name] ?: dynamic[name]

    fun names(): List<String> = all().map { it.spec.name }

    /** One-line-per-tool summary injected into the system prompt. */
    fun promptSummary(): String = all().joinToString("\n") { "- ${it.spec.name}: ${it.spec.description}" }
}

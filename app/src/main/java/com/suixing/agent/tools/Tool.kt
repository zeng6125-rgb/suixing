package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityTiers
import com.suixing.agent.data.JobStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.ScheduleStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.TodoStore
import com.suixing.agent.data.UserProfileStore
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

// The 执行档次 keys, declared in capability/ beside the state that resolves them and re-exported
// here because every tool declares its tier with these names.
const val TIER_NONE = CapabilityTiers.NONE
const val TIER_ACCESSIBILITY = CapabilityTiers.ACCESSIBILITY
const val TIER_SHELL = CapabilityTiers.SHELL
const val TIER_ROOT = CapabilityTiers.ROOT

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

/**
 * 把「只有 spec」的虚拟工具包装成 [Tool]，好让它出现在工具页里。
 *
 * 用于引擎自己在分发前拦截的工具（例如 `compress_context`）：它没有要跑的实现、也不在注册表里，
 * 但模型看得到它，所以设置页也该列出来。它的 [run] 永远不会被调用 —— 引擎在分发前就返回了。
 */
fun virtualTool(spec: ToolSpec): Tool = object : Tool {
    override val spec: ToolSpec = spec
    override val tier: String = TIER_NONE
    override suspend fun run(args: JsonObject): ToolResult =
        ToolResult.failure("这个工具由引擎在分发前拦截，不会走到这里。")
}

/** A bundle of related tools sharing state (context, capabilities). */
abstract class ToolGroup {
    abstract val specs: List<ToolSpec>

    open val tier: String = TIER_NONE

    /**
     * 设置页里这一组显示的名字（中文可以）。**由工具包自己声明**，界面不再维护一张
     * 名字→分组的表：加了工具就自动出现在它那一组，组头的人数也跟着变。
     * 空串＝未声明，界面归到「其他」。
     */
    open val group: String = ""

    abstract suspend fun execute(name: String, args: JsonObject): ToolResult
}

/** Small builders so tool schemas stay readable. */
object Schema {
    // 描述是**可选**的：`"description":""` 也要占十几个字符，而属性名常常已经说明一切（path、from、x…）。
    // 传空串就整条不发 —— 每次请求都付这份钱，能省则省（实测口径见 ToolSchemaBudgetTest）。
    fun string(description: String, enumValues: List<String>? = null, default: String? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            if (description.isNotBlank()) put("description", description)
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
        if (description.isNotBlank()) put("description", description)
        if (default != null) put("default", default)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        if (description.isNotBlank()) put("description", description)
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

    /** 工具名 → 分组名，由 [add] 在注册时记下（见 [groupOf]）。 */
    private val groups = LinkedHashMap<String, String>()

    /** Tool names this view hides; empty for the registry the app builds at startup. */
    private var hidden: Set<String> = emptySet()

    constructor(
        context: Context,
        capabilities: CapabilityHub,
        skills: SkillStore,
        speaker: Speaker,
        settings: SettingsRepository,
        memory: MemoryStore,
        profile: UserProfileStore,
        jobs: JobStore,
        todos: TodoStore,
        schedules: ScheduleStore,
    ) : this() {
        add(SystemTools(context, memory, profile))
        add(FileTools(context))
        add(ShellTools(context, capabilities))
        add(UiTools(context, capabilities))
        add(JobTools(jobs))
        add(TodoTools(todos))
        add(ScheduleTools(context, schedules))
        add(SkillTools(skills))
        add(WebTools(context, settings))
        add(VoiceTools(speaker))
        add(AssistantTools())
        add(SessionTools())
    }

    @Synchronized
    fun add(group: ToolGroup) {
        for (spec in group.specs) {
            check(!builtin.containsKey(spec.name)) { "工具名重复：${spec.name}" }
            builtin[spec.name] = object : Tool {
                override val spec: ToolSpec = spec
                override val tier: String = group.tier
                override suspend fun run(args: JsonObject): ToolResult = group.execute(spec.name, args)
            }
            groups[spec.name] = group.group
        }
    }

    /** 这个名字属于哪一组（工具包自己声明的）；空串＝没声明，界面归到「其他」。 */
    fun groupOf(name: String): String = groups[name].orEmpty()

    /** 组的出现顺序：按注册顺序，自动跟着工具包走，界面不用再排。 */
    fun groupOrder(): List<String> = groups.values.filter { it.isNotBlank() }.distinct()

    /**
     * Replaces every runtime-contributed tool whose name starts with [prefix].
     * MCP servers connect and drop at runtime, so they never touch the builtin
     * map; a name that would collide with a builtin is rejected instead of
     * silently shadowing it.
     */
    @Synchronized
    fun replaceDynamic(prefix: String, tools: List<Tool>) {
        dynamic.keys.removeAll { it.startsWith(prefix) }
        for (tool in tools) {
            check(!builtin.containsKey(tool.spec.name)) { "工具名与内置工具冲突：${tool.spec.name}" }
            dynamic[tool.spec.name] = tool
        }
    }

    /**
     * A tool snapshot without [names], for a persona that must not reach for them.
     * New runs snapshot the current MCP catalog; in-flight tool tables stay fixed.
     */
    @Synchronized
    fun excluding(names: Set<String>): ToolRegistry {
        if (names.isEmpty()) return this
        return ToolRegistry().also {
            it.builtin.putAll(builtin)
            it.dynamic.putAll(dynamic)
            it.hidden = hidden + names
        }
    }

    @Synchronized
    fun all(): List<Tool> =
        (builtin.values + dynamic.values).filter { it.spec.name !in hidden }

    fun isHidden(name: String): Boolean = name in hidden

    @Synchronized
    fun find(name: String): Tool? =
        if (name in hidden) null else builtin[name] ?: dynamic[name]

    fun names(): List<String> = all().map { it.spec.name }

    /** One-line-per-tool summary injected into the system prompt. */
    fun promptSummary(): String = all().joinToString("\n") { "- ${it.spec.name}: ${it.spec.description}" }
}

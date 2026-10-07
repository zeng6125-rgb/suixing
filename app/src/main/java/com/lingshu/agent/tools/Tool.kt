package com.lingshu.agent.tools

import android.content.Context
import com.lingshu.agent.capability.CapabilityHub
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
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String, fallback: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

class ToolRegistry(context: Context, capabilities: CapabilityHub) {
    private val tools = LinkedHashMap<String, Tool>()

    init {
        add(SystemTools(context))
        add(FileTools(context))
        add(ShellTools(capabilities))
        add(UiTools(capabilities))
    }

    fun add(group: ToolGroup) {
        for (spec in group.specs) {
            check(!tools.containsKey(spec.name)) { "工具名重复：${spec.name}" }
            tools[spec.name] = object : Tool {
                override val spec: ToolSpec = spec
                override val tier: String = group.tier
                override suspend fun run(args: JsonObject): ToolResult = group.execute(spec.name, args)
            }
        }
    }

    fun all(): List<Tool> = tools.values.toList()

    fun find(name: String): Tool? = tools[name]

    fun names(): List<String> = tools.keys.toList()

    /** One-line-per-tool summary injected into the system prompt. */
    fun promptSummary(): String = tools.values.joinToString("\n") { "- ${it.spec.name}: ${it.spec.description}" }
}

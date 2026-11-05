package com.suixing.agent.ui.chat

import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TranscriptRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A composer command such as `/goal finish the migration` or `/web_search {"query":"…"}`. */
internal data class SlashCommand(
    val name: String,
    /** Everything after the first whitespace, already trimmed. */
    val argument: String,
)

/**
 * The composer only treats a message as a command when the first token is a slash plus a
 * tool-like name. A bare `/` or a path such as `/data/...` stays ordinary text.
 */
internal fun parseSlashCommand(text: String): SlashCommand? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/") || trimmed.length < 2) return null
    val end = trimmed.indexOfFirst { it.isWhitespace() }.let { if (it < 0) trimmed.length else it }
    val name = trimmed.substring(1, end)
    if (!NAME.matches(name)) return null
    return SlashCommand(name.lowercase(), trimmed.substring(end).trim())
}

private val NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

/** `/goal` reads; `/goal resume` resumes; `/goal clear` clears. */
internal fun SlashCommand.toolName(): String =
    when {
        name == "goal" && argument.isBlank() -> "goal_read"
        name == "goal" && argument.equals("resume", ignoreCase = true) -> "goal_resume"
        else -> name
    }

internal fun SlashCommand.toolArguments(spec: ToolSpec): JsonObject? {
    if (name == "goal" && toolName() == "goal_resume") return buildJsonObject { }
    if (name == "goal" && argument.equals("clear", ignoreCase = true)) {
        return buildJsonObject { put("text", "") }
    }
    val args = slashArguments(spec, argument) ?: return null
    // An explicitly malformed goal object must not be interpreted as a request to clear it.
    if (name == "goal" && argument.isNotBlank() && (args["text"] as? JsonPrimitive)?.isString != true) return null
    return args
}

/** Editing alone never executes a command, but user command secrets still cannot enter disk/title. */
internal fun editedTranscriptMessage(message: StoredMessage, text: String): StoredMessage {
    val command = if (message.role == Roles.USER) parseSlashCommand(text) else null
    val stored = command?.let { TranscriptRedaction.commandForTranscript(text, it.toolName(), "") } ?: text
    return message.copy(text = stored, edited = true)
}

/**
 * Maps the typed tail to the tool's own JSON schema.
 *
 * Three accepted forms, in order: `{"json":"object"}`, a single string placed in the
 * first required string property (or the first string property), and an empty object for
 * argument-less tools. Anything else returns null; the caller reports the usage rather
 * than sending a guessed call.
 */
internal fun slashArguments(spec: ToolSpec, argument: String): JsonObject? {
    if (argument.isBlank()) return buildJsonObject { }
    if (argument.startsWith("{")) {
        return runCatching { Json.parseToJsonElement(argument).jsonObject }.getOrNull()
    }
    val properties = runCatching {
        spec.parameters["properties"]!!.jsonObject
    }.getOrNull() ?: return null
    val required = (spec.parameters["required"] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content }
    val key = required.firstOrNull { name ->
        (properties[name] as? JsonObject)?.stringType() == true
    } ?: properties.entries.firstOrNull { (_, value) ->
        (value as? JsonObject)?.stringType() == true
    }?.key ?: return null
    // A selected reference supplies a path value to file tools, while other text stays verbatim.
    val reference = if (key == "path" || key.endsWith("_path") || key.endsWith("Path")) {
        fileReferences(argument).singleOrNull()?.takeIf {
            it.start == 0 && it.end == argument.length
        }
    } else null
    return buildJsonObject { put(key, reference?.path ?: argument) }
}

private fun JsonObject.stringType(): Boolean =
    (this["type"] as? JsonPrimitive)?.content == "string"

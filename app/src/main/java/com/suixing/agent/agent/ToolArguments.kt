package com.suixing.agent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.suixing.agent.core.NativeCore

/** Reject malformed or non-object arguments before approval, hooks, or tool side effects. */
internal fun parseToolArguments(raw: String): JsonObject {
    val text = raw.ifBlank { "{}" }
    return NativeCore.parseObject(text) ?: (Json.parseToJsonElement(text) as? JsonObject)
        ?: throw IllegalArgumentException("Tool arguments must be a JSON object")
}

package com.suixing.agent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Reject malformed or non-object arguments before approval, hooks, or tool side effects. */
internal fun parseToolArguments(raw: String): JsonObject {
    return Json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject
        ?: throw IllegalArgumentException("Tool arguments must be a JSON object")
}

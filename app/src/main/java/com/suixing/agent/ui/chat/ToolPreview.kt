package com.suixing.agent.ui.chat

import com.suixing.agent.data.TranscriptRedaction
import kotlinx.serialization.json.*

private val previewJson = Json { isLenient = true }
private val secretKey = Regex("(?i)^(?:[a-z0-9]+[_-])*(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|cookie|credential)$")
private val bearer = Regex("""(?i)\b(Bearer|Basic)\s+[a-z0-9._~+/=-]+""")
private val urlCredentials = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/\s@]+@""")
private val assignment = Regex("""(?i)([\w-]*(?:api[_-]?key|token|password|secret)\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&}]+)""")

/** Header-only redaction. Execution arguments, approval text and the expanded call stay intact. */
internal fun toolArgumentPreview(name: String, arguments: String): String? {
    val stored = TranscriptRedaction.argumentsForTranscript(name, arguments)
    fun mask(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (secretKey.matches(key)) JsonPrimitive("•••") else mask(child)
        })
        is JsonArray -> JsonArray(value.map(::mask))
        else -> value
    }
    val structured = runCatching { mask(previewJson.parseToJsonElement(stored)).toString() }.getOrDefault(stored)
    val withoutCredentials = urlCredentials.replace(structured) { it.groupValues[1] + "•••@" }
    val safe = assignment.replace(bearer.replace(withoutCredentials) { it.groupValues[1] + " •••" }) {
        it.groupValues[1] + "•••"
    }
    return safe.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?.takeIf { it.isNotEmpty() && it != "{}" }?.take(512)
}

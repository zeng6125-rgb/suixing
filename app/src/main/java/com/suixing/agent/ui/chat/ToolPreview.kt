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
    if (stored.isBlank()) return null
    when (val outcome = NativeToolPreview.preview(stored)) {
        is NativePreviewOutcome.Ready -> return outcome.text
        NativePreviewOutcome.Empty -> return null
        null -> Unit
    }
    fun mask(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            var updated: LinkedHashMap<String, JsonElement>? = null
            value.entries.forEachIndexed { idx, (key, child) ->
                val redactedChild = if (secretKey.matches(key)) JsonPrimitive("•••") else mask(child)
                if (updated == null && redactedChild !== child) {
                    updated = LinkedHashMap<String, JsonElement>(value.size).apply {
                        for ((k, v) in value.entries.take(idx)) put(k, v)
                    }
                }
                updated?.put(key, redactedChild)
            }
            updated?.let(::JsonObject) ?: value
        }
        is JsonArray -> {
            var updated: ArrayList<JsonElement>? = null
            for (i in value.indices) {
                val child = value[i]
                val redactedChild = mask(child)
                if (updated == null && redactedChild !== child) {
                    updated = ArrayList<JsonElement>(value.size).apply {
                        for (j in 0 until i) add(value[j])
                    }
                }
                updated?.add(redactedChild)
            }
            updated?.let(::JsonArray) ?: value
        }
        else -> value
    }
    val structured = runCatching { mask(previewJson.parseToJsonElement(stored)).toString() }.getOrDefault(stored)
    val withoutCredentials = if (structured.contains("://")) {
        urlCredentials.replace(structured) { it.groupValues[1] + "•••@" }
    } else structured
    val afterBearer = if (
        withoutCredentials.contains("bearer", ignoreCase = true) ||
        withoutCredentials.contains("basic", ignoreCase = true)
    ) {
        bearer.replace(withoutCredentials) { it.groupValues[1] + " •••" }
    } else withoutCredentials
    val safe = if (
        (afterBearer.contains('=') || afterBearer.contains(':')) &&
        (afterBearer.contains("api", ignoreCase = true) ||
            afterBearer.contains("token", ignoreCase = true) ||
            afterBearer.contains("password", ignoreCase = true) ||
            afterBearer.contains("secret", ignoreCase = true))
    ) {
        assignment.replace(afterBearer) { it.groupValues[1] + "•••" }
    } else afterBearer
    return firstNonBlankTrimmedLine(safe)?.takeIf { it != "{}" }?.take(512)
}

private fun firstNonBlankTrimmedLine(text: String): String? {
    var lineStart = 0
    val len = text.length
    while (lineStart <= len) {
        val lf = text.indexOf('\n', lineStart)
        val rawEnd = if (lf < 0) len else lf
        var end = if (rawEnd > lineStart && text[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
        var start = lineStart
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) return text.substring(start, end)
        if (lf < 0) break
        lineStart = lf + 1
    }
    return null
}

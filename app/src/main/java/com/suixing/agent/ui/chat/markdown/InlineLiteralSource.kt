package com.suixing.agent.ui.chat.markdown

/** Every supported inline construct requires one of these characters (URLs/paths include '/'). */
private const val INLINE_OPENERS = "\\`*_~[/"

internal fun literalInlineSource(text: String, from: Int = 0): Boolean {
    for (index in from until text.length) if (text[index] in INLINE_OPENERS) return false
    return true
}

/** Keep only the current source. Rewrites are checked in full; appends inspect their suffix. */
internal class InlineLiteralSource {
    private var previous = ""
    private var literal = true
    var lastWasAppend: Boolean = false
        private set

    fun accepts(text: String): Boolean {
        val appending = text.length >= previous.length && text.startsWith(previous)
        lastWasAppend = appending
        literal = if (appending) literal && literalInlineSource(text, previous.length) else literalInlineSource(text)
        previous = text
        return literal
    }
}

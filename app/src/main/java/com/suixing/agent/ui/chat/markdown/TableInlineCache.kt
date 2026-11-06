package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.text.AnnotatedString

/** Owned by one table and one palette/callback scope; never retains a screen globally. */
internal class TableInlineCache(
    private val maxEntries: Int = 192,
    private val maxChars: Int = 32 * 1024,
    private val maxEstimatedBytes: Long = 256 * 1024,
) {
    private data class Key(val source: String, val caret: Boolean)
    private data class Entry(val result: AnnotatedString, val estimatedBytes: Long)
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var chars = 0
    private var bytes = 0L

    init { require(maxEntries > 0 && maxChars > 0 && maxEstimatedBytes > 0) }

    fun getOrParse(
        source: String,
        caret: Boolean,
        cacheable: Boolean = true,
        parse: () -> AnnotatedString,
    ): AnnotatedString {
        // The active final cell already remembers its exact result at the call site. Its growing
        // prefixes are never revisited; retaining them would churn this cache and evict old cells.
        if (caret || !cacheable) return parse()
        val key = Key(source, caret)
        entries[key]?.let { return it.result }
        val result = parse()
        val weight = 96L + source.length * 2L + result.length * 2L +
            result.spanStyles.size * 64L + result.paragraphStyles.size * 64L +
            result.getLinkAnnotations(0, result.length).size * 96L
        if (source.length > maxChars || weight > maxEstimatedBytes) return result
        entries[key] = Entry(result, weight)
        chars += source.length
        bytes += weight
        while (entries.size > maxEntries || chars > maxChars || bytes > maxEstimatedBytes) {
            val oldest = entries.entries.iterator()
            val entry = oldest.next()
            chars -= entry.key.source.length
            bytes -= entry.value.estimatedBytes
            oldest.remove()
        }
        return result
    }
}

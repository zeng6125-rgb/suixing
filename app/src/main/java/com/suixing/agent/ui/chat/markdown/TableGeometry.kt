package com.suixing.agent.ui.chat.markdown

import kotlin.math.roundToInt
import kotlin.math.sign

/** Equal weights use the same rounded-share/remainder distribution as Foundation Row. */
internal class TableColumns(width: Int, count: Int, divider: Int) {
    val widths = IntArray(count)
    val starts = IntArray(count)
    val dividers = IntArray((count - 1).coerceAtLeast(0))
    val dividerWidths = IntArray(dividers.size)

    init {
        require(width >= 0 && count >= 0 && divider >= 0)
        if (count > 0) {
            val fixed = ((count - 1L) * divider).coerceAtMost(width.toLong()).toInt()
            val available = width - fixed
            val rounded = (available.toFloat() / count).roundToInt()
            var remainder = available.toLong() - rounded.toLong() * count
            var x = 0
            var dividerLeft = fixed
            for (index in 0 until count) {
                val correction = remainder.sign
                remainder -= correction
                widths[index] = (rounded + correction).coerceAtLeast(0)
                starts[index] = x
                x += widths[index]
                if (index < count - 1) {
                    dividers[index] = x
                    val pixels = minOf(divider, dividerLeft)
                    dividerWidths[index] = pixels
                    dividerLeft -= pixels
                    x += pixels
                }
            }
        }
    }

    fun dividerX(index: Int, width: Int, rtl: Boolean): Int =
        if (rtl) width - dividers[index] - dividerWidths[index] else dividers[index]
}

/** Geometry has no text/font dependency; only a small set of widths/counts is retained per table. */
internal class TableColumnCache {
    private data class Key(val width: Int, val count: Int, val divider: Int)
    private val entries = LinkedHashMap<Key, TableColumns>(8, .75f, true)

    fun get(width: Int, count: Int, divider: Int): TableColumns {
        val key = Key(width, count, divider)
        return entries[key] ?: TableColumns(width, count, divider).also { columns ->
            entries[key] = columns
            if (entries.size > 8) entries.entries.iterator().let { it.next(); it.remove() }
        }
    }
}

package com.suixing.agent.ui.chat

/** Index line starts once. Only requested rows allocate a substring. */
internal class IndexedTextLines(
    private val source: String,
    private val universalNewlines: Boolean,
) : AbstractList<String>() {
    private var starts = IntArray(16)
    override var size = 1
        private set

    init {
        var index = 0
        while (index < source.length) {
            val character = source[index++]
            if (character != '\n' && !(universalNewlines && character == '\r')) continue
            if (character == '\r' && index < source.length && source[index] == '\n') index++

            if (size == starts.size) starts = starts.copyOf(starts.size * 2)
            starts[size++] = index
        }
    }

    override fun get(index: Int): String {
        if (index !in indices) throw IndexOutOfBoundsException("index=$index, size=$size")
        val start = starts[index]
        if (index == lastIndex) return source.substring(start)
        var end = starts[index + 1] - 1
        if (universalNewlines && source[end] == '\n' && end > start && source[end - 1] == '\r') end--
        return source.substring(start, end)
    }
}

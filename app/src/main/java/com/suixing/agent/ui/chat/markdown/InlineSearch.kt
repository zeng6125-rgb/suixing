package com.suixing.agent.ui.chat.markdown

/** Search state for one active inline text node. It retains only the current source and frontiers. */
internal class InlineSearchState(initialText: CharSequence) {
    val delimiters = InlineDelimiterSearch(initialText)
    val links = InlineLinkSearch(initialText)

    fun update(text: CharSequence, appendOnly: Boolean) {
        delimiters.update(text, appendOnly)
        links.update(text, appendOnly)
    }
}

/** Reuses failed delimiter searches when the active source grows. */
internal class InlineDelimiterSearch(initialText: CharSequence) {
    private var text: CharSequence = initialText
    // One bounded failed query and its stable prefix frontier for each marker/width pair.
    private val failedRanges = IntArray(18) { -1 }

    fun update(nextText: CharSequence, appendOnly: Boolean) {
        if (!appendOnly || nextText.length < text.length) {
            text = nextText
            failedRanges.fill(-1)
            return
        }
        if (nextText.length == text.length) {
            // InlineLiteralSource has already checked equality through startsWith when lengths match.
            text = nextText
            return
        }

        val stableEnd = stableEndBeforeAppend()
        for (slot in failedRanges.indices step 3) {
            val safeEnd = failedRanges[slot + 2]
            if (safeEnd >= 0) failedRanges[slot + 2] = minOf(safeEnd, stableEnd)
        }
        text = nextText
    }

    fun findClosing(marker: Char, len: Int, from: Int, to: Int): Int {
        val slot = slot(marker, len) * 3
        val failedFrom = failedRanges[slot]
        val failedTo = failedRanges[slot + 1]
        val safeEnd = failedRanges[slot + 2]
        val fromWasPossiblyEscaped =
            failedTo >= 0 && from > failedFrom && from > 0 && text[from - 1] == '\\'
        if (failedTo >= 0 && from >= failedFrom && !fromWasPossiblyEscaped) {
            if (to == failedTo) return -1
            if (to > failedTo && from <= safeEnd) {
                val match = scan(marker, len, maxOf(from, safeEnd), to)
                if (match >= 0) return match
                failedRanges[slot + 1] = to
                failedRanges[slot + 2] = stableEnd(from = failedFrom, to = to)
                return -1
            }
        }

        val match = scan(marker, len, from, to)
        if (match >= 0) return match
        if (failedTo < 0 || from < failedFrom) {
            failedRanges[slot] = from
            failedRanges[slot + 1] = to
            failedRanges[slot + 2] = stableEnd(from, to)
        }
        return -1
    }

    /** The old final token can join an appended escape or marker run, so rescan from its start. */
    private fun stableEndBeforeAppend(): Int = stableEnd(0, text.length)

    private fun stableEnd(from: Int, to: Int): Int {
        var boundary = to
        if (boundary <= from) return boundary
        val last = text[boundary - 1]
        if (last == '\\') {
            while (boundary > from && text[boundary - 1] == '\\') boundary--
            return boundary
        }
        if (last in DELIMITER_CHARS) {
            while (boundary > from && text[boundary - 1] == last) boundary--
            while (boundary > from && text[boundary - 1] == '\\') boundary--
        }
        return boundary
    }

    private fun scan(marker: Char, len: Int, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to) {
                i += 2
                continue
            }
            if (c == marker) {
                var runEnd = i + 1
                while (runEnd < to && text[runEnd] == marker) runEnd++
                val runStartsHere = i == 0 || text[i - 1] != marker
                val available = runEnd - i
                if (runStartsHere && (if (len == 1) available == 1 else available >= len)) return i
                i = runEnd
                continue
            }
            i++
        }
        return -1
    }

    private fun slot(marker: Char, len: Int): Int = when (marker) {
        '`' -> 0
        '*' -> if (len == 1) 1 else 2
        '_' -> if (len == 1) 3 else 4
        '~' -> 5
        else -> error("Unsupported inline delimiter")
    }

    private companion object {
        const val DELIMITER_CHARS = "`*_~"
    }
}

/** Reuses the active label/URL search frontier for the current bracket opener. */
internal class InlineLinkSearch(initialText: CharSequence) {
    private var text: CharSequence = initialText
    private var activeStart = -1
    private var labelClose = -1
    private var nestedLabelStart = -1
    private var failedLabelTo = -1
    private var urlFrom = -1
    private var urlClose = -1
    private var failedUrlTo = -1

    fun update(nextText: CharSequence, appendOnly: Boolean) {
        if (!appendOnly || nextText.length < text.length) {
            text = nextText
            clearSearch()
            return
        }
        if (nextText.length == text.length) {
            text = nextText
            return
        }

        var stableEnd = text.length
        while (stableEnd > 0 && text[stableEnd - 1] == '\\') stableEnd--
        if (failedLabelTo > stableEnd) failedLabelTo = stableEnd
        if (failedUrlTo > stableEnd) failedUrlTo = stableEnd
        text = nextText
    }

    /** Returns the first unescaped `]`, or -1 when the label is unfinished/invalid. */
    fun findLabelClose(start: Int, to: Int): Int {
        val useCache = prepareStart(start)
        if (useCache) {
            if (nestedLabelStart >= 0) return -1
            if (labelClose >= 0) return if (labelClose < to) labelClose else -1
            if (failedLabelTo >= 0) {
                if (to <= failedLabelTo) return -1
            }
        }

        var cursor = start + 1
        if (useCache && failedLabelTo >= 0) cursor = maxOf(cursor, failedLabelTo)
        while (cursor < to) {
            val c = text[cursor]
            if (c == '\\' && cursor + 1 < to) {
                cursor += 2
                continue
            }
            if (c == '[') {
                if (useCache) nestedLabelStart = cursor
                return -1
            }
            if (c == ']') {
                if (useCache) {
                    labelClose = cursor
                    failedLabelTo = -1
                }
                return cursor
            }
            cursor++
        }
        if (useCache) failedLabelTo = to
        return -1
    }

    /** Returns the first unescaped `)` in a link URL, or -1 while it is still streaming. */
    fun findUrlClose(start: Int, from: Int, to: Int): Int {
        val useCache = prepareStart(start)
        if (useCache && urlFrom != from) {
            urlFrom = from
            urlClose = -1
            failedUrlTo = -1
        }
        if (useCache) {
            if (urlClose >= 0) return if (urlClose < to) urlClose else -1
            if (failedUrlTo >= 0 && to <= failedUrlTo) return -1
        }

        var end = from
        if (useCache && failedUrlTo >= 0) end = maxOf(end, failedUrlTo)
        while (end < to) {
            val char = text[end]
            if (char == ')') {
                if (useCache) {
                    urlClose = end
                    failedUrlTo = -1
                }
                return end
            }
            if (char == '\\' && end + 1 < to) end++
            end++
        }
        if (useCache) failedUrlTo = to
        return -1
    }

    private fun prepareStart(start: Int): Boolean {
        if (start == activeStart) return true
        if (activeStart > start) return false
        activeStart = start
        labelClose = -1
        nestedLabelStart = -1
        failedLabelTo = -1
        urlFrom = -1
        urlClose = -1
        failedUrlTo = -1
        return true
    }

    private fun clearSearch() {
        activeStart = -1
        labelClose = -1
        nestedLabelStart = -1
        failedLabelTo = -1
        urlFrom = -1
        urlClose = -1
        failedUrlTo = -1
    }
}

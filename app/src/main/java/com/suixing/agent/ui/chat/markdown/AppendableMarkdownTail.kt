package com.suixing.agent.ui.chat.markdown

/** A parser-owned continuation, valid only while the complete source keeps its prefix. */
internal sealed interface AppendableMarkdownTail {
    fun append(source: String, previousLength: Int, checkpoint: (() -> Unit)?): MdBlock?

    class Paragraph(private val lastLineStart: Int, private val completedLines: String) : AppendableMarkdownTail {
        override fun append(source: String, previousLength: Int, checkpoint: (() -> Unit)?): MdBlock? {
            // A newline can turn the preceding pipe-containing line into a table header,
            // or introduce a new block. Let the full parser resolve that boundary.
            // A new pipe may also let a preceding table absorb this formerly plain
            // line, even before a newline arrives (optional outer-pipe syntax).
            if (source.indexOf('\n', previousLength) >= 0 || source.indexOf('|', previousLength) >= 0) return null
            val lastLine = source.substring(lastLineStart).trim()
            return MdBlock.Paragraph(if (completedLines.isEmpty()) lastLine else completedLines + lastLine)
        }
    }

    class Code(
        private val language: String,
        private val bodyStart: Int,
        private var lastLineStart: Int,
        private var lastLineIsBody: Boolean,
    ) : AppendableMarkdownTail {
        override fun append(source: String, previousLength: Int, checkpoint: (() -> Unit)?): MdBlock? {
            // A line already containing a non-backtick cannot become a closing fence
            // by appending. Only newly introduced lines or a pending fence need checking.
            var start = lastLineStart
            if (lastLineIsBody) {
                val newline = source.indexOf('\n', previousLength)
                if (newline < 0) return MdBlock.Code(language, source.substring(bodyStart))
                start = newline + 1
            }
            while (true) {
                checkpoint?.invoke()
                val newline = source.indexOf('\n', start)
                val end = if (newline < 0) source.length else newline
                val line = source.substring(start, end).trim()
                if (line.length >= 3 && line.all { it == '`' }) return null
                if (newline < 0) {
                    lastLineStart = start
                    lastLineIsBody = line.any { it != '`' }
                    return MdBlock.Code(language, source.substring(bodyStart))
                }
                start = newline + 1
            }
        }
    }
    class Table(
        private val header: List<String>,
        completedRows: List<List<String>>,
        private var lastLineStart: Int,
        private var partialCells: List<String>?,
    ) : AppendableMarkdownTail {
        // Own the append-only prefix. Published results capture a fixed count, so completing
        // another row does not copy all earlier row references or extend an older snapshot.
        private val completedRows = ArrayList(completedRows)

        override fun append(source: String, previousLength: Int, checkpoint: (() -> Unit)?): MdBlock? {
            var start = lastLineStart
            var additions: ArrayList<List<String>>? = null
            while (true) {
                val newline = source.indexOf('\n', start)
                val end = if (newline < 0) source.length else newline
                val row = source.substring(start, end).trim()
                if (row.isEmpty()) {
                    // A complete empty line ends the table; an unfinished empty final
                    // line can still grow into another row on the next publication.
                    if (newline >= 0) return null
                    if (additions != null) completedRows.addAll(additions)
                    lastLineStart = start
                    partialCells = null
                    return MdBlock.Table(header, CompletedTableRows(completedRows, completedRows.size, null))
                }
                if ('|' !in row) return null
                val cells = if (start == lastLineStart && end == previousLength && partialCells != null) {
                    requireNotNull(partialCells)
                } else splitTableRow(row, header.size)
                if (newline < 0) {
                    if (additions != null) completedRows.addAll(additions)
                    lastLineStart = start
                    partialCells = cells
                    return MdBlock.Table(header, CompletedTableRows(completedRows, completedRows.size, cells))
                }
                checkpoint?.invoke()
                val completed = additions ?: ArrayList<List<String>>().also { additions = it }
                completed.add(cells)
                start = newline + 1
            }
        }
    }
}

/** A new final row does not copy or replace completed row identities. */
private class CompletedTableRows(
    private val prefix: List<List<String>>,
    private val completedCount: Int,
    private val last: List<String>?,
) : AbstractList<List<String>>() {
    override val size: Int get() = completedCount + if (last == null) 0 else 1
    override fun get(index: Int): List<String> {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        return if (index < completedCount) prefix[index] else requireNotNull(last)
    }
}

/** These first characters may still become a block marker as the current line grows. */
internal fun ordinaryParagraphLine(line: String): Boolean {
    val first = line.firstOrNull { !it.isWhitespace() } ?: return false
    return first !in "#`>-*+_|:" && !first.isDigit()
}

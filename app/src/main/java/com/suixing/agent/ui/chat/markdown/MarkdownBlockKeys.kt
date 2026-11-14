package com.suixing.agent.ui.chat.markdown

private data class SettledMarkdownKey(val block: MdBlock, val occurrence: Int) {
    private val cachedHashCode = 31 * block.hashCode() + occurrence
    override fun hashCode(): Int = cachedHashCode
}
private data class TailMarkdownKey(val kind: kotlin.reflect.KClass<out MdBlock>)
private data class TableMarkdownKey(val header: List<String>, val occurrence: Int)

/** Reuse parser-retained prefixes while preserving content-based identities after edits. */
internal class MarkdownBlockKeyCache {
    private val prefixBlocks = ArrayList<MdBlock>()
    private val prefixKeys = ArrayList<Any>()
    private val occurrences = HashMap<MdBlock, Int>()
    private val tables = HashMap<List<String>, Int>()

    fun keysFor(blocks: List<MdBlock>): List<Any> {
        val prefixSize = (blocks.size - 1).coerceAtLeast(0)
        if (prefixSize < prefixBlocks.size ||
            prefixBlocks.indices.any { prefixBlocks[it] !== blocks[it] }
        ) {
            prefixBlocks.clear()
            prefixKeys.clear()
            occurrences.clear()
            tables.clear()
        }
        for (index in prefixBlocks.size until prefixSize) {
            val block = blocks[index]
            prefixKeys.add(keyFor(block, tail = false))
            prefixBlocks.add(block)
        }
        return ArrayList<Any>(blocks.size).apply {
            addAll(prefixKeys)
            blocks.lastOrNull()?.let { add(keyFor(it, tail = true)) }
        }
    }

    private fun keyFor(block: MdBlock, tail: Boolean): Any {
        // A table's rows grow after its header. The same identity also survives
        // becoming a settled prefix, retaining its inner viewport and follow flag.
        // Hash only its header, never every body cell on each stream publication.
        return if (block is MdBlock.Table) {
            val occurrence = tables[block.header] ?: 0
            if (!tail) tables[block.header] = occurrence + 1
            TableMarkdownKey(block.header, occurrence)
        } else if (tail) TailMarkdownKey(block::class)
        else {
            val occurrence = occurrences[block] ?: 0
            occurrences[block] = occurrence + 1
            SettledMarkdownKey(block, occurrence)
        }
    }
}

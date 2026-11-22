package com.suixing.agent.ui.chat.markdown

private data class SettledMarkdownKey(val block: MdBlock, val occurrence: Int) {
    private val cachedHashCode = 31 * block.hashCode() + occurrence
    override fun hashCode(): Int = cachedHashCode
}
private data class TailMarkdownKey(val kind: kotlin.reflect.KClass<out MdBlock>)
private data class TableMarkdownKey(val header: List<String>, val occurrence: Int)

internal fun sharedCompletedMarkdownBlocks(previous: List<MdBlock>, next: List<MdBlock>): Int {
    fun List<MdBlock>.completedPrefix(): CompletedMarkdownPrefix? = when (this) {
        is CompletedMarkdownPrefix -> this
        is JoinedMarkdownBlocks -> prefix.completedPrefix()
        else -> null
    }
    val before = previous.completedPrefix() ?: return 0
    val after = next.completedPrefix() ?: return 0
    return before.sharedSize(after)
}

/** Reuse parser-retained prefixes while preserving content-based identities after edits. */
internal class MarkdownBlockKeyCache {
    private var previous = emptyList<MdBlock>()
    private val prefixBlocks = ArrayList<MdBlock>()
    private var prefixKeys = ArrayList<Any>()
    private val occurrences = HashMap<MdBlock, Int>()
    private val tables = HashMap<List<String>, Int>()

    fun keysFor(blocks: List<MdBlock>): List<Any> {
        val prefixSize = (blocks.size - 1).coerceAtLeast(0)
        val shared = sharedCompletedMarkdownBlocks(previous, blocks)
        if (prefixSize < prefixBlocks.size ||
            (shared until prefixBlocks.size).any { prefixBlocks[it] !== blocks[it] }
        ) {
            prefixBlocks.clear()
            // Older key snapshots still own the previous list.
            prefixKeys = ArrayList()
            occurrences.clear()
            tables.clear()
        }
        for (index in prefixBlocks.size until prefixSize) {
            val block = blocks[index]
            prefixKeys.add(keyFor(block, tail = false))
            prefixBlocks.add(block)
        }
        previous = blocks
        val tail = blocks.lastOrNull() ?: return emptyList()
        return MarkdownKeySnapshot(prefixKeys, prefixSize, keyFor(tail, tail = true))
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

/** The backing prefix only appends; a snapshot exposes its original length and tail. */
private class MarkdownKeySnapshot(
    private val prefix: List<Any>,
    private val prefixSize: Int,
    private val tail: Any,
) : AbstractList<Any>(), RandomAccess {
    override val size: Int get() = prefixSize + 1

    override fun get(index: Int): Any = when {
        index == prefixSize -> tail
        index in 0 until prefixSize -> prefix[index]
        else -> throw IndexOutOfBoundsException("index=$index, size=$size")
    }
}

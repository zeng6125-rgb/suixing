package com.suixing.agent.ui.chat.markdown

private data class SettledMarkdownKey(val block: MdBlock, val occurrence: Int)
private data class TailMarkdownKey(val kind: kotlin.reflect.KClass<out MdBlock>)
private data class TableMarkdownKey(val header: List<String>, val occurrence: Int)

/** Stable settled blocks survive insertion; the growing tail retains its state as text arrives. */
internal fun markdownBlockKeys(blocks: List<MdBlock>): List<Any> {
    val occurrences = HashMap<MdBlock, Int>()
    val tables = HashMap<List<String>, Int>()
    return blocks.mapIndexed { index, block ->
        // A table's rows grow after its header. The same identity also survives
        // becoming a settled prefix, retaining its inner viewport and follow flag.
        // Hash only its header, never every body cell on each stream publication.
        if (block is MdBlock.Table) {
            val occurrence = tables[block.header] ?: 0
            tables[block.header] = occurrence + 1
            TableMarkdownKey(block.header, occurrence)
        } else if (index == blocks.lastIndex) TailMarkdownKey(block::class)
        else {
            val occurrence = occurrences[block] ?: 0
            occurrences[block] = occurrence + 1
            SettledMarkdownKey(block, occurrence)
        }
    }
}

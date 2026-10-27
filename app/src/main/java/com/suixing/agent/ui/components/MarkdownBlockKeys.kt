package com.suixing.agent.ui.components

private data class SettledMarkdownKey(val block: MdBlock, val occurrence: Int)
private data class TailMarkdownKey(val kind: kotlin.reflect.KClass<out MdBlock>)

/** Stable settled blocks survive insertion; the growing tail retains its state as text arrives. */
internal fun markdownBlockKeys(blocks: List<MdBlock>): List<Any> {
    val occurrences = HashMap<MdBlock, Int>()
    return blocks.mapIndexed { index, block ->
        if (index == blocks.lastIndex) TailMarkdownKey(block::class)
        else {
            val occurrence = occurrences[block] ?: 0
            occurrences[block] = occurrence + 1
            SettledMarkdownKey(block, occurrence)
        }
    }
}

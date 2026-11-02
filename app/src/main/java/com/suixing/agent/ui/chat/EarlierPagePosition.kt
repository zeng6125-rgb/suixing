package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import com.suixing.agent.data.StoredMessage

/** Wait for both the larger UI window and (when needed) its asynchronously read prefix. */
internal class EarlierPageReveal(
    val shownTurns: Int,
    private val previousHead: StoredMessage?,
) {
    fun isReady(currentShownTurns: Int, currentHead: StoredMessage?): Boolean =
        currentShownTurns >= shownTurns && (previousHead == null || currentHead !== previousHead)
}

internal data class EarlierPageTarget(val index: Int, val scrollOffset: Int)

/**
 * Resolve an old, still-laid-out message against the incoming keyed projection. The paging
 * header is deliberately absent from [incomingIndices]: keeping that header in place would
 * replace the messages being read with the newly inserted page.
 */
internal fun earlierPageTarget(
    visibleItems: List<LazyListItemInfo>,
    incomingIndices: Map<Any, Int>,
): EarlierPageTarget? {
    for (item in visibleItems) {
        val index = incomingIndices[item.key] ?: continue
        // LazyList's item offset and scroll offset have opposite signs. Both use the
        // content origin, so viewportStartOffset (negative top padding) is not added.
        return EarlierPageTarget(index, -item.offset)
    }
    return null
}

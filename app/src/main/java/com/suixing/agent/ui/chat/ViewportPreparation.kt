package com.suixing.agent.ui.chat

import com.suixing.agent.ui.chat.markdown.SettledBlocks

/** Select a bounded old-to-new row window using the requested lazy-list orientation. */
internal fun <T> nearbyViewportRows(
    rows: List<T>,
    feedPrefix: Int,
    firstVisible: Int,
    lastVisible: Int,
    margin: Int = 4,
    reverseLayout: Boolean = true,
): List<T> {
    if (rows.isEmpty() || firstVisible > lastVisible) return emptyList()
    val firstFeed = (firstVisible - feedPrefix - margin).coerceAtLeast(0)
    val lastFeed = (lastVisible - feedPrefix + margin).coerceAtMost(rows.lastIndex)
    if (lastFeed < firstFeed) return emptyList()
    return if (reverseLayout) rows.subList(rows.lastIndex - lastFeed, rows.size - firstFeed)
    else rows.subList(firstFeed, lastFeed + 1)
}

/** Convert a legacy bottom-relative intra-row offset after the row has been measured. */
internal fun legacyReadingOffset(offset: Int, rowHeight: Int, readingHeight: Int): Int =
    (rowHeight.toLong() - readingHeight - offset).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal fun <T> openingViewportRows(rows: List<T>, anchorIndex: Int): List<T> {
    if (rows.isEmpty()) return emptyList()
    val center = anchorIndex.coerceIn(0, rows.lastIndex)
    return rows.subList((center - 3).coerceAtLeast(0), (center + 4).coerceAtMost(rows.size))
}

/**
 * Return this row's text parts when markdown preparation may be needed.
 *
 * A missing row cache submits all of its text parts, including short ones. An existing cache
 * only needs a list when a settled-length text is absent; `null` means every such text is cached.
 */
internal fun markdownTextsToPrepare(
    parts: List<Part>,
    current: Map<String, *>?,
): List<String>? {
    if (current == null) return parts.mapNotNull { (it as? Part.Text)?.text }

    for (part in parts) {
        if (part is Part.Text &&
            part.text.length >= SettledBlocks.MIN_CHARS &&
            part.text !in current
        ) {
            return parts.mapNotNull { (it as? Part.Text)?.text }
        }
    }
    return null
}

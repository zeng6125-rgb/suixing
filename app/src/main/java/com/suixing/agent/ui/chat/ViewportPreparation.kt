package com.suixing.agent.ui.chat

import com.suixing.agent.ui.chat.markdown.SettledBlocks
import com.suixing.agent.ui.chat.markdown.MessageSegment
import com.suixing.agent.ui.chat.markdown.splitImageSegments

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
    imageSegments: Map<String, List<MessageSegment>> = emptyMap(),
): List<String>? {
    val texts = buildList {
        for (part in parts) if (part is Part.Text) {
            if (!(part.hasImageMarker ?: part.text.contains("!["))) add(part.text) else {
                val segments = imageSegments[part.text] ?: splitImageSegments(part.text)
                if (segments.any { it is MessageSegment.Image }) {
                    segments.forEach { if (it is MessageSegment.Text && it.text.isNotBlank()) add(it.text) }
                } else add(part.text)
            }
        }
    }
    return texts.takeIf { current == null || texts.any { it.length >= SettledBlocks.MIN_CHARS && it !in current } }
}

internal fun imageSegmentsToPrepare(
    parts: List<Part>,
    current: Map<String, List<MessageSegment>> = emptyMap(),
): Map<String, List<MessageSegment>> = buildMap {
    for (part in parts) if (part is Part.Text && (part.hasImageMarker ?: part.text.contains("!["))) {
        put(part.text, current[part.text] ?: splitImageSegments(part.text))
    }
}

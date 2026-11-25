package com.suixing.agent.ui.chat

import com.suixing.agent.ui.chat.markdown.MdBlock
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

internal sealed interface RowMarkdownPlan {
    data object Cached : RowMarkdownPlan

    class Ready(
        val documents: Map<String, List<MdBlock>>,
        val segments: Map<String, List<MessageSegment>>,
    ) : RowMarkdownPlan

    data object NeedsBackground : RowMarkdownPlan
}

private inline fun forEachRenderedText(
    part: Part.Text,
    segments: List<MessageSegment>?,
    onText: (String) -> Unit,
) {
    if (segments != null && segments.any { it is MessageSegment.Image }) {
        for (segment in segments) {
            if (segment is MessageSegment.Text && segment.text.isNotBlank()) onText(segment.text)
        }
    } else {
        onText(part.text)
    }
}

private fun cachedSegmentKeysMatch(
    parts: List<Part>,
    cachedSegments: Map<String, List<MessageSegment>>,
): Boolean {
    if (cachedSegments.size <= 1) return true
    return cachedSegments.keys.all { key ->
        parts.any { it is Part.Text && it.text == key && (it.hasImageMarker ?: it.text.contains("![")) }
    }
}

private fun partsNeedMarkdownPreparation(
    parts: List<Part>,
    current: Map<String, *>,
    imageSegments: Map<String, List<MessageSegment>>,
): Boolean {
    for (part in parts) {
        if (part !is Part.Text) continue
        val hasMarker = part.hasImageMarker ?: part.text.contains("![")
        val segments = if (hasMarker) imageSegments[part.text] ?: return true else null
        var missing = false
        forEachRenderedText(part, segments) { segmentText ->
            if (segmentText.length >= SettledBlocks.MIN_CHARS && segmentText !in current) {
                missing = true
            }
        }
        if (missing) return true
    }
    return false
}

internal inline fun planRowMarkdown(
    parts: List<Part>,
    currentDocuments: Map<String, List<MdBlock>>?,
    cachedSegments: Map<String, List<MessageSegment>>,
    resolveSettled: (String) -> List<MdBlock>?,
): RowMarkdownPlan {
    var matchedMarkers = 0
    var needsPublish = currentDocuments == null
    var resolved: HashMap<String, List<MdBlock>>? = null
    for (part in parts) {
        if (part !is Part.Text) continue
        val hasMarker = part.hasImageMarker ?: part.text.contains("![")
        val segments = if (hasMarker) {
            matchedMarkers++
            cachedSegments[part.text] ?: return RowMarkdownPlan.NeedsBackground
        } else {
            null
        }
        forEachRenderedText(part, segments) { segmentText ->
            if (segmentText.length >= SettledBlocks.MIN_CHARS && currentDocuments?.get(segmentText) == null) {
                if (currentDocuments != null && currentDocuments.isNotEmpty()) return RowMarkdownPlan.NeedsBackground
                needsPublish = true
                val settled = resolveSettled(segmentText) ?: return RowMarkdownPlan.NeedsBackground
                val map = resolved ?: HashMap<String, List<MdBlock>>(2).also { resolved = it }
                map[segmentText] = settled
            }
        }
    }
    if (matchedMarkers == 0 && cachedSegments.isNotEmpty()) return RowMarkdownPlan.NeedsBackground
    if (matchedMarkers > 0 && cachedSegments.size > 1 && !cachedSegmentKeysMatch(parts, cachedSegments)) {
        return RowMarkdownPlan.NeedsBackground
    }
    if (!needsPublish) return RowMarkdownPlan.Cached
    return RowMarkdownPlan.Ready(resolved ?: emptyMap(), cachedSegments)
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
    if (current != null && !partsNeedMarkdownPreparation(parts, current, imageSegments)) return null
    val texts = buildList {
        for (part in parts) if (part is Part.Text) {
            val segments = if (part.hasImageMarker ?: part.text.contains("![")) {
                imageSegments[part.text] ?: splitImageSegments(part.text)
            } else {
                null
            }
            forEachRenderedText(part, segments) { add(it) }
        }
    }
    return texts.takeIf { current == null || texts.any { it.length >= SettledBlocks.MIN_CHARS && it !in current } }
}

internal fun imageSegmentsToPrepare(
    parts: List<Part>,
    current: Map<String, List<MessageSegment>> = emptyMap(),
): Map<String, List<MessageSegment>> {
    var result: LinkedHashMap<String, List<MessageSegment>>? = null
    var matchedMarkers = 0
    for (part in parts) {
        if (part !is Part.Text || !(part.hasImageMarker ?: part.text.contains("!["))) continue
        val cached = current[part.text]
        if (cached == null) {
            val built = result ?: LinkedHashMap<String, List<MessageSegment>>(current.size + 1).apply {
                for (prior in parts) {
                    if (prior === part) break
                    if (prior is Part.Text && (prior.hasImageMarker ?: prior.text.contains("!["))) {
                        current[prior.text]?.let { put(prior.text, it) }
                    }
                }
                result = this
            }
            built[part.text] = splitImageSegments(part.text)
        } else {
            matchedMarkers++
            result?.put(part.text, cached)
        }
    }
    val built = result
    if (built != null) return built
    if (matchedMarkers == 0) return emptyMap()
    if (cachedSegmentKeysMatch(parts, current)) return current
    return buildMap {
        for (part in parts) {
            if (part is Part.Text && (part.hasImageMarker ?: part.text.contains("!["))) {
                current[part.text]?.let { put(part.text, it) }
            }
        }
    }
}

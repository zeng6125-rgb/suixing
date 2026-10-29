package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalTranscriptPosition = compositionLocalOf<TranscriptPosition?> { null }
internal val LocalReadingTextKey = compositionLocalOf<String?> { null }
internal val LocalReadingRowKey = compositionLocalOf<String?> { null }

/** Layout owns positioning. No coroutine observes a drawn frame and then pulls it back. */
internal class TranscriptPosition(private val list: LazyListState) {
    var following: () -> Boolean = { false }
    var reading: () -> Boolean = { false }
    private var invalidate: (() -> Unit)? = null
    private var top = 0f
    private var bottom = 0f
    private val regions = LinkedHashMap<Any, TextRegion>()
    private val visibleRows = HashSet<Any>()
    private var anchor: ReadingPoint? = null
    private var lastRepair: Triple<Int, Int, Int>? = null
    private var userChangeUntil = 0L
    private var userTransitions = 0

    fun pauseForUserChange() {
        userChangeUntil = android.os.SystemClock.uptimeMillis() + PROCESS_EXPAND_MS + 48L
        anchor = null
        lastRepair = null
    }

    fun beginRevealTransition(): Boolean {
        if (android.os.SystemClock.uptimeMillis() >= userChangeUntil && userTransitions == 0) return false
        userTransitions++
        return true
    }

    fun endRevealTransition() {
        userTransitions = (userTransitions - 1).coerceAtLeast(0)
        if (userTransitions == 0) userChangeUntil = android.os.SystemClock.uptimeMillis() + 32L
        anchor = null
        lastRepair = null
    }

    private fun canRead(): Boolean = reading() && userTransitions == 0 &&
        android.os.SystemClock.uptimeMillis() >= userChangeUntil

    fun attach(invalidate: () -> Unit) { this.invalidate = invalidate }
    fun detach() { invalidate = null; regions.clear(); visibleRows.clear(); anchor = null }

    fun viewportPlaced(coordinates: LayoutCoordinates) {
        val layout = list.layoutInfo
        val viewportY = coordinates.positionInRoot().y
        top = viewportY + layout.beforeContentPadding
        bottom = viewportY + layout.viewportSize.height - layout.afterContentPadding
        if (following()) {
            anchor = null
            if (list.canScrollForward && layout.totalItemsCount > 0) {
                list.requestScrollToItem(layout.totalItemsCount - 1, TRANSCRIPT_END_OFFSET)
                // requestScrollToItem's snapshot notification alone may be delivered
                // later. Explicitly enqueue a new measure during placement, which the
                // owner's measure/layout pass drains before drawing this frame.
                invalidate?.invoke()
            }
        } else if (!canRead()) anchor = null
    }

    fun interactionChanged() {
        anchor = null
        lastRepair = null
        if (canRead()) capture()
        invalidate?.invoke()
    }

    private fun capture() {
        val probe = top + 24f
        visibleRows.clear()
        for (item in list.layoutInfo.visibleItemsInfo) visibleRows.add(item.key)
        var nearest: TextRegion? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        for (candidate in regions.values) {
            if (candidate.rowKey !in visibleRows || !candidate.coordinates.isAttached) continue
            val bounds = candidate.coordinates.boundsInRoot()
            if (bounds.height <= 0f || bounds.top >= bottom || bounds.bottom <= top) continue
            val y = candidate.coordinates.positionInRoot().y
            val distance = if (probe >= y && probe <= y + candidate.coordinates.size.height) 0f else abs(y - probe)
            if (distance < nearestDistance) {
                nearest = candidate
                nearestDistance = distance
                if (distance == 0f) break
            }
        }
        val region = nearest ?: return
        val result = region.layout() ?: return
        if (result.lineCount == 0) return
        val y = region.coordinates.positionInRoot().y
        val line = result.getLineForVerticalPosition((probe - y).coerceAtLeast(0f))
        val offset = result.getLineStart(line)
        val text = result.layoutInput.text.text
        anchor = ReadingPoint(region.key, offset, text.substring(offset, (offset + 32).coerceAtMost(text.length)),
            y + result.getLineTop(line))
    }

    fun textPlaced(key: Any, rowKey: String, coordinates: LayoutCoordinates, layout: () -> TextLayoutResult?) {
        // Placement runs again during scrolling even when the text is unchanged.
        // Retain its record rather than allocate one for every cell on every pass.
        val region = regions[key]
        if (region == null) {
            regions[key] = TextRegion(key, rowKey, coordinates, layout)
        } else {
            region.rowKey = rowKey
            region.coordinates = coordinates
            region.layout = layout
        }
    }

    /** Resolve a single anchor only after every descendant has its final position. */
    fun contentPlaced() {
        if (!canRead()) return
        if (anchor == null) capture()
        val point = anchor ?: return
        val region = regions[point.key] ?: return
        val coordinates = region.coordinates
        if (!coordinates.isAttached || list.layoutInfo.visibleItemsInfo.none { it.key == region.rowKey }) return
        val textLayout = region.layout() ?: return
        if (textLayout.lineCount == 0) return
        val offset = readingCharacterOffset(textLayout.layoutInput.text.text, point.offset, point.sample)
        if (point.sample.isNotEmpty() &&
            !textLayout.layoutInput.text.text.regionMatches(offset, point.sample, 0, point.sample.length)) {
            // Parsing may replace/repartition this block under the same ordinal.
            // Its old character offset no longer identifies the reader's text.
            // Drop that point instead of pulling unrelated content to the old Y.
            anchor = null
            lastRepair = null
            return
        }
        val y = coordinates.positionInRoot().y + textLayout.getLineTop(textLayout.getLineForOffset(offset))
        val delta = (y - point.y).roundToInt()
        if (delta == 0) { lastRepair = null; return }
        // The first lazy item may change after prepend. Use this pass's current
        // index/offset, rather than retaining an obsolete index with the text point.
        val target = readingScrollOffset(list.firstVisibleItemScrollOffset, delta)
        val repair = Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, target)
        // A changed/shortened document can make the old point physically unreachable.
        // Never keep invalidating at an end clamp when another pass cannot improve it.
        if (target == list.firstVisibleItemScrollOffset || repair == lastRepair) return
        lastRepair = repair
        list.requestScrollToItem(list.firstVisibleItemIndex, target)
        invalidate?.invoke()
    }

    fun remove(key: Any) {
        regions.remove(key)
        // Keep the semantic point through a live/stored or process/tail remount.
        // The replacement region uses the same run/part/block key.
    }

    private class TextRegion(val key: Any, var rowKey: String, var coordinates: LayoutCoordinates, var layout: () -> TextLayoutResult?)
    private class ReadingPoint(val key: Any, val offset: Int, val sample: String, val y: Float)
}

/** Formatting can remove literal markup before a previously selected character. */
internal fun readingCharacterOffset(text: String, offset: Int, sample: String): Int {
    if (sample.isNotEmpty()) {
        if (offset >= 0 && text.regionMatches(offset, sample, 0, sample.length)) return offset
        val from = (offset - 256).coerceAtLeast(0).coerceAtMost(text.length)
        var found = text.indexOf(sample, from)
        var closest = -1
        while (found >= 0 && found <= offset + 256) {
            if (closest < 0 || abs(found - offset) < abs(closest - offset)) closest = found
            found = text.indexOf(sample, found + 1)
        }
        if (closest >= 0) return closest
    }
    return offset.coerceIn(0, text.length)
}

internal fun readingScrollOffset(offset: Int, delta: Int): Int =
    (offset.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal fun Modifier.transcriptPosition(owner: TranscriptPosition): Modifier = this then TranscriptElement(owner)

@Composable
internal fun Modifier.readingText(layout: () -> TextLayoutResult?): Modifier {
    val owner = LocalTranscriptPosition.current ?: return this
    val key = LocalReadingTextKey.current ?: return this
    val rowKey = LocalReadingRowKey.current ?: return this
    DisposableEffect(owner, key) { onDispose { owner.remove(key) } }
    return onPlaced { owner.textPlaced(key, rowKey, it, layout) }
}

private data class TranscriptElement(val owner: TranscriptPosition) : ModifierNodeElement<TranscriptNode>() {
    override fun create() = TranscriptNode(owner)
    override fun update(node: TranscriptNode) { node.bind(owner) }
    override fun InspectorInfo.inspectableProperties() { name = "transcriptPosition" }
}

private class TranscriptNode(var owner: TranscriptPosition) : Modifier.Node(), LayoutModifierNode, LayoutAwareModifierNode {
    fun bind(next: TranscriptPosition) {
        if (owner === next) return
        if (isAttached) owner.detach()
        owner = next
        if (isAttached) owner.attach { invalidateMeasurement() }
    }
    override fun onAttach() { owner.attach { invalidateMeasurement() } }
    override fun onDetach() { owner.detach() }
    override fun onPlaced(coordinates: LayoutCoordinates) { owner.viewportPlaced(coordinates) }
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            child.place(0, 0)
            owner.contentPlaced()
        }
    }
}

package com.suixing.agent.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.suixing.agent.core.Core
import com.suixing.agent.data.Roles
import com.suixing.agent.data.ViewportBookmark
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Keep the live bubble's identity when its grouped, settled answer replaces it. */
internal fun liveRowKey(chatId: String, runId: String) = "run:$chatId:$runId"

internal fun presentationKeys(chatId: String, rows: List<ChatRow>): List<String> {
    val lastByRun = HashMap<String, Int>()
    rows.forEachIndexed { index, row ->
        if (row.message.role == Roles.ASSISTANT && !row.message.runId.isNullOrEmpty()) {
            lastByRun[row.message.runId] = index
        }
    }
    val keys = rows.mapIndexed { index, row ->
        val run = row.message.runId.orEmpty()
        if (run.isNotEmpty() && lastByRun[run] == index) liveRowKey(chatId, run)
        else "message:$chatId:${row.rounds.firstOrNull()?.id ?: row.message.id}"
    }.toMutableList()
    // Legacy transcripts may contain repeated message IDs. Count from the newest end so
    // prepending an older page cannot rename an already-mounted occurrence.
    val occurrences = HashMap<String, Int>()
    for (index in keys.indices.reversed()) {
        val base = keys[index]
        val occurrence = occurrences[base] ?: 0
        occurrences[base] = occurrence + 1
        if (occurrence > 0) keys[index] = "duplicate:$occurrence:$base"
    }
    return keys
}

internal fun bookmarkRow(bookmark: ViewportBookmark?, keys: List<String>, rows: List<ChatRow>): Int {
    if (bookmark == null || bookmark.followLatest) return -1
    keys.indexOf(bookmark.rowKey).takeIf { it >= 0 }?.let { return it }
    val matching = rows.indexOfLast { row ->
        bookmark.messageId != null && (row.message.id == bookmark.messageId || row.rounds.any { it.id == bookmark.messageId }) ||
            bookmark.runId != null && row.message.runId == bookmark.runId
    }
    if (matching >= 0) return matching
    for (id in bookmark.nearbyMessageIds) {
        rows.indexOfFirst { row -> row.message.id == id || row.rounds.any { it.id == id } }
            .takeIf { it >= 0 }?.let { return it }
    }
    return -1
}

@OptIn(ExperimentalFoundationApi::class)
internal class ConversationViewport(val conversationId: String, val bookmark: ViewportBookmark?) {
    // Two screens ahead, one behind: bounded reuse instead of retaining whole transcripts.
    val listState = LazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 1f))
    // One target transcript, with opacity finishing before its small spatial settle. Reads
    // stay in graphicsLayer; changing this clock never recomposes or remeasures the rows.
    val entrance = Animatable(0f)
    val navigation = NavigationMotion()
    var prepared by mutableStateOf(false)
    var presented by mutableStateOf(false)
    var followOverride by mutableStateOf(false)
}

internal object ConversationEntrance {
    const val DURATION_MS = 300
    const val TRAVEL_DP = 18f
    private val fade = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    // Opacity settles in 100ms. A critically damped spatial response then eases
    // into its resting place, with no bounce or scaling of already-readable text.
    fun alpha(progress: Float): Float = fade.transform((progress * 3f).coerceIn(0f, 1f))
    fun remainingTravel(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val end = 1f - 9f * exp(-8f)
        return (1f - (1f - (1f + 8f * t) * exp(-8f * t)) / end).coerceIn(0f, 1f)
    }
}

/** A new row projection matters even when a streamed/edited row keeps its list index. */
private class NearbyPreparation(
    val rows: List<ChatRow>,
    val revision: Long,
    val prefix: Int,
    val first: Int,
    val last: Int,
) {
    override fun equals(other: Any?): Boolean = other is NearbyPreparation &&
        rows === other.rows && revision == other.revision && prefix == other.prefix &&
        first == other.first && last == other.last

    override fun hashCode(): Int = 31 * (31 * (31 * (31 * System.identityHashCode(rows) +
        revision.hashCode()) + prefix) + first) + last
}

/** Bound under key(conversationId): disposal cannot read the next chat's providers. */
@Composable
internal fun BindConversationViewport(
    viewport: ConversationViewport,
    viewModel: ChatViewModel,
    scroll: ChatScroll,
    rows: List<ChatRow>,
    keys: List<String>,
    keyIndices: Map<String, Int>,
    feedPrefix: Int,
    itemCount: Int,
    shownTurns: Int,
    totalTurns: Int,
    fontScale: Float,
    storageReady: Boolean,
    messagesRevision: Long,
    prepare: suspend (List<ChatRow>) -> Unit,
) {
    val rowsNow = rememberUpdatedState(rows)
    val keysNow = rememberUpdatedState(keys)
    val keyIndicesNow = rememberUpdatedState(keyIndices)
    val prefixNow = rememberUpdatedState(feedPrefix)
    val countNow = rememberUpdatedState(itemCount)
    val turnsNow = rememberUpdatedState(shownTurns)
    val totalNow = rememberUpdatedState(totalTurns)
    val fontNow = rememberUpdatedState(fontScale)
    val prepareNow = rememberUpdatedState(prepare)
    val revisionNow = rememberUpdatedState(messagesRevision)
    val expectedWidth = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val owner = LocalLifecycleOwner.current

    val capture = remember(viewport, scroll) {
        {
            if (viewport.presented && !scroll.restoring && viewport.conversationId.isNotBlank()) {
                val list = viewport.listState
                val first = list.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.key in keyIndicesNow.value ||
                        (it.key as? String)?.startsWith("run:${viewport.conversationId}:") == true
                }
                if (first != null) {
                    val key = first.key as? String
                    val rowIndex = keyIndicesNow.value[key] ?: -1
                    val row = rowsNow.value.getOrNull(rowIndex)
                    val nearby = if (rowIndex >= 0) listOfNotNull(rowsNow.value.getOrNull(rowIndex - 1)?.message?.id,
                        rowsNow.value.getOrNull(rowIndex + 1)?.message?.id)
                        else rowsNow.value.takeLast(2).map { it.message.id }
                    val liveRun = key?.takeIf { it.startsWith("run:${viewport.conversationId}:") }
                        ?.removePrefix("run:${viewport.conversationId}:")
                    Core.conversationViewports.save(ViewportBookmark(
                        conversationId = viewport.conversationId,
                        rowKey = key,
                        messageId = row?.message?.id,
                        runId = row?.message?.runId?.takeIf { it.isNotBlank() } ?: liveRun,
                        nearbyMessageIds = nearby,
                        offset = if (first.index == list.firstVisibleItemIndex)
                            list.firstVisibleItemScrollOffset else -first.offset,
                        followLatest = scroll.stickToBottom,
                        shownTurns = turnsNow.value,
                        totalTurns = totalNow.value,
                        width = list.layoutInfo.viewportSize.width,
                        fontScale = fontNow.value,
                        topAnchored = true,
                    ))
                }
            }
        }
    }
    DisposableEffect(viewport, owner, viewModel) {
        viewModel.bindViewport(viewport.conversationId, capture)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) capture()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            capture()
            owner.lifecycle.removeObserver(observer)
            viewModel.unbindViewport(capture)
        }
    }

    LaunchedEffect(viewport, storageReady) {
        if (!storageReady || viewport.conversationId.isBlank()) return@LaunchedEffect
        scroll.restoring = true
        viewModel.state.first { !it.loading && it.conversationId == viewport.conversationId }
        val bookmark = viewport.bookmark
        // A cold tail may not include a saved historical message. Read earlier pages without
        // ever displaying an intermediate/default position. Existing repository guards apply.
        if (bookmark != null && !bookmark.followLatest) {
            while (true) {
                if (viewport.followOverride) break
                val state = viewModel.state.value
                if (state.conversationId != viewport.conversationId) return@LaunchedEffect
                val found = state.messages.any { it.id == bookmark.messageId ||
                    bookmark.runId != null && it.runId == bookmark.runId || it.id in bookmark.nearbyMessageIds }
                if (found || bookmark.messageId == null && bookmark.runId == null ||
                    bookmark.runId == state.liveRunId || state.messages.isEmpty() ||
                    state.messageCount in 1..state.messages.size) break
                val added = suspendCancellableCoroutine { continuation ->
                    val accepted = viewModel.loadEarlier(onComplete = { result ->
                        if (continuation.isActive) continuation.resume(result)
                    })
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
                if (!added) break
            }
        }
        // The derived row projection commits with the screen; wait for that exact source revision.
        snapshotFlow { revisionNow.value }.first { it == viewModel.screenState.value.messagesRevision }
        var target = bookmarkRow(bookmark, keysNow.value, rowsNow.value)
        val center = if (target >= 0) target else rowsNow.value.lastIndex
        prepareNow.value(openingViewportRows(rowsNow.value, center))
        // Parsing suspends: structural rows or a live/status prefix may have changed meanwhile.
        snapshotFlow { revisionNow.value }.first { it == viewModel.screenState.value.messagesRevision }
        target = bookmarkRow(bookmark, keysNow.value, rowsNow.value)
        val liveKey = bookmark?.rowKey?.takeIf { it == liveRowKey(viewport.conversationId, viewModel.state.value.liveRunId) }
        val following = viewport.followOverride || bookmark == null || bookmark.followLatest ||
            target < 0 && liveKey == null
        val index = when {
            following || liveKey != null -> (countNow.value - 1).coerceAtLeast(0)
            target >= 0 -> prefixNow.value + target
            else -> prefixNow.value
        }
        val compatible = bookmark == null || (bookmark.width == 0 || abs(bookmark.width - expectedWidth) <= 2) &&
            abs(bookmark.fontScale - fontNow.value) < 0.01f
        val offset = if (following) TRANSCRIPT_END_OFFSET
            else if (compatible && bookmark?.topAnchored == true && (target >= 0 || liveKey != null)) bookmark.offset
            else 0
        scroll.stickToBottom = following
        val beforeLayout = viewport.listState.layoutInfo
        viewport.listState.requestScrollToItem(index, offset)
        viewport.prepared = true
        if (rowsNow.value.isNotEmpty() || viewModel.state.value.runningHere) {
            snapshotFlow { viewport.listState.layoutInfo }.first {
                it !== beforeLayout && it.totalItemsCount > 0 && it.visibleItemsInfo.isNotEmpty()
            }
            val targetKey = if (viewport.followOverride) null else if (target >= 0) keysNow.value.getOrNull(target) else liveKey
            if (targetKey != null && viewport.listState.layoutInfo.visibleItemsInfo.none { it.key == targetKey }) {
                // Edited/shortened rows may invalidate a pixel offset. Retry the same stable
                // row at its boundary while still hidden; never repair a visible transcript.
                val at = keysNow.value.indexOf(targetKey)
                val retry = if (at >= 0) prefixNow.value + at else (countNow.value - 1).coerceAtLeast(0)
                val previous = viewport.listState.layoutInfo
                viewport.listState.requestScrollToItem(retry)
                withTimeoutOrNull(2_000) {
                    snapshotFlow { viewport.listState.layoutInfo }.first { it !== previous && it.visibleItemsInfo.isNotEmpty() }
                }
            }
            // Old bookmarks measured an item's bottom against the bottom reading edge.
            // Convert only after its height is known and while the list is still hidden.
            if (!following && compatible && bookmark?.topAnchored == false && targetKey != null) {
                val layout = viewport.listState.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull { it.key == targetKey }
                if (item != null) {
                    val readingHeight = (layout.viewportSize.height - layout.beforeContentPadding - layout.afterContentPadding)
                        .coerceAtLeast(0)
                    val converted = if (viewport.listState.canScrollBackward || viewport.listState.canScrollForward)
                        legacyReadingOffset(bookmark.offset, item.size, readingHeight) else 0
                    viewport.listState.requestScrollToItem(item.index, converted)
                    withTimeoutOrNull(2_000) {
                        snapshotFlow { viewport.listState.layoutInfo }.first { it !== layout && it.visibleItemsInfo.isNotEmpty() }
                    }
                }
            }
        }
        scroll.restoring = false
        viewport.presented = true
        var acknowledged = false
        viewport.entrance.animateTo(1f, tween(ConversationEntrance.DURATION_MS, easing = LinearEasing)) {
            if (!acknowledged && value > 0f) {
                viewModel.presentViewport(viewport.conversationId)
                acknowledged = true
            }
        }
        Core.startup.contentPresented()
    }

    LaunchedEffect(viewport, scroll) {
        snapshotFlow {
            // While a gesture/fling owns the list, a bookmark cannot be final. Avoid
            // allocating/emitting a tuple and starting a collector on every scroll pixel.
            if (viewport.listState.isScrollInProgress) null else
                viewport.listState.firstVisibleItemIndex to viewport.listState.firstVisibleItemScrollOffset
        }.collect { idlePosition ->
            if (idlePosition != null) capture()
        }
    }
    LaunchedEffect(viewport) {
        snapshotFlow {
            val items = viewport.listState.layoutInfo.visibleItemsInfo
            if (!viewport.presented || items.isEmpty()) null else NearbyPreparation(
                rowsNow.value, revisionNow.value, prefixNow.value, items.first().index, items.last().index,
            )
        }.filterNotNull().conflate().collect { request ->
                // Finish at most one bounded parse, retaining only the newest pending
                // window. collectLatest used to restart work at every item boundary.
                // Disposal of this viewport still cancels the entire operation.
                if (request.rows !== rowsNow.value || request.revision != revisionNow.value ||
                    request.prefix != prefixNow.value) return@collect
                prepareNow.value(nearbyViewportRows(request.rows, request.prefix, request.first, request.last,
                    reverseLayout = false))
            }
    }
}

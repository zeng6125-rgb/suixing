package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class ChatEarlierPaging(val loading: State<Boolean>, val onLoad: () -> Unit)

private class EarlierPageSettleRequest(
    val token: Int,
    val conversationId: String,
    val anchor: EarlierPageAnchor,
    val rowsToPrepare: List<ChatRow>,
)

/** Disk page results wait for the drag/fling to end and preserve the visible message at insertion. */
@Composable
internal fun rememberChatEarlierPaging(
    state: ChatUiState,
    transcript: ChatTranscriptProjection,
    actions: ChatScreenActions,
    scope: CoroutineScope,
    scroll: ChatScroll,
    gesture: ChatReadingGesture,
    live: Boolean,
    transcriptPosition: TranscriptPosition? = null,
    prepareRows: (suspend (List<ChatRow>) -> Unit)? = null,
    contentReadiness: ViewportContentReadiness? = null,
): ChatEarlierPaging = with(gesture) {
    val shownTurns = transcript.shownTurns
    val displayedChat = transcript.displayedChat
    val page = transcript.page
    val visible = transcript.visible
    val itemCount = transcript.itemCount
    val rowKeyIndices = transcript.rowKeyIndices
    val rowOffset = transcript.rowOffset
    val listState = transcript.listState
    var shownTurnsByConversation by transcript.windowState
    // A successful page publishes a larger turn window, even if grouping/filtering keeps the
    // same row count. Never release restoration merely because the read was accepted.
    val loadingState = remember(state.conversationId) { mutableStateOf(false) }
    var loadingEarlier by loadingState
    val earlierRequestToken = remember(state.conversationId) { intArrayOf(0) }
    var earlierReveal by remember(state.conversationId) { mutableStateOf<EarlierPageReveal?>(null) }
    var pendingAnchor by remember(state.conversationId) { mutableStateOf<EarlierPageAnchor?>(null) }
    var pendingSettle by remember(state.conversationId) { mutableStateOf<EarlierPageSettleRequest?>(null) }
    val latestRowKeyIndices = rememberUpdatedState(rowKeyIndices)
    val latestRowOffset = rememberUpdatedState(rowOffset)
    val latestItemCount = rememberUpdatedState(itemCount)
    val latestLive = rememberUpdatedState(live)
    val latestState = rememberUpdatedState(state)

    fun resolveIncomingIndices(): Map<Any, Int> = buildMap {
        latestRowKeyIndices.value.forEach { (key, index) -> put(key, latestRowOffset.value + index) }
        val cur = latestState.value
        if (latestLive.value || cur.runningHere) {
            put(liveRowKey(cur.conversationId, cur.liveRunId), latestItemCount.value - 1)
        }
    }

    SideEffect {
        val reveal = earlierReveal
        if (reveal != null && reveal.isReady(shownTurns, state.messages.firstOrNull())) {
            val incomingIndices = resolveIncomingIndices()
            val captured = pendingAnchor
            val resolvedAnchor: EarlierPageAnchor?
            val target: EarlierPageTarget?
            if (captured != null) {
                val mappedIndex = incomingIndices[captured.key]
                if (mappedIndex != null) {
                    resolvedAnchor = captured
                    target = EarlierPageTarget(mappedIndex, -captured.offset)
                } else {
                    resolvedAnchor = captureEarlierPageAnchor(listState.layoutInfo.visibleItemsInfo)
                    target = earlierPageTarget(listState.layoutInfo.visibleItemsInfo, incomingIndices)
                }
            } else {
                resolvedAnchor = captureEarlierPageAnchor(listState.layoutInfo.visibleItemsInfo)
                target = earlierPageTarget(listState.layoutInfo.visibleItemsInfo, incomingIndices)
            }
            earlierReveal = null
            pendingAnchor = null
            if (!scroll.stickToBottom && target != null && resolvedAnchor != null) {
                transcriptPosition?.rebaseAfterNavigation()
                listState.requestScrollToItem(target.index, target.scrollOffset)
                val prefixCount = (target.index - rowOffset).coerceIn(0, visible.size)
                pendingSettle = EarlierPageSettleRequest(
                    token = earlierRequestToken[0],
                    conversationId = state.conversationId,
                    anchor = resolvedAnchor,
                    rowsToPrepare = visible.take(prefixCount),
                )
            } else {
                if (!scroll.stickToBottom && target != null) {
                    transcriptPosition?.rebaseAfterNavigation()
                    listState.requestScrollToItem(target.index, target.scrollOffset)
                }
                loadingEarlier = false
            }
        }
    }

    LaunchedEffect(pendingSettle) {
        val req = pendingSettle ?: return@LaunchedEffect
        try {
            withTimeoutOrNull(1_200L) {
                if (req.rowsToPrepare.isNotEmpty()) {
                    prepareRows?.invoke(req.rowsToPrepare)
                }
                if (displayedChat.value != req.conversationId || earlierRequestToken[0] != req.token) {
                    return@withTimeoutOrNull
                }
                withFrameNanos { }
                contentReadiness?.awaitReady()
                repeat(2) {
                    if (displayedChat.value != req.conversationId ||
                        earlierRequestToken[0] != req.token ||
                        scroll.stickToBottom
                    ) return@withTimeoutOrNull
                    val drift = earlierAnchorDriftPx(listState.layoutInfo.visibleItemsInfo, req.anchor)
                    if (drift != null && drift == 0) return@withTimeoutOrNull
                    val anchorIndex = resolveIncomingIndices()[req.anchor.key] ?: return@withTimeoutOrNull
                    val beforeLayout = listState.layoutInfo
                    transcriptPosition?.rebaseAfterNavigation()
                    listState.requestScrollToItem(anchorIndex, -req.anchor.offset)
                    withTimeoutOrNull(300L) {
                        snapshotFlow {
                            displayedChat.value != req.conversationId ||
                                (listState.layoutInfo !== beforeLayout &&
                                    listState.layoutInfo.visibleItemsInfo.isNotEmpty())
                        }.first { it }
                    }
                    contentReadiness?.awaitReady()
                }
            }
            if (displayedChat.value == req.conversationId && earlierRequestToken[0] == req.token) {
                transcriptPosition?.rebaseAfterNavigation()
            }
        } finally {
            if (earlierRequestToken[0] == req.token) {
                pendingSettle = null
                loadingEarlier = false
            }
        }
    }

    val loadEarlier = rememberStableCallback0 {
        // Loading history is an explicit reading action, like
        // navigating to an older turn. A short tail must not
        // re-arm latest-follow while its disk page is pending.
        scroll.allowBottomResume = false
        scroll.stickToBottom = false
        transcriptPosition?.pauseForUserChange()
        val chatId = state.conversationId
        val token = ++earlierRequestToken[0]
        val needsDiskPage = page.from == 0
        val previousHead = state.messages.firstOrNull().takeIf { needsDiskPage }
        loadingEarlier = true
        fun finish(added: Boolean) {
            if (actions.state.value.conversationId != chatId || earlierRequestToken[0] != token) return
            if (added) {
                scope.launch {
                    // requestScrollToItem cancels an active scroll.
                    // Keep drawing the old suffix until the current
                    // drag/fling ends, then insert the page in one pass.
                    snapshotFlow {
                        displayedChat.value != chatId ||
                            (!pressed && !listState.isScrollInProgress)
                    }.first { it }
                    if (displayedChat.value != chatId ||
                        actions.state.value.conversationId != chatId ||
                        earlierRequestToken[0] != token) return@launch
                    val nextShownTurns = shownTurns + PAGE_TURNS
                    pendingAnchor = captureEarlierPageAnchor(listState.layoutInfo.visibleItemsInfo)
                    earlierReveal = EarlierPageReveal(nextShownTurns, previousHead)
                    shownTurnsByConversation = shownTurnsByConversation + (chatId to nextShownTurns)
                }
            } else {
                transcriptPosition?.rebaseAfterNavigation()
                loadingEarlier = false
            }
        }
        if (!needsDiskPage) finish(true)
        else if (!actions.loadEarlier(onComplete = ::finish)) finish(false)
    }

    remember(loadingState, loadEarlier) { ChatEarlierPaging(loadingState, loadEarlier) }
}

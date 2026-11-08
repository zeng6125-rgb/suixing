package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ChatEarlierPaging(val loading: State<Boolean>, val onLoad: () -> Unit)

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
): ChatEarlierPaging = with(gesture) {
    val shownTurns = transcript.shownTurns
    val displayedChat = transcript.displayedChat
    val page = transcript.page
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
    SideEffect {
        val reveal = earlierReveal
        if (reveal != null && reveal.isReady(shownTurns, state.messages.firstOrNull())) {
            // This is the incoming projection, but layoutInfo still describes the last
            // displayed frame. Preserve its current message, even if the reader moved
            // while disk I/O was pending. Apply once, before the new page is measured.
            val incomingIndices = buildMap<Any, Int> {
                rowKeyIndices.forEach { (key, index) -> put(key, rowOffset + index) }
                if (live || state.runningHere) {
                    put(liveRowKey(state.conversationId, state.liveRunId), itemCount - 1)
                }
            }
            // Disk paging can finish after the reader has explicitly returned to latest.
            // Preserve history only while reading; the old request must not pull them back.
            if (!scroll.stickToBottom) {
                earlierPageTarget(listState.layoutInfo.visibleItemsInfo, incomingIndices)?.let { target ->
                    listState.requestScrollToItem(target.index, target.scrollOffset)
                }
            }
            earlierReveal = null
            loadingEarlier = false
        }
    }
    val loadEarlier = rememberStableCallback0 {
        // Loading history is an explicit reading action, like
        // navigating to an older turn. A short tail must not
        // re-arm latest-follow while its disk page is pending.
        scroll.allowBottomResume = false
        scroll.stickToBottom = false
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
                    earlierReveal = EarlierPageReveal(nextShownTurns, previousHead)
                    shownTurnsByConversation = shownTurnsByConversation + (chatId to nextShownTurns)
                }
            } else {
                loadingEarlier = false
            }
        }
        if (!needsDiskPage) finish(true)
        else if (!actions.loadEarlier(onComplete = ::finish)) finish(false)
    }


    remember(loadingState, loadEarlier) { ChatEarlierPaging(loadingState, loadEarlier) }
}

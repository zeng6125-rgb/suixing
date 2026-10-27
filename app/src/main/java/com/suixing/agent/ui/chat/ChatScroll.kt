package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** One scroll policy for one conversation. Durable positions belong to ConversationViewport. */
class ChatScroll internal constructor(
    internal val listState: LazyListState,
    val conversationId: String,
    initialFollowing: Boolean,
) {
    var stickToBottom by mutableStateOf(initialFollowing)
    internal var restoring by mutableStateOf(true)
    internal var pinActive by mutableStateOf(false)
    val atBottom by derivedStateOf { !listState.canScrollBackward }
    internal suspend fun toBottom() = listState.pinToBottom()
}

internal suspend fun LazyListState.pinToBottom() { scrollToItem(0) }

private data class FollowWant(
    val index: Int,
    val offset: Int,
    val stick: Boolean,
    val pressed: Boolean,
    val guard: Boolean,
    val restoring: Boolean,
)

@Composable
fun rememberChatScroll(
    listState: LazyListState,
    autoFollow: Boolean,
    pressedNow: () -> Boolean,
    guardNow: () -> Boolean,
    conversationId: Any? = Unit,
    trace: (String) -> Unit = {},
    initialFollowing: Boolean = true,
): ChatScroll {
    val scroll = remember(listState) { ChatScroll(listState, conversationId.toString(), initialFollowing) }
    LaunchedEffect(listState) {
        snapshotFlow {
            !listState.canScrollBackward && !listState.isScrollInProgress &&
                !scroll.restoring && !pressedNow() && !guardNow()
        }.collectLatest { reachedBottom ->
            if (!reachedBottom) return@collectLatest
            val index = listState.firstVisibleItemIndex
            // Keep the existing overscroll/selection settling interval.
            delay(420)
            if (!listState.canScrollBackward && !listState.isScrollInProgress && !scroll.restoring &&
                !pressedNow() && !guardNow() && listState.firstVisibleItemIndex == index) {
                scroll.pinActive = false
                scroll.stickToBottom = true
                trace("stick=true reason=reached-bottom")
            }
        }
    }
    LaunchedEffect(listState, autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        snapshotFlow {
            FollowWant(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                scroll.stickToBottom, pressedNow(), guardNow(), scroll.restoring)
        }.collect { want ->
            if (want.stick && !want.pressed && !want.guard && !want.restoring &&
                !listState.isScrollInProgress && (want.index != 0 || want.offset != 0)) {
                listState.scrollToItem(0)
            }
        }
    }
    return scroll
}

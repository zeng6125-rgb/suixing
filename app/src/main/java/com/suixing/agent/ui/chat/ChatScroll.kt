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
import com.suixing.agent.ui.drawer.LocalDrawerGestureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/** One scroll policy for one conversation. Durable positions belong to ConversationViewport. */
class ChatScroll internal constructor(
    internal val listState: LazyListState,
    val conversationId: String,
    initialFollowing: Boolean,
) {
    var stickToBottom by mutableStateOf(initialFollowing)
    internal var allowBottomResume by mutableStateOf(true)
    internal var restoring by mutableStateOf(true)
    internal var nestedScrolling by mutableStateOf<() -> Boolean>({ false })
    /** Event identity for late selection cleanup; not observed by composition. */
    internal var latestRequestGeneration = 0L
    val atBottom by derivedStateOf { !listState.canScrollForward }
    internal suspend fun toBottom() = listState.pinToBottom()
}

/** A normal transcript anchors readers at its top; only explicit following seeks the end. */
internal const val TRANSCRIPT_END_OFFSET = Int.MAX_VALUE / 2
internal suspend fun LazyListState.pinToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last, TRANSCRIPT_END_OFFSET)
}

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
    val drawer = LocalDrawerGestureState.current
    val drawerGenerationAtMount = remember(scroll, drawer) { drawer?.gestureGeneration }
    LaunchedEffect(scroll, drawer) {
        if (drawer == null) return@LaunchedEffect
        snapshotFlow {
            drawer.gestureGeneration to (drawer.homeShieldActive && !scroll.stickToBottom)
        }.collect { (generation, readingBehindDrawer) ->
            // Drawer/IME geometry cannot turn reading into following. The next vertical
            // transcript gesture or explicit latest request re-enables bottom resume.
            // The generation retains an open/close that finishes before the next frame.
            if (!scroll.stickToBottom && (readingBehindDrawer || generation != drawerGenerationAtMount)) {
                scroll.allowBottomResume = false
            }
        }
    }
    LaunchedEffect(listState, drawer) {
        snapshotFlow {
            !scroll.stickToBottom && drawer?.homeShieldActive != true && scroll.allowBottomResume && !listState.canScrollForward && !listState.isScrollInProgress && !scroll.nestedScrolling() &&
                !scroll.restoring && !pressedNow() && !guardNow()
        }.collectLatest { reachedBottom ->
            if (!reachedBottom) return@collectLatest
            val index = listState.firstVisibleItemIndex
            val drawerGeneration = drawer?.gestureGeneration
            // Keep the existing overscroll/selection settling interval.
            delay(420)
            if (!scroll.stickToBottom && drawer?.gestureGeneration == drawerGeneration && scroll.allowBottomResume && !listState.canScrollForward && !listState.isScrollInProgress && !scroll.nestedScrolling() && !scroll.restoring &&
                !pressedNow() && !guardNow() && listState.firstVisibleItemIndex == index) {
                scroll.stickToBottom = true
                trace("stick=true reason=reached-bottom")
            }
        }
    }
    return scroll
}

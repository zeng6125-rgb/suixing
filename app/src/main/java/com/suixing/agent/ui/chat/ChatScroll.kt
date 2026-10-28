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
    val atBottom by derivedStateOf { !listState.canScrollForward }
    internal suspend fun toBottom() = listState.pinToBottom()
}

/** A normal transcript anchors readers at its top; only explicit following seeks the end. */
internal const val TRANSCRIPT_END_OFFSET = Int.MAX_VALUE / 2
internal suspend fun LazyListState.pinToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last, TRANSCRIPT_END_OFFSET)
}

private data class FollowWant(
    val awayFromBottom: Boolean,
    val scrolling: Boolean,
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
    /** 内容版本（消息数 + 流式/思考字符数）。变化即"这一帧内容要长高"。 */
    contentVersion: Any? = null,
): ChatScroll {
    val scroll = remember(listState) { ChatScroll(listState, conversationId.toString(), initialFollowing) }
    // **贴底前移**：内容变化这一帧、测量之前先请求滚动到底。
    // LaunchedEffect 在组合应用后、布局前运行 ⇒ 本帧就贴底，不再"先长高、下一帧拉回"，
    // 从而消掉流式时那一帧的位移残影（用户反馈）。只加这一处，
    // 与下面 FollowWant 的状态机（是否算追底）分工不变。
    LaunchedEffect(contentVersion) {
        if (contentVersion != null && scroll.stickToBottom && !scroll.restoring) {
            listState.pinToBottom()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            !listState.canScrollForward && !listState.isScrollInProgress &&
                !scroll.restoring && !pressedNow() && !guardNow()
        }.collectLatest { reachedBottom ->
            if (!reachedBottom) return@collectLatest
            val index = listState.firstVisibleItemIndex
            // Keep the existing overscroll/selection settling interval.
            delay(420)
            if (!listState.canScrollForward && !listState.isScrollInProgress && !scroll.restoring &&
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
            FollowWant(listState.canScrollForward, listState.isScrollInProgress,
                scroll.stickToBottom, pressedNow(), guardNow(), scroll.restoring)
        }.collect { want ->
            if (want.stick && !want.pressed && !want.guard && !want.restoring &&
                !want.scrolling && want.awayFromBottom) {
                listState.pinToBottom()
            }

        }
    }
    return scroll
}

package com.suixing.agent.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 顶锚列表的滚动层：追底、取消追底、阅读位置记忆。滚动相关的状态全在这里，
 * ChatScreen 只读它的字段、不写逻辑。
 *
 * 顶锚（非 reverseLayout）下内容往末尾追加：读上面的内容时末尾怎么长都不动视口，
 * 所以"阅读时静止"是结构属性，不需要纠正代码。
 */
class ChatScroll internal constructor(internal val listState: LazyListState) {
    /** 在底就追新内容。只由两件事改变：手指上拖（关）、真滚到底（开）。内容增长不改它。 */
    var stickToBottom by mutableStateOf(true)

    /** 每个会话的阅读位置：首可见项 key + 它离视口顶的 px。切走存、切回恢复。 */
    val spots = mutableStateMapOf<String, Pair<Any?, Int>>()
    var spotKey by mutableStateOf<Any?>(null)
        internal set
    var spotOffset by mutableStateOf(0)
        internal set

    val atBottom by derivedStateOf { !listState.canScrollForward }

    internal fun saveSpot(conversationId: String) {
        if (!stickToBottom && spotKey != null) spots[conversationId] = spotKey to spotOffset
    }

    internal fun takeSpot(conversationId: String): Pair<Any?, Int>? = spots[conversationId]

    /** 贴底两段式：先定位末项，再补一大段增量（增量被列表夹住，落点就是真底）。 */
    internal suspend fun toBottom(itemCount: Int) = listState.pinToBottom(itemCount)
}

/**
 * 贴底两段式（真机验证过的形态）：`scrollToItem(末项, Int.MAX_VALUE)` 会被夹到 offset=0
 * （末项顶对齐，没到底）；先定位末项、再 scrollBy 一大段增量（被列表夹住）才落在真底。
 * 主转录与思维链内层列表共用。
 */
internal suspend fun LazyListState.pinToBottom(itemCount: Int) {
    scrollToItem(itemCount - 1)
    scrollBy(FOLLOW_SCROLL_PX)
}

/** Far more than any transcript: the list clamps this to its own end. */
internal const val FOLLOW_SCROLL_PX = 200_000f

/** 追底这一帧要不要滑、滑多少。作为 snapshotFlow 的载荷：任一字段变化都会重新判定一次。 */
private data class FollowWant(
    val below: Int,
    val stick: Boolean,
    val pressed: Boolean,
    val guard: Boolean,
    val dragging: Boolean,
)

@Composable
fun rememberChatScroll(
    listState: LazyListState,
    autoFollow: Boolean,
    pressed: Boolean,
    pressGuardOn: Boolean,
): ChatScroll {
    val scroll = remember(listState) { ChatScroll(listState) }

    // 真滚到底才重新打开追底，且**手指不在拖**（拖动中列表瞬时在底不代表人回来了，
    // 那时重开追底会和手指打架 —— 用户：「往上轻微滑动，又被锁回来」）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }
            .collect { (canForward, scrolling) ->
                if (!canForward && !scrolling) scroll.stickToBottom = true
            }
    }

    // 追底：**不枚举 key**，直接盯"离底还有多少"。任何布局变化（流式长高、让位变、键盘起落、
    // 已落盘的行里折叠条展开、图片解码完）都会改变这个数 ⇒ 一定会被看到；手指按着/拖着时不抢。
    // 逐帧用**增量**滚动：离底多少滑多少 ⇒ 内容长多少跟多少，不会"先长一截、再猛跳一下"。
    // 两段式的 pinToBottom 只留给真正的"跳到底"（打开会话、回到最新、一轮结束补贴一次）。
    val downNow = rememberUpdatedState(pressed)
    val guardNow = rememberUpdatedState(pressGuardOn)
    LaunchedEffect(listState, autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            FollowWant(
                below = if (last == null) {
                    0
                } else {
                    (last.offset + last.size) - info.viewportEndOffset + info.afterContentPadding
                },
                stick = scroll.stickToBottom,
                pressed = downNow.value,
                guard = guardNow.value,
                dragging = listState.isScrollInProgress,
            )
        }.collect { want ->
            if (want.below > 0 && want.stick && !want.pressed && !want.guard && !want.dragging) {
                listState.scrollBy(want.below.toFloat())
            }
        }
    }

    // 阅读位置：手指停下那一刻记首可见项。顶锚下增长在末尾，读上面时视口天然不动，无需纠正。
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            Triple(first?.key, first?.offset ?: 0, listState.isScrollInProgress)
        }.collect { (key, offset, scrolling) ->
            if (!scroll.stickToBottom && !scrolling && wasScrolling && key != null) {
                scroll.spotKey = key
                scroll.spotOffset = offset
            }
            wasScrolling = scrolling
        }
    }
    return scroll
}

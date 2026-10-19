package com.suixing.agent.ui.chat

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
 * 底锚列表的滚动层：贴底、取消贴底、阅读位置记忆。滚动相关的状态全在这里，
 * ChatScreen 只读它的字段、不写逻辑。
 *
 * 底锚（reverseLayout，见 ChatScreen 的 LazyColumn）下喂序 index 0 就是视觉最底：
 * 内容在 index 0 长高是**向上**长，卡片的底边不动，所以追底不需要逐帧滚动 ——
 * 只有滚动位置被顶离 (0, 0) 时才补一次（新行插在喂序最前会把锚点挪到 index 1）。
 * 读历史时增长同样不动视口：视口钉在首可见项上，增长发生在它哪一侧都不挪它。
 */
class ChatScroll internal constructor(internal val listState: LazyListState) {
    /** 在底就追新内容。只由两件事改变：手指轻扫（关）、真滚到底（开）。内容增长不改它。 */
    var stickToBottom by mutableStateOf(true)

    /** 每个会话的阅读位置：首可见项 key + 它离滚动起点的 px。切走存、切回恢复。 */
    val spots = mutableStateMapOf<String, Pair<Any?, Int>>()
    var spotKey by mutableStateOf<Any?>(null)
        internal set
    var spotOffset by mutableStateOf(0)
        internal set

    /**
     * 最近一次「停下且不在底部」时看到的首可见项。
     *
     * **为什么要有它**（用户：「切换会话之后有些情况滚动位置不是离开前的状态」）：
     * 以前只在「一次滚动结束」那一刻写 [spotKey]，切会话时再从 [spotKey] 落进 [spots]。
     * 只要中间有过一次不产生滚动结束事件的动作（轻点、程序化滚动、会话内容先变），
     * 写进去的就是上一次滚动的旧值 —— 于是「有些情况」不还原。
     * 现在每一帧只要停在非底部就刷新它，切走时直接取，不存在过期值。
     */
    internal var lastSeen: Pair<Any?, Int>? by mutableStateOf(null)

    /** 底锚：滚不到更早（index 0 且没有偏移）就是贴着底；与给输入框的底 padding 无关。 */
    val atBottom by derivedStateOf { !listState.canScrollBackward }

    internal fun saveSpot(conversationId: String) {
        if (stickToBottom) return
        val seen = lastSeen ?: spotKey?.let { it to spotOffset } ?: return
        spots[conversationId] = seen
    }

    internal fun takeSpot(conversationId: String): Pair<Any?, Int>? = spots[conversationId]

    /** 贴底 = 回到 index 0（底锚的滚动起点）。 */
    internal suspend fun toBottom() = listState.pinToBottom()
}

/**
 * 底锚下贴底只有一件事：滚到 index 0。旧顶锚的两段式（定位末项 + scrollBy 一大段增量）
 * 不再需要，也再没有"补一大段增量"的机会去和别处的滚动打架。
 */
internal suspend fun LazyListState.pinToBottom() {
    scrollToItem(0)
}

/** 追底这一帧要不要回底。作为 snapshotFlow 的载荷：任一字段变化都会重新判定一次。 */
private data class FollowWant(
    val index: Int,
    val offset: Int,
    val stick: Boolean,
    val pressed: Boolean,
    val guard: Boolean,
)

@Composable
fun rememberChatScroll(
    listState: LazyListState,
    autoFollow: Boolean,
    pressed: Boolean,
    pressGuardOn: Boolean,
): ChatScroll {
    val scroll = remember(listState) { ChatScroll(listState) }

    // 真回到 index 0 才重新打开追底，且**手指不在拖**（拖动中列表瞬时在底不代表人回来了，
    // 那时重开追底会和手指打架 —— 用户：「往上轻微滑动，又被锁回来」）。
    // 底锚下判据就是 (0, 0)：底 padding 不参与 —— index 0 的底边本来就压在让位之上，
    // 不像顶锚那样会出现"看着在底、canScrollForward 仍为真"。
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollBackward to listState.isScrollInProgress }
            .collect { (canBack, scrolling) ->
                if (!scrolling && !canBack) scroll.stickToBottom = true
            }
    }

    // 追底：**不逐帧滚**。底锚下 index 0 长高本来就向上长（长高多少、底边都不动），
    // 只有滚动位置被顶离 (0, 0) 时才补一次：新行插在喂序最前（index 0）会让 LazyColumn
    // 按 key 把锚点挪到 index 1，这一帧补回 0 即可。手指按着/拖着时不抢。
    val downNow = rememberUpdatedState(pressed)
    val guardNow = rememberUpdatedState(pressGuardOn)
    LaunchedEffect(listState, autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        snapshotFlow {
            FollowWant(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                stick = scroll.stickToBottom,
                pressed = downNow.value,
                guard = guardNow.value,
            )
        }.collect { want ->
            if (want.stick && !want.pressed && !want.guard &&
                (want.index != 0 || want.offset != 0)
            ) {
                listState.scrollToItem(0)
            }
        }
    }

    // **阅读锚**（用户：「微信里别人在底下发消息，我看上面的内容位置不会变」）：
    // 底锚下 index 0（视觉最底那条）长高会把上面全部顶走 —— 流式时就是你正在读的内容
    // 一直在动。修法不是"补底部的增量"（那四版都失败了：崩 / 抖 / 锁），而是**换锚点**：
    // 不在底部时，把「用户正在看的那条可见项」钉在它原来的视口位置；
    // 它挪了多少就补多少，绝大多数帧挪动是 0 ⇒ 不抖；补偿后位置复原 ⇒ 不会连续补 ⇒ 不锁。
    LaunchedEffect(listState) {
        var anchorKey: Any? = null
        var anchorOffset = 0
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            Triple(first?.key, first?.offset ?: 0, scroll.stickToBottom)
        }.collect { (key, offset, stick) ->
            if (stick) {
                // 在底部：由追底负责，锚点作废。
                anchorKey = null
                return@collect
            }
            if (anchorKey == null || anchorKey != key) {
                // 换了锚（刚往上翻 / 切换会话）：以当前状态为准，不做补偿。
                anchorKey = key
                anchorOffset = offset
            } else if (kotlin.math.abs(offset - anchorOffset) > 1) {
                // 锚点被顶走了：补回差额。正向 delta 会让内容下移（reverseLayout 下
                // index 越大越靠上），所以差额取反。
                val fix = (anchorOffset - offset).toFloat()
                anchorOffset = offset + fix.toInt()
                listState.dispatchRawDelta(fix)
            }
        }
    }

    // 阅读位置：手指停下那一刻记首可见项。底锚下"首可见"是视觉最底那条（滚动起点）。
    // 位置取 firstVisibleItemScrollOffset —— 它正是 scrollToItem(index, offset) 的第二个参数；
    // layoutInfo 里那条项的 offset 是含负偏移的视口坐标，直接回填会把位置修正掉。
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val key = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
            Triple(key, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { (key, offset, scrolling) ->
            // **每一帧都刷新 lastSeen**，不再只在"滚动刚结束"那一帧写：
            // 只在结束时写，遇到轻点/程序化滚动这类不产生结束事件的动作，值就停在旧位置，
            // 切会话时还原到的就是上一次滚动的位置（用户报的「有些情况不还原」）。
            if (!scroll.stickToBottom && !scrolling && key != null) {
                scroll.lastSeen = key to offset
                scroll.spotKey = key
                scroll.spotOffset = offset
            }
            wasScrolling = scrolling
        }
    }
    return scroll
}

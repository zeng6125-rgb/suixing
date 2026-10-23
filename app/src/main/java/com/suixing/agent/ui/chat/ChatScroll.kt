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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
    /**
     * 当前正在显示的会话 id，由 ChatScreen 在**内容切换之前**写入（`SideEffect`，布局前）。
     *
     * **是 state 而不是普通 var**（issue #4）：下面那些"按会话存"的字段都靠它取键，
     * 切换会话必须让 `snapshotFlow` 重新发一次 —— 否则追底 collector 还拿着上一个会话的
     * 判定在跑。普通 var 读不到变化。
     */
    var conversationId: String by mutableStateOf("")
        internal set

    /**
     * **贴底意图按会话存**（issue #4 链路 3）：`ChatScroll` 是全局单例（`listState` 全应用共用），
     * 以前 A 里往上翻一下，切到 B 时 B 一进来就是"不贴底"、新消息不再自动滚。现在每个会话各一份，
     * 缺省 true。
     */
    private val stickByConversation = mutableStateMapOf<String, Boolean>()
    /** 在底就追新内容。只由两件事改变：手指轻扫（关）、真滚到底（开）。内容增长不改它。 */
    var stickToBottom: Boolean
        get() = stickByConversation[conversationId] ?: true
        set(value) {
            stickByConversation[conversationId] = value
        }

    /** 每个会话的阅读位置：首可见项 key + 它离滚动起点的 px。切走存、切回恢复。 */
    val spots = mutableStateMapOf<String, Pair<Any?, Int>>()

    /** 恢复中：这几帧不许"滚到底就把贴底打开"的 collector 把 [stickToBottom] 翻回去（链路 4）。 */
    internal var restoring by mutableStateOf(false)

    /**
     * **读历史期间钉住了流式行的高度**（#17 的 `pinnedLiveHeight` 生效中）。
     *
     * 钉住 ⇒ 内容比真实高度短 ⇒ 列表更容易被判定成「已经在底部」；而下面那个
     * 「持续 250ms 到底就把贴底打开」的 collector 一旦把 [stickToBottom] 翻回 true，
     * 钉住就会被解除、内容一下子恢复真实高度 ⇒ 用户看到的就是「已输出的内容还在动」。
     *
     * 所以钉住期间**不许**这个 collector 自动打开贴底：解除只能来自用户真正的操作
     * （「回到最新」按钮）或者这一轮结束。
     */
    internal var pinActive by mutableStateOf(false)

    /** 每个会话各自的恢复目标（链路 1）：切回 A 时不能读到 B 恢复时的临时值。 */
    private val spotByConversation = mutableStateMapOf<String, Pair<Any?, Int>>()
    var spotKey: Any?
        get() = spotByConversation[conversationId]?.first
        internal set(value) {
            spotByConversation[conversationId] = value to (spotByConversation[conversationId]?.second ?: 0)
        }
    var spotOffset: Int
        get() = spotByConversation[conversationId]?.second ?: 0
        internal set(value) {
            spotByConversation[conversationId] = spotByConversation[conversationId]?.first to value
        }

    /**
     * 最近一次「停下且不在底部」时看到的首可见项，**按会话存**（链路 2）。
     *
     * **为什么要有它**（用户：「切换会话之后有些情况滚动位置不是离开前的状态」）：
     * 以前只在「一次滚动结束」那一刻写 [spotKey]，切会话时再从 [spotKey] 落进 [spots]。
     * 只要中间有过一次不产生滚动结束事件的动作（轻点、程序化滚动、会话内容先变），
     * 写进去的就是上一次滚动的旧值 —— 于是「有些情况」不还原。
     * 现在每一帧只要停在非底部就刷新它，切走时直接取，不存在过期值。
     *
     * 按会话存是 issue #4 的修法：以前它是全局的，在 B 里翻过之后切回 A，`saveSpot(A)`
     * 会把 **B 的位置**写进 A 的槽。
     */
    private val lastSeenByConversation = mutableStateMapOf<String, Pair<Any?, Int>>()
    internal var lastSeen: Pair<Any?, Int>?
        get() = lastSeenByConversation[conversationId]
        set(value) {
            if (value == null) lastSeenByConversation.remove(conversationId)
            else lastSeenByConversation[conversationId] = value
        }

    /** 底锚：滚不到更早（index 0 且没有偏移）就是贴着底；与给输入框的底 padding 无关。 */
    val atBottom by derivedStateOf { !listState.canScrollBackward }

    /**
     * 切走时把**那个会话**的位置落盘。参数必须显式给 id：调用点是在 `conversationId`
     * 已经换成新会话之后才跑的（`SideEffect` 先写），按"当前会话"取会存到新会话名下。
     */
    /**
     * 离开某会话时，它当时是否处于「跟底」（贴底追最新）。
     *
     * 只读，给上层决定「要不要连 anchors 一起忘掉」：贴底离开 = 没有要记的位置。
     */
    internal fun wasFollowing(conversationId: String): Boolean =
        stickByConversation[conversationId] ?: true

    /**
     * 把某会话记住的位置全部丢掉：`spots`、`lastSeen`、`spotKey/spotOffset`。
     *
     * **为什么必须三个一起清**（真机日志 2026-10-21）：在 A 里上翻到 X 之后点「回到最新」，
     * 列表贴底但锚点停更在 X（贴底时不记），恢复时优先读 X ⇒ 切回来跳回"点追底之前"的位置。
     * 只清 `spots` 不够、只清上层的 `anchors` 也不够 —— 两处都存着 X。
     */
    internal fun forgetSpot(conversationId: String) {
        spots.remove(conversationId)
        lastSeenByConversation.remove(conversationId)
        spotByConversation.remove(conversationId)
    }

    internal fun saveSpot(conversationId: String) {
        // 贴底离开：把旧位置丢掉，别留给下一次「切回来」—— 否则会跳回上翻时的 X。
        if (stickByConversation[conversationId] ?: true) {
            forgetSpot(conversationId)
            return
        }
        val seen = lastSeenByConversation[conversationId]
            ?: spotByConversation[conversationId]
            ?: return
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
    /**
     * 会话 id。阅读锚点按它隔离 —— 切换会话时锚点必须作废重来，
     * 否则旧会话的锚会被拿去"纠正"新会话的位置（用户：「各会话要隔离」）。
     */
    conversationId: Any? = Unit,
    /** 诊断回调（issue #14）：谁把 `stickToBottom` 翻回 true。查完删。 */
    trace: (String) -> Unit = {},
): ChatScroll {
    val scroll = remember(listState) { ChatScroll(listState) }

    // 真回到 index 0 才重新打开追底，且**手指不在拖**（拖动中列表瞬时在底不代表人回来了，
    // 那时重开追底会和手指打架 —— 用户：「往上轻微滑动，又被锁回来」）。
    // 底锚下判据就是 (0, 0)：底 padding 不参与 —— index 0 的底边本来就压在让位之上，
    // 不像顶锚那样会出现"看着在底、canScrollForward 仍为真"。
    // 手指按着 / 长按守护期间的引用：先于 collector 声明（原来在下面）。
    val downNow0 = rememberUpdatedState(pressed)
    val guardNow0 = rememberUpdatedState(pressGuardOn)
    LaunchedEffect(listState) {
        // **`pinActive` 必须在读取集里**（评审确证）：只在 collect 体内读它时，钉住解除那一刻
        // 若 `canBack`/`isScrollInProgress` 都没变，collector 就不会重跑 ⇒ 人已经在底部、
        // `stickToBottom` 仍是 false ⇒ 「回到最新」常亮、新输出不跟（静默失去跟底）。
        snapshotFlow {
            Triple(
                listState.canScrollBackward,
                listState.isScrollInProgress,
                scroll.pinActive,
            )
        }
            .collectLatest { (canBack, scrolling, pinned) ->
                // **恢复位置的那几帧不许翻回 true**（issue #4 链路 4）：`scrollToItem` 之后布局
                // 还没定型，`canScrollBackward` 会短暂为假，collector 于是把刚设的 false 改回去，
                // 位置立刻被追底拉到底。
                // 钉住期间不自动打开贴底（见 [pinActive]）：那时「到底」不代表用户想跟新内容，
                // 只代表被裁短了的内容滚到了头。
                // **钉住期间不再无条件跳过**（真机反馈：「流式上翻时底部滑不下去」）。
                // 原来的 `|| pinned` 让"到没到底"的判定在钉住期间永远不成立 ⇒ `stickToBottom`
                // 一直是 false、钉住只能等这一轮结束才解除 ⇒ 用户滑到底也解不开、也就看不到最新输出。
                // 现在：钉住期间也照常等 250ms 的"持续到底"，**到了就解除钉住并重新贴底**。
                // 反过来，"内容被裁短导致的一瞬间到底"仍被 250ms 持续性挡住。
                if (scrolling || canBack || scroll.restoring) return@collectLatest
                // **必须「持续」到底才算数**（issue #14，真机日志证据）：长按建立选区时布局会重排，
                // `canScrollBackward` 会瞬时变成 false。原来一见到 false 就把 `stickToBottom` 翻回 true，
                // 追底逻辑紧接着 `scrollToItem(0)` 把列表拉回底部 —— 日志里 `guard-off idx=0/624`，
                // 两秒后 `guard-on idx=0/0`，中间没有任何手势。
                // 250ms 的持续性是区分「用户真的滚到底」和「布局瞬闪」的关键。
                delay(250)
                if (!listState.canScrollBackward && !listState.isScrollInProgress &&
                    !scroll.restoring
                ) {
                    // 钉住期间走到这里 = 用户自己滚回了底部 ⇒ 解除钉住（下一帧内容就会展开）。
                    if (scroll.pinActive) {
                        scroll.pinActive = false
                        trace("pin=false reason=reached-bottom")
                    }
                    scroll.stickToBottom = true
                    trace("stick=true reason=canBack-false")
                }
            }
    }

    // 追底：**不逐帧滚**。底锚下 index 0 长高本来就向上长（长高多少、底边都不动），
    // 只有滚动位置被顶离 (0, 0) 时才补一次：新行插在喂序最前（index 0）会让 LazyColumn
    // 按 key 把锚点挪到 index 1，这一帧补回 0 即可。手指按着/拖着时不抢。
    LaunchedEffect(listState, autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        snapshotFlow {
            FollowWant(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                stick = scroll.stickToBottom,
                pressed = downNow0.value,
                guard = guardNow0.value,
            )
        }.collect { want ->
            if (want.stick && !want.pressed && !want.guard &&
                (want.index != 0 || want.offset != 0)
            ) {
                listState.scrollToItem(0)
            }
        }
    }

    // **这里仍然不做任何阅读锚/偏移纠正**（用户：「滑一下就飞到天上去了，还滑不回来」）：
    // 任何"看到偏移就纠正"的做法都会和手指/惯性滚动抢同一份列表，一旦判断有偏差
    // 就会连续纠正、越滚越远。流式长高造成的漂移改由 ChatScreen 里的**增量补偿**处理
    // （只补 liveHeight 的 Δ，不读偏移；见那边的 LaunchedEffect）。
    // 已知代价：补偿会消耗 offset，offset 吃完（回到 (0,0)）后漂移会重新开始。

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
                // **同时直接落到当前会话名下**：切走时 `state.conversationId` 已经变了，
                // 那一刻再 saveSpot 可能取到新会话布局的值 —— 这里落盘就没有时序问题。
                if (scroll.conversationId.isNotEmpty()) {
                    scroll.spots[scroll.conversationId] = key to offset
                }
            }
            wasScrolling = scrolling
        }
    }
    return scroll
}

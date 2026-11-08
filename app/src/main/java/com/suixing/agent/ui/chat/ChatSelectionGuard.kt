package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Snapshot reads stay in gestures/effects; pressing does not subscribe the whole screen. */
internal class ChatReadingGesture {
    val pressedState = mutableStateOf(false)
    var pressed by pressedState
    val pressUpAtState = mutableLongStateOf(0L)
    var pressUpAt by pressUpAtState
    val pressGuardState = mutableStateOf(false)
    var pressGuardOn by pressGuardState
    val pressMovedState = mutableStateOf(false)
    var pressMoved by pressMovedState
    val revealBlocked = mutableStateOf(false)
    val lastRevealRequestAt = mutableLongStateOf(0L)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberChatSelectionGuard(
    gesture: ChatReadingGesture,
    listState: LazyListState,
    scroll: ChatScroll,
    viewConfig: ViewConfiguration,
    setSelectionActive: ((Boolean) -> Unit)?,
): BringIntoViewSpec = with(gesture) {
    LaunchedEffect(Unit) {
        snapshotFlow { pressed }.collectLatest { down ->
            if (!down) return@collectLatest
            delay(viewConfig.longPressTimeoutMillis)
            if (!pressMoved) pressGuardOn = true
        }
    }
    // **#14 的根因（真机日志 2026-10-21 00:25 坐实，不是推断）**：
    // 长按建立选区后，框架会为「把选中的内容滚进视口」发起一次 bring-into-view，走的就是
    // `LocalBringIntoViewSpec`。日志证据：`guard-on` 在 0/0，365ms 后开始逐帧位移
    // （d=148/137/59/52/44/36/30 … 1），同期请求序列 `want=-621 → -473 → -336 → … → -1`，
    // 合计 621px，与 `lock-would-correct offset-drift=621` 完全吻合；全程 `moved=false`（手指没动）。
    // `size=2507 / container=2510` —— 被选中的消息几乎有整屏高，框架试图把它整个露出来。
    //
    // 修法是**在源头拒绝**，不是事后纠回去（事后纠会先跳一下再跳回来）：
    // 按下期间让「要滚多远」恒为 0，选区的 bring-into-view 就推不动列表。
    //
    // 为什么还要一段「安静期」：请求是逐帧来的，只挡到抬手的话动画还没收完，
    // 之后的帧照样把剩下的距离滚掉。所以每次被挡的请求都续期，请求停止 500ms 后才放行。
    // 选区按需武装（#27/#42③）：guard 武装（长按建立选区）→ true；
    // 滚动开始且手指不在屏上 → false（滚动会取消文本选区，解除安全）。
    val defaultBringIntoView = LocalBringIntoViewSpec.current
    val revealGuardSpec = remember(defaultBringIntoView) {
        // **严格等价**：不拦的时候，两个成员都原样转发 —— 位移量 `calculateScrollDistance`
        // 与动画规格 `scrollAnimationSpec`。少转发后者会落到接口默认实现上，
        // 只要原规格用的不是接口默认，滚动动画就变了。
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float>
                get() = defaultBringIntoView.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (!revealBlocked.value) {
                    return defaultBringIntoView.calculateScrollDistance(offset, size, containerSize)
                }
                // 逐帧续期：被挡的请求还在来，说明这套 reveal 动画没结束，别提前放行。
                lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
                return 0f
            }
        }
    }
    // 安静期：请求停止 500ms（远长于 60fps 的一帧）才放行，避免动画收尾时把剩下的距离滚掉。
    // **组合期不读 `revealBlocked`**：原来 `LaunchedEffect(revealBlocked.value)` 把
    // 一个 State 当 key —— 每次长按武装/解除各重组一次整屏，而这台机器上长按/甩动都在
    // 手势热路径上。改成协程内 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    LaunchedEffect(Unit) {
        snapshotFlow { revealBlocked.value }.collectLatest { blocked ->
            if (!blocked) return@collectLatest
            while (revealBlocked.value) {
                delay(100)
                if (SystemClock.uptimeMillis() - lastRevealRequestAt.longValue > 500L) {
                    revealBlocked.value = false
                }
            }
        }
    }
    // **不能把 `pressGuardOn` 当 LaunchedEffect 的 key**：它是 `by pressGuardState` 的 State，
    // 出现在 key 位就是组合期订阅 ⇒ 每次长按武装/解除各**整屏重组一次**（~58 个子组合）。
    // 同文件上面 `pressed` / `revealBlocked` 已用 snapshotFlow 修掉，这里漏了。
    LaunchedEffect(Unit) {
      snapshotFlow { pressGuardOn }.collectLatest { on ->
        if (!on) return@collectLatest
        // 按下就开始拦「把选中内容滚进视口」的请求（#14）：框架的这次滚动是为了露出选区，
        // 而用户按住的正是眼前这段文字，露出它没有意义，只会把列表推走。
        lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
        revealBlocked.value = true
        setSelectionActive?.invoke(true)
        // **只在结束时纠一次**（issue #14 建议）：v0.8.238 那版是逐帧纠 + 用数组下标当 item index，
        // 把列表甩到了底部。这里三个关键区别：
        //  · 只在 `finally` 里纠正一次，不逐帧写滚动位置；
        //  · 用 `target.index`（LazyColumn 给的全局 index），不是 `indexOfFirst` 的数组下标；
        //  · 不判断 `pressMoved` —— 选区扩展也是手指移动，那种情况同样需要纠。
        val lockKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        val lockOffset = listState.firstVisibleItemScrollOffset
        val latestRequestGeneration = scroll.latestRequestGeneration
        try {
            while (pressGuardOn) {
                if (!pressed && SystemClock.uptimeMillis() - pressUpAt >= LONG_PRESS_SETTLE_MS) {
                    pressGuardOn = false
                }
                withFrameNanos { }
            }
        } finally {
            // 按下到抬手后 400ms 内，如果列表位置被推走
            // （选区重排造成，手指没动），就关掉追底 —— 而不是把位置纠回去（0.8.242 的教训）。
            // A later explicit latest click owns the position, even when this older
            // selection's settling window has not finished yet.
            if (lockKey != null && latestRequestGeneration == scroll.latestRequestGeneration) {
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lockKey }
                when {
                    target == null -> Unit
                    target.index == listState.firstVisibleItemIndex -> {
                        val drift = listState.firstVisibleItemScrollOffset - lockOffset
                        if (abs(drift) > 1 && !pressMoved) scroll.stickToBottom = false
                    }
                    else -> if (!pressMoved) scroll.stickToBottom = false
                }
            }
            // 组合被带走（切会话/退页）也要解冻，否则外壳的 IME clamp 永远停摆。
            setSelectionActive?.invoke(false)
        }
      }
    }

    revealGuardSpec
}

package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

/** The chronological transcript's drag/selection policy, independent of its message rendering. */
internal fun Modifier.chatTranscriptGesture(
    conversationId: String,
    gestureState: ChatReadingGesture,
    navigationJob: Array<Job?>,
    scroll: ChatScroll,
    selectionTracker: SelectionFocusTracker,
    viewConfig: ViewConfiguration,
    selectionFocus: FocusManager,
    setSelectionActive: ((Boolean) -> Unit)?,
): Modifier = pointerInput(conversationId) {
    with(gestureState) {

            try {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                        if (down.changes.none { it.pressed }) continue
                        if (navigationJob[0]?.isActive == true) {
                            navigationJob[0]?.cancel()
                            scroll.restoring = false
                        }
                        val downPoint = down.changes.first().position
                        val downY = downPoint.y
                        // 横坐标也要记：只看 dy 会把侧滑时的纵向抖动当成阅读（§131）。
                        val downX = downPoint.x
                        val downAt = SystemClock.uptimeMillis()
                        pressed = true
                        // **不在这里 arm 守护**（跟手性）：原来每次按下都置 true，而
                        // 只有「没移动且短于长按阈值」才会立刻撤销 ⇒ **任何拖动手势之后
                        // 守护都会保持 [LONG_PRESS_SETTLE_MS]（400ms）**，正好盖住整段
                        // 惯性滚动：这期间有一个逐帧协程在跑，还冻结着外壳的 IME clamp。
                        // 现在改由下面的 `LaunchedEffect(pressed)` 在**真的到长按**时才 arm。
                        pressMoved = false
                        // **但 reveal 拦截要在按下瞬间就开**（长按回归根因，实测 lp_trace.log）：
                        // 文本选区的 bringIntoView 比长按超时早 ~28ms 到达，头 5 条放行请求
                        // 把列表推走 228px —— 等 guard-on 再拦已经晚了。上面注释否掉的是
                        // 「arm 整套守护」（逐帧协程 + IME 冻结，会盖住惯性滚动）；
                        // 这里只置 revealBlocked 标志（零成本、只拦 reveal 请求），
                        // 识别为滚动时立刻解除 —— 与那条教训不冲突。
                        revealBlocked.value = true
                        lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
                        val gesture = DragGestureState()
                        var released = false
                        while (!released) {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            val change = next.changes.firstOrNull()
                            // **任一方向**越过 4px 都算拖动。只认"手指上移"是错的：顶锚列表里
                            // 读上面的内容是**手指下移**（把内容拉下来），纵向判定必须覆盖两个方向。
                            val pos = change?.position
                            val dy = (pos?.y ?: downY) - downY
                            val dx = (pos?.x ?: downX) - downX
                            // **两件事分开**（§131）：
                            //   · 任一方向越阈值 ⇒ 手指在拖，取消长按/点击并解除 reveal 拦截；
                            //   · 只有纵向主导才取消追底 —— 侧滑抽屉时的手指抖动不该让内容停跟。
                            // 两个 one-shot 标记彼此独立：先横后竖仍要在首次纵向时停跟（#73）。
                            val intent = dragIntent(dx, dy)
                            val isPressed = change?.pressed == true
                            if (gesture.markMoved(intent, isPressed)) {
                                pressMoved = true
                                revealBlocked.value = false
                            }
                            // 每次手势只写一次：setter 不做等值短路，逐事件写会让本组合体反复失效。
                            if (gesture.markFollowCancelled(intent, isPressed)) {
                                scroll.allowBottomResume = true
                                scroll.stickToBottom = false
                            }
                            released = next.changes.none { it.pressed }
                        }
                        pressed = false
                        pressUpAt = SystemClock.uptimeMillis()
                        // Selection's own release/drag-end handlers run in Main.
                        // Clearing focus after them prevents an old manager from
                        // re-showing its toolbar after a blank tap closed it.
                        awaitPointerEvent(PointerEventPass.Final)
                        if (selectionTracker.active && !gesture.moved && pressUpAt - downAt < viewConfig.longPressTimeoutMillis) {
                             selectionTracker.clear()
                            selectionFocus.clearFocus()
                        }
                        // 短按/拖动都不需要撤销：守护从没 arm 过（见上面的说明），
                        // 只有**长按**那条路才会打开它，并由 `LaunchedEffect(pressGuardOn)`
                        // 里的 400ms 结算逻辑关掉。
                    }
                }
            } finally {
                // A chat switch cancels the old gesture as well as its captured
                // selection tracker. It must not leave IME/follow guards armed.
                pressed = false
                pressUpAt = SystemClock.uptimeMillis()
                pressMoved = false
                pressGuardOn = false
                revealBlocked.value = false
                setSelectionActive?.invoke(false)
            }

    }
}

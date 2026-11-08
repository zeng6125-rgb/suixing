package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Turn rail navigation resolves stable row identity again after every suspension. */
@Composable
internal fun BoxScope.ChatTurnNavigation(
    state: ChatUiState,
    transcript: ChatTranscriptProjection,
    live: Boolean,
    actions: ChatScreenActions,
    scope: CoroutineScope,
    scroll: ChatScroll,
    transcriptPosition: TranscriptPosition,
    prepareRows: suspend (List<ChatRow>) -> Unit,
    imePxState: State<Int>,
) {
    val viewport = transcript.viewport
    val listState = transcript.listState
    val rowKeys = transcript.rowKeys
    val rowKeyIndices = transcript.rowKeyIndices
    val rowOffset = transcript.rowOffset
    val itemCount = transcript.itemCount
    val page = transcript.page
    val visible = transcript.visible
    val turnIndices = transcript.turnIndices
    val currentReadingTurn = transcript.currentReadingTurn
    val navigationToken = transcript.navigationToken
    val navigationJob = transcript.navigationJob
    val switchEntrance = viewport.entrance
    // The jump-to-latest button lives inside the composer stack (first row of
    // its column): 12dp above whatever the stack's top row is, clear of the
    // pill — anchored to the reading area's bottom it hid behind the capsule.
    // One line per turn on the right edge; the old ◀ i/N ▶ row lived in the
    // composer and fought the status line for width.
    // **窗口第一轮的全局轮号**：`page.earlier` = 上面还有几轮没加载 ⇒ 窗口从
    // `earlier + 1` 开始（`TurnPage.earlier = turns - drawn`）。
    // **不要用 `turnIndices.size` 反推**（这里原来就是）：流式期间最新那一轮的行被
    // `runId != liveRunId` 过滤掉，`turnIndices` 比 `page.drawn` 少一项 ⇒ 反推出来的
    // 起点偏一轮 ⇒ 标签念错一句、点最新那条还会越界（`getOrNull` 拿到 null，
    // 于是一下都不动 —— 用户说的「点不中 / 对应不上」）。全局轮号才是真坐标。
    val firstDrawnTurn = page.earlier + 1
    // **点击后立刻高亮目标轮**：导航按距离播放动画，若等滚动经过再更新高亮，
    // 观感就是"点了没反应"。这里先乐观置位，导航结束时清掉（用户要求实时响应）。
    var pendingTurn by remember(state.conversationId) { mutableStateOf<Int?>(null) }
    val navigationTargetNow = rememberUpdatedState { targetKey: String ->
        navigationTargetIndex(
            targetKey, rowKeyIndices, rowOffset,
            if (live || state.runningHere) liveRowKey(state.conversationId, state.liveRunId) else null,
            itemCount - 1,
        )?.let { index -> index to visible.getOrNull(index - rowOffset) }
    }
    val prepareForNavigation = rememberUpdatedState<suspend (List<ChatRow>) -> Unit>(prepareRows)
    val scrubberSettling by remember(viewport, scroll) {
        derivedStateOf {
            scrubberHighlightSettling(
                // A rail jump pauses reading-anchor repair, but should retain
                // the tick's own animation. Only restoration/entrance snaps it.
                restoring = scroll.restoring && pendingTurn == null,
                presented = viewport.presented,
                entrance = viewport.entrance.value,
            )
        }
    }
    ScrubberPresentation(state.conversationId,
        presented = { viewport.presented },
        entrance = { switchEntrance.value }) { entranceModifier ->
        TurnScrubber(
            conversationId = state.conversationId,
            totalTurns = page.turns,
            // 已加载的轮数用 `page.drawn`（窗口的**定义**）：条数、窗口起点、
            // 「已加载到第几轮」全按它算，与 `firstDrawnTurn` 同一口径。
            drawnTurns = page.drawn,
            current = { pendingTurn ?: currentReadingTurn.value },
            // 恢复位置及会话入场完成前不做补间，避免高亮从临时轮次向真实轮次下扫。
            settling = scrubberSettling,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(entranceModifier)
                // 键盘抬起时导航条跟着上移（用户：「抬起时为什么导航条不动」）。
                // 整页不再按 IME 缩高之后，CenterEnd 的居中点是满高的 H/2，而旧结构里
                // 居中在 (H − I) 的框内 ⇒ 等价于往上挪 I/2。只在**放置阶段**读 IME，
                // 不触发重组、不重测列表。
                .offset { androidx.compose.ui.unit.IntOffset(0, -(imePxState.value / 2)) }
                // **离右边缘 4dp**（用户要求在 4dp 上「稍微收一点」的同时定了这个数）。
                // 观感/位置只按你说的数字动。
                .padding(end = 4.dp),
            labelOf = { turn ->
                // `turn` 是**全局轮号**（1 基），标签直接用它。
                // 找这一轮的开头文字要换算成**窗口内**下标（0 基）：`turnIndices` 里装的是
                // 窗口内各轮的开头行，窗口第一轮 = 全局 `firstDrawnTurn`。
                val inWindow = turn - firstDrawnTurn
                val opening = visible.getOrNull(
                    turnIndices.getOrElse(inWindow) { -1 } - rowOffset,
                )
                    ?.message
                    ?.text
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
                if (opening.isEmpty()) {
                    "第 $turn 轮"
                } else {
                    "第 $turn 轮 · ${opening.take(8)}"
                }
            },
            onJumpTo = jump@{ turn ->
                // Convert the global turn to a stable target identity. Only the
                // actual live newest turn may fall back to the trailing live card.
                val inWindow = turn - firstDrawnTurn
                val index = turnIndices.getOrNull(inWindow)
                val targetKey = index?.let { rowKeys.getOrNull(it - rowOffset) }
                    ?: if (turn == page.turns && (live || state.runningHere)) {
                        liveRowKey(state.conversationId, state.liveRunId)
                    } else null
                if (targetKey == null) return@jump
                pendingTurn = turn
                val token = ++navigationToken[0]
                val previousNavigation = navigationJob[0]
                previousNavigation?.cancel()
                navigationJob[0] = scope.launch {
                    try {
                        previousNavigation?.join()
                        // **等呈现要有上限**（审计 5.28）：`presented` 由恢复协程在
                        // 「定位 + 补读更早页」之后置位，那次恢复若早退或被取消，这个
                        // `first{}` 永不返回；而 `pendingTurn` 只在 token 匹配的 finally
                        // 里清除 ⇒ 导航条高亮会一直钉在被点的那一轮。超时只是让这次跳转
                        // 作废（用户再点一下），不会动滚动几何与命中逻辑。
                        if (withTimeoutOrNull(NAVIGATION_PRESENT_TIMEOUT_MS) {
                                snapshotFlow { viewport.presented }.first { it }
                            } == null) return@launch
                        if (token != navigationToken[0] || actions.state.value.conversationId != state.conversationId) return@launch
                        scroll.allowBottomResume = false
                        scroll.stickToBottom = false
                        scroll.restoring = true
                        // Suspend eager capture until the final child placement. LazyList
                        // may publish layoutInfo before text LayoutCoordinates have moved.
                        transcriptPosition.rebaseAfterNavigation()
                        var target = navigationTargetNow.value(targetKey) ?: return@launch
                        target.second?.let { prepareForNavigation.value(listOf(it)) }
                        if (token != navigationToken[0] || actions.state.value.conversationId != state.conversationId) return@launch
                        // Preparation suspends; paging/settlement may move the row.
                        // Re-resolve the identity instead of scrolling a stale index.
                        target = navigationTargetNow.value(targetKey) ?: return@launch
                        if (target.second != null) {
                            val layout = listState.layoutInfo
                            // Both nearby and distant targets arrive through scrolling.
                            viewport.navigation.navigate(listState, target.first,
                                TurnMarks.navigationScrollOffset(layout.viewportStartOffset, layout.viewportEndOffset))
                        } else {
                            viewport.navigation.navigate(listState, target.first, TRANSCRIPT_END_OFFSET)
                        }
                    } finally {
                        if (token == navigationToken[0]) pendingTurn = null
                        if (token == navigationToken[0] && scroll.conversationId == state.conversationId) {
                            scroll.restoring = false
                            // Request a placement handoff, not a capture of coordinates
                            // from the frame before the last scroll delta was placed.
                            transcriptPosition.rebaseAfterNavigation()
                        }
                    }
                }
            },
        )
    }

}
/**
 * 导航条跳转最多等「内容已呈现」多久（审计 5.28）。
 *
 * 恢复协程置 `presented` 之前要定位、必要时补读更早的页；它若早退/被取消，跳转协程会永远
 * 停在 `snapshotFlow { presented }.first { it }` 上，而 `pendingTurn` 只在 token 匹配的
 * finally 里清除 ⇒ 高亮一直钉在被点的那一轮。10s 远长于一次正常恢复，超时只是让这次跳转
 * 作废，用户再点一下即可。
 */
private const val NAVIGATION_PRESENT_TIMEOUT_MS = 10_000L


package com.suixing.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Which line a finger at [y] is on. Rows are found by their centre rather than by their box,
 * so the air between two lines belongs to whichever is nearer — that is what allows the strip
 * to be packed tighter than a fingertip is wide.
 */
internal fun scrubberRowFor(y: Float, rowPx: Float, pitchPx: Float, rows: Int): Int {
    if (rows <= 1) return 0
    return ((y - rowPx / 2f) / pitchPx).roundToInt().coerceIn(0, rows - 1)
}

/**
 * The turns the strip draws: [SCRUBBER_VISIBLE] lines ending at the newest turn, slid up so the
 * turn being read is on them, and — while a finger has pulled past the top line — slid up
 * [unlocked] turns further, so the turns above the window can be reached.
 *
 * The window is a fixed number of lines that moves rather than one that grows. Growing it would
 * change the rail's height mid-drag, and the rail is centred: every line would shift down by
 * half of what was added, under a finger that is holding one of them. Sliding keeps every line
 * where it is and changes only which turn each one names, so the line under the finger is always
 * the line the label is on.
 */

/** The rail's width, and so every row's hit width: the longest line the rail draws. */
internal val SCRUBBER_RAIL = 24.dp

/**
 * One row's box. Shorter than the old 18dp because the finger is matched to the nearest line
 * instead of having to land inside a box — the rows no longer need to be big enough to hit,
 * only far enough apart to tell apart.
 */
internal val SCRUBBER_ROW = 14.dp

/** Air between rows: half the old 6dp, which was most of what made the strip read as sparse. */
internal val SCRUBBER_GAP = 3.dp

/** How far the finger travels before a press turns into a drag. Aether's own threshold. */
internal val SCRUBBER_DRAG_THRESHOLD = 18.dp

/** Drifting this far left of the rail drops the jump. Aether's own exit tolerance. */
internal val SCRUBBER_EXIT_TOLERANCE = 28.dp

/**
 * How long a still finger waits before the label appears. Shorter than the platform's long
 * press because a scrubber has to answer at once, but long enough that a plain tap on a line
 * never flashes a label on its way to jumping.
 */
internal const val SCRUBBER_LONG_PRESS_MS = 220L

/** The label's fade in, and its fade out once the finger lifts. Aether's own pair. */
internal const val SCRUBBER_LABEL_IN_MS = 150
internal const val SCRUBBER_LABEL_OUT_MS = 110

/**
 * How long the label takes to glide to the line the finger has reached. Deliberately the same
 * 110ms as the line's own growth above, so the two land together and the label never outruns
 * the line it names.
 */
internal const val SCRUBBER_SELECT_MS = 110

/** Air between the rail and the label. */
internal val SCRUBBER_LABEL_GAP = 6.dp

/** The label's longest line, so it never covers the bubble it floats over. */
internal val SCRUBBER_LABEL_MAX = 180.dp

/**
 * The label bubble's own cap: [SCRUBBER_LABEL_MAX], or what is left of the window once the rail it
 * sits beside, its gap and the two margins are taken out.
 *
 * The bubble is drawn to the rail's left and the rail hugs the right edge, so a flat 180dp ran off
 * the left of a narrow window — the split-screen case, where the window is not the display.
 */
internal fun scrubberLabelMax(windowWidth: Dp): Dp = minOf(
    SCRUBBER_LABEL_MAX,
    windowWidth - POPUP_EDGE * 2 - SCRUBBER_RAIL - SCRUBBER_LABEL_GAP,
)

/** How far a line grows once its label is out, and while it is merely touched. */
internal const val SCRUBBER_PRESS_SCALE = 1.4f
internal const val SCRUBBER_TOUCH_SCALE = 1.12f

/** Aether's motion curve, borrowed for the row scale and the label's entry and exit. */
internal val SCRUBBER_EASING = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

/** 滚动条刻度（列表里每行一个，原来每次重组都 new）。 */
private val ScrubTickShape = RoundedCornerShape(1.dp)

/** 滚动条标签卡圆角。 */
private val ScrubLabelShape = RoundedCornerShape(8.dp)

/** Only touched/chosen rows and rows still shrinking own animation state. */
private class ScrubberScales(private val scope: CoroutineScope) {
    private class Entry {
        val animation: Animatable<Float, AnimationVector1D> = Animatable(1f)
        var target = 1f
        var job: Job? = null
    }

    private val entries = mutableStateMapOf<Int, Entry>()

    /** Called from graphicsLayer: neither map changes nor frames subscribe composition. */
    fun value(turn: Int): Float = entries[turn]?.animation?.value ?: 1f

    fun update(visible: IntRange, chosen: Int?, pressed: Int?) {
        for (turn in entries.keys.toList()) {
            if (turn !in visible) {
                entries.remove(turn)?.job?.cancel()
            } else if (turn != chosen && turn != pressed) {
                target(turn, 1f)
            }
        }
        pressed?.takeIf { it != chosen && it in visible }?.let { target(it, SCRUBBER_TOUCH_SCALE) }
        chosen?.takeIf { it in visible }?.let { target(it, SCRUBBER_PRESS_SCALE) }
    }

    private fun target(turn: Int, scale: Float) {
        val entry = entries[turn] ?: if (scale == 1f) return else Entry().also { entries[turn] = it }
        if (entry.target == scale) return
        entry.target = scale
        entry.job?.cancel()
        entry.job = scope.launch {
            entry.animation.animateTo(scale, tween(110, easing = SCRUBBER_EASING))
            // A newer target can have replaced this job; only a completed return to rest
            // releases the row. Leaving a row never snaps its scale back in the same frame.
            if (scale == 1f && entry.target == 1f && entries[turn] === entry) {
                entries.remove(turn)
            }
        }
    }

    fun clear() {
        entries.values.forEach { it.job?.cancel() }
        entries.clear()
    }
}

/**
 * One horizontal line per turn, stacked on the list's right edge: the user asked for
 * 「一条一条横线」 in place of the old ◀ i/N ▶ arrows, which also fought the status
 * line for width inside the composer.
 *
 * Only the newest few turns are drawn, one line each — no sampling, so every line is a real
 * turn and a tap lands where the eye aims — and the window slides up when the user is
 * reading something older than it, so "where am I" is always on screen. The turn being read
 * is the longest line and the only brand-coloured one; turns already read keep a weaker
 * brand tint, and the ones still ahead are outline grey.
 *
 * Dragging to the top line unlocks what is above it: the window slides up for as long as the
 * finger keeps pushing past the rail's top edge, one turn per row of travel, so a long
 * conversation is reachable in a single drag without the strip ever changing size. The unlocked
 * amount belongs to the conversation it was pulled in and is dropped when another is opened.
 *
 * Holding the strip brings up a label naming the turn under the finger, and dragging then
 * walks that label along the lines; lifting commits the jump. The whole rail is one gesture
 * handler rather than a tap target per row, which is what lets the rows sit close together:
 * the finger is matched to the nearest line, so it never has to land inside one.
 * (Interaction and the drag/exit thresholds are borrowed from Aether's ConversationTimeline;
 * the code here is our own.)
 */
@Composable
internal fun TurnScrubber(
    /** The conversation whose window this is: opening another one resets it. */
    conversationId: String,
    /**
     * **会话总轮数**（全局 1 基，最新那轮 = 这个数）。
     *
     * 旧参数名叫 `turns`、装的是"窗口里画了几轮"（≤8），而标签按全局轮号算、条数也按全局算 ——
     * 同一个下标被当两种坐标用，于是条数涨不上去、标签与行错位（用户：导航条对应轮次不对）。
     * 现在统一：这里给总轮数，窗口由 [scrubberWindow] 从总轮数 + 当前轮推出。
     */
    totalTurns: Int,
    /**
     * **已经加载出来的轮数**（窗口之外的还在盘上，没加载就不能跳过去）。
     */
    drawnTurns: Int,
    // **延迟读**（issue #26 原因一）：值随滚动每跨一个 item 就变；若由调用方在组合体里算好再传，
    // 每次变化都会让整个聊天页重组。改成 lambda 后只有本组件自己读、自己重组。
    current: () -> Int,
    modifier: Modifier = Modifier,
    labelOf: (Int) -> String,
    onJumpTo: (Int) -> Unit,
) {
    // 一轮都没有 ⇒ 不画（只有一轮时导航条也没有意义）。
    if (totalTurns <= 1) return
    val scheme = MaterialTheme.colorScheme
    // How far above the window the finger has pulled the strip — the turns it has unlocked in
    // this conversation. Kept for as long as the chat is open, dropped the moment another is
    // opened: a deep conversation unlocked in one chat must not decide what the next one shows.
    var unlocked by remember(conversationId) { mutableIntStateOf(0) }
    // **长按展开更多条**（用户：长按应显示更多轮次导航条）。状态不保存：重开应用/切会话都重置。
    var expanded by remember(conversationId) { mutableStateOf(false) }
    // The last few turns, always including the one being read: sampling the whole
    // conversation into five evenly spaced lines is what made "which line is which turn"
    // impossible to tell, and made the jump land somewhere the user did not point at.
    //
    // **current() 现在返回全局轮号**（1 基），与 [scrubberWindow] 的坐标一致。
    val currentTurn = current()
    val window = remember(totalTurns, drawnTurns, currentTurn, unlocked, expanded) {
        scrubberWindow(
            totalTurns = totalTurns,
            currentTurn = currentTurn,
            drawnTurns = drawnTurns,
            unlocked = unlocked,
            // 长按展开：把**已加载的全部**轮次都画出来（Aether 的 `navigating` 分支同理）。
            forceRows = if (expanded) drawnTurns else 0,
        )
    }
    val rows = remember(window) { window.toList() }
    val rowCount = rows.size
    // 窗口可能为空而会话不止一轮：元信息已知有 N 轮、正文还没读回来（drawnTurns = 0）时
    // [scrubberWindow] 返回空区间。少了这一行会**崩溃** —— 下面的
    // `selectionRow.coerceIn(0, rowCount - 1)` 在 rowCount = 0 时是 coerceIn(0, -1)，
    // Kotlin 直接抛 IllegalArgumentException（Cannot coerce value to an empty range）。
    if (rowCount == 0) return
    // A gesture outlives the recomposition it started in, so it reads the turns and the
    // jump through these instead of closing over values a new message has replaced.
    val latestRows by rememberUpdatedState(rows)
    val latestJump by rememberUpdatedState(onJumpTo)
    // Which row the finger is on, which row the label names, and whether the label is out.
    // All three are cleared when the finger lifts, so no frame can leave the rail stuck open.
    var pressRow by remember { mutableIntStateOf(-1) }
    var selectionRow by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val selected = selectionRow.coerceIn(0, rowCount - 1)
    val animationScope = rememberCoroutineScope()
    val rowScales = remember(conversationId, animationScope) { ScrubberScales(animationScope) }
    DisposableEffect(rowScales) {
        onDispose { rowScales.clear() }
    }
    SideEffect {
        rowScales.update(
            visible = window,
            chosen = if (shown) rows.getOrNull(selected) else null,
            pressed = rows.getOrNull(pressRow),
        )
    }

    val density = LocalDensity.current
    val rowPx = with(density) { SCRUBBER_ROW.toPx() }
    val gapPx = with(density) { SCRUBBER_GAP.toPx() }
    val railPx = with(density) { SCRUBBER_RAIL.toPx() }
    val labelGapPx = with(density) { SCRUBBER_LABEL_GAP.toPx() }
    // The label rides beside its own row, and only the rows know where they sit, so it is
    // measured off the rail's vertical centre: the strip itself is centred on the reading
    // area and its height changes with the number of turns in the window.
    val railHeightPx = rowPx * rowCount + gapPx * (rowCount - 1)
    // Animated, not computed straight from the row: dragging the finger down three lines then
    // has the label glide to each one instead of teleporting, which is what makes the drag read
    // as moving along the strip rather than as three separate taps.
    val labelDyPx by animateFloatAsState(
        targetValue = selected * (rowPx + gapPx) + rowPx / 2f - railHeightPx / 2f,
        animationSpec = tween(SCRUBBER_SELECT_MS, easing = SCRUBBER_EASING),
        label = "scrubber_label_dy",
    )

    // Full width for the *measuring constraint only*: the label is a child of this box, and a
    // child can never be measured wider than its parent — at the rail's own 24dp the bubble was
    // squeezed into a 24dp-wide square with 9dp of padding either side, which left about 6dp
    // for the text and ellipsized it to a single character. That square is the dark blob the
    // user photographed. The gesture lives on the rail itself below (24dp wide, the height of
    // the drawn lines), because a full-width column here turned every touch across the
    // transcript's right edge into a scrubber press: a long press meant to select text landed
    // on a turn, the jump on the lift moved the list, and the selection went with it.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                // The touch area is the rail and nothing else: exactly the width of the longest
                // line drawn, and the height of the rows plus their gaps.
                .width(SCRUBBER_RAIL)
                .align(Alignment.CenterEnd)
                // One handler for the whole rail instead of a clickable per row: the finger
                // is matched to the nearest line, so a press between two lines still lands
                // on one of them and a drag can cross rows without dropping the gesture.
                .pointerInput(Unit) {
                    val hitRowPx = SCRUBBER_ROW.toPx()
                    val hitGapPx = SCRUBBER_GAP.toPx()
                    val hitPitchPx = SCRUBBER_ROW.toPx() + SCRUBBER_GAP.toPx()
                    val railWidthPx = SCRUBBER_RAIL.toPx()
                    val dragPx = SCRUBBER_DRAG_THRESHOLD.toPx()
                    val exitPx = SCRUBBER_EXIT_TOLERANCE.toPx()
                    awaitEachGesture {
                        val railRows = latestRows
                        if (railRows.isEmpty()) return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startRow =
                            scrubberRowFor(down.position.y, hitRowPx, hitPitchPx, railRows.size)
                        pressRow = startRow
                        selectionRow = startRow
                        shown = false
                        var travel = 0f
                        // Only a gesture that really moved is eligible for the off-rail cancel
                        // below; a tap and a still hold always jump.
                        var dragged = false
                        var pointer = down.position
                        // True only until the hold has run out; after that the pointer is
                        // awaited without a deadline, so a slow drag is never cut short.
                        var waiting = true
                        var pressed = true
                        while (pressed) {
                            val event = if (waiting) {
                                withTimeoutOrNull(SCRUBBER_LONG_PRESS_MS) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // The hold ran out: the label comes up and stays up until the
                                // finger lifts. This is the "press and hold to see it" half.
                                waiting = false
                                shown = true
                                // 长按到点：顺带把导航条展开到最多条数（松手即恢复）。
                                expanded = true
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                pressed = false
                                continue
                            }
                            // Both axes, but only the dominant one per event: summing them
                            // doubled the jitter during a still hold and could pop the label
                            // before the 220ms hold, while counting Y alone let a purely
                            // horizontal drag off the rail slip through as a jump.
                            travel += max(
                                abs(change.position.y - pointer.y),
                                abs(change.position.x - pointer.x),
                            )
                            pointer = change.position
                            if (travel >= dragPx) {
                                // Dragging is an answer in itself, so it skips the rest of the
                                // hold and starts previewing turns at once.
                                waiting = false
                                dragged = true
                                shown = true
                                selectionRow = scrubberRowFor(
                                    change.position.y,
                                    hitRowPx,
                                    hitPitchPx,
                                    railRows.size,
                                )
                                // On the top line the strip gives ground: a finger that keeps
                                // pushing up unlocks the turns above the window, one row of
                                // travel at a time, so the whole conversation is reachable in
                                // one drag. The window slides rather than grows, so the rows
                                // never move under the hand and the line the finger is on stays
                                // the line it is on.
                                unlocked = scrubberPull(
                                    y = change.position.y,
                                    rowPx = hitRowPx,
                                    pitchPx = hitPitchPx,
                                    pull = unlocked,
                                )
                            }
                            pressed = change.pressed
                            // Consumed here so the transcript under the rail never scrolls
                            // along: dragging the strip previews turns, it does not move the
                            // conversation. The jump happens once, on the lift.
                            change.consume()
                        }
                        val destination = selectionRow
                        // A finger that has really dragged and then wanders off the strip was
                        // after the transcript rather than a turn, so the jump is dropped and the
                        // strip springs back. Tested on the two sides and the bottom of the rail —
                        // not the top. Pushing up past the first line is precisely how the turns
                        // above the window are unlocked (see [scrubberPull]), and cancelling there
                        // made the user's own gesture work against itself: one turn of unlock is
                        // about 17dp of travel, and this tolerance killed the whole gesture at
                        // 28dp — so the harder they pulled for more history, the more certainly
                        // the lift did nothing at all. Which is what "导航条条数没增加" is: the
                        // strip had unlocked a line or two and then thrown the jump away.
                        //
                        // Measured here rather than read from the composition's own rail height:
                        // this handler is keyed on Unit and never restarts, so a window that has
                        // since gained or lost a turn would be judged against a stale box. It
                        // cannot: the window is a fixed number of lines that slides, so its size —
                        // the only thing this needs — is the same at the lift as at the press.
                        val railBottomPx =
                            hitRowPx * railRows.size + hitGapPx * (railRows.size - 1)
                        val cancelled = dragged && (
                            pointer.x < -exitPx ||
                                pointer.x > railWidthPx + exitPx ||
                                pointer.y > railBottomPx + exitPx
                            )
                        pressRow = -1
                        expanded = false
                        shown = false
                        // The strip recentres on wherever the jump lands: the unlocked turns
                        // were the ones this drag could reach, and the window goes back to
                        // naming the turn being read instead of staying slid up over turns the
                        // reader has not left yet.
                        unlocked = 0
                        // [latestRows], not the snapshot the hit test was matched against: a
                        // drag that unlocked its way up the conversation is aimed at a turn
                        // that snapshot never held.
                        if (!cancelled) latestRows.getOrNull(destination)?.let(latestJump)
                    }
                },
            verticalArrangement = Arrangement.spacedBy(SCRUBBER_GAP),
            horizontalAlignment = Alignment.End,
        ) {
            rows.forEachIndexed { row, turn ->
                // `turn` 是**全局轮号**（1 基）：比当前轮早的是"已读过"，等于当前轮的是"正在读"。
                val read = turn < currentTurn
                val active = turn == currentTurn
                val chosen = shown && row == selected
                Box(
                    modifier = Modifier.height(SCRUBBER_ROW),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            // The turn being read is the longest and the only full-strength
                            // brand line. It used to fall into the "not reached" branch and come
                            // out grey, which is exactly backwards.
                            // **细条，宽度再收一档**（用户：「条宽比例缩小一下先」）：
                            // 当前轮 / 已读 / 未读 = 20 / 16 / 10 dp（原 24/20/12 的等比例缩小），
                            // 高度不动（3/2dp 已经是最细的可读档）。
                            .width(if (active) 20.dp else if (read) 16.dp else 10.dp)
                            .height(if (active) 3.dp else 2.dp)
                            // Grown out of its right edge: the enlargement then never walks the
                            // line off the 4dp edge gap, and never re-lays-out the rows under a
                            // finger that is still dragging.
                            .graphicsLayer {
                                val scale = rowScales.value(turn)
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                            .clip(ScrubTickShape)
                            .background(
                                when {
                                    // Lit while it is the one being previewed, so dragging
                                    // onto a turn that has not been read yet still shows up.
                                    chosen -> scheme.primary
                                    active -> scheme.primary
                                    read -> scheme.primary.copy(alpha = 0.45f)
                                    else -> scheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                // Layout-neutral on purpose: the label hangs off the rail's left edge and must
                // not widen it, or the strip would drift out over the bubbles it clears today.
                .offset { IntOffset(-(railPx + labelGapPx).roundToInt(), labelDyPx.roundToInt()) },
            enter = fadeIn(tween(SCRUBBER_LABEL_IN_MS)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(190, easing = SCRUBBER_EASING),
            ),
            exit = fadeOut(tween(SCRUBBER_LABEL_OUT_MS)) + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(130, easing = SCRUBBER_EASING),
            ),
        ) {
            Text(
                text = latestRows.getOrNull(selected)?.let(labelOf).orEmpty(),
                // labelMedium rather than labelSmall, and a roomier box: the bubble floats
                // over the transcript for as long as a finger holds the rail, so it has to be
                // read at a glance. Still one line, and still capped at SCRUBBER_LABEL_MAX, so
                // a long opening words the line short rather than wrapping it into a block.
                style = MaterialTheme.typography.labelMedium,
                color = scheme.inverseOnSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = scrubberLabelMax(LocalConfiguration.current.screenWidthDp.dp))
                    .clip(ScrubLabelShape)
                    .background(scheme.inverseSurface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

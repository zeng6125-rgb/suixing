
package com.suixing.agent.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * The rail has up to twelve slots, each representing one real, consecutive loaded turn.
 * Short conversations have no placeholders. A full window slides around the turn being read.
 */

/** The rail's width, and so every row's hit width: the longest line the rail draws. */
internal val SCRUBBER_RAIL = 24.dp

/** A longer rhythm with the same twelve slots and shared drawing/hit geometry. */
internal val SCRUBBER_ROW = 8.dp

/** Twelve slots occupy 129dp; spacing also drives drag selection and the floating label. */
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
internal const val SCRUBBER_LABEL_IN_MS = 90
internal const val SCRUBBER_LABEL_OUT_MS = 100

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

/** One tick's target look: the values the rail draws when it is not animating. */
internal data class ScrubberTickStyle(
    val width: Float,
    val height: Float,
    val color: Color,
)

/** Settling discards the old animations; normal reading starts from the current target. */
@Composable
internal fun scrubberTickStyle(
    conversationId: String,
    target: ScrubberTickStyle,
    settling: Boolean,
): State<ScrubberTickStyle> = key(conversationId) {
    if (settling) {
        rememberUpdatedState(target)
    } else {
        val animatedWidth = animateFloatAsState(
            target.width, spring(dampingRatio = 0.86f, stiffness = 800f), label = "turn_width")
        val animatedHeight = animateFloatAsState(
            target.height, spring(dampingRatio = 0.9f, stiffness = 900f), label = "turn_height")
        val animatedColor = animateColorAsState(
            target.color, tween(120, easing = SCRUBBER_EASING), label = "turn_color")
        // Do not subscribe the rail's composition to every tick of all twelve animations.
        // The caller samples this state only from graphicsLayer/drawBehind.
        remember {
            derivedStateOf { ScrubberTickStyle(animatedWidth.value, animatedHeight.value, animatedColor.value) }
        }
    }
}

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
            entry.animation.animateTo(scale, spring(dampingRatio = 0.82f, stiffness = 850f))
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
 * reading something older than it, so "where am I" is always on screen. The current turn
 * is strongest; width and tint fall away symmetrically on either side of it.
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
    /** 正在恢复位置（切会话/回书签）：视觉直接取目标值，不做补间，避免高亮从上往下扫。 */
    settling: Boolean = false,
) {
    // Draw only existing loaded turns, up to twelve.
    if (totalTurns <= 0) return
    val scheme = MaterialTheme.colorScheme
    // How far above the window the finger has pulled the strip — the turns it has unlocked in
    // this conversation. Kept for as long as the chat is open, dropped the moment another is
    // opened: a deep conversation unlocked in one chat must not decide what the next one shows.
    var unlocked by remember(conversationId) { mutableIntStateOf(0) }
    // Keep consecutive real turns around the current one; every enabled line has one target.
    //
    // **current() 现在返回全局轮号**（1 基），与 [scrubberWindow] 的坐标一致。
    val currentTurn = current()
    val window = remember(totalTurns, drawnTurns, currentTurn, unlocked) {
        scrubberWindow(
            totalTurns = totalTurns,
            currentTurn = currentTurn,
            drawnTurns = drawnTurns,
            unlocked = unlocked,
        )
    }
    val rows = remember(window) { scrubberSlots(window) }
    if (rows.isEmpty()) return
    val rowCount = rows.size
    // A gesture outlives the recomposition it started in, so it reads the turns and the
    // jump through these instead of closing over values a new message has replaced.
    val latestRows by rememberUpdatedState(rows)
    val latestJump by rememberUpdatedState(onJumpTo)
    // Which row the finger is on, which row the label names, and whether the label is out.
    // All three are cleared when the finger lifts, so no frame can leave the rail stuck open.
    var pressRow by remember(conversationId, rowCount) { mutableIntStateOf(-1) }
    var selectionRow by remember(conversationId, rowCount) { mutableIntStateOf(0) }
    var shown by remember(conversationId, rowCount) { mutableStateOf(false) }
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
    val labelDyPx = animateFloatAsState(
        targetValue = selected * (rowPx + gapPx) + rowPx / 2f - railHeightPx / 2f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 900f),
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
                .pointerInput(conversationId, rowCount) {
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
                        val startRow =
                            scrubberRowFor(down.position.y, hitRowPx, hitPitchPx, railRows.size)
                        if (railRows[startRow] <= 0) return@awaitEachGesture
                        down.consume()
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
                        // 长按已经跳过的轮次：抬手时若目标没变就不再跳第二次（避免滚动被重置）。
                        var holdJumped: Int? = null
                        while (pressed) {
                            val event = if (waiting) {
                                withTimeoutOrNull(SCRUBBER_LONG_PRESS_MS) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // 长按到底：标签亮起，并且**当场定位**到手指所在的这一轮
                                // （用户要求"长按导航条时显示高亮并定位到对应位置"；
                                // 原来只亮标签、非要等抬手才跳，长按像没反应）。
                                waiting = false
                                shown = true
                                val target = latestRows.getOrNull(selectionRow)?.takeIf { it > 0 }
                                if (target != null) {
                                    holdJumped = target
                                    latestJump(target)
                                }
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
                                ).coerceAtLeast(railRows.indexOfFirst { it > 0 }.coerceAtLeast(0))
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
                        // The handler restarts if the slot count changes; a sliding full window
                        // keeps its gesture. Exit bounds use the same rows as the initial hit test.
                        val railBottomPx =
                            hitRowPx * railRows.size + hitGapPx * (railRows.size - 1)
                        val cancelled = dragged && (
                            pointer.x < -exitPx ||
                                pointer.x > railWidthPx + exitPx ||
                                pointer.y > railBottomPx + exitPx
                            )
                        pressRow = -1
                        shown = false
                        // The strip recentres on wherever the jump lands: the unlocked turns
                        // were the ones this drag could reach, and the window goes back to
                        // naming the turn being read instead of staying slid up over turns the
                        // reader has not left yet.
                        unlocked = 0
                        // [latestRows], not the snapshot the hit test was matched against: a
                        // drag that unlocked its way up the conversation is aimed at a turn
                        // that snapshot never held.
                        if (!cancelled) {
                            val target = latestRows.getOrNull(destination)?.takeIf { it > 0 }
                            if (target != null && target != holdJumped) latestJump(target)
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(SCRUBBER_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rows.forEachIndexed { row, turn ->
                val exists = turn > 0
                val active = exists && turn == currentTurn
                val chosen = exists && shown && row == selected
                val distance = abs(turn.toLong() - currentTurn)
                // Animate visual properties inside a fixed slot; hit boxes and
                // conversation positioning never participate in these springs.
                val targetWidth = when {
                    active -> 20f
                    exists && distance == 1L -> 16f
                    exists && distance <= 3L -> 12f
                    else -> 8f
                }
                val targetHeight = if (active) 3f else 2f
                val targetColor = when {
                    !exists -> scheme.outlineVariant.copy(alpha = 0.35f)
                    chosen || active -> scheme.primary
                    distance == 1L -> scheme.primary.copy(alpha = 0.48f)
                    distance <= 3L -> scheme.primary.copy(alpha = 0.32f)
                    else -> scheme.primary.copy(alpha = 0.22f)
                }
                val targetStyle = ScrubberTickStyle(targetWidth, targetHeight, targetColor)
                val tickStyle = scrubberTickStyle(conversationId, targetStyle, settling)
                Box(
                    modifier = Modifier.width(SCRUBBER_RAIL).height(SCRUBBER_ROW),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            // A centred fixed drawing surface keeps both ends balanced while
                            // visual widths animate, without changing the rail's hit geometry.
                            .width(20.dp)
                            .height(3.dp)
                            .graphicsLayer {
                                val style = tickStyle.value
                                val scale = rowScales.value(turn)
                                scaleX = minOf(style.width * scale, SCRUBBER_RAIL.value) / 20f
                                scaleY = style.height / 3f * scale
                                transformOrigin = TransformOrigin.Center
                            }
                            .clip(ScrubTickShape)
                            .drawBehind { drawRect(tickStyle.value.color) },
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
                .graphicsLayer {
                    translationX = -(railPx + labelGapPx)
                    translationY = labelDyPx.value
                },
            enter = fadeIn(tween(SCRUBBER_LABEL_IN_MS)) + scaleIn(
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(1f, 0.5f),
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 800f),
            ),
            exit = fadeOut(tween(SCRUBBER_LABEL_OUT_MS)) + scaleOut(
                targetScale = 0.97f,
                transformOrigin = TransformOrigin(1f, 0.5f),
                animationSpec = tween(100, easing = SCRUBBER_EASING),
            ),
        ) {
            Text(
                text = latestRows.getOrNull(selected)?.takeIf { it > 0 }?.let(labelOf).orEmpty(),
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

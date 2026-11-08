package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.suixing.agent.ui.components.bottomInsetPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.ui.chat.GenerationSheet
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.MENU_EXIT_MS
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.menuRippleClickable
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Goal
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.label
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.runtime.mutableFloatStateOf

/** 上下文环、环卡与面板卡：只读「用了多少 / 估算还是实测」的几个入参，和输入框本身无关。 */

/**
 * The context window as a ring, left of the mic.
 *
 * A gauge rather than a number: 22dp of arc is all the room this row has, and the figures are
 * one tap away. The arc turns red at 80% of the window — where the engine starts folding the
 * conversation and where the next question may no longer fit — so the colour carries the
 * warning and the card carries the detail. With no window configured (0 = unlimited) the ring
 * is still drawn, as a neutral track with no arc, and the card says so and offers the way to
 * settings: the user asked for a gauge, and "this model has no window set" is exactly the kind
 * of thing a gauge should be able to tell them. No denominator is invented — a made-up
 * fraction would be decoration pretending to be data.
 *
 * The touch target is 26dp, not the 48dp the primary controls use: this row also carries the
 * model caption, the mic and the send button, and at 48dp the caption would be too narrow to
 * read on a 360dp-wide screen. The arc is 22dp of that, which is what leaves room for the
 * percentage in the middle: 22dp less the 2dp stroke either side is 18dp of clear space, and
 * three digits at 7sp measure about 11.7dp by 8dp.
 */
@Composable
internal fun ContextRing(
    used: Int?,
    /** True when [used] is this app's own estimate; the card says 「约」 when it is. */
    estimated: Boolean = false,
    window: Int,
    /** The current system, tool, and conversation estimates, independent of run status. */
    breakSplit: Triple<Int, Int, Int>? = null,
    /** Refresh system and tool estimates when the panel opens. */
    onPanelOpen: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val fraction = if (known && used != null) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val tone = if (fraction >= 0.8f) scheme.error else scheme.primary
    val track = scheme.surfaceVariant
    var open by remember { mutableStateOf(false) }
    // A focus-less popup does not receive BACK either: without this the press falls through to the
    // activity and finishes it. Same pair the composer's own cards use.
    BackHandler(enabled = open) { open = false }
    val density = LocalDensity.current
    val ringPopupMarginPx = with(density) { RING_POPUP_EDGE.toPx() }
    // The number in the middle, as a whole percent: the arc is a shape and the number is the
    // figure behind it. A known window still shows zero; an unfinished estimate shows an ellipsis.
    val percent = (fraction * 100).roundToInt()
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = {
                    open = true
                    // 打开即按当前上下文算一次三行（不依赖 run）：空闲时也有得显示。
                    onPanelOpen()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            val ringSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = stroke),
            )
            if (known && fraction > 0f) {
                drawArc(
                    color = tone,
                    // From twelve o'clock, clockwise: how a gauge is read.
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        // 9sp, two steps up from the 7sp the arc's clear space was first sized for: Bold at 7sp
        // was read as "could be a little bigger" twice, the second time with a figure. Three
        // digits at 9sp are about 15dp of the 18dp of clear space inside the arc, so the
        // longest reading still fits. Bold, not Medium: at this size the user read the Medium
        // weight as thin, and the ring is the only place these digits appear — the extra weight
        // costs nothing elsewhere.
        if (known) {
            Text(
                text = if (used == null) "…" else percent.toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
            )
        }
        // The card's anchor: a zero-height box at the ring's top edge, so the popup opens
        // upward from the ring instead of covering the row it belongs to.
        Box(
            Modifier
                .align(Alignment.TopCenter),
        ) {
            // A focusable window takes the keyboard away from the field below — the user asked why
            // tapping the ring closes the input method, and that is the whole of the answer: the
            // popup grabs focus, the IME goes, and the composer is then laid out a keyboard lower
            // while this card stays where it was. The card is focus-less instead, which means its
            // window can see neither a tap outside itself nor BACK; both are caught here, the way
            // the composer's own cards do it (see [MenuCard]'s `focusable`). The scrim is a window
            // of its own, composed before the card: windows stack in composition order, so a scrim
            // composed after it would cover it instead.
            //
            // 小浮层进出场（用户：「用量统计这些弹窗出现得太生硬」）。`keeping` 让退场动画期间窗口
            // 仍留在组合里：遮罩和卡片都挂在它上面，否则退场的第一帧窗口就被销毁、动画看不到。
            // 遮罩层保持原样（用户明确：不需要改小浮层的阴影/遮罩）。只有卡片本身有动效。
            // **直接复用 MenuCard**（与模型选择同一个组件）；需要显式定位，故传 popupPositionProvider。
            val belowGlyphPx = with(density) { 8.dp.roundToPx() }
            // 卡片离屏幕边至少 12dp（用户：单轮统计卡不要碰屏幕边缘）。
            val edgePxForPopup = ringPopupMarginPx.toInt().coerceAtLeast(with(density) { 12.dp.roundToPx() })
            MenuCard(
                visible = open,
                onDismiss = { open = false },
                alignment = Alignment.TopCenter,
                offset = IntOffset.Zero,
                focusable = false,
                popupPositionProvider = remember(ringPopupMarginPx) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            // 恢复成**原来那一版**的算法（用户：改布局之前位置是对的）。
                            // 只额外保证不贴屏幕边：横纵各留 edgePxForPopup。
                            val margin = edgePxForPopup
                            val x = (windowSize.width - margin - popupContentSize.width)
                                .coerceAtLeast(margin)
                            val y = (anchorBounds.top - popupContentSize.height - belowGlyphPx)
                                .coerceAtLeast(margin)
                            return IntOffset(x, y)
                        }
                    }
                },
            ) {
                ContextPanelCard(
                    used = used,
                    estimated = estimated,
                    window = window,
                    split = breakSplit,
                )
            }
        }
    }
}

/** The ring's detail: the numbers a 22dp arc cannot show. */
@Composable
internal fun ContextRingCard(
    used: Int,
    /** True when [used] is this app's own estimate rather than a count the endpoint sent. */
    estimated: Boolean = false,
    window: Int,
    /**
     * 当前模型名（可为空）。
     *
     * issue #49 缺陷 2：切换 provider/model 时**分母会变**（1M → 256K），同样的 tokens 百分比
     * 差好几倍，用户看到的是"上下文突然变满/变空"却不知道原因。把窗口与模型名列出来，
     * 一眼就能看出变的是窗口而不是上下文。
     */
    model: String = "",
    sessionTokens: Int,
    /** The text-based floor for the cumulative row, when nothing in the session was priced. */
    sessionEstimate: Int = 0,
    sessionSplit: SessionSplit,
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) (used * 100.0 / window).roundToInt().coerceIn(0, 100) else 0
    Column(
        modifier = Modifier
            // 264dp, not 240: the value on the 「上下文占用」 line is a sentence of its own
            // (「12.3k / 200k（6%）」, or the 窗口未设置 form) and the old cap ellipsized it.
            // Still inside a 360dp screen's own margin, and still narrower than the
            // transcript's full width. Capped against the window, so a split-screen window
            // gets a card that fits it rather than one that hangs off its edge.
            .widthIn(max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp))
                // 卡片外框由 MenuCard 提供，这里只画内容。
        // 与 MenuCard（那七处）一致：**不设 verticalArrangement**（用户：和那几处一样）。
    ) {
        if (used <= 0) {
            // A brand-new conversation has nothing to report yet, and saying so is not the same
            // as saying there is no data: 「暂无数据」 on a chat the user has not typed into yet
            // reads as a fault. Everything else here has been estimated by now, so this branch
            // means an empty transcript and nothing else.
            Text(
                text = if (sessionTurns <= 0) "新会话 · 还没有用量" else "暂无数据",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            // Same intrinsic-width trick as the metric cards: the numbers share one right edge
            // without the card asking for the screen's width.
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 208.dp)) {
            RingDetail(
                "上下文占用",
                if (known) {
                    // 「约」 only when the number is this app's own estimate: the endpoint's own
                    // count is a fact and reads as one, and an estimate presented as a fact is
                    // the one thing this card must not do.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} / ${shortTokens(window)}（$percent%）"
                } else {
                    // No invented denominator, and no editor either: where the window is
                    // filled in is the settings page, which also fills it from the endpoint's
                    // own context_window when the user has not said otherwise. The ring only
                    // reports.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} · 窗口未设置 · 到设置里填"
                },
            )
            // 窗口和模型分开显示：长模型名与完整窗口数挤在单行时会被省略，无法解释分母变化。
            if (known) {
                RingDetail("模型窗口", "${fullTokens(window.toLong())} tok")
            }
            if (model.isNotBlank()) {
                Text(
                    text = "当前模型：$model",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (estimated) {
                    "用量为本地估算，百分比按当前模型窗口计算。"
                } else {
                    "用量沿用本会话最近实测，百分比按当前模型窗口计算；切换模型后的实际用量以下次上报为准。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            // The conversation's own hit rate, not the newest round's: the user asked for
            // 「总缓存命中率」 and a label of exactly 「缓存命中」. SessionStats.cachePercent is
            // the whole session's cached tokens over its whole prompt, so it is the total this
            // line is for; the newest round's own ratio was the wrong number under the wrong
            // name. It sits here, above the speed and the clock, because it is a reading of the
            // context the line above it is about.
            // Drawn even when it reads 0%: the line used to be skipped whenever the ratio came
            // out at zero, and the user — who had asked for this reading by name — read the
            // missing row as the reading having been dropped. A priced session with no cache
            // hits yet is a 0%, not a gap.
            // 两位小数：整百分比在几千 token 的会话里几乎不动，四舍五入之后看不出缓存到底在不在
            // 起作用。数值本身不取整（SessionStats.cachePercent 是 Double），格式只在这里决定。
            RingDetail(
                "缓存命中",
                remember(sessionCachePercent) {
                    String.format(Locale.getDefault(), "%.2f%%", sessionCachePercent)
                },
            )
            // The rows below are in three groups, in this order: 上下文 (the two above) →
            // 本会话累计 → 速度与时间. They used to be stacked in the order the features landed,
            // which put 「输出速度」 above the total and the two per-round lines at the very bottom
            // — the user asked for the ordering to make sense. The grouping is carried by the
            // names (「累计…」) rather than by a divider or a heading: every row is already a
            // label/value pair, so a separator would add chrome and answer nothing.
            //
            // The per-round pair that used to sit here (「本轮输入」/「本轮输出」) is gone: the user
            // asked for it off, and it was the only consumer of the round's own two figures — the
            // arc's number and these totals come from other sources, so nothing else went with it.
            if (sessionTokens > 0) {
                // 本会话累计: the whole conversation's spend, not the context on screen. Every
                // round re-sends the context, so this is the number a bill would be built from —
                // and the same tally the usage page shows. Labelled 「累计」 rather than 「总量」
                // because it counts each round's re-send, which is why it can dwarf the
                // 「上下文占用」 line above it.
                RingDetail("累计用量", "${fullTokens(sessionTokens.toLong())} tok · $sessionTurns 轮")
                // The split under the total, by the names the user asked for: what the cache
                // could not answer, what it did, and what came back. All three are the whole
                // conversation's, like the total above them.
                RingDetail("未缓存输入", "${fullTokens(sessionSplit.uncached)} tok")
                RingDetail("缓存读取", "${fullTokens(sessionSplit.cached)} tok")
                RingDetail("输出", "${fullTokens(sessionSplit.output)} tok")
            } else if (sessionEstimate > 0) {
                // Nothing was ever priced — a gateway that sends no usage block, or rows written
                // before the counts existed — so the total is the same text-based floor the line
                // above uses, times the turns that re-sent it. 「约」 says what it is, and the
                // split stays out rather than being invented: the cache halves cannot be guessed
                // from a character count, and a made-up 「缓存读取」 is worse than a missing one.
                RingDetail(
                    "累计用量",
                    "约 ${fullTokens(sessionEstimate.toLong())} tok · $sessionTurns 轮",
                )
            }
            // 速度与时间 last: they are about the work, not about the window or the bill.
            if (sessionPerSecond > 0) {
                RingDetail(
                    "输出速度",
                    remember(sessionPerSecond) {
                        "${Tps.format(sessionPerSecond)} tok/s"
                    },
                )
            }
            if (sessionElapsedMs > 0) {
                RingDetail(
                    "总用时",
                    remember(sessionElapsedMs) { formatRunElapsed(sessionElapsedMs) },
                )
            }
            }
        }
    }
}

/**
 * The ring's popup content, restyled to the panel the user handed over as a screenshot:
 * a title row (「上下文已用 N%」 with the 「~used / window」 figure right-aligned), a progress
 * bar, then the live request's three shares as dotted rows with their own proportion bars.
 * The old detail card (占用/缓存/累计/速度/时间) did not go away — it moved to the title
 * capsule's entry ([TopBarContextEntry]); this panel is what the composer's ring opens now.
 */
@Composable
internal fun ContextPanelCard(used: Int?, estimated: Boolean, window: Int, split: Triple<Int, Int, Int>?) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known && used != null) (used * 100.0 / window).roundToInt().coerceIn(0, 100) else 0
    // **三条归一到顶部的总数**（用户：「三条的对应 token 数字对不上总 token 数」）：顶部是端点
    // 实测的 prompt tokens，三条是本地字符估算（÷4），两个口径不同。按比例缩放到实测总数上，
    // 最后一条吸收舍入误差 ⇒ 三条相加精确等于顶部那个数。
    val parts = if (used == null) null else split?.let { (system, tools, history) ->
        val (s2, t2, h2) = normaliseContextSplit(system, tools, history, used)
        listOf(
            Triple("系统提示词", s2, scheme.primary),
            Triple("工具定义", t2, scheme.secondary),
            Triple("对话消息", h2, scheme.tertiary),
        )
    }
    Column(
        modifier = Modifier
            .widthIn(max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp))
                // 卡片外框由 MenuCard 提供，这里只画内容。
        // 与 MenuCard（那七处）一致：**不设 verticalArrangement**（用户：和那几处一样）。
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (known && used != null) "上下文已用 $percent%" else "上下文用量",
                style = MaterialTheme.typography.labelMedium,
                // 与数字同一个颜色（用户：「这几个字的颜色都改为和数字一样的颜色」）。
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    used == null -> "计算中…"
                    else -> buildString {
                        if (estimated) append('~')
                        append(panelTokens(used))
                        if (known) append(" / ${panelTokens(window)}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (known && used != null) {
            // 标题与进度条之间留大一点的空（用户：「离下面那个进度条间距再大一点点」）。
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 三行始终是估算分项，加起来等于顶部的总用量。
        parts?.let {
            Spacer(Modifier.height(4.dp))
            val denominator = if (window > 0) window else parts.sumOf { it.second }
            parts.forEach { (label, value, tone) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        // 与数字同一个颜色（用户：「这几个字的颜色都改为和数字一样的颜色」）。
                        color = scheme.onSurfaceVariant,
                        // *fill = true**（用户：「进度条没有对齐」）：fill=false 时标签列只占自己
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "~${panelTokens(value)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        // **统一固定宽度**（用户：「数字左侧没对齐，三行的进度条长度和两侧也没对齐」）：
                        // 上一步用 IntrinsicSize.Max 让每行各按自身内容取宽 —— 于是「~22.1K」与
                        // 「~568.3K」左缘不齐、后面小条起点也随之错位。给三行同一个宽度，
                        // 值右对齐 + 小条同起点同长度。
                        // 56dp 是按最长可能值（「~999.9K」，7 字符 @ labelSmall）定的，
                        // 原来的 44dp 放不下「568.3K」才被切掉。
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(56.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    val fraction = if (denominator > 0) {
                        (value.toFloat() / denominator).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(scheme.surfaceContainerHighest)
                            .drawBehind {
                                drawRect(tone, size = Size((size.width * fraction).roundToInt().toFloat(), size.height))
                            },
                    )
                }
            }
        }
    }
}

/** 「6.9K / 23.9K / 150K / 1M」: one decimal under 100 of a unit, whole above, unit letter capped. */
internal fun panelTokens(v: Int): String {
    val k = v / 1000.0
    val (n, unit) = if (k >= 1000.0) k / 1000.0 to "M" else k to "K"
    val s = String.format(java.util.Locale.US, "%.1f", n)
    return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
}

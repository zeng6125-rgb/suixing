package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.suixing.agent.ui.components.bottomInsetPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.label
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.mutableFloatStateOf

/** The composer's status line: plain 13sp text with no container of its own. */
@Composable
internal fun statusLineStyle(): TextStyle = MaterialTheme.typography.labelMedium

/**
 * 思考强度 beside the model chip: the same levels as the settings slider (REASONING_LEVELS),
 * written
 * through on selection. It lands on the provider the active assistant is bound to when
 * that provider owns its sampling knobs, and on the global setting otherwise — the same
 * precedence [AppSettings.forAssistant] resolves with.
 */
/**
 * The one sheet both composer captions open: which model answers and how hard it
 * thinks are one decision, so they share one surface — model name and level badge on
 * top, the six-step slider in the middle, the provider's enabled models as radio rows
 * below. Every change writes through at once; there is no confirm button to forget.
 *
 * It hangs off the composer's zero-height anchor box (see `ComposerStack`, the `Box` at the
 * top of the stack): the sheet opens upward from there and its bottom edge lands 8dp above
 * that box, so it never covers the field it belongs to. Horizontally its right edge lines up
 * with the composer's content edge, and it is nudged back inside the screen when that would
 * hang it off an edge. Right-aligned because centring it on the capsule put the sheet's mass
 * left of the ring, which the user read as left-aligned.
 */
/** How long a popup card's exit animation runs before the popup is taken down. */
internal const val MENU_EXIT_MS = 140L

/** 全站小浮层统一时长（用户：所有统一为七处那一套，140ms）。 */
internal const val POPUP_ANIM_MS = 110

/** The margin a popup keeps from the window's own edges. */
internal val POPUP_EDGE = 8.dp

/** 取消追底的手指位移阈值（用户：「取消的最小方法就是往上移动一点就需要取消」）。 */
internal const val CANCEL_FOLLOW_PX = 4f

/**
 * 阅读锚点「还算漂移」的上限（issue #11/#15，设计 A）。
 *
 * 帧回调里每帧对一次账：偏差在这以内 ⇒ 当成内容长高造成的漂移，直接补掉；
 * 超过它 ⇒ 是用户自己滚了，把锚点跟上去而不是把人拽回原处。
 * 200px 大约是一整张卡片的高度，正常的长高不会一帧跳这么多。
 */
internal const val USER_SCROLL_PX = 200

/** 弹窗离屏幕边缘的**呼吸感**（用户：「弹窗位置不要贴屏幕边缘，留点呼吸感」）。 */
internal val POPUP_EDGE_AIR = 16.dp

/** 圆环面板离屏幕右边缘的距离（实测面板停在 63dp 处后定的值，见 ContextRing 里的说明）。 */
internal val RING_POPUP_EDGE = 12.dp

/**
 * A popup's width cap: the width it wants, never wider than the window it opens in.
 *
 * [windowWidth] is `screenWidthDp`, the window's own width — in split screen the display's width
 * would hand a card a size the window does not have. A card wider than its window is a card whose
 * right half is off the screen: the model sheet's 340dp needs a 356dp window to fit.
 */
internal fun popupCap(nominal: Dp, windowWidth: Dp): Dp = minOf(nominal, windowWidth - POPUP_EDGE_AIR * 2)

/**
 * How far a popup anchored at [anchorLeft] has to move to stay inside the window.
 *
 * The card is [width] wide and opens to the right of its anchor, so only its left edge can be
 * moved; [edgePx] of air is kept on both sides. The same rule the model sheet's own nudge uses,
 * shared here so the card hung off the row's left end obeys it too.
 */
internal fun popupNudge(anchorLeft: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorLeft == 0f) return 0f
    val left = anchorLeft.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - anchorLeft
}

/** 附件与正文的合流（用户：「文件的注入不生效，随对话发出就消失了」）。 */
internal fun withAttachment(attachments: List<Attachment>, text: String): Pair<String, List<String>> {
    val paths = attachments.mapNotNull { it.filePath }
    val body = if (paths.isEmpty()) {
        text
    } else {
        val note = paths.joinToString("\n") { "［附件文件］$it" }
        if (text.isBlank()) note else "${text.trimEnd()}\n\n$note"
    }
    return body to attachments.mapNotNull { it.base64.takeIf { b -> b.isNotEmpty() } }
}


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
    used: Int,
    /** True when [used] is this app's own estimate; the card says 「约」 when it is. */
    estimated: Boolean = false,
    window: Int,
    /**
     * 面板三行（系统提示词 / 工具定义 / 对话消息）的**当前窗口**读数，不依赖 run：run 中优先用
     * [live] 的实测三块，空闲用这个（打开面板时由 [onPanelOpen] 触发上层按当前上下文算）。
     */
    breakSplit: Triple<Int, Int, Int>? = null,
    /** 打开面板时回调：让上层按当前上下文算一次三行（见 ChatViewModel.refreshContextBreakdown）。 */
    onPanelOpen: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val fraction = if (known) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val tone = if (fraction >= 0.8f) scheme.error else scheme.primary
    val track = scheme.surfaceVariant
    var open by remember { mutableStateOf(false) }
    // A focus-less popup does not receive BACK either: without this the press falls through to the
    // activity and finishes it. Same pair the composer's own cards use.
    BackHandler(enabled = open) { open = false }
    val density = LocalDensity.current
    val ringPopupMarginPx = with(density) { RING_POPUP_EDGE.toPx() }
    // The number in the middle, as a whole percent: the arc is a shape and the number is the
    // figure behind it. A window that was never set has no percentage to show (0 = unlimited,
    // not 0%), and 0% is a ring with no arc to read it against.
    val percent = if (known) (fraction * 100).roundToInt() else 0
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
        if (percent > 0) {
            Text(
                text = percent.toString(),
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
    val percent = if (known) used * 100 / window else 0
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
            // **窗口与模型名**（issue #49 修法 2）：切 provider 后分母变了，这一行让用户
            // 立刻看出"是窗口变了，不是上下文变了"。
            if (known && model.isNotBlank()) {
                RingDetail("模型窗口", "${fullTokens(window.toLong())} tok · $model")
            }
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
internal fun ContextPanelCard(used: Int, window: Int, split: Triple<Int, Int, Int>?) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) used * 100 / window else 0
    // **三条归一到顶部的总数**（用户：「三条的对应 token 数字对不上总 token 数」）：顶部是端点
    // 实测的 prompt tokens，三条是本地字符估算（÷4），两个口径不同。按比例缩放到实测总数上，
    // 最后一条吸收舍入误差 ⇒ 三条相加精确等于顶部那个数。
    val parts = split?.let { (system, tools, history) ->
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
                text = if (known) "上下文已用 $percent%" else "上下文用量",
                style = MaterialTheme.typography.labelMedium,
                // 与数字同一个颜色（用户：「这几个字的颜色都改为和数字一样的颜色」）。
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (known) {
                    "~${panelTokens(used)} / ${panelTokens(window)}"
                } else {
                    "~${panelTokens(used)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (known) {
            // 标题与进度条之间留大一点的空（用户：「离下面那个进度条间距再大一点点」）。
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 三行**常驻**（用户：「系统提示词就是当前窗口的 tokens 和占比，不是单个 run 的」）：
        // 数值来自实测（run 中）或按当前上下文的估算（空闲，见 refreshContextBreakdown），
        // 所以打开面板就有；进度条基准是**上下文窗口**（三行占比之和 = 顶部那条的比例）。
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

@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            // **不要 `by` 委托**（优化清单 chat-P1）：`by` 会在**组合期**读值 ⇒ 只要这三点
            // 可见就按屏幕刷新率持续重组+重绘。同文件 `BlinkingCursor` 已因同款写法实测
            // 213fps / 单核 42% 改掉，这里漏改了。改成拿 State、在 `graphicsLayer` 的
            // **绘制期**读 —— 视觉逐帧相同，但不再触发重组。
            val alphaState = transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .graphicsLayer { alpha = alphaState.value }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
internal fun BlinkingCursor() {
    // **不要用 `rememberInfiniteTransition` + tween**：那会让界面按**屏幕刷新率**持续重绘。
    // 真机实测（0.8.382，一轮在跑、手指不动）：15 秒 3194 帧 ≈ 213 fps、单核 42%
    // —— 长回答/长工具调用时就是持续发热与耗电，而同屏还有悬浮窗在同时动画。
    // 闪烁只需要"两档"，用 480ms 的步进替代：观感一样，重绘从 ~120 次/秒降到 ~2 次/秒。
    val alpha = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(480)
            alpha.floatValue = if (alpha.floatValue > 0.5f) 0.15f else 1f
        }
    }
    // 光标画成一条**细线**而不是字符「▍」：那个字形本身有宽度（一个全角块的宽度），
    // 用户反馈「光标太宽」。线宽 1.5dp ≈ 一个字笔画的粗细，高度取正文行高。
    val body = MaterialTheme.typography.bodyMedium
    // 高度跟着字号走（乘 1.4 约等于正文行高），这样系统字体缩放时光标也跟着变；
    // 不直接读 lineHeight，是因为它在个别主题下是 Unspecified。
    val lineHeight = with(LocalDensity.current) {
        (body.fontSize.value * 1.4f).sp.toDp()
    }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(lineHeight)
            .alpha(alpha.floatValue)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(1.dp),
            ),
    )
}

/**
 * The live run's progress line: the engine's own sentence, plus how long the run has been
 * going. The clock lives in here — one tick a second re-composes this caption and nothing
 * else, and the elapsed time is read off the wall clock on each tick, so a line that is
 * re-created (a switch away and back) still shows the true time rather than restarting.
 */
/**
 * 把内容上移 [amount] 吃进列表间距（spacedBy 的 16dp）里，布局高度同步减掉这一段 ——
 * 「无气泡时状态行的上间隙」要从 16 压到 10 的那 6dp 就从这里来。
 *
 * 不用负 padding：`Modifier.padding` 收负值不可靠（supervisor 指出会抛异常）。这里只依赖两件
 * 官方且极常见的行为：子节点可以放在负坐标（`Modifier.offset` 同源的 place），以及布局尺寸非负
 * （[amount] 与高度差都 coerce 到 ≥0，不会量出负尺寸）。
 */
internal fun Modifier.liftIntoGap(amount: Dp): Modifier = layout { measurable, constraints ->
    val lift = amount.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) {
        placeable.place(0, -lift)
    }
}

@Composable
internal fun RunStatusLine(
    text: String,
    /** When the run began, or 0 while the effect that records it has yet to run. */
    startedAt: Long,
    /** False for the compression note: that is a fact, not a progress bar. */
    timed: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(startedAt) {
        if (startedAt <= 0) return@LaunchedEffect
        while (true) {
            delay(1000)
            tick++
        }
    }
    val caption = remember(tick, startedAt, text, timed) {
        if (!timed || startedAt <= 0) text
        else "$text，用时 ${formatRunElapsed(System.currentTimeMillis() - startedAt)}"
    }
    Text(
        text = caption,
        style = statusLineStyle(),
        color = color,
        // 不截断（用户：「错误的提示要显示全」）。重试/失败那行是这条状态线写的（VM 的
        // Retrying/Failed 分支），一行省略号会把「服务器限流（429），用…」的后半句吃掉，
        // 而那一半正是用户要读的原因。允许换行；它本来就短，只有失败原因会长。
        modifier = modifier,
    )
}

@Composable
internal fun TimeLabel(timestamp: Long) {
    Text(
        text = formatTime(timestamp),
        modifier = Modifier.fillMaxWidth(),
        // The label's distance from its bubble is decided by the caller (3dp inside the
        // item's Column); this only tightens the text's own line box, so the glyphs sit
        // closer to the top of their slot than labelSmall's 16sp box would put them.
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun Composer(
    input: String,
    onInput: (String) -> Unit,
    /**
     * Takes a queued line back out of the queue and into the composer — its text **and its
     * picture**. A callback of its own rather than `onInput(text)`: the attachment lives in the
     * view model's cache, not in this composable, so only the caller can put both halves back.
     */
    onRestoreSteer: (PendingSteer) -> Unit,
    running: Boolean,
    settings: AppSettings,
    /** The persona this conversation runs with, not the app-wide active one. */
    assistantId: String,
    /** The window the context ring divides by; 0 hides the ring (no denominator). */
    contextWindow: Int,
    /** Tokens the newest round filled of that window, and how many of them were cached. */
    contextUsed: Int,
    /** True when [contextUsed] is this app's own estimate rather than the endpoint's count. */
    contextEstimated: Boolean = false,
    /**
     * 圆环面板三行（系统提示词 / 工具定义 / 对话消息）的当前窗口读数，不依赖 run：空闲时由
     * [onContextPanelOpen] 触发上层计算后从这里进来。
     */
    contextBreak: Triple<Int, Int, Int>? = null,
    /** 面板打开时回调：上层按当前上下文算一次三行。 */
    onContextPanelOpen: () -> Unit = {},
    /** This conversation's own model pick, or null while it follows persona/defaults. */
    conversationProviderId: String?,
    conversationModel: String?,
    /** This conversation's own thinking level, or null while it follows the defaults. */
    conversationReasoningEffort: String?,
    onPickModel: (ProviderConfig, String) -> Unit,
    /** Asks the endpoint once what context windows its models advertise; see prefetchWindows. */
    onPrefetchWindows: () -> Unit,
    /** Writes a thinking level onto this conversation alone. */
    onChooseReasoning: (String) -> Unit,
    attachments: List<Attachment>,
    /** 删附件列里第几个；多附件后「清空」就是逐个删到空。 */
    onRemoveAttachment: (Int) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Queues a line for the running model; false means it was dropped. */
    onSteer: (String, List<String>) -> Boolean,
    /** Asks the live run to fold the conversation at its next turn boundary. */
    onRequestCompress: () -> Boolean,
    /**
     * **没有 run 时**直接压一次（按已落盘历史）。菜单项在 [onRequestCompress] 返回 false
     * 时改走这条 —— 以前这条根本没接，于是"点了没反应"（用户报的正是这个）。
     */
    onCompressNow: () -> Unit,
    /** Lines queued for the run that is going, oldest first. */
    pendingSteers: List<PendingSteer>,
    /**
     * Lines typed in 排队 mode, waiting to be the next turn. Drawn in the same stack as
     * [pendingSteers] and through the same row — same three actions — but the row is told these are
     * next turns, so it says 「下一轮发送」 instead of calling them interruptions.
     */
    pendingTurns: List<PendingTurn>,
    /** 「立即插入」 on a waiting turn: hand it to the run in flight as a steer. */
    onPromoteTurn: (String) -> Unit,
    /** Drops one waiting turn. */
    onRetractTurn: (String) -> Unit,
    /**
     * The agent's own task board, as its last `todo_write` left it. Drawn above the queue, and
     * only while this run is going: the board is the run's plan, not a second transcript.
     */
    todos: List<TodoItem> = emptyList(),
    /** 点「任务 2/5」那一行：完整清单在 ChatScreen 里画（这个 composable 拿不到那个状态）。 */
    onOpenTodos: () -> Unit,
    /** Sends a queued line now, cutting the round in flight. */
    onPromoteSteer: (String) -> Unit,
    /** Drops a queued line before the model reads it; 「修改」 uses it too. */
    onRetractSteer: (String) -> Unit,
    onFocused: () -> Unit,
    onMic: () -> Unit,
    /** 设置里的「语音输入」：关着的时候这一排里连麦克风都不画。 */
    voiceInput: Boolean,
    /** 工具审批 mode in force, and the picker that changes it; see [ApprovalMode]. */
    approvalMode: ApprovalMode,
    onPickApproval: (ApprovalMode) -> Unit,
    /** The empty provider list's way out: straight into the model settings page. */
    onOpenSettings: () -> Unit,
    /** Scrolls the transcript to its newest line; the stack's own corner button. */
    jumpToLatest: () -> Unit,
    /** Shown while the transcript is scrolled away from its end. */
    showJump: Boolean,
    /**
     * The stack's own measured height, so the list can pad by the room it truly takes out of the
     * transcript (the caller adds the strip under it and its own 12dp of air).
     */
    onHeightChanged: (Dp) -> Unit,
    /** Called once the stack has been placed for this frame: the caller's follow pin rides it. */
) {
    // 每 120ms 重组的只有这一个环（含时间读数），不拖别人。
    val scheme = MaterialTheme.colorScheme
    // 发送后键盘退避（用户：「抬起输入法发送时，对话框也会挡住」）：不收键盘的话 composer 悬在
    // 键盘上方、把列表尾的状态行盖在后面。发送＝这句话说完了，收键盘让整条栈落回去；「停止」
    // 不收（那一下没有发送任何文字，用户的手还在输入上）。
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val density = LocalDensity.current
    // The window's own width, which is what a popup has to fit inside. `screenWidthDp` is the
    // window's, not the display's, so a card that fits here also fits a split-screen window.
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { windowWidth.toPx() }
    val popupEdgePx = with(density) { POPUP_EDGE_AIR.toPx() }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // The approval mode's own card, anchored to its button like the other two.
    var approvalOpen by remember { mutableStateOf(false) }
    var approvalAnchorLeft by remember { mutableStateOf(0f) }
    var approvalWidth by remember { mutableStateOf(0) }
    // The model capsule's right edge in window pixels: the sheet is aligned to it so the card
    // hangs off the button that opened it rather than off the free space around it. Measured
    // here because the capsule is this row's child, and read by [GenerationSheet].
    var capsuleRight by remember { mutableStateOf(0f) }
    // 胶囊上沿（窗口坐标）：模型卡要贴在它上方 8dp。
    var capsuleTop by remember { mutableStateOf(0f) }
    // Where the model button's centre sits, in window pixels: the 生成设置 sheet is centred
    // on it rather than on the composer or the screen, which is what "right above the model
    // button" means. Read from the layout, because the row's paddings and the label's own
    // width decide it.
    val ctx = LocalContext.current
    // The two cards this row opens are focus-less (see [MenuCard]'s focusable): a focusable
    // popup window takes the keyboard away from the field, the shell then lays the composer
    // out a keyboard lower, and the card stays anchored to where the composer was — 250dp
    // above the button it belongs to, which is the 偏上 the user photographed. A focus-less
    // window cannot see a tap outside its own bounds and cannot see BACK, so both are caught
    // here. The scrim is a window of its own, composed before the cards: windows stack in
    // composition order, so a scrim composed after them would cover them instead.
    //
    // 纯遮罩：三张卡片都是 [MenuCard]，进出场在卡片自己身上，这层什么都不画、也不加动画修饰符。
    // `keeping` 只做一件事：卡片退场那段让遮罩留在组合里，否则「点卡片外面」会在卡片还看得见的
    // 时候漏到下面的对话流上。
    if (menuOpen || sheetOpen || approvalOpen) {
        Popup(properties = PopupProperties(focusable = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        menuOpen = false
                        sheetOpen = false
                        approvalOpen = false
                    },
            )
        }
    }
    // Focus-less popups do not receive BACK either: without this the press falls through to
    // the activity and finishes it.
    BackHandler(enabled = menuOpen || sheetOpen || approvalOpen) {
        menuOpen = false
        sheetOpen = false
        approvalOpen = false
    }
    // 手势条高度在**组合期**算一次：它是 @Composable 调用，布局 lambda 里不能调；
    // 而它在键盘动画期间不变化，捕获下来即可。键盘高度本身在布局期读（见下面的 .layout）。
    val navBarBottomPx = with(density) { WindowInsets.navigationBars.getBottom(this) }
    val imePxState = LocalHeldImePx.current
    // Nothing here paints a background: the transcript scrolls right down to the gesture
    // bar behind the pill, so the composer reads as something floating over the
    // conversation rather than an opaque strip that cuts it off.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The gesture bar's strip, but never on top of the keyboard: when the keyboard is
            // up it already covers that strip, and padding for it as well pushed the card a
            // navigation bar's height above the keyboard — the band the user photographed.
            // The shell's clamped keyboard height is what to subtract (clamped, so the
            // platform's end-of-animation rebound cannot flicker the band back for a frame).
            // **读数在布局期完成**（键盘动画期间不重组）：这一处原来在组合期减键盘高度，
            // 键盘抬起/收起时输入框每帧重组一次 —— 用户报的「输入法一抬一收就掉帧」。
            .bottomInsetPadding { (navBarBottomPx - imePxState.value).coerceAtLeast(0) }
            .padding(horizontal = 16.dp)
            // The air under the pill, named: the list's own padding is this plus the strip
            // above, and it is measured here, inside the padding (see COMPOSER_BOTTOM_PAD).
            .padding(top = 6.dp, bottom = COMPOSER_BOTTOM_PAD)
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) },
    ) {
        Column {
            // 「回到最新」按钮**不在这里**（用户报的「往上滑一点点就跳」）：它原来在本列里占
            // 48dp + 12dp，一出现就把 composer 撑高 60dp ⇒ 底部让位跟着变大 ⇒ 整条转录被顶上去
            // 60dp。现在画在 composer 之外的兄弟节点上（见 ChatScreen 里的 jumpToLatest 浮钮），
            // 高度不参与 [onHeightChanged] 的测量。
            // No progress line here any more: the run's caption lives in the transcript,
            // where the answer is going to appear. Pinned above the pill it read as a
            // second, competing status area — and it carried no clock, so the wait looked
            // untimed while the user was staring at it.
            // 多附件一排：每张 chip 可单独删（onRemoveAttachment 报下标），横向可滚 —— 不限张数时
            // 一排装不下也不能把 composer 撑高。
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = STEER_QUEUE_INSET)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEachIndexed { index, item ->
                        AttachmentChip(attachment = item, onRemove = { onRemoveAttachment(index) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // The agent's own board, above the queue: what it is doing right now, then what the
            // user has queued for it. Same inset and spacing as the queue rows below, because
            // they answer the same question — "what is going on" — and a board in different
            // clothes would read as another app's card.
            //
            // Drawn only while this conversation's run is going: the plan belongs to the run
            // that made it, and once the answer has landed the transcript is the record. The
            // board itself is emptied when a run starts (see ChatViewModel.start), so a run that
            // never touches the todo tools cannot inherit the last one's list.
            if (running && todos.isNotEmpty()) {
                val rows = todoRows(todos)
                val done = todos.count { it.status == TODO_COMPLETED }
                // 一行，只一行：进行中的那条（没有就取第一条待办），右边是完成数/总数。用户的话：
                // 「不要堆多行，就一行就行了」——整条清单改由点击这一行打开（见 TodoSheet）。
                val current = rows.firstOrNull { it.status == TODO_IN_PROGRESS } ?: rows.firstOrNull()
                if (current != null) {
                    // 与排队行同一个内缩：它们同属「输入框上方那一列」，宽度必须一致。
                    Box(Modifier.padding(horizontal = STEER_QUEUE_INSET)) {
                        TodoQueueRow(
                            item = current,
                            tally = "$done/${todos.size}",
                            onClick = onOpenTodos,
                        )
                    }
                }
                // Tight to what follows, exactly as the queue is: these rows belong to the field
                // they sit above, not to the transcript behind them.
                Spacer(Modifier.height(3.dp))
            }
            // Queued lines sit directly above the field they came from: the user's own words
            // on their way to the model, not messages yet — so they are inset from the pill
            // (visibly narrower than it, which is what stops them reading as part of it) and
            // each one carries its own actions as icons.
            // 排队 turns first: they are the ones that have not gone anywhere yet, so they sit
            // closest to the field they will be sent from. Without this row the user pressed 插话
            // again and again — the line left the field with nothing on screen to show for it.
            if (pendingTurns.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingTurns.take(STEER_ROWS_SHOWN).forEach { queued ->
                        // The same row, the same three actions — what changes is what they mean and
                        // what the row says, which `nextTurn` carries.
                        SteerQueueRow(
                            steer = PendingSteer(queued.id, queued.text, queued.imagesBase64),
                            undelivered = false,
                            nextTurn = true,
                            onPromote = { onPromoteTurn(queued.id) },
                            onEdit = {
                                onRestoreSteer(PendingSteer(queued.id, queued.text, queued.imagesBase64))
                                onRetractTurn(queued.id)
                            },
                            onDelete = { onRetractTurn(queued.id) },
                        )
                    }
                    if (pendingTurns.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingTurns.size - STEER_ROWS_SHOWN} 条待发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // 与下面 pendingSteers 块同一个 3dp（用户：「插话进队列是紧贴的，点了立即插话
                // 就有缝隙了」——两种状态的间距必须恒定）。队列行属于它将要发出的那个输入框，
                // 但「紧贴」和「恒贴 3dp」之间选恒定：状态切换时布局不跳。
                Spacer(Modifier.height(3.dp))
            }
            if (pendingSteers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingSteers.take(STEER_ROWS_SHOWN).forEach { queued ->
                        SteerQueueRow(
                            steer = queued,
                            // The run is over, so nothing will ever read this line unless the
                            // user sends it again — the row says so.
                            undelivered = !running,
                            onPromote = { onPromoteSteer(queued.id) },
                            onEdit = {
                                // Both halves go back, text and picture — see restoreQueuedSteer.
                                // The line is then retracted, so nothing is sent twice.
                                onRestoreSteer(queued)
                                onRetractSteer(queued.id)
                            },
                            onDelete = { onRetractSteer(queued.id) },
                        )
                    }
                    if (pendingSteers.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingSteers.size - STEER_ROWS_SHOWN} 条排队中",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // Tight to the pill: a queued line belongs to the field it will be sent from,
                // and a gap here made it look like a floating card of its own.
                Spacer(Modifier.height(3.dp))
            }
            // Opaque surface with one real soft shadow: the drawn concentric rings read
            // as a halo, and 86% alpha let the transcript bleed through the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    ),
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                // Light uses the canvas colour; dark steps one surface up, because there
                // `surface` IS the canvas and the pill would vanish into it.
                color = if (LocalDarkTheme.current) scheme.surfaceContainer else scheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column {
                    // Row 1: the field itself, transparent on the capsule — no border,
                    // no container, just text with room to breathe above the controls.
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) onFocused()
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            color = scheme.onSurface,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 5,
                        // Enter sends — and while a run is live it sends the line to that
                        // run instead of starting a second one. 「回车键发送」 turned off makes
                        // the key a newline instead: the field is already multi-line, and the
                        // action button under it is then the only way to submit.
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (settings.enterKeySends) {
                                ImeAction.Send
                            } else {
                                ImeAction.Default
                            },
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = input.trim()
                                // A picture with no caption is still something to send: only an
                                // entirely empty composer does nothing here.
                                if (text.isEmpty() && attachments.isEmpty()) return@KeyboardActions
                                if (running) {
                                    // The attachment rides along: 排队 mode turns this into the next
                                    // turn, 立即 mode into a steer, and either way a picture the user
                                    // attached belongs to the line.
                                    //
                                    // The field is cleared by the **view model**, not here — see
                                    // ChatViewModel.steer, which clears on both accepted paths. This
                                    // used to clear as well, and that second writer is why the text and
                                    // the picture kept coming back: `setDraft`'s defaults come from
                                    // *this composition's* captured draft, so `onInput("")` wrote the old
                                    // uri/name straight back into `_drafts`, and the collector pushed it
                                    // onto the screen a frame later.
                                    //
                                    // A refusal (the run ended between the last frame and this tap)
                                    // falls through to an ordinary send, so the line is never left
                                    // sitting in the box with nothing happening.
                                    withAttachment(attachments, text)
                                        .let { (body, images) -> if (!onSteer(body, images)) onSend() }
                                } else {
                                    onSend()
                                }
                                dismissKeyboard()
                            },
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = when {
                                            pendingSteers.isNotEmpty() ->
                                                "还有 ${pendingSteers.size} 条排队中…"

                                            running -> "插一句话…"
                                            else -> "让随行做点什么…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // Row 2: attach, the two flat selectors, mic, action — the controls
                    // live under the text now. 48dp of content, not 44: the buttons carry
                    // a 48dp minimum touch target, and a shorter row squeezed them into
                    // ellipses with the circle clipped to a 40x36 box.
                    Row(
                        modifier = Modifier
                            // 10dp, and the box beside it adds 7dp more (a 36dp box around a 22dp
                            // glyph): the 「+」 sits 17dp from the capsule's edge, which is the
                            // 16–18dp the user asked for. The row's own padding is the only lever
                            // that moves that edge without moving the gap between the two buttons.
                            .padding(start = 10.dp, end = 12.dp, bottom = 8.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        // Zero: the two buttons' own boxes supply the only gap between their glyphs,
                        // and every dp spent here is a dp the user sees between the icons.
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                    // The anchor's own left edge and the card's width, both measured: the card opens
                    // to the right of this button, and on a window narrower than the button plus
                    // the card it used to hang off the screen's right edge.
                    var plusAnchorLeft by remember { mutableStateOf(0f) }
                    var plusWidth by remember { mutableStateOf(0) }
                    Box(Modifier.onGloballyPositioned { plusAnchorLeft = it.boundsInWindow().left }) {
                        IconButton(
                            onClick = { menuOpen = true },
                            // 36dp around the glyph's original 22dp. What the eye measures is the
                            // glyph's distance to the capsule's edge (the row's 10dp plus this box's
                            // 7dp) and to the glyph beside it (7 + 0 + 8 = 15dp), and both are set by
                            // this one number — so it is chosen once rather than tuned twice: at
                            // 40dp the pair would sit 19dp apart, outside the 14–16dp asked for. The
                            // 「不要浪费空间」 the user pointed at was the 11dp a side that the 44dp
                            // box carried; this one carries 7dp.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_plus),
                                contentDescription = "添加图片或文件",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // The same card as the model sheet: one popup style for the whole
                        // screen, instead of a Material dropdown here and a card there.
                        MenuCard(
                            visible = menuOpen,
                            onDismiss = { menuOpen = false },
                            // Opens upward from the button it belongs to. BottomStart puts the
                            // popup's bottom edge on the anchor's bottom edge, and the anchor is the
                            // button's own 36dp box — so the offset clears that box plus the 8dp of
                            // air the card is meant to leave above it (-8dp alone left the card
                            // sitting on the button).
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = plusAnchorLeft,
                                        width = plusWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            // Same reason as the model sheet: this card hangs off a button of
                            // the composer too, and a focusable popup here would move the
                            // composer out from under it.
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(200.dp, windowWidth))
                                .onSizeChanged { plusWidth = it.width },
                        ) {
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_image),
                                label = "图片",
                                onClick = {
                                    menuOpen = false
                                    onPickImage()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_camera),
                                label = "拍照",
                                onClick = {
                                    menuOpen = false
                                    onTakePhoto()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_file),
                                label = "文件",
                                onClick = {
                                    menuOpen = false
                                    onPickFile()
                                },
                            )
                            // Always listed: the entry disappearing between runs made users
                            // think the feature was gone. With no run in flight the engine
                            // refuses and the toast says why — a visible refusal beats a
                            // missing menu item.
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
                                label = "压缩上下文",
                                onClick = {
                                    menuOpen = false
                                    val asked = onRequestCompress()
                                    if (asked) {
                                        Toast.makeText(ctx, "已请求压缩，本轮结束后生效", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // 没有 run：**真的压一次**，并且状态行会显示「正在压缩上下文」。
                                        // 以前这里只弹一句 toast，什么也没压（用户：「点了没反应」）。
                                        onCompressNow()
                                        Toast.makeText(ctx, "正在压缩上下文…", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                    // 工具审批, right of the add button: the same choice the settings page
                    // offers (自动运行 / 每次询问 / 禁止), one tap from the field it governs —
                    // asking for approval is a decision about the question being typed, and
                    // walking to 设置 mid-thought is how the setting stayed on whatever it was.
                    // The icon is the mode itself, so the row says which one is in force without
                    // being opened: a tick when it runs on its own, a shield while it asks, an
                    // alert shield when it refuses.
                    Box(
                        Modifier.onGloballyPositioned {
                            approvalAnchorLeft = it.boundsInWindow().left
                        },
                    ) {
                        IconButton(
                            onClick = { approvalOpen = true },
                            // Same 36dp box and the same glyph size as the 「+」 beside it: the two are
                            // one visual group on the row's left, so they are measured together.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(approvalIcon(approvalMode)),
                                contentDescription = "工具审批：${approvalMode.label}",
                                tint = when (approvalMode) {
                                    ApprovalMode.DENY -> scheme.error
                                    ApprovalMode.AUTO -> scheme.primary
                                    else -> scheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        MenuCard(
                            visible = approvalOpen,
                            onDismiss = { approvalOpen = false },
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = approvalAnchorLeft,
                                        width = approvalWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(220.dp, windowWidth))
                                .onSizeChanged { approvalWidth = it.width },
                        ) {
                            ApprovalMode.entries.forEach { mode ->
                                MenuItemRow(
                                    icon = ImageVector.vectorResource(approvalIcon(mode)),
                                    label = mode.label,
                                    selected = mode == approvalMode,
                                    onClick = {
                                        approvalOpen = false
                                        onPickApproval(mode)
                                    },
                                )
                            }
                            // The mode is read when a run starts (see ApprovalGate), so saying
                            // so here is the difference between a setting and a surprise.
                            Text(
                                text = "下一次任务生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                    // One selector for both decisions: the model and the reasoning
                    // level live together in the 生成设置 sheet, so the row keeps a
                    // single caption instead of two competing ones.
                    //
                    // The caption is the row's flexible child (weight, not a fixed cap):
                    // capping it at 104dp was what squeezed a long model id down to a single
                    // ellipsis while the rest of the row sat empty, and it could still
                    // overflow a 360dp screen once the ring, the mic and the send button
                    // were all present. Weighted, it takes exactly the space the controls
                    // leave — never more — so the row can neither clip the send button nor
                    // starve the name. The name itself caps at 160dp and ellipsizes after
                    // that, which is the one truncation the user asked for.
                    Box(Modifier.weight(1f)) {
                        // The sheet hangs off the capsule itself, not off a probe at the top
                        // of the stack: anchored there it opened above the whole composer, and
                        // the user asked for it above the model button. Right-aligned inside
                        // the free space so the capsule sits against the ring, as asked.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                // The probe and the capsule are siblings here, and that is the whole
                                // of it: as a *wrapper* around them the probe stretched this box —
                                // a Box is as wide as its widest child, and a full-width child makes
                                // it the whole free space — and the capsule inside then sat at that
                                // box's own start (TopStart, the default), i.e. the model selector
                                // at the far left of an empty row with the ring way off to the
                                // right. That is exactly the screenshot the user sent: the
                                // right-alignment was written and had nothing to align.
                                //
                                // Zero-height probe at the capsule's TOP edge: the popup hangs
                                // off it bottom-aligned, so its bottom edge lands 8dp above the
                                // capsule. Hung on the capsule box itself, BottomStart put the
                                // popup's bottom on the capsule's bottom — covering the button
                                // that opened it, which is what the user photographed.
                                // The capsule's own right edge, measured: the sheet hangs off
                                // the button that opened it, and the free space around the
                                // button is wider than the button whenever the model name is
                                // short — right-aligning to the free space left the card that
                                // far to the right of the control it belongs to.
                                Box(
                                    Modifier.onGloballyPositioned {
                                        capsuleRight = it.boundsInWindow().right
                                        capsuleTop = it.boundsInWindow().top
                                    },
                                ) {
                                // **卡片挂在按钮自己的 Box 里**（与添加菜单、审批菜单同构）：Popup 的
                                // 锚点就是这个 28dp 高的按钮框，于是卡片底边落在按钮上沿 −8dp、
                                // 右缘对齐按钮右缘。原来它挂在一个整行宽的零高探针上，锚点是屏幕
                                // 左边那条线，卡片因此跑到别的位置。
                                GenerationSheet(
                                    settings = settings,
                                    assistantId = assistantId,
                                    conversationProviderId = conversationProviderId,
                                    conversationModel = conversationModel,
                                    conversationReasoningEffort = conversationReasoningEffort,
                                    onChooseReasoning = onChooseReasoning,
                                    visible = sheetOpen,
                                    onPickModel = onPickModel,
                                    onOpenSettings = onOpenSettings,
                                    onDismiss = { sheetOpen = false },
                                )
                                UnifiedSelector(
                                settings = settings,
                                assistantId = assistantId,
                                conversationProviderId = conversationProviderId,
                                conversationModel = conversationModel,
                                conversationReasoningEffort = conversationReasoningEffort,
                                onClick = {
                                    sheetOpen = true
                                    // The sheet is the one place models are looked at, so
                                    // this is where the endpoint's own window numbers are
                                    // worth asking for — once per endpoint per process.
                                    onPrefetchWindows()
                                },
                                )
                                }
                        }
                    }
                    // Left of the mic, where the user asked for it: the mic is the control
                    // reached for mid-thought, and this is the gauge that says whether the
                    // next question still fits in the window.
                    ContextRing(
                        used = contextUsed,
                        estimated = contextEstimated,
                        window = contextWindow,
                        breakSplit = contextBreak,
                        onPanelOpen = onContextPanelOpen,
                    )
                    // The ring's own right margin: the row packs its children edge to edge, and the
                    // gauge sat hard against whatever follows it — the mic when 语音输入 is on, the
                    // send button when it is not. Four dp, and nothing else in the row moves: the
                    // model capsule to its left takes the free space either way.
                    Spacer(Modifier.width(4.dp))
                    // 设置里的「语音输入」是唯一的开关，默认关：关着时这里什么都不画，连占位都不留。
                    // 开着也不需要系统识别器了——我们自己的录音链路走 AudioRecord，不会再弹系统面板。
                    if (voiceInput) {
                        IconButton(
                            onClick = onMic,
                            enabled = !running,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_mic),
                                contentDescription = "语音输入",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    val hasSomething = input.isNotBlank() || attachments.isNotEmpty()
                    // While a run is going, a field with something in it sends that line to the
                    // run; an empty field stops it. One button, and what it does is what the
                    // field in front of the user says — the separate 插话 button only ever made
                    // the user wonder which of the two they wanted.
                    //
                    // "Something" is text **or** a picture: a screenshot with no caption is still a
                    // turn the user wants to send, and judging by text alone left that case with no
                    // way out at all — the button stayed on 停止 and the attachment could not go
                    // anywhere until the run ended. 停止 is for an empty field, nothing else.
                    val steers = running && hasSomething
                    val sendable = running || hasSomething
                    CircleActionButton(
                        icon = if (running && !steers) {
                            ImageVector.vectorResource(R.drawable.ic_stop_filled)
                        } else {
                            ImageVector.vectorResource(R.drawable.ic_arrow_up)
                        },
                        contentDescription = when {
                            steers -> "插话发送"
                            running -> "停止"
                            else -> "发送"
                        },
                        onClick = {
                            when {
                                // Cleared by the view model when it takes the line, not here — see the
                                // note on the Enter path above. A refusal falls through to a normal
                                // send, so the text cannot be stranded in the field.
                                steers -> {
                                    withAttachment(attachments, input.trim())
                                        .let { (body, images) -> if (!onSteer(body, images)) onSend() }
                                    dismissKeyboard()
                                }
                                running -> onStop()
                                else -> {
                                    onSend()
                                    dismissKeyboard()
                                }
                            }
                        },
                        enabled = sendable,
                        size = 40.dp,
                        // Three states, three colours: a live run offers stop, a field with
                        // something in it turns brand-coloured so the button reads as ready,
                        // and an empty field stays a quiet grey circle.
                        containerColor = if (running || hasSomething) {
                            scheme.primary
                        } else {
                            scheme.surfaceVariant
                        },
                        contentColor = if (running || hasSomething) {
                            scheme.onPrimary
                        } else {
                            scheme.onSurfaceVariant
                        },
                        // Flat: the pill's own lift is the only depth in this corner.
                        shadowElevation = 0.dp,
                        iconSize = 22.dp,
                    )
                    }
                }
            }
        }
    }
            }
}

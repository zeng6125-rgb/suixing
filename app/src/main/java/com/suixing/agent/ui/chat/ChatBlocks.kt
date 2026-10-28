
package com.suixing.agent.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.ui.chat.markdown.MarkdownText
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun BranchArrow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = scheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * How wide a process block's own name may get before it starts yielding.
 *
 * A name is short by nature — 「思考过程」 is four characters, `run_shell` nine — so it is never
 * given a share of the row to fill: it takes what it needs and hands the rest to the status and
 * the preview beside it. The cap only stops a long MCP name from eating the whole header.
 */
internal val PROCESS_TITLE_MAX = 160.dp

/**
 * 折叠块展开/收起的高度动画时长。
 *
 * 180 → 220（用户：「慢一点点，不要太生硬即可」）：180ms 对整块卡片来说收得太急，末尾像"啪"地
 * 停住；220ms 仍明显短于长按守护窗 [LONG_PRESS_SETTLE_MS]（400ms），所以短按点折叠条时
 * 整段动画依然落在「抬手即放行追底」之前（见 ChatScreen 的手势段注释）。
 */
internal const val PROCESS_EXPAND_MS = 220

/**
 * 折叠块展开/收起的缓动曲线（0.8.297）。
 *
 * 原来用 `LinearOutSlowInEasing`（= `CubicBezierEasing(0, 0, 0.2, 1)`）：它在 t=0 的斜率约 5，
 * 动画**第一帧**就已经走到 12% ⇒ 展开时下方内容一帧内被推开整块高度的 12%（540px 的块 = 65px
 * 一跳），收起时同理 —— 看起来就是「弹一下」。
 *
 * 换成 M3 的 standard 曲线（`CubicBezierEasing(0.2, 0, 0, 1)`）：起点斜率为 0，第一帧只走 1.2%
 * （540px 的块 = 6px），展开是「慢慢长出来」、收尾也是慢慢合上，中间快 —— 头尾都不再跳。
 */
internal val REVEAL_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 主转录的滚动状态：折叠块展开/收起时，若**不在底部**，要按高度增量反向补偿，
 * 才能让块顶边钉住、内容向下展开（见 [RevealHeight]）。
 */
internal val LocalChatListState = compositionLocalOf<androidx.compose.foundation.lazy.LazyListState?> { null }


/**
 * 外观 → 打字机效果（默认开）。只落在**两个**流式渲染点上：正文的 Markdown 与思考块。
 * 用 composition local 而不是逐层传参，是因为这两点离设置有好几层，而参数要穿过折叠卡片、
 * 工具块、轮次渲染器。
 */
internal val LocalTypewriter = compositionLocalOf { true }

/**
 * 卡片级可点块的按下反馈：**整块背景压暗**，不用涟漪。
 *
 * M3 的 `ripple()` 是 bounded 的：半径＝触摸点到本节点**最远角**的距离。放在一个又高又宽的卡片上，
 * 它画出来就是一个大圆（被圆角裁掉一半仍是个圆），与「这一块被按下了」的预期完全不是一回事 ——
 * 用户看到的就是「为什么是个圆形」。这里换成整块 8% 的 onSurface 叠色，形状与可见圆角一致，
 * 松手即恢复。
 *
 * 只给**卡片级**的可点块用（工具行、折叠卡片的表头、检查点卡）；单行小按钮（图标按钮、菜单行、
 * 26dp 的圆形动作）保留 M3 原生涟漪，那才是涟漪本来该有的样子。
 *
 * 没有复用 `suixingClickable`：它的两个重载都把 `indication = ripple()` 写死了，没有留出
 * 「不要涟漪」的入口。
 */
@Composable
internal fun Modifier.blockPress(shape: Shape, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .clip(shape)
        .background(
            if (pressed) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            } else {
                Color.Transparent
            },
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 「外层流式行正在做滞后补偿」——为 true 时，里面的 [RevealHeight] **不再自己补滚动**。
 *
 * 为什么需要它（issue #17，用户实测「读历史过程中还是有抖动，貌似是思考块流式后再折叠导致的」）：
 * 流式行里的思考块/工具块走 RevealHeight，它有自己的逐帧补偿；而流式行外层又会量到同一次
 * 高度变化并补一次 ⇒ **同一份增量被补两次** ⇒ 过冲/回弹。
 * 两处只能有一处补：外层滞后补偿生效时由外层统一补，RevealHeight 只负责动高度。
 * 贴底（外层不补）时这个 flag 为 false，RevealHeight 行为不变。
 */
internal val LocalLiveRowLag = compositionLocalOf { false }

/**
 * 折叠块的展开/收起：**纯高度动画**。
 *
 * 不用 `AnimatedVisibility(expandVertically)` 是因为这里要按内容自然高度逐帧写 progress，
 * 且展开与收起共用一套裁剪。滚动不在这里做 —— 转录是底锚（reverseLayout），index 0 的
 * 卡片长高天然向上、底边不动；旧版在这里逐帧 `dispatchRawDelta` 同步列表，两处抢同一份
 * 列表才是"先向下、再向上"回弹的来源，已随底锚一起删掉。
 *
 * 展开/收起一律有动画（0.8.319 起不再按「是否在读历史」区分：那个 signal 的唯一
 * 作用变成了「上翻时把正在跑的动画打断重启」，已删除）。
 */
/**
 * ③ **收尾按实测位移对账**：逐帧补偿用的是推算高度，遇到外层约束（钳位、`weight`、嵌套滚动）
 * 就会多补。这里用 `positionInRoot` 量出来的真实位移补差值：逐帧补偿正确时它是空操作，
 * 有偏差时它正好是那份差值。
 */
private fun reconcileRevealTop(
    outer: androidx.compose.foundation.lazy.LazyListState?,
    before: Float,
    after: Float,
) {
    if (outer == null || !outer.layoutInfo.reverseLayout || !outer.canScrollBackward) return
    if (before.isNaN() || after.isNaN()) return
    val drift = after - before
    if (abs(drift) > 1f) outer.dispatchRawDelta(-drift)
}

@Composable
private fun RevealHeight(
    visible: Boolean,
    durationMs: Int,
    content: @Composable () -> Unit,
) {
    // 外层流式行正在做滞后补偿时，这里只动高度、不碰滚动（见 [LocalLiveRowLag]）。
    // **必须经 `rememberUpdatedState` 读**（评审 M1）：帧循环所在的 `LaunchedEffect(visible)`
    // 只以 `visible` 为 key，局部 `val` 变了**不会重启协程** ⇒ 读到的是动画开始那一刻的值。
    // 后果：钉住期间（外层已把高度钉死、补偿必须为 0）它仍是旧值 false ⇒ 帧循环继续
    // `dispatchRawDelta(comp)`，把内容按剩余补偿量推走 —— 正是 #17 要治的那份漂移。
    val suppressOuterCompensation by rememberUpdatedState(LocalLiveRowLag.current)
    var progress by remember { mutableFloatStateOf(if (visible) 1f else 0f) }
    // 内容的自然高度（px）。普通数组而不是 State：只在动画循环里读，不参与组合。
    val natural = remember { intArrayOf(0) }
    val outer = LocalChatListState.current
    // **这个块在根坐标系里的 y**（issue #22）：补偿量不再靠"推算长了多少"，而是量
    // "动画前后这个块实际移动了多少"。任何布局约束（`heightIn(max)`、嵌套滚动、`weight`）
    // 让实际高度 ≠ 自然高度时，推算都会错，测量不会。
    //
    // 用普通数组而不是 State：它**每帧**写一次，State 每写一次都要发一次快照通知
    // （TODO #29 列的嫌疑项之一）。数组读出来就是最新值，语义与 State 一致。
    val topInRoot = remember { floatArrayOf(Float.NaN) }

    LaunchedEffect(visible) {
        val target = if (visible) 1f else 0f
        val start = progress
        if (start == target) return@LaunchedEffect
        // 展开的第一帧还没量过内容：先让出一帧让 Layout 量一次，再决定怎么动。
        var px = natural[0]
        if (px <= 0) {
            withFrameNanos { }
            px = natural[0]
        }
        // **一律有动画**（用户：「折叠展开一会有一会没有」）：原来是
        // `if (pinned) 0 else durationMs` —— 贴底时有动画，往上翻过之后变成瞬间展开，
        // 于是同一个动作时有时无。位置稳定交给 ChatScroll 的阅读锚点，不再靠"取消动画"。
        val dur = durationMs
        if (dur <= 0 || px <= 0) {
            progress = target
            return@LaunchedEffect
        }
        // ① 先让一帧落地，记下动画开始前的真实位置。
        withFrameNanos { }
        val beforeTop = topInRoot[0]
        // ② 高度动画 + 逐帧补偿，**恢复成原来的 `px * progress` 增量式**。
        //
        // 上一版改成"逐帧按实测位移补"，结果第一层也偏了 —— 原因有两个：
        //  · 丢了 `canScrollBackward` 这个门：贴底时（底锚天然钉住底边）本来就不该补，
        //    现在照补，于是第一层一展开就把画面推下去；
        //  · `topInRoot` 是 `onGloballyPositioned` 在**布局之后**写的，动画循环里读到的是
        //    上一帧的值，补偿永远慢一拍，形成"补一点、位置又变、再补"的回路。
        // 所以逐帧回到原来的写法（对第一层已验证有效），把"跟实际位移的偏差"交给 ③ 的收尾对账。
        val begin = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - begin) / 1_000_000f / dur).coerceIn(0f, 1f)
            val oldH = px * progress
            progress = start + (target - start) * REVEAL_EASING.transform(t)
            // **这一帧该补多少**：必须和高度变化落在**同一帧**，晚一拍就一直在抖。
            //
            // 真机埋点（0.8.295，153 帧）实测：补偿晚一帧时，块顶边每帧正好偏「一帧的高度增量」
            // （5~75px），最后靠收尾对账拉回来 ⇒ 观感就是「展开/收起时位置一直在变，最后位置是对的」。
            //
            // 补偿量 = 这个块自己的高度增量（0.8.298 起**所有层**都这样）。
            //
            // 以前嵌套层要另外算「外层容器的高度增量」，是因为工具条那个容器**有高度上限**：
            // 容器顶到上限后不再跟着内容长，块自己长高 ≠ 容器长高，差多少得看钳位
            // （真机实测 0.8.296：跨上限那一帧差 6~7px ⇒ 顶边跳一下）。
            // 现在工具条的外层改成**普通 Column、没有上限**（用户：工具条不该是虚拟列表，
            // 也不该被截断）⇒ 容器高度 == 内容高度 ⇒ 容器长高 == 这个块长高 ⇒ 一套算法通吃。
            val growth = px * progress - oldH
            val comp = growth
            // 外层流式行在补时这里绝不能补（否则同一份增量补两次 ⇒ 抖动）。
            if (!suppressOuterCompensation && comp != 0f && outer != null &&
                outer.layoutInfo.reverseLayout && outer.canScrollBackward) {
                outer.dispatchRawDelta(comp)
            }
            if (t >= 1f) break
        }
        // ③ 收尾按实测位移对账。
        withFrameNanos { }
        withFrameNanos { }
        if (!suppressOuterCompensation) {
            reconcileRevealTop(outer = outer, before = beforeTop, after = topInRoot[0])
        }
    }

    // **折叠静止时内容不进组合**（issue #42 补充，13ms 帧的主因）：`Layout(content=)` 原来
    // 无条件组合内容 —— 折叠只是测量跳过，节点创建照付。用户「历史思考块默认展开=关」⇒
    // 每张卡里的思考块都是折叠态 ⇒ 每次进视口都白付内层 LazyColumn+snapshotFlow 的组合成本
    // ⇒ 帧时 13ms > 8.3ms（120Hz 预算）⇒ 掉半帧率的「顿」。展开/收起动画期间（progress>0）
    // 照常组合，动画不受影响。
    // Composition observes only mounting/unmounting. Fractional height stays a measure read,
    // so each animation frame can reuse the unchanged content's composition and constraints.
    val contentMounted by remember(visible) { derivedStateOf { visible || progress > 0f } }
    if (contentMounted) Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            // 高度在动画中间时内容会超出自己的框，裁掉，否则它会画到下一行上。
            // **0.8.285 回退**：0.8.277~0.8.284 曾改用 `graphicsLayer { clip = … }`（为了少一层
            // saveLayer），但实测展开/收起会出现残影；这里回到最初的 `clipToBounds()`。
            .clipToBounds()
            .onGloballyPositioned { coords -> topInRoot[0] = coords.positionInRoot().y },
    ) { measurables, constraints ->
        // 收起且没有动画时不量：一列收起的长思维链没有理由每次都测一遍。
        if (!visible && progress <= 0f) {
            layout(constraints.maxWidth, 0) { }
        } else {
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
            // **注意：不能用 `constraints.maxHeight` 钳**（issue #24 情况 A 在这里不成立）。
            // `LazyColumn` 测量子项时，滚动方向给的是 `Constraints.Infinity` —— 两层 RevealHeight
            // 都嵌在 LazyColumn 的 item 里，所以拿到的 maxHeight 都是无穷大，钳了等于没钳（实测
            // 加过这行、是空操作，已撤回）。
            natural[0] = placeable.height
            val h = (placeable.height * progress).roundToInt().coerceIn(0, placeable.height)
            layout(constraints.maxWidth, h) { placeable.place(0, 0) }
        }
    }
}

/**
 * The chrome every folded process block shares: thinking, a tool call and the turn's own
 * summary all draw through this, so a reply that thinks, calls, thinks again reads as one
 * repeating shape instead of three unrelated widgets stacked in a column.
 *
 * The header *is* the summary line — there is no second row above it — which is what
 * keeps a turn that ran ten tools the height of one row.
 */
@Composable
internal fun ProcessCard(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** First line of the folded content, shown after the meta while the card is closed. */
    preview: String? = null,
    /** Drawn right after [preview]: the streaming cursor for a folded think. */
    previewTail: (@Composable () -> Unit)? = null,
    /** The turn summary owns its seam with the flat tail; other cards keep their usual air. */
    contentBottomPadding: Dp = 6.dp,
    /** false = 没有可展开内容：不响应点击、不画箭头（卡片仍显示，用来报用时）。 */
    expandable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // *折叠条切换后视口不动** —— 不在这里做任何事：这一项变高变矮会把上方内容推走，而 ChatScreen
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Exactly 36dp folded: the fourth time the user has nailed this
                // card's height down, so it is a fixed height, not a minimum —
                // nothing in the row wraps (every text is maxLines 1).
                .height(36.dp)
                // 整块按下反馈（见 blockPress）：这一行是折叠条的入口，涟漪是一颗小圆角矩形。
                .then(
                    if (expandable) Modifier.blockPress(ProcessHeaderShape) { onToggle() } else Modifier,
                )
                .padding(horizontal = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                // One slot for all three card families: the icon sits centred in a
                // 24dp box at 16dp, so Psychology, Build and Insights line up exactly
                // instead of each header carrying its own size and tint.
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    // 折叠块统一：标题一律 labelMedium（原来这里是 labelLarge×13/14）。三处
                    // （思考块 / 工具行 / 本轮用量）的表头现在同一行高、同一字号、同一内边距。
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The name takes what it needs, not half the row. `weight(1f, fill = false)`
                    // used to hand it a share of the width and keep it even when the name was
                    // short: the leftover sat right after 「思考过程」 as a band of nothing, and a
                    // name as short as `run_shell` could come back cut in half inside it. With a
                    // preview beside it the name yields at [PROCESS_TITLE_MAX]; without one it
                    // still holds the row, which is what lets the summary card's long header
                    // ellipsize instead of the chip after it.
                    // 标题吃满剩余宽度 ⇒ 箭头贴行最右（有 preview 的卡片才收缩）。
                    modifier = if (preview != null) {
                        Modifier.widthIn(max = PROCESS_TITLE_MAX)
                    } else {
                        Modifier.weight(1f)
                    },
                )
                if (trailing != null) {
                    // The title and the status are two different facts; without this gap
                    // they read as one word — 「run_shell1 个失败」.
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
                if (preview != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = preview,
                        style = metaStyle(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot: it eats whatever the name, the status and
                        // the chevron leave, so the chevron stays on the card's right edge and
                        // nothing is left over behind it. It used to be `fill = false` as well,
                        // which is what left that leftover band sitting after the chevron.
                        modifier = Modifier.weight(1f),
                    )
                    previewTail?.invoke()
                }
                if (expandable) Icon(
                    imageVector = if (expanded) {
                        ImageVector.vectorResource(R.drawable.ic_chevron_up)
                    } else {
                        ImageVector.vectorResource(R.drawable.ic_chevron_down)
                    },
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // 展开体：高度动画由 [RevealHeight] 驱动（滚动交给底锚，不再逐帧同步列表）。
            // 前两版都没成：只用 expandVertically + 追底＝两套逐帧动画，谁先谁后不确定，总会
            // 露出一帧位移；干脆不做高度动画＝不闪，但"啪"地出现，生硬（用户：「还是会闪一下
            // ……动画也生硬」）。这一版只做高度：逐帧把进度写进 progress，卡片底边由底锚
            // 钉住，长高只向上。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                Column {
                    // Header and content are one card; the hairline is their only seam.
                    HorizontalDivider(color = scheme.outlineVariant)
                    // The grid the expanded rows share: 12dp of side air, 8dp above and
                    // below, and every row inside lines its own column up to this edge.
                    Column(
                        // 水平 0dp（用户：「展开时原样展开」）：运行时那些块是顶到卡片左右边缘的，
                        // 12dp 内边距让展开后的内容整体变窄 —— 就是「又窄又短」的那一半。
                        // **上方 0dp**（用户：「折叠条分割线下方还有一处空白，不能去掉吗」）：
                        // 那 8dp 就是分割线下面那道空白，所有折叠块共用这一处，所以在这里一次去掉。
                        // 下方仍留 8dp：展开内容与卡片下沿之间需要一点收尾空气。
                        Modifier.padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = contentBottomPadding),
                        content = content,
                    )
                }
            }
        }
}

/**
 * 12sp for the trailing status of a [ProcessCard].
 *
 * Expressed off labelMedium rather than as a bare 12.sp so the user's 字号 still applies:
 * the theme scales every slot, and a hard size would opt this text out of it.
 */
/** 折叠条表头统一圆角：文件级常量，避免每次组合分配。 */
private val ProcessHeaderShape = RoundedCornerShape(8.dp)
@Composable
internal fun metaStyle(): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    return remember(base) { base.copy(fontSize = base.fontSize * (12f / 13f)) }
}

/** The trailing status of a [ProcessCard]: a short phrase, never a sentence. */
@Composable
internal fun ProcessStatus(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = metaStyle(),
        color = tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The spinner both process blocks show while they are the one being waited on. */
@Composable
internal fun ProcessSpinner(tone: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(13.dp),
        strokeWidth = 2.dp,
        color = tone,
    )
}

/**
 * 一条过程留痕（「自动重试 1 次 · 服务器限流（429）」）。
 *
 * 刻意只做**一行小字 + 图标**，不是 [ProcessCard]：它没有可展开的内容，也不该看起来像一次
 * 工具调用。但它必须留在转录里 —— 这一轮是成功的，用户回头要能看出"这次慢是因为重试过"，
 * 而不是只看到一张说这轮失败了的红卡（用户：「重试成功要在过程态中留痕……整轮成功就不要有
 * 错误提示卡片」）。
 *
 * **[modifier] 由调用方给，块自己不带任何上下留白。** 两条渲染路径的间距来源不一样：流式那条
 * 每个 part 之间已经有 6dp 的 Spacer，转录那条没有、靠各 part 自己的内边距。写死在这里，两边的
 * 上下边距必然有一边对不上（用户截的就是这个：重试行贴着上面、离下面远）。
 */
@Composable
internal fun TraceBlock(note: String, modifier: Modifier = Modifier) {
    // **与折叠条同一套栅格**（用户：「你把它改成和思考块 工具块这种不行吗？这样的嵌入 icon
    // 标题什么都没有对齐」）：36dp 行高、图标居中在 24dp 槽里（16dp）、8dp 之后接标题，
    // 标题用 labelMedium —— 与 ProcessCard 的表头逐项相同，所以思考块 / 工具块 / 重试留痕
    // 三者的图标和文字左边线在一条竖线上。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 运行中的一次上下文压缩：画成过程块，位置就是它发生的那一刻。
 *
 * 与转录里那张 [CheckpointCard] 是同一件事的两种形态 —— 那张按"被折叠的最后一条消息"定位，
 * 负责回看；这一块属于正在跑的这轮，所以出现在「思考 → 工具 → 压缩 → 继续」的顺序里。
 * 不再往气泡下面塞一行提示（用户：「不需要这种在卡片下的提示」）。
 */
@Composable
internal fun CompressionBlock(
    checkpoint: ContextCheckpoint,
    expansion: ReasoningExpansion,
    blockId: String,
) {
    val expanded = expansion.stored(blockId) ?: false
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文",
        expanded = expanded,
        onToggle = { expansion.set(blockId, !expanded) },
        preview = "${checkpoint.messages} 条消息 → 摘要 · 压缩前约 ${shortTokens(checkpoint.promptTokens)}",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(remember(expansion, blockId) { expansion.viewport(blockId).scrollState }),
        ) {
            Text(
                text = checkpoint.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Which thinking blocks the user has opened or closed, for the conversation on screen.
 *
 * A block's state cannot live inside the block: the live think and the stored one it turns
 * into are two different call sites, so a `remember` there started from scratch the moment
 * the reply was saved (and with 思考过程默认展开 on, that meant a think never folded). The
 * conversation is part of every key, so two chats never share a block's switch.
 */
@Stable
internal class ReasoningExpansion(
    private val conversationId: String,
    private val entries: MutableMap<String, Boolean>,
) {
    // Live and stored bubbles are different composition groups. Protect this run's
    // viewports through hand-over, plus 16 recent historical viewports. An arbitrary
    // cap on the active run would lose the first positions when 17+ blocks remount.
    // Entries contain state only, never a job, composable, or Activity.
    private val viewports = LinkedHashMap<String, ReasoningViewport>(16, 0.75f, true)
    private var protectedPrefix: String? = null
    private var historicalViewports = 0

    fun protectRun(runId: String) {
        val prefix = "$runId:"
        if (protectedPrefix == prefix) return
        protectedPrefix = prefix
        historicalViewports = viewports.keys.count { !isProtected(it) }
        trimHistoricalViewports()
    }

    fun viewport(blockId: String): ReasoningViewport {
        viewports[blockId]?.let { return it }
        val state = ReasoningViewport()
        viewports[blockId] = state
        if (!isProtected(blockId)) historicalViewports++
        trimHistoricalViewports()
        return state
    }

    private fun isProtected(blockId: String): Boolean = protectedPrefix?.let { blockId.startsWith(it) } == true

    private fun trimHistoricalViewports() {
        if (historicalViewports <= 16) return
        val oldest = viewports.entries.iterator()
        while (historicalViewports > 16 && oldest.hasNext()) {
            if (!isProtected(oldest.next().key)) {
                oldest.remove()
                historicalViewports--
            }
        }
    }

    /** Null while this block has no opinion of its own. */
    fun stored(blockId: String): Boolean? = entries["$conversationId:$blockId"]

    fun set(blockId: String, expanded: Boolean) {
        entries["$conversationId:$blockId"] = expanded
    }

    fun hasOpenFold(parts: List<Part>, runId: String): Boolean = parts.indices.any { index ->
        val slot = processFoldSlot(parts, index, runId) ?: return@any false
        if (stored(slot) == true) return@any true
        val tool = parts[index] as? Part.Tools ?: return@any false
        stored(slot) != false && tool.calls.indices.any { stored("$slot:call:$it") == true }
    }

    fun summaryExpanded(parts: List<Part>, runId: String, streaming: Boolean): Boolean =
        stored(summarySlot(runId)) ?: (streaming || stored("$runId:reading") == true || hasOpenFold(parts, runId))

    /** Reading is a user action too: don't auto-close what was open under that reader. */
    fun retainForReading(parts: List<Part>, runId: String, expandTools: Boolean) {
        protectRun(runId)
        if (stored("$runId:reading") != true) set("$runId:reading", true)
        parts.forEachIndexed { index, part ->
            val slot = processFoldSlot(parts, index, runId) ?: return@forEachIndexed
            if (stored(slot) == null && when (part) {
                is Part.Thinking -> true
                is Part.Tools -> expandTools
                else -> false
            }) set(slot, true)
        }
    }
}

@Stable
internal class ReasoningViewport {
    val listState = LazyListState()
    val scrollState by lazy { ScrollState(0) }
    var follow by mutableStateOf(true)
}

internal fun summarySlot(runId: String): String = "$runId:summary"

/** Last prose and anything that arrived after it stay in source order outside the process card. */
internal fun processTailStart(parts: List<Part>): Int =
    parts.indexOfLast { it is Part.Text }.takeIf { it >= 0 } ?: parts.size

internal fun processFoldSlot(parts: List<Part>, index: Int, runId: String): String? = when (val part = parts[index]) {
    is Part.Thinking -> thinkSlot(parts, index, runId)
    is Part.Tools -> {
        var before = 0
        for (i in 0 until index) before += (parts[i] as? Part.Tools)?.calls?.size ?: 0
        "$runId:tools:$before"
    }
    is Part.Compress -> "$runId:compression:${part.checkpoint.id}"
    else -> null
}

/**
 * Which slot a thinking block occupies in the screen's table of open blocks.
 *
 * The key is the run that produced the think plus how many thinks that run had already
 * produced — not the composable's position, and not the message id, because neither of
 * those is shared by the live block and the stored message that replaces it. The run id is:
 * the engine stamps it on the message it saves, so the same think answers to the same key
 * on both sides of the hand-over. [fallback] covers a message stored before runs were keyed.
 */
internal fun thinkSlot(parts: List<Part>, index: Int, fallback: String): String {
    val part = parts.getOrNull(index) as? Part.Thinking ?: return index.toString()
    // 一次遍历数「同一 run 在这一段之前已经有几段思考」：原来 `parts.take(index)` 每次都分配
    // 一个中间 list —— 卡片里每段思考调一次，展开体里每段再调一次，纯属白分配。
    var before = 0
    for (i in 0 until index) {
        val earlier = parts[i]
        if (earlier is Part.Thinking && earlier.runId == part.runId) before++
    }
    return "${part.runId.ifEmpty { fallback }}:$before"
}

/**
 * The model's thinking, folded into the reply's own card.
 *
 * It is drawn in its own [LazyColumn] so a 10k-character chain of thought only ever
 * composes the handful of lines on screen — laying the whole string out as one [Text] is
 * what makes a long think stutter. Whether it is open is not remembered here: it is a row
 * of the screen's [ReasoningExpansion] table, so the choice outlives this composable and
 * survives the hand-over from the live row to the stored message.
 */
@Composable
internal fun ReasoningBlock(
    text: String,
    streaming: Boolean,
    /** The run can still be live after this individual think stopped growing. */
    runStreaming: Boolean = streaming,
    /** This block's slot in the screen's table: see [thinkSlot]. */
    blockId: String,
    expansion: ReasoningExpansion,
    reasoningTokens: Int = 0,
    elapsedMs: Long = 0,
    /**
     * 外观 → 历史思考块默认展开 ([AppSettings.expandReasoning]): what a block does when the
     * user has never touched its header. A block that is still arriving is always open — it is
     * the reason to run a thinking model, and watching it arrive is the point. One that has
     * settled opens only if this is on: with it off, the transcript stays a transcript, and the
     * header tap is the way into any think the reader wants in full.
     */
    expandReasoning: Boolean = true,
    /**
     * Inside an expanded summary card the block is a row of the card's grid, not a
     * card of its own: no surface, no second header height — just the icon slot, the
     * title and the chain, lined up with the tool rows beside it.
     */
    nested: Boolean = false,
    /** How tall the chain's inner scroll may grow; the appearance page owns it. */
    maxHeight: Dp = 180.dp,
    /**
     * 外观 → 思考块跟随最新: whether the chain's own scroll chases its tail while the think
     * streams. A parameter rather than a composition local because this is the one value the
     * nested rows already carry [maxHeight] for, and a second hidden channel for a second
     * boolean is how two sources of the same truth start to disagree. Defaults to true, which
     * is what the block did before the switch existed.
     */
    followReasoning: Boolean = true,
) {
    // Open unless the user closed it. Both halves of the report are answered here: a block used
    // to fold itself the moment its answer landed, and — past 200 lines — to draw only its first
    // 200 with a 「展开全部 N 行」 button, so the chain being read was replaced by a control the
    // moment it grew. The chain is now drawn whole (its height cap below is the bound, not its
    // length), and the only thing that closes a block is a tap on its own header — recorded in
    // the expansion table, which outlives the hand-over from the live row to the stored message.
    //
    // What a block nobody has touched does is the one thing the appearance setting still decides:
    // a think that is still arriving is open (watching it arrive is why the model was asked to
    // think), and one that has settled follows 历史思考块默认展开. Off, a finished think folds to
    // its header — the reader's tap is the way in, and the table remembers it per conversation.
    val expanded = expansion.stored(blockId) ?: (runStreaming || expandReasoning)
    // **增量切行**（见 [IncrementalLines]）：文本是逐字变长的，`text.split('\n')` 每一拍都在
    // 全文上重做一次切分 + 分配一个新列表。切分器记住上一次的文本与结果，只切新增那一段。
    val splitter = remember { IncrementalLines() }
    val lines = remember(text) { splitter.linesFor(text) }
    // **remember 真正的依赖**（原来每帧一个 buildList）：`meta` 只由这两个数决定。
    val meta = remember(reasoningTokens, elapsedMs) {
        buildList<String> {
            if (reasoningTokens > 0) add("$reasoningTokens tokens")
            if (elapsedMs > 0) add(formatDuration(elapsedMs))
        }
    }
    // Source order is also visual order. A trailing blank line owns the cursor
    // immediately after a newline, rather than moving it back onto the preceding line.
    val cursorAt = if (streaming) lines.lastIndex else -1
    val firstLine = remember(lines) { lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
    // The chain itself, shared by both shapes: a think with no text at all (the
    // endpoint reported time but no chain) must not render as an empty slab, and a
    // monster chain stays bounded at 320dp of lazy list that follows its tail while
    // the think streams.
    val chain: @Composable () -> Unit = {
        if (lines.none { it.isNotBlank() }) {
            Text(
                text = "（本段无思考文本）",
                style = thinkingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Top-anchored lines keep an already-read wrapped line still while its tail grows.
            val viewport = remember(expansion, blockId) { expansion.viewport(blockId) }
            val inner = viewport.listState
            val lineCount by rememberUpdatedState(lines.size)
            // A completed user scroll away from the bottom stops following; reaching
            // the bottom resumes it. Content growth alone must not disable following.
            var innerWasScrolling by remember { mutableStateOf(false) }
            LaunchedEffect(inner) {
                inner.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) viewport.follow = false
                }
            }
            LaunchedEffect(inner) {
                snapshotFlow { Triple(inner.isScrollInProgress, inner.canScrollForward, inner.layoutInfo.totalItemsCount > 0) }
                    .collect { (scrolling, canForward, measured) ->
                        if (!measured) return@collect
                        if (!canForward && !scrolling) {
                            viewport.follow = true
                        } else if (innerWasScrolling && !scrolling && canForward) {
                            // 一次滚动结束在非底部 ⇒ 用户拖上去的（程序化贴底只会停在底部）。
                            viewport.follow = false
                        }
                        innerWasScrolling = scrolling
                    }
            }
            // Follow only while opted in. The ordinary top anchor itself keeps a
            // reader still when a wrapped line or later lines grow below them.
            LaunchedEffect(inner, expanded, followReasoning) {
                if (!expanded || !followReasoning) return@LaunchedEffect
                snapshotFlow {
                    inner.canScrollForward to viewport.follow
                }.collect { (offBottom, follow) ->
                    if (follow && offBottom && !inner.isScrollInProgress) inner.scrollToItem(lineCount)
                }
            }
            LazyColumn(
                state = inner,
                reverseLayout = false,
                modifier = Modifier
                    .fillMaxWidth()
                    // Configurable in 外观: a chain of thought is context, and the
                    // answer has to stay on screen while it is being read.
                    .heightIn(max = maxHeight),
                // 首行上边距放在**列表内部**（contentPadding），不是容器上：容器内边距在滚动区
                // 外面，滑到哪它都留在那儿 —— 用户说的「列表滑下去那个空白条还在」就是它。
                // contentPadding 属于可滚动内容，第一行滚上去时这段空白一起滚走。
                contentPadding = PaddingValues(top = BLOCK_CONTENT_TOP),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    count = lines.size,
                    // Appending a logical line never changes an existing line's identity.
                    key = { index -> index },
                    contentType = { "reasoning-line" },
                ) { index ->
                    val line = lines[index]
                    if (line.isBlank()) {
                        // **最新行是空行时，光标就画在这里**：它才是「已显示文本的末尾」
                        // （刚换行、下一个字还没被打字机放出来）。原来是 `Spacer`，光标于是
                        // 跳回上一行末尾 —— 见 cursorAt 上面的说明。
                        if (streaming && index == cursorAt) {
                            // 与有字那行同一个光标实现（内联绘制），空串也会画出竖条。
                            MarkdownText(
                                text = "",
                                style = thinkingStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                cursor = true,
                            )
                        } else if (index > 0) {
                            // A leading blank line takes no extra space below the header.
                            Spacer(Modifier.height(4.dp))
                        }
                    } else {
                        // 思考链每一行都可选中复制（用户：「确实所有情况都要可复制」）——
                        // 由长按武装制满足：不按时零选区成本，长按那一刻容器就位。
                        SelectableWhenArmed {
                        MarkdownText(
                            text = line,
                            // 13sp with roomier leading than bodySmall's: a long chain of
                            // thought is read, not skimmed.
                            style = thinkingStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 不再额外加上边距：thinkingStyle 是 13sp 字号配 20sp 行高
                            // （ChatBlocks.thinkingStyle），首行上方已经有约 3.5dp 的半行距，
                            // 再叠 padding 就成了用户看到的那点「空白」。
                            modifier = Modifier,
                            // **光标直接拼在这一行的末字符后面**（用户：「根本没有跟在思考链
                            // 的后面」）：以前是一个独立的 `BlinkingCursor()` 跟在 item 后面，
                            // 它自成一行、位置和文字对不上。现在作为 AnnotatedString 的尾段，
                            // 换行和对齐都跟着正文走。
                            // The current final logical line owns the streaming cursor.
                            cursor = streaming && index == cursorAt,
                        )
                        }
                    }
                    // 光标已内联进上一段 MarkdownText（见 cursor 参数），这里不再另起一行。
                }
                // A zero-height terminal item provides exact bottom following even when
                // the final logical line wraps taller than this capped viewport.
                item(key = "reasoning-end", contentType = "reasoning-end") { Spacer(Modifier.height(0.dp)) }
            }
        }
    }
    val toggle = {
        // Written to the table, not to a local: the choice has to survive the hand-over
        // from the live block to the stored one.
        // 贴底这件事不在这里做：底锚下卡片长高天然向上、底边不动，这里再滚一次就会滚两倍。
        expansion.set(blockId, !expanded)
    }
    val status: @Composable () -> Unit = {
        // The gap in front of the first item belongs to the caller: [ProcessCard] already puts
        // 8dp between the name and this trailing slot, so a second one here doubled it to 16dp —
        // the band of nothing the user pointed at after 「思考过程」. The nested row below has no
        // ProcessCard and adds its own.
        // Item by item with a real gap: joining them into one " · " string is what made
        // "256 tokens · 25.1s" read as a single glued-together word at 13sp.
        meta.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.width(7.dp))
            ProcessStatus(item)
        }
        if (streaming) {
            // 用户口径三次叠加：先「运行中的 icon 左右间距不一致」⇒ 放进方槽居中；
            // 再「左右边距小一点」⇒ 7dp → 4dp；再「再小一点」⇒ 4dp → 2dp。
            // **这一处故意不跟 meta 项的 7dp 走**：spinner 是图标不是文字，和「256 tokens」读成一组太松。
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier.size(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProcessSpinner(MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(2.dp))
        }
    }
    if (nested) {
        // **平铺的折叠块**（用户：「思考块就是折叠块的啊，展开还是虚拟列表啊」+「要的就是平铺」）：
        // 外层不再套卡片底（见 ProcessCard），这一块自己是一条折叠条 —— 表头（图标 + 思考过程 +
        // 段信息 + 箭头）+ 展开后仍是虚拟列表（chain 里的 LazyColumn，逐行虚拟化、高度有上限）。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 折叠块统一（与 ProcessCard 表头、ToolRow 同一套）：固定 36dp 行高 + 12dp
                    // 水平内边距 + labelMedium 标题。折叠态每行都是 maxLines=1，固定高度不会裁字。
                    .height(36.dp)
                    .padding(horizontal = 0.dp)
                    // 整块按下反馈（见 blockPress）：平铺后这一行就是折叠条本体，涟漪是小圆角矩形。
                    .blockPress(MaterialTheme.shapes.small, onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_brain),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
                status()
                if (!expanded && firstLine.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = firstLine,
                        style = metaStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot, exactly as in [ProcessCard]: the preview
                        // eats the leftover so the chevron sits on the right edge.
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        ImageVector.vectorResource(R.drawable.ic_chevron_up)
                    } else {
                        ImageVector.vectorResource(R.drawable.ic_chevron_down)
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // 和第一层（ProcessCard）走同一条路径（用户：「第一层折叠能自适应，多个工具展开
            // 就全都往上展」）：`AnimatedVisibility(expandVertically)` 只改高度、不碰列表，
            // 底锚下长出来的那一截只能往上顶；RevealHeight 每帧把增长量反向补进列表，块顶边
            // 才不动。淡入淡出随之取消 —— 第一层本来就没有，两层的观感才一致。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                // 虚拟列表照旧（用户：「展开还是虚拟列表啊」）：chain 内部就是 LazyColumn，
                // 逐行虚拟化 + heightIn(max = 外观里的思考块高度) 上限。
                // top 归 0：首行边距已经放进 chain 内部列表的 contentPadding（会随滚动消失），
                // 这里再留一层就是固定不动的空白条。
                Column(Modifier.padding(start = 44.dp, top = 0.dp)) { chain() }
            }
        }
    } else {
        ProcessCard(
            icon = ImageVector.vectorResource(R.drawable.ic_brain),
            title = "思考过程",
            expanded = expanded,
            onToggle = toggle,
            preview = firstLine.takeIf { it.isNotEmpty() },
            // **折叠条不再挂光标**（用户截图里同一屏出现两个光标）：展开体里已经有那个
            // 跟在末字符后面的光标了，折叠条这个纯属重复。
            previewTail = null,
            trailing = status,
        ) {
            // 思考块**不加**首行间距：正文自带行高，再加一条就是用户看到的那道空白
            // （用户：「思考块分割线下面那个空白」）。工具块仍用 BLOCK_CONTENT_TOP ——
            // 那边是等宽命令/输出，字符本身贴得紧，需要一点 padding。
            chain()
        }
    }
}

/**
 * 折叠块展开后，**内容第一行**与折叠条分割线之间的间距 —— 所有块共用这一个值。
 *
 * 为什么必须是一个常量：思考块（13sp/20sp 行高）、工具块的命令（12sp 等宽）、「输出」行
 * 原本各自用自己的内边距或行高凑间距，于是三个块展开后首行上方的空隙互不相同
 * （用户：「所有块基本的东西都要保持一致」）。现在统一取它。
 */
internal val BLOCK_CONTENT_TOP = 6.dp

/** 13sp / 20sp leading, scaled off bodySmall so 字号 still applies to both. */
// 两个补偿是纯配置对象，提为常量（原来每次调用新建 3 个对象）。
private val NoFontPadStyle = PlatformTextStyle(includeFontPadding = false)
private val FirstLineTopTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.FirstLineTop,
)

@Composable
internal fun thinkingStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) {
        body.copy(
        lineHeight = body.fontSize * (20f / 13f),
        // 两个补偿缺一不可：
        //  · includeFontPadding = false 去掉**字体自带**的上下 padding；
        //  · Trim.FirstLineTop 去掉**首行**分摊到的那半截行距（20sp 行高配 13sp 字号，
        //    首行上方原本还有约 3.5dp）。
        // 少了第二条，思考块的首行空隙就是 BLOCK_CONTENT_TOP + 3.5dp，而工具块只有
        // BLOCK_CONTENT_TOP —— 仍然是「不一致」，只是数值换了。
        platformStyle = NoFontPadStyle,
            lineHeightStyle = FirstLineTopTrim,
        )
    }
}

/**
 * Every tool call of one turn behind one [ProcessCard]: the header is the summary line,
 * so ten calls cost one row until the user asks for more.
 *
 * Two levels on purpose. The header opens the list of calls; a single call opens its own
 * arguments and output. Arguments are the noisy part, so they stay behind the second tap
 * even once the list is open.
 */
@Composable
internal fun ToolBundle(
    tools: List<ToolLine>,
    expansion: ReasoningExpansion,
    blockId: String,
    runningTool: String? = null,
    startExpanded: Boolean = false,
) {
    val expanded = expansion.stored(blockId) ?: startExpanded
    // **每个工具独立展开**（issue #20）：原来是单个 `String?`，展开 B 会覆盖 A ——
    // 想同时对照「第 1 个的输入」和「第 3 个的输出」就得来回点。用 Set 记住所有打开的，
    // 展开新的不影响已开的。
    // 代价（issue #20 自己提的）：多个 `RevealHeight` 会同时跑动画，位置补偿的负载上升，
    // 与 #13 是同一处风险 —— 若实测位置跳动更明显，再考虑限制「最多同时开 N 个」。
    // **摘要只依赖 tools 本身**：count/标题/预览 remember 住，折叠条常驻，
    // 原来每帧都重算一遍（审计 2§A14）。
    // R4：这个 count 也是同一类 —— 它只随 tools 变，而这一行在每次重组都会被求值一遍。
    val failures = remember(tools) { tools.count { it.done && !it.ok } }
    val (title, preview) = remember(tools) {
        // One call is its own name; several are a count, because the names are what the
        // expanded list is for.
        val t = if (tools.size == 1) {
            tools.first().name.ifBlank { "调用 1 个工具" }
        } else {
            "调用 ${tools.size} 个工具"
        }
        // Folded, the header still says what the call is doing: the first argument line of
        // the newest call (a shell command, a path), which is the row's own content anyway.
        val p = tools.lastOrNull()
            ?.args
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "{}" }
        t to p
    }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_wrench),
        title = title,
        expanded = expanded,
        onToggle = { expansion.set(blockId, !expanded) },
        preview = preview,
        trailing = {
            when {
                // The name of the tool being waited on is the only useful thing the header
                // can say while it is waiting.
                runningTool != null -> {
                    ProcessStatus(runningTool, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    ProcessSpinner(MaterialTheme.colorScheme.primary)
                }

                failures > 0 -> ProcessStatus(
                    text = "$failures 个失败",
                    tone = MaterialTheme.colorScheme.error,
                )

                else -> ProcessStatus("完成", MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        // One call: the header already IS that call's row, so expanding shows its
        // detail straight away — an inner row repeating the header's own name is
        // what drew one call as two.
        if (tools.size == 1) {
            ToolDetail(
                line = tools.first(),
                listState = remember(expansion, blockId) { expansion.viewport("$blockId:call:0:output").listState },
            )
        } else {
            // **这里不用虚拟列表，也不用高度上限**（用户：3 个工具条里不该有虚拟列表，
            // 具体工具里面设虚拟列表即可，和单个工具一样）。
            // 原来是 `LazyColumn(heightIn(max = 320.dp))`：代价有两个 ——
            //  ① 工具条被 320dp 截断，第 2、3 个工具看不全，只能在条内滑动；
            //  ② 条内一滑，容器里的内容和主列表的滚动就脱节，接着折叠会让位置跳一下。
            // 改成普通 Column 后：工具条高度 == 内容高度（不被截断、不能滑动），
            // 里面的块长高多少，容器就长高多少 ⇒ 补偿和第一层用同一套算法。
            // 具体的工具内容仍然是虚拟列表（ToolDetail 自己的 180dp 上限，没动）。
            //
            // 不再加 8dp 行距：多工具里每个工具点开后的样子必须和「单个工具」一致，
            // 多一层间距就是多一层它没有的边距。
            Column(Modifier.fillMaxWidth()) {
                tools.forEachIndexed { index, tool ->
                    // 每个工具**各自**点开（用户：「不是点击多个工具马上平铺各个工具」），
                    // 但彼此**不互斥**：展开第二个不会收起第一个（issue #20）。
                    // 展开后的内容与单个工具同一套（ToolRow 内部不再加缩进）。
                    val key = "$blockId:call:$index"
                    key(key) {
                        ToolRow(
                            line = tool,
                            listState = remember(expansion, key) { expansion.viewport("$key:output").listState },
                            // 只有这一轮还在跑才转圈；历史 done=false 是中断遗留。
                            running = runningTool != null,
                            expanded = expansion.stored(key) ?: false,
                            onToggle = {
                                expansion.set(key, !(expansion.stored(key) ?: false))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a call actually did: its arguments, then its output behind a copy button, then
 * any screenshot it took. Shared by the two-level row and the single-call bundle, so
 * a live call and a settled one show the same thing.
 */
@Composable
internal fun ToolDetail(line: ToolLine, maxHeight: Dp = 180.dp, listState: LazyListState? = null) {
    val scheme = MaterialTheme.colorScheme
    val args = line.args.takeIf { it.isNotBlank() && it != "{}" }
    val output = line.message?.toolOutput?.takeIf { it.isNotBlank() }
    // 行表在这里算（`LazyColumn` 的 content lambda **不是** `@Composable`，里面不能用 remember）。
    // 不 remember 的话，工具块在流式期间每个发布节拍重组都会重跑一次 lines()。
    val rows = remember(output) { output?.lines().orEmpty() }
    // 工具输出的「复制」：写剪贴板 + Toast（用户：「复制后给个提示」）。
    val copyOutput = rememberCopyToClipboard()
    // 与思考块同一套形态：**虚拟列表 + 高度上限**，内容比上限短时按真实高度渲染。
    // 两处刻意的差别（用户要求）：
    //  · 命令**不截断**（原来 maxLines = 2 + Ellipsis，长命令根本读不全）；
    //  · 输出不再用「固定 132dp 的滚动 Text」，而是逐行进入虚拟列表，长输出不会一次性排版。
    // 命令与输出都可选中复制（用户：「确实所有情况都要可复制」）——同上，武装制。
    SelectableWhenArmed {
    // 12sp 等宽正文样式：`MaterialTheme.typography` 是 @Composable 读取，**不能**写在
    // remember 的 lambda 里（编译器报 “@Composable invocations can only happen from …”）。
    // 先取出 typography，再 remember 派生值；下面逐行复用它。
    val bodySmall = MaterialTheme.typography.bodySmall
    val monoStyle = remember(bodySmall) { bodySmall.copy(fontSize = 12.sp) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), state = listState ?: rememberLazyListState()) {
        if (args != null) {
            item(key = "args") {
                Text(
                    text = args,
                    style = monoStyle,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    // 上 6dp：卡片内容区顶部现在是 0，命令直接贴着折叠条分割线太挤
                    // （用户：「输入那个指令就相当于贴边了」）。只给 args 自己留，不给整块内容区留 ——
                    // 这样「只有输出」的卡片上方仍然是干净的 2dp。
                    modifier = Modifier.fillMaxWidth().padding(top = BLOCK_CONTENT_TOP),
                )
            }
        }
        if (output != null) {
            if (args != null) {
                // 输入（命令/参数）与输出之间要有一条分割线：两段都是等宽小字，没有这条线
                // 读起来是连续的一大段，分不清哪是让它做的、哪是它返回的。
                item(key = "divider") {
                    // 只留上方 6dp：下方再留就成了「分割线与「输出」之间 6+8=14dp」的空白
                    // （用户看出来的「这一行离分割线的间距很大」）。
                    HorizontalDivider(
                        color = scheme.outlineVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "output-head") {
                // 垂直居中对齐：28dp 的复制按钮和「输出」两字的视觉中线必须重合，用 Top 对齐时
                // 图标会显得比文字高一截（用户看出来的）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 卡片内容区上方已经是 0dp，所以这里固定 2dp：有输入时距分割线 2dp、
                    // 只有输出时距卡片分割线也是 2dp —— 两种卡片看起来一样。
                    modifier = Modifier.padding(top = BLOCK_CONTENT_TOP),
                ) {
                    if (!line.ok) {
                        // A failed call's output is the evidence; the bar marks it as such.
                        Box(
                            Modifier
                                .width(3.dp)
                                .heightIn(min = 16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.error),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    // 左边标出「输出」：复制按钮在右边，左侧空着时那一行看不出是什么区段
                    // （用户：「显得左侧很空，可以在左侧写上输出二字」）。
                    Text(
                        text = "输出",
                        style = metaStyle(),
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { copyOutput(output) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                            contentDescription = "复制输出",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            // 逐行进虚拟列表：行数再多也只排版可见的那些。
            items(rows.size, key = { index -> "out-$index" }, contentType = { "tool-output" }) { index ->
                Text(
                    text = rows[index],
                    style = monoStyle,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (line.ok) 0.dp else 11.dp),
                )
            }
        }
    }
    }
    line.message?.allImages?.forEach { ImageBlock(it) }
}

@Composable
internal fun ToolRow(
    line: ToolLine,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 这一轮是否还在跑：历史里被中断落盘的 `done=false` 行不是在跑，不能永久转圈。 */
    running: Boolean = false,
    listState: LazyListState? = null,
) {
    val tone = when {
        !line.done -> MaterialTheme.colorScheme.primary
        line.ok -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .fillMaxWidth()
            // 整块按下反馈（见 blockPress）：这一列**展开后包含整段参数与输出**，是有多高就多高
            // 的节点 —— 涟漪的半径按「到最远角的距离」算，于是画出来正是一个大圆，也就是用户
            // 截图里那个「为什么是个圆形」。
            .blockPress(MaterialTheme.shapes.small, onClick = onToggle)
            // 折叠块统一：水平 12dp 内边距与另外两处表头一致；行高加在**表头那一行**上（这一列
            // 展开后还有内容，给 Column 定高会把详情裁掉）。
            .padding(horizontal = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same 24dp slot the thinking rows use, so both icon columns share one
            // left edge inside the expanded card.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (line.done) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_wrench),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(16.dp),
                    )
                } else if (running) {
                    // A call in flight is a spinner, not a dot: the row has to say "still
                    // working" while the model waits on it.
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = tone,
                    )
                } else {
                    // 历史遗留的 done=false（run 被杀后落盘）：静态扳手，不再转。
                    // 不定式进度圈按屏幕刷新率重绘，常驻卡片挂着它 = 读历史一直烧帧。
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_wrench),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = line.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The name is short and fixed — it takes what it needs and yields the rest to
                // the argument preview. Holding a half-row share cut it in the middle instead:
                // `run_shell` came back as 「run_sh…」, which is the fragment the user read as
                // 「sh」 sitting in the header.
                modifier = Modifier.widthIn(max = PROCESS_TITLE_MAX),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    !line.done -> "运行中…"
                    line.elapsedMs > 0 -> (if (line.ok) "✓ " else "✗ ") + formatDuration(line.elapsedMs)
                    line.ok -> "✓"
                    else -> "✗"
                },
                style = metaStyle(),
                color = tone,
                maxLines = 1,
                softWrap = false,
            )
            // What the call is doing, on the row itself: a folded tool row that only
            // says 「完成」 is exactly what the user called 「执行什么内容也没有」.
            // R4：`line.args` 是这一行唯一的相关输入；逐帧 lineSequence + firstOrNull 只为
            // 了拿它的第一行，remember 住（外层 ToolDetail 也是一个常驻分支）。
            val firstArg = remember(line.args) {
                line.args.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "{}" }
            }
            if (firstArg != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = firstArg,
                    style = metaStyle(),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Icon(
                imageVector = if (expanded) ImageVector.vectorResource(R.drawable.ic_chevron_up) else ImageVector.vectorResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        // 同 ReasoningBlock：和第一层一样走 RevealHeight，展开时块顶边不动、内容往下长。
        RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
            // 与「单个工具」同一套边距：这里**不加** 44dp 缩进 —— 那是多工具独有的、用户
            // 明确指出的多余边距（「要像单个工具一样没有边距」）。
            Column(Modifier.fillMaxWidth()) {
                ToolDetail(line = line, listState = listState)
            }
        }
    }
}
/**
 * The turn's own summary: how much it thought, how many tools it ran, and what it cost,
 * folded into the same card family as the blocks above it.
 *
 * It replaced a bare line of small print under the reply, which read as a footnote rather
 * than as the answer's own record — and it is where the per-segment numbers live, so the
 * thinking and tool blocks above stay one line each.
 */
@Composable
internal fun TurnSummary(
    parts: List<Part>,
    usage: Usage?,
    live: StreamStats?,
    contextWindow: Int,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 历史思考块默认展开; passed on to each think in this card. */
    expandReasoning: Boolean = true,
    /** 外观 → 工具块默认展开; 与运行时同一套（[ToolBundle.startExpanded]）。 */
    expandTools: Boolean = false,
    /**
     * The run's own note — the compression line, when there was one. It belongs in this
     * header rather than only in the status line: the status line is gone the moment the run
     * ends, and "the context was folded here" is a fact about this turn. It rides inside the
     * card, so it folds away with the rest of it.
     */
    note: String = "",
    /** The message these blocks belong to; part of every block's key in the table. */
    messageId: String = "",
    reasoningExpansion: ReasoningExpansion,
    runId: String = messageId,
    streaming: Boolean = false,
    runningTool: String? = null,
    preserveExpanded: Boolean = false,
    onEdit: (StoredMessage) -> Unit = {},
) {
    // Metadata only: establish protection before the child viewport lookups compose.
    // Keep it through completion; the next live run releases it into bounded history.
    if (streaming) reasoningExpansion.protectRun(runId)
    val expanded = reasoningExpansion.summaryExpanded(parts, runId, streaming)
    SideEffect {
        if (streaming && preserveExpanded) {
            reasoningExpansion.retainForReading(parts, runId, expandTools)
        } else if (!streaming && reasoningExpansion.stored(summarySlot(runId)) == null) {
            // Latch the hand-over once. Closing an inner block later must not close
            // the whole process card around the reader as a side effect.
            reasoningExpansion.set(summarySlot(runId), expanded)
        }
    }
    // **remember**（性能）：这两行在**参数变化时**才需要重算；记住后同一实例能让
    // `AbstractList.equals` 首行 `o === this` 短路，省掉逐条 Part 深比较。
    val thoughts = remember(parts) { parts.filterIsInstance<Part.Thinking>() }
    val calls = remember(parts) { parts.filterIsInstance<Part.Tools>().flatMap { it.calls } }
    val settled = usage?.takeIf { it.known }
    // The prompt size of the newest round is what is actually sitting in the window now.
    // While a run streams, the engine's copy of the endpoint's last report is fresher than
    // anything already stored, so it wins.
    val context = live?.lastPromptTokens?.takeIf { it > 0 } ?: usage?.prompt ?: 0
    // 运行中的用时**自己走**（与状态行同一口径：从这一轮开始算起，每秒跳一次），
    // 结算后用端点上报的耗时。原来只取 `live.elapsedMs`，运行中拿到的是 0 ⇒ 标题不显示用时。
    var elapsedTick by remember { mutableIntStateOf(0) }
    val runStartedAt = remember { mutableLongStateOf(0L) }
    LaunchedEffect(streaming) {
        if (streaming) {
            if (runStartedAt.longValue == 0L) runStartedAt.longValue = android.os.SystemClock.elapsedRealtime()
            while (true) {
                kotlinx.coroutines.delay(1_000)
                elapsedTick++
            }
        } else {
            runStartedAt.longValue = 0L
        }
    }
    val runningElapsed = if (streaming && runStartedAt.longValue > 0L) {
        elapsedTick.let { android.os.SystemClock.elapsedRealtime() - runStartedAt.longValue }
    } else {
        0L
    }
    val elapsedMs = if (runningElapsed > 0L) runningElapsed else (live?.elapsedMs ?: settled?.elapsedMs ?: 0L)
    val headline = buildList {
        if (elapsedMs > 0) add("用时 ${formatDuration(elapsedMs)}")
        if (thoughts.isNotEmpty()) add("思考 ${thoughts.size} 段")
        if (calls.isNotEmpty()) add("工具 ${calls.size} 个")
    }
    // While a run streams the header shows the numbers that move; a settled reply shows
    // the ones the endpoint reported.
    val tail = emptyList<String>()
    // The numbers ride inside the title rather than in their own slot, so they shorten
    // with it and the chip after them is never the thing that gets squeezed.
    // 标题串只在输入变化时重算（每次重组 3 个 buildList + joinToString 是纯浪费）。
    val header = remember(headline, tail, note, streaming) {
        buildList {
        add(if (headline.isEmpty()) if (streaming) "正在回复" else "本轮用量" else "本轮：${headline.joinToString(" · ")}")
        addAll(tail)
        // After the numbers, before nothing else: the compression line is a fact about this
        // turn, so it rides in the same title and folds away with the card.
        if (note.isNotBlank()) add(note)
        }.joinToString(" · ")
    }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_chart_column),
        title = header,
        expandable = parts.isNotEmpty() && processTailStart(parts) > 0,
        expanded = expanded,
        contentBottomPadding = 0.dp,
        onToggle = { reasoningExpansion.set(summarySlot(runId), !expanded) },
        trailing = {
            if (live != null) {
                Spacer(Modifier.width(8.dp))
                ProcessSpinner(MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        // Expanding means the run as it happened: every piece in arrival order —
        // thinking blocks (foldable, full text), tool rows, every stretch of prose
        // including the mid-run transitions, steer bubbles — the same sequence the
        // live bubble draws. Usage details stay below the final prose.
        val tailStart = remember(parts) { processTailStart(parts) }
        // The prefix and flat tail form one visual sequence. Whether a new prose
        // part moves the former tail here must not add padding above that content.
        Column(
            modifier = Modifier.padding(top = 6.dp, bottom = if (tailStart > 0) 6.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The answer itself is already drawn by the bubble below this card (the settled
            // path keeps the last stretch of prose). Leaving a second copy in here is what
            // made one reply read as a stack of the same text; the mid-run transitions — the
            // model's "let me check" before a tool — stay, because the bubble does not show
            // those and the card is their only record.
            // R5：`indexOfLast` 只随 parts 变（列表元素是数据类，新块诞生/追加即 key 变），
            // 折叠卡展开时却每帧都在整条 parts 上重扫一遍。
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), never inside it: a non-local return out of a key
                // group leaves the group unclosed and Compose aborts with "Start/end
                // imbalance" — the crash this file has already paid for once.
                if (index >= tailStart) return@forEachIndexed
                key(index) {
                    when (part) {
                        is Part.Thinking -> ReasoningBlock(
                            text = part.text,
                            streaming = streaming && index == parts.lastIndex,
                            runStreaming = streaming,
                            // One message can hold several thinks (think → tool → think);
                            // the slot is the run plus the think's place inside it.
                            blockId = thinkSlot(parts, index, runId),
                            expansion = reasoningExpansion,
                            reasoningTokens = part.tokens,
                            elapsedMs = part.elapsedMs,
                            // A think from an earlier launch is a settled one, so it opens only if
                            // 历史思考块默认展开 is on; a tap on its header decides it per
                            // conversation either way (the expansion table is keyed by chat).
                            expandReasoning = expandReasoning,
                            // *原样展开**（用户：「折叠条只把最后那段最终文本之外的收起，展开时原样
                            nested = false,
                            maxHeight = reasoningMaxHeight,
                            followReasoning = followReasoning,
                        )

                        is Part.Tools -> {
                            // **与运行时同一套渲染**（用户：「展开时原样展开，不对 UI 做任何更改」）：
                            // 这里原来每个调用单独一行，运行时是合并成「调用 N 个工具」的 ToolBundle。
                            ToolBundle(
                                tools = part.calls,
                                expansion = reasoningExpansion,
                                blockId = processFoldSlot(parts, index, runId)!!,
                                runningTool = runningTool.takeIf { streaming && index == parts.lastIndex },
                                startExpanded = expandTools,
                            )
                        }

                        // Prose keeps the card's own left edge: it is read as the answer's
                        // body, not as a row of the grid, and 2dp of vertical air plus the
                        // column's 8dp keeps it off its neighbours.
                        // This prose already appeared in the flat tail. Reparenting
                        // it here must never replay its typewriter from an empty string.
                        is Part.Text -> AssistantProse(part, streaming, typewriter = false)

                        // Earlier steering remains at its actual position in the process.
                        is Part.Steer -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            UserBubble(message = part.message, onEdit = onEdit)
                        }

                        // 重试留痕：与思考块/工具块同一套栅格（见 TraceBlock），留白不再自己加。
                        is Part.Trace -> TraceBlock(part.note)

                        // 压缩块：位置就是它发生的地方（思考 → 工具 → 压缩 → 继续）。
                        is Part.Compress -> CompressionBlock(part.checkpoint, reasoningExpansion,
                            processFoldSlot(parts, index, runId)!!)
                    }
                }
            }
        }
    }
}


/** One line inside [TurnSummary] is gone with the segment rows; the expanded card now
 * draws full thinking blocks and two-level tool rows instead. */


package com.suixing.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.indication
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp as fLerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.suixing.agent.R
import kotlin.math.roundToInt
/**
 * 全站唯一的「列表行」：左边标题（可带前置图标、可带副标题），右边控件。
 * 设置页、关于页、文件页的行都从这里出——曾经每页各写一遍，行高、内边距、
 * 副标题字号各不相同。
 *
 * 标准（改这里就是改全站）：
 *  - 纵向 **12dp**：两行行盒 43dp + 24dp = 67dp；单行 25dp + 24dp = 49dp，仍在 48dp 触控下限之上。
 *  - 标题 `bodyLarge`、副标题 `bodySmall`、控件前 12dp。
 *  - **横向留白默认不加**（[contentPadding] 默认 0）：卡内的行由 SuixingCard 的 16dp 给，
 *    页面级的行由调用方用 [modifier] 传 16dp。默认值给 16 会让卡里的行变成 32dp 缩进。
 *    唯一的例外是 [SettingsGroup]：那里的行要**铺满卡片**（涟漪才碰得到卡片边缘），所以它
 *    自己把 16dp 从容器挪进 [contentPadding]、并把 [shape] 换成与卡片同形。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    /** 行自己的可见形状：涟漪的圆角跟着它走。卡内的行由调用方传卡片形状。 */
    shape: Shape = MaterialTheme.shapes.small,
    /** 内容内边距，落在**点击区之内** ⇒ 涟漪照样铺满整行（横向），内容仍缩进。 */
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    // 行自带圆角点击区：水波纹跟着圆角走，而不是溢成一个方角。
                    // 走共享修饰符：clip → clickable → 上面的 padding，涟漪就等于整行（含 12dp 行高
                    // 内边距），不再只盖住文字那一块。[contentPadding] 在它**之后**，所以横向留白
                    // 也算在点击区里 —— 这正是「涟漪铺满卡片」的关键。
                    Modifier.suixingClickable(shape, onClick = onClick)
                },
            )
            .padding(contentPadding)
            .padding(vertical = SettingsStyle.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(SettingsStyle.ControlGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = SettingsStyle.rowTitle,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (control != null) {
            Spacer(Modifier.width(SettingsStyle.ControlGap))
            control()
        }
    }
}

/** 行的「后面还有一页」标记：一个 18dp 的 chevron、一种颜色，全站只有这一份。 */
@Composable
fun RowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}


/** Confirm the tap before starting Material's ripple; a drag never starts one. */
private data class TapRipple(private val delegate: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TapRippleNode(interactionSource, delegate)
}

private class TapRippleNode(
    private val source: InteractionSource,
    factory: IndicationNodeFactory,
) : DelegatingNode() {
    private val taps = MutableInteractionSource()
    private val presses = PressFeedback<PressInteraction.Press>()

    init { delegate(factory.create(taps)) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> presses.press(event)
                    is PressInteraction.Release -> if (presses.release(event.press)) {
                        // Material owns expansion and fade-out even for Press + Release
                        // in the same frame. Never clear its drawing on pointer-up.
                        taps.emit(event.press)
                        taps.emit(event)
                    }
                    is PressInteraction.Cancel -> presses.cancel(event.press)
                    else -> taps.emit(event)
                }
            }
        }
    }

    override fun onReset() { presses.clear() }
    override fun onDetach() { presses.clear() }
}
/** Logical segment position under a pointer; centered on each equal-width option. */
internal fun segmentPositionAt(x: Float, width: Float, count: Int, rtl: Boolean): Float? {
    if (count <= 0 || width <= 0f || !width.isFinite() || !x.isFinite()) return null
    val physical = (x / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    return if (rtl) count - 1f - physical else physical
}

/** Library selection/ripple primitives, with group-level drag-to-select. */
@Composable
fun SegmentedPill(
    options: List<String>,
    /** −1 表示「一项都没选中」。权限档次始终传入当前显示档的下标。 */
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    /** 这一格是否可用；不可用的只置灰，仍可点（能力页的「未开启档位」就是这种）。 */
    optionEnabled: (Int) -> Boolean = { true },
    /** 点了已经选中的那一格。不传则保持当前选择。 */
    onReselect: (() -> Unit)? = null,
    /**
     * 槽的深浅（0..1，按 onSurface 与页面底色混合）。
     * 默认 0.08 是全站口径；**能力页「执行档次」单独调深一点**——那块四格常带置灰，
     * 0.08 下边界看不清（用户要求只在这一页加深，不要带着全站一起变）。
     */
    trackTint: Float = 0.08f,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectNow by rememberUpdatedState(onSelect)
    val rippleIndication = remember(colors.onSurface) { TapRipple(ripple(color = colors.onSurface)) }
    var dragPosition by remember(options, selectedIndex, rtl) { mutableStateOf<Float?>(null) }
    // 每一格自己的交互源与「当前按下」事件：拖动开始时要把按下取消掉，
    // 否则只有横向拖动、手指没离开的这一格，照样会画一圈点击涟漪（用户报的现象）。
    val interactions = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val presses = remember(options.size) { arrayOfNulls<androidx.compose.foundation.interaction.PressInteraction.Press>(options.size) }
    val position = animateFloatAsState(
        targetValue = dragPosition ?: selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)).toFloat(),
        // 与开关同一条弹簧参数：两个控件的"选中/切换"手感统一（用户要求样式动效统一），
        // 拖动中仍然 snap（跟手，不插值）。
        animationSpec = if (dragPosition != null) snap()
        else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "segment selection",
    )
    val visibleIndex = dragPosition?.roundToInt() ?: selectedIndex
    Box(
        modifier.fillMaxWidth().selectableGroup()
            .pointerInput(options, selectedIndex, rtl) {
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            // 拖动先取消所有格的按住态：涟漪随 Cancel 立即消失，
                            // 之后由拖动高亮接替反馈。
                            presses.forEachIndexed { index, press ->
                                if (press != null) {
                                    interactions[index].tryEmit(
                                        androidx.compose.foundation.interaction.PressInteraction.Cancel(press),
                                    )
                                    presses[index] = null
                                }
                            }
                            dragPosition = segmentPositionAt(offset.x, size.width.toFloat(), options.size, rtl)
                        },
                        onDragCancel = { dragPosition = null },
                        onDragEnd = {
                            val index = dragPosition?.roundToInt()
                            dragPosition = null
                            if (index != null && index != selectedIndex) selectNow(index)
                        },
                    ) { change, _ ->
                        change.consume()
                        dragPosition = segmentPositionAt(change.position.x, size.width.toFloat(), options.size, rtl)
                    }
                } finally {
                    dragPosition = null
                }
            },
    ) {
        // A 40dp visible track and 48dp touch targets; labels can grow with font size.
        Box(
            Modifier.matchParentSize().padding(vertical = 4.dp)
                // 0.06 → 0.08（用户先要更明显，再看整体觉得 0.12 偏深，定为 0.08）：
                // 只把槽提亮一档，不改变选中块的对比；边线另外收到 0.5dp。
                .clip(shape).background(lerp(colors.surface, colors.onSurface, trackTint))
                .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f), shape),
        ) {
            if (options.isNotEmpty() && (dragPosition != null || selectedIndex >= 0)) Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(1f / options.size)
                    .graphicsLayer {
                        translationX = position.value * size.width * if (rtl) -1f else 1f
                    }
                    .padding(3.dp)
                    .shadow(1.dp, shape)
                    .background(colors.surface, shape)
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), shape),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { index, label ->
                val interaction = interactions[index]
                androidx.compose.runtime.LaunchedEffect(interaction) {
                    interaction.interactions.collect { event ->
                        when (event) {
                            is androidx.compose.foundation.interaction.PressInteraction.Press ->
                                presses[index] = event
                            is androidx.compose.foundation.interaction.PressInteraction.Release,
                            is androidx.compose.foundation.interaction.PressInteraction.Cancel,
                            -> presses[index] = null
                            else -> Unit
                        }
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                        // Foundation expands this 40dp visual node's touch target to
                        // 48dp. Press coordinates and the ripple now share one origin.
                        .padding(vertical = 4.dp).clip(shape)
                        .selectable(
                            selected = index == selectedIndex,
                            interactionSource = interaction,
                            // Only a confirmed click starts Material's complete ripple.
                            indication = rippleIndication,
                            role = role,
                            onClick = {
                                if (index != selectedIndex) onSelect(index) else onReselect?.invoke()
                            },
                        ),
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = SettingsStyle.controlLabel,
                            color = when {
                                index == visibleIndex -> colors.onSurface
                                !optionEnabled(index) -> colors.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> colors.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 全站唯一的开关：自绘，进度与手指同步。
 *
 * 为什么不用 M3 `Switch`：它的圆钮由内部弹簧动画驱动，拖动时只要 `checked` 变化就会被接管
 * ——快拖时圆钮来回跳、轨道闪色；而"只在松手时改状态"又会被读成"不能实时拖动"。
 * 所以这里把**进度 0..1** 自己画出来：
 * · 拖动中直接按"按下时的进度 + 手指位移/行程"画（RTL 反向），**不做任何二次动画**；
 * · 松手后先 `snapTo` 到松手位置，再弹簧补到目标 ⇒ 不跳变；
 * · 点击仍走 `toggleable`：**点击有涟漪**，而拖动一开始就把按住态 Cancel 掉（拖动不要涟漪）。
 *
 * 颜色与形态对齐分段控件：关态轨道 = 分段槽的 0.08 灰；圆点 = 页面色 + 0.5dp 细边。
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 关态轨道深浅：默认 0.08（与分段槽一致）；抽屉里的悬浮控制台单独加深。 */
    offTrackTint: Float = 0.08f,
) {
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactive = onCheckedChange != null && enabled
    val changeNow by rememberUpdatedState(onCheckedChange)
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val settleSpec = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

    val anim = remember { Animatable(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(checked) {
        if (!dragging) anim.animateTo(if (checked) 1f else 0f, settleSpec)
    }
    // Track drawing and the thumb feedback layer read animation progress locally.
    val progressNow = remember(anim) { { (if (dragging) dragProgress else anim.value).coerceIn(0f, 1f) } }
    val rippleIndication = remember(colors.onSurface) {
        // Material's state layer is wider than its thumb: keep a 40dp circular halo.
        TapRipple(ripple(bounded = false, radius = 20.dp, color = colors.onSurface))
    }

    // 拖动开始时取消按住态：点击仍有涟漪，拖动不留。
    val press = remember { arrayOfNulls<androidx.compose.foundation.interaction.PressInteraction.Press>(1) }
    LaunchedEffect(source) {
        source.interactions.collect { event ->
            when (event) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> press[0] = event
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel,
                -> press[0] = null
                else -> Unit
            }
        }
    }

    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            // The indication lives on the moving thumb, sharing this interaction source.
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }
    }
    val drag = if (interactive) {
        Modifier.pointerInput(checked, enabled, rtl) {
            detectHorizontalDragGestures(
                onDragStart = {
                    press[0]?.let {
                        source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Cancel(it))
                        press[0] = null
                    }
                    dragStartX = it.x
                    dragStartProgress = anim.value
                    dragProgress = anim.value
                    dragging = true
                },
                onDragCancel = {
                    dragging = false
                    scope.launch { anim.animateTo(if (checked) 1f else 0f, settleSpec) }
                },
                onDragEnd = {
                    val released = dragProgress
                    scope.launch {
                        // 先对齐松手位置，再交回状态：不跳变。
                        anim.snapTo(released)
                        dragging = false
                        val next = released >= 0.5f
                        if (next != checked) changeNow?.invoke(next)
                        else anim.animateTo(if (checked) 1f else 0f, settleSpec)
                    }
                },
            ) { change, _ ->
                change.consume()
                // 行程 = 46dp 轨道的可用宽度（左右各 4dp 内缩）⇒ 38dp。
                // 之前误用 `size.width`（外层 48dp 命中区）减 56dp，结果被 coerce 成 1px，
                // 手指一动就跳到底 —— 表现就是「拖动只有两个状态，没有中间态」。
                val travel = (46.dp - 8.dp).toPx().coerceAtLeast(1f)
                val delta = (change.position.x - dragStartX) / travel * (if (rtl) -1f else 1f)
                dragProgress = (dragStartProgress + delta).coerceIn(0f, 1f)
            }
        }
    } else Modifier

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(drag)
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        val trackOff = lerp(colors.surface, colors.onSurface, offTrackTint)
        val alpha = if (enabled && onCheckedChange != null) 1f else 0.38f
        Canvas(
            Modifier
                .size(width = 46.dp, height = 28.dp)
                .alpha(alpha),
        ) {
            val progress = progressNow()
            val trackColor = lerp(trackOff, colors.primary, progress)
            val h = size.height
            val w = size.width
            val radius = h / 2f
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius, radius))
            val inset = 4.dp.toPx()
            val thumbR = radius - inset
            val cx = if (rtl) fLerp(w - thumbR - inset, thumbR + inset, progress)
            else fLerp(thumbR + inset, w - thumbR - inset, progress)
            // 圆点：页面色 + 细边（学分段控件的选中块：面 + 0.5dp 描边，而不是纯色圆片）。
            drawCircle(color = colors.surface, radius = thumbR, center = Offset(cx, h / 2f))
            drawCircle(
                color = colors.outlineVariant.copy(alpha = 0.55f),
                radius = thumbR,
                center = Offset(cx, h / 2f),
                style = Stroke(width = 0.5.dp.toPx()),
            )
        }
        if (interactive) Box(
            Modifier.size(20.dp)
                .graphicsLayer {
                    translationX = (progressNow() - 0.5f) * 18.dp.toPx() * if (rtl) -1f else 1f
                }
                .indication(source, rippleIndication),
        )
    }
}

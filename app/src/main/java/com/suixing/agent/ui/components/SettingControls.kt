package com.suixing.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectNow by rememberUpdatedState(onSelect)
    var dragPosition by remember(options, selectedIndex, rtl) { mutableStateOf<Float?>(null) }
    val position = animateFloatAsState(
        targetValue = dragPosition ?: selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)).toFloat(),
        animationSpec = if (dragPosition != null) snap() else tween(180, easing = FastOutSlowInEasing),
        label = "segment selection",
    )
    val visibleIndex = dragPosition?.roundToInt() ?: selectedIndex
    Box(
        modifier.fillMaxWidth().selectableGroup()
            .pointerInput(options, selectedIndex, rtl) {
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
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
                .clip(shape).background(lerp(colors.surface, colors.onSurface, 0.06f)),
        ) {
            if (options.isNotEmpty()) Box(
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
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                        // Foundation expands this 40dp visual node's touch target to
                        // 48dp. Press coordinates and the ripple now share one origin.
                        .padding(vertical = 4.dp).clip(shape)
                        .selectable(
                            selected = index == selectedIndex,
                            interactionSource = interaction,
                            indication = ripple(color = colors.primary),
                            role = role,
                            onClick = { if (index != selectedIndex) onSelect(index) },
                        ),
                ) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = SettingsStyle.controlLabel,
                            color = if (index == visibleIndex) colors.onSurface else colors.onSurfaceVariant,
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

/** Every settings destination uses the same switch size, track and disabled treatment. */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val track = lerp(colors.primary, colors.surface, 0.12f)
    val source = remember { MutableInteractionSource() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactive = onCheckedChange != null
    val changeNow by rememberUpdatedState(onCheckedChange)
    var dragChecked by remember(checked, enabled, interactive, rtl) { mutableStateOf<Boolean?>(null) }
    // Keep the touch/semantics owner unscaled while the standard animated switch shrinks.
    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            // Switch draws its own thumb-centered ripple from this shared source.
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
    val drag = if (enabled && interactive) {
        Modifier.pointerInput(checked, enabled, interactive, rtl) {
            try {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragChecked = segmentPositionAt(offset.x, size.width.toFloat(), 2, rtl)?.let { it >= 0.5f }
                    },
                    onDragCancel = { dragChecked = null },
                    onDragEnd = {
                        val next = dragChecked
                        dragChecked = null
                        if (next != null && next != checked) changeNow?.invoke(next)
                    },
                ) { change, _ ->
                    change.consume()
                    dragChecked = segmentPositionAt(change.position.x, size.width.toFloat(), 2, rtl)?.let { it >= 0.5f }
                }
            } finally {
                dragChecked = null
            }
        }
    } else Modifier
    Box(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).then(drag).then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = dragChecked ?: checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.scale(0.88f).clearAndSetSemantics { },
            interactionSource = source,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = track,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surfaceContainerHigh,
                uncheckedBorderColor = colors.outlineVariant,
            ),
        )
    }
}

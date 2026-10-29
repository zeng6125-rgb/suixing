package com.suixing.agent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.suixing.agent.R
import kotlin.math.roundToInt
/**
 * The app's one popup card: rounded, shadowed, and animated in and out.
 *
 * Every menu on this screen wears it — the 生成设置 sheet and the 「+」 attachment menu — so
 * they read as one control instead of as two unrelated leftovers. The caller keeps passing
 * [visible]; this keeps the popup composed until the exit animation has played, because a
 * popup taken down the moment it is dismissed has no time to animate out.
 */
@Composable
internal fun MenuCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment,
    offset: IntOffset,
    modifier: Modifier = Modifier,
    /**
     * Whether the popup's own window takes focus.
     *
     * False by default, and every caller in this file passes it explicitly. That default is the
     * lesson of the 「点圆环把输入法关了」 report: a focusable popup window takes the keyboard away
     * from the field, the shell then drops the whole composer by the keyboard's height, and a card
     * anchored 8dp above a button of that row is left floating a keyboard above it — the card the
     * user photographed as 偏上. Only a card whose own subject *is* the keyboard (a field inside
     * it) should ask for `true`; everything hanging off this screen's bars is focus-less, which
     * means it can see neither a tap outside itself nor BACK, so both live with the caller: see the
     * scrim and BackHandler in [Composer], the ring's card, and the metric card's call site.
     */
    focusable: Boolean = false,
    /** 需要显式定位时传它（与 alignment + offset 二选一）。 */
    popupPositionProvider: androidx.compose.ui.window.PopupPositionProvider? = null,
    /** 是否自带"点外面即关闭"的遮罩（默认带：focus-less 窗口自己看不到外部点击）。 */
    scrim: Boolean = true,
    /** 卡片底色；默认与其它浮层一致（theme 的 surface）。 */
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    var keepMounted by remember { mutableStateOf(visible) }
    val motion = remember { PopupMotionState() }
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 1f)) }
    val shadowMargin = with(LocalDensity.current) { PopupShadowMargin.roundToPx() }
    val shadowProvider = remember(popupPositionProvider, alignment, offset, shadowMargin) {
        ShadowPopupPositionProvider(shadowMargin, popupPositionProvider, alignment, offset) { origin = it }
    }
    LaunchedEffect(visible) {
        if (visible) {
            keepMounted = true
            // Keep real content measured before starting the shared entrance motion.
            androidx.compose.runtime.withFrameNanos { }
            motion.show()
        } else if (keepMounted) {
            motion.hide()
            keepMounted = false
        }
    }
    // A re-open reverses the current animation, rather than jumping back to scale zero.
    if (!visible && !keepMounted) return
    // **自带遮罩**：focus-less 的 Popup 窗口看不到外部点击与 BACK，没有这层就"点哪都关不掉"。
    // 放在卡片 Popup 之前组合 —— 窗口按组合顺序叠，先组合的在下面。
    if (visible && scrim) {
        Popup(properties = PopupProperties(focusable = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
    }
    val cardBody: @Composable () -> Unit = {
        // The motion layer owns the shadow margin, not just the card: group opacity composites
        // into a buffer sized to this node, and a card-sized buffer sliced the shadow off for
        // the first frames of every fade.
        Box(Modifier.popupMotion(motion) { origin }.padding(PopupShadowMargin)) {
        Column(
            modifier = modifier
                .popupCardSurface(containerColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            content = content,
        )
        }
    }
    Popup(
        popupPositionProvider = shadowProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable, clippingEnabled = false),
        content = cardBody,
    )
}

/**
 * [popupNudge] for a card centred on its anchor instead of left-aligned to it — the ring's own
 * detail card, which is centred on a ring that sits at the row's right end.
 */
internal fun popupNudgeCentred(anchorCentre: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorCentre == 0f) return 0f
    val desired = anchorCentre - width / 2f
    val left = desired.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - desired
}

/** One row of a popup menu: icon, label, and the whole row as the target. */
@Composable
internal fun MenuItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** Ticked on the right when this row is the one in force; the caller says which. */
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The thinking-strength slider: a gradient-filled track with a round handle, drawn here
 * rather than taken from Material.
 *
 * The stock slider's hairline rail and its flanking icons were the look the user called
 * old-fashioned; this one carries the app's own colours and nothing else. The value is a
 * level index, so a drag snaps to the nearest step as it goes.
 */
@Composable
internal fun LevelSlider(levelIndex: Int, levels: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val last = (levels - 1).coerceAtLeast(1)
    var width by remember { mutableStateOf(0) }
    val fraction = (levelIndex.toFloat() / last).coerceIn(0f, 1f)
    // **手势闭包里的等级必须是"最新值"**（用户实测：从 max 滑到别的档之后滑不回 max，
    // 得重开弹窗）。原因：`pointerInput(levels)` 只在 `levels` 变时重启，它捕获的 `pick`
    // 一直持有**打开弹窗那一刻**的 `levelIndex` —— 于是「滑回原档」时 `index == 旧值`，
    // 被当成"没变"而不派发，滑不回去。
    //
    // 不能改成 `pointerInput(levels, levelIndex)`：等级一变指针输入就重启，**正在进行的拖动
    // 会被取消**，滑一下就断。所以用 `rememberUpdatedState` 取最新值，键仍然是 `levels`。
    val currentLevel by rememberUpdatedState(levelIndex)
    val currentOnChange by rememberUpdatedState(onChange)
    val pick: (Float) -> Unit = { x ->
        if (width > 0) {
            val index = (x / width * last).roundToInt().coerceIn(0, last)
            if (index != currentLevel) currentOnChange(index)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(levels) { detectTapGestures { spot -> pick(spot.x) } }
            .pointerInput(levels) {
                detectHorizontalDragGestures { change, _ -> pick(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val trackHeight = 8.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val top = (size.height - trackHeight) / 2f
            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = radius,
            )
            // Filled up to the handle, in a brand gradient: a flat colour read as a
            // progress bar rather than as a control that can be moved.
            val filled = (size.width * fraction).coerceAtLeast(trackHeight)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(scheme.primary.copy(alpha = 0.55f), scheme.primary),
                ),
                topLeft = Offset(0f, top),
                size = Size(filled, trackHeight),
                cornerRadius = radius,
            )
            val handle = 11.dp.toPx()
            val centre = Offset(
                x = (size.width * fraction).coerceIn(handle, (size.width - handle).coerceAtLeast(handle)),
                y = size.height / 2f,
            )
            // A surface ring inside the handle, so it stays visible where it overlaps the
            // filled part of the rail.
            drawCircle(color = scheme.surface, radius = handle, center = centre)
            drawCircle(color = scheme.primary, radius = handle - 2.dp.toPx(), center = centre)
        }
    }
}

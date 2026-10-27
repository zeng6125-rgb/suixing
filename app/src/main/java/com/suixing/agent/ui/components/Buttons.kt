package com.suixing.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.R

/**
 * 全站统一的可点块：形状、涟漪、按下反馈都从这一处来。
 *
 * 为什么要有它：`ripple()` 是 **bounded** 的——它按**这个节点自己的**尺寸画。顺序或挂载点写错，
 * 就正好是用户报的那两类毛病：
 *  - `.padding(...)` 写在 `.clickable(...)` **前面** ⇒ 节点比可见的行小一圈，涟漪四周差一截
 *    （「没有填充满」）；
 *  - `.clickable(...)` 挂在内层内容块上（只有图标、或只有一行字）⇒ 可见的是一整行，涟漪却是
 *    中间一个小圆点。
 * 这里把顺序钉死：**clip(shape) → clickable → 调用方自己的 padding**。clip 在 clickable 之前，
 * 涟漪就跟着各自形状的圆角/圆形走，既不会溢成方角，也不会被 padding 裁掉。
 *
 * 关于 `remember`：`MutableInteractionSource` 必须 `remember`，而 `remember` 是组合函数，写不进
 * 普通的 `Modifier` 扩展。两种解法：
 *  1. **把这个工厂本身声明成 `@Composable`**（本文件采用）——调用点本来就在组合里，没有额外节点、
 *     没有额外开销；代价是它只能在组合中调用，而这恰好是我们要的约束。
 *  2. `Modifier.composed { remember { ... } }`——它是为「必须在非组合上下文里构造修饰符」准备的，
 *     会多一层 node，在布局/绘制阶段调用还有额外开销。这里不需要，所以不用。
 * 需要自己读按下状态（做整块高亮之类）的调用方，用下面那个带 [interactionSource] 的重载。
 */
@Composable
fun Modifier.suixingClickable(
    shape: Shape,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = suixingClickable(
    shape = shape,
    interactionSource = remember { MutableInteractionSource() },
    enabled = enabled,
    role = role,
    onClick = onClick,
)

/** 同上，但交互源由调用方持有（例如还要 `collectIsPressedAsState` 做按下反馈）。 */
fun Modifier.suixingClickable(
    shape: Shape,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this
    .clip(shape)
    .clickable(
        interactionSource = interactionSource,
        indication = ripple(),
        enabled = enabled,
        role = role,
        onClick = onClick,
    )

/**
 * The app's round action button: a filled circle that lifts when pressed.
 *
 * One helper for the header's two buttons, the composer's send/stop/steer and the
 * jump-to-latest button, so the four of them cannot drift apart in size, colour or
 * shadow — they were built separately once and did.
 */
/**
 * A lifted disc button: the chat header floats over the transcript with no bar of its
 * own, so each of its buttons carries its own surface and the same soft shadow the
 * composer's capsule casts — a flat glyph over scrolling content has nothing to sit on.
 *
 * The disc sizes across the app are one scale, because every disc is read against the ones
 * beside it — see the table in [CircleActionButton]: this one is the 40dp disc with a 20dp
 * glyph, and it answers across the whole 48dp of the box it is drawn in.
 */
@Composable
fun FlatIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The same two boxes as [CircleActionButton]: the outer one is the whole 48dp touch
    // target, the inner one only the drawing — a pressed disc lifts, the box it lives in
    // does not move.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = tint,
                    bounded = false,
                    radius = size / 2,
                ),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    elevation = if (pressed) 8.dp else 6.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The disc the sidebar handle wears. The reference the user gave was about the *glyph* —
 * three thick bars — never the disc, so the disc keeps the neutral `surfaceContainerHigh`
 * it had before: the button reads as one of the header's other actions rather than the
 * single coloured control in the row. Paired with [handleInk] so the chat header and the
 * drawer handle flip together instead of drifting apart.
 */
@Composable
private fun handleDisc(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** The other half of [handleDisc]: the ink that reads on that disc. */
@Composable
private fun handleInk(): Color = MaterialTheme.colorScheme.onSurface

/**
 * The same disc as [FlatIconButton] for glyphs that are vectors of our own rather
 * than material's: the drawer handle and the compose pencil are drawn in
 * res/drawable so their stroke weight is ours to set.
 *
 * The disc and the ink come from [handleDisc]/[handleInk], the same pair the drawer's
 * handle uses: both draw the one button the user pointed at, so neither drifts alone.
 */
@Composable
fun FlatPainterButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = handleInk(),
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Same two boxes as [FlatIconButton]: a 40dp disc drawn inside a 48dp touch target,
    // lifting 2dp while held. The disc had no size parameter before, so it stayed 44dp
    // wherever it was used and could not sit beside the 40dp discs it was drawn with.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = tint,
                    bounded = false,
                    radius = size / 2,
                ),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    // 6dp → 8dp（按下 8 → 10）：用户说主页顶栏那两枚按钮的阴影「再深一点点」。
                    // 这两枚与标题胶囊（[PressPill]）是同一条基线上的三个元素，所以两处一起抬，
                    // 免得只有按钮变深、胶囊还浮在原来的高度上。颜色不动：加深靠高度，不靠墨色。
                    // （同文件 :651 的 [FlatIconButton] 是同一个数值的兄弟，但它不在聊天页顶栏上，
                    // 所以没跟着动 —— 要一起抬说一声。）
                    elevation = if (pressed) 10.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .clip(CircleShape)
                .background(handleDisc()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * A capsule that carries a title rather than an action — the chat header's own bar.
 *
 * It was drawn inline in the chat header, which is how it ended up the only 44dp capsule
 * in the app with its own shadow numbers while every disc beside it came from a helper.
 * It takes no click: nothing about the conversation's name is a button, so it is a
 * surface, and [onClick] exists only for the caller that later wants it tappable — a
 * capsule that answers a tap is the same shape whether or not anything happens.
 */
@Composable
fun PressPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = LocalContentColor.current,
    // 6dp → 8dp：与它两侧的两枚 [FlatPainterButton] 一起抬（用户要求顶栏那三个元素的阴影再深
    // 一点点，且彼此一致）。全站只有聊天页标题这一处用它，所以改默认值不会波及别处。
    elevation: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    // 常量形状（原来每次重组都 new 一个，全站胶囊共用）。
    val shape = PillShape
    val shadowed = modifier.shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.12f),
        spotColor = Color.Black.copy(alpha = 0.12f),
    )
    Surface(
        // 胶囊形状要跟着涟漪走：原来 clickable 挂在 Surface 外面、没有 clip，涟漪是一个**方角矩形**
        // 盖在一颗胶囊上（用户看到的「没填充满/形状不对」）。suixingClickable 自带 clip(shape)。
        modifier = if (onClick == null) shadowed else shadowed.suixingClickable(shape, onClick = onClick),
        shape = shape,
        color = color,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A filled disc that lifts when pressed — the app's one round action button.
 *
 * One helper for the composer's send/stop/steer, the jump-to-latest button and a page's
 * back button, so the discs on a screen cannot drift apart in size, colour or shadow.
 *
 * The scale, and why it has two steps rather than one: a disc is read against the discs
 * beside it, and shape decides which family it belongs to. A control with a label keeps
 * the form's 40dp rail; a disc carrying only a glyph gets the 48dp touch box and stays
 * inside it visually at 40dp. 44dp exists only for the two headers' handles — the one
 * button the user grabs without looking, and the one the composer capsule is built around.
 *
 * | element                  | disc | glyph | touch | shape            |
 * |--------------------------|------|-------|-------|------------------|
 * | composer send/stop/steer | 40   | 22    | 48    | circle, flat     |
 * | composer capsule host    | 44   | —     | —     | large (22 radius)|
 * | header / back            | 40   | 20    | 48    | circle, lifted   |
 * | drawer handle            | 44   | 20    | 48    | circle, lifted   |
 * | jump to latest (chat)    | 48   | 22    | 48    | circle, lifted   |
 * | inline (copy output)     | 28   | 16    | 28    | circle, flat     |
 *
 * The inline row and the jump button are the deliberate exceptions. The first lives in the
 * corner of a code block, where a 48dp box would cover the text it copies. The second floats
 * alone over the transcript with no bar behind it and has to be found without looking, so it
 * is one step *up* from the shared disc rather than flattened into it. Never below 36dp for a
 * standalone disc, and never a glyph below 18dp unless it sits in that inline slot.
 */
@Composable
fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    /**
     * 0.dp for a button that must not cast one — the send button is flat by design, and
     * so is the back button on a filled bar. Such a button stays flat when pressed too:
     * see the press rule in the painter overload below.
     */
    shadowElevation: Dp = 2.dp,
    /** 阴影色：浮层卡片同一套（Black 18% 同色 ambient/spot）。 */
    shadowColor: Color = Color.Black.copy(alpha = 0.12f),
) = CircleActionButton(
    painter = rememberVectorPainter(icon),
    contentDescription = contentDescription,
    onClick = onClick,
    modifier = modifier,
    size = size,
    containerColor = containerColor,
    contentColor = contentColor,
    enabled = enabled,
    iconSize = iconSize,
    shadowElevation = shadowElevation,
    shadowColor = shadowColor,
)

/**
 * The same disc for a glyph that lives in a drawable rather than in code — the drawer
 * handle, which the chat header draws from the same resource. Module-internal so the
 * shared top bars can use it; the public API stays the [ImageVector] overload.
 */
@Composable
internal fun CircleActionButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    shadowElevation: Dp = 2.dp,
    /** 阴影颜色：浮层卡片用 Black 18% 同色 ambient/spot（无方向性），见 PopupStyle.kt。 */
    shadowColor: Color = Color.Black.copy(alpha = 0.12f),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Two boxes on purpose. M3's clickable Surface folds its 48dp minimum touch size into
    // the same box that carries the shape, so a 40dp disc asked for inside a 36dp row came
    // back as a 48x36 ellipse with the ripple bound to that box. Here the outer box is the
    // touch target and the inner one is only the drawing: the disc is always [size] round
    // and a parent that cannot spare 48dp costs touch area — never the shape. The click sits
    // on the outer box so the whole 48dp is reachable: on a child box the hit area would
    // shrink back to the disc, which is what the old Surface did wrong in the other
    // direction.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = contentColor,
                    // Unbounded and disc-sized: a bounded ripple inside a 48dp box grows
                    // into the corners and reads as a circle wider than the button, the
                    // artefact this rewrite exists to remove.
                    bounded = false,
                    radius = size / 2,
                ),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    // The press lifts a disc off the surface it sits on. A button that
                    // asked for no shadow has no disc to lift — the back button on a
                    // filled bar is only a glyph — so it stays flat on press instead of
                    // growing a smudge; every filled button keeps the lift.
                    elevation = if (pressed && shadowElevation > 0.dp) shadowElevation + 2.dp else shadowElevation,
                    shape = CircleShape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The handle that opens the drawer. Both headers draw it as a circle now — the shape
 * the user's reference shows — so [SuixingTopBar] takes the default too; the rounded
 * square is still accepted for a caller that wants it, and none does today.
 *
 * Public because the chat header is not a [SuixingTopBar]: it is a solid bar of its own.
 */
@Composable
fun DrawerButton(size: Dp = 44.dp, shape: Shape = CircleShape) {
    val open = LocalOpenSidebar.current ?: return
    // One glyph, drawn once: the chat header's handle is this same drawable through
    // FlatPainterButton, so the two headers cannot drift into two hamburgers.
    val handle = painterResource(R.drawable.ic_menu_soft)
    if (shape == CircleShape) {
        CircleActionButton(
            painter = handle,
            contentDescription = "打开侧边栏",
            onClick = open,
            size = size,
            containerColor = handleDisc(),
            contentColor = handleInk(),
            iconSize = 20.dp,
        )
        return
    }
    // The square handle gets the same treatment as the round one: the 48dp box is the
    // clickable itself and the tile is a child of it, so the whole box answers the thumb
    // while the tile keeps its [size]. A Surface(onClick) inside a sized box only reserves
    // 48dp of layout space — its own hit area stays at [size].
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    bounded = false,
                    radius = size / 2,
                ),
                role = Role.Button,
                onClick = open,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = handle,
                    contentDescription = "打开侧边栏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 胶囊圆角：PressPill 共用，避免每次重组新建。 */
private val PillShape = RoundedCornerShape(50)

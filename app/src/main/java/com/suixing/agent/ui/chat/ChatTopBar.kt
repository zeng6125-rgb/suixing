
package com.suixing.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.suixing.agent.R
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.PressPill

/**
 * The chat header: drawer handle on the left, the conversation's name in the middle,
 * and one capsule on the right holding both the new-conversation and overflow actions.
 * The overflow card is anchored to that capsule, so opening it cannot shift the row.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    onNew: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTerminal: () -> Unit,
    /** 标题胶囊右侧的入口；null 不画。 */
    contextEntry: (@Composable RowScope.() -> Unit)? = null,
) {
    val openDrawer = LocalOpenSidebar.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 4.dp)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_menu_soft),
            contentDescription = "打开侧边栏",
            onClick = { openDrawer?.invoke() },
        )
        PressPill(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
        ) {
            val style = titleBarStyle()
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = style.copy(
                    lineHeight = style.fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contextEntry != null) {
                contextEntry.invoke(this)
            }
        }
        // One capsule for both right-hand actions. The overflow card is a child of this
        // Box, not of the Row: the Row keeps exactly three children whether the card is
        // open or closed, so the buttons cannot move when the menu appears.
        Box {
            val capsuleShape = RoundedCornerShape(50)
            Surface(
                modifier = Modifier
                    .height(44.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = capsuleShape,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.12f),
                    ),
                shape = capsuleShape,
                // 与左侧抽屉按钮（FlatPainterButton → handleDisc）同一个色 token：
                // surface 在浅色主题是纯白，会跟旁边两枚 surfaceContainerHigh 圆盘脱节。
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Plain clickable Boxes, not IconButton: M3's IconButton forces a 48dp
                    // minimum touch target, which would overflow this 44dp capsule. The
                    // drawables carry a black android:tint, so pass onSurface explicitly —
                    // the same ink FlatPainterButton uses on the header's other actions.
                    CapsuleAction(
                        painter = painterResource(R.drawable.ic_compose),
                        description = "新对话",
                        onClick = onNew,
                    )
                    CapsuleAction(
                        painter = painterResource(R.drawable.ic_more_vertical),
                        description = "更多",
                        onClick = { menuOpen = true },
                    )
                }
            }
            val density = LocalDensity.current
            // Remembered: a fresh provider on every recomposition would re-run the popup's
            // positioning while the card is open (see ChatContext.kt's ringProvider).
            // Right-aligned, directly below the capsule, 8dp gap, 12dp screen margin.
            val belowCapsule = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val gap = with(density) { 8.dp.roundToPx() }
                        val edge = with(density) { 12.dp.roundToPx() }
                        val maxLeft = (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge)
                        val x = (anchorBounds.right - popupContentSize.width).coerceIn(edge, maxLeft)
                        val maxTop = (windowSize.height - edge - popupContentSize.height).coerceAtLeast(edge)
                        val y = (anchorBounds.bottom + gap).coerceAtMost(maxTop)
                        return IntOffset(x, y)
                    }
                }
            }
            MenuCard(
                visible = menuOpen,
                onDismiss = { menuOpen = false },
                alignment = Alignment.TopEnd,
                offset = IntOffset.Zero,
                popupPositionProvider = belowCapsule,
                // Narrow, fixed width: MenuItemRow uses fillMaxWidth(), so without this the
                // card stretches to the popup window's full width.
                modifier = Modifier.width(180.dp),
                focusable = false,
            ) {
                MenuItemRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_globe),
                    label = "打开浏览器",
                    onClick = {
                        menuOpen = false
                        onOpenBrowser()
                    },
                )
                MenuItemRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                    label = "打开终端",
                    onClick = {
                        menuOpen = false
                        onOpenTerminal()
                    },
                )
            }
        }
    }
}

/** The bar's title: 15sp medium, scaled off titleMedium so 字号 still applies. */
@Composable
internal fun titleBarStyle(): TextStyle {
    val title = MaterialTheme.typography.titleMedium
    return title.copy(fontSize = title.fontSize * (15f / 17f))
}

/** One 44×44 slot inside the right-hand capsule; no M3 minimum-size inflation. */
@Composable
private fun CapsuleAction(
    painter: Painter,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

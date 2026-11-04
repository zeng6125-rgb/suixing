
package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.PressPill

/**
 * The chat header: a solid bar that owns the status bar strip. Drawer handle on the
 * left, the conversation's name in the middle, and two discs on the right: new
 * conversation and an overflow menu. The floating console moved to the drawer.
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
        // Two discs side by side: the compose glyph and the overflow menu.
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_compose),
            contentDescription = "新对话",
            onClick = onNew,
        )
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_more_vertical),
            contentDescription = "更多",
            onClick = { menuOpen = true },
        )
        MenuCard(
            visible = menuOpen,
            onDismiss = { menuOpen = false },
            alignment = Alignment.TopEnd,
            offset = IntOffset.Zero,
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

/** The bar's title: 15sp medium, scaled off titleMedium so 字号 still applies. */
@Composable
internal fun titleBarStyle(): TextStyle {
    val title = MaterialTheme.typography.titleMedium
    return title.copy(fontSize = title.fontSize * (15f / 17f))
}

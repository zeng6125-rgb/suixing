package com.suixing.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.suixing.agent.core.Core
import com.suixing.agent.ui.capability.CapabilityScreen
import com.suixing.agent.ui.chat.ChatScreen
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.extension.ExtensionScreen
import com.suixing.agent.ui.files.FilesScreen
import com.suixing.agent.ui.settings.SettingsScreen

private data class Destination(
    val route: String,
    val label: String,
    val hint: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
)

private val destinations = listOf(
    Destination("chat", "会话", "和随行对话", Icons.Outlined.Forum, Icons.Filled.Forum),
    Destination("capability", "能力", "权限与开关", Icons.Outlined.Security, Icons.Filled.Security),
    Destination("files", "文件", "浏览与编辑", Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
    Destination("extension", "扩展", "技能与 MCP", Icons.Outlined.Extension, Icons.Filled.Extension),
    Destination("settings", "设置", "模型与外观", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * The app shell.
 *
 * There is no bottom bar: the composer owns the bottom of the screen, and every
 * other destination is reached through the sidebar that slides in from the right.
 * The handle for it is drawn by [com.suixing.agent.ui.components.SuixingTopBar],
 * which is why the shell only has to publish [LocalOpenSidebar] once.
 */
@Composable
fun SuixingApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val pending by Core.gate.pending.collectAsState()
    val route = destinations
        .firstOrNull { destination -> current?.hierarchy?.any { it.route == destination.route } == true }
        ?.route
        ?: "chat"

    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    val navigate: (String) -> Unit = { target ->
        sidebarOpen = false
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalOpenSidebar provides { sidebarOpen = true }) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                // Screens own their top inset (their headers paint behind the status
                // bar) and the composer owns the bottom one, so the scaffold reserves
                // nothing for itself.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = "chat",
                    modifier = Modifier.padding(padding),
                    enterTransition = {
                        fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 28 }
                    },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(120)) },
                ) {
                    composable("chat") { ChatScreen() }
                    composable("capability") { CapabilityScreen() }
                    composable("files") { FilesScreen() }
                    composable("extension") { ExtensionScreen() }
                    composable("settings") { SettingsScreen() }
                }
            }
            Sidebar(
                open = sidebarOpen,
                route = route,
                onSelect = navigate,
                onClose = { sidebarOpen = false },
            )
        }
    }

    // Rendered outside the navigation host so a prompt cannot be missed while the
    // user is on another tab — the agent is blocked until it is answered.
    pending?.let { request -> ApprovalDialog(request.toolName, request.args) }
}

/**
 * The right-hand drawer. Everything the bottom bar used to hold lives here, with
 * room for a one-line explanation of each destination that a five-column bar
 * never had.
 */
@Composable
private fun BoxScope.Sidebar(
    open: Boolean,
    route: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
    }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(tween(240)) { it },
        exit = slideOutHorizontally(tween(200)) { it },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp),
            shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp),
            color = scheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SuixingMark(size = 30.dp)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text("随行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "跟着你走的手机智能体",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = scheme.outlineVariant,
                )
                Spacer(Modifier.height(12.dp))

                destinations.forEach { destination ->
                    SidebarItem(
                        destination = destination,
                        selected = destination.route == route,
                        onClick = { if (destination.route != route) onSelect(destination.route) else onClose() },
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "长按消息可以修改或重新发送。",
                    modifier = Modifier.padding(horizontal = 22.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) destination.activeIcon else destination.icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
                Text(
                    text = destination.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApprovalDialog(toolName: String, args: String) {
    AlertDialog(
        onDismissRequest = { Core.gate.resolve(false) },
        icon = { Icon(Icons.Filled.ShieldMoon, contentDescription = null) },
        title = { Text("需要你确认") },
        text = {
            Column {
                Text("随行想调用工具：$toolName")
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = args,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "拒绝后模型会收到失败结果，可以换个方式继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { Core.gate.resolve(true) }) { Text("允许一次") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { Core.gate.resolve(true, always = true) }) { Text("总是允许") }
                TextButton(onClick = { Core.gate.resolve(false) }) { Text("拒绝") }
            }
        },
    )
}

/** Shared content padding so every tab lines up with the same gutters. */
internal val ScreenPadding = PaddingValues(horizontal = 16.dp)

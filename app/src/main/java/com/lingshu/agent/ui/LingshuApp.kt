package com.lingshu.agent.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lingshu.agent.core.Core
import com.lingshu.agent.ui.capability.CapabilityScreen
import com.lingshu.agent.ui.chat.ChatScreen
import com.lingshu.agent.ui.extension.ExtensionScreen
import com.lingshu.agent.ui.files.FilesScreen
import com.lingshu.agent.ui.settings.SettingsScreen

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
)

private val destinations = listOf(
    Destination("chat", "会话", Icons.Outlined.Forum, Icons.Filled.Forum),
    Destination("capability", "能力", Icons.Outlined.Security, Icons.Filled.Security),
    Destination("files", "文件", Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
    Destination("extension", "扩展", Icons.Outlined.Extension, Icons.Filled.Extension),
    Destination("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun LingshuApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val pending by Core.gate.pending.collectAsState()
    val route = destinations
        .firstOrNull { destination -> current?.hierarchy?.any { it.route == destination.route } == true }
        ?.route
        ?: "chat"

    // Edge-to-edge means the window no longer resizes for the keyboard, so the
    // scaffold lifts itself and the floating bar steps out of the way instead of
    // sitting between the composer and the keyboard.
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        // Screens own their top inset (their headers paint behind the status bar),
        // so the scaffold only reserves room for the floating bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!keyboardVisible) {
                LingshuNavBar(
                    route = route,
                    onSelect = { target ->
                        navController.navigate(target) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
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

    // Rendered outside the navigation host so a prompt cannot be missed while the
    // user is on another tab — the agent is blocked until it is answered.
    pending?.let { request -> ApprovalDialog(request.toolName, request.args) }
}

/** A floating capsule instead of a full-width system bar. */
@Composable
private fun LingshuNavBar(route: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                destinations.forEach { destination ->
                    val selected = destination.route == route
                    NavCapsule(
                        destination = destination,
                        selected = selected,
                        onClick = { if (!selected) onSelect(destination.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavCapsule(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) scheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = if (selected) destination.activeIcon else destination.icon,
                    contentDescription = destination.label,
                    tint = tint,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
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
                Text("灵枢想调用工具：$toolName")
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

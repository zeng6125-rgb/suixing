package com.lingshu.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.lingshu.agent.core.Core
import com.lingshu.agent.ui.capability.CapabilityScreen
import com.lingshu.agent.ui.chat.ChatScreen
import com.lingshu.agent.ui.extension.ExtensionScreen
import com.lingshu.agent.ui.files.FilesScreen
import com.lingshu.agent.ui.settings.SettingsScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("chat", "会话", Icons.Outlined.Forum),
    Destination("capability", "能力", Icons.Outlined.Security),
    Destination("files", "文件", Icons.Outlined.FolderOpen),
    Destination("extension", "扩展", Icons.Outlined.Extension),
    Destination("settings", "设置", Icons.Outlined.Settings),
)

@Composable
fun LingshuApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val pending by Core.gate.pending.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = current?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
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
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { Core.gate.resolve(false) },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("需要确认") },
            text = {
                Column {
                    Text("灵枢想调用工具：${request.toolName}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = request.args,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { Core.gate.resolve(true) }) { Text("允许一次") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { Core.gate.resolve(true, always = true) }) { Text("总是允许") }
                    TextButton(onClick = { Core.gate.resolve(false) }) { Text("拒绝") }
                }
            },
        )
    }
}

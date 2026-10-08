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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Conversation
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.capability.CapabilityScreen
import com.suixing.agent.ui.chat.ChatScreen
import com.suixing.agent.ui.chat.ChatViewModel
import com.suixing.agent.ui.chat.SessionStats
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.extension.ExtensionScreen
import com.suixing.agent.ui.files.FilesScreen
import com.suixing.agent.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlinx.coroutines.flow.map

/**
 * The app shell.
 *
 * Navigation lives in the left drawer, and the drawer is mostly a conversation list:
 * switching conversations is what a chat app is for, so it gets the top of the panel
 * and everything that is not a conversation sits at the bottom, out of the way. The
 * handle is drawn by [com.suixing.agent.ui.components.SuixingTopBar], which is why the
 * shell only has to publish [LocalOpenSidebar] once.
 */
@Composable
fun SuixingApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route?.substringBefore('?') ?: "chat"
    val pending by Core.gate.pending.collectAsState()

    // Owned here rather than inside the chat screen: the drawer lists and switches the
    // very conversations the screen is showing.
    val chat: ChatViewModel = viewModel()
    val conversations by chat.conversations.collectAsState()
    // The two ids alone, not the whole chat state: the shell must not recompose once per
    // streamed token just to keep one row highlighted and one dot lit.
    val shellIds by remember(chat) {
        chat.state.map { it.conversationId to it.runningChatId }.distinctUntilChanged()
    }.collectAsState(initial = "" to "")
    val activeConversationId = shellIds.first
    val runningChatId = shellIds.second
    // Same reason as the ids above: the picker needs one field, not the whole transcript.
    val assistantId by remember(chat) {
        chat.state.map { it.assistantId }.distinctUntilChanged()
    }.collectAsState(initial = "")

    // The conversation's own tally, for the drawer's status line. Its own flow, so the
    // shell does not recompose once per streamed token.
    val sessionStats by chat.sessionStats.collectAsState(initial = null)

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var assistantsOpen by rememberSaveable { mutableStateOf(false) }
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())

    // Top-level destinations replace each other: the drawer is a switch, not a stack.
    val open: (String) -> Unit = { target ->
        drawerOpen = false
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // A row inside the settings page pushes, so back returns to the list it came from.
    val push: (String) -> Unit = { target -> navController.navigate(target) }

    CompositionLocalProvider(LocalOpenSidebar provides { drawerOpen = true }) {
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
                    composable("chat") { ChatScreen(viewModel = chat) }
                    composable("files") { FilesScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") {
                        SettingsScreen(
                            onOpen = push,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("capability") { CapabilityScreen(onBack = { navController.popBackStack() }) }
                    composable(
                        route = "extension?tab={tab}",
                        arguments = listOf(
                            navArgument("tab") {
                                type = NavType.IntType
                                defaultValue = 0
                            },
                        ),
                    ) { entry ->
                        ExtensionScreen(
                            initialTab = entry.arguments?.getInt("tab") ?: 0,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
            Drawer(
                open = drawerOpen,
                route = route,
                conversations = conversations,
                activeId = activeConversationId,
                runningId = runningChatId,
                onClose = { drawerOpen = false },
                onNew = {
                    chat.newConversation()
                    open("chat")
                },
                onOpenConversation = { conversation ->
                    chat.openConversation(conversation)
                    open("chat")
                },
                onDeleteConversation = { chat.deleteConversation(it) },
                onOpenAssistants = {
                    drawerOpen = false
                    assistantsOpen = true
                },
                onNavigate = open,
                stats = sessionStats,
            )
        }
    }

    // Rendered outside the navigation host so a prompt cannot be missed while the
    // user is on another screen — the agent is blocked until it is answered.
    pending?.let { request -> ApprovalDialog(request.toolName, request.args) }

    // The assistant picker used to live in the chat header's avatar, which the floating
    // header no longer has; the drawer is where the rest of the app's switches live.
    if (assistantsOpen) {
        AssistantSheet(
            assistants = settings.assistants,
            activeId = assistantId.ifBlank { settings.activeAssistantId },
            settings = settings,
            onUse = { chat.useAssistant(it) },
            onSave = { chat.saveAssistant(it) },
            onDelete = { chat.deleteAssistant(it) },
            onDismiss = { assistantsOpen = false },
        )
    }
}

/**
 * The left drawer: conversations on top, everything else at the bottom.
 */
@Composable
private fun BoxScope.Drawer(
    open: Boolean,
    route: String,
    conversations: List<Conversation>,
    activeId: String,
    runningId: String,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onOpenAssistants: () -> Unit,
    stats: SessionStats?,
    onNavigate: (String) -> Unit,
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
        modifier = Modifier.align(Alignment.CenterStart),
        enter = slideInHorizontally(tween(240)) { -it },
        exit = slideOutHorizontally(tween(200)) { -it },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp),
            shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
            color = scheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    // The keyboard can be up when the drawer is opened mid-sentence.
                    .imePadding()
                    .padding(vertical = 18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SuixingMark(size = 30.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("随行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "跟着你走的手机智能体",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onNew, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新对话",
                            tint = scheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                // What this conversation has cost so far, from its own stored usage rows.
                // Hidden until there is something to say.
                stats?.let { tally ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buildString {
                                append("${tally.turns} 轮 ${tally.steps} 步")
                                if (tally.perSecond > 0) {
                                    append(" · 均 ")
                                    append(String.format(Locale.US, "%.1f", tally.perSecond))
                                    append(" tok/s")
                                }
                                if (tally.tokens > 0) append(" · ${tally.tokens / 1000}k tok")
                                if (tally.cachePercent > 0) append(" · 缓存 ${tally.cachePercent}%")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.weight(1f))
                        if (tally.contextTokens > 0) {
                            Text(
                                text = if (tally.contextWindow > 0) {
                                    "上下文 ${tally.contextTokens * 100 / tally.contextWindow}%"
                                } else {
                                    "上下文 ${tally.contextTokens / 1000}k"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = scheme.outlineVariant,
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == activeId,
                            running = conversation.id == runningId,
                            onClick = { onOpenConversation(conversation) },
                            onDelete = { onDeleteConversation(conversation) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = scheme.outlineVariant,
                )
                Spacer(Modifier.height(6.dp))
                DrawerAction(
                    label = "助手",
                    hint = "人格与工具白名单",
                    icon = Icons.Rounded.SmartToy,
                    selected = false,
                    onClick = onOpenAssistants,
                )
                DrawerAction(
                    label = "文件",
                    hint = "浏览与编辑",
                    icon = Icons.Rounded.FolderOpen,
                    selected = route == "files",
                    onClick = { onNavigate("files") },
                )
                DrawerAction(
                    label = "设置",
                    hint = "模型 · 外观 · 权限",
                    icon = Icons.Rounded.Settings,
                    selected = route == "settings",
                    onClick = { onNavigate("settings") },
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                // 运行中的会话可能在用户看不见的那一屏里跑，抽屉里得有个标记，
                // 否则「谁在占着那个 run」只能靠猜。
                Box(
                    Modifier
                        .size(7.dp)
                        .background(scheme.primary, CircleShape),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除会话",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerAction(
    label: String,
    hint: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
                Text(
                    text = hint,
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
        icon = { Icon(Icons.Rounded.ShieldMoon, contentDescription = null) },
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

/** Shared content padding so every screen lines up with the same gutters. */
internal val ScreenPadding = PaddingValues(horizontal = 16.dp)

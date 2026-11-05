package com.suixing.agent.ui.chat

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.suixing.agent.agent.RunState
import com.suixing.agent.core.Core
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

internal val LocalAccessibleChatActions = compositionLocalOf { false }

/** One listener and one status subscription for the page, independent of streamed tokens. */
@Composable
internal fun rememberChatAccessibility(chatId: String): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(chatId, view, lifecycle) {
        if (chatId.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Core.engine.stateOf(chatId).map { state ->
                when (state) {
                    is RunState.Running -> "任务开始执行"
                    is RunState.Done -> "任务已完成"
                    is RunState.Failed -> "任务失败，请查看错误回执"
                    is RunState.Cancelled -> "任务已停止"
                    RunState.Idle -> ""
                }
            }.distinctUntilChanged().drop(1).collect { status ->
                if (status.isNotEmpty() && manager.isEnabled) {
                    @Suppress("DEPRECATION")
                    view.announceForAccessibility(status)
                }
            }
        }
    }
    return exploring
}

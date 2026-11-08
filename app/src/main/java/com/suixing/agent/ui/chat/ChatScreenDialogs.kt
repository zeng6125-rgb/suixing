package com.suixing.agent.ui.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.FullScreenOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Global sheets retain their lifetime; message editing and todos are keyed to the displayed chat. */
internal class ChatDialogState(
    val editingState: MutableState<StoredMessage?>,
    val todoSheetOpenState: MutableState<Boolean>,
    val voiceOpenState: MutableState<Boolean>,
    val assistantsOpenState: MutableState<Boolean>,
    val editorAssistantIdState: MutableState<String?>,
    val pathMenuPathState: MutableState<String?>,
) {
    var editing by editingState
    var todoSheetOpen by todoSheetOpenState
    var voiceOpen by voiceOpenState
    var assistantsOpen by assistantsOpenState
    var editorAssistantId by editorAssistantIdState
    var pathMenuPath by pathMenuPathState
}

@Composable
internal fun rememberChatDialogState(state: ChatUiState): ChatDialogState {
    val editing = remember(state.conversationId) { mutableStateOf<StoredMessage?>(null) }
    val todos = remember(state.conversationId) { mutableStateOf(false) }
    val voice = remember { mutableStateOf(false) }
    val assistants = remember { mutableStateOf(false) }
    val editor = remember { mutableStateOf<String?>(null) }
    val path = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.conversationId, state.todos.isEmpty()) {
        if (state.todos.isEmpty()) todos.value = false
    }
    return remember(editing, todos, voice, assistants, editor, path) {
        ChatDialogState(editing, todos, voice, assistants, editor, path)
    }
}

@Composable
internal fun ChatInlineOverlays(
    state: ChatUiState,
    settings: AppSettings,
    dialogs: ChatDialogState,
    input: ChatInputBindings,
    composerH: Dp,
    onOpenSettings: () -> Unit,
) {

    var voiceOpen by dialogs.voiceOpenState
    var assistantsOpen by dialogs.assistantsOpenState
    var editorAssistantId by dialogs.editorAssistantIdState
    var pathMenuPath by dialogs.pathMenuPathState
    var editing by dialogs.editingState
    var todoSheetOpen by dialogs.todoSheetOpenState

    fun currentInput(): String = input.controller.currentInput()
    fun setDraft(text: String) = input.controller.setDraft(text = text)
        if (voiceOpen) {
            VoiceOverlay(
                // 转写要用这份设置里的服务商与模型。
                settings = settings,
                // Just above the composer, never over it: the capsule's whole point is that the
                // user can still read what they are dictating into.
                bottomInset = composerH + 8.dp,
                onOpenSettings = onOpenSettings,
                onResult = { text ->
                    val input = currentInput()
                    setDraft(text = if (input.isBlank()) text else "$input $text")
                },
                onDismiss = { voiceOpen = false },
            )
        }
        // 任务清单的完整版：点输入框上方那一行打开。清单在跑完/清空时自己为空，所以这里不需要
        // 额外的开关——内容没了，表也就没什么可显示。
        if (todoSheetOpen && state.todos.isNotEmpty()) {
            // `state.todos`，不是 `todos`：那个名字是 Composer 的参数，在这一层不存在。
            TodoSheet(todos = state.todos, onDismiss = { todoSheetOpen = false })
        }

}

@Composable
internal fun ChatDialogs(
    state: ChatUiState,
    settings: AppSettings,
    dialogs: ChatDialogState,
    actions: ChatScreenActions,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    val clipboard = LocalClipboardManager.current

    var voiceOpen by dialogs.voiceOpenState
    var assistantsOpen by dialogs.assistantsOpenState
    var editorAssistantId by dialogs.editorAssistantIdState
    var pathMenuPath by dialogs.pathMenuPathState
    var editing by dialogs.editingState
    var todoSheetOpen by dialogs.todoSheetOpenState
    // 文件路径菜单（LINK-CLICK-SPEC §3.2 一期）：复制路径、让JAgent读取。一期不碰 FileProvider
    // （那是二期「文件管理器打开 / 分享」）。「让JAgent读取」＝往会话发一条带该路径的用户消息，
    // agent 有 read_file/run_shell，能自己读；文件已在上一轮作为附件落进 inbox 的也照此办理。
    pathMenuPath?.let { path ->
        PathActionSheet(
            path = path,
            onDismiss = { pathMenuPath = null },
            onCopy = {
                clipboard.setText(AnnotatedString(path))
                pathMenuPath = null
                scope.launch { snackbarHostState.showSnackbar("已复制路径") }
            },
            onAskModel = {
                pathMenuPath = null
                val text = "读取这个文件并总结：$path"
                val accepted = actions.send(text)
                if (!accepted) {
                    // 运行中被 send 拒绝时按既有语义走插话；再不成功也要给一句回执，
                    // 不能只让菜单消失（issue #76 F-07）。
                    val queued = state.runningHere && actions.steer(text)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (queued) "已放进当前任务的排队"
                            else "没有发送：当前无法开始新的一轮，请稍后再试",
                        )
                    }
                }
            },
        )
    }

    if (assistantsOpen) {
        AssistantSheet(
            assistants = settings.assistants,
            activeId = state.assistantId.ifBlank { settings.activeAssistantId },
            settings = settings,
            onUse = { actions.useAssistant(it) },
            onSetDefault = { actions.setDefaultAssistant(it) },
            // 编辑/新建：收掉半屏表，编辑器画在主窗口的全屏覆盖层（结构路，0.8.105）。
            onOpenEditor = { id ->
                assistantsOpen = false
                editorAssistantId = id
            },
            onDismiss = { assistantsOpen = false },
        )
    }

    // 助手编辑器：主窗口里的全屏一层，盖住聊天页的一切（含浮动头）。Dialog 窗口在这台设备上
    // 的状态栏行为修了四轮都不对，所以这一页不再有自己的窗口。进出场与导航页同一套动画
    // （FullScreenOverlay）；退场那 200ms 里内容靠 lastEditorId 留住，不会淡出一片空白。
    var lastEditorId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editorAssistantId) {
        if (editorAssistantId != null) lastEditorId = editorAssistantId
    }
    FullScreenOverlay(visible = editorAssistantId != null) {
        val id = editorAssistantId ?: lastEditorId
        if (id != null) {
            val servers by Core.mcp.servers.collectAsStateWithLifecycle()
            val toolNames = remember(servers) { Core.tools.names() + com.suixing.agent.agent.SUBAGENT_SPECS.map { it.name } }
            val target = settings.assistants.firstOrNull { it.id == id }
                ?: Assistant(id = id, avatar = "🧭")
            AssistantEditor(
                initial = target,
                settings = settings,
                toolNames = toolNames,
                backEnabled = editorAssistantId != null,
                onSave = {
                    actions.saveAssistant(it)
                    editorAssistantId = null
                },
                onDelete = if (settings.assistants.size > 1) {
                    {
                        actions.deleteAssistant(target)
                        editorAssistantId = null
                    }
                } else {
                    null
                },
                onDismiss = { editorAssistantId = null },
            )
        }
    }

    editing?.let { message ->
        EditDialog(
            message = message,
            onDismiss = { editing = null },
            onSave = { text ->
                actions.editMessage(message.id, text)
                editing = null
            },
            onResend = if (message.role == Roles.USER) {
                { text ->
                    actions.editAndResend(message.id, text)
                    editing = null
                }
            } else {
                null
            },
        )
    }
}

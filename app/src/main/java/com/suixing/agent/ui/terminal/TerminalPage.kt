package com.suixing.agent.ui.terminal

import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.core.Core
import com.suixing.agent.terminal.*
import com.suixing.agent.ui.components.ActionMenu
import com.suixing.agent.ui.components.ActionMenuItem
import com.suixing.agent.ui.components.MenuRippleHost
import com.suixing.agent.ui.components.MenuRippleTextButton
import com.suixing.agent.ui.components.menuRippleClickable
import com.suixing.agent.ui.components.PageTopBar
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen(onBack: () -> Unit, chatId: String = "") {
    val manager = Core.terminals
    val entries by manager.entries.collectAsStateWithLifecycle()
    val warning by manager.backgroundWarning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(manager.selectedId) }
    var error by remember { mutableStateOf<String?>(null) }
    var environments by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<TerminalEntry?>(null) }
    var preparing by remember { mutableStateOf(true) }
    val current = entries.firstOrNull { it.info.id == selected } ?: entries.lastOrNull()
    fun select(entry: TerminalEntry) {
        selected = entry.info.id
        manager.selectedId = selected
        error = null
    }
    fun open(kind: String) {
        scope.launch {
            runCatching { manager.open(kind) }.onSuccess {
                select(it)
            }.onFailure { error = it.message }
        }
    }
    fun remove(entry: TerminalEntry) {
        val removingCurrent = current?.info?.id == entry.info.id
        scope.launch {
            runCatching { manager.remove(entry.info.id) }
                .onSuccess {
                    if (removingCurrent) {
                        selected = manager.entries.value.lastOrNull()?.info?.id
                        manager.selectedId = selected
                    }
                }
                .onFailure { error = it.message }
        }
    }
    LaunchedEffect(manager) {
        manager.load()
        Core.linux.load()
        preparing = false
        if (manager.entries.value.isEmpty()) open("android")
    }
    BackHandler(onBack = onBack)
    if (newSession) AlertDialog(
        onDismissRequest = { newSession = false }, title = { Text("新建终端会话") },
        text = {
            MenuRippleHost(horizontalOverflow = 24.dp) {
            Column {
                Text("最多同时运行 4 个会话，退出页面后继续保留。", style = MaterialTheme.typography.bodySmall)
                // Session choices are full-width rows; the host's wider feedback must not leave
                // only each label's intrinsic width tappable.
                MenuRippleTextButton(
                    onClick = { newSession = false; open("android") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Android · 应用身份", modifier = Modifier.fillMaxWidth()) }
                for (distro in LINUX_DISTRIBUTIONS) MenuRippleTextButton(onClick = {
                    newSession = false
                    if (Core.linux.states.value[distro.id]?.available == true) open(distro.id) else environments = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(distro.title + " Linux", modifier = Modifier.fillMaxWidth())
                }
            }
            }
        }, confirmButton = { TextButton(onClick = { newSession = false }) { Text("取消") } },
    )
    if (sessions) TerminalSessionsDialog(
        entries = entries,
        selectedId = current?.info?.id,
        onSelect = { select(it); sessions = false },
        onStop = { sessions = false; closing = it },
        onRemove = { remove(it) },
        onNew = { sessions = false; newSession = true },
        onEnvironments = { sessions = false; environments = true },
        onDismiss = { sessions = false },
    )
    if (environments) LinuxEnvironmentsDialog(
        onDismiss = { environments = false },
        onOpen = { kind -> environments = false; open(kind) },
        onInstallTools = { distro ->
            environments = false
            scope.launch {
                runCatching {
                    val entry = manager.open(distro.id)
                    select(entry)
                    entry.session!!.write(distro.toolsCommand + "\r")
                }.onFailure { error = it.message }
            }
        },
    )
    closing?.let { entry ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text("停止这个终端会话？") },
            text = { Text("将终止这个会话中的命令，其它会话继续运行。") },
            confirmButton = { TextButton(onClick = {
                closing = null
                scope.launch { runCatching { manager.stop(entry.info.id) }.onFailure { error = it.message } }
            }) { Text("停止") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("取消") } })
    }
    Scaffold(topBar = { PageTopBar(title = "终端", onBack = onBack,
        actions = { TextButton(onClick = { sessions = true }) { Text("会话 · ${entries.size}") } }) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
            when {
                current == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (preparing) "正在准备终端…" else "暂无终端会话")
                        if (!preparing) TextButton(onClick = { newSession = true }) { Text("新建会话") }
                    }
                }
                current.session != null -> key(current.info.id) {
                    TerminalContent(current, "会话 ${entries.indexOf(current) + 1} · ${current.info.name}", chatId,
                        onStop = { closing = current },
                        onRemove = { remove(current) }, onError = { error = it }, modifier = Modifier.weight(1f))
                }
                else -> Column(Modifier.weight(1f).padding(12.dp)) {
                    Text(current.info.note ?: "会话已结束，请新建会话。")
                    TextButton(onClick = { remove(current) }) { Text("移除记录") }
                }
            }
        }
    }
}

@Composable
private fun TerminalSessionsDialog(
    entries: List<TerminalEntry>, selectedId: String?, onSelect: (TerminalEntry) -> Unit,
    onStop: (TerminalEntry) -> Unit, onRemove: (TerminalEntry) -> Unit,
    onNew: () -> Unit, onEnvironments: () -> Unit, onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest = onDismiss, title = { Text("终端会话") },
        text = {
            MenuRippleHost(horizontalOverflow = 24.dp) {
            Column {
                if (entries.isEmpty()) Text("暂无会话", style = MaterialTheme.typography.bodyMedium)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.info.id }) { index, entry ->
                        val selected = entry.info.id == selectedId
                        val running = entry.info.phase == "running"
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected) scheme.primaryContainer else scheme.surface, MaterialTheme.shapes.small)
                            .semantics { this.selected = selected }
                            .clip(MaterialTheme.shapes.small)
                            .menuRippleClickable { onSelect(entry) }
                            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("会话 ${index + 1} · ${entry.info.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text((if (selected) "当前 · " else "") + when (entry.info.phase) {
                                    "running" -> "运行中"
                                    "interrupted" -> "已断开"
                                    else -> "已结束"
                                }, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                            if (running) MenuRippleTextButton(onClick = { onStop(entry) }) { Text("停止") }
                            else MenuRippleTextButton(onClick = { onRemove(entry) }) { Text("移除") }
                        }
                    }
                }
            }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            Row {
                TextButton(onClick = onEnvironments) { Text("Linux 环境") }
                TextButton(onClick = onNew) { Text("新建") }
            }
        })
}

@Composable
private fun TerminalContent(
    entry: TerminalEntry, title: String, chatId: String, onStop: () -> Unit, onRemove: () -> Unit,
    onError: (String) -> Unit, modifier: Modifier,
) {
    val session = entry.session!!
    val status by session.status.collectAsStateWithLifecycle()
    val ended by session.ended.collectAsStateWithLifecycle()
    val historyLimited by session.historyLimited.collectAsStateWithLifecycle()
    val running = status == TerminalSession.SessionStatus.RUNNING
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var modifierKey by remember { mutableStateOf(TerminalModifier.NONE) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var paste by remember { mutableStateOf<String?>(null) }
    var logEnabled by remember { mutableStateOf(session.log.enabled) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    fun keyboard() {
        if (running) view?.let { terminal ->
            // Run after a toolbar/menu click has returned focus to the native editor.
            terminal.post {
                if (terminal.isAttachedToWindow && terminal.requestFocus()) {
                    context.getSystemService(InputMethodManager::class.java)
                        .showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
    fun requestPaste() {
        val text = clipboard.getText()?.text.orEmpty()
        if (text.toByteArray().size > 16 * 1024) {
            Toast.makeText(context, "每次粘贴最多 16 KiB", Toast.LENGTH_SHORT).show()
        } else if (text.any { it < ' ' && it != '\t' || it.code == 127 }) {
            paste = text
        } else if (running) {
            session.takeOver()
            session.display.emulator.paste(text)
            keyboard()
        }
    }
    val currentPaste by rememberUpdatedState { requestPaste() }
    DisposableEffect(session) {
        session.onCopy = { clipboard.setText(AnnotatedString(it)) }
        session.onPaste = { currentPaste() }
        onDispose { session.onCopy = null; session.onPaste = null }
    }
    paste?.let { text ->
        AlertDialog(onDismissRequest = { paste = null }, title = { Text("确认粘贴内容") },
            text = { Column {
                Text("内容包含换行或控制字符，粘贴后可能执行命令。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { paste = it }, maxLines = 8)
            } },
            confirmButton = { TextButton(onClick = {
                if (running && text.toByteArray().size <= 16 * 1024) {
                    session.takeOver()
                    session.display.emulator.paste(text)
                }
                paste = null
                keyboard()
            }) { Text("确认粘贴") } },
            dismissButton = { TextButton(onClick = { paste = null }) { Text("取消") } })
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("会话信息") },
        text = { SelectionContainer {
            Text(listOfNotNull(
                if (entry.info.kind == "android") "Android · 应用身份" else "Linux · 模拟 root",
                "Shell：${session.shell}", "PID：${session.pid}",
                if (entry.info.kind == "android") session.cwd else "/workspace → ${session.cwd}",
                entry.info.note?.let { "启动说明：$it" },
            ).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("完成") } })
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(enabled = running, onClick = { requestPaste() }) { Text("粘贴") }
            TextButton(enabled = running, onClick = {
                session.takeOver()
                scope.launch {
                    runCatching { session.interrupt() }.onFailure { onError(it.message ?: "中断失败") }
                }
            }) { Text("中断") }
            Box {
                TextButton(onClick = { menu = true }) { Text("更多") }
                ActionMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ActionMenuItem(text = { Text("复制全部输出") }, onClick = {
                        menu = false
                        scope.launch {
                            clipboard.setText(AnnotatedString(session.transcript()))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    })
                    ActionMenuItem(text = { Text("清屏") }, onClick = { menu = false; session.clearOutput() })
                    TerminalControlMenu(session, chatId) { menu = false }
                    ActionMenuItem(text = { Text(if (logEnabled) "关闭日志" else "开启日志") }, onClick = {
                        menu = false
                        val enable = !logEnabled
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { session.log.setEnabled(enable) } }
                                .onSuccess { logEnabled = session.log.enabled }
                                .onFailure { onError(it.message ?: "无法切换日志") }
                        }
                    })
                    ActionMenuItem(text = { Text("清除日志") }, onClick = {
                        menu = false
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { session.log.clear() } }
                                .onSuccess { logEnabled = false }
                                .onFailure { onError(it.message ?: "无法清除日志") }
                        }
                    })
                    ActionMenuItem(text = { Text("会话信息") }, onClick = { menu = false; details = true })
                    ActionMenuItem(text = { Text(if (running) "停止会话" else "移除记录") }, onClick = {
                        menu = false
                        if (running) onStop() else onRemove()
                    })
                }
            }
        }
        if (!running || ended != null) Text(ended ?: sessionStatusLabel(status), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        if (historyLimited) Text("历史已达到保留上限，较早输出会被替换；开启日志可保存后续输出。",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
        TerminalViewport(session, modifierKey, { modifierKey = TerminalModifier.NONE }, { view = it },
            { keyboard() }, Modifier.weight(1f).fillMaxWidth())
        Hairline()
        KeyStrip(enabled = running, activeModifier = modifierKey, onKey = { key ->
            if (isModifier(key)) {
                val next = modifierFor(key)
                modifierKey = if (modifierKey == next) TerminalModifier.NONE else next
            } else {
                session.takeOver()
                val bytes = keyBytes(key, modifierKey)
                modifierKey = TerminalModifier.NONE
                scope.launch { runCatching { session.writeBytes(bytes) } }
            }
        }, onKeyboard = { keyboard() })
    }
}

@Composable
private fun TerminalControlMenu(session: TerminalSession, chatId: String, onDismiss: () -> Unit) {
    val runs by Core.engine.runsFlow.collectAsStateWithLifecycle()
    val revision by session.policy.revision.collectAsStateWithLifecycle()
    val caller = runs[chatId]?.terminalCaller
    val allowed = remember(revision, caller) { caller != null && session.policy.allow(caller) }
    ActionMenuItem(
        text = { Text(if (allowed) "接管终端 · 暂停 Agent 输入"
            else if (caller != null) "授权当前任务操作" else "用户控制 · 无运行中的任务") },
        enabled = allowed || caller != null && session.policy.canGrant(caller),
        onClick = {
            onDismiss()
            if (allowed) session.takeOver() else if (caller != null) session.policy.grantOrResume(caller)
        },
    )
}

@Composable
private fun TerminalViewport(
    session: TerminalSession, modifierKey: TerminalModifier, consume: () -> Unit,
    onView: (TerminalView) -> Unit, onTap: () -> Unit, modifier: Modifier,
) {
    val revision by session.revision.collectAsStateWithLifecycle()
    val currentModifier by rememberUpdatedState(modifierKey)
    val currentConsume by rememberUpdatedState(consume)
    val currentTap by rememberUpdatedState(onTap)
    val density = LocalDensity.current
    val font = MaterialTheme.typography.bodySmall.fontSize * (14f / 13f)
    val fontSize = with(density) { font.toPx().toInt() }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val background = MaterialTheme.colorScheme.background.toArgb()
    val cursor = MaterialTheme.colorScheme.primary.toArgb()
    // Native bookkeeping is not Compose state and belongs to this view/session binding.
    val appliedRevision = remember(session) { longArrayOf(Long.MIN_VALUE) }
    // Native TerminalView owns tap, scroll and selection; a Compose tap detector steals that stream.
    Box(modifier.padding(horizontal = 12.dp)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            TerminalView(context, null).apply {
                appliedRevision[0] = Long.MIN_VALUE
                setTerminalViewClient(TerminalViewBridge(
                    { currentTap() }, { currentModifier == TerminalModifier.CTRL },
                    { currentModifier == TerminalModifier.ALT }, { currentConsume() }))
                // The upstream XML supplied these flags; this view is created programmatically.
                isFocusable = true
                isFocusableInTouchMode = true
                // Initializes the renderer with MONOSPACE; setTypeface requires an existing renderer.
                setTextSize(fontSize)
                tag = fontSize
                setTerminalCursorBlinkerRate(0)
                contentDescription = "交互式终端，向上滚动查看历史，轻触输入，长按选择文字"
                onView(this)
            }
        }, update = { terminal ->
            val fontChanged = terminal.tag != fontSize
            if (fontChanged) {
                terminal.tag = fontSize
                terminal.setTextSize(fontSize)
            }
            val colors = session.display.emulator.mColors.mCurrentColors
            val paletteChanged = colors[TextStyle.COLOR_INDEX_FOREGROUND] != foreground ||
                colors[TextStyle.COLOR_INDEX_BACKGROUND] != background ||
                colors[TextStyle.COLOR_INDEX_CURSOR] != cursor
            if (paletteChanged) {
                colors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
                colors[TextStyle.COLOR_INDEX_BACKGROUND] = background
                colors[TextStyle.COLOR_INDEX_CURSOR] = cursor
            }
            val sessionChanged = terminal.attachSession(session.display)
            // Reading here refreshes only the AndroidView, not the toolbar or page composition.
            val outputRevision = revision
            val outputChanged = appliedRevision[0] != outputRevision
            appliedRevision[0] = outputRevision
            if (sessionChanged || outputChanged) {
                // This also invalidates and adjusts history/selection for real terminal output.
                terminal.onScreenUpdated()
            } else if (fontChanged || paletteChanged) {
                // A toolbar or palette update must not rerun output-driven scroll bookkeeping.
                terminal.invalidate()
            }
        }, onRelease = { it.setTerminalCursorBlinkerState(false, true) })
    }
}

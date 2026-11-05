package com.suixing.agent.ui.terminal

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.core.Core
import com.suixing.agent.terminal.*
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
    var closing by remember { mutableStateOf<TerminalEntry?>(null) }
    val current = entries.firstOrNull { it.info.id == selected } ?: entries.lastOrNull()
    fun open(kind: String) {
        scope.launch {
            runCatching { manager.open(kind) }.onSuccess {
                selected = it.info.id
                error = null
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(manager) {
        manager.load()
        Core.linux.load()
        if (manager.entries.value.isEmpty()) open("android")
    }
    BackHandler(onBack = onBack)
    if (newSession) AlertDialog(
        onDismissRequest = { newSession = false }, title = { Text("新建终端会话") },
        text = {
            Column {
                Text("最多同时运行 4 个会话，退出页面后继续保留。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { newSession = false; open("android") }) { Text("Android · 应用身份") }
                for (distro in LINUX_DISTRIBUTIONS) TextButton(onClick = {
                    newSession = false
                    if (Core.linux.states.value[distro.id]?.available == true) open(distro.id) else environments = true
                }) { Text(distro.title + " Linux") }
            }
        }, confirmButton = { TextButton(onClick = { newSession = false }) { Text("取消") } },
    )
    if (environments) LinuxEnvironmentsDialog(
        onDismiss = { environments = false },
        onOpen = { kind -> environments = false; open(kind) },
        onInstallTools = { distro ->
            environments = false
            scope.launch {
                runCatching {
                    val entry = manager.open(distro.id)
                    selected = entry.info.id
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
    Scaffold(topBar = { PageTopBar(title = "终端", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("应用身份", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { environments = true }) { Text("Linux 环境") }
                TextButton(onClick = { newSession = true }) { Text("＋ 新建") }
            }
            if (entries.size > 1) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(entries, key = { it.info.id }) { entry ->
                    TextButton(onClick = { selected = entry.info.id; manager.selectedId = selected }) {
                        Text(entry.info.name + if (entry.info.phase == "running") " ·" else " · 已结束",
                            color = if (entry == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
            when {
                current == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    Text("正在准备终端…", modifier = Modifier.padding(12.dp))
                }
                current.session != null -> key(current.info.id) {
                    TerminalContent(current, chatId, onStop = { closing = current }, modifier = Modifier.weight(1f))
                }
                else -> Column(Modifier.weight(1f).padding(12.dp)) {
                    Text(current.info.note ?: "会话已结束，请新建会话。")
                    TextButton(onClick = { scope.launch { manager.remove(current.info.id) } }) { Text("移除记录") }
                }
            }
        }
    }
}

@Composable
private fun TerminalContent(entry: TerminalEntry, chatId: String, onStop: () -> Unit, modifier: Modifier) {
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
    fun keyboard() {
        if (running) view?.let {
            it.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
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
    Column(modifier.fillMaxWidth()) {
        val kind = if (entry.info.kind == "android") "Android" else "Linux · 模拟 root"
        Text(kind + " · " + session.shell + " · PID " + session.pid, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        Text(if (entry.info.kind == "android") session.cwd else "/workspace → " + session.cwd,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch {
                clipboard.setText(AnnotatedString(session.transcript()))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            } }) { Text("复制") }
            TextButton(enabled = running, onClick = { requestPaste() }) { Text("粘贴") }
            TextButton(enabled = running, onClick = { session.takeOver(); scope.launch { session.interrupt() } }) { Text("中断") }
            TextButton(onClick = { session.clearOutput() }) { Text("清屏") }
            TextButton(enabled = running, onClick = onStop) { Text("停止") }
            TextButton(onClick = {
                val enable = !logEnabled
                scope.launch {
                    withContext(Dispatchers.IO) { session.log.setEnabled(enable) }
                    logEnabled = session.log.enabled
                }
            }) { Text(if (logEnabled) "日志：开" else "日志：关") }
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { session.log.clear() }; logEnabled = false }
            }) { Text("清除日志") }
        }
        TerminalControl(session, chatId)
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
private fun TerminalControl(session: TerminalSession, chatId: String) {
    val runs by Core.engine.runsFlow.collectAsStateWithLifecycle()
    val revision by session.policy.revision.collectAsStateWithLifecycle()
    val caller = runs[chatId]?.terminalCaller
    val allowed = remember(revision, caller) { caller != null && session.policy.allow(caller) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (allowed) "当前任务可以操作此终端" else "用户控制 · Agent 输入已暂停",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (allowed) TextButton(onClick = { session.takeOver() }) { Text("接管") }
        else if (caller != null && session.policy.canGrant(caller))
            TextButton(onClick = { session.policy.grantOrResume(caller) }) { Text("授权当前任务") }
    }
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
    Box(modifier.pointerInput(Unit) { detectTapGestures(onTap = { currentTap() }) }.padding(horizontal = 12.dp)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            TerminalView(context, null).apply {
                setTerminalViewClient(TerminalViewBridge(
                    { currentTap() }, { currentModifier == TerminalModifier.CTRL },
                    { currentModifier == TerminalModifier.ALT }, { currentConsume() }))
                // Initializes the renderer with MONOSPACE; setTypeface requires an existing renderer.
                setTextSize(fontSize)
                tag = fontSize
                setTerminalCursorBlinkerRate(0)
                contentDescription = "交互式终端，向上滚动查看历史，轻触输入，长按选择文字"
                onView(this)
            }
        }, update = { terminal ->
            if (terminal.tag != fontSize) {
                terminal.tag = fontSize
                terminal.setTextSize(fontSize)
            }
            val colors = session.display.emulator.mColors.mCurrentColors
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = background
            colors[TextStyle.COLOR_INDEX_CURSOR] = cursor
            terminal.attachSession(session.display)
            terminal.onScreenUpdated()
            // Reading the revision here invalidates only this native viewport.
            if (revision >= 0) terminal.invalidate()
        }, onRelease = { it.setTerminalCursorBlinkerState(false, true) })
    }
}

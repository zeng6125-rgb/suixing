package com.suixing.agent.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.suixing.agent.R
import com.suixing.agent.terminal.TerminalKeys
import com.suixing.agent.terminal.TerminalSession
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.launch

// 终端视口本身固定深色：这是「终端」而不是一张普通卡片，浅/深主题下都应保持墨底绿字的
// 终端语义，避免亮色主题下变成一张白底黑字的奇怪面板。外壳（顶栏、状态条、输入、按钮）
// 一律用 MaterialTheme.colorScheme token，跟随主题。
private val TerminalBackground = Color(0xFF101418)
private val TerminalForeground = Color(0xFFD6E0DC)
private val TerminalMuted = Color(0xFF7E9099)
private val TerminalBorder = Color(0xFF2A343B)
private val TerminalShape = RoundedCornerShape(12.dp)

/**
 * 内置交互式终端页（#61）。
 *
 * 这是 #61 PR-0 的原生 PTY 探针 + 一层最小会话所有权（单 reader / TERM→KILL→waitpid→close），
 * 不是 PR-1 的多会话 manager，也没有 VT/ANSI 仿真、Agent 接入或后台存活。键条只转发真实终端
 * 会发的字节序列；PTY 在真实 ROM 上的 TTY/Ctrl-C/resize/reap 兼容门槛**尚未真机验证**，
 * 故顶栏标注「实验性」；forkpty 失败时明确显示原因，不伪装成可用终端。离开本页即关闭会话。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { TerminalSession.open(context) }
            .onSuccess { session = it; it.start() }
            .onFailure { openError = it.message ?: it.javaClass.simpleName }
    }
    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    // session 出现前后都要有状态：未启动时视为 STARTING，启动后订阅真实状态流。
    // 用 produceState 无条件订阅，避免在 if 分支里条件调用 composable 破坏组合一致性。
    val status by produceState(TerminalSession.SessionStatus.STARTING, session) {
        val flow = session?.status ?: return@produceState
        flow.collect { value = it }
    }
    val outputText by produceState("", session) {
        val flow = session?.output ?: return@produceState
        flow.collect { value = it }
    }
    fun leave() {
        session?.close()
        onBack()
    }
    BackHandler {
        if (status == TerminalSession.SessionStatus.RUNNING) confirmLeave = true else leave()
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("关闭终端会话？") },
            text = { Text("关闭会话会终止正在运行的命令。") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; leave() }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PageTopBar(
                title = "终端",
                subtitle = "Android · 应用身份（实验性）",
                onBack = { if (status == TerminalSession.SessionStatus.RUNNING) confirmLeave = true else leave() },
                actions = {
                    CircleActionButton(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = "复制输出",
                        onClick = { if (outputText.isNotEmpty()) clipboard.setText(AnnotatedString(outputText)) },
                        enabled = outputText.isNotEmpty(),
                        containerColor = Color.Transparent,
                        shadowElevation = 0.dp,
                    )
                    CircleActionButton(
                        painter = painterResource(R.drawable.ic_trash),
                        contentDescription = "清空输出",
                        onClick = { session?.clearOutput() },
                        enabled = outputText.isNotEmpty(),
                        containerColor = Color.Transparent,
                        shadowElevation = 0.dp,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            when {
                openError != null -> ErrorCard(openError!!)
                session == null -> HintCard("正在启动会话…")
                else -> TerminalBody(session!!, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ErrorCard(reason: String) {
    Surface(
        shape = TerminalShape,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("终端启动失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text = "$reason\n\n该设备/ROM 可能不支持 forkpty（#61 PR-0 真机门槛未验证）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun TerminalBody(session: TerminalSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val output by session.output.collectAsState()
    val status by session.status.collectAsState()
    val ended by session.ended.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val keyScroll = rememberScrollState()
    val running = status == TerminalSession.SessionStatus.RUNNING

    // 只在用户本来就在底部时跟随输出，读历史时绝不把视图拽走。
    val atBottom = scroll.maxValue - scroll.value <= 8
    LaunchedEffect(output) {
        if (atBottom) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 头部事实条：诚实展示这一页到底是什么，不冒充完整终端。
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusChip(status)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "无 VT/ANSI 仿真 · 无多会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${session.shell} -i   ·   pid ${session.pid}   ·   ${session.columns}×${session.rows}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "cwd ${session.cwd}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // 视口：圆角描边 + 墨底，滚动只在视口内部。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 10.dp)
                .background(TerminalBackground, TerminalShape)
                .border(1.dp, TerminalBorder, TerminalShape)
                .padding(12.dp),
        ) {
            Text(
                text = output.ifEmpty { "（等待输出）" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.45f),
                color = if (output.isEmpty()) TerminalMuted else TerminalForeground,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
            )
        }

        ended?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 键条：转发真实终端字节，不做仿真。横向滚动，避免窄屏挤压。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .horizontalScroll(keyScroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("Tab", running) { scope.launch { session.writeBytes(TerminalKeys.Tab) } }
            KeyButton("Esc", running) { scope.launch { session.writeBytes(TerminalKeys.Esc) } }
            KeyButton("^C", running) { scope.launch { session.writeBytes(TerminalKeys.CtrlC) } }
            KeyButton("^D", running) { scope.launch { session.writeBytes(TerminalKeys.CtrlD) } }
            ArrowButton("←", R.drawable.ic_arrow_left, running) { scope.launch { session.writeBytes(TerminalKeys.Left) } }
            ArrowButton("↑", R.drawable.ic_arrow_up, running) { scope.launch { session.writeBytes(TerminalKeys.Up) } }
            ArrowButton("↓", R.drawable.ic_arrow_down, running) { scope.launch { session.writeBytes(TerminalKeys.Down) } }
            ArrowButton("→", R.drawable.ic_chevron_right, running) { scope.launch { session.writeBytes(TerminalKeys.Right) } }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            fun submit() {
                val text = input
                if (!running || text.isEmpty()) return
                input = ""
                scope.launch { session.write(text + "\n") }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("输入命令，回车执行", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                enabled = running,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            IconButton(
                onClick = { submit() },
                enabled = running && input.isNotEmpty(),
            ) {
                Icon(painter = painterResource(R.drawable.ic_arrow_down), contentDescription = "执行", modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = { scope.launch { session.interrupt() } },
                enabled = running,
            ) {
                Icon(painter = painterResource(R.drawable.ic_stop_filled), contentDescription = "Ctrl-C 中断", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(status: TerminalSession.SessionStatus) {
    val (label, color) = when (status) {
        TerminalSession.SessionStatus.STARTING -> "启动中" to MaterialTheme.colorScheme.tertiary
        TerminalSession.SessionStatus.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        TerminalSession.SessionStatus.EXITED -> "已结束" to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ArrowButton(label: String, icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painter = painterResource(icon), contentDescription = "方向键 $label", modifier = Modifier.size(18.dp))
    }
}

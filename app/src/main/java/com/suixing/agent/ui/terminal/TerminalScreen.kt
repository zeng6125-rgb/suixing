package com.suixing.agent.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.terminal.TerminalSession
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.launch

/**
 * 内置交互式终端页（#61）。
 *
 * 这是 #61 PR-0 的原生 PTY 探针 + 一层最小会话所有权（单 reader / TERM→KILL→waitpid→close），
 * 不是 PR-1 的多会话 manager，也没有 VT/ANSI 仿真、Agent 接入或后台存活。
 * PTY 在真实 ROM 上的 TTY/Ctrl-C/resize/reap 兼容门槛**尚未真机验证**，故顶栏标注「实验性」；
 * forkpty 失败时明确显示原因，不伪装成可用终端。离开本页即关闭会话。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { TerminalSession.open(context) }
            .onSuccess { session = it; it.start() }
            .onFailure { openError = it.message ?: it.javaClass.simpleName }
    }
    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PageTopBar(
                title = "终端",
                subtitle = "Android · 应用身份（实验性）",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 12.dp),
        ) {
            when {
                openError != null -> Text(
                    text = "终端启动失败：$openError\n\n该设备/ROM 可能不支持 forkpty（#61 PR-0 真机门槛未验证）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                session == null -> Text(
                    text = "正在启动会话…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> TerminalBody(session!!, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TerminalBody(session: TerminalSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val output by session.output.collectAsState()
    val status by session.status.collectAsState()
    val ended by session.ended.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    // Follow the tail as output arrives; the buffer itself is bounded in TerminalSession.
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF101418))
            .verticalScroll(scroll)
            .padding(10.dp),
    ) {
        Text(
            text = output.ifEmpty { "（等待输出）" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFD6E0DC),
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("输入命令，回车执行", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            enabled = status == TerminalSession.SessionStatus.RUNNING,
        )
        IconButton(
            onClick = {
                val text = input
                input = ""
                scope.launch { session.write(text + "\n") }
            },
            enabled = status == TerminalSession.SessionStatus.RUNNING && input.isNotEmpty(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = "执行",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = { scope.launch { session.interrupt() } },
            enabled = status == TerminalSession.SessionStatus.RUNNING,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stop_filled),
                contentDescription = "Ctrl-C 中断",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

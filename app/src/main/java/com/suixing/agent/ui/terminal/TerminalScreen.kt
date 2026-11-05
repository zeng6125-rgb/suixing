package com.suixing.agent.ui.terminal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.R
import com.suixing.agent.terminal.TERMINAL_KEY_BAR
import com.suixing.agent.terminal.TerminalKey
import com.suixing.agent.terminal.TerminalModifier
import com.suixing.agent.terminal.TerminalSession
import com.suixing.agent.terminal.canSubmit
import com.suixing.agent.terminal.copyFeedback
import com.suixing.agent.terminal.isModifier
import com.suixing.agent.terminal.keyBytes
import com.suixing.agent.terminal.keyLabel
import com.suixing.agent.terminal.keySpoken
import com.suixing.agent.terminal.keysEnabled
import com.suixing.agent.terminal.modifierFor
import com.suixing.agent.terminal.promptText
import com.suixing.agent.terminal.sessionStatusLabel
import com.suixing.agent.terminal.shellLabel
import com.suixing.agent.terminal.terminalSize
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.suixingClickable
import kotlinx.coroutines.launch

// 12dp 是这一页唯一的呼吸节奏；6dp 只用于一行文字内部。
private val Gap = 12.dp
private val StripGap = 6.dp
private val TouchTarget = 40.dp
private val KeyMinHeight = 40.dp
private val Glyph = 16.dp

// 输入行与键条用**固定**行盒：中英文 fallback 字体的字形盒高度不同，若行高随文字
// 内容算（sp 乘法 / 字体 ascent），先英文后中文会把 BasicTextField 撑高一点。固定
// lineHeight + 固定 height 之后，行盒与内容无关，混排不再抖。
private val LineBox = 24.dp
private val LineHeightSp = 24.sp
private val NoFontPad = PlatformTextStyle(includeFontPadding = false)
private val CenteredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * 内置交互式终端页（#61）。
 *
 * 版式照参考图：顶栏、一段 Android 标识、一条等宽提示符行、大片空白、底部发丝线 + 键条。
 * 没有深色视口、没有卡片、没有辉光——终端的样子由等宽字承担。
 *
 * 仍是 #61 PR-0 的原生 PTY 探针：App UID 的 /system/bin/sh -i，单会话，不做 VT/ANSI
 * 仿真，不接 Agent，没有 Debian 环境。PTY 在真实 ROM 上的兼容门槛**尚未真机验证**。
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
    DisposableEffect(Unit) { onDispose { session?.close() } }

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
    fun copyOutput() {
        val hasOutput = outputText.isNotEmpty()
        if (hasOutput) clipboard.setText(AnnotatedString(outputText))
        Toast.makeText(context.applicationContext, copyFeedback(hasOutput), Toast.LENGTH_SHORT).show()
    }
    fun requestLeave() {
        if (status == TerminalSession.SessionStatus.RUNNING) confirmLeave = true else leave()
    }
    BackHandler { requestLeave() }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("关闭终端会话？") },
            text = { Text("关闭会话会终止正在运行的命令。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; leave() }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PageTopBar(title = "终端", onBack = { requestLeave() }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            val body = session
            when {
                openError != null -> ErrorNote(openError!!, Modifier.weight(1f))
                body == null -> Text(
                    text = "正在启动会话…",
                    style = transcriptStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = Gap),
                )
                else -> TerminalBody(
                    session = body,
                    hasOutput = outputText.isNotEmpty(),
                    onCopy = { copyOutput() },
                    modifier = Modifier.weight(1f),
                )
            }
            // Scaffold 的 padding 已经含系统栏，这里不再叠一次 navigationBarsPadding：
            // 双份避让会在键条下面留一大截空白（用户截图里那块）。
        }
    }
}

@Composable
private fun TerminalBody(
    session: TerminalSession,
    hasOutput: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val output by session.output.collectAsState()
    val status by session.status.collectAsState()
    val ended by session.ended.collectAsState()
    val running = keysEnabled(status)
    val shell = remember(session.shell) { shellLabel(session.shell) }
    var input by remember { mutableStateOf("") }
    var pendingModifier by remember { mutableStateOf(TerminalModifier.NONE) }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    fun submit() {
        val text = input
        if (!canSubmit(text, running)) return
        input = ""
        scope.launch { session.writeBytes((text + "\n").toByteArray(Charsets.UTF_8)) }
    }
    fun send(bytes: ByteArray) {
        if (!running) return
        scope.launch { session.writeBytes(bytes) }
    }
    fun onKey(key: TerminalKey) {
        if (isModifier(key)) {
            // 粘滞：再点一次同一个修饰键就取消。
            val next = modifierFor(key)
            pendingModifier = if (pendingModifier == next) TerminalModifier.NONE else next
            return
        }
        send(keyBytes(key, pendingModifier))
        pendingModifier = TerminalModifier.NONE
    }

    // 视口尺寸 → PTY 列数/行数。等宽字的字符宽约 0.6 × 字号，行高就是固定行盒。
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val textStyle = transcriptStyle()
    val cellWidthPx = with(density) { (textStyle.fontSize.value * 0.6f).sp.toPx() }
    val cellHeightPx = with(density) { LineBox.toPx() }
    LaunchedEffect(viewport.width, viewport.height) {
        if (viewport.width <= 0) return@LaunchedEffect
        val (columns, rows) = terminalSize(
            widthPx = viewport.width.toFloat(),
            heightPx = viewport.height.toFloat(),
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
        )
        session.resize(columns, rows)
    }

    // 只在视图本来就贴底时跟随输出；读历史时绝不把视图拽走。
    val atBottom = scroll.maxValue - scroll.value <= 8
    LaunchedEffect(output) {
        if (atBottom) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TopStrip(
            hasOutput = hasOutput,
            running = running,
            onCopy = onCopy,
            onInterrupt = { scope.launch { session.interrupt() } },
            onClear = { session.clearOutput() },
        )
        StatusNote(running = running, status = status, ended = ended)
        // 转录占满剩余空间、按视口宽度折行（不再横向滚动，就不会有一行冲出屏幕）。
        // 输入行紧跟在转录之后：输出自旧到新往下流，新提示符落在最后一行下面，
        // 才是真终端的顺序，而不是把 `sh $` 顶在最上面、看起来「命令由下到上」。
        Text(
            text = output,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewport = it }
                .verticalScroll(scroll)
                .padding(horizontal = Gap),
        )
        PromptLine(
            value = input,
            onValueChange = { input = it },
            prefix = promptText(shell, input, running),
            enabled = running,
            focus = focus,
            onExec = { submit() },
        )
        Hairline()
        KeyStrip(
            enabled = running,
            activeModifier = pendingModifier,
            onKey = { onKey(it) },
            onKeyboard = { runCatching { focus.requestFocus() } },
        )
    }
}

/**
 * 顶部：只留 Android 一段纯标识（本页只有一个 App UID 的 PTY，没有第二个环境可切），
 * 右侧三个动作都是真有的能力——复制、Ctrl-C 中断、清空转录。
 */
@Composable
private fun TopStrip(
    hasOutput: Boolean,
    running: Boolean,
    onCopy: () -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gap, vertical = Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Android",
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = scheme.onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = Gap, vertical = 7.dp),
        )
        Spacer(Modifier.weight(1f))
        FlatAction(R.drawable.ic_copy, "复制输出", enabled = hasOutput, onClick = onCopy)
        FlatAction(R.drawable.ic_stop_filled, "Ctrl-C 中断正在运行的命令", enabled = running, onClick = onInterrupt)
        FlatAction(R.drawable.ic_trash, "清空输出", enabled = hasOutput, onClick = onClear)
    }
}

/** 无底色小图标：40dp 热区，禁用只降墨色不动尺寸。 */
@Composable
private fun FlatAction(icon: Int, spoken: String, enabled: Boolean, onClick: () -> Unit) =
    CircleActionButton(
        icon = ImageVector.vectorResource(icon),
        contentDescription = spoken,
        onClick = onClick,
        enabled = enabled,
        size = TouchTarget,
        iconSize = Glyph,
        containerColor = Color.Transparent,
        shadowElevation = 0.dp,
    )

/**
 * 提示符行：等宽前缀 + 行内输入 + 方块光标。
 * 固定 24dp 高、固定 24sp 行高：中英文 fallback 字体不会改变行盒尺寸。
 */
@Composable
private fun PromptLine(
    value: String,
    onValueChange: (String) -> Unit,
    prefix: String,
    enabled: Boolean,
    focus: FocusRequester,
    onExec: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val style = transcriptStyle()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(LineBox)
            .padding(horizontal = Gap)
            .focusRequester(focus)
            .semantics { contentDescription = "终端输入行" },
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(scheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onExec() }),
        visualTransformation = VisualTransformation.None,
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(LineBox)) {
                Text(text = prefix, style = style, color = scheme.onSurface, maxLines = 1)
                Box(Modifier.weight(1f)) { inner() }
            }
        },
    )
}

/** 运行中不占位；启动中/已结束各给一行很淡的说明。 */
@Composable
private fun StatusNote(running: Boolean, status: TerminalSession.SessionStatus, ended: String?) {
    if (running) return
    Text(
        text = listOfNotNull(sessionStatusLabel(status), ended).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gap).padding(bottom = Gap / 2),
    )
}

/** forkpty 失败：原因照原样给出，并说明这是设备/ROM 的门槛，不伪装成可用终端。 */
@Composable
private fun ErrorNote(reason: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Gap)
            .background(scheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(Gap),
    ) {
        Text("终端启动失败", style = MaterialTheme.typography.titleSmall, color = scheme.onErrorContainer)
        Text(reason, style = transcriptStyle(), color = scheme.onErrorContainer, modifier = Modifier.padding(top = 6.dp))
        Text(
            text = "该设备/ROM 可能不支持 forkpty（#61 PR-0 真机门槛未验证），换一台设备或系统版本再试。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onErrorContainer.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 键条上方的发丝线。 */
@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)))
}

/**
 * 键条：参考图顺序 Esc · Tab · Ctrl · Alt · ← ↑ ↓ → · 键盘图标。
 * Ctrl/Alt 是粘滞修饰键，点亮后作用于下一次按键，按完自动熄灭。
 */
@Composable
private fun KeyStrip(
    enabled: Boolean,
    activeModifier: TerminalModifier,
    onKey: (TerminalKey) -> Unit,
    onKeyboard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Gap, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(StripGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in TERMINAL_KEY_BAR) {
            val active = when (key) {
                TerminalKey.CTRL -> activeModifier == TerminalModifier.CTRL
                TerminalKey.ALT -> activeModifier == TerminalModifier.ALT
                else -> false
            }
            KeyPill(label = keyLabel(key), spoken = keySpoken(key), enabled = enabled, active = active) { onKey(key) }
        }
        // 键盘图标：把焦点还给输入行让系统 IME 起来。不是悬浮球那根羽毛。
        KeyPill(label = null, spoken = "显示键盘", enabled = true, active = false, onClick = onKeyboard)
    }
}

/** 一枚键：白底圆角、黑粗体；修饰键点亮时用主色描出，一眼看得出还挂着。 */
@Composable
private fun KeyPill(label: String?, spoken: String, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val ink = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .heightIn(min = KeyMinHeight)
            .widthIn(min = 40.dp)
            .clip(shape)
            .background(if (active) scheme.primaryContainer else scheme.surface)
            .suixingClickable(shape, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (label == null) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_keyboard),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(Glyph),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (active) scheme.onPrimaryContainer else ink,
                maxLines = 1,
            )
        }
    }
}

/** 等宽样式：固定行高，中英混排不改变行盒；字号跟「字号」设置档联动。 */
@Composable
private fun transcriptStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) {
        body.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = LineHeightSp,
            platformStyle = NoFontPad,
            lineHeightStyle = CenteredLine,
        )
    }
}

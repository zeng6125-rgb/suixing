package com.suixing.agent.ui.terminal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
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
import com.suixing.agent.terminal.sessionStatusLabel
import com.suixing.agent.terminal.splitTerminalOutput
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

// 保留完整行高，输入行与转录沿同一基线排列；系统字号放大时行盒一起放大。
private val NoFontPad = PlatformTextStyle(includeFontPadding = false)
private val CenteredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
    mode = LineHeightStyle.Mode.Fixed,
)

/**
 * 内置交互式终端页（#61）。
 *
 * 顶栏、Android 标识、从上向下排列的命令与输出，底部发丝线和快捷键。
 * 当前输入接在 shell 输出的最后一行，随历史滚动，不在底部常驻。
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
                .consumeWindowInsets(padding)
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
            // 系统栏 padding 先消费，IME 只补剩余高度，键条下不重复留导航栏空隙。
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
    var input by remember { mutableStateOf("") }
    var pendingModifier by remember { mutableStateOf(TerminalModifier.NONE) }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val showKeyboard = remember(running, focus, keyboard, scroll, scope) {
        {
            if (running) {
                runCatching { focus.requestFocus() }.onSuccess {
                    keyboard?.show()
                    scope.launch { scroll.scrollTo(scroll.maxValue) }
                }
            }
        }
    }

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
    val cellHeightPx = with(density) { textStyle.lineHeight.toPx() }
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

    // 等布局更新了滚动范围再跟随；输出与输入折行共用这条路径，读历史时保持位置。
    LaunchedEffect(scroll) {
        var previousMaximum = scroll.maxValue
        snapshotFlow { scroll.maxValue }.collect { maximum ->
            val following = previousMaximum - scroll.value <= 8
            if (following && !scroll.isScrollInProgress) scroll.scrollTo(maximum)
            previousMaximum = maximum
        }
    }
    val transcript = remember(output) { splitTerminalOutput(output) }
    val scheme = MaterialTheme.colorScheme
    val historyText = remember(transcript.history, scheme.primary, scheme.onSurface) {
        transcript.history?.let { terminalStyledText(it, scheme.primary, scheme.onSurface) }
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
        // 只有视口占满剩余空间；历史与当前输入都从视口顶部排起，不用权重把提示符推到底。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 放在滚动内容外面，整个视口的空白（含左右留白）都能唤起输入。
                .pointerInput(showKeyboard) { detectTapGestures(onTap = { showKeyboard() }) }
                .padding(horizontal = Gap)
                .onSizeChanged { viewport = it }
                .verticalScroll(scroll),
        ) {
            historyText?.let { history ->
                Text(
                    text = history,
                    style = textStyle,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (running) {
                PromptLine(
                    value = input,
                    onValueChange = { input = it },
                    prefix = transcript.currentLine,
                    enabled = true,
                    focus = focus,
                    onExec = { submit() },
                )
            } else if (transcript.currentLine.isNotEmpty()) {
                Text(
                    text = transcript.currentLine,
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Hairline()
        KeyStrip(
            enabled = running,
            activeModifier = pendingModifier,
            onKey = { onKey(it) },
            onKeyboard = showKeyboard,
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
 * shell 当前行与可编辑输入共用排版，长命令按视口宽度折行。
 * 提示符由视觉转换前置，光标只能编辑其后的输入。
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
    val lineBox = with(LocalDensity.current) { style.lineHeight.toDp() }
    val promptTransformation = remember(prefix, scheme.primary, scheme.onSurface) {
        TerminalInputTransformation(prefix, scheme.primary, scheme.onSurface)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = lineBox)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onExec()
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = "终端输入行" },
        enabled = enabled,
        singleLine = false,
        textStyle = style.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onExec() }),
        visualTransformation = promptTransformation,
    )
}

/** 显示真实 PTY 当前行，同时把编辑偏移限制在本地输入内。 */
internal class TerminalInputTransformation(
    private val prefix: String,
    private val promptColor: Color = Color.Unspecified,
    private val commandColor: Color = Color.Unspecified,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = terminalStyledText(prefix, promptColor, commandColor) + text,
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = prefix.length + offset
            override fun transformedToOriginal(offset: Int): Int =
                (offset - prefix.length).coerceIn(0, text.length)
        },
    )
}

/** 命令保持正文色、提示符用主题色；只加样式，不改 PTY 文本或编辑偏移。 */
internal fun terminalStyledText(text: String, promptColor: Color, commandColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (text.startsWith("$ ", start) || text.startsWith("> ", start)) {
                addStyle(SpanStyle(color = commandColor), start, end)
                addStyle(SpanStyle(color = promptColor), start, start + 2)
            }
            start = end + 1
        }
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
            .padding(horizontal = Gap),
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
        KeyPill(label = null, spoken = "显示键盘", enabled = enabled, active = false, onClick = onKeyboard)
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

/** 标准档 14sp 等宽字 / 22sp 完整行盒，跟随应用字号和系统缩放。 */
@Composable
private fun transcriptStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) {
        body.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = body.fontSize * (14f / 13f),
            lineHeight = body.fontSize * (22f / 13f),
            platformStyle = NoFontPad,
            lineHeightStyle = CenteredLine,
        )
    }
}

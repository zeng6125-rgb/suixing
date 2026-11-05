package com.suixing.agent.ui.terminal

import android.os.Process
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.R
import com.suixing.agent.terminal.TerminalKey
import com.suixing.agent.terminal.TerminalNative
import com.suixing.agent.terminal.TerminalSession
import com.suixing.agent.terminal.TERMINAL_KEY_COLUMNS
import com.suixing.agent.terminal.bytes
import com.suixing.agent.terminal.canSubmit
import com.suixing.agent.terminal.copyFeedback
import com.suixing.agent.terminal.keysEnabled
import com.suixing.agent.terminal.sessionFacts
import com.suixing.agent.terminal.sessionStatusLabel
import com.suixing.agent.terminal.shellLabel
import com.suixing.agent.terminal.terminalKeyRows
import com.suixing.agent.terminal.workspaceLabel
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.navBarContentPadding
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.launch

// 视口是这一页**唯一**固定的深色面：它是终端，不是一张普通卡片。浅色主题下把视口刷成白底黑字
// 就不再是终端（终端的语义就是墨底亮字）；深色主题下它比卡片再暗一档，于是屏幕里是「页面 /
// 卡片 / 视口」三级，而不是一整块灰。其余外壳（状态条、会话条、键帽、命令行、按钮）全部读
// MaterialTheme.colorScheme，跟随主题，浅深两套都成立。
private val ViewportInk = Color(0xFF0D1114)
private val ViewportRule = Color(0xFF243138)
private val ViewportPrompt = Color(0xFF7FE3C9)
private val ViewportText = Color(0xFFD3DED8)
private val ViewportMuted = Color(0xFF6C7B80)
private val ViewportAccent = Color(0xFFF0C070)

// 12dp 是这一页的呼吸节奏（卡片内边距、块间距）；6/8dp 只出现在一个元素内部。
private val PageGutter = 12.dp
private val CardPadding = 12.dp
private val ChunkGap = 8.dp
private val KeyGap = 8.dp
private val KeyMinHeight = 44.dp
private val KeyGlyph = 16.dp
private val ChipGlyph = 13.dp

// 等宽正文的两个补偿（与思考块同一写法）：去掉字体自带上下 padding，再去掉首行分摊的半截行距，
// 否则转录首行会凭空多出 3dp、和视口内边距对不上。
private val NoFontPad = PlatformTextStyle(includeFontPadding = false)
private val FirstLineTopTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.FirstLineTop,
)

/**
 * 内置交互式终端页（#61）。
 *
 * 这是 #61 PR-0 的原生 PTY 探针 + 一层最小会话所有权（单 reader / TERM→KILL→waitpid→close），
 * 不是 PR-1 的多会话 manager，也没有 VT/ANSI 仿真、Agent 接入或后台存活。键条只转发真实终端
 * 会发的字节序列；PTY 在真实 ROM 上的 TTY/Ctrl-C/resize/reap 兼容门槛**尚未真机验证**，
 * 故顶栏标注「实验性」；forkpty 失败时明确显示原因，不伪装成可用终端。离开本页即关闭会话。
 *
 * 版式自上而下：状态条（身份 / 状态 / 工作区）→ 视口卡（内嵌会话条 + 等宽转录 + 空态）→
 * 键帽三列 → 命令行。视口吸走剩余高度，所以键盘弹起时先压转录，命令行始终留在屏上。
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
    fun copyOutput() {
        val hasOutput = outputText.isNotEmpty()
        if (hasOutput) clipboard.setText(AnnotatedString(outputText))
        Toast.makeText(context.applicationContext, copyFeedback(hasOutput), Toast.LENGTH_SHORT).show()
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
                subtitle = "Android · 应用身份 · 实验性",
                onBack = {
                    if (status == TerminalSession.SessionStatus.RUNNING) confirmLeave = true else leave()
                },
                actions = {
                    // 复制与清空只针对已显示出来的转录（清空纯 UI 动作，PTY 与子进程不受影响）。
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_copy),
                        contentDescription = "复制输出",
                        onClick = { copyOutput() },
                        enabled = outputText.isNotEmpty(),
                        containerColor = Color.Transparent,
                        shadowElevation = 0.dp,
                    )
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_trash),
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
                .padding(horizontal = PageGutter),
        ) {
            SessionFactsCard(status = status, openError = openError)
            when {
                openError != null -> OpenErrorCard(openError!!, Modifier.weight(1f))
                session == null -> StartingCard(Modifier.weight(1f))
                else -> TerminalBody(session!!, Modifier.weight(1f))
            }
            Spacer(Modifier.height(navBarContentPadding(extra = 0.dp).calculateBottomPadding()))
        }
    }
}

/**
 * 状态条：这一页是什么（身份）、现在能不能打字（状态）、以及它跑在哪（工作区）。
 *
 * 事实排成 chip，不是一段等宽小字：身份与工作区各占一行，工作区超长省略——绝对路径不会再把
 * 这一块撑成三行灰字墙。最后一行是这一页的诚实声明，全页只此一处。
 */
@Composable
private fun SessionFactsCard(status: TerminalSession.SessionStatus, openError: String?) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 身份只报 App 自己的 UID：这一页没有 root、也没有 Agent 通道。
    val uid = remember { runCatching { Process.myUid().toString() }.getOrDefault("?") }
    // 打不开时连工作区都不编造：失败卡片自己说原因。
    val cwd = remember(context, openError) {
        if (openError != null) {
            null
        } else {
            runCatching {
                workspaceLabel(
                    cwd = TerminalNative.appShellCwd(context),
                    externalRoot = context.getExternalFilesDir(null),
                )
            }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftIcon(ImageVector.vectorResource(R.drawable.ic_terminal), size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "应用身份终端",
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "以 App UID 运行 · 不申请 root",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(status)
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(ChunkGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FactChip(icon = R.drawable.ic_id_card, text = "App UID $uid", spoken = "应用身份 UID $uid")
                FactChip(
                    icon = R.drawable.ic_console_feather,
                    text = shellLabel(TerminalNative.APP_SHELL),
                    spoken = "shell ${shellLabel(TerminalNative.APP_SHELL)} -i",
                    enabled = openError == null,
                )
            }

            Row(
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChunkGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FactChip(
                    icon = R.drawable.ic_folder,
                    text = cwd ?: "workspace 不可用",
                    spoken = "工作区 ${cwd ?: "不可用"}",
                    enabled = cwd != null,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionRule(modifier = Modifier.padding(top = 10.dp))
            Text(
                text = "无 VT/ANSI 仿真 · 无多会话 · 无 Agent 接入 · 实验性",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 一个事实 chip；[enabled] 为 false 时（打不开 / 工作区缺失）降透明度，读起来仍是 disabled。 */
@Composable
private fun FactChip(
    icon: Int,
    text: String,
    spoken: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(ChipGlyph),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一处 1dp 分隔线（outlineVariant）：状态条里分区，不额外造一种颜色。 */
@Composable
private fun SectionRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

@Composable
private fun StatusChip(status: TerminalSession.SessionStatus, modifier: Modifier = Modifier) {
    val tone = when (status) {
        TerminalSession.SessionStatus.STARTING -> Tone.INFO
        TerminalSession.SessionStatus.RUNNING -> Tone.OK
        TerminalSession.SessionStatus.EXITED -> Tone.OFF
    }
    val color = tone.color(LocalDarkTheme.current)
    val label = sessionStatusLabel(status)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "会话状态：$label"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * 「正在启动会话…」：同一个视口壳，里面只有一行状态，于是启动期不是一块空白，也没有换页感。
 * 外层 Column 是必要的：`weight(1f)` 只有直接躺在 Column 里的那个节点能吃到，把 weight
 * 塞进 TerminalChrome 的 modifier 是无效的。
 */
@Composable
private fun StartingCard(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        TerminalChrome(Modifier.fillMaxWidth().weight(1f)) {
            ChromeText(
                text = "正在启动会话…",
                color = ViewportMuted,
                style = viewportTextStyle(),
            )
        }
    }
}

/** forkpty 失败：原因照原样给出，并说明这是设备/ROM 的门槛，不伪装成可用终端。 */
@Composable
private fun OpenErrorCard(reason: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = ChunkGap),
        shape = MaterialTheme.shapes.large,
        color = scheme.errorContainer,
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(CardPadding)) {
            Text(
                text = "终端启动失败",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onErrorContainer,
            )
            Text(
                text = reason,
                style = MonoStyle,
                color = scheme.onErrorContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "该设备/ROM 可能不支持 forkpty（#61 PR-0 真机门槛未验证），换一台设备或系统版本再试。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 会话真身：视口 + 键帽 + 命令行。
 *
 * 视口是唯一吸高度的块；键帽与命令行按内容固定，所以键盘弹起时被压的是转录而不是输入行。
 */
@Composable
private fun TerminalBody(session: TerminalSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val output by session.output.collectAsState()
    val status by session.status.collectAsState()
    val ended by session.ended.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val running = keysEnabled(status)
    val shell = remember(session.shell) { shellLabel(session.shell) }
    val facts = remember(session) { sessionFacts(session) }

    // 只在用户本来就在底部时跟随输出，读历史时绝不把视图拽走。
    val atBottom = scroll.maxValue - scroll.value <= 8
    LaunchedEffect(output) {
        if (atBottom) scroll.animateScrollTo(scroll.maxValue)
    }

    fun submit() {
        val text = input
        if (!canSubmit(text, running)) return
        input = ""
        scope.launch { session.write(text + "\n") }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TerminalChrome(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = ChunkGap),
            prompt = viewportPrompt(running, ended != null, shell),
            facts = facts,
            message = ended,
        ) {
            if (output.isEmpty()) {
                EmptyViewport(running = running, shell = shell)
            } else {
                // fillMaxSize 让转录撑满视口（短转录时滚动状态的 maxValue 就是 0，底部判定
                // 才成立）；横向滚动只给超宽行（长路径、宽表格）用。两个滚动轴挂在同一个
                // 节点上是有意的：外面再套一层会多吃一遍尺寸约束。
                Text(
                    text = output,
                    style = viewportTextStyle(),
                    color = ViewportText,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScroll)
                        .verticalScroll(scroll),
                )
            }
        }
        KeyBar(enabled = running) { key ->
            scope.launch { session.writeBytes(key.bytes()) }
        }
        CommandLine(
            value = input,
            onValueChange = { input = it },
            prompt = if (running) "$shell $" else "$shell",
            // 提示符只在能打字时带上「$」；会话结束后降为裸 shell 名，禁用态由 BasicTextField 自己变灰。
            enabled = running,
            canSubmit = canSubmit(input, running),
            onExec = { submit() },
            onInterrupt = { scope.launch { session.interrupt() } },
        )
    }
}

private data class PromptLine(val text: String)

/** 视口提示符：只有运行中才带一个强调色的「›」，另两种状态用中性的省略号/叉。 */
private fun viewportPrompt(running: Boolean, exited: Boolean, shell: String): PromptLine = when {
    running -> PromptLine("$shell ›")
    exited -> PromptLine("$shell ×")
    else -> PromptLine("$shell …")
}

/**
 * 终端外壳：卡片形状 + 视口墨底 + 内描边（一层压在外描边里侧的发丝线，制造"玻璃内沿"的
 * 层次感，而不是靠阴影——README 不用阴影）+ 顶部一枚很淡的强调色辉光。
 *
 * [prompt] 是终端提示符，[facts] 是 pid / 尺寸，[message] 是结束原因；三者都在视口自己的
 * 细条里，于是"终端里的一条状态行"和"终端里的正文"是同一件事的两种字号，而不是两块拼贴。
 */
@Composable
private fun TerminalChrome(
    modifier: Modifier = Modifier,
    prompt: PromptLine = PromptLine("…"),
    facts: String? = null,
    message: String? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    // 左上角一枚很淡的强调色辉光：终端玻璃上的一点点反光。用渐变而不是投影——README 不用阴影，
    // 层次靠底色与发丝线。半径是像素常量、与卡片尺寸无关，所以转录滚动时它不会跟着变。
    val glow = remember {
        Brush.radialGradient(
            colors = listOf(ViewportPrompt.copy(alpha = 0.14f), Color.Transparent),
            center = Offset.Zero,
            radius = 520f,
        )
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = ViewportInk,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(glow)
                // 内沿发丝线：外描边是主题色（卡片在浅色主题里也有边），内沿是视口自己的墨绿线，
                // 两层叠出「玻璃内壁」的厚度，而不是一圈普通边框。
                .border(1.dp, ViewportRule, shape)
                .padding(horizontal = CardPadding, vertical = 10.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeText(
                        text = "❯",
                        color = ViewportPrompt,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(6.dp))
                    ChromeText(
                        text = prompt.text,
                        color = ViewportPrompt,
                        style = chromeMono(),
                        weight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (facts != null) {
                        ChromeText(
                            text = facts,
                            color = ViewportMuted,
                            style = chromeMono(11.sp),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp, bottom = 9.dp)
                        .height(1.dp)
                        .background(ViewportRule),
                )
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                if (message != null) {
                    ChromeText(
                        text = message,
                        color = ViewportAccent,
                        style = chromeMono(11.sp),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** 视口内的空态：写出下一步该做什么，而不是留一句「等待输出」。 */
@Composable
private fun EmptyViewport(running: Boolean, shell: String) {
    Column {
        ChromeText(
            text = "会话已就绪，等第一条命令。",
            color = ViewportText,
            style = viewportTextStyle(),
        )
        Spacer(Modifier.height(10.dp))
        ChromeText(
            text = "在下面的命令行里输入，回车执行；Tab / Ctrl-C / 方向键在键帽上。",
            color = ViewportMuted,
            style = chromeMono(),
        )
        Spacer(Modifier.height(6.dp))
        ChromeText(
            text = if (running) {
                "工作区是应用自己的外部目录，$shell 以 App UID 运行。"
            } else {
                "会话尚未运行。"
            },
            color = ViewportMuted,
            style = chromeMono(),
        )
    }
}

/** 视口里的文字：等宽、跟随字号档，行距按 1.5 给（等宽字挤在一起最难读）。 */
@Composable
private fun ChromeText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
) {
    Text(
        text = text,
        style = if (weight != null) style.copy(fontWeight = weight) else style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun viewportTextStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) {
        body.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = body.fontSize,
            lineHeight = body.fontSize * 1.5f,
            platformStyle = NoFontPad,
            lineHeightStyle = FirstLineTopTrim,
        )
    }
}

@Composable
private fun chromeMono(size: TextUnit = 12.sp): TextStyle {
    return remember(size) {
        MonoStyle.copy(
            fontSize = size,
            lineHeight = size * 1.5f,
            platformStyle = NoFontPad,
            lineHeightStyle = FirstLineTopTrim,
        )
    }
}

/**
 * 键帽条：三列键帽，等宽等高、可换行（窄屏不压缩触控目标）。字节序列全部来自
 * [TerminalKey.bytes]，这里只负责样子；[enabled] 是唯一开关（RUNNING 才可点）。
 */
@Composable
private fun KeyBar(enabled: Boolean, onKey: (TerminalKey) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ChunkGap)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(KeyGap),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        for (row in terminalKeyRows()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                for (key in row) {
                    KeyCap(key = key, enabled = enabled, onClick = { onKey(key) }, modifier = Modifier.weight(1f))
                }
                // 末行不满三列时用空位补齐，键帽宽度才不会跟着「这一行有几个键」跳；
                // 空位之间同样隔一个 gap，否则末两格会比上一行宽出半个 gap。
                repeat(TERMINAL_KEY_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                    if (it < TERMINAL_KEY_COLUMNS - row.size - 1) Spacer(Modifier.width(KeyGap))
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: TerminalKey, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = keyLabel(key)
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .heightIn(min = KeyMinHeight)
            .clip(shape)
            .background(if (enabled) scheme.surfaceContainerHighest else scheme.surfaceContainerLow)
            .suixingClickable(shape, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = keySpoken(key) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
        when (key) {
            TerminalKey.LEFT, TerminalKey.UP, TerminalKey.DOWN, TerminalKey.RIGHT -> Icon(
                imageVector = ImageVector.vectorResource(keyIcon(key)),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(KeyGlyph),
            )
            else -> Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

private fun keyLabel(key: TerminalKey): String = when (key) {
    TerminalKey.TAB -> "Tab"
    TerminalKey.ESC -> "Esc"
    TerminalKey.CTRL_C -> "^C"
    TerminalKey.CTRL_D -> "^D"
    TerminalKey.LEFT -> "←"
    TerminalKey.UP -> "↑"
    TerminalKey.DOWN -> "↓"
    TerminalKey.RIGHT -> "→"
}

private fun keySpoken(key: TerminalKey): String = when (key) {
    TerminalKey.TAB -> "Tab 键"
    TerminalKey.ESC -> "Esc 键"
    TerminalKey.CTRL_C -> "Ctrl-C"
    TerminalKey.CTRL_D -> "Ctrl-D"
    TerminalKey.LEFT -> "方向键 左"
    TerminalKey.UP -> "方向键 上"
    TerminalKey.DOWN -> "方向键 下"
    TerminalKey.RIGHT -> "方向键 右"
}

private fun keyIcon(key: TerminalKey): Int = when (key) {
    TerminalKey.LEFT -> R.drawable.ic_arrow_left
    TerminalKey.UP -> R.drawable.ic_arrow_up
    TerminalKey.DOWN -> R.drawable.ic_arrow_down
    TerminalKey.RIGHT -> R.drawable.ic_chevron_right
    else -> R.drawable.ic_circle
}

/**
 * 命令行：一枚等宽提示符前缀 + 无边框输入 + 执行 / 中断两枚圆钮。
 *
 * 提示符画在 BasicTextField 的 decorationBox 里：它与输入共用基线、随焦点态一起着色，整行读起来
 * 就是终端的一行 `sh $ ▮`，而不是「标签 + 表单」。能否提交由 [canSubmit] 决定（RUNNING 且有字），
 * 中断按钮只受 [enabled] 管（RUNNING 即可）。
 */
@Composable
private fun CommandLine(
    value: String,
    onValueChange: (String) -> Unit,
    prompt: String,
    enabled: Boolean,
    canSubmit: Boolean,
    onExec: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val fieldText = promptInputStyle()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = ChunkGap),
        shape = shape,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = KeyMinHeight)
                    .semantics { contentDescription = "命令输入行" },
                enabled = enabled,
                singleLine = true,
                textStyle = fieldText,
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onGo = { onExec() }),
                visualTransformation = VisualTransformation.None,
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = prompt,
                            style = fieldText.copy(color = scheme.primary.copy(alpha = 0.85f)),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(vertical = 10.dp),
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = "输入命令，回车执行",
                                    style = fieldText.copy(color = scheme.onSurfaceVariant.copy(alpha = 0.75f)),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    }
                },
            )
            CircleActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_arrow_down),
                contentDescription = "执行命令",
                onClick = onExec,
                enabled = canSubmit,
                size = KeyMinHeight,
                iconSize = 18.dp,
                containerColor = if (canSubmit) scheme.primary else scheme.surfaceContainerHighest,
                contentColor = if (canSubmit) scheme.onPrimary else scheme.onSurfaceVariant,
                shadowElevation = 0.dp,
            )
            CircleActionButton(
                icon = ImageVector.vectorResource(R.drawable.ic_stop_filled),
                contentDescription = "Ctrl-C 中断",
                onClick = onInterrupt,
                enabled = enabled,
                size = KeyMinHeight,
                iconSize = 18.dp,
                containerColor = scheme.surfaceContainerHighest,
                contentColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f),
                shadowElevation = 0.dp,
            )
        }
    }
}

/**
 * 命令行文字的样式：等宽、字号跟 bodySmall 走（于是「字号」档整体联动），颜色留空，
 * 由 BasicTextField 的 `enabled` 自己决定（禁用态会自己降透明度）。
 */
@Composable
private fun promptInputStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) { body.copy(fontFamily = FontFamily.Monospace) }
}

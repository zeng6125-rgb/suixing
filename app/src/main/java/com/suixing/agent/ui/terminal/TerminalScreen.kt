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
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)))
}

/**
 * 键条：参考图顺序 Esc · Tab · Ctrl · Alt · ← ↑ ↓ → · 键盘图标。
 * Ctrl/Alt 是粘滞修饰键，点亮后作用于下一次按键，按完自动熄灭。
 */
@Composable
internal fun KeyStrip(
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

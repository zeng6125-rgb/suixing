package com.suixing.agent.terminal

import java.io.File

/**
 * 键条上真正会用到的键。参考图的顺序：Esc · Tab · Ctrl · Alt · ← ↑ ↓ →。
 * Ctrl/Alt 是**粘滞修饰键**（点亮后作用于下一次按键），不是直接发某个字节的假按钮。
 */
internal enum class TerminalKey { ESC, TAB, CTRL, ALT, LEFT, UP, DOWN, RIGHT }

/** 键条绘制顺序；测试钉住这份表，UI 与字节映射共用它。 */
internal val TERMINAL_KEY_BAR: List<TerminalKey> = listOf(
    TerminalKey.ESC,
    TerminalKey.TAB,
    TerminalKey.CTRL,
    TerminalKey.ALT,
    TerminalKey.LEFT,
    TerminalKey.UP,
    TerminalKey.DOWN,
    TerminalKey.RIGHT,
)

/** 一枚键的显示字形。修饰键是文字，方向键是真正的箭头符号。 */
internal fun keyLabel(key: TerminalKey): String = when (key) {
    TerminalKey.ESC -> "Esc"
    TerminalKey.TAB -> "Tab"
    TerminalKey.CTRL -> "Ctrl"
    TerminalKey.ALT -> "Alt"
    TerminalKey.LEFT -> "←"
    TerminalKey.UP -> "↑"
    TerminalKey.DOWN -> "↓"
    TerminalKey.RIGHT -> "→"
}

/** 屏幕阅读器读的文字，不把字形念出来。 */
internal fun keySpoken(key: TerminalKey): String = when (key) {
    TerminalKey.ESC -> "Esc 键"
    TerminalKey.TAB -> "Tab 键"
    TerminalKey.CTRL -> "Ctrl 修饰键，粘滞"
    TerminalKey.ALT -> "Alt 修饰键，粘滞"
    TerminalKey.LEFT -> "方向键 左"
    TerminalKey.UP -> "方向键 上"
    TerminalKey.DOWN -> "方向键 下"
    TerminalKey.RIGHT -> "方向键 右"
}

/** 粘滞修饰键：点一下点亮，下一次按键消费掉并熄灭。 */
internal enum class TerminalModifier { NONE, CTRL, ALT }

internal fun isModifier(key: TerminalKey): Boolean =
    key == TerminalKey.CTRL || key == TerminalKey.ALT

internal fun modifierFor(key: TerminalKey): TerminalModifier = when (key) {
    TerminalKey.CTRL -> TerminalModifier.CTRL
    TerminalKey.ALT -> TerminalModifier.ALT
    else -> TerminalModifier.NONE
}

/**
 * 一枚非修饰键在给定修饰态下真正发往 PTY 的字节。
 *
 * 全部是 xterm/readline 的标准序列，不做「Ctrl 就当 Ctrl-C」这类偷懒映射：
 *  · 裸方向键 = CSI A/B/C/D；
 *  · Alt + 键 = ESC 前缀（meta），方向键因此是 `ESC ESC [ A`；
 *  · Ctrl + 方向键 = CSI 1;5 A/B/C/D（xterm ctrl-arrow）；
 *  · Ctrl/Alt + Esc/Tab 没有可靠的标准序列 ⇒ 退回裸字节，不伪造。
 */
internal fun keyBytes(key: TerminalKey, modifier: TerminalModifier): ByteArray {
    val plain = when (key) {
        TerminalKey.ESC -> TerminalKeys.Esc
        TerminalKey.TAB -> TerminalKeys.Tab
        TerminalKey.LEFT -> TerminalKeys.Left
        TerminalKey.UP -> TerminalKeys.Up
        TerminalKey.DOWN -> TerminalKeys.Down
        TerminalKey.RIGHT -> TerminalKeys.Right
        TerminalKey.CTRL, TerminalKey.ALT -> return ByteArray(0)
    }
    val arrow = when (key) {
        TerminalKey.LEFT -> 'D'
        TerminalKey.UP -> 'A'
        TerminalKey.DOWN -> 'B'
        TerminalKey.RIGHT -> 'C'
        else -> null
    }
    return when {
        modifier == TerminalModifier.CTRL && arrow != null ->
            byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, arrow.code.toByte())
        modifier == TerminalModifier.ALT -> TerminalKeys.Esc + plain
        else -> plain
    }
}

/** 状态词：一个会话状态一行，留在纯逻辑里供 JVM 断言。 */
internal fun sessionStatusLabel(status: TerminalSession.SessionStatus): String = when (status) {
    TerminalSession.SessionStatus.STARTING -> "启动中"
    TerminalSession.SessionStatus.RUNNING -> "运行中"
    TerminalSession.SessionStatus.EXITED -> "已结束"
    TerminalSession.SessionStatus.INTERRUPTED -> "已断开"
}

/** 只有 RUNNING 接受按键与命令。 */
internal fun keysEnabled(status: TerminalSession.SessionStatus?): Boolean =
    status == TerminalSession.SessionStatus.RUNNING

/** 回车发送需要一行字和一个活着 shell。 */
internal fun canSubmit(command: String, running: Boolean): Boolean =
    running && command.isNotEmpty()

/** 完整历史行与 shell 当前行共用一个滚动区域；null 表示尚无完整历史行。 */
internal data class TerminalTranscript(val history: String?, val currentLine: String)

/** 当前行直接来自 PTY（包含真实提示符），不再另造一个常驻的 sh $ 输入前缀。 */
internal fun splitTerminalOutput(output: String): TerminalTranscript {
    val text = output.replace("\r\n", "\n")
    val lastBreak = text.lastIndexOf('\n')
    return if (lastBreak < 0) {
        TerminalTranscript(history = null, currentLine = text)
    } else {
        TerminalTranscript(history = text.substring(0, lastBreak), currentLine = text.substring(lastBreak + 1))
    }
}

/** 工作目录相对应用私有目录展示，避免把一长串绝对路径糊在屏幕上。 */
internal fun workspaceLabel(cwd: String, externalRoot: File?): String {
    val tail = cwd.substringAfterLast('/', missingDelimiterValue = cwd).ifEmpty { cwd }
    val root = externalRoot?.absolutePath ?: return tail
    if (cwd == root) return "应用目录"
    if (cwd.startsWith("$root/")) return "应用目录/" + cwd.removePrefix("$root/")
    return tail
}

/** 复制按钮唯一的反馈；纯决策。 */
internal fun copyFeedback(copied: Boolean): String? = if (copied) "已复制" else "没有可复制的输出"

/**
 * 按视口像素宽与等宽字宽算 PTY 列数。至少要 20 列，最多 240 列；行数同理按高算。
 * 这是 `TIOCSWINSZ` 的入参，让 `sh`/`ls`/`vim` 按屏幕实际宽度折行，而不是写死 80×24。
 */
internal fun terminalSize(widthPx: Float, heightPx: Float, cellWidthPx: Float, cellHeightPx: Float): Pair<Int, Int> {
    if (cellWidthPx <= 0f || cellHeightPx <= 0f || widthPx <= 0f || heightPx <= 0f) return 80 to 24
    val columns = (widthPx / cellWidthPx).toInt().coerceIn(20, 240)
    val rows = (heightPx / cellHeightPx).toInt().coerceIn(6, 120)
    return columns to rows
}

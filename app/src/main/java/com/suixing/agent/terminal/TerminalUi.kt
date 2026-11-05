package com.suixing.agent.terminal

import java.io.File

/** The key bar's vocabulary: the keys a real terminal sends, and nothing else. */
internal enum class TerminalKey { TAB, ESC, CTRL_C, CTRL_D, LEFT, UP, DOWN, RIGHT }

/** Raw bytes for one key — the single place the bar, the arrows and the tests agree. */
internal fun TerminalKey.bytes(): ByteArray = when (this) {
    TerminalKey.TAB -> TerminalKeys.Tab
    TerminalKey.ESC -> TerminalKeys.Esc
    TerminalKey.CTRL_C -> TerminalKeys.CtrlC
    TerminalKey.CTRL_D -> TerminalKeys.CtrlD
    TerminalKey.LEFT -> TerminalKeys.Left
    TerminalKey.UP -> TerminalKeys.Up
    TerminalKey.DOWN -> TerminalKeys.Down
    TerminalKey.RIGHT -> TerminalKeys.Right
}

/**
 * The bar is laid out literally, not derived: control keys in this order, then the
 * three arrows, then Tab as the widest key of the row. A terminal has no "logical"
 * key order, so the layout is the spec.
 */
internal val TERMINAL_KEY_BAR: List<TerminalKey> = listOf(
    TerminalKey.CTRL_C,
    TerminalKey.CTRL_D,
    TerminalKey.ESC,
    TerminalKey.LEFT,
    TerminalKey.UP,
    TerminalKey.DOWN,
    TerminalKey.TAB,
)

/** Three keys per visual row: the bar reads as a keypad, not as a clipped scroll strip. */
internal const val TERMINAL_KEY_COLUMNS = 3

/** Split [TERMINAL_KEY_BAR] into rows of [TERMINAL_KEY_COLUMNS]; the last row may be short. */
internal fun terminalKeyRows(
    columns: Int = TERMINAL_KEY_COLUMNS,
): List<List<TerminalKey>> {
    require(columns > 0) { "key columns must be positive" }
    return TERMINAL_KEY_BAR.chunked(columns)
}

/** The status chip's word, kept out of the composable so it can be asserted on the JVM. */
internal fun sessionStatusLabel(status: TerminalSession.SessionStatus): String = when (status) {
    TerminalSession.SessionStatus.STARTING -> "启动中"
    TerminalSession.SessionStatus.RUNNING -> "运行中"
    TerminalSession.SessionStatus.EXITED -> "已结束"
}

/** Only a RUNNING session accepts keystrokes — the one gate the whole page shares. */
internal fun keysEnabled(status: TerminalSession.SessionStatus?): Boolean =
    status == TerminalSession.SessionStatus.RUNNING

/** Enter sends the line only when there is a line and a shell to send it to. */
internal fun canSubmit(command: String, running: Boolean): Boolean =
    running && command.isNotEmpty()

/** `/system/bin/sh` reads better as `sh` in a chip; anything else keeps its last segment. */
internal fun shellLabel(shell: String): String =
    shell.trimEnd('/').substringAfterLast('/').ifEmpty { shell }

/**
 * The workspace path cannot break the chip row: the status bar clips it to one line, so
 * the tail — the directory the user invoked the shell from — is the half worth keeping.
 */
internal fun compactCwd(cwd: String): String =
    cwd.substringAfterLast('/', missingDelimiterValue = cwd).ifEmpty { cwd }

/** PID + `cols×rows`, the two facts that describe the PTY rather than the app. */
internal fun ptyLabel(pid: Int, columns: Int, rows: Int): String = "pid $pid · ${columns}×${rows}"

/** The viewport's own status line: what the PTY looks like from inside, not from the app. */
internal fun sessionFacts(session: TerminalSession): String =
    ptyLabel(session.pid, session.columns, session.rows)

/**
 * Where the session actually runs. The header names the workspace relative to the app's
 * external files dir, so the long absolute path never has to be printed.
 */
internal fun workspaceLabel(cwd: String, externalRoot: File?): String {
    val root = externalRoot?.absolutePath ?: return compactCwd(cwd)
    if (cwd == root) return "应用目录"
    if (cwd.startsWith("$root/")) return "应用目录/" + cwd.removePrefix("$root/")
    return compactCwd(cwd)
}

/** 「已复制」 is the only confirmation a copy button can give; feedback is a pure decision. */
internal fun copyFeedback(copied: Boolean): String? = if (copied) "已复制" else "没有可复制的输出"

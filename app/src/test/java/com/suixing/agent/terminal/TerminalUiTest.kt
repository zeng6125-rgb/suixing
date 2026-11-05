package com.suixing.agent.terminal

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal screen's pure decisions: key layout, status words and the input gate. */
class TerminalUiTest {
    @Test
    fun `every key maps to the exact bytes TerminalKeys pins`() {
        assertArrayEquals(byteArrayOf(0x09), TerminalKey.TAB.bytes())
        assertArrayEquals(byteArrayOf(0x1b), TerminalKey.ESC.bytes())
        assertArrayEquals(byteArrayOf(0x03), TerminalKey.CTRL_C.bytes())
        assertArrayEquals(byteArrayOf(0x04), TerminalKey.CTRL_D.bytes())
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x41), TerminalKey.UP.bytes())
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x42), TerminalKey.DOWN.bytes())
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x43), TerminalKey.RIGHT.bytes())
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x44), TerminalKey.LEFT.bytes())
    }

    @Test
    fun `the key bar is split into rows of three and keeps its order`() {
        val rows = terminalKeyRows()
        assertEquals(listOf(3, 3, 1), rows.map { it.size })
        assertEquals(TERMINAL_KEY_BAR, rows.flatten())
        assertEquals(TerminalKey.CTRL_C, rows.first().first())
        assertEquals(TerminalKey.TAB, rows.last().single())
    }

    @Test
    fun `a wider bar still flattens back to the same key list`() {
        assertEquals(TERMINAL_KEY_BAR, terminalKeyRows(columns = 4).flatten())
        assertEquals(listOf(4, 3), terminalKeyRows(columns = 4).map { it.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive column count is rejected`() {
        terminalKeyRows(columns = 0)
    }

    @Test
    fun `status words follow the three session states`() {
        assertEquals("启动中", sessionStatusLabel(TerminalSession.SessionStatus.STARTING))
        assertEquals("运行中", sessionStatusLabel(TerminalSession.SessionStatus.RUNNING))
        assertEquals("已结束", sessionStatusLabel(TerminalSession.SessionStatus.EXITED))
    }

    @Test
    fun `only a running session accepts keys and commands`() {
        assertFalse(keysEnabled(null))
        assertFalse(keysEnabled(TerminalSession.SessionStatus.STARTING))
        assertTrue(keysEnabled(TerminalSession.SessionStatus.RUNNING))
        assertFalse(keysEnabled(TerminalSession.SessionStatus.EXITED))
    }

    @Test
    fun `enter needs both a running shell and a non-empty line`() {
        assertTrue(canSubmit("ls", running = true))
        assertFalse(canSubmit("", running = true))
        assertFalse(canSubmit("ls", running = false))
        assertFalse(canSubmit("", running = false))
    }

    @Test
    fun `the shell chip drops the system prefix`() {
        assertEquals("sh", shellLabel("/system/bin/sh"))
        assertEquals("bash", shellLabel("/system/bin/bash/"))
        assertEquals("sh", shellLabel("sh"))
    }

    @Test
    fun `the workspace chip is relative to the app files dir`() {
        // 生产代码传的是 File.absolutePath；JVM 上绝对路径会被解析到工作目录，
        // 所以 cwd 必须用同一个 absolutePath 构造，比较才成立。
        val root = File("/app-files")
        val rootPath = root.absolutePath
        assertEquals("应用目录", workspaceLabel(rootPath, root))
        assertEquals("应用目录/terminal", workspaceLabel("$rootPath/terminal", root))
        // Outside the app dir (or an unknown root) the last segment is what fits.
        assertEquals("sdcard", workspaceLabel("/sdcard", root))
        assertEquals("files", workspaceLabel("/other/x/files", null))
    }

    @Test
    fun `the pty chip never claims more than it knows`() {
        assertEquals("pid 4242 · 80×24", ptyLabel(4242, 80, 24))
    }

    @Test
    fun `copy feedback distinguishes an empty transcript from a copied one`() {
        assertEquals("已复制", copyFeedback(copied = true))
        assertEquals("没有可复制的输出", copyFeedback(copied = false))
    }
}

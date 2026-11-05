package com.suixing.agent.terminal

import java.io.File
import androidx.compose.ui.text.AnnotatedString
import com.suixing.agent.ui.terminal.TerminalInputTransformation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 终端页的纯决策：键条字节、粘滞修饰、尺寸换算、状态词与输入门。 */
class TerminalUiTest {
    @Test
    fun `the strip follows the reference order Esc Tab Ctrl Alt arrows`() {
        assertEquals(
            listOf(
                TerminalKey.ESC,
                TerminalKey.TAB,
                TerminalKey.CTRL,
                TerminalKey.ALT,
                TerminalKey.LEFT,
                TerminalKey.UP,
                TerminalKey.DOWN,
                TerminalKey.RIGHT,
            ),
            TERMINAL_KEY_BAR,
        )
    }

    @Test
    fun `the strip labels arrows with real arrow glyphs`() {
        assertEquals("Esc", keyLabel(TerminalKey.ESC))
        assertEquals("Tab", keyLabel(TerminalKey.TAB))
        assertEquals("Ctrl", keyLabel(TerminalKey.CTRL))
        assertEquals("Alt", keyLabel(TerminalKey.ALT))
        assertEquals("←", keyLabel(TerminalKey.LEFT))
        assertEquals("↑", keyLabel(TerminalKey.UP))
        assertEquals("↓", keyLabel(TerminalKey.DOWN))
        assertEquals("→", keyLabel(TerminalKey.RIGHT))
    }

    @Test
    fun `plain keys send the exact bytes TerminalKeys pins`() {
        assertArrayEquals(byteArrayOf(0x1b), keyBytes(TerminalKey.ESC, TerminalModifier.NONE))
        assertArrayEquals(byteArrayOf(0x09), keyBytes(TerminalKey.TAB, TerminalModifier.NONE))
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x41), keyBytes(TerminalKey.UP, TerminalModifier.NONE))
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x42), keyBytes(TerminalKey.DOWN, TerminalModifier.NONE))
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x43), keyBytes(TerminalKey.RIGHT, TerminalModifier.NONE))
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x44), keyBytes(TerminalKey.LEFT, TerminalModifier.NONE))
    }

    @Test
    fun `Alt prefixes the plain sequence and Ctrl sends xterm ctrl-arrow`() {
        // Alt + Up = ESC + CSI A
        assertArrayEquals(
            byteArrayOf(0x1b, 0x1b, 0x5b, 0x41),
            keyBytes(TerminalKey.UP, TerminalModifier.ALT),
        )
        // Ctrl + Up = CSI 1;5 A
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x41),
            keyBytes(TerminalKey.UP, TerminalModifier.CTRL),
        )
        // Ctrl/Alt + Esc/Tab 没有可靠标准序列：退回裸字节，不伪造。
        assertArrayEquals(byteArrayOf(0x1b), keyBytes(TerminalKey.ESC, TerminalModifier.CTRL))
        assertArrayEquals(byteArrayOf(0x1b, 0x09), keyBytes(TerminalKey.TAB, TerminalModifier.ALT))
    }

    @Test
    fun `modifier keys are sticky and produce no bytes on their own`() {
        assertTrue(isModifier(TerminalKey.CTRL))
        assertTrue(isModifier(TerminalKey.ALT))
        assertFalse(isModifier(TerminalKey.ESC))
        assertEquals(TerminalModifier.CTRL, modifierFor(TerminalKey.CTRL))
        assertEquals(TerminalModifier.ALT, modifierFor(TerminalKey.ALT))
        assertEquals(TerminalModifier.NONE, modifierFor(TerminalKey.UP))
        assertArrayEquals(ByteArray(0), keyBytes(TerminalKey.CTRL, TerminalModifier.NONE))
    }

    @Test
    fun `terminal size follows the viewport instead of a hard-coded 80x24`() {
        // 800px 宽 / 8px 字符宽 = 100 列；400px 高 / 20px 行高 = 20 行。
        assertEquals(100 to 20, terminalSize(800f, 400f, 8f, 20f))
        // 越界钳制；非正数退回 80×24。
        assertEquals(240 to 120, terminalSize(100000f, 100000f, 8f, 20f))
        assertEquals(20 to 6, terminalSize(80f, 60f, 8f, 20f))
        assertEquals(80 to 24, terminalSize(0f, 0f, 8f, 20f))
        assertEquals(80 to 24, terminalSize(800f, 400f, 0f, 20f))
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
    fun `the current prompt comes from the PTY instead of a second synthetic shell label`() {
        assertEquals(TerminalTranscript(null, "$ "), splitTerminalOutput("$ "))
        assertEquals(TerminalTranscript("$ echo '", "> "), splitTerminalOutput("$ echo '\r\n> "))
        assertEquals(TerminalTranscript("$ exit", ""), splitTerminalOutput("$ exit\r\n"))
    }

    @Test
    fun `successive commands and their output stay in top to bottom order`() {
        var output = "$ "
        output = appendBounded(output, "echo 中文\r\n中文\r\n$ ")
        assertEquals(TerminalTranscript("$ echo 中文\n中文", "$ "), splitTerminalOutput(output))
        output = appendBounded(output, "pwd\r\n/app-files\r\n$ ")
        assertEquals(
            TerminalTranscript("$ echo 中文\n中文\n$ pwd\n/app-files", "$ "),
            splitTerminalOutput(output),
        )
    }

    @Test
    fun `blank output lines and incomplete reads retain their place`() {
        assertEquals(TerminalTranscript(null, ""), splitTerminalOutput(""))
        assertEquals(TerminalTranscript("", "$ "), splitTerminalOutput("\n$ "))
        assertEquals(TerminalTranscript("first\n", "tail"), splitTerminalOutput("first\r\n\r\ntail"))
        val output = appendBounded("first\r", "\nsecond\r\n$ ")
        assertEquals(TerminalTranscript("first\nsecond", "$ "), splitTerminalOutput(output))
    }

    @Test
    fun `terminal editing stays after the real prompt for English Chinese and emoji`() {
        for (input in listOf("", "echo hello", "echo 中文", "echo 😀")) {
            val transformed = TerminalInputTransformation("$ ").filter(AnnotatedString(input))
            assertEquals("$ $input", transformed.text.text)
            for (offset in 0..input.length) {
                val rendered = transformed.offsetMapping.originalToTransformed(offset)
                assertEquals(offset, transformed.offsetMapping.transformedToOriginal(rendered))
            }
            assertEquals(0, transformed.offsetMapping.transformedToOriginal(0))
            assertEquals(0, transformed.offsetMapping.transformedToOriginal(1))
        }
    }

    @Test
    fun `a custom or multiline current prefix appears once without entering editable text`() {
        val prefix = "> waiting: "
        val transformed = TerminalInputTransformation(prefix).filter(AnnotatedString("answer"))
        assertEquals("> waiting: answer", transformed.text.text)
        assertEquals(prefix.length, transformed.offsetMapping.originalToTransformed(0))
        assertEquals(0, transformed.offsetMapping.transformedToOriginal(prefix.length - 1))
        assertEquals(6, transformed.offsetMapping.transformedToOriginal(prefix.length + 6))
    }

    @Test
    fun `the workspace label is relative to the app files dir`() {
        val root = File("/app-files")
        val rootPath = root.absolutePath
        assertEquals("应用目录", workspaceLabel(rootPath, root))
        assertEquals("应用目录/terminal", workspaceLabel("$rootPath/terminal", root))
        assertEquals("sdcard", workspaceLabel("/sdcard", root))
        assertEquals("files", workspaceLabel("/other/x/files", null))
    }

    @Test
    fun `copy feedback distinguishes an empty transcript from a copied one`() {
        assertEquals("已复制", copyFeedback(true))
        assertEquals("没有可复制的输出", copyFeedback(false))
    }
}

package com.suixing.agent.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.*
import org.junit.Test

class TerminalEmulatorTest {
    private class Output : TerminalOutput() {
        val writes = StringBuilder()
        var clipboardWrites = 0
        override fun write(data: ByteArray, offset: Int, count: Int) { writes.append(String(data, offset, count, Charsets.UTF_8)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) { clipboardWrites++ }
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }
    private fun emulator(output: Output = Output()) = TerminalEmulator(output, 80, 24, 1, 1, 2000, null)
    private fun TerminalEmulator.feed(text: String) { val bytes = text.toByteArray(); append(bytes, bytes.size) }
    private fun TerminalEmulator.text() = screen.transcriptText.trimEnd()

    @Test fun sgrNeverAppearsAsLiteralText() {
        val emulator = emulator()
        emulator.feed("\u001b[31mred\u001b[0m plain")
        assertEquals("red plain", emulator.text())
    }
    @Test fun carriageReturnOverwritesWithoutAppending() {
        val emulator = emulator(); emulator.feed("abcdef\rXY")
        assertEquals("XYcdef", emulator.text())
    }
    @Test fun clearAndHomeResetTheScreen() {
        val emulator = emulator(); emulator.feed("old\u001b[2J\u001b[Hnew")
        assertEquals("new", emulator.text())
    }
    @Test fun eraseLineRemovesTheRemainder() {
        val emulator = emulator(); emulator.feed("abcdef\rXY\u001b[K")
        assertEquals("XY", emulator.text())
    }
    @Test fun escapeAndUtf8CanBeSplitAtEveryBoundary() {
        val emulator = emulator()
        for (byte in "\u001b[32m中文🙂\u001b[0m".toByteArray()) emulator.append(byteArrayOf(byte), 1)
        assertEquals("中文🙂", emulator.text())
    }
    @Test fun alternateScreenRestoresTheShellScreen() {
        val emulator = emulator(); emulator.feed("shell\u001b[?1049hfull screen\u001b[?1049l")
        assertEquals("shell", emulator.text())
    }
    @Test fun cursorPositionAndBackspaceRedrawTheExistingLine() {
        val emulator = emulator(); emulator.feed("abc\bX\u001b[1;1HY")
        assertEquals("YbX", emulator.text())
    }
    @Test fun osc52CannotTouchAndroidClipboard() {
        val output = Output(); val emulator = emulator(output)
        emulator.feed("\u001b]52;c;c2VjcmV0\u0007ok")
        assertEquals(0, output.clipboardWrites)
        assertEquals("ok", emulator.text())
    }
    @Test fun bracketedPasteIsWrappedAndDoesNotSendEscapeFromClipboard() {
        val output = Output(); val emulator = emulator(output)
        emulator.feed("\u001b[?2004h")
        emulator.paste("one\ntwo\u001b")
        assertEquals("\u001b[200~one\rtwo\u001b[201~", output.writes.toString())
    }
    @Test fun ordinaryPasteUsesNoBracketedMarkers() {
        val output = Output(); val emulator = emulator(output)
        emulator.paste("hello")
        assertEquals("hello", output.writes.toString())
    }
    @Test fun scrollbackIsBoundedWhileKeepingRecentOutput() {
        val emulator = emulator()
        emulator.feed((1..4000).joinToString("\r\n") { "row$it" })
        assertTrue(emulator.screen.activeTranscriptRows <= 2000)
        assertTrue(emulator.text().contains("row4000"))
        assertFalse(emulator.text().lineSequence().any { it == "row1" })
    }
    @Test fun toolbarClearErasesVisibleTextAndHistoryWithoutDisablingBracketedPaste() {
        val output = Output(); val emulator = emulator(output)
        emulator.feed((1..80).joinToString("\r\n") { "row$it" } + "\u001b[?2004h")
        assertTrue(emulator.screen.activeTranscriptRows > 0)
        emulator.clearScreenAndHistory()
        assertEquals("", emulator.text())
        assertEquals(0, emulator.screen.activeTranscriptRows)
        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
        emulator.paste("one\ntwo")
        assertEquals("\u001b[200~one\rtwo\u001b[201~", output.writes.toString())
    }
}

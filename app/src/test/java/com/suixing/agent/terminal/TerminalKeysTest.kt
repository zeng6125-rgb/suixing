package com.suixing.agent.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Pin the exact bytes the key bar forwards to the PTY; no VT/ANSI emulation happens here. */
class TerminalKeysTest {
    @Test
    fun `tab and escape are their single control bytes`() {
        assertArrayEquals(byteArrayOf(0x09), TerminalKeys.Tab)
        assertArrayEquals(byteArrayOf(0x1b), TerminalKeys.Esc)
    }

    @Test
    fun `ctrl-c and ctrl-d are the INTR and EOF bytes`() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKeys.CtrlC)
        assertArrayEquals(byteArrayOf(0x04), TerminalKeys.CtrlD)
    }

    @Test
    fun `arrow keys are CSI A B C D sequences`() {
        // ESC [ A / B / C / D — the shell (or the program it runs) interprets these.
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x41), TerminalKeys.Up)
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x42), TerminalKeys.Down)
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x43), TerminalKeys.Right)
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x44), TerminalKeys.Left)
    }
}

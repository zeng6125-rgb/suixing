package com.suixing.agent.terminal

/**
 * Bytes a real terminal sends for the keys the on-screen bar exposes. This is NOT a VT/ANSI
 * emulator: we forward the exact bytes to the PTY and let `/system/bin/sh` (and whatever it
 * runs) interpret them. Kept as raw byte arrays so a JUnit test can pin every sequence.
 */
internal object TerminalKeys {
    /** Horizontal tab. */
    val Tab = byteArrayOf(0x09)

    /** Escape key. */
    val Esc = byteArrayOf(0x1b)

    /** Ctrl-C: INTR. The dedicated interrupt button signals the process group instead. */
    val CtrlC = byteArrayOf(0x03)

    /** Ctrl-D: EOF / logout. */
    val CtrlD = byteArrayOf(0x04)

    /** Arrow Up: CSI A (ESC [ A). */
    val Up = byteArrayOf(0x1b, 0x5b, 0x41)

    /** Arrow Down: CSI B (ESC [ B). */
    val Down = byteArrayOf(0x1b, 0x5b, 0x42)

    /** Arrow Right: CSI C (ESC [ C). */
    val Right = byteArrayOf(0x1b, 0x5b, 0x43)

    /** Arrow Left: CSI D (ESC [ D). */
    val Left = byteArrayOf(0x1b, 0x5b, 0x44)
}

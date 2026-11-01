package com.suixing.agent.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires an Android device or emulator; ordinary JVM tests cannot validate
 * forkpty, PTY controlling terminals, kernel job control, or real SELinux policy.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPtyDeviceTest {
    @Test fun shellReceivesInputAndProducesLiveOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val h = TerminalNative.openAppShell(context)
        val master = h[0].toInt()
        val pid = h[1].toInt()
        try {
            val command = "test -t 0 && echo __SUixING_TTY_OK__\n"
            TerminalNative.nativeWrite(master, command.toByteArray())
            val output = StringBuilder()
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val bytes = TerminalNative.nativeRead(master, 4096, 250) ?: continue
                if (bytes.isEmpty()) break
                output.append(String(bytes, Charsets.UTF_8))
                if ("__SUixING_TTY_OK__" in output) break
            }
            assertTrue("PTY should be interactive; output was: $output", "__SUixING_TTY_OK__" in output)
            TerminalNative.nativeResize(master, 110, 36)
        } finally {
            TerminalNative.nativeSignalGroup(pid, 15) // SIGTERM, child still unreaped
            TerminalNative.nativeClose(master)
            TerminalNative.nativeWait(pid)
        }
    }
}

package com.suixing.agent.capability

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ShellCancellationTest {
    private fun child(): Process {
        val executable = File(System.getProperty("java.home"), "bin/" +
            if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java")
        val args = File.createTempFile("suixing-shell-cancel", ".args").apply {
            deleteOnExit()
            val classpath = System.getProperty("java.class.path").replace('\\', '/')
            writeText("-cp \"$classpath\"\ncom.suixing.agent.capability.ShellWaitProcess\n")
        }
        return ProcessBuilder(executable.absolutePath, "@${args.absolutePath}").start()
    }

    @Test fun timeoutRetainsItsCancellationTypeAndReapsChild() = runBlocking {
        val process = child()
        try {
            val failure = runCatching {
                withContext(Dispatchers.IO) { withTimeout(200) { Shell.collect(process) } }
            }.exceptionOrNull()
            assertTrue("timeout must stay recognizable to plugin boundary: $failure", failure is TimeoutCancellationException)
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        } finally { process.destroyForcibly() }
    }

    @Test fun userCancellationRetainsItsCauseAndReapsChild() = runBlocking {
        val process = child()
        try {
            val job = async(Dispatchers.IO) { Shell.collect(process) }
            delay(150)
            job.cancel(CancellationException("user stop"))
            val failure = runCatching { job.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(failure is TimeoutCancellationException)
            assertEquals("user stop", failure?.message)
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        } finally { process.destroyForcibly() }
    }
}

object ShellWaitProcess {
    @JvmStatic fun main(args: Array<String>) { Thread.sleep(30_000) }
}

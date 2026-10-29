package com.suixing.agent.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedToolExecutionTest {
    @Test fun `device mutations queue across engines but independent reads continue`() = runBlocking {
        val shared = SharedToolExecution()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { shared.run("run_shell") { release.await(); "shell" } }
        var tapped = false
        val second = async(start = CoroutineStart.UNDISPATCHED) { shared.run("ui_tap") { tapped = true } }
        assertFalse(tapped)
        assertEquals("read", shared.run("read_file") { "read" })
        second.cancel()
        release.complete(Unit)
        assertEquals("shell", first.await())
        second.join()
        assertFalse(tapped)
        assertEquals("next", shared.run("app_action") { "next" })
    }
}

package com.suixing.agent.terminal

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the actual process owner; only JNI allocation and the emulator are replaced. */
class TerminalSessionManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { appScope.cancel() }

    private class Runtime(private val directory: File) : TerminalSessionRuntime {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        val session = mockk<TerminalSession>(relaxed = true)
        var creationFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var startFailure: Throwable? = null
        var opens = 0
        var released = 0
        var fd = -1
        var pid = -1
        init {
            every { session.start() } answers {
                val failure = startFailure
                if (failure != null) throw failure
            }
        }
        override suspend fun androidPlan(context: Context) = TerminalLaunch(
            listOf("bash", "-i"), emptyMap(), directory.absolutePath, "bash",
        )
        override fun openProcess(plan: TerminalLaunch): LongArray { opens++; return longArrayOf(42, 4242) }
        override fun createSession(
            id: String, masterFd: Int, pid: Int, plan: TerminalLaunch, policy: TerminalPolicy,
            scope: CoroutineScope, log: TerminalLog, onEnded: (Int, String?) -> Unit,
        ): TerminalSession {
            fd = masterFd
            this.pid = pid
            creationFailure?.let { throw it }
            return session
        }
        override fun ensureForeground(context: Context): String? = null
        override suspend fun releaseUnclaimed(native: LongArray) {
            assertEquals(listOf(42L, 4242L), native.toList())
            released++
            releaseFailure?.let { throw it }
        }
    }

    private fun manager(runtime: Runtime): TerminalSessionManager {
        val files = temporary.newFolder()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns files
            every { noBackupFilesDir } returns files
        }
        return TerminalSessionManager(context, appScope, LinuxInstaller(context, appScope), runtime)
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        fail("The injected startup failure must reach the caller")
        error("unreachable")
    }

    @Test fun constructionFailureReleasesTheUnclaimedNativeProcess() = runBlocking {
        val runtime = Runtime(temporary.root)
        val failure = IllegalStateException("emulator construction failed")
        runtime.creationFailure = failure
        val manager = manager(runtime)
        val caught = failureOf { manager.open() }
        assertEquals(failure.message, caught.message)
        assertEquals(1, runtime.opens)
        assertEquals(1, runtime.released)
        assertEquals(0, manager.entries.value.size)
        assertNull(manager.selectedId)
        coVerify(exactly = 0) { runtime.session.stop() }
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
    }

    @Test fun startFailureStopsItsSessionAndRollsBackThePublishedEntry() = runBlocking {
        val runtime = Runtime(temporary.root)
        val failure = IllegalStateException("writer startup failed")
        runtime.startFailure = failure
        val manager = manager(runtime)
        val caught = failureOf { manager.open() }
        assertEquals(failure.message, caught.message)
        assertEquals(0, manager.entries.value.size)
        assertNull(manager.selectedId)
        assertEquals(0, runtime.released)
        coVerify(exactly = 1) { runtime.session.stop() }
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
    }

    @Test fun aSuccessfulOpenTransfersOwnershipExactlyOnce() = runBlocking {
        val runtime = Runtime(temporary.root)
        val manager = manager(runtime)
        val entry = manager.open()
        assertEquals(42, runtime.fd)
        assertEquals(4242, runtime.pid)
        assertSame(runtime.session, entry.session)
        assertEquals(entry.info.id, manager.selectedId)
        assertEquals("running", manager.entries.value.single().info.phase)
        assertEquals(0, runtime.released)
        verify(exactly = 1) { runtime.session.start() }
        coVerify(exactly = 0) { runtime.session.stop() }
    }

    @Test fun cleanupFailureCannotReplaceTheOriginalStartupFailure() = runBlocking {
        val runtime = Runtime(temporary.root)
        val failure = IllegalStateException("original startup failure")
        runtime.creationFailure = failure
        runtime.releaseFailure = IllegalStateException("cleanup failed")
        val manager = manager(runtime)
        val caught = failureOf { manager.open() }
        assertEquals(failure.message, caught.message)
        assertEquals(1, runtime.released)
        assertEquals(0, manager.entries.value.size)
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
        assertTrue(generateSequence(caught) { it.cause }
            .flatMap { it.suppressed.asSequence() }.any { it.message == "cleanup failed" })
    }

}

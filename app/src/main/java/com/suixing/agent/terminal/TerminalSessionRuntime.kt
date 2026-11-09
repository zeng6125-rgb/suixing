package com.suixing.agent.terminal

import android.content.Context
import com.suixing.agent.service.TerminalForegroundService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Native allocation is separate from ownership transfer, so failed startup can be rolled back. */
internal interface TerminalSessionRuntime {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    suspend fun androidPlan(context: Context): TerminalLaunch
    fun openProcess(plan: TerminalLaunch): LongArray
    fun createSession(
        id: String,
        masterFd: Int,
        pid: Int,
        plan: TerminalLaunch,
        policy: TerminalPolicy,
        scope: CoroutineScope,
        log: TerminalLog,
        onEnded: (Int, String?) -> Unit,
    ): TerminalSession
    fun ensureForeground(context: Context): String?
    suspend fun releaseUnclaimed(native: LongArray)
}

internal object AndroidTerminalSessionRuntime : TerminalSessionRuntime {
    override val main get() = Dispatchers.Main.immediate
    override val io get() = Dispatchers.IO
    override suspend fun androidPlan(context: Context) = androidTerminalLaunch(context)
    override fun openProcess(plan: TerminalLaunch): LongArray = TerminalNative.nativeOpenProcess(
        plan.argv.toTypedArray(),
        plan.environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
        plan.cwd, 80, 24,
    )
    override fun createSession(
        id: String,
        masterFd: Int,
        pid: Int,
        plan: TerminalLaunch,
        policy: TerminalPolicy,
        scope: CoroutineScope,
        log: TerminalLog,
        onEnded: (Int, String?) -> Unit,
    ) = TerminalSession(id, masterFd, pid, plan, policy, scope, log, onEnded)

    override fun ensureForeground(context: Context) = TerminalForegroundService.ensure(context)

    override suspend fun releaseUnclaimed(native: LongArray) = withContext(NonCancellable + io) {
        val fd = native[0].toInt()
        val pid = native[1].toInt()
        try {
            runCatching { TerminalNative.nativeSignalOwned(fd, pid, SIGTERM) }
            delay(200)
            runCatching { TerminalNative.nativeSignalOwned(fd, pid, SIGKILL) }
            runCatching { TerminalNative.nativeWaitOwned(fd, pid, -1) }
        } finally {
            TerminalNative.nativeClose(fd)
        }
        Unit
    }
}

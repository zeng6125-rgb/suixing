package com.suixing.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

/** Optional maintenance waits for real content and a draw, without blocking service-only starts. */
internal class StartupReadiness(private val fallbackMillis: Long = 5_000L) {
    @Volatile private var awaitingUi = false
    private val drawn = CompletableDeferred<Unit>()
    private val content = CompletableDeferred<Unit>()

    /**
     * 后台豁免当前的「闸门」。
     *
     * **可被后来的界面启动撤销**（issue #56 R4）：原来它是一个终身只 complete 一次的
     * `CompletableDeferred`，开机自启或后台先拉起之后，`backgroundStarting()` 把它完成，
     * 此后用户再点开界面，`awaitMaintenance()` 依然立刻放行 —— 维护任务与首帧争 IO，
     * 正是「service 不能绕过 content readiness」的反向漏洞。现在换成可替换的实例：
     * 界面启动时换一个新的未完成闸门，新的等待就重新生效。
     */
    @Volatile private var background = CompletableDeferred<Unit>()
    private val maintenance = Mutex()

    /** Call before UI initialization; a late UI start revokes the background-only exemption. */
    @Synchronized fun uiStarting() {
        awaitingUi = true
        if (background.isCompleted) background = CompletableDeferred()
    }

    /** A real headless entry point can release optional scans without waiting for a frame. */
    @Synchronized fun backgroundStarting() { if (!awaitingUi) background.complete(Unit) }

    fun firstFrameDrawn() { drawn.complete(Unit) }
    fun contentPresented() { content.complete(Unit) }

    suspend fun awaitMaintenance() {
        // 取调用时刻的闸门：界面启动会把字段换成新的未完成实例，这次等待因此重新生效。
        val gate = background
        withTimeoutOrNull(fallbackMillis) {
            select<Unit> { drawn.onAwait { }; gate.onAwait { } }
            select<Unit> { content.onAwait { }; gate.onAwait { } }
        }
    }

    suspend fun <T> maintenance(block: suspend () -> T): T {
        awaitMaintenance()
        return maintenance.withLock { block() }
    }
}

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
    private val background = CompletableDeferred<Unit>()
    private val maintenance = Mutex()

    /** Call before UI initialization; background-only entry points have no frame to await. */
    @Synchronized fun uiStarting() { awaitingUi = true }

    /** A real headless entry point can release optional scans without waiting for a frame. */
    @Synchronized fun backgroundStarting() { if (!awaitingUi) background.complete(Unit) }

    fun firstFrameDrawn() { drawn.complete(Unit) }
    fun contentPresented() { content.complete(Unit) }

    suspend fun awaitMaintenance() {
        withTimeoutOrNull(fallbackMillis) {
            select<Unit> { drawn.onAwait { }; background.onAwait { } }
            select<Unit> { content.onAwait { }; background.onAwait { } }
        }
    }

    suspend fun <T> maintenance(block: suspend () -> T): T {
        awaitMaintenance()
        return maintenance.withLock { block() }
    }
}

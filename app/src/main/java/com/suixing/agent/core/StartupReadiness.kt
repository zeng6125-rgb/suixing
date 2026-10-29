package com.suixing.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional maintenance waits for real content and a draw, without blocking service-only starts. */
internal class StartupReadiness(private val fallbackMillis: Long = 5_000L) {
    private val drawn = CompletableDeferred<Unit>()
    private val content = CompletableDeferred<Unit>()
    private val maintenance = Mutex()

    fun firstFrameDrawn() { drawn.complete(Unit) }
    fun contentPresented() { content.complete(Unit) }

    suspend fun awaitMaintenance() {
        withTimeoutOrNull(fallbackMillis) {
            drawn.await()
            content.await()
        }
    }

    suspend fun <T> maintenance(block: suspend () -> T): T {
        awaitMaintenance()
        return maintenance.withLock { block() }
    }
}

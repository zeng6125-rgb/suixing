package com.suixing.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StartupReadinessTest {
    @Test fun `a skeleton draw alone does not release optional work`() = runBlocking {
        val ready = StartupReadiness()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { ready.awaitMaintenance() }
        ready.firstFrameDrawn()
        assertFalse(waiting.isCompleted)
        ready.contentPresented()
        withTimeout(1_000) { waiting.await() }
    }

    @Test fun `content before drawing still waits and cancelled waiter leaves shared signals intact`() = runBlocking {
        val ready = StartupReadiness()
        ready.contentPresented()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { ready.awaitMaintenance() }
        assertFalse(cancelled.isCompleted)
        cancelled.cancelAndJoin()
        ready.firstFrameDrawn()
        withTimeout(1_000) { ready.awaitMaintenance() }
    }

    @Test fun `a service only start eventually runs maintenance without an Activity`() = runBlocking {
        withTimeout(1_000) { StartupReadiness(fallbackMillis = 1).awaitMaintenance() }
    }

    @Test fun `optional scans are serialized and failure releases the next task`() = runBlocking {
        val ready = StartupReadiness()
        ready.firstFrameDrawn()
        ready.contentPresented()
        val release = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { ready.maintenance { release.await(); error("scan failed") } }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            ready.maintenance { secondStarted = true }
        }
        assertFalse(secondStarted)
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        withTimeout(1_000) { second.await() }
        assertTrue(secondStarted)
    }
}

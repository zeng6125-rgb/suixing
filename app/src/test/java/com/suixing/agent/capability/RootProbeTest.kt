package com.suixing.agent.capability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProbeTest {
    @Test fun `overlapping automatic and manual callers start only one check`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val probe = RootProbe(scope, { null }) { calls++; release.await(); true }
            val first = async(start = CoroutineStart.UNDISPATCHED) { probe.await(false) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { probe.await(true) }
            assertEquals(1, calls)
            release.complete(Unit)
            assertTrue(first.await())
            assertTrue(second.await())
        } finally { scope.cancel() }
    }

    @Test fun `cancelling a waiter does not cancel the shared check`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val probe = RootProbe(scope, { null }) { calls++; release.await(); true }
            val leaving = async(start = CoroutineStart.UNDISPATCHED) { probe.await(false) }
            val staying = async(start = CoroutineStart.UNDISPATCHED) { probe.await(false) }
            leaving.cancelAndJoin()
            release.complete(Unit)
            assertTrue(staying.await())
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun `automatic callers reuse a known answer but manual rechecks remain fresh`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var known: Boolean? = false
            var calls = 0
            val probe = RootProbe(scope, { known }) { calls++; true.also { known = it } }
            assertFalse(probe.await(false))
            assertEquals(0, calls)
            assertTrue(probe.await(true))
            assertTrue(probe.await(false))
            assertEquals(1, calls)
            assertTrue(probe.await(true))
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}

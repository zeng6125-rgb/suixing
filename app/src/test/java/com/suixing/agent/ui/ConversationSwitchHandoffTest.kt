package com.suixing.agent.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchHandoffTest {
    @Test fun `a target waits for a real closing frame and can prepare before close ends`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val progress = mutableFloatStateOf(1f)
        val finish = CompletableDeferred<Unit>()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { finish.await() } }
        val ready = async(start = CoroutineStart.UNDISPATCHED) { handoff.awaitClosingFrame { progress.floatValue } }
        yield()
        assertFalse("a stationary full-open drawer must still cover the old transcript", ready.isCompleted)

        progress.floatValue = 0.95f
        Snapshot.sendApplyNotifications()
        assertTrue(withTimeout(5_000) { ready.await() })
        assertFalse("target preparation must overlap the remaining close", close.isCompleted)
        finish.complete(Unit)
        close.join()
    }

    @Test fun `cancel before the first closing frame releases the waiter without committing`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { awaitCancellation() } }
        val ready = async(start = CoroutineStart.UNDISPATCHED) { handoff.awaitClosingFrame { 1f } }
        close.cancelAndJoin()
        Snapshot.sendApplyNotifications()
        assertFalse(withTimeout(5_000) { ready.await() })
    }

    @Test fun `an interrupted partial close cannot commit when its watcher resumes late`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { awaitCancellation() } }
        close.cancelAndJoin()
        assertFalse("partial progress alone must not revive an aborted close",
            handoff.awaitClosingFrame { 0.8f })
    }

    @Test fun `a completed close permits a delayed waiter even without an observed moving frame`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        handoff.close { }
        assertTrue(handoff.awaitClosingFrame { 0f })
    }

    @Test fun `a later navigation intent wakes and rejects an older stationary switch`() = runBlocking {
        val intents = ConversationSwitchIntents()
        val token = intents.begin()
        val handoff = ConversationSwitchHandoff()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { awaitCancellation() } }
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitClosingFrame(isCurrent = { intents.isCurrent(token) }) { 1f }
        }
        assertTrue(intents.busy.value)
        intents.invalidate() // New, route navigation, Back and explicit drawer actions share this.
        Snapshot.sendApplyNotifications()
        assertFalse(withTimeout(5_000) { ready.await() })
        assertFalse(intents.busy.value)
        close.cancelAndJoin()
    }

    @Test fun `a completed old close still cannot replace a newer navigation intent`() = runBlocking {
        val intents = ConversationSwitchIntents()
        val token = intents.begin()
        val handoff = ConversationSwitchHandoff()
        handoff.close { }
        intents.invalidate()
        assertFalse(handoff.awaitClosingFrame(isCurrent = { intents.isCurrent(token) }) { 0f })
    }

    @Test fun `target publication waits for the pre-publish hook to complete`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val finishClose = CompletableDeferred<Unit>()
        val finishHook = CompletableDeferred<Unit>()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { finishClose.await() } }
        var hookRan = false
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitClosingFrame(beforePublish = {
                finishHook.await()
                hookRan = true
            }) { 0.9f }
        }
        try {
            yield()
            assertFalse("target published before the pre-publish hook finished", ready.isCompleted)
            finishHook.complete(Unit)
            assertTrue(withTimeout(5_000) { ready.await() })
            assertTrue(hookRan)
            assertFalse("hook must overlap drawer close, not run after it", close.isCompleted)
        } finally {
            ready.cancelAndJoin()
            close.cancelAndJoin()
        }
    }

    @Test fun `a newer intent during the pre-publish hook prevents the old target from appearing`() = runBlocking {
        val intents = ConversationSwitchIntents()
        val token = intents.begin()
        val handoff = ConversationSwitchHandoff()
        handoff.close { }
        val finishHook = CompletableDeferred<Unit>()
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitClosingFrame(
                isCurrent = { intents.isCurrent(token) },
                beforePublish = { finishHook.await() },
            ) { 0f }
        }
        intents.invalidate()
        finishHook.complete(Unit)
        assertFalse(withTimeout(5_000) { ready.await() })
    }

    @Test fun `drawer interruption during the pre-publish hook prevents target publication`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { handoff.close { awaitCancellation() } }
        val finishHook = CompletableDeferred<Unit>()
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitClosingFrame(beforePublish = { finishHook.await() }) { 0.9f }
        }
        close.cancelAndJoin()
        finishHook.complete(Unit)
        assertFalse(withTimeout(5_000) { ready.await() })
    }

    @Test fun `an older close finally cannot clear the newest switch busy flag`() {
        val intents = ConversationSwitchIntents()
        val old = intents.begin()
        intents.invalidate()
        val latest = intents.begin()
        intents.finish(old)
        assertTrue(intents.busy.value)
        assertTrue(intents.isCurrent(latest))
        assertFalse(intents.isCurrent(old))
        intents.finish(latest)
        assertFalse(intents.busy.value)
    }
}

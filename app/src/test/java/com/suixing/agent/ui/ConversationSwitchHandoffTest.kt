package com.suixing.agent.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchHandoffTest {
    @Test fun `ordinary home frames draw directly without recording or posting callbacks`() {
        val handoff = ConversationSwitchHandoff()
        var content = 0
        var recordings = 0
        val posted = ArrayList<() -> Unit>()
        repeat(120) {
            handoff.draw(content = { content++ }, record = { recordings++ }, post = { posted.add(it) })
        }
        assertEquals(120, content)
        assertEquals(0, recordings)
        assertTrue(posted.isEmpty())
    }

    @Test fun `capture records one frame and resumes after the posted drawing callback`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        var content = 0
        var recordings = 0
        val posted = ArrayList<() -> Unit>()
        val captured = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitDraw(capture = true) }
        try {
            repeat(2) {
                handoff.draw(content = { content++ }, record = { recordings++ }, post = { posted.add(it) })
            }
            assertEquals(1, recordings)
            assertEquals(1, content)
            assertEquals(1, posted.size)
            assertFalse(captured.isCompleted)
            posted.single().invoke()
            withTimeout(5_000) { captured.join() }

            handoff.draw(content = { content++ }, record = { recordings++ }, post = { posted.add(it) })
            assertEquals(1, recordings)
            assertEquals(2, content)
        } finally {
            captured.cancelAndJoin()
        }
    }

    @Test fun `target drawing acknowledgment uses the direct content path`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        var content = 0
        var recordings = 0
        val posted = ArrayList<() -> Unit>()
        val drawn = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitDraw() }
        try {
            handoff.draw(content = { content++ }, record = { recordings++ }, post = { posted.add(it) })
            assertEquals(1, content)
            assertEquals(0, recordings)
            assertEquals(1, posted.size)
            assertFalse(drawn.isCompleted)
            posted.single().invoke()
            withTimeout(5_000) { drawn.join() }
        } finally {
            drawn.cancelAndJoin()
        }
    }

    @Test fun `a cancelled draw and its late callback cannot release the latest capture`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        var recordings = 0
        val posted = ArrayList<() -> Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitDraw() }
        handoff.draw(content = {}, record = { recordings++ }, post = { posted.add(it) })
        old.cancel()
        val latest = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitDraw(capture = true) }
        try {
            old.join()
            assertEquals(1, posted.size)
            posted.removeAt(0).invoke()
            assertFalse(latest.isCompleted)
            handoff.draw(content = {}, record = { recordings++ }, post = { posted.add(it) })
            assertEquals(1, recordings)
            assertFalse(latest.isCompleted)
            posted.single().invoke()
            withTimeout(5_000) { latest.join() }
        } finally {
            old.cancelAndJoin()
            latest.cancelAndJoin()
        }
    }

    @Test fun `closing does not start until the target has rendered`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        val rendered = CompletableDeferred<Unit>()
        var closing = false
        val switch = launch(start = CoroutineStart.UNDISPATCHED) {
            handoff.close(prepare = {
                rendered.await()
                true
            }) { closing = true }
        }
        try {
            assertFalse("target layout must finish before any closing movement", closing)
            rendered.complete(Unit)
            switch.join()
            assertTrue(closing)
        } finally {
            switch.cancelAndJoin()
        }
    }

    @Test fun `a superseded target cannot close the drawer`() = runBlocking {
        val handoff = ConversationSwitchHandoff()
        var closing = false
        handoff.close(prepare = { false }) { closing = true }
        assertFalse("an obsolete target must leave the newer drawer action in control", closing)
    }

    @Test fun `cancelling target preparation leaves the drawer in place`() = runBlocking {
        val rendered = CompletableDeferred<Unit>()
        var closing = false
        val switch = launch(start = CoroutineStart.UNDISPATCHED) {
            ConversationSwitchHandoff().close(prepare = {
                rendered.await()
                true
            }) { closing = true }
        }
        switch.cancelAndJoin()
        rendered.complete(Unit)
        assertFalse(closing)
    }

    @Test fun `a newer navigation during rendering rejects the old close`() = runBlocking {
        val intents = ConversationSwitchIntents()
        val token = intents.begin()
        val rendered = CompletableDeferred<Unit>()
        var closing = false
        val switch = launch(start = CoroutineStart.UNDISPATCHED) {
            ConversationSwitchHandoff().close(prepare = {
                rendered.await()
                intents.isCurrent(token)
            }) { closing = true }
        }
        intents.invalidate()
        val latest = intents.begin()
        rendered.complete(Unit)
        switch.join()
        intents.finish(token)
        assertFalse(closing)
        assertTrue(intents.busy.value)
        intents.finish(latest)
        assertFalse(intents.busy.value)
    }

    @Test fun `the latest switch remains busy until its close finishes`() = runBlocking {
        val intents = ConversationSwitchIntents()
        val token = intents.begin()
        val closed = CompletableDeferred<Unit>()
        val switch = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ConversationSwitchHandoff().close(prepare = { true }) { closed.await() }
            } finally {
                intents.finish(token)
            }
        }
        assertTrue(intents.busy.value)
        closed.complete(Unit)
        switch.join()
        assertFalse(intents.busy.value)
    }
}

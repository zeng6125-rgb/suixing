package com.suixing.agent.ui.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test

class ChatUiLifecycleTest {
    private fun owner(visible: Boolean) = ChatUiLifecycleOwner(visible, LifecycleRegistry::createUnsafe)

    @Test fun `covering chat stops subscriptions and uncovering resumes the same owner`() {
        val owner = owner(true)
        val events = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event -> events.add(event) })
        owner.parentChanged(Lifecycle.State.RESUMED)
        owner.visibilityChanged(false)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        assertEquals(listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP), events.takeLast(2))
        owner.visibilityChanged(true)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        assertEquals(listOf(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME), events.takeLast(2))
        assertFalse(events.contains(Lifecycle.Event.ON_DESTROY))
    }

    @Test fun `chat never becomes more active than its Activity`() {
        val owner = owner(true)
        owner.parentChanged(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        owner.parentChanged(Lifecycle.State.STARTED)
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
        owner.visibilityChanged(false)
        owner.parentChanged(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        owner.visibilityChanged(true)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    @Test fun `a chat created under a covered page remains inactive until shown`() {
        val owner = owner(false)
        owner.parentChanged(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        owner.destroy()
        owner.visibilityChanged(true)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}

package com.suixing.agent.ui.chat

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test fun `drawer freeze pauses collections without recomposing the chat host`() = runBlocking {
        val executor = ArchTaskExecutor.getInstance()
        executor.setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val runner = launch(frameClock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        try {
            val parent = owner(true).apply { parentChanged(Lifecycle.State.RESUMED) }
            val frozen = mutableStateOf(false)
            lateinit var child: LifecycleOwner
            var compositions = 0
            composition.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides parent) {
                    child = rememberChatUiLifecycleOwner { !frozen.value }
                    SideEffect { compositions++ }
                }
            }
            assertEquals(Lifecycle.State.RESUMED, child.lifecycle.currentState)
            for ((index, freeze) in listOf(true, false).withIndex()) {
                frozen.value = freeze
                repeat(3) { frame ->
                    Snapshot.sendApplyNotifications()
                    repeat(8) { yield() }
                    frameClock.sendFrame((index * 3L + frame + 1) * 16_000_000L)
                    repeat(8) { yield() }
                }
                assertEquals(if (freeze) Lifecycle.State.CREATED else Lifecycle.State.RESUMED,
                    child.lifecycle.currentState)
                assertEquals(1, compositions)
            }
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            executor.setDelegate(null)
        }
    }
}

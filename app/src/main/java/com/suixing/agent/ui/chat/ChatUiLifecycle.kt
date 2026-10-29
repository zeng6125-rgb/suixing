package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Retain the chat tree while stopping its UI subscriptions underneath full-screen destinations. */
internal class ChatUiLifecycleOwner(
    visible: Boolean,
    registryFactory: (LifecycleOwner) -> LifecycleRegistry = { LifecycleRegistry(it) },
) : LifecycleOwner {
    private val registry = registryFactory(this)
    override val lifecycle: Lifecycle get() = registry
    private var parentState = Lifecycle.State.INITIALIZED
    private var visible = visible

    fun parentChanged(state: Lifecycle.State) { parentState = state; update() }
    fun visibilityChanged(value: Boolean) { visible = value; update() }
    fun destroy() { parentChanged(Lifecycle.State.DESTROYED) }

    private fun update() {
        registry.currentState = minOf(parentState, if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
    }
}

@Composable
internal fun rememberChatUiLifecycleOwner(visible: Boolean): LifecycleOwner {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { ChatUiLifecycleOwner(visible) }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { source, _ -> owner.parentChanged(source.lifecycle.currentState) }
        parent.lifecycle.addObserver(observer)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.destroy()
        }
    }
    SideEffect { owner.visibilityChanged(visible) }
    return owner
}

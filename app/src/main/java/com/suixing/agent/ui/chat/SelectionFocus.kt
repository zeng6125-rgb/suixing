package com.suixing.agent.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf

/** Track only message selection, so dismissing it never blurs the composer. */
internal class SelectionFocusTracker {
    private val owners = HashSet<Any>()
    val active: Boolean get() = owners.isNotEmpty()
    fun update(owner: Any, focused: Boolean) {
        if (focused) owners.add(owner) else owners.remove(owner)
    }
    fun remove(owner: Any) { owners.remove(owner) }
}

internal val LocalSelectionFocusTracker = staticCompositionLocalOf<SelectionFocusTracker?> { null }

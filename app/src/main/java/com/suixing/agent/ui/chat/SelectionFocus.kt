package com.suixing.agent.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar

/** Track only message selection, so dismissing it never blurs the composer. */
internal class SelectionFocusTracker {
    private val owners = HashSet<Any>()
    private data class Selection(val selected: () -> Boolean, val clear: () -> Unit)
    private val selections = HashMap<Any, Selection>()
    val active: Boolean get() = owners.isNotEmpty() || selections.values.any { it.selected() }

    fun register(owner: Any, selected: () -> Boolean, clear: () -> Unit) {
        selections[owner] = Selection(selected, clear)
    }

    /** Release the actual selection before focus is cleared; never touch the composer. */
    fun clear() {
        selections.values.toList().forEach { it.clear() }
    }
    fun update(owner: Any, focused: Boolean) {
        if (focused) owners.add(owner) else owners.remove(owner)
    }
    fun remove(owner: Any) {
        owners.remove(owner)
        selections.remove(owner)
    }
}

internal val LocalSelectionFocusTracker = staticCompositionLocalOf<SelectionFocusTracker?> { null }

/** The legacy Android toolbar path needs the same ownership check as the new context menu. */
internal class SelectionTextToolbar(
    private val delegate: TextToolbar,
    private val selected: () -> Boolean,
) : TextToolbar by delegate {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        if (selected()) delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
        else delegate.hide()
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        if (selected()) delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, onAutofillRequested)
        else delegate.hide()
    }
}

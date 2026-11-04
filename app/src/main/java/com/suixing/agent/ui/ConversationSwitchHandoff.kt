package com.suixing.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Only the newest drawer/navigation intent may publish a conversation switch. */
internal class ConversationSwitchIntents {
    private var generation by mutableIntStateOf(0)
    val busy = mutableStateOf(false)
    private val departure = Animatable(1f)
    private var departureOwner by mutableStateOf<String?>(null)

    /** Read by the transcript and rail in their draw phase, never by the shell's composition. */
    fun departureAlpha(conversationId: String): Float =
        if (departureOwner == conversationId) departure.value else 1f

    suspend fun depart(token: Int, conversationId: String) {
        if (!isCurrent(token)) return
        if (departureOwner != conversationId) {
            departure.snapTo(1f)
            if (!isCurrent(token)) return
            departureOwner = conversationId
        }
        // A second selection of the same outgoing chat continues from the current opacity.
        departure.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
    }

    fun cancelDeparture(token: Int) {
        if (isCurrent(token)) departureOwner = null
    }

    fun begin(): Int {
        generation++
        busy.value = true
        return generation
    }

    fun invalidate() {
        generation++
        busy.value = false
        departureOwner = null
    }

    fun isCurrent(token: Int): Boolean = token == generation

    fun finish(token: Int) {
        // Keep the source hidden until the next navigation intent. Clearing its opacity here
        // could reveal a late source frame before the target composition has applied.
        if (isCurrent(token)) busy.value = false
    }
}

/** Retain the source through departure; a cancelled or superseded close cannot hand over. */
internal class ConversationSwitchHandoff {
    private var finished by mutableStateOf(false)
    private var completed by mutableStateOf(false)

    suspend fun close(block: suspend () -> Unit) {
        try {
            block()
            completed = true
        } finally {
            finished = true
        }
    }

    suspend fun awaitClosingFrame(
        isCurrent: () -> Boolean = { true },
        depart: suspend () -> Unit = {},
        progress: () -> Float,
    ): Boolean {
        snapshotFlow { !isCurrent() || finished || progress() < 1f }.first { it }
        if (!isCurrent() || finished && !completed) return false
        depart()
        return isCurrent() && (!finished || completed)
    }
}

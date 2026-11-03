package com.suixing.agent.ui

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

    fun begin(): Int {
        generation++
        busy.value = true
        return generation
    }

    fun invalidate() {
        generation++
        busy.value = false
    }

    fun isCurrent(token: Int): Boolean = token == generation

    fun finish(token: Int) {
        if (isCurrent(token)) busy.value = false
    }
}

/** The first moving close frame may hand over; a cancelled close never counts as arrival. */
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
        progress: () -> Float,
    ): Boolean {
        snapshotFlow { !isCurrent() || finished || progress() < 1f }.first { it }
        return isCurrent() && (!finished || completed)
    }
}

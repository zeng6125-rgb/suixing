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
        // 只有仍是最新意图的那次关闭才能收尾；旧意图不能清掉新一次的 busy。
        if (isCurrent(token)) busy.value = false
    }
}

/** A cancelled or superseded close cannot hand over to a target conversation. */
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

    /**
     * Wait for the first real closing frame, then hand over to the target.
     *
     * [beforePublish] is an optional suspend hook between the frame wait and publication:
     * a cancellation or a newer intent arriving while it is suspended must still be rejected
     * by the checks below. It used to drive the source's fade-out; the outgoing transcript is
     * now replaced immediately (no opacity transition), so this hook is only about the
     * handover timing and cancellation safety.
     */
    suspend fun awaitClosingFrame(
        isCurrent: () -> Boolean = { true },
        beforePublish: suspend () -> Unit = {},
        progress: () -> Float,
    ): Boolean {
        snapshotFlow { !isCurrent() || finished || progress() < 1f }.first { it }
        if (!isCurrent() || finished && !completed) return false
        beforePublish()
        return isCurrent() && (!finished || completed)
    }
}

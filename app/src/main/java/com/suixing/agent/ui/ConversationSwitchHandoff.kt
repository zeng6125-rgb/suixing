package com.suixing.agent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

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

@PublishedApi
internal class ConversationDrawRequest(val capture: Boolean) {
    val completed = CompletableDeferred<Unit>()
    var queued = false
}

/** Target composition, restoration and drawing finish before the page starts moving. */
internal class ConversationSwitchHandoff {
    @PublishedApi
    internal var pendingDraw by mutableStateOf<ConversationDrawRequest?>(null)

    suspend fun awaitDraw(capture: Boolean = false) {
        val request = ConversationDrawRequest(capture)
        pendingDraw = request
        try {
            request.completed.await()
        } finally {
            if (pendingDraw === request) pendingDraw = null
        }
    }

    internal inline fun draw(content: () -> Unit, record: () -> Unit, post: (() -> Unit) -> Unit) {
        val request = pendingDraw
        if (request?.capture == true && !request.queued) record() else content()
        if (request != null && !request.queued) {
            request.queued = true
            post { request.completed.complete(Unit) }
        }
    }

    suspend fun close(prepare: suspend () -> Boolean, block: suspend () -> Unit) {
        if (prepare()) block()
    }
}

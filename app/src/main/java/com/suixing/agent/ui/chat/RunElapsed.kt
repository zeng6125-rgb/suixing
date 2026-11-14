package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.suixing.agent.data.StoredMessage

internal fun runElapsedById(messages: List<StoredMessage>): Map<String, Long> = buildMap {
    for (message in messages) {
        val run = message.runId ?: continue
        if (run.isNotEmpty() && message.elapsedMs > 0) put(run, (get(run) ?: 0L) + message.elapsedMs)
    }
    for (message in messages) {
        val run = message.runId ?: continue
        if (run.isNotEmpty()) message.runElapsedMs?.let { put(run, it) }
    }
}

internal fun runStartedAtMillis(startedAtNanos: Long, nowNanos: Long, nowMillis: Long): Long =
    nowMillis - ((nowNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

/** Both visible captions read this one sample; neither starts a clock when it is composed. */
@Composable
internal fun rememberRunElapsed(startedAt: Long, running: Boolean, clockKey: Any = startedAt): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return key(clockKey, lifecycle) {
        produceState(if (running && startedAt > 0) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L) else 0L,
            startedAt, running) {
            // Keep the last sample through the brief terminal-state -> stored-row handover.
            // A different run key creates a fresh State; a stopped run never flashes back to 0.
            if (!running || startedAt <= 0) return@produceState
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runElapsedTicker(startedAt, System::currentTimeMillis, { delay(it) }) { value = it }
            }
        }
    }
}

/** Sample against the run's origin, including after a hidden/offscreen interval. */
internal suspend fun runElapsedTicker(
    startedAt: Long,
    now: () -> Long,
    wait: suspend (Long) -> Unit,
    emit: (Long) -> Unit,
) {
    if (startedAt <= 0L) return
    while (currentCoroutineContext().isActive) {
        val elapsed = (now() - startedAt).coerceAtLeast(0L)
        emit(elapsed)
        wait(1_000L - elapsed % 1_000L)
    }
}

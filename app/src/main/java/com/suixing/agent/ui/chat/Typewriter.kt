package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.ceil

/** Run/part identity retains progress through live-to-stored handoff. */
internal class TextRevealState {
    var revealed by mutableIntStateOf(-1)
    var everStreamed = false
    var startedTyping = false

    fun startIfNeeded(enabled: Boolean) {
        if (!startedTyping && enabled) {
            startedTyping = true
            everStreamed = true
            revealed = 0
        }
    }

    fun advance(goal: Int) {
        advanceBy(goal, typeStep(goal - revealed.coerceAtLeast(0)))
    }

    fun advanceBy(goal: Int, amount: Int) {
        revealed = (revealed.coerceAtLeast(0) + amount).coerceAtMost(goal)
    }
}

/** Only streamed text reveals gradually; historical text never replays. Idle streams suspend. */
@Composable
internal fun rememberTypedText(
    text: String,
    enabled: Boolean,
    bornAtMs: Long = 0L,
    startAtZero: Boolean = false,
    retained: TextRevealState? = null,
): String {
    if (!LocalTypewriter.current) return text
    val progress = retained ?: remember { TextRevealState() }
    if (enabled) progress.everStreamed = true
    if (!progress.everStreamed) return text
    val target = rememberUpdatedState(text)
    remember(progress) {
        if (progress.revealed < 0) progress.revealed =
            if (startAtZero) 0 else initialRevealed(text.length, nowMs() - bornAtMs)
        progress
    }
    val enabledNow = rememberUpdatedState(enabled)
    LaunchedEffect(progress) {
        snapshotFlow { target.value.length to enabledNow.value }.collect {
            progress.startIfNeeded(enabledNow.value)
            var previousFrame = withFrameNanos { it }
            var credit = 0.0
            while (progress.revealed < target.value.length) {
                val frame = withFrameNanos { it }
                // Preserve speed across 60/90/120 Hz; avoid a huge jump after a stalled frame.
                val elapsed = ((frame - previousFrame) / 1_000_000.0).coerceIn(0.0, 32.0)
                previousFrame = frame
                val latest = target.value
                credit += typeStep(latest.length - progress.revealed.coerceAtLeast(0)) * elapsed / 16.0
                val step = credit.toInt()
                if (step > 0) {
                    credit -= step
                    progress.advanceBy(latest.length, step)
                }
            }
        }
    }
    return remember(progress.revealed, text) {
        val end = revealBoundary(text, progress.revealed.coerceIn(0, text.length))
        if (end >= text.length) text else text.take(end)
    }
}

internal fun initialRevealed(textLength: Int, ageMs: Long): Int =
    if (ageMs in 0L..500L) 0 else textLength

/** Drain batches over several frames rather than revealing almost all of a batch in one frame. */
internal fun typeStep(backlog: Int): Int = maxOf(2, ceil(backlog.coerceAtLeast(0) / 4.0).toInt())

/** Do not emit half of a UTF-16 surrogate pair (including a pair split across network chunks). */
internal fun revealBoundary(text: String, requested: Int): Int {
    val end = requested.coerceIn(0, text.length)
    return if (end > 0 && text[end - 1].isHighSurrogate()) end - 1 else end
}

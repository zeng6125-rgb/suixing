package com.suixing.agent.service

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.widget.ImageView

/**
 * Plays the floating ball's eye loop: a blink while idle, a left/right look while a run is live.
 *
 * Frames live in `assets/ball_motion/<mode>/` as `<index>_<duration-ms>.png`. Consecutive identical
 * GIF frames were collapsed into one PNG whose filename carries the whole hold, so the loops are
 * 9 and 23 frames instead of 60 and 90. Decoded bitmaps are cached for the service's lifetime, so
 * a mode switch never decodes twice.
 *
 * Scheduling is explicit: [onAttach] starts the loop, [onDetach] stops it. An overlay window taken
 * out of the window tree must not keep a delayed frame callback running against an invisible view —
 * the same reason the old status dot's pulse was cancelled on detach.
 */
internal class BallMotion(
    private val context: Context,
    private val target: ImageView,
    private val handler: Handler,
) {
    private enum class Mode(val dir: String) {
        Blink("blink"),
        Look("look"),
    }

    private data class Frame(val drawable: BitmapDrawable, val durationMs: Long)

    private val frames = mutableMapOf<Mode, List<Frame>>()
    private var mode: Mode = Mode.Blink
    private var index = 0
    private var attached = false

    // Explicit type: the runnable re-posts itself, so inference would be recursive.
    private val tick: Runnable = object : Runnable {
        override fun run() {
            val list = framesOf(mode)
            if (!attached || list.isEmpty()) return
            val frame = list[index]
            target.setImageDrawable(frame.drawable)
            index = (index + 1) % list.size
            handler.postDelayed(tick, frame.durationMs)
        }
    }

    /**
     * Idle blinks; a live run looks left/right. A repeat of the current mode is a no-op, so the
     * engine's ~8 Hz state stream cannot restart the loop on every tick.
     */
    fun setRunning(running: Boolean) = setMode(if (running) Mode.Look else Mode.Blink)

    private fun scheduleNext(delayMs: Long = 0L) {
        handler.removeCallbacks(tick)
        if (delayMs > 0L) handler.postDelayed(tick, delayMs) else handler.post(tick)
    }

    private fun setMode(next: Mode) {
        if (next == mode) return
        mode = next
        index = 0
        if (attached) scheduleNext()
    }

    fun onAttach() {
        attached = true
        scheduleNext()
    }

    fun onDetach() {
        attached = false
        handler.removeCallbacks(tick)
    }

    private fun framesOf(mode: Mode): List<Frame> = frames.getOrPut(mode) {
        val dir = "ball_motion/${mode.dir}"
        val names = runCatching { context.assets.list(dir) }.getOrNull().orEmpty().sorted()
        names.mapNotNull { name ->
            val duration = name.substringAfterLast('_').substringBefore('.').toLongOrNull()
                ?: return@mapNotNull null
            val bitmap = runCatching {
                context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@mapNotNull null
            Frame(BitmapDrawable(context.resources, bitmap), duration)
        }
    }
}

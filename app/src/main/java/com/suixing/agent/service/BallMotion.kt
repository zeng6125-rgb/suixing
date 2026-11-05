package com.suixing.agent.service

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.widget.ImageView

/**
 * Plays the floating ball's face loops from the delivered icon motion set.
 *
 * Frames live in `assets/ball_motion/<mode>/` as `<index>_<duration-ms>.png`, one PNG per
 * distinct GIF frame (identical consecutive frames are collapsed by the build script).
 * Decoded bitmaps are cached for the service's lifetime, so switching modes never decodes
 * a loop twice. All asset decoding happens lazily on first use of that mode.
 *
 * Two ambient loops follow run state: [Mode.Blink] while idle, [Mode.Roll] while a run is
 * live. Two one-shots play on events and then return to the ambient loop: [Mode.Sparks]
 * when a run starts, [Mode.Head] when the ball is tapped.
 *
 * Scheduling is explicit: [onAttach] starts the loop, [onDetach] stops it. An overlay
 * window taken out of the window tree must not keep a delayed frame callback running
 * against an invisible view.
 */
internal class BallMotion(
    private val context: Context,
    private val target: ImageView,
    private val handler: Handler,
) {
    private enum class Mode(val dir: String, val loop: Boolean) {
        Blink("blink", true),
        Roll("roll", true),
        Head("head", false),
        Sparks("sparks", false),
    }

    private data class Frame(val drawable: BitmapDrawable, val durationMs: Long)

    /**
     * The delivered GIFs move on 40–140ms frames, which reads as a flicker at 42dp.
     * Stretch the short motion frames but leave the long holds alone, so a blink still
     * rests ~1.1s between blinks while the lid movement is actually visible.
     */
    private fun frameDuration(rawMs: Long): Long = when {
        rawMs >= 300L -> rawMs          // idle / hold frames keep their pace
        else -> (rawMs * 3L).coerceAtMost(240L)
    }

    private val frames = mutableMapOf<Mode, List<Frame>>()
    private var mode: Mode = Mode.Blink
    /** The ambient loop a one-shot must return to when it finishes. */
    private var ambient: Mode = Mode.Blink
    private var index = 0
    private var attached = false

    // Explicit type: the runnable re-posts itself, so inference would be recursive.
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!attached) return
            val list = framesOf(mode)
            if (list.isEmpty()) return
            val frame = list[index]
            target.setImageDrawable(frame.drawable)
            val next = index + 1
            if (next >= list.size) {
                if (mode.loop) {
                    index = 0
                } else {
                    // One-shot finished: go back to whatever the run state says.
                    index = 0
                    mode = ambient
                    handler.postDelayed(tick, frame.durationMs)
                    return
                }
            } else {
                index = next
            }
            handler.postDelayed(tick, frame.durationMs)
        }
    }

    /** Idle blinks; a live run rolls its eyes. A repeat of the current mode is a no-op. */
    fun setRunning(running: Boolean) {
        ambient = if (running) Mode.Roll else Mode.Blink
        // Don't interrupt a one-shot; it will fall back to the ambient loop itself.
        if (mode.loop) setMode(ambient)
    }

    /** One-shot when a run starts: the sparks fly in once. */
    fun playRunStart() = playOneShot(Mode.Sparks)

    /** One-shot when the ball is tapped: the head turns once. */
    fun playTap() = playOneShot(Mode.Head)

    private fun playOneShot(oneShot: Mode) {
        if (!attached) return
        mode = oneShot
        index = 0
        scheduleNext()
    }

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
        mode = ambient
        index = 0
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
            Frame(BitmapDrawable(context.resources, bitmap), frameDuration(duration))
        }
    }
}

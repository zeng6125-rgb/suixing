package com.suixing.agent.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
    /** ~22 fps: short motion frames (and the blended frames between them) play at this cadence. */
    private val motionFrameMs = 45L
    /** At or above this, a frame is an idle/hold: keep its own duration and never tween it. */
    private val holdFrameMs = 300L

    private enum class Mode(val dir: String, val loop: Boolean) {
        Blink("blink", true),
        Roll("roll", true),
        Head("head", false),
        Sparks("sparks", false),
    }

    private data class Frame(val drawable: BitmapDrawable, val durationMs: Long)

    /**
     * The delivered GIFs are only 9–14 distinct frames per motion. Playing those at their
     * raw 40–70ms reads as a flicker, and stretching each to 120–240ms reads as a stutter
     * (4–8 fps). So instead we synthesise one half-way frame between every pair of short
     * motion frames and play the whole run at a steady [motionFrameMs] (~22 fps):
     * the eye/head movement becomes continuous while one full motion keeps roughly its
     * original duration. Long idle/hold frames are left untouched — interpolating a rest
     * would only stretch the pause, not smooth it.
     *
     * The blend is a straight per-pixel ARGB lerp, not two stacked translucent drawables:
     * fully transparent corners stay transparent and opaque pixels stay opaque (verified),
     * so the cream tile never washes out.
     */
    private fun frameDuration(rawMs: Long): Long =
        if (rawMs >= holdFrameMs) rawMs else motionFrameMs

    private fun blend(a: Bitmap, b: Bitmap): Bitmap {
        val width = a.width
        val height = a.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowA = IntArray(width)
        val rowB = IntArray(width)
        val rowOut = IntArray(width)
        for (y in 0 until height) {
            a.getPixels(rowA, 0, width, 0, y, width, 1)
            b.getPixels(rowB, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val ca = rowA[x]
                val cb = rowB[x]
                rowOut[x] = Color.argb(
                    (Color.alpha(ca) + Color.alpha(cb)) / 2,
                    (Color.red(ca) + Color.red(cb)) / 2,
                    (Color.green(ca) + Color.green(cb)) / 2,
                    (Color.blue(ca) + Color.blue(cb)) / 2,
                )
            }
            out.setPixels(rowOut, 0, width, 0, y, width, 1)
        }
        return out
    }

    /** Build the played frame list: every short-frame transition gains one blended frame. */
    private fun withInterpolatedFrames(raw: List<Pair<Bitmap, Long>>): List<Frame> {
        val result = ArrayList<Frame>(raw.size * 2)
        for (i in raw.indices) {
            val (bitmap, durationMs) = raw[i]
            result += Frame(BitmapDrawable(context.resources, bitmap), frameDuration(durationMs))
            val next = raw.getOrNull(i + 1)
            // Only insert a tween when BOTH sides are motion frames; a hold stays still.
            if (next != null && durationMs < holdFrameMs && next.second < holdFrameMs) {
                val middle = blend(bitmap, next.first)
                result += Frame(BitmapDrawable(context.resources, middle), motionFrameMs)
            }
        }
        return result
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
        val decoded = names.mapNotNull { name ->
            val duration = name.substringAfterLast('_').substringBefore('.').toLongOrNull()
                ?: return@mapNotNull null
            val bitmap = runCatching {
                context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@mapNotNull null
            bitmap to duration
        }
        // Decoding + blending happens once per mode, lazily, cached for the service's life.
        withInterpolatedFrames(decoded)
    }
}

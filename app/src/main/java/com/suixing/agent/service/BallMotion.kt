package com.suixing.agent.service

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.widget.ImageView
import com.suixing.agent.R
import com.suixing.agent.core.RoundIconClip
import com.suixing.agent.core.RoundIconFrames
import com.suixing.agent.core.RoundIconMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Plays the delivered round artwork with the same running loop as the in-app brand marks. */
internal class BallMotion(
    private val context: Context,
    private val target: ImageView,
    private val handler: Handler,
    private val scope: CoroutineScope,
) {
    private var ambient = RoundIconMotion.Blink
    private var mode = ambient
    private var clip: RoundIconClip? = null
    private var attached = false
    private var generation = 0L
    private var startedAt = 0L
    private var loadJob: Job? = null
    private var durationScale = 1f
    private var observingScale = false
    private val scaleObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            durationScale = readDurationScale()
            if (!attached) return
            handler.removeCallbacks(tick)
            if (clip != null) handler.post(tick) else setMode(mode, restart = true)
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!attached) return
            if (durationScale == 0f) {
                target.setImageResource(R.drawable.brand_mark_round)
                return
            }
            val current = clip ?: return
            val now = SystemClock.uptimeMillis()
            val elapsed = ((if (mode.loop) now else now - startedAt) / durationScale.toDouble()).toLong()
            if (!mode.loop && elapsed >= current.durationMs) {
                setMode(ambient)
                return
            }
            val index = current.frameAt(elapsed)
            val bitmap = current.frames[index].bitmap
            // A scale notification or an early millisecond boundary can repeat the same frame.
            if ((target.drawable as? BitmapDrawable)?.bitmap !== bitmap) {
                target.setImageBitmap(bitmap)
            }
            // Use the original timeline rather than accumulating a late callback every frame.
            handler.postDelayed(this,
                (current.remainingMs(elapsed, index) * durationScale).toLong().coerceAtLeast(1L))
        }
    }

    fun setRunning(running: Boolean) {
        ambient = if (running) RoundIconMotion.SignalBlink else RoundIconMotion.Blink
        if (mode.loop) setMode(ambient)
    }

    fun playRunStart() = playOneShot(RoundIconMotion.Sparks)

    fun playTap() = playOneShot(
        if (mode == RoundIconMotion.HeadTurn) RoundIconMotion.EyeRoll else RoundIconMotion.HeadTurn,
    )

    private fun playOneShot(motion: RoundIconMotion) {
        if (attached) setMode(motion, restart = true)
    }

    private fun setMode(next: RoundIconMotion, restart: Boolean = false) {
        if (mode == next && !restart) return
        mode = next
        val owner = ++generation
        loadJob?.cancel()
        loadJob = null
        handler.removeCallbacks(tick)
        clip = null
        if (!attached) return
        if (durationScale == 0f) {
            target.setImageResource(R.drawable.brand_mark_round)
            return
        }
        val cached = RoundIconFrames.peek(next)
        if (cached != null) {
            show(cached)
        } else {
            loadJob = scope.launch {
                val loaded = RoundIconFrames.load(context, next) ?: return@launch
                if (attached && generation == owner) show(loaded)
            }
        }
    }

    private fun show(loaded: RoundIconClip) {
        clip = loaded
        startedAt = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun onAttach() {
        attached = true
        if (!observingScale) {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver,
            )
            observingScale = true
        }
        durationScale = readDurationScale()
        setMode(ambient, restart = true)
    }

    fun onDetach() {
        attached = false
        generation++
        loadJob?.cancel()
        loadJob = null
        clip = null
        handler.removeCallbacks(tick)
        if (observingScale) {
            context.contentResolver.unregisterContentObserver(scaleObserver)
            observingScale = false
        }
    }

    private fun readDurationScale(): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() && it >= 0f } ?: 1f
}

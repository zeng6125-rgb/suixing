package com.suixing.agent.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The five delivered round animations; running always uses SignalBlink. */
internal enum class RoundIconMotion(val directory: String, val loop: Boolean) {
    Blink("blink", true),
    HeadTurn("head", false),
    EyeRoll("roll", false),
    Sparks("sparks", false),
    SignalBlink("signal", true),
}

internal data class RoundIconFrame(val bitmap: Bitmap, val durationMs: Long)

internal class RoundIconClip(val frames: List<RoundIconFrame>) {
    private val ends = LongArray(frames.size)
    val durationMs: Long
    val byteCount: Int = frames.sumOf { it.bitmap.byteCount }

    init {
        var elapsed = 0L
        frames.forEachIndexed { index, frame ->
            elapsed += frame.durationMs
            ends[index] = elapsed
        }
        durationMs = elapsed
    }

    fun frameAt(elapsedMs: Long): Int {
        val result = ends.binarySearch(elapsedMs.mod(durationMs) + 1L)
        return if (result >= 0) result else -result - 1
    }

    fun remainingMs(elapsedMs: Long, index: Int): Long =
        ends[index] - elapsedMs.mod(durationMs)
}

/** Shared bitmap cache: off-main decoding, original timing, no runtime pixel blending. */
internal object RoundIconFrames {
    private val cache = object : LruCache<RoundIconMotion, RoundIconClip>(12 * 1024 * 1024) {
        override fun sizeOf(key: RoundIconMotion, value: RoundIconClip): Int = value.byteCount
    }
    // This index adds no strong owners and reuses evicted clips only while consumers keep them alive.
    private val liveClips = ConcurrentHashMap<RoundIconMotion, WeakReference<RoundIconClip>>()
    private val decodeLock = Mutex()

    fun peek(motion: RoundIconMotion): RoundIconClip? = cache.get(motion) ?: liveClips[motion]?.get()

    suspend fun load(context: Context, motion: RoundIconMotion): RoundIconClip? {
        peek(motion)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeLock.withLock {
                peek(motion)?.let { return@withLock it }
                val assets = context.applicationContext.assets
                val directory = "ball_motion/${motion.directory}"
                val names = assets.list(directory).orEmpty().filter { it.endsWith(".webp") }.sorted()
                val frames = ArrayList<RoundIconFrame>(names.size)
                for (name in names) {
                    currentCoroutineContext().ensureActive()
                    val duration = name.substringAfterLast('_').substringBefore('.').toLongOrNull()
                        ?.takeIf { it > 0L } ?: continue
                    val bitmap = assets.open("$directory/$name").use { BitmapFactory.decodeStream(it) }
                        ?: continue
                    frames += RoundIconFrame(bitmap, duration)
                }
                if (frames.isEmpty()) null else RoundIconClip(frames).also {
                    liveClips[motion] = WeakReference(it)
                    cache.put(motion, it)
                }
            }
        }
    }
}

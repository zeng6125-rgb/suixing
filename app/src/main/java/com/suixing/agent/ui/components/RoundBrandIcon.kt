package com.suixing.agent.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.suixing.agent.R
import com.suixing.agent.core.RoundIconFrames
import com.suixing.agent.core.RoundIconMotion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Decorative round artwork. Frame ticks invalidate drawing, never the surrounding chat row. */
@Composable
internal fun RoundBrandIcon(
    size: Dp,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    animateIdle: Boolean = false,
    animationEnabled: Boolean = true,
) {
    if (!running && !animateIdle) {
        Image(
            painter = painterResource(R.drawable.brand_mark_round),
            contentDescription = null,
            modifier = modifier.size(size),
        )
        return
    }

    val motion = if (running) RoundIconMotion.SignalBlink else RoundIconMotion.Blink
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val clip = remember(motion) { mutableStateOf(RoundIconFrames.peek(motion)) }
    val frame = remember(motion) { mutableIntStateOf(0) }
    // Retain the loaded images, renderer and last frame while pausing only the clock.
    if (animationEnabled && LocalDecorationMotion.current()) {
        LaunchedEffect(motion, owner) {
            val durationScale = currentCoroutineContext()[MotionDurationScale]
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                    if (scale <= 0f) {
                        frame.intValue = 0
                        return@collectLatest
                    }
                    val loaded = RoundIconFrames.load(context, motion) ?: return@collectLatest
                    clip.value = loaded
                    while (currentCoroutineContext().isActive) {
                        val elapsed = (SystemClock.uptimeMillis() / scale.toDouble()).toLong()
                        val index = loaded.frameAt(elapsed)
                        frame.intValue = index
                        delay((loaded.remainingMs(elapsed, index) * scale).toLong().coerceAtLeast(1L))
                    }
                }
            }
        }
    }
    val loaded = clip.value
    if (loaded == null) {
        Image(
            painter = painterResource(R.drawable.brand_mark_round),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    } else {
        val images = remember(loaded) { loaded.frames.map { it.bitmap.asImageBitmap() } }
        Canvas(modifier.size(size)) {
            drawImage(
                image = images[frame.intValue],
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            )
        }
    }
}

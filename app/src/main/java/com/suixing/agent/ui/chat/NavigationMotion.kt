package com.suixing.agent.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keep the transcript visible throughout a single scroll to the shared navigation offset. */
internal class NavigationMotion {
    suspend fun navigate(list: LazyListState, index: Int, offset: Int) {
        val layout = list.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val distance = item?.let { it.offset.toLong() + offset }
        val close = distance != null && abs(distance) <= layout.viewportSize.height.toLong()
        if (close) {
            // Already measured nearby rows have an exact distance. One scroll,
            // with zero initial/final velocity rather than the default spring.
            list.animateScrollBy(distance.toFloat(), tween(
                navigationDuration(distance, layout.viewportSize.height), easing = SCROLL_EASING))
        } else {
            // Let the lazy list discover and scroll through unmeasured rows. Its
            // final placement uses the same offset as the nearby measured path.
            list.animateScrollToItem(index, offset)
        }
    }

    private companion object {
        val SCROLL_EASING = CubicBezierEasing(0.22f, 0f, 0.14f, 1f)
    }
}

internal fun navigationDuration(distance: Long, viewportHeight: Int): Int =
    (240f + 100f * (abs(distance).toFloat() / viewportHeight.coerceAtLeast(1)).coerceIn(0f, 1f)).roundToInt()

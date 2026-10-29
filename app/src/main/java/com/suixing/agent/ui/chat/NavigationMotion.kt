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
        // 慢入快出的曲线在长距离上显得"弹一下就停"，观感生硬；换成标准减速曲线
        // （起手柔和、尾段收敛），配合更长的时长读起来才像"滑过去"而不是"跳过去"。
        val SCROLL_EASING = CubicBezierEasing(0.33f, 0f, 0.16f, 1f)
    }
}

internal fun navigationDuration(distance: Long, viewportHeight: Int): Int =
    // 240–340ms → 420–640ms：用户反馈"太快、太生硬"。近距离 420ms 保证看得清移动方向，
    // 远距离封顶 640ms，再长就会觉得"点了半天没到"。
    (420f + 220f * (abs(distance).toFloat() / viewportHeight.coerceAtLeast(1)).coerceIn(0f, 1f)).roundToInt()

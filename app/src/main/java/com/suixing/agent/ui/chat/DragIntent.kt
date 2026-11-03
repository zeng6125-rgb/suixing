package com.suixing.agent.ui.chat

/**
 * 一次拖动被判定成什么（issue #131：侧滑时主页偶发上下移动）。
 *
 * ## 为什么需要它
 *
 * 列表上的被动观察器原来只看 `dy`：**任一方向只要纵向越过 4px 就取消追底并记为"移动"**。
 * 侧滑进抽屉时手指必然带一点纵向抖动，于是：
 * - 追底被取消（用户读到一半的内容不再跟随，观感是"内容自己挪了"）；
 * - `moved` 被置真（长按/选区判定跟着受影响）。
 *
 * 两件事必须分开：
 *
 * - [Moved]：**任一方向**越过阈值 ⇒ 手指确实在拖，取消长按/点击、解除 reveal 拦截；
 * - [VerticalScroll]：**纵向主导**（|dy| ≥ |dx|）且越阈值 ⇒ 才可以取消追底。
 *
 * 水平主导的手势（侧滑抽屉、表格/代码横向滚动）只算 [Moved]，不再动追底状态。
 *
 * 阈值仍是 [CANCEL_FOLLOW_PX]（4px），**不能**换成 `touchSlop`：历史上的用户要求是
 * "轻微纵滑也要停止追底"，抬高阈值会把轻滑重新变成"松手被贴回底部"。
 */
internal enum class DragIntent {
    /** 还在原地（抖动小于阈值），什么都不能定。 */
    Idle,

    /** 手指在拖，但不是纵向主导。取消长按/点击，但**不动**追底。 */
    Moved,

    /** 纵向主导的拖动 —— 这是阅读行为，取消追底并解除 reveal 拦截。 */
    VerticalScroll,
}

/**
 * 把「相对按下点的累计位移」分类。累计而不是逐帧增量：逐帧判断会让
 * "先横后竖"的手势在某一帧被误判，而累计值能稳定反映整段手势的方向。
 */
internal fun dragIntent(dx: Float, dy: Float, thresholdPx: Float = CANCEL_FOLLOW_PX): DragIntent {
    val ax = kotlin.math.abs(dx)
    val ay = kotlin.math.abs(dy)
    if (ax <= thresholdPx && ay <= thresholdPx) return DragIntent.Idle
    // 纵向主导才取消追底；等值（45°）也按纵向处理：宁可停跟，也别在斜着滑时跟丢内容。
    return if (ay > thresholdPx && ay >= ax) DragIntent.VerticalScroll else DragIntent.Moved
}

/**
 * 一次手势内的两个 one-shot 标记（issue #73）。
 *
 * [moved] 管长按/点击与 reveal；取消追底另有标记 —— 先横向 [DragIntent.Moved]
 * 不能吃掉后一帧才出现的 [DragIntent.VerticalScroll]。每次按下新建一个实例即重置。
 */
internal class DragGestureState {
    var moved = false
        private set
    var followCancelled = false
        private set

    /** 首次非 [DragIntent.Idle] 且仍按下的判定返回 true。 */
    fun markMoved(intent: DragIntent, pressed: Boolean): Boolean {
        if (moved || !pressed || intent == DragIntent.Idle) return false
        moved = true
        return true
    }

    /** 首次纵向判定返回 true（哪怕此前已 [markMoved]）；每次手势最多一次。 */
    fun markFollowCancelled(intent: DragIntent, pressed: Boolean): Boolean {
        if (followCancelled || !pressed || intent != DragIntent.VerticalScroll) return false
        followCancelled = true
        return true
    }
}

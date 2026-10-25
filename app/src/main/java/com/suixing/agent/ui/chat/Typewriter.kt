package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * 打字机：把**已经收到**的文本按"追得上的速度"逐字放出来。
 *
 * 三条约束决定了它长这样：
 *
 * 1. **不许落后**。积压越多放得越快（每个节拍放 backlog/4 个字，至少 2 个），稳态落后
 *    大约 4 个节拍 ≈ 160ms —— 看得见在打字，但读完不会被吊在后面。固定速度（比如每帧 1 字）
 *    在快端点上会越积越多，最后变成一个永远追不上的尾巴，那是比整块跳更糟的体验。
 * 2. **不许逐帧重排 Markdown**。节拍是 40ms（25 次/秒），不是每一帧：正文每换一次前缀都要
 *    整段重新解析，60fps 会把这个代价翻一倍多（见 ChatViewModel 里 `DELTA_PUBLISH_MS` 的注释，
 *    渲染缓存是按文本内容做键的，文本一变就是一次 miss）。
 * 3. **只在流式时用**。调用方传 `enabled = streaming`：一轮结束后直接给全文，尾巴不会被
 *    "打字"拖住，也就不会出现"已经答完了却还在慢慢蹦字"。
 *
 * 文本变短（换到新的一段、新的块）时立刻对齐，绝不回放旧内容。
 */
@Composable
internal fun rememberTypedText(text: String, enabled: Boolean, bornAtMs: Long = 0L): String {
    if (!enabled || !LocalTypewriter.current) return text
    val target = rememberUpdatedState(text)
    // 初值见 [initialRevealed]（issue #46）：刚诞生的块从 0 打，早就存在的块直接给全文。
    var revealed by remember {
        mutableIntStateOf(initialRevealed(text.length, nowMs() - bornAtMs))
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TYPE_TICK_MS)
            val goal = target.value.length
            if (revealed >= goal) {
                revealed = goal
            } else {
                val backlog = goal - revealed
                revealed = (revealed + maxOf(TYPE_MIN_STEP, ceil(backlog / 4.0).toInt()))
                    .coerceAtMost(goal)
            }
        }
    }
    return remember(revealed, text) { text.take(revealed) }
}

/** 一个节拍多久放一次字。40ms＝25 次/秒：够顺，又不至于每秒重新解析 60 次 Markdown。 */
private const val TYPE_TICK_MS = 40L

/** 每个节拍至少放几个字：慢端点（一个 delta 一两个字）也要看得见在动。 */
private const val TYPE_MIN_STEP = 2

/**
 * 打字机初值（issue #46）：**刚诞生的块从 0 开始打，早就存在的块直接给全文**。
 *
 * 原来固定用 `text.length` 当初值：块第一次组合时正文若已经攒了几百字（网关攒批、首片整块
 * 到达），等于一个字都不用打 —— 用户看到的就是「正文有时一次性全部显示」。反过来固定用 0
 * 会让上翻回来、中途打开打字机开关、折叠块重新展开时把看过的内容重播一遍。
 *
 * 两个场景的差别不在**长度**（同样的长度两种都可能是），而在**年龄**：诞生 500ms 内就第一次
 * 组合 ⇒ 用户还没见过 ⇒ 从 0 打；超过 500ms 才第一次组合 ⇒ 它早就在那儿了。
 */
internal fun initialRevealed(textLength: Int, ageMs: Long): Int =
    if (ageMs in 0L..TYPE_FRESH_MS) 0 else textLength

/** 块诞生后多久内算「刚出现」：覆盖一次 delta 发布（80ms）加一两帧组合延迟。 */
private const val TYPE_FRESH_MS = 500L
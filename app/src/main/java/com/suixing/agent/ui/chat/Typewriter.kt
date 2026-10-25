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
 * 1. **看得见在打，但不许掉队**。每拍 2~6 个字（50~150 字/秒）：慢端点也看得见在动，正常
 *    积压下封顶 150 字/秒。旧公式 `backlog/4` 会在第一拍就推上去几十上百个字（正文一个 delta
 *    常带几百字）—— 「正文没能逐字」就是它；思考块的 delta 小，所以一直看着正常。
 *    积压超过一屏（300 字）才额外加速，避免长尾巴吊着。反过来，固定速度在快端点上会越积越多、
 *    变成一个永远追不上的尾巴，那是比整块跳更糟的体验 —— 所以是"上限 + 超量加速"，不是纯固定。
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
                revealed = (revealed + typeStep(goal - revealed)).coerceAtMost(goal)
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

/**
 * 这一拍放几个字（40ms 一拍 ⇒ 25 拍/秒）。
 *
 * 旧规则是 `backlog / 4`：积压多少就按四分之一往下砍。思考块的 delta 小，看着一直正常；但正文
 * 一个 delta 常带几百字，**第一拍就推上去 75 个字** —— 视觉上就是"整屏一次刷出来"，也就是用户报的
 * 「正文没能逐字」。
 *
 * 现在给速度设上下限：
 * - 慢端至少 [TYPE_MIN_STEP] 个字/拍（50 字/秒）：一个 delta 一两个字的端点上，也看得见在动；
 * - 正常积压按 [TYPE_MAX_STEP] 个字/拍（150 字/秒）封顶：看起来仍是"在打"，不是整块刷；
 * - 积压超过 [TYPE_BURST_BACKLOG]（300 字）才额外加速：避免一次超长输出把显示吊在后面几分钟。
 *
 * 为什么不全速追赶：全速等于没有打字机（旧行为）。为什么不让它一直慢：固定速度在快端点上会越积
 * 越多，最后变成一个永远追不上的尾巴（见文件头第 1 条）—— 这里用"上限 + 超量加速"两头都收住。
 */
internal fun typeStep(backlog: Int): Int {
    if (backlog <= 0) return TYPE_MIN_STEP
    // 12 拍 ≈ 0.5 秒追平的斜率，再封顶到 TYPE_MAX_STEP。
    val normal = ((backlog + 11) / 12).coerceAtMost(TYPE_MAX_STEP)
    // 积压超过一屏半才动加速档，正常流式永远走不到这里。
    val burst = if (backlog > TYPE_BURST_BACKLOG) (backlog - TYPE_BURST_BACKLOG) / 30 else 0
    return (normal + burst).coerceAtLeast(TYPE_MIN_STEP)
}

/** 每拍最多放几个字（正常积压下的封顶）：6 个/拍 = 150 字/秒。 */
private const val TYPE_MAX_STEP = 6

/** 积压超过这么多字才开始额外加速（大约一屏，正常流式只有几十字）。 */
private const val TYPE_BURST_BACKLOG = 300

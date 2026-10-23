package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 打字机：把**已经收到**的文本按"追得上的速度"逐字放出来。
 *
 * 三条约束决定了它长这样：
 *
 * 1. **逐字**。每拍最多 [TYPE_MAX_STEP]（4）个字 ⇒ 永远慢于阅读速度，每个字都看得见。
 *    **不要按积压比例放**（`积压/4` 之类）：第一跳就是积压的 1/4，800 字积压第一跳 200 字，
 *    那就是用户说的"瞬移"（0.8.399 踩过，连续反馈）。比例放只有在小积压下才像逐字。
 * 2. **不许逐帧重排 Markdown**。节拍是 40ms（25 次/秒），不是每一帧：正文每换一次前缀都要
 *    整段重新解析，60fps 会把这个代价翻一倍多（见 ChatViewModel 里 `DELTA_PUBLISH_MS` 的注释，
 *    渲染缓存是按文本内容做键的，文本一变就是一次 miss）。
 * 3. **只在流式时用**。调用方传 `enabled = streaming`：一轮结束后直接给全文，尾巴不会被
 *    "打字"拖住，也就不会出现"已经答完了却还在慢慢蹦字"。
 *
 * 文本变短（换到新的一段、新的块）时立刻对齐，绝不回放旧内容。
 */
@Composable
internal fun rememberTypedText(text: String, enabled: Boolean): String {
    if (!enabled || !LocalTypewriter.current) return text
    val target = rememberUpdatedState(text)
    // 初值是当前长度：中途打开这个开关（或这一块刚从折叠里出来）时不会把已有文字重播一遍。
    // **初值必须是 0，不能是 text.length**：思考内容常整块到达（网关一次把
    // `reasoning_content` 全吐出来），块被创建时 text 已经是全文 —— 初值取长度就等于
    // 「一个字都不用打」，真机表现就是"思考一下子全显示完"（正文是细碎增量所以看不出问题）。
    // 只在 enabled（=正在流式）时才会走到这里，所以已定型的块不受影响。
    var revealed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TYPE_TICK_MS)
            val goal = target.value.length
            if (revealed >= goal) {
                revealed = goal
            } else {
                val backlog = goal - revealed
                // **每拍有上限**：原来是 `backlog/4`，意味着不论积压多少都在 4 拍（160ms）内追完
                // ⇒ 看起来是一大块一大块蹦出来，而不是"打字"。现在压到固定上限，
                // 只有积压**很大**（端点一次吐几千字）时才直接对齐，避免永远追不上的尾巴。
                // 步长按积压自适应，但**有上下限**：下限保证看得见"在打字"，
                // 上限避免一块一块蹦。原来的 `CATCH_UP=240` 太小 —— 思考内容常成块到达
                // （一次几百字），一超过就直接对齐 ⇒ 观感变成"瞬发"（真机反馈）。
                val step = typewriterStep(backlog)
                revealed = (revealed + step).coerceAtMost(goal)
            }
        }
    }
    return remember(revealed, text) { text.take(revealed) }
}

/** 一个节拍多久放一次字。40ms＝25 次/秒：够顺，又不至于每秒重新解析 60 次 Markdown。 */
internal const val TYPE_TICK_MS = 40L

/** 每个节拍至少放几个字：慢端点（一个 delta 一两个字）也要看得见在动。 */
private const val TYPE_MIN_STEP = 2

/**
 * 这一拍放几个字。
 *
 * 抽成纯函数只为了能测：它决定"看得见在打字"还是"整块蹦出来"。
 *
 * **每拍都有硬上限 [TYPE_MAX_STEP]**（4 个字）：这是"逐字"的底线 ——
 * 0.8.381~0.8.399 之间改过三轮，全是"上限不够小"或"攒批太大"：
 *  · 稳态无上限 ⇒ 第一跳就是积压的 1/4，800 字积压第一跳 200 字，**瞬移**；
 *  · 上限 20 字 ⇒ 40ms 一拍 = 500 字/秒，刚好追平快端点，也是瞬移。
 * 现在每拍最多 4 字 ⇒ 封顶 100 字/秒，慢于任何人的阅读速度，也慢于端点的吐字速度
 * ⇒ 永远有字在放，且每放一个字都看得见。
 *
 * 一次来了几千字时放宽到 [TYPE_FAST_STEP]（40 字/拍）：那种情况若还按 4 字/拍，
 * 一条 5000 字的回答要 50 秒才显示完，那是另一种坏。
 */
internal fun typewriterStep(backlog: Int): Int = when {
    backlog <= TYPE_MIN_STEP -> backlog.coerceAtLeast(1)
    backlog > TYPE_CATCH_UP -> (backlog / 8).coerceIn(TYPE_MAX_STEP, TYPE_FAST_STEP)
    else -> backlog.coerceAtMost(TYPE_MAX_STEP)
}

/** 一次到达超过这个字数的积压才走"加速"，否则严格逐字。 */
private const val TYPE_CATCH_UP = 2_000

/** 每拍最多放几个字：逐字的底线。4 字 × 25 拍/秒 = 100 字/秒，慢于阅读速度。 */
private const val TYPE_MAX_STEP = 4

/** 加速档的每拍上限：够快，但不至于整块蹦出来。 */
private const val TYPE_FAST_STEP = 40

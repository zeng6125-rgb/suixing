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
internal fun rememberTypedText(
    text: String,
    enabled: Boolean,
    bornAtMs: Long = 0L,
    /** live 块强制的起点：true 时**永远从 0 开始**，不吃「诞生多久」那套推断。 */
    startAtZero: Boolean = false,
): String {
    if (!LocalTypewriter.current) return text
    // **一旦开始打就一直打完**（含流式结束之后）。原来 `enabled=false` 立刻返回全文 ——
    // 模型一停，屏幕上剩下没打的尾巴会被一次性补上，那正是「一下子全部显示」的最后一段。
    // 用普通数组记「这块打过没有」：写它不通知快照，不会引起重组循环。
    // **只有"参与过流式"的块才打字**（用户：「为什么对已输出的旧内容逐字出现啊；我要的是
    // 对话进行中的流式」）。这道门必须对所有调用方生效：历史消息、切会话后首次显示的块、
    // 以及定型后才第一次组合的块，`enabled` 都是 false 且从没流过 ⇒ 直接给全文，绝不重播。
    val everStreamed = remember { booleanArrayOf(false) }
    if (enabled) everStreamed[0] = true
    if (!everStreamed[0]) return text
    val target = rememberUpdatedState(text)
    // 初值见 [initialRevealed]（issue #46）：刚诞生的块从 0 打，早就存在的块直接给全文。
    var revealed by remember {
        mutableIntStateOf(
            if (startAtZero) 0 else initialRevealed(text.length, nowMs() - bornAtMs),
        )
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

/**
 * 不知道诞生时刻的调用方用这个（目前只有思考块）。
 *
 * [rememberTypedText] 的 [initialRevealed] 靠 `nowMs() - bornAtMs` 判断「刚诞生还是早就在」。
 * 传默认值 `bornAtMs = 0L` 时年龄等于开机到现在（极大）⇒ 判定成「很久以前」⇒ **直接给全文，
 * 一个字都不打**。思考块那一处（ChatTopBar.kt 的 `Part.Thinking` 分支）就是漏传了它，
 * 所以思考从上线起就没有逐字。
 *
 * 这里在块首次组合时记下时刻：live 块是诞生即组合，于是年龄≈0 ⇒ 从 0 打；历史块不会走这条
 * 分支（`enabled=false` 时 [rememberTypedText] 本来就返回全文）。
 */
@Composable
internal fun rememberTypedTextFromBirth(text: String, enabled: Boolean): String {
    // **强制从 0 开始**，不再记诞生时刻。
    //
    // 原来记 `remember { nowMs() }` 再用年龄判断，但这条路径挂在 `key(index)` 的循环里：
    // parts 列表结构一变（新增 Trace / Compress / Tools），index 槽位会错位，`remember`
    // 里留着的是**上一次占用该槽的旧时刻** ⇒ 年龄被算成几秒 ⇒ 判定"很久以前" ⇒
    // `revealed` 初始化成全文 ⇒ 一个字都不打。用户反复报「正文没有逐字」就是它。
    // live 块本来就应该从头打，直接给 0 最稳。
    return rememberTypedText(text, enabled, startAtZero = true)
}

/** 一个节拍多久放一次字。16ms＝62 次/秒：一屏文字每秒只变 25 次时，人眼看到的是「成串蹦字」
 * （30 字/秒的 5 字一跳）——这才是「逐字效果差」的真因，不是速度。16ms 下每次只多 2 个字。 */
private const val TYPE_TICK_MS = 16L

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
 * **2026-10-25 第四次修（用户：不要分档，任何长度都要逐字）**：
 * 恒定 5 字/拍（125 字/秒），**不再按落后量提速**。前几版分别用 `backlog/4`、上限 6 字/拍、
 * 按落后量分档 —— 设备实测生成速度 51~175 tokens/s（中文约 100~200 字/秒），只要揭示速度
 * 接近或超过到达速度，每一拍就会把新到的字一次放完，看着就是整块出现。恒定慢速才始终逐字。
 *
 * 代价（用户明确接受）：整段答完的速度会比模型慢。900 字的回答约 12 秒打完。
 *
 * 另外**流式结束后继续打完**（见下面的 [TextTyper]）：否则模型一停，剩下的尾巴会被一次性
 * 补上 —— 那正是「一下子全部显示」的最后一段。
 */
internal fun typeStep(backlog: Int): Int = TYPE_STEP

/** 每拍放几个字，恒定不变：2 字/拍 × 62.5 拍/秒 = 125 字/秒。**任何长度的正文都按这个速度逐字**，
 * 不按落后量提速 —— 分档会让「积压多的时候又快成整块」，那等于没有打字机。 */
private const val TYPE_STEP = 2

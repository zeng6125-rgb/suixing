package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** The text's identity owns progress, rather than its current composition group. */
internal class TextRevealState {
    var revealed by mutableIntStateOf(-1)
    var everStreamed = false
    var startedTyping = false

    fun startIfNeeded(enabled: Boolean) {
        if (!startedTyping && enabled) {
            startedTyping = true
            everStreamed = true
            revealed = 0
        }
    }

    fun advance(goal: Int) {
        revealed = (revealed.coerceAtLeast(0) + typeStep(goal - revealed.coerceAtLeast(0))).coerceAtMost(goal)
    }
}

/**
 * 打字机：把**已经收到**的文本按"追得上的速度"逐字放出来。
 *
 * 节拍为 16ms，最多约 62.5 次/秒。积压不超过 8 字时每拍放 2 字；超过时放到
 * 只剩 4 字，避免快端点输出积压。它没有 40ms 上限，也没有固定 5 字的步长。
 * Markdown 的稳定块复用由 LiveBlocks 负责，最后一个未闭合块仍可能随节拍重新排版。
 *
 * 只有参与过流式的文本才逐字显示；结束后继续放完尾部，纯历史直接给全文。
 * 调用方可按run/part提供retained进度，跨过程区与完成交接沿用；未提供时归当前组合组。
 */
@Composable
internal fun rememberTypedText(
    text: String,
    enabled: Boolean,
    bornAtMs: Long = 0L,
    /** live 块强制的起点：true 时**永远从 0 开始**，不吃「诞生多久」那套推断。 */
    startAtZero: Boolean = false,
    retained: TextRevealState? = null,
): String {
    if (!LocalTypewriter.current) return text
    // **一旦开始打就一直打完**（含流式结束之后）。原来 `enabled=false` 立刻返回全文 ——
    // 模型一停，屏幕上剩下没打的尾巴会被一次性补上，那正是「一下子全部显示」的最后一段。
    // 用普通数组记「这块打过没有」：写它不通知快照，不会引起重组循环。
    // **只有"参与过流式"的块才打字**（用户：「为什么对已输出的旧内容逐字出现啊；我要的是
    // 对话进行中的流式」）。这道门必须对所有调用方生效：历史消息、切会话后首次显示的块、
    // 以及定型后才第一次组合的块，`enabled` 都是 false 且从没流过 ⇒ 直接给全文，绝不重播。
    val progress = retained ?: remember { TextRevealState() }
    if (enabled) progress.everStreamed = true
    if (!progress.everStreamed) return text
    val target = rememberUpdatedState(text)
    remember(progress) {
        if (progress.revealed < 0) progress.revealed =
            if (startAtZero) 0 else initialRevealed(text.length, nowMs() - bornAtMs)
        progress
    }
    // **一旦开始流式就要真的从头打**（用户反复报「正文没有逐字」的根因）：
    // `revealed` 是 remember 的，初值在块第一次组合时就定死 —— 那一刻 `enabled` 往往还是
    // false（还没开始流），于是被初始化成"当前全文长度"。之后 streaming 变 true、文本继续增长，
    // `revealed` 只会从那个大数往上加，永远不会从 0 打起 ⇒ 屏幕上是整段整段地出。
    // 这里用一个普通标记：第一次看到 enabled=true 时把进度归零，重新起打。
    // **协程要有出口**（优化清单 chat-P1）：原来是 `LaunchedEffect(Unit){ while(true){ delay(16) } }`
    // —— 打完字之后每个参与过流式的块仍然每 16ms 醒一次，一页十几块常驻组合就是每秒几百次
    // 空转。现在：追平且不再流式 ⇒ 结束；`enabled` 再变 true（新一轮开始）⇒ 协程重启。
    // **只在文本真的变长时醒着**（优化清单 chat-P1）：原来是
    // `LaunchedEffect(Unit){ while(true){ delay(16) } }` —— 打完字之后每个参与过流式的块
    // 仍然每 16ms 醒一次，一页十几块就是每秒几百次空转。
    // 现在由 `snapshotFlow` 驱动：长度不变 ⇒ 协程挂起（不占 CPU）；文本一到 ⇒ 继续把尾巴打完。
    // **不能用"追平就退出"的写法**：退出之后文本若再变长，`text.take(revealed)` 会把新内容
    // 永久截掉（那样才是真的改行为）。
    val enabledNow = rememberUpdatedState(enabled)
    LaunchedEffect(progress) {
        snapshotFlow { target.value.length }.collect {
            progress.startIfNeeded(enabledNow.value)
            while (progress.revealed < target.value.length) {
                delay(TYPE_TICK_MS)
                val goal = target.value.length
                progress.advance(goal)
            }
        }
    }
    return remember(progress.revealed, text) { text.take(progress.revealed.coerceAtLeast(0)) }
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
 * 16ms 一拍的揭示步长。小积压为 2 字，大积压保留最后 4 字给后续节拍。
 */
internal fun typeStep(backlog: Int): Int {
    // **积压越多放越快**（issue #44 §5 的 Eta 做法）：固定 2 字/拍在快模型下永远追不上，
    // 于是文本一直落后、最后一刻还在补字。当前实现按积压量提速：
    //   backlog ≤ 8   ⇒ 2 字/拍（125 字/秒，慢速端点也能看见在动）
    //   否则          ⇒ backlog - 4（积压 108 字 ⇒ 104 字/拍，只留 4 字）
    // 追平后回落到 2 字/拍，观感始终是"逐字往上推"，而不是"一段一段蹦"。
    // **滞后上限只有 4 个字**（用户：「正文也会滞后」）：原来大积压时放 `backlog / 4`，那是
    // 指数收敛 —— 到达多快就恒定落后 4 倍（每秒 1000 字时落后约 80 字，看起来永远慢半拍）。
    // 现在积压一超过 8 就直接放到"只留 4 字"，每拍几乎贴住最新文本，追平后自然回落到
    // 2 字/拍的逐字节奏。逐字感保留在起点，滞后不再累积。
    return if (backlog <= 8) TYPE_STEP else maxOf(TYPE_STEP, backlog - 4)
}

/** 小积压的基础步长：2 字/拍 × 62.5 拍/秒 = 125 字/秒；大积压走 typeStep 的加速分支。 */
private const val TYPE_STEP = 2

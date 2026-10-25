package com.suixing.agent.ui.chat

import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage

/**
 * 流式气泡里画的东西，以及它们的**增量更新**规则（issue #38）。
 *
 * 为什么单独成文件：这些函数处在**每个 delta 都会走的路径**上（`applyPending` 每 80ms 一批，
 * 一轮几千个 delta）。它们原来散在 `ChatViewModel`（3,600 行、依赖整个 `Core`、无法单测）里，
 * 于是"改坏一处不会有人发现"——而这里恰好已经出过两次 O(n²) 事故。
 * 现在它们是纯函数（只依赖 `List<LivePart>`），可以直接断言。
 */
sealed interface LivePart {
/**
     * [tokens] and [elapsedMs] belong to this block alone: they are frozen when the run
     * moves on to a tool or to prose, so two thinking blocks in one run never show the
     * same clock — sharing the run-level tally is what made every block read 23.3s.
     */
/**
     * 一段思考。**文本用可增长缓冲**（issue #38 的 O(n²) 修复）：
     * 原来每个 delta 都 `part.text + delta`（整串复制），一轮 5000 个 delta ⇒ 约 1.2×10⁷ 字符复制。
     * 现在只追加增量（O(delta)），渲染时 `text` 才 `toString()` 一次。
     */
class Thinking(
    val buffer: GrowableText = GrowableText(),
    val tokens: Int = 0,
    val elapsedMs: Long = 0,
    /**
     * **版本号**：每次追加 +1。
     *
     * 为什么必须有它（用户反复报「正文流式是整段整段地出」的真凶，见 [LivePartsFreezeTest]）：
     * 缓冲是**可变**的，追加只改它、不换实例 ⇒ 新旧列表里是**同一个对象** ⇒ `equals` 里
     * `this === other` 短路为 true ⇒ 列表判等 ⇒ `_state.update { copy(liveParts = …) }` 判等 ⇒
     * **StateFlow 不发射、不重组、屏幕冻结**，直到列表结构变化（段落结束）才一次性吐出。
     * 思考流式看起来正常，只是因为 `reasoning` 字段每次都变，整个 state 判不等才顺带发射。
     *
     * 有了版本号：追加时产生**新实例**且 version 不同 ⇒ 列表判等为 false ⇒ state 发射 ⇒
     * 重组 ⇒ 读到的就是最新文本。缓冲仍然共享（O(1) 追加），没有回到 O(n²) 整串复制。
     */
    val version: Int = 0,
) : LivePart {
    constructor(text: String, tokens: Int = 0, elapsedMs: Long = 0) :
        this(GrowableText(text), tokens, elapsedMs, 0)

    val text: String get() = buffer.toString()

    /** 追加后的新实例（缓冲共享，版本 +1）。 */
    internal fun appended(): Thinking = Thinking(buffer, tokens, elapsedMs, version + 1)

    override fun equals(other: Any?): Boolean =
        other is Thinking && other.version == version &&
            other.buffer.length() == buffer.length() &&
            other.tokens == tokens && other.elapsedMs == elapsedMs

    override fun hashCode(): Int = version * 31 + buffer.length() * 31 + tokens
}

data class Tools(val calls: List<LiveTool>) : LivePart

/**
 * 正文。同样用可增长缓冲（见 [Thinking] 的说明）。
 *
 * [bornAtMs] 是这个块**诞生的时刻**（[nowMs]）。打字机用它区分「刚出现的块」（从 0 开始打）与
 * 「早就存在、这次才重新组合出来的块」（直接给全文）—— issue #46。
 *
 * **它有意不参与相等性**：下面的 `equals` 按缓冲长度比，是为了让「内容没变长度时不重算映射」
 * 这条契约继续成立（见 [Thinking] 的注释）。把时间戳塞进 equals 会让每次重建都判定为"不等"，
 * 等于把那条契约废掉。
 */
class Text(
    val buffer: GrowableText = GrowableText(),
    val bornAtMs: Long = nowMs(),
    /** 见 [Thinking] 的 version：每次追加 +1，让列表判等为 false，从而让 state 真的发射。 */
    val version: Int = 0,
) : LivePart {
    constructor(text: String) : this(GrowableText(text), nowMs(), 0)

    val text: String get() = buffer.toString()

    /** 追加后的新实例（缓冲共享，版本 +1）。 */
    internal fun appended(): Text = Text(buffer, bornAtMs, version + 1)

    // bornAtMs **不参与**相等性（它的原契约：时间戳塞进 equals 会让每次重建都判"不等"）；
    // 版本号已经足够区分"内容变了"。
    override fun equals(other: Any?): Boolean =
        other is Text && other.version == version &&
            other.buffer.length() == buffer.length()

    override fun hashCode(): Int = version * 31 + buffer.length()
}

data class Steer(val message: StoredMessage) : LivePart

/**
     * 过程留痕（重试）：运行中就要看得见。
     *
     * 这一轮的落盘消息在流式期间被藏在转录之外（live 气泡画的就是它们），所以没有这个 part，
     * 「刚才重试过」要等整轮结束才出现 —— 用户要的恰恰是过程态里的留痕。
     */
data class Trace(val note: String) : LivePart

/**
     * 运行中发生的一次上下文压缩。
     *
     * 它必须成为过程区的一个块，而不是气泡下面一行提示：压缩是"这一轮里发生的事"，位置就在
     * 它发生的地方（思考 → 工具 → 压缩 → 继续）。运行结束后转录里另有一张按旧行定位的
     * CheckpointCard 负责回看。
     */
data class Compress(val checkpoint: ContextCheckpoint) : LivePart
}

class GrowableText(initial: String = "") {
private val buf = StringBuilder(initial)

fun append(delta: String) {
        buf.append(delta)
}

fun length(): Int = buf.length

override fun toString(): String = buf.toString()
}

/**
     * Appends a text delta to the prose piece of the **current segment**, or opens one.
     *
     * 判据是「当前段（[segmentStart] 之后）里有没有正文块」，不是「上一个 part 是不是正文」：
     * 交错流（思考→正文→思考→正文）里若按上一个 part 判，一个 2-token 的思考块就会把一句
     * 话劈成两块（真机截图实测）。同一段里正文只持一个块；跨过工具行/插话行/轮落盘才是新段
     * —— 工具前后的正文确实是两段话，合并会把「让我查一下」和查完的回答连成一行。
     */
internal fun List<LivePart>.appendText(delta: String, segmentStart: Int): List<LivePart> {
        val from = segmentStart.coerceIn(0, size)
        val tailIdx = subList(from, size).indexOfLast { it is LivePart.Text }
        if (tailIdx < 0) return this + LivePart.Text(delta)
        val idx = from + tailIdx
        val part = this[idx] as LivePart.Text
        // **只追加增量**（不是整串复制）：缓冲原地增长，列表只复制"前段 + 这一项"。
        // 放回的是**新实例**（version +1）—— 否则列表判等为 true、state 不发射、屏幕冻结。
        part.buffer.append(delta)
        return take(idx) + part.appended() + subList(idx + 1, size)
}

/**
     * 重试留痕：连续重试只更新同一块（「自动重试 1 次」→「自动重试 2 次」），不叠成两行。
     */
internal fun List<LivePart>.traceRetry(note: String): List<LivePart> =
        if (lastOrNull() is LivePart.Trace) {
            dropLast(1) + LivePart.Trace(note)
        } else {
            this + LivePart.Trace(note)
        }

/** 同 [appendText]：同一段里思考只持一个块，交错流不再劈开。 */
internal fun List<LivePart>.appendThinking(delta: String, segmentStart: Int): List<LivePart> {
        val from = segmentStart.coerceIn(0, size)
        val tailIdx = subList(from, size).indexOfLast { it is LivePart.Thinking }
        if (tailIdx < 0) return this + LivePart.Thinking(delta)
        val idx = from + tailIdx
        val part = this[idx] as LivePart.Thinking
        part.buffer.append(delta)
        return take(idx) + part.appended() + subList(idx + 1, size)
}

/** 当前段里是否已有思考块（决定这一段的思考钟是续走还是重置）。 */
internal fun List<LivePart>.hasOpenThinking(segmentStart: Int): Boolean {
        val from = segmentStart.coerceIn(0, size)
        return subList(from, size).any { it is LivePart.Thinking }
}

/**
     * Freezes the still-open thinking block the moment the run moves on: its own text
     * length stands in for its token count and its own clock stops here, so a later
     * block cannot inherit this one's elapsed time.
     */
internal fun List<LivePart>.sealThinking(startedAt: Long): List<LivePart> {
        val last = lastOrNull() as? LivePart.Thinking ?: return this
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        // `Thinking` 现在不是 data class（它持有一个可变缓冲），所以这里**新建**一个，
        // 但**沿用同一个 buffer** —— 文本不能被复制/重建（那正是我们要避免的整串复制）。
        val tokens = last.text.length / 4
        return dropLast(1) + LivePart.Thinking(last.buffer, tokens, elapsed)
}

/** A call joins the round of tools already open, or starts one. */
internal fun List<LivePart>.startTool(name: String, args: String): List<LivePart> {
        val call = LiveTool(name = name, args = args, startedAt = System.currentTimeMillis())
        return when (val last = lastOrNull()) {
            is LivePart.Tools -> dropLast(1) + last.copy(calls = last.calls + call)
            else -> this + LivePart.Tools(listOf(call))
        }
}

/**
     * Marks the oldest still-running call of that name as done.
     *
     * Matched by name, not by index: the engine reports the finish by name, and a run that
     * calls the same tool twice must not have the second result close the first row.
     */
internal fun List<LivePart>.finishTool(name: String, ok: Boolean): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val index = part.calls.indexOfFirst { it.name == name && !it.done }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        val call = calls[index]
        calls[index] = call.copy(
            done = true,
            ok = ok,
            elapsedMs = System.currentTimeMillis() - call.startedAt,
        )
        part.copy(calls = calls)
}

/**
     * Hangs a landed TOOL message on the call it reports, matching the way
     * [finishTool] matches: oldest open call of that name. The message carries the
     * output and any screenshot, which only the row that owns it may draw.
     */
internal fun List<LivePart>.attachToolMessage(message: StoredMessage): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val name = message.toolName.orEmpty()
        val index = part.calls.indexOfFirst { it.name == name && it.message == null }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        calls[index] = calls[index].copy(message = message)
        part.copy(calls = calls)
}

/** 单调时钟（毫秒）。只用来比较「这块诞生到现在多久」，与墙上时间无关。 */
internal fun nowMs(): Long = System.nanoTime() / 1_000_000

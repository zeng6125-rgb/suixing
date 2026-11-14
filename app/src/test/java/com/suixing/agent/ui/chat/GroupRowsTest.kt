package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One run is one bubble.
 *
 * The engine stores one assistant message per round, and a round that only asked for tools
 * carries neither text nor thinking — so it is invisible and its tool result is the first
 * thing of that run the grouping ever sees. Those results used to open a card of their own
 * and claim no run, which left every following result of the same run to start yet another
 * card: one answer arriving as three 「本轮：工具 1 个」 cards plus a fourth that finally
 * carried the thinking, the tools and the prose.
 */
class GroupRowsTest {

    private fun question(id: String) = StoredMessage(id = id, role = Roles.USER, text = "问 $id")

    /** A round that asked for tools and said nothing: no text, no thinking. */
    private fun silentRound(id: String, run: String, vararg calls: String) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        runId = run,
        toolCalls = calls.map { StoredToolCall(id = "c-$it", name = it) },
    )

    /** A round that thought, wrote and asked for tools. */
    private fun round(id: String, run: String, text: String, reasoning: String?, vararg calls: String) =
        StoredMessage(
            id = id,
            role = Roles.ASSISTANT,
            text = text,
            reasoning = reasoning,
            runId = run,
            promptTokens = 100,
            completionTokens = 20,
            toolCalls = calls.map { StoredToolCall(id = "c-$it", name = it) },
        )

    private fun result(id: String, run: String, name: String) = StoredMessage(
        id = id,
        role = Roles.TOOL,
        toolName = name,
        toolCallId = "c-$name",
        toolOutput = "ok",
        runId = run,
    )

    private fun steer(id: String, run: String) =
        StoredMessage(id = id, role = Roles.USER, text = "插 $id", steer = true, runId = run)

    private fun group(messages: List<StoredMessage>) = groupRows(messages, emptyMap(), false)

    @Test fun `the live row owns pending tools steers and system receipts`() {
        val messages = listOf(
            question("q0"), round("a0", "saved", "已有回答", null),
            question("q1"), steer("early", "live"),
            silentRound("a1", "live", "t1"), result("m1", "live", "t1"),
            StoredMessage(id = "receipt", role = Roles.SYSTEM, text = "回执", runId = "live"),
            result("m2", "live", "t2"), steer("s1", "live"),
            round("a2", "live", "正在回答", "思考"),
        )
        val rows = groupRows(messages, emptyMap(), running = true, pendingRun = "live")
        assertEquals(listOf("q0", "a0", "q1"), rows.map { it.message.id })
        assertTrue(rows.none { row -> row.parts.any { it is Part.Tools || it is Part.Steer } })

        val settled = groupRows(messages, emptyMap(), running = false)
        val answer = settled.single { it.message.id == "a2" }
        assertEquals(listOf("t1", "t2"),
            answer.parts.filterIsInstance<Part.Tools>().flatMap { it.calls }.map { it.name })
        assertEquals("s1", answer.parts.filterIsInstance<Part.Steer>().single().message.id)
        assertTrue(answer.branch?.canRegenerate == true)
    }

    @Test fun `hiding a live row preserves tool ownership in a mid-turn page`() {
        val messages = listOf(
            round("old", "saved", "已有回答", null),
            silentRound("silent", "live", "t1"), result("m1", "live", "t1"),
            round("current", "live", "新回答", "思考"), result("m2", "live", "t2"),
            steer("s1", "live"),
        )
        val expected = groupRows(messages, emptyMap(), running = true)
            .filterNot { it.message.runId == "live" }
        val actual = groupRows(messages, emptyMap(), running = true, pendingRun = "live")
        assertEquals(expected, actual)
        assertEquals(listOf("t1"),
            actual.single().parts.filterIsInstance<Part.Tools>().flatMap { it.calls }.map { it.name })
    }

    @Test fun `a pending answer suppresses the old branch before its first stored round`() {
        val messages = listOf(question("q1"), round("a1", "saved", "旧回答", null))
        assertEquals(listOf("q1"),
            groupRows(messages, mapOf("q1" to "live"), running = true, pendingRun = "live")
                .map { it.message.id })
    }

    /**
     * The reported screenshot: three silent tool rounds, then one round that thought, ran
     * four tools and answered. All of it is one run, so all of it is one card.
     */
    @Test
    fun `a run of silent tool rounds and a final answer is one card`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"), result("m1", "r1", "t1"),
            silentRound("a2", "r1", "t2"), result("m2", "r1", "t2"),
            silentRound("a3", "r1", "t3"), result("m3", "r1", "t3"),
            round("a4", "r1", "正文", "想过", "t4", "t5", "t6", "t7"),
            result("m4", "r1", "t4"), result("m5", "r1", "t5"),
            result("m6", "r1", "t6"), result("m7", "r1", "t7"),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a4"), rows.map { it.message.id })
        val card = rows[1]
        // 思考 1 段 · 工具 4 个 — every call of the run, in the one card that reports them.
        assertEquals(1, card.parts.count { it is Part.Thinking })
        assertEquals(
            listOf("t1", "t2", "t3", "t4", "t5", "t6", "t7"),
            card.parts.filterIsInstance<Part.Tools>().flatMap { it.calls }.map { it.name },
        )
        // The result that opened the card is one of its rounds: the round that asked for it
        // was invisible, so the card's clock and cost are summed over what actually landed.
        assertEquals(listOf("m1", "a4"), card.rounds.map { it.id })
    }

    /**
     * 重试留痕排在这一轮的最前面：它发生在这一轮产出任何字之前。用户的要求是"重试成功要在
     * 过程态中留痕"—— 一块和思考/工具并排的记录，而不是一张说这轮失败了的红卡。
     */
    @Test
    fun `a retry trace is the first part of the round that retried`() {
        val messages = listOf(
            question("q1"),
            round("a1", "r1", "正文", "想过").copy(traceNote = "自动重试 1 次 · 服务器限流（429）"),
        )

        val card = group(messages)[1]

        assertEquals(
            listOf("留痕", "思考", "正文"),
            card.parts.map {
                when (it) {
                    is Part.Trace -> "留痕"
                    is Part.Thinking -> "思考"
                    is Part.Text -> "正文"
                    else -> "其他"
                }
            },
        )
        assertEquals(
            "自动重试 1 次 · 服务器限流（429）",
            (card.parts.first() as Part.Trace).note,
        )
    }

    /** The card a run's first result opens is the card the run's later rounds fold into. */
    @Test
    fun `the first tool result of a run opens the card the run keeps using`() {        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"), result("m1", "r1", "t1"),
            silentRound("a2", "r1", "t2"), result("m2", "r1", "t2"),
            round("a3", "r1", "正文", null),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a3"), rows.map { it.message.id })
        // Consecutive calls stay one block: the fold is about cards, not about splitting a
        // run of calls into one card each.
        val tools = rows[1].parts.filterIsInstance<Part.Tools>()
        assertEquals(1, tools.size)
        assertEquals(listOf("t1", "t2"), tools[0].calls.map { it.name })
        assertEquals(listOf("正文"), rows[1].parts.filterIsInstance<Part.Text>().map { it.text })
    }

    /** A run that never wrote prose is still one card, not one card per call. */
    @Test
    fun `a stopped run of tool rounds stays one card`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"), result("m1", "r1", "t1"),
            silentRound("a2", "r1", "t2"), result("m2", "r1", "t2"),
        )

        val rows = group(messages)

        assertEquals(2, rows.size)
        assertEquals(
            listOf("t1", "t2"),
            rows[1].parts.filterIsInstance<Part.Tools>().flatMap { it.calls }.map { it.name },
        )
    }

    /** Two runs of one turn stay two branches: folding is by run, never by turn. */
    @Test
    fun `a re-answered turn keeps its runs apart`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"), result("m1", "r1", "t1"),
            silentRound("a2", "r2", "t2"), result("m2", "r2", "t2"),
        )

        // Only the newest run is drawn, and it is drawn as one card.
        val rows = group(messages)
        assertEquals(2, rows.size)
        assertEquals(
            listOf("t2"),
            rows[1].parts.filterIsInstance<Part.Tools>().flatMap { it.calls }.map { it.name },
        )
    }

    /**
     * A 插话 typed while the run was going belongs to the card it interrupted — it is not a
     * question, so it must not close the bubble and start a second one.
     */
    @Test
    fun `a steer joins the card of the run it interrupted`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"), result("m1", "r1", "t1"),
            steer("s1", "r1"),
            round("a2", "r1", "正文", null),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertTrue(rows[1].parts.any { it is Part.Steer && it.message.id == "s1" })
    }

    /** The ordinary shape still folds: thinking, tools and prose of one run, one card. */
    @Test
    fun `a run that thought and then answered is one card`() {
        val messages = listOf(
            question("q1"),
            round("a1", "r1", "", "想过", "t1"), result("m1", "r1", "t1"),
            round("a2", "r1", "正文", null),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(listOf("a1", "a2"), rows[1].rounds.map { it.id })
    }

    /**
     * A tool result that carries no run id while the answer carries one used to take the whole turn
     * with it: `Transcript.chosenRuns` picked the turn's run as `activeRuns[turn] ?: runs.last()`,
     * the stray result's empty run was the newest, and `Transcript.active` then kept that single
     * message and filtered the answer out — the answer vanished rather than being drawn beside it.
     * The tie-break now prefers a run that names itself (`Transcript.chosenRuns`), so the answer is
     * still there and the tool card folds into it.
     */
    @Test
    fun `a tool result with no run id does not hide the answer`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "r1", "t1"),
            result("m1", "", "t1"),
            round("a2", "r1", "正文", "想过"),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(1, rows[1].parts.count { it is Part.Text })
        // The stray result itself is still filtered out — `Transcript.active` keeps one run per
        // turn, and that result names no run — so it is not drawn with the answer it produced. That
        // is the residue of the same defect (a message that lost its run id), not a rendering rule:
        // what matters here is that losing it no longer costs the answer.
        assertEquals(listOf("a2"), rows[1].rounds.map { it.id })
    }

    /**
     * The same split one step later: a round that only thought, then the round that answered.
     * Both carry the run id, so this is the shape that must keep folding — the regression guard
     * for the ordinary case.
     */
    @Test
    fun `a round that only thought folds into the round that writes the prose`() {
        val messages = listOf(
            question("q1"),
            round("a1", "r1", "", "想过"),
            round("a2", "r1", "正文", "又想过"),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(2, rows[1].parts.count { it is Part.Thinking })
    }

    /**
     * A transcript written before runs were keyed shares one run id, [Transcript.BASE_RUN] — the
     * empty string — and its rounds used to refuse to fold because the comparison required a
     * non-empty run. Two rounds of one legacy answer are still one answer.
     */
    @Test
    fun `rounds written before runs were keyed still fold into one card`() {
        val messages = listOf(
            question("q1"),
            round("a1", "", "", "想过"),
            round("a2", "", "正文", null),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(listOf("a1", "a2"), rows[1].rounds.map { it.id })
    }

    /**
     * The other half of the tie-break that keeps a run-less message from stealing its turn: a turn
     * made of nothing *but* empty runs — every message written before branching — must still draw.
     * `Transcript.chosenRuns` falls back to the last run when none of them names itself, so the
     * legacy transcript keeps its answer instead of being filtered down to nothing.
     */
    @Test
    fun `a turn made only of legacy rounds still draws`() {
        val messages = listOf(
            question("q1"),
            silentRound("a1", "", "t1"),
            result("m1", "", "t1"),
            round("a2", "", "正文", null),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(listOf("m1", "a2"), rows[1].rounds.map { it.id })
    }

    @Test
    fun `a long orphan legacy run keeps its tool blocks and round identities`() {
        val firstTools = List(128) { result("first-$it", "", "first-$it") }
        val secondTools = List(128) { result("second-$it", "", "second-$it") }
        val thinking = round("thinking", "", "", "想过")
        val answer = round("answer", "", "正文", null)

        val card = group(firstTools + thinking + secondTools + answer).single()

        assertSame(answer, card.message)
        assertEquals(4, card.parts.size)
        assertEquals(
            List(128) { "first-$it" },
            (card.parts[0] as Part.Tools).calls.map { it.name },
        )
        assertEquals("想过", (card.parts[1] as Part.Thinking).text)
        assertEquals(
            List(128) { "second-$it" },
            (card.parts[2] as Part.Tools).calls.map { it.name },
        )
        assertEquals("正文", (card.parts[3] as Part.Text).text)
        assertEquals(listOf("first-0", "thinking", "answer"), card.rounds.map { it.id })
        assertSame(firstTools.first(), card.rounds[0])
        assertSame(thinking, card.rounds[1])
        assertSame(answer, card.rounds[2])
        assertSame(firstTools.last(), (card.parts[0] as Part.Tools).calls.last().message)
    }

    @Test
    fun `retry traces preserve thinking dedup and later tool boundaries`() {
        val first = round("a1", "r1", "", "想过").copy(reasoningTokens = 7, elapsedMs = 9)
        val retried = round("a2", "r1", "", "想过").copy(traceNote = "重试")
        val later = round("a3", "r1", "", "想过")
        val answer = round("a4", "r1", "正文", "想过")

        val card = group(listOf(
            question("q1"), first, retried, later, result("m1", "r1", "t1"), answer,
        ))[1]

        assertEquals(6, card.parts.size)
        assertEquals(Part.Thinking("想过", 7, 9, "r1"), card.parts[0])
        assertEquals(Part.Trace("重试"), card.parts[1])
        assertEquals(Part.Thinking("想过", 0, 0, "r1"), card.parts[2])
        assertEquals(listOf("t1"), (card.parts[3] as Part.Tools).calls.map { it.name })
        assertEquals(Part.Thinking("想过", 0, 0, "r1"), card.parts[4])
        assertEquals(Part.Text("正文"), card.parts[5])
        assertEquals(listOf("a1", "a2", "a3", "a4"), card.rounds.map { it.id })
    }

    @Test
    fun `a system receipt preserves the open tool block and time label anchors`() {
        val first = round("a1", "r1", "", "想过").copy(timestamp = 1)
        val answer = round("a2", "r1", "正文", null).copy(timestamp = 5 * TIME_LABEL_GAP_MS)
        val receipt = StoredMessage(
            id = "receipt",
            role = Roles.SYSTEM,
            text = "回执",
            runId = "r1",
            timestamp = 3 * TIME_LABEL_GAP_MS,
        )
        val messages = listOf(
            question("q1").copy(timestamp = 0),
            first,
            result("m1", "r1", "t1").copy(timestamp = 2 * TIME_LABEL_GAP_MS),
            receipt,
            result("m2", "r1", "t2").copy(timestamp = 4 * TIME_LABEL_GAP_MS),
            answer,
            question("q2").copy(timestamp = 6 * TIME_LABEL_GAP_MS),
        )

        val rows = group(messages)

        assertEquals(listOf("q1", "a2", "receipt", "q2"), rows.map { it.message.id })
        assertEquals(listOf(true, false, true, false), rows.map { it.showTimeLabel })
        assertEquals(3, rows[1].parts.size)
        assertEquals(listOf("t1", "t2"), (rows[1].parts[1] as Part.Tools).calls.map { it.name })
        assertEquals(listOf("a1", "a2"), rows[1].rounds.map { it.id })
        assertSame(answer, rows[1].message)
        assertSame(receipt, rows[2].message)
    }
}

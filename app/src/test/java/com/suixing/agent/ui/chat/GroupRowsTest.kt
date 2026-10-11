package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
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

    /** The card a run's first result opens is the card the run's later rounds fold into. */
    @Test
    fun `the first tool result of a run opens the card the run keeps using`() {
        val messages = listOf(
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
}

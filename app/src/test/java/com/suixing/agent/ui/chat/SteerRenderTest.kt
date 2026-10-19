package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 插话 is on screen the moment it is handed over, and on screen exactly once.
 *
 * Delivery does two things at once (see the `AgentEvent.Steered` branch of
 * `ChatViewModel.handleEvent`): the line is stored as a message of the running run, and it
 * becomes a `LivePart.Steer` of that run's bubble. The second half is the only way the user
 * sees it while the run goes, because the run's own stored rows are hidden from the
 * transcript for as long as it streams — and a stored steer folds into exactly the row that
 * is hidden. The first half is what keeps the line after the run ends.
 *
 * These tests stay on the pure surface: `ChatUiState.visible`, `groupRows` and the two part
 * types. The `LivePart.Steer -> Part.Steer` mapping itself lives inside a private composable
 * of `ChatScreen` and is not reachable from a JVM test.
 */
class SteerRenderTest {

    private fun question(id: String) = StoredMessage(id = id, role = Roles.USER, text = "问 $id")

    /** A round that asked for tools and said nothing: invisible to the grouping. */
    private fun silentRound(id: String, run: String, vararg calls: String) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        runId = run,
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

    private fun round(id: String, run: String, text: String) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        text = text,
        runId = run,
        promptTokens = 100,
        completionTokens = 20,
    )

    /**
     * The line the `Steered` branch builds: a user message, marked `steer`, carrying the run
     * it interrupted. [runId] is what the screen's live filter keys off; see the last test.
     */
    private fun steer(id: String, run: String?) =
        StoredMessage(id = id, role = Roles.USER, text = "插 $id", steer = true, runId = run)

    /**
     * The settled half of the screen, computed the way `ChatScreen` computes it: group the
     * whole transcript, then drop the rows of the run that is still streaming. Mirrored here
     * because the real thing is a `remember` block inside a composable.
     */
    private fun settled(state: ChatUiState): List<ChatRow> =
        groupRows(state.messages, state.activeRuns, state.runningHere)
            .filterNot { it.message.runId == state.liveRunId && state.liveRunId.isNotEmpty() }

    /**
     * How many copies of [id] the screen draws: settled rows plus the live bubble.
     *
     * A row counts once however the line sits in it — folded in as a part, or as the row's
     * own message when it had no card to fold into.
     */
    private fun drawn(state: ChatUiState, id: String): Int {
        val inRows = settled(state).count { row ->
            row.message.id == id || row.parts.any { it is Part.Steer && it.message.id == id }
        }
        val inLive = state.liveParts.count { it is LivePart.Steer && it.message.id == id }
        return inRows + inLive
    }

    /**
     * Delivery, mid-run: the model has run a tool but written no prose yet. The line is in
     * the live bubble, and the settled transcript still shows only the question — so the one
     * copy the user sees is the live one.
     */
    @Test
    fun `a delivered steer is drawn once, on the live card`() {
        val line = steer("s1", "r1")
        val state = ChatUiState(
            messages = listOf(
                question("q1"),
                silentRound("a1", "r1", "t1"),
                result("m1", "r1", "t1"),
                line,
            ),
            liveRunId = "r1",
            liveParts = listOf(LivePart.Steer(line)),
            runningHere = true,
        )

        assertEquals(1, drawn(state, "s1"))
        assertEquals(listOf("q1"), settled(state).map { it.message.id })
        // It is a live part of the run, not a settled row: this is the only place it can be
        // drawn from while the run streams.
        assertEquals(
            listOf("s1"),
            state.liveParts.filterIsInstance<LivePart.Steer>().map { it.message.id },
        )
    }

    /** The stored copy is hidden for as long as its run streams — that is what stops the pair. */
    @Test
    fun `the stored steer stays out of the visible transcript while its run streams`() {
        val line = steer("s1", "r1")
        val state = ChatUiState(
            messages = listOf(question("q1"), line),
            liveRunId = "r1",
            liveParts = listOf(LivePart.Steer(line)),
            runningHere = true,
        )

        assertEquals(listOf("q1"), state.visible.map { it.id })
    }

    /**
     * The run ends: the live bubble goes away and the stored line takes its place inside the
     * card it interrupted — same widget, same position, still one copy. The order is the
     * order things arrived in: the tool that ran before the line, the line, then the prose
     * the model wrote after reading it.
     */
    @Test
    fun `after the run settles the steer folds into the card it interrupted`() {
        val state = ChatUiState(
            messages = listOf(
                question("q1"),
                silentRound("a1", "r1", "t1"),
                result("m1", "r1", "t1"),
                steer("s1", "r1"),
                round("a2", "r1", "正文"),
            ),
        )

        val rows = settled(state)
        assertEquals(listOf("q1", "a2"), rows.map { it.message.id })
        assertEquals(1, drawn(state, "s1"))

        val card = rows[1]
        assertEquals(
            listOf("工具 t1", "插话 s1", "正文"),
            card.parts.map {
                when (it) {
                    is Part.Tools -> "工具 " + it.calls.joinToString(",") { call -> call.name }
                    is Part.Steer -> "插话 " + it.message.id
                    is Part.Text -> it.text
                    is Part.Thinking -> "思考"
                    is Part.Trace -> "留痕 " + it.note
                    is Part.Compress -> "压缩"
                }
            },
        )
        // Folded in, not appended as a round of its own: the card still costs what the model
        // spent, and the line is not a reply.
        assertEquals(listOf("m1", "a2"), card.rounds.map { it.id })
    }

    /**
     * The `openIndex == -1` case: typed before the model had anything on screen to fold
     * into. It stands as its own row rather than being dropped, and it does not open a turn.
     */
    @Test
    fun `a steer with no open card stands as its own row`() {
        val state = ChatUiState(
            messages = listOf(question("q1"), steer("s1", "r1"), round("a1", "r1", "正文")),
        )

        val rows = settled(state)
        assertEquals(listOf("q1", "s1", "a1"), rows.map { it.message.id })
        assertEquals(1, drawn(state, "s1"))
        assertEquals(listOf("s1"), rows[1].parts.filterIsInstance<Part.Steer>().map { it.message.id })

        // A steer is not a question: 重新回答 and the branch switcher still belong to `q1`,
        // and the reply the model wrote after the line folds into a card of its own.
        assertEquals("q1", rows[2].branch?.turnId)
        assertTrue(rows[2].branch?.canRegenerate == true)
    }

    /**
     * Why the delivered line must carry its run's id. Without it the screen's live filter
     * cannot see that the stored row and the live part are the same line, so a steer typed
     * before the model had written anything back appeared twice — once settled, once live.
     */
    @Test
    fun `a steer without the run id slips past the live filter and doubles up`() {
        val orphan = steer("s1", null)
        val state = ChatUiState(
            messages = listOf(question("q1"), orphan),
            liveRunId = "r1",
            liveParts = listOf(LivePart.Steer(orphan)),
            runningHere = true,
        )

        assertEquals(2, drawn(state, "s1"))
        // Same line, one field apart: with the run id it is drawn once.
        val keyed = steer("s1", "r1")
        val fixed = ChatUiState(
            messages = listOf(question("q1"), keyed),
            liveRunId = "r1",
            liveParts = listOf(LivePart.Steer(keyed)),
            runningHere = true,
        )
        assertEquals(1, drawn(fixed, "s1"))
    }
}

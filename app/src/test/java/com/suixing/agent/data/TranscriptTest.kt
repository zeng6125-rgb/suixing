package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {

    private fun question(id: String) = StoredMessage(id = id, role = Roles.USER, text = "问 $id")

    private fun answer(id: String, run: String?) =
        StoredMessage(id = id, role = Roles.ASSISTANT, text = "答 $id", runId = run)

    private fun call(id: String, run: String?) =
        StoredMessage(
            id = id,
            role = Roles.TOOL,
            toolName = "run_shell",
            toolCallId = "c-$id",
            runId = run,
        )

    @Test
    fun `every message follows the question that opened its turn`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), call("t1", "r1"), question("q2"))
        val turns = Transcript.turnOf(messages)
        assertEquals("q1", turns["q1"])
        assertEquals("q1", turns["a1"])
        assertEquals("q1", turns["t1"])
        assertEquals("q2", turns["q2"])
    }

    @Test
    fun `a second run becomes another branch of the same turn`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), answer("a2", "r2"))
        assertEquals(mapOf("q1" to listOf("r1", "r2")), Transcript.runsByTurn(messages))
    }

    @Test
    fun `a run that emitted several messages is listed once`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), call("t1", "r1"), answer("a2", "r1"))
        assertEquals(mapOf("q1" to listOf("r1")), Transcript.runsByTurn(messages))
    }

    @Test
    fun `the newest run is drawn when nothing was picked`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), answer("a2", "r2"))
        assertEquals(listOf("q1", "a2"), Transcript.active(messages, emptyMap()).map { it.id })
    }

    @Test
    fun `picking a branch hides the other answer but keeps the question`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), call("t1", "r1"), answer("a2", "r2"))
        val shown = Transcript.active(messages, mapOf("q1" to "r1"))
        assertEquals(listOf("q1", "a1", "t1"), shown.map { it.id })
    }

    @Test
    fun `a stale branch id falls back to the newest run`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), answer("a2", "r2"))
        assertEquals(listOf("q1", "a2"), Transcript.active(messages, mapOf("q1" to "gone")).map { it.id })
    }

    @Test
    fun `each turn keeps its own choice`() {
        val messages = listOf(
            question("q1"), answer("a1", "r1"), answer("a2", "r2"),
            question("q2"), answer("b1", "r3"), answer("b2", "r4"),
        )
        val chosen = mapOf("q1" to "r1", "q2" to "r4")
        assertEquals(
            listOf("q1", "a1", "q2", "b2"),
            Transcript.active(messages, chosen).map { it.id },
        )
    }

    @Test
    fun `legacy messages without a run form one branch`() {
        val messages = listOf(question("q1"), answer("a1", null), call("t1", null), answer("a2", null))
        assertEquals(mapOf("q1" to listOf(BASE_RUN)), Transcript.runsByTurn(messages))
        assertEquals(messages, Transcript.active(messages, emptyMap()))
    }

    @Test
    fun `a transcript that starts mid turn is kept`() {
        val messages = listOf(answer("a1", null), question("q1"), answer("a2", "r1"))
        assertEquals(messages, Transcript.active(messages, emptyMap()))
    }

    @Test
    fun `withoutLastAnswer drops only the newest reply`() {
        val messages = listOf(
            question("q1"), answer("a1", "r1"),
            question("q2"), answer("a2", "r2"), call("t2", "r2"),
        )
        assertEquals(
            listOf("q1", "a1", "q2"),
            Transcript.withoutLastAnswer(messages).map { it.id },
        )
    }

    @Test
    fun `withoutLastAnswer on a question with no reply is the list itself`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), question("q2"))
        assertEquals(messages, Transcript.withoutLastAnswer(messages))
    }

    @Test
    fun `chosenRuns reports the run each turn resolved to`() {
        val messages = listOf(question("q1"), answer("a1", "r1"), answer("a2", "r2"))
        assertEquals(mapOf("q1" to "r2"), Transcript.chosenRuns(messages, emptyMap()))
        assertEquals(mapOf("q1" to "r1"), Transcript.chosenRuns(messages, mapOf("q1" to "r1")))
    }
}

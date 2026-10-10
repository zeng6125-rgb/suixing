package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lazy loading, at the boundary.
 *
 * A conversation is drawn as a suffix of whole turns: the newest [PAGE_TURNS] when it is opened,
 * one more page per tap. These are the edges of that cut — where it lands, what it keeps, and
 * what it must never do (split a turn, or draw nothing at all).
 */
class TurnPageTest {

    private fun question(id: String) = StoredMessage(id = id, role = Roles.USER, text = "问 $id")

    private fun answer(id: String, run: String) =
        StoredMessage(id = id, role = Roles.ASSISTANT, text = "答 $id", runId = run)

    /** [count] turns, each a question and its answer, in order. */
    private fun conversation(count: Int): List<StoredMessage> =
        (1..count).flatMap { listOf(question("q$it"), answer("a$it", "r$it")) }

    @Test
    fun `a short conversation is drawn whole`() {
        val messages = conversation(5)

        val page = turnPage(messages, PAGE_TURNS)

        assertEquals(0, page.from)
        assertEquals(5, page.drawn)
        assertEquals(5, page.turns)
        assertEquals(0, page.earlier)
        assertFalse(page.hasEarlier)
    }

    @Test
    fun `a long conversation opens on the newest page`() {
        val messages = conversation(30)

        val page = turnPage(messages, PAGE_TURNS)

        // 30 - 12 = 18 turns above the drawn part, so the page starts at turn 19's question.
        assertEquals(18, page.earlier)
        assertEquals(12, page.drawn)
        assertEquals(30, page.turns)
        assertTrue(page.hasEarlier)
        assertEquals("q19", messages[page.from].id)
    }

    @Test
    fun `each page keeps whole turns and never starts inside one`() {
        val messages = conversation(30)

        val first = turnPage(messages, PAGE_TURNS)
        val second = turnPage(messages, PAGE_TURNS * 2)

        assertEquals("q19", messages[first.from].id)
        assertEquals("q7", messages[second.from].id)
        // The cut is always on a question: a run's rounds belong to the card built for them.
        assertEquals(Roles.USER, messages[second.from].role)
        assertEquals(24, second.drawn)
        assertEquals(6, second.earlier)
    }

    @Test
    fun `a page wider than the conversation draws everything, opening message included`() {
        // A conversation that opens with something other than a question — a greeting.
        val messages = listOf(answer("hello", "r0")) + conversation(3)

        val page = turnPage(messages, 99)

        assertEquals(0, page.from)
        assertEquals(3, page.drawn)
        assertEquals(0, page.earlier)
        assertFalse(page.hasEarlier)
        assertEquals("hello", messages[page.from].id)
    }

    @Test
    fun `a request below one turn is read as one`() {
        val messages = conversation(10)

        val page = turnPage(messages, 0)

        assertEquals(1, page.drawn)
        assertEquals(9, page.earlier)
        assertEquals("q10", messages[page.from].id)
    }

    @Test
    fun `a steer does not open a turn, so it cannot be a page boundary`() {
        val messages = listOf(
            question("q1"),
            answer("a1", "r1"),
            StoredMessage(id = "s1", role = Roles.USER, text = "插一句", steer = true, runId = "r1"),
            answer("a2", "r1"),
            question("q2"),
            answer("a3", "r2"),
            question("q3"),
            answer("a4", "r3"),
        )

        val page = turnPage(messages, 2)

        assertEquals(3, page.turns)
        assertEquals(2, page.drawn)
        assertEquals(1, page.earlier)
        assertEquals("q2", messages[page.from].id)
    }

    @Test
    fun `a conversation with no turn at all draws nothing`() {
        assertEquals(TurnPage(from = 0, turns = 0, drawn = 0), turnPage(emptyList(), PAGE_TURNS))
        assertEquals(0, turnPage(emptyList(), PAGE_TURNS).earlier)
        assertFalse(turnPage(emptyList(), PAGE_TURNS).hasEarlier)
    }

    @Test
    fun `the drawn part is the tail of the conversation`() {
        val messages = conversation(40)

        val page = turnPage(messages, PAGE_TURNS)

        // Counted in turns: the drawn slice holds exactly the newest [drawn] questions.
        val drawnQuestions = messages.drop(page.from).count { it.role == Roles.USER && !it.steer }
        assertEquals(page.drawn, drawnQuestions)
        assertEquals(messages.size, page.from + messages.drop(page.from).size)
    }
}

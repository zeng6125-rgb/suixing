package com.suixing.agent.ui.chat

import androidx.compose.ui.unit.dp
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SteerBubbleSpacingTest {
    private fun steer(id: String) = StoredMessage(
        id = id, role = Roles.USER, text = "插话 $id", steer = true, runId = "run",
    )

    private fun steerPadding(parts: List<Part>) = parts.take(processTailStart(parts)).mapIndexedNotNull { index, part ->
        if (part is Part.Steer) processSteerTopPadding(parts.getOrNull(index - 1)) else null
    }

    @Test fun `consecutive live steers have separation while the answer has no prose`() {
        val projection = LivePartProjection("run")
        val parts = projection.project(listOf(
            LivePart.Thinking("thinking"),
            LivePart.Steer(steer("one")),
            LivePart.Steer(steer("two")),
            LivePart.Steer(steer("three")),
        ), showReasoning = true)

        assertEquals(parts.size, processTailStart(parts))
        assertEquals(listOf("one", "two", "three"), parts.filterIsInstance<Part.Steer>().map { it.message.id })
        assertEquals(listOf(0.dp, 6.dp, 6.dp), steerPadding(parts))
    }

    @Test fun `settled steers keep the same separation inside their process prefix`() {
        val rows = groupRows(listOf(
            StoredMessage(id = "question", role = Roles.USER, text = "question"),
            StoredMessage(id = "thinking", role = Roles.ASSISTANT, reasoning = "thinking", runId = "run"),
            steer("one"), steer("two"),
        ), emptyMap(), false)
        val parts = rows.last().parts

        assertEquals(listOf("question", "thinking"), rows.map { it.message.id })
        assertEquals(parts.size, processTailStart(parts))
        assertEquals(listOf(0.dp, 6.dp), steerPadding(parts))
    }

    @Test fun `hiding reasoning does not join the visible steer bubbles`() {
        val parts = LivePartProjection("run").project(listOf(
            LivePart.Steer(steer("one")),
            LivePart.Thinking("thinking"),
            LivePart.Steer(steer("two")),
        ), showReasoning = false)

        assertEquals(listOf("one", "two"), parts.filterIsInstance<Part.Steer>().map { it.message.id })
        assertEquals(listOf(0.dp, 6.dp), steerPadding(parts))
    }
}

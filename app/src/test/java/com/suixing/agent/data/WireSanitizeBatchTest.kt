package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WireSanitizeBatchTest {
    private fun call(id: String, vararg calls: String) = StoredMessage(
        id = id, role = Roles.ASSISTANT,
        toolCalls = calls.map { StoredToolCall(it, "read_file", "{}") },
    )

    private fun result(id: String, callId: String) = StoredMessage(
        id = id, role = Roles.TOOL, toolCallId = callId, toolOutput = id,
    )

    @Test
    fun `an earlier result cannot answer a later incomplete batch with the same call id`() {
        val messages = listOf(
            call("a1", "call_0"), result("t1", "call_0"), call("a2", "call_0"),
        )
        assertEquals(listOf("a1", "t1"), WireSanitize.apply(messages).map { it.id })
    }

    @Test
    fun `a later batch result cannot complete an earlier partial batch`() {
        val messages = listOf(
            call("a1", "c1", "c2"), result("t1", "c1"),
            call("a2", "c2"), result("t2", "c2"),
        )
        assertEquals(listOf("a2", "t2"), WireSanitize.apply(messages).map { it.id })
    }

    @Test
    fun `one batch keeps first results and ordinary messages in their original order`() {
        val messages = listOf(
            call("a1", "c1", "c2"),
            result("t1", "c1"),
            StoredMessage(id = "steer", role = Roles.USER, text = "Continue", steer = true),
            result("duplicate", "c1"),
            StoredMessage(id = "note", role = Roles.ASSISTANT, text = "Stopped after the first tool"),
            result("t2", "c2"),
            result("orphan", "absent"),
        )
        assertEquals(listOf("a1", "t1", "steer", "note", "t2"), WireSanitize.apply(messages).map { it.id })
    }
}

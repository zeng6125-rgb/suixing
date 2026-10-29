package com.suixing.agent.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentsTest {
    @Test fun `malformed JSON and nonobjects fail before a tool can run`() {
        for (raw in listOf("{\"path\":\"target\",", "null", "[]", "\"command\"", "true")) {
            assertThrows("must reject $raw", IllegalArgumentException::class.java) { parseToolArguments(raw) }
        }
    }

    @Test fun `blank arguments preserve zero argument tool compatibility`() {
        assertEquals(JsonObject(emptyMap()), parseToolArguments("  "))
        assertEquals("{\"command\":\"echo ok\"}", parseToolArguments("{\"command\":\"echo ok\"}").toString())
    }
}

package com.suixing.agent.ui.chat

import com.suixing.agent.tools.Schema
import com.suixing.agent.tools.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandTest {
    private val search = ToolSpec(
        name = "web_search",
        description = "",
        parameters = Schema.objectOf(
            properties = listOf("query" to Schema.string("")),
            required = listOf("query"),
        ),
    )

    @Test
    fun `goal and tool commands split name from argument`() {
        assertEquals(
            SlashCommand("goal", "finish the migration"),
            parseSlashCommand("  /goal  finish the migration  "),
        )
        assertEquals(SlashCommand("goal", ""), parseSlashCommand("/goal"))
        assertEquals(SlashCommand("web_search", "{\"query\":\"a\"}"), parseSlashCommand("/web_search {\"query\":\"a\"}"))
    }

    @Test
    fun `a path or a bare slash is not a command`() {
        assertNull(parseSlashCommand("/data/notes.md"))
        assertNull(parseSlashCommand("/"))
        assertNull(parseSlashCommand("hello /goal"))
    }

    @Test
    fun `plain text fills the first required string parameter`() {
        assertEquals("{\"query\":\"weather\"}", slashArguments(search, "weather").toString())
        assertEquals("{}", slashArguments(search, "").toString())
    }

    @Test
    fun `an explicit json object is passed through`() {
        assertEquals(
            "{\"query\":\"weather\",\"limit\":3}",
            slashArguments(search, "{\"query\":\"weather\",\"limit\":3}").toString(),
        )
    }

    @Test
    fun `a tool with no string input cannot be called from plain text`() {
        val numeric = ToolSpec(
            name = "count",
            description = "",
            parameters = Schema.objectOf(
                properties = listOf("limit" to Schema.integer("")),
                required = listOf("limit"),
            ),
        )
        assertNull(slashArguments(numeric, "3"))
        assertEquals("{\"limit\":3}", slashArguments(numeric, "{\"limit\":3}").toString())
    }
}
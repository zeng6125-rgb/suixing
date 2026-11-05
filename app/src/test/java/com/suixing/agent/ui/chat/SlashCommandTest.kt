package com.suixing.agent.ui.chat

import com.suixing.agent.tools.Schema
import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun selectedFileReferenceBecomesAPathWithoutRewritingOtherTextParameters() {
        val read = ToolSpec("read_file", "", Schema.objectOf(
            properties = listOf("path" to Schema.string("")),
            required = listOf("path"),
        ))
        val path = "/workspace/报告 final.txt"
        val token = fileReferenceToken(path)
        val command = requireNotNull(parseSlashCommand("/read_file " + token))
        assertEquals(path, command.toolArguments(read)?.get("path")?.jsonPrimitive?.content)
        assertEquals(token, slashArguments(search, token)?.get("query")?.jsonPrimitive?.content)
    }

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
    fun `goal reads sets clears and JSON arguments resolve without guessing`() {
        val goal = ToolSpec("goal", "", Schema.objectOf(
            listOf("text" to Schema.string("")), required = listOf("text")))
        val read = ToolSpec("goal_read", "", Schema.objectOf(emptyList()))
        assertEquals("goal_read", SlashCommand("goal", "").toolName())
        assertEquals("goal_resume", SlashCommand("goal", "resume").toolName())
        assertEquals("{}", SlashCommand("goal", "resume").toolArguments(goal).toString())
        assertEquals("{}", SlashCommand("goal", "").toolArguments(read).toString())
        assertEquals("goal", SlashCommand("goal", "finish").toolName())
        assertEquals("{\"text\":\"finish\"}", SlashCommand("goal", "finish").toolArguments(goal).toString())
        assertEquals("{\"text\":\"\"}", SlashCommand("goal", "CLEAR").toolArguments(goal).toString())
        assertEquals("{\"text\":\"new goal\"}", SlashCommand("goal", "{\"text\":\"new goal\"}").toolArguments(goal).toString())
        assertNull(SlashCommand("goal", "{\"other\":\"not a clear request\"}").toolArguments(goal))
        assertNull(SlashCommand("goal", "{\"text\":42}").toolArguments(goal))
        assertEquals("web_search", SlashCommand("web_search", "weather").toolName())
        assertEquals("{\"query\":\"weather\"}", SlashCommand("web_search", "weather").toolArguments(search).toString())
    }

    @Test
    fun `edit-only save masks a user command before it can derive a metadata title`() {
        val message = StoredMessage("user", Roles.USER, "old text")
        val edited = editedTranscriptMessage(message, "/ui_type hunter2")
        assertEquals("user", edited.id)
        assertTrue(edited.edited)
        assertFalse(edited.text.contains("hunter2"))
        assertFalse(edited.text.take(18).contains("hunter2"))
        assertEquals("/goal new goal", editedTranscriptMessage(message, "/goal new goal").text)
        assertEquals("/ui_type example", editedTranscriptMessage(
            StoredMessage("assistant", Roles.ASSISTANT, "old"), "/ui_type example").text)
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

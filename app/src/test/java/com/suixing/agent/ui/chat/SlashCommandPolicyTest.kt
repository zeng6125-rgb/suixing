package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandPolicyTest {
    @Test
    fun `a persona disabled tool stays unavailable except for goal`() {
        val disabled = setOf("run_shell", "goal", "goal_read")

        assertFalse(slashToolAllowed("run_shell", disabled))
        assertFalse(slashToolAllowed("goal_read", disabled))
        assertTrue("goal is a user setting, not a model call", slashToolAllowed("goal", disabled))
        assertTrue(slashToolAllowed("web_search", disabled))
    }

    @Test
    fun `a submitted draft is cleared only while it is still the command`() {
        assertTrue(submittedDraftStillMatches("/web_search 天气", false, "/web_search 天气"))
        assertTrue(submittedDraftStillMatches(" /web_search 天气 ", false, "/web_search 天气"))
        assertFalse(submittedDraftStillMatches("/web_search 天气 明天", false, "/web_search 天气"))
        assertFalse(submittedDraftStillMatches("/web_search 天气", true, "/web_search 天气"))
        assertFalse(submittedDraftStillMatches("", false, "/goal ship"))
    }

    @Test
    fun `the command token ends at the first whitespace`() {
        assertEquals(5, slashTokenEnd("/goal"))
        assertEquals(5, slashTokenEnd("/goal ship it"))
        assertEquals(7, slashTokenEnd("  /goal ship"))
        assertNull(slashTokenEnd("hello"))
        assertNull(slashTokenEnd("/"))
    }

    @Test
    fun `suggestions appear only while the name is still being typed`() {
        val commands = listOf(
            "goal" to "set the objective",
            "goal_read" to "read the objective",
            "web_search" to "search the web",
        )

        assertEquals(listOf("goal", "goal_read"), slashSuggestions(commands, "/go").map { it.first })
        assertEquals(listOf("web_search"), slashSuggestions(commands, "/web").map { it.first })
        assertEquals("goal", slashSuggestions(commands, "/goal").first().first)
        assertEquals(listOf("goal"), slashSuggestions(commands, "/", limit = 1).map { it.first })

        assertTrue("an argument is no longer a command name", slashSuggestions(commands, "/goal ship").isEmpty())
        assertTrue(slashSuggestions(commands, "hi").isEmpty())
    }
}
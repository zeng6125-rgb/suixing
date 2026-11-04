package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandPolicyTest {
    @Test
    fun `persona disabled tools are neither offered nor directly executed`() {
        val disabled = setOf("run_shell", "goal", "goal_read")

        assertFalse(slashToolAllowed("run_shell", disabled))
        assertFalse(slashToolAllowed("goal_read", disabled))
        assertFalse(slashToolAllowed("goal", disabled))
        assertTrue(slashToolAllowed("web_search", disabled))
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
    fun `goal is offered first instead of being buried by registry order`() {
        val registryOrder = (1..10).map { "tool$it" to "tool $it" } + ("goal" to "set the objective")

        val commands = slashCommands(registryOrder)

        assertEquals("goal", commands.first().first)
        assertEquals(listOf("tool1", "tool2", "tool3", "tool4"), commands.drop(1).take(4).map { it.first })
        assertEquals("goal", slashSuggestions(commands, "/").first().first)
        assertEquals(listOf("goal", "tool1"), slashSuggestions(commands, "/", limit = 2).map { it.first })
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
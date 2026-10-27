package com.suixing.agent.agent

import com.suixing.agent.data.MemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectedUserContextTest {
    private val profile = "  PROFILE_SECRET  "
    private val memory = mapOf("preference" to MemoryEntry("MEMORY_SECRET"))

    @Test fun `both switches off remove both sections and their values`() {
        assertEquals("", injectedUserContext(profile, memory, false, false))
    }

    @Test fun `profile alone never leaks memory into the request`() {
        val text = injectedUserContext(profile, memory, true, false)
        assertEquals("\n\n## 关于用户（画像）\nPROFILE_SECRET", text)
        assertFalse(text.contains("MEMORY_SECRET"))
        assertFalse(text.contains("## 你记得的事"))
    }

    @Test fun `memory alone never leaks profile into the request`() {
        val text = injectedUserContext(profile, memory, false, true)
        assertEquals("\n\n## 你记得的事\n- preference: MEMORY_SECRET", text)
        assertFalse(text.contains("PROFILE_SECRET"))
        assertFalse(text.contains("## 关于用户"))
    }

    @Test fun `both switches on preserve profile then sorted memory`() {
        assertEquals(
            "\n\n## 关于用户（画像）\nPROFILE_SECRET\n\n## 你记得的事\n- preference: MEMORY_SECRET",
            injectedUserContext(profile, memory, true, true),
        )
    }

    @Test fun `empty sections do not leave headings`() {
        assertEquals("", injectedUserContext(" \n ", emptyMap(), true, true))
    }

    @Test fun `memory count and value limits preserve the request contract`() {
        val memories = (50 downTo 1).associate { "key%02d".format(it) to MemoryEntry("x".repeat(450)) }
        val lines = injectedUserContext("", memories, false, true).lineSequence().filter { it.startsWith("- ") }.toList()
        assertEquals(40, lines.size)
        assertTrue(lines.first().startsWith("- key01: "))
        assertTrue(lines.last().startsWith("- key40: "))
        assertTrue(lines.all { it.substringAfter(": ").length == 400 })
    }
}

package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TableViewportTest {
    @Test fun `completed prose reuses table reading state without sharing another part or run`() {
        val expansion = ReasoningExpansion("chat", mutableMapOf())
        expansion.protectRun("run")
        val live = expansion.viewport("run:text:0").table(2)
        live.follow = false

        val completed = expansion.viewport("run:text:0").table(2)
        assertSame(live, completed)
        assertFalse(completed.follow)
        assertNotSame(live, expansion.viewport("run:text:0").table(3))
        assertNotSame(live, expansion.viewport("run:text:1").table(2))
        assertNotSame(live, expansion.viewport("next:text:0").table(2))
    }
}

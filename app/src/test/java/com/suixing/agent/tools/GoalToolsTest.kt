package com.suixing.agent.tools

import com.suixing.agent.data.GoalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoalToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the tool writes reads and clears the goal`() = runBlocking {
        val tools = GoalTools(GoalStore(File(folder.root, "goal.json")))

        assertTrue(tools.execute("goal", buildJsonObject { put("text", "ship 0.8.650") }).ok)
        assertTrue(tools.execute("goal_read", buildJsonObject { }).output.contains("ship 0.8.650"))

        val cleared = tools.execute("goal", buildJsonObject { put("text", "") })
        assertTrue(cleared.ok)
        assertEquals("No goal is set.", tools.execute("goal_read", buildJsonObject { }).output)
    }
}
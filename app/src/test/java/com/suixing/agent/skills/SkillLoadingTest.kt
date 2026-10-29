package com.suixing.agent.skills

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillLoadingTest {
    @Test fun `find resolves directory ids loose files and display names with admission state`() = runBlocking {
        addSkill("folder-id", "---\nname: Display Name\n---\nfolder body")
        File(tmp.root, "skills/loose.md").writeText("loose body")
        val store = store()
        assertEquals("folder-id", store.find("FOLDER-ID")!!.id)
        assertEquals("folder-id", store.find("Display Name")!!.id)
        assertEquals("loose", store.find("loose")!!.id)
        store.setEnabled("folder-id", false)
        assertNull(store.find("folder-id"))
        assertNull(store.find("Display Name"))
    }
    @get:Rule val tmp = TemporaryFolder()

    private fun store(): SkillStore {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tmp.root
        every { context.getExternalFilesDir(null) } returns tmp.root
        return SkillStore(context)
    }

    private fun addSkill(id: String, text: String): File =
        File(tmp.root, "skills/$id/SKILL.md").apply { parentFile.mkdirs(); writeText(text) }

    @Test fun `catalogue scan drops bodies but chosen disabled preview retains full instructions`() = runBlocking {
        val file = addSkill("large", "---\nname: 名字\ndescription: 说明\n---\n" + "body\n".repeat(100_000))
        val store = store()
        store.setEnabled("large", false)
        val header = store.listHeaders().single()
        assertEquals("名字", header.name)
        assertEquals("说明", header.description)
        assertEquals("", header.body)
        assertFalse(header.enabled)
        assertNull(store.find("large"))
        assertEquals(splitFrontmatter(file.readText()).second, store.load("large")!!.body)
    }

    @Test fun `metadata fallback matches full parser for frontmatter and plain documents`() {
        for ((index, text) in listOf("plain first\nsecond", "\n---\nname: hi\n---\n\nfirst body\nrest", "---\nname: hi\n---\n", "---\ninvalid unclosed").withIndex()) {
            val file = addSkill("s$index", text)
            val full = parseSkill(file.parentFile, file)
            val header = parseSkill(file.parentFile, file, includeBody = false)
            assertEquals(full.copy(body = ""), header)
        }
    }

    @Test fun `malformed enormous header line cannot become enormous resident metadata`() {
        val file = addSkill("huge", "---\nname: " + "x".repeat(2_000_000) + "\n---\nbody")
        val header = parseSkill(file.parentFile, file, includeBody = false)
        assertEquals("huge", header.name)
        assertEquals("", header.body)
        assertTrue(header.description.length <= 120)
    }

    @Test fun `cold corrupt switch state disables all skills and preserves corrupt evidence`() = runBlocking {
        addSkill("a", "body a"); addSkill("b", "body b")
        File(tmp.root, "skill-state.json").writeText("broken")
        val store = store()
        assertTrue(store.listHeaders().all { !it.enabled })
        assertTrue(File(tmp.root, "skill-state.json.corrupt").isFile)
        assertTrue(store().listHeaders().all { !it.enabled })
        store.setEnabled("a", true)
        assertTrue(store.find("a")!!.enabled)
        assertNull(store.find("b"))
    }

    @Test fun `warm corrupt switch state keeps last valid switches`() = runBlocking {
        addSkill("a", "body a"); addSkill("b", "body b")
        val store = store()
        store.setEnabled("b", false)
        File(tmp.root, "skill-state.json").writeText("broken")
        val recovered = store.listHeaders().associateBy { it.id }
        assertTrue(recovered.getValue("a").enabled)
        assertFalse(recovered.getValue("b").enabled)
    }
}

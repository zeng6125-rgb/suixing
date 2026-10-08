package com.suixing.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Scanning a real directory tree is the half of the loader that only shows up on
 * a device — which folder becomes which skill, how ids are derived, and that a
 * re-import of the same skill lands as one entry, not two. A TemporaryFolder is
 * enough: [scanSkills] and [parseSkill] take plain [File]s.
 */
class SkillScanTest {

    @get:Rule
    val root = TemporaryFolder()

    private fun skillFolder(name: String, content: String): File {
        val folder = File(root.root, name).apply { mkdirs() }
        File(folder, SKILL_FILE_NAME).writeText(content)
        return folder
    }

    private fun looseMd(name: String, content: String): File =
        File(root.root, name).apply { writeText(content) }

    @Test
    fun `a folder with SKILL_md takes its id from the folder name`() {
        skillFolder("checkin", "---\nname: 每日签到\n---\n正文")
        val skill = scanSkills(root.root).single()
        assertEquals("checkin", skill.id)
        assertEquals("每日签到", skill.name)
        assertEquals("正文", skill.body)
    }

    @Test
    fun `a folder without a name field falls back to the folder name`() {
        skillFolder("no-name", "直接正文，没有 frontmatter")
        val skill = scanSkills(root.root).single()
        assertEquals("no-name", skill.id)
        assertEquals("no-name", skill.name)
    }

    @Test
    fun `a folder with no description falls back to the first body line`() {
        skillFolder("no-desc", "---\nname: 只有名字\n---\n这是第一行说明\n第二行不算")
        val skill = scanSkills(root.root).single()
        assertEquals("这是第一行说明", skill.description)
    }

    @Test
    fun `a loose md file takes its id from the file name`() {
        looseMd("standalone.md", "---\nname: 散装技能\n---\n正文")
        val skill = scanSkills(root.root).single()
        assertEquals("standalone", skill.id)
        assertEquals("散装技能", skill.name)
    }

    @Test
    fun `a folder without any SKILL file is skipped`() {
        File(root.root, "empty-dir").mkdirs()
        looseMd("real.md", "正文")
        assertEquals(1, scanSkills(root.root).size)
    }

    @Test
    fun `a missing root directory scans to nothing rather than throwing`() {
        assertTrue(scanSkills(File(root.root, "does-not-exist")).isEmpty())
    }

    @Test
    fun `the catalogue order is stable across scans`() {
        // A prompt that reshuffles every run would miss the prefix cache.
        listOf("zeta", "alpha", "mid").forEach { skillFolder(it, "正文 $it") }
        val ids = scanSkills(root.root).map { it.id }
        assertEquals(listOf("alpha", "mid", "zeta"), ids)
        assertEquals(ids, scanSkills(root.root).map { it.id })
    }

    @Test
    fun `re-importing the same skill folder stays one entry`() {
        // This is the file-level core of import idempotency: a second import
        // overwrites the same folder, so the scan must still see exactly one skill
        // and the newest content.
        skillFolder("dup", "---\nname: 第一版\n---\n旧正文")
        assertEquals(1, scanSkills(root.root).size)

        skillFolder("dup", "---\nname: 第二版\n---\n新正文")
        val after = scanSkills(root.root)
        assertEquals(1, after.size)
        assertEquals("第二版", after.single().name)
        assertEquals("新正文", after.single().body)
    }

    @Test
    fun `a saved skill round-trips through compose and scan`() {
        val content = composeSkill("written", "模型自己存的", "第一步\n第二步")
        skillFolder("written", content)
        val skill = scanSkills(root.root).single()
        assertEquals("written", skill.id)
        assertEquals("模型自己存的", skill.description)
        assertTrue(skill.body.contains("第一步"))
        assertTrue(skill.body.contains("第二步"))
    }
}

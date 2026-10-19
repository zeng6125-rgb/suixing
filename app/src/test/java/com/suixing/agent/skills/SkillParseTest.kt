package com.suixing.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frontmatter split, the catalogue render and the id sanitiser are pure
 * string/file-name logic: a SKILL.md from the wild is only guaranteed to be a
 * markdown document, so every way the header can be absent or malformed is worth
 * pinning down without a device.
 */
class SkillParseTest {

    @Test
    fun `splits a well-formed header from the body`() {
        val text = "---\nname: 每日签到\ndescription: 打开某 App 完成签到\n---\n正文第一行\n"
        val (meta, body) = splitFrontmatter(text)
        assertEquals("每日签到", meta["name"])
        assertEquals("打开某 App 完成签到", meta["description"])
        // Trimmed at the start only: the body reaches the model exactly as it was
        // written, so a trailing newline in the file stays part of the body.
        assertEquals("正文第一行\n", body)
    }

    @Test
    fun `a document with no header is all body`() {
        val text = "# 标题\n直接就是正文"
        val (meta, body) = splitFrontmatter(text)
        assertTrue(meta.isEmpty())
        assertEquals("# 标题\n直接就是正文", body)
    }

    @Test
    fun `an unterminated header is treated as body, not as metadata`() {
        // A leading `---` with no closing fence is a horizontal rule, not frontmatter;
        // guessing here would swallow the whole document as "metadata".
        val text = "---\nname: 没有闭合"
        val (meta, body) = splitFrontmatter(text)
        assertTrue(meta.isEmpty())
        assertTrue(body.contains("name: 没有闭合"))
    }

    @Test
    fun `an empty header yields no metadata and keeps the body`() {
        val (meta, body) = splitFrontmatter("---\n---\n正文")
        assertTrue(meta.isEmpty())
        assertEquals("正文", body)
    }

    @Test
    fun `quotes and comment lines in the header are handled`() {
        val text = "---\n# 这是注释\ndescription: \"带引号的用途\"\n---\n正文"
        val (meta, _) = splitFrontmatter(text)
        assertEquals("带引号的用途", meta["description"])
        assertFalse(meta.containsKey("#"))
    }

    @Test
    fun `a value may itself contain a colon`() {
        val (meta, _) = splitFrontmatter("---\ndescription: 时间: 每天 9 点\n---\n正文")
        assertEquals("时间: 每天 9 点", meta["description"])
    }

    @Test
    fun `compose writes the header a skill file is expected to carry`() {
        val composed = composeSkill("my-id", "一句话用途", "正文内容")
        assertTrue(composed.startsWith("---\n"))
        assertTrue(composed.contains("name: my-id"))
        assertTrue(composed.contains("description: 一句话用途"))
        assertTrue(composed.contains("正文内容"))
    }

    @Test
    fun `ids that become directory names refuse separators and stray dots`() {
        assertEquals("daily-checkin", sanitizeSkillId("daily-checkin"))
        assertEquals("a-b-c", sanitizeSkillId("a/b\\c"))
        assertEquals("name", sanitizeSkillId("..name--"))
        assertEquals("skill", sanitizeSkillId("///"))
        assertEquals("skill", sanitizeSkillId("   "))
    }

    @Test
    fun `an empty catalogue renders to nothing`() {
        assertEquals("", catalogOf(emptyList()))
    }

    @Test
    fun `the catalogue lists name, id and description`() {
        val catalog = catalogOf(
            listOf(
                Skill(id = "checkin", name = "每日签到", description = "签到用", body = "", path = "p"),
            ),
        )
        assertTrue(catalog.contains("## Available skills"))
        assertTrue(catalog.contains("- 每日签到 (id: checkin): 签到用"))
    }

    @Test
    fun `a disabled skill leaves the catalogue the model sees`() {
        // This mirrors SkillStore.catalog: the flag is applied before rendering, so
        // the prompt-building code never has to know about it.
        val all = listOf(
            Skill(id = "on", name = "开着的", description = "在", body = "", path = "a", enabled = true),
            Skill(id = "off", name = "关掉的", description = "不在", body = "", path = "b", enabled = false),
        )
        val catalog = catalogOf(all.filter { it.enabled })
        assertTrue(catalog.contains("开着的"))
        assertFalse(catalog.contains("关掉的"))
    }
}

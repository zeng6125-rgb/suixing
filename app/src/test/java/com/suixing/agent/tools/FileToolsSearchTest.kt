package com.suixing.agent.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `grep_files` / `list_dir` / `find_files` 的实际行为（issue #32 的三项加强）。
 *
 * 为什么要写这些：上下文窗口的边界、排除规则、pattern 与 limit 的先后顺序，全都有具体的
 * 出错方式，而**模型看不出自己漏了** —— 它只会以为"这个项目里没有那个文件"。
 * 只靠"代码看起来对"是不够的：glob 的死循环就是靠测试才暴露的。
 *
 * `FileTools` 需要一个 Android `Context` 才能构造，用 mockk 给一个只提供
 * `getExternalFilesDir` 的假 Context（工作目录指向临时目录）。
 */
class FileToolsSearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun workspace(): File = tmp.newFolder("workspace")

    private fun tools(root: File): FileTools {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getExternalFilesDir(null) } returns root
        every { ctx.filesDir } returns root
        return FileTools(ctx)
    }

    /**
     * 构造参数对象。**数字/布尔要保持原生类型**：`JsonObject.int()` 用的是
     * `JsonPrimitive.intOrNull`，字符串 `"10"` 会读成 null 而回落默认值 ——
     * 于是"传了参数却没生效"在测试里看起来像功能没实现。
     */
    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            put(k, when (v) {
                is Int -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            })
        }
    }

    // --- grep_files ---

    @Test
    fun `grep reports the matching line with its number`() = runBlocking {
        val dir = workspace()
        File(dir, "a.txt").writeText("one\ntwo\nthree\n")
        val result = tools(dir).runForTest("grep_files", args("root" to dir.path, "query" to "two"))
        assertTrue("状态要成功：" + result.output, result.ok)
        assertTrue("要带上匹配内容", result.output.contains("two"))
        assertTrue("要带上行号 2", result.output.contains(":2"))
    }

    @Test
    fun `grep context uses the grep -C format`() = runBlocking {
        val dir = workspace()
        File(dir, "a.txt").writeText("l1\nl2\nMATCH\nl4\nl5\n")
        val result = tools(dir).runForTest(
            "grep_files",
            args("root" to dir.path, "query" to "MATCH", "context" to 1),
        )
        assertTrue("状态要成功：" + result.output, result.ok)
        val out = result.output
        assertTrue("上一行 l2 要出现", out.contains("l2"))
        assertTrue("下一行 l4 要出现", out.contains("l4"))
        // grep -C 的格式约定：匹配行用 ':'，上下文行用 '-'。模型见过这种格式。
        assertTrue("匹配行用冒号：" + out, out.contains(":3:"))
        assertTrue("上下文行用连字符：" + out, out.contains(":2-"))
        assertTrue("上下文行用连字符：" + out, out.contains(":4-"))
    }

    @Test
    fun `grep context is clamped and does not run off the file`() = runBlocking {
        val dir = workspace()
        File(dir, "a.txt").writeText("MATCH\nb\n")
        val result = tools(dir).runForTest(
            "grep_files",
            args("root" to dir.path, "query" to "MATCH", "context" to 5),
        )
        // 关键：不能崩（越界会 IndexOutOfBounds），且要成功
        assertTrue("文件头附近不能越界崩溃：" + result.output, result.ok)
        assertTrue(result.output.contains("MATCH"))
    }

    @Test
    fun `grep exclude skips a path substring`() = runBlocking {
        val dir = workspace()
        File(File(dir, "skipme").apply { mkdirs() }, "x.txt").writeText("needle\n")
        File(dir, "keep.txt").writeText("needle\n")
        val result = tools(dir).runForTest(
            "grep_files",
            args("root" to dir.path, "query" to "needle", "exclude" to "skipme"),
        )
        assertTrue(result.ok)
        assertTrue("保留的文件要命中：" + result.output, result.output.contains("keep.txt"))
        assertFalse("被排除的路径不该出现：" + result.output, result.output.contains("skipme"))
    }

    @Test
    fun `grep files_only lists each file once`() = runBlocking {
        val dir = workspace()
        File(dir, "a.txt").writeText("needle\nneedle again\n")
        val result = tools(dir).runForTest(
            "grep_files",
            args("root" to dir.path, "query" to "needle", "files_only" to "true"),
        )
        assertTrue(result.ok)
        val hits = result.output.lines().filter { it.contains("a.txt") }
        assertEquals("同一个文件只列一次：" + result.output, 1, hits.size)
        // **正向锁定**：`files_only` 的实现是 `hitFiles.add(file.absolutePath)` 后直接
        // `return@walk`（FileTools.kt:411-414），压根不拼行号。这里正面断言"输出就是这个
        // 文件的绝对路径"，而不是只断言"不含某种格式"—— 后者放宽了会掩盖真实缺陷。
        assertEquals(
            "files_only 只返回文件路径本身：" + result.output,
            File(dir, "a.txt").absolutePath,
            hits[0].trim(),
        )
    }

    // --- list_dir ---

    @Test
    fun `list_dir pattern filters before the limit truncates`() = runBlocking {
        val dir = workspace()
        repeat(30) { File(dir, "noise$it.txt").writeText("") }
        File(dir, "wanted.kt").writeText("")
        val result = tools(dir).runForTest(
            "list_dir",
            args("path" to dir.path, "pattern" to "*.kt", "limit" to 5),
        )
        assertTrue(result.ok)
        assertTrue("目标文件必须出现（pattern 要在截断前生效）：" + result.output, result.output.contains("wanted.kt"))
        assertFalse("噪声文件不该出现", result.output.contains("noise0.txt"))
    }

    @Test
    fun `list_dir without pattern still lists everything up to the limit`() = runBlocking {
        val dir = workspace()
        File(dir, "a.txt").writeText("")
        File(dir, "b.txt").writeText("")
        val result = tools(dir).runForTest("list_dir", args("path" to dir.path))
        assertTrue(result.ok)
        assertTrue(result.output.contains("a.txt"))
        assertTrue(result.output.contains("b.txt"))
    }

    // --- find_files ---

    @Test
    fun `find_files glob matches the extension`() = runBlocking {
        val dir = workspace()
        File(dir, "A.kt").writeText("")
        File(dir, "B.java").writeText("")
        val result = tools(dir).runForTest("find_files", args("root" to dir.path, "pattern" to "*.kt"))
        assertTrue(result.ok)
        assertTrue("kt 要命中：" + result.output, result.output.contains("A.kt"))
        assertFalse("java 不该出现", result.output.contains("B.java"))
    }

    @Test
    fun `find_files name still works without a pattern`() = runBlocking {
        val dir = workspace()
        File(dir, "Report.md").writeText("")
        File(dir, "other.txt").writeText("")
        val result = tools(dir).runForTest("find_files", args("root" to dir.path, "name" to "report"))
        assertTrue(result.ok)
        assertTrue("子串匹配且大小写不敏感：" + result.output, result.output.contains("Report.md"))
    }

    @Test
    fun `find_files requires either name or pattern`() = runBlocking {
        val dir = workspace()
        val result = tools(dir).runForTest("find_files", args("root" to dir.path))
        assertFalse("两个都没给要明确报错，而不是列出全部", result.ok)
    }

    // --- issue #59 D1：遍历预算 ---

    /** 零命中也必须报出扫描量：否则模型分不清"真没有"和"预算不够"。 */
    @Test
    fun `find_files reports how many nodes it scanned when nothing matches`() = runBlocking {
        val dir = workspace()
        repeat(5) { File(dir, "f$it.txt").writeText("") }
        val result = tools(dir).runForTest(
            "find_files",
            args("root" to dir.path, "pattern" to "*.nomatch"),
        )
        assertTrue(result.ok)
        assertTrue("要说明没找到：" + result.output, result.output.contains("没有找到"))
        assertTrue("要带扫描量：" + result.output, result.output.contains("本次共扫描"))
    }

    /** `max_visited` 到顶就停，并在输出里说清是哪道闸拦的。 */
    @Test
    fun `find_files stops at max_visited and names the cap`() = runBlocking {
        val dir = workspace()
        // `max_visited` 的生产下限是 100（防误配），所以这里要造超过 100 个节点才够触发。
        repeat(150) { File(dir, "n$it.txt").writeText("") }
        val result = tools(dir).runForTest(
            "find_files",
            args(
                "root" to dir.path,
                "pattern" to "*.nomatch",
                "max_visited" to 10,
                "limit" to 100,
            ),
        )
        assertTrue(result.ok)
        assertTrue("要点名 max_visited：" + result.output, result.output.contains("max_visited"))
        assertTrue("要提示结果可能不完整：" + result.output, result.output.contains("可能不完整"))
    }

    /** 命中够数时按 `limit` 停，不受预算影响（老行为不能被新闸改掉）。 */
    @Test
    fun `find_files still stops at limit before the budget`() = runBlocking {
        val dir = workspace()
        repeat(20) { File(dir, "k$it.kt").writeText("") }
        val result = tools(dir).runForTest(
            "find_files",
            args("root" to dir.path, "pattern" to "*.kt", "limit" to "3"),
        )
        assertTrue(result.ok)
        assertTrue("命中数不该超过 limit：" + result.output, result.output.contains("找到 3 个"))
        assertTrue("要点名 limit：" + result.output, result.output.contains("limit"))
    }
}

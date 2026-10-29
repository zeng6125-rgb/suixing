package com.suixing.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FileTools] 里手写 glob 的行为（issue #32）。
 *
 * 为什么要测：这两个函数决定了 `find_files(pattern="*.kt")` 能不能替代
 * `run_shell("find . -name '*.kt'")`。写错了的表现是**静默漏文件**或**多匹配**，
 * 而模型没法察觉——它只会以为"这个项目里没有那个文件"。
 *
 * 手写而不是转 Regex，是 issue 明确要求的：整条路径转 Regex 的转义太容易错。
 */
class GlobMatchTest {
    @Test fun `path matching ignores case and literal prefixes prune only unrelated directories`() {
        val root = File("build/tmp/glob-prefix")
        val matches = Glob.pathMatcher("SRC/main/*.KT", root)
        assertTrue(matches(File(root, "src/Main/Test.kt")))
        val descend = Glob.directoryMatcher("src/main/*.kt", root)
        assertTrue(descend(File(root, "src")))
        assertTrue(descend(File(root, "src/main/deeper")))
        assertFalse(descend(File(root, "src/test")))
        assertFalse(descend(File(root, "other")))
        assertTrue(Glob.directoryMatcher("**/main/*.kt", root)(File(root, "other")))
    }
    private fun file(path: String) = File(path)

    // --- 名称 glob（不含 /）---

    @Test
    fun `star matches any file name`() {
        assertTrue(Glob.nameMatches("*.kt", "Main.kt"))
        assertTrue(Glob.nameMatches("*.kt", "a.b.kt"))
        assertFalse(Glob.nameMatches("*.kt", "Main.java"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(Glob.nameMatches("a?c.kt", "abc.kt"))
        assertFalse(Glob.nameMatches("a?c.kt", "ac.kt"))
        assertFalse(Glob.nameMatches("a?c.kt", "abbc.kt"))
    }

    @Test
    fun `name glob is case-insensitive`() {
        assertTrue(Glob.nameMatches("*.KT", "main.kt"))
        assertTrue(Glob.nameMatches("Main.*", "main.kt"))
    }

    @Test
    fun `a dot in the pattern is literal, not a regex wildcard`() {
        // 这是手工转 Regex 最容易踩的坑：不转义 "." 就会让 a.kt 匹配 axkt
        assertTrue(Glob.nameMatches("a.kt", "a.kt"))
        assertFalse(Glob.nameMatches("a.kt", "axkt"))
    }

    @Test
    fun `build prefix matches directories named build`() {
        assertTrue(Glob.nameMatches("build*", "build"))
        assertTrue(Glob.nameMatches("build*", "build-tools"))
        assertFalse(Glob.nameMatches("build*", "rebuild"))
    }

    // --- 路径 glob（含 /）：相对 root ---

    @Test
    fun `double star crosses directories`() {
        // **用相对路径构造**：测试跑在 JVM（这台机器是 Windows），`File("/w")` 会被解析成
        // `\w` 之类的盘符路径，绝对路径对不上 —— 之前三条测试全红就是这个原因，
        // 不是 glob 逻辑错（那三行的第一个 assertTrue 都在这里失败）。
        val root = File("build/tmp/globtest")
        assertTrue(Glob.pathMatches("**/test/*.kt", File(root, "src/test/a.kt"), root))
        assertTrue(Glob.pathMatches("**/test/*.kt", File(root, "a/b/test/x.kt"), root))
        // `**/` 要求至少一层前缀 ⇒ 顶层的 test/a.kt 不匹配（与 find -path 一致）
        assertFalse(Glob.pathMatches("**/test/*.kt", File(root, "test/a.kt"), root))
        assertFalse(Glob.pathMatches("**/test/*.kt", File(root, "src/main/a.kt"), root))
    }

    @Test
    fun `single star does not cross a directory boundary`() {
        val root = File("build/tmp/globtest")
        assertFalse(
            "src/*.kt must not reach src/main/a.kt",
            Glob.pathMatches("src/*.kt", File(root, "src/main/a.kt"), root),
        )
        assertTrue(Glob.pathMatches("src/*.kt", File(root, "src/a.kt"), root))
    }

    @Test
    fun `patterns are anchored at the root, not the absolute path`() {
        val root = File("build/tmp/proj")
        // 相对路径是 "src/a.kt" ⇒ "src/*.kt" 命中；root 自己那一段不该参与匹配。
        assertTrue(Glob.pathMatches("src/*.kt", File(root, "src/a.kt"), root))
        assertFalse(Glob.pathMatches("proj/src/*.kt", File(root, "src/a.kt"), root))
    }
}

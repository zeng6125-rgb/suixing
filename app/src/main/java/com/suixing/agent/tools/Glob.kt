package com.suixing.agent.tools

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 手写 glob（issue #32 明确要求：不要转成整条路径的 Regex，转义太容易错）。
 *
 * 支持 `*`（不跨目录）、`?`（单字符）、`**`（跨目录）。不带 `/` 的模式只匹配**文件名**
 * （对应 `find -name`），带 `/` 的匹配**相对 root 的路径**（对应 `find -path`）。
 *
 * 单独一个对象而不是 `FileTools` 里的私有方法：`FileTools` 需要 Android `Context`，
 * 在 JVM 单测里起不来，而这两个函数的边界行为恰恰是最该测的（写错的表现是静默漏文件）。
 */
internal object Glob {
    /**
     * 模式 → 正则的缓存。
     *
     * **为什么需要**：这两个函数的调用方在**目录遍历里逐项**调用它们（`FileTools` 的
     * list/walk，一次 `find_files` 可能几万个文件），同一份 pattern 会被重复编译几千次；
     * 正则编译要解析 + 建树，纯浪费。缓存后每个 pattern 只编一次。
     *
     * 上限 64：模式来自工具参数，实际只有个位数；超过整体清空（简单、无抖动）。
     * `ConcurrentHashMap`：工具调用可能在多个 IO 线程上并行。
     */
    private val nameCache = ConcurrentHashMap<String, Regex>()
    private val pathCache = ConcurrentHashMap<String, Regex>()
    private const val CACHE_MAX = 64

    /** 只针对文件名的 glob：没有目录层级，`*` 匹配任意字符。大小写不敏感（同 `find -iname` 的直觉）。 */
    fun nameMatches(pattern: String, name: String): Boolean {
        val rx = nameCache[pattern] ?: buildNameRegex(pattern)?.also {
            if (nameCache.size >= CACHE_MAX) nameCache.clear()
            nameCache[pattern] = it
        } ?: return false
        return rx.matches(name)
    }

    private fun buildNameRegex(pattern: String): Regex? {
        val regex = StringBuilder("^")
        for (c in pattern) {
            when {
                c == '*' -> regex.append(".*")
                c == '?' -> regex.append('.')
                c in SPECIAL -> regex.append(BACKSLASH).append(c)
                else -> regex.append(c)
            }
        }
        regex.append('$')
        // 编译失败 ⇒ null（调用方按「不匹配」处理，与原来的 getOrDefault(false) 一致）。
        return runCatching { Regex(regex.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }

    /** 针对**相对 [root]** 的路径 glob：`*` 不跨 `/`，`**` 跨任意段。 */
    fun pathMatches(pattern: String, file: File, root: File): Boolean {
        return pathMatcher(pattern, root)(file)
    }

    /** Compile once and normalize the root once for a whole search. */
    fun pathMatcher(pattern: String, root: File): (File) -> Boolean {
        if (!pattern.contains('/')) return { nameMatches(pattern, it.name) }
        val rx = pathCache[pattern] ?: buildPathRegex(pattern)?.also {
            if (pathCache.size >= CACHE_MAX) pathCache.clear()
            pathCache[pattern] = it
        } ?: return { false }
        val base = root.absolutePath.replace('\\', '/').trimEnd('/') + "/"
        return { file -> rx.matches(file.absolutePath.replace('\\', '/').removePrefix(base)) }
    }

    /** A literal directory prefix can exclude subtrees without changing wildcard semantics. */
    fun directoryMatcher(pattern: String?, root: File): (File) -> Boolean {
        val prefix = pattern?.trimStart('/')?.substringBeforeLast('/', "")?.split('/')
            ?.takeWhile { it.isNotEmpty() && '*' !in it && '?' !in it }.orEmpty()
        if (prefix.isEmpty()) return { true }
        val base = root.absolutePath.replace('\\', '/').trimEnd('/') + "/"
        return { dir ->
            val parts = dir.absolutePath.replace('\\', '/').removePrefix(base).split('/')
            parts.zip(prefix).all { (part, expected) -> part.equals(expected, ignoreCase = true) }
        }
    }

    private fun buildPathRegex(pattern: String): Regex? {
        val p = pattern.trimStart('/')
        val regex = StringBuilder("^")
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    regex.append(".*")
                    i += 2
                }
                c == '*' -> {
                    regex.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    regex.append("[^/]")
                    i++
                }
                c in SPECIAL -> {
                    regex.append(BACKSLASH).append(c)
                    i++
                }
                // **必须推进 i**：漏了它就死循环，regex 无限变长直到 OOM
                // （第一版就是这个 bug，测试里表现为 OutOfMemoryError）。
                else -> {
                    regex.append(c)
                    i++
                }
            }
        }
        regex.append('$')
        return runCatching { Regex(regex.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }

    /** 需要转义的正则元字符（只列在 glob 里可能出现的）。 */
    private val SPECIAL = setOf('.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']')

    private const val BACKSLASH = '\\'
}

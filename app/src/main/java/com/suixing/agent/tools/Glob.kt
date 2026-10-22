package com.suixing.agent.tools

import java.io.File

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
    /** 只针对文件名的 glob：没有目录层级，`*` 匹配任意字符。大小写不敏感（同 `find -iname` 的直觉）。 */
    fun nameMatches(pattern: String, name: String): Boolean {
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
        return runCatching { Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(name) }
            .getOrDefault(false)
    }

    /** 针对**相对 [root]** 的路径 glob：`*` 不跨 `/`，`**` 跨任意段。 */
    fun pathMatches(pattern: String, file: File, root: File): Boolean {
        if (!pattern.contains('/')) return nameMatches(pattern, file.name)
        val base = root.absolutePath.trimEnd('/')
        val full = file.absolutePath
        // **统一成 `/`**：Windows 上 File.absolutePath 用反斜杠，而 glob 模式按 `/` 分段
        // （诊断实测：rel = "src\a.kt" 时 `src/*.kt` 匹配失败；Android 用 `/` 所以真机看不出来，
        // 只有 JVM 单测在这台 Windows 上会暴露 —— 但规范化本身在任何平台都无害且更稳）。
        val rel = (if (full.startsWith(base)) full.removePrefix(base) else full)
            .replace('\\', '/')
            .trimStart('/')
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
        return runCatching { Regex(regex.toString()).matches(rel) }.getOrDefault(false)
    }

    /** 需要转义的正则元字符（只列在 glob 里可能出现的）。 */
    private val SPECIAL = setOf('.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']')

    private const val BACKSLASH = '\\'
}

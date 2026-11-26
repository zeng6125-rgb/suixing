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
        if (isAsciiSimpleGlob(pattern) && isPlainAsciiLine(name)) {
            return matchesAsciiGlobName(pattern, name)
        }
        val rx = nameCache[pattern] ?: buildNameRegex(pattern)?.also {
            if (nameCache.size >= CACHE_MAX) nameCache.clear()
            nameCache[pattern] = it
        } ?: return false
        return rx.matches(name)
    }

    private fun isAsciiSimpleGlob(pattern: String): Boolean {
        for (i in 0 until pattern.length) {
            val c = pattern[i]
            if (c.code >= 128 || c == '\\' || c == '{' || c == '}' || c == '\n' || c == '\r') return false
        }
        return true
    }

    private fun isPlainAsciiLine(name: String): Boolean {
        for (i in 0 until name.length) {
            val c = name[i]
            if (c.code >= 128 || c == '\n' || c == '\r') return false
        }
        return true
    }

    private fun matchesAsciiGlobName(pattern: String, name: String): Boolean {
        var pi = 0
        var si = 0
        var starIdx = -1
        var matchIdx = 0
        val plen = pattern.length
        val slen = name.length
        while (si < slen) {
            val pc = if (pi < plen) pattern[pi] else '\u0000'
            when {
                pi < plen && pc == '*' -> {
                    starIdx = pi++
                    matchIdx = si
                }
                pi < plen && (pc == '?' || asciiEqIgnoreCase(pc, name[si])) -> {
                    pi++
                    si++
                }
                starIdx != -1 -> {
                    pi = starIdx + 1
                    si = ++matchIdx
                }
                else -> return false
            }
        }
        while (pi < plen && pattern[pi] == '*') pi++
        return pi == plen
    }

    private fun asciiEqIgnoreCase(a: Char, b: Char): Boolean {
        if (a == b) return true
        val la = if (a in 'A'..'Z') a.code + 32 else a.code
        val lb = if (b in 'A'..'Z') b.code + 32 else b.code
        return la == lb
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

    private fun cachedPathRegex(pattern: String): Regex? =
        pathCache[pattern] ?: buildPathRegex(pattern)?.also {
            if (pathCache.size >= CACHE_MAX) pathCache.clear()
            pathCache[pattern] = it
        }

    /** Compile once and normalize the root once for a whole search. */
    fun pathMatcher(pattern: String, root: File): (File) -> Boolean {
        if (!pattern.contains('/')) return { nameMatches(pattern, it.name) }
        val base = root.absolutePath.replace('\\', '/').trimEnd('/') + "/"
        val trimmed = pattern.trimStart('/')
        if (isSimpleAsciiPathGlob(trimmed)) {
            var compiled: Regex? = null
            var compiledDone = false
            return { file ->
                val rawPath = file.absolutePath
                val start = relativeStartAfterBase(rawPath, base)
                if (isAsciiPathSlice(rawPath, start)) {
                    matchesAsciiPathGlob(trimmed, rawPath, start)
                } else {
                    if (!compiledDone) {
                        compiled = cachedPathRegex(pattern)
                        compiledDone = true
                    }
                    val rx = compiled
                    if (rx == null) false else rx.matches(rawPath.replace('\\', '/').removePrefix(base))
                }
            }
        }
        val rx = cachedPathRegex(pattern) ?: return { false }
        return { file -> rx.matches(file.absolutePath.replace('\\', '/').removePrefix(base)) }
    }

    /** A literal directory prefix can exclude subtrees without changing wildcard semantics. */
    fun directoryMatcher(pattern: String?, root: File): (File) -> Boolean {
        val prefix = pattern?.trimStart('/')?.substringBeforeLast('/', "")?.split('/')
            ?.takeWhile { it.isNotEmpty() && '*' !in it && '?' !in it }.orEmpty()
        if (prefix.isEmpty()) return { true }
        val base = root.absolutePath.replace('\\', '/').trimEnd('/') + "/"
        return { dir -> matchesNormalizedPrefix(dir.absolutePath, base, prefix) }
    }

    private fun matchesNormalizedPrefix(rawPath: String, base: String, prefix: List<String>): Boolean {
        val normalized = if (rawPath.indexOf('\\') >= 0) rawPath.replace('\\', '/') else rawPath
        var pos = if (normalized.startsWith(base)) base.length else 0
        val len = normalized.length
        for (expected in prefix) {
            if (pos > len) return true
            val slash = normalized.indexOf('/', pos)
            val segEnd = if (slash < 0) len else slash
            val segLen = segEnd - pos
            if (segLen != expected.length || !normalized.regionMatches(pos, expected, 0, segLen, ignoreCase = true)) {
                return false
            }
            if (slash < 0) return true
            pos = slash + 1
        }
        return true
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

    private fun isSimpleAsciiPathGlob(pattern: String): Boolean {
        for (i in 0 until pattern.length) {
            val c = pattern[i]
            if (c.code >= 128 || c == BACKSLASH || c == '{' || c == '}' || c == '\r' || c == '\n') {
                return false
            }
        }
        return true
    }

    private fun isAsciiPathSlice(rawPath: String, start: Int): Boolean {
        for (i in start until rawPath.length) {
            val c = rawPath[i]
            if (c.code >= 128 || c == '\r' || c == '\n') return false
        }
        return true
    }

    private fun relativeStartAfterBase(rawPath: String, base: String): Int {
        if (rawPath.length < base.length) return 0
        for (i in 0 until base.length) {
            val rc = rawPath[i]
            val normalized = if (rc == BACKSLASH) '/' else rc
            if (normalized != base[i]) return 0
        }
        return base.length
    }

    private fun asciiLower(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c

    private fun pathCharAt(rawPath: String, index: Int): Char {
        val c = rawPath[index]
        return if (c == BACKSLASH) '/' else c
    }

    /**
     * Zero-allocation matcher for ASCII path globs (`*`, `**`, `?`), preserving exact
     * [buildPathRegex] semantics (`**` -> `.*`, `*` -> `[^/]*`, `?` -> `[^/]`, ASCII ignore-case)
     * without allocating regex objects or slash-normalized string copies.
     */
    private fun matchesAsciiPathGlob(pattern: String, rawPath: String, startPos: Int): Boolean {
        val pLen = pattern.length
        val tLen = rawPath.length
        val firstDbl = pattern.indexOf("**")
        if (firstDbl < 0) {
            return matchSingleStarAnchored(pattern, 0, pLen, rawPath, startPos, tLen)
        }
        var tCursor = if (firstDbl == 0) {
            startPos
        } else {
            matchSingleStarPrefix(pattern, 0, firstDbl, rawPath, startPos, tLen)
        }
        if (tCursor < 0) return false
        var chunkStart = firstDbl + 2
        while (true) {
            val nextDbl = pattern.indexOf("**", chunkStart)
            if (nextDbl < 0) break
            if (nextDbl > chunkStart) {
                var matchedEnd = -1
                var tryStart = tCursor
                while (tryStart <= tLen) {
                    val end = matchSingleStarPrefix(pattern, chunkStart, nextDbl, rawPath, tryStart, tLen)
                    if (end >= 0) {
                        matchedEnd = end
                        break
                    }
                    tryStart++
                }
                if (matchedEnd < 0) return false
                tCursor = matchedEnd
            }
            chunkStart = nextDbl + 2
        }
        if (chunkStart == pLen) return true
        for (tryStart in tCursor..tLen) {
            if (matchSingleStarAnchored(pattern, chunkStart, pLen, rawPath, tryStart, tLen)) {
                return true
            }
        }
        return false
    }

    private fun matchSingleStarPrefix(
        pattern: String,
        pStart: Int,
        pEnd: Int,
        rawPath: String,
        tStart: Int,
        tEnd: Int,
    ): Int {
        var pi = pStart
        var ti = tStart
        var starPi = -1
        var starTi = 0
        while (pi < pEnd) {
            val pc = pattern[pi]
            if (pc == '*') {
                starPi = ++pi
                starTi = ti
            } else if (ti < tEnd) {
                val tc = pathCharAt(rawPath, ti)
                val matches = if (pc == '?') tc != '/' else asciiLower(pc) == asciiLower(tc)
                if (matches) {
                    pi++
                    ti++
                } else if (starPi != -1 && starTi < tEnd && pathCharAt(rawPath, starTi) != '/') {
                    starTi++
                    ti = starTi
                    pi = starPi
                } else {
                    return -1
                }
            } else {
                return -1
            }
        }
        return ti
    }

    private fun matchSingleStarAnchored(
        pattern: String,
        pStart: Int,
        pEnd: Int,
        rawPath: String,
        tStart: Int,
        tEnd: Int,
    ): Boolean {
        var pi = pStart
        var ti = tStart
        var starPi = -1
        var starTi = 0
        while (ti < tEnd) {
            if (pi < pEnd && pattern[pi] == '*') {
                starPi = ++pi
                starTi = ti
            } else if (pi < pEnd) {
                val pc = pattern[pi]
                val tc = pathCharAt(rawPath, ti)
                val matches = if (pc == '?') tc != '/' else asciiLower(pc) == asciiLower(tc)
                if (matches) {
                    pi++
                    ti++
                } else if (starPi != -1 && starTi < tEnd && pathCharAt(rawPath, starTi) != '/') {
                    starTi++
                    ti = starTi
                    pi = starPi
                } else {
                    return false
                }
            } else if (starPi != -1 && starTi < tEnd && pathCharAt(rawPath, starTi) != '/') {
                starTi++
                ti = starTi
                pi = starPi
            } else {
                return false
            }
        }
        while (pi < pEnd && pattern[pi] == '*') pi++
        return pi == pEnd
    }
}

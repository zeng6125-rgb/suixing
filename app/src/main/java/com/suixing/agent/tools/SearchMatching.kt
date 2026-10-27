package com.suixing.agent.tools

/** Both grep modes follow the tool's case_sensitive contract. */
internal fun searchPattern(query: String, caseSensitive: Boolean): Regex =
    Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

internal fun searchLineMatches(
    line: String,
    query: String,
    caseSensitive: Boolean,
    pattern: Regex?,
): Boolean = pattern?.containsMatchIn(line) ?: line.contains(query, ignoreCase = !caseSensitive)

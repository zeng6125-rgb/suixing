package com.suixing.agent.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Parsed documents owned by the rows about to mount, rather than an extra process cache. */
internal val LocalPreparedMarkdown = staticCompositionLocalOf<Map<String, List<MdBlock>>> { emptyMap() }

internal suspend fun prepareMarkdownTexts(texts: List<String>): Map<String, List<MdBlock>> {
    val required = texts.filter { it.length >= SettledBlocks.MIN_CHARS }.distinct()
    if (required.isEmpty()) return emptyMap()
    return withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        buildMap {
            for (text in required) {
                context.ensureActive()
                put(text, SettledBlocks.get(text) ?: parseMarkdown(text) { context.ensureActive() })
            }
        }
    }
}

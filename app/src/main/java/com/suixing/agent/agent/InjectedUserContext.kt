package com.suixing.agent.agent

import com.suixing.agent.data.MemoryEntry

/** The exact optional suffix used by systemPrompt; disabled sections contribute no bytes. */
internal fun injectedUserContext(
    profile: String,
    memory: Map<String, MemoryEntry>,
    injectProfile: Boolean,
    injectMemory: Boolean,
): String = buildString {
    val about = if (injectProfile) profile.trim() else ""
    if (about.isNotBlank()) {
        append("\n\n## 关于用户（画像）\n")
        append(about)
    }
    if (injectMemory && memory.isNotEmpty()) {
        append("\n\n## 你记得的事\n")
        append(memory.entries.sortedBy { it.key }.take(40).joinToString("\n") { (key, entry) ->
            "- $key: ${entry.value.take(400)}"
        })
    }
}

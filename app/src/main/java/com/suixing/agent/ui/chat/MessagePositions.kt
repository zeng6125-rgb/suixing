package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage

/** Preserves indexOfFirst semantics even for older transcripts with duplicate IDs. */
internal fun List<StoredMessage>.firstPositions(): Map<String, Int> = buildMap {
    forEachIndexed { index, message -> putIfAbsent(message.id, index) }
}

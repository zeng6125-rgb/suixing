package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage

/** The raw head object survives append-only stream updates; IDs can repeat in old logs. */
internal fun prependEarlierPage(
    messages: List<StoredMessage>,
    expectedHead: StoredMessage,
    older: List<StoredMessage>,
): List<StoredMessage>? = if (messages.firstOrNull() === expectedHead) older + messages else null

/** Rebase a stream snapshot over pages loaded since it was taken, without deduplicating IDs. */
internal fun carryEarlierPages(
    messages: List<StoredMessage>,
    knownPrefixSize: Int,
    loadedPrefix: List<StoredMessage>,
): List<StoredMessage> {
    val missing = loadedPrefix.size - knownPrefixSize
    return if (missing > 0) loadedPrefix.take(missing) + messages else messages
}

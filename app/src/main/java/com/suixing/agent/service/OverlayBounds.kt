package com.suixing.agent.service

/** A window larger than the available area is anchored at its visible start edge. */
internal fun boundedOverlayOffset(offset: Int, available: Int, extent: Int): Int =
    offset.coerceIn(0, (available - extent).coerceAtLeast(0))

/** Retain complete UTF-8 code points within the byte budget. */
internal fun utf8Prefix(bytes: ByteArray, budget: Int): String {
    var end = budget.coerceIn(0, bytes.size)
    while (end > 0 && end < bytes.size && bytes[end].toInt() and 0xC0 == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}

internal fun utf8Suffix(bytes: ByteArray, budget: Int): String {
    var start = (bytes.size - budget.coerceAtLeast(0)).coerceAtLeast(0)
    while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) start++
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}

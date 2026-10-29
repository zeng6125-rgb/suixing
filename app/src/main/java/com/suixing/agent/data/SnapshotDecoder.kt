package com.suixing.agent.data

/** One decoded snapshot, shared by all readers. No defaults are emitted before disk is read. */
internal class SnapshotDecoder<T : Any>(private val decode: (String?) -> T) {
    private data class Entry<T>(val raw: String?, val value: T)
    private var latest: Entry<T>? = null

    @Synchronized
    fun read(raw: String?): T {
        val saved = latest
        if (saved != null && saved.raw == raw) return saved.value
        return decode(raw).also { latest = Entry(raw, it) }
    }
}

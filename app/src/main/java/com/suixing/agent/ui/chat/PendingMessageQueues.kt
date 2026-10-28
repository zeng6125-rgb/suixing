package com.suixing.agent.ui.chat

/**
 * Only undelivered user input belongs here. Consuming/retracting the last item removes the
 * conversation entry as well; a run ending or the user looking elsewhere never discards input.
 * Callbacks in [drain] run outside the monitor so sending can enqueue/reenter safely.
 */
internal class PendingMessageQueues<T>(private val idOf: (T) -> String) {
    private val lock = Any()
    private val queues = HashMap<String, List<T>>()
    private val draining = HashSet<String>()

    val conversationCount: Int get() = synchronized(lock) { queues.size }

    fun list(chatId: String): List<T> = synchronized(lock) { queues[chatId].orEmpty() }

    fun add(chatId: String, item: T, first: Boolean = false) = synchronized(lock) {
        val old = queues[chatId].orEmpty()
        queues[chatId] = if (first) listOf(item) + old else old + item
    }

    fun take(chatId: String, id: String): T? = synchronized(lock) {
        val old = queues[chatId] ?: return@synchronized null
        val item = old.firstOrNull { idOf(it) == id } ?: return@synchronized null
        replace(chatId, old.filterNot { idOf(it) == id })
        item
    }

    fun forget(chatId: String) = synchronized(lock) { queues.remove(chatId); Unit }

    enum class DrainResult { BUSY, BACKGROUND, EMPTY, SENT, REFUSED }

    /** Take before send; put back at the head only if sending was refused. */
    fun drain(chatId: String, isDisplayed: () -> Boolean, send: (T) -> Boolean): DrainResult {
        if (!synchronized(lock) { draining.add(chatId) }) return DrainResult.BUSY
        try {
            if (!isDisplayed()) return DrainResult.BACKGROUND
            val head = synchronized(lock) {
                val old = queues[chatId].orEmpty()
                old.firstOrNull()?.also { replace(chatId, old.drop(1)) }
            } ?: return DrainResult.EMPTY
            val accepted = try {
                send(head)
            } catch (error: Throwable) {
                add(chatId, head, first = true)
                throw error
            }
            if (accepted) return DrainResult.SENT
            add(chatId, head, first = true)
            return DrainResult.REFUSED
        } finally {
            synchronized(lock) { draining.remove(chatId) }
        }
    }

    /** Caller holds [lock]. Never retain an empty list for a consumed conversation. */
    private fun replace(chatId: String, items: List<T>) {
        if (items.isEmpty()) queues.remove(chatId) else queues[chatId] = items
    }
}

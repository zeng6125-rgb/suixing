package com.suixing.agent.data

/**
 * 最近打开过的会话正文，按**访问顺序**淘汰（LRU），容量固定。
 *
 * 为什么单独一个类：它的行为（淘汰顺序、容量、线程安全）比"嵌在 ViewModel 里的一行
 * LinkedHashMap"更容易出错，而 ViewModel 需要 Android Context 起不来单测。
 * 这里纯 Kotlin、无依赖，可以直接测。
 *
 * **每条都带着"这份内容是否已经在磁盘上"**（[Entry.diskBacked]）。两件事放进同一个值，
 * 是为了让"内容与标记脱节"物理上不可能发生 —— 本项目已经因为"一个概念存两份"出过四次事故
 * （`feedPrefix`/`itemCount`、钉住高度/开关、两套位置记忆、缓存/标记）。
 *
 * 容量 5（用户定的数）：在几个会话之间来回切不该反复读盘；同时最坏内存 = 5 × 一页，
 * 与"列表常驻全文"（54MB，RSS 413MB 的根因）不是一个量级。
 *
 * @param capacity 最多缓存几个会话。
 */
internal class MessageCache(private val capacity: Int = 5) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * 一条缓存：正文 + 它是否**确认已经在磁盘上**。
     *
     * `diskBacked = true` 只应给"刚从那块盘读回来的页"或"刚刚保存成功的那一份"；
     * 内存里拼出来的（可能含保存失败的消息）必须是 `false` —— 它决定合并草稿时能不能删草稿，
     * 而草稿是进程被杀后唯一的副本。
     */
    internal data class Entry(
        val messages: List<StoredMessage>,
        val diskBacked: Boolean,
    )

    // Insertion order plus explicit promotion lets metadata lookups stay O(1) without
    // changing which transcript will be evicted. All access is under this instance's lock.
    private val map = object : LinkedHashMap<String, Entry>(capacity, 0.75f, false) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Entry>?,
        ): Boolean = size > capacity
    }

    /** 写入一条。空列表**不写入**：它没有信息量（也因此无法用空列表"降级"已有标记）。 */
    @Synchronized
    fun put(chatId: String, messages: List<StoredMessage>, diskBacked: Boolean = false) {
        if (chatId.isEmpty() || messages.isEmpty()) return
        map.remove(chatId)
        map[chatId] = Entry(messages, diskBacked)
    }

    /** 命中的条目会被移到"最近使用"那一端。 */
    @Synchronized
    fun get(chatId: String): List<StoredMessage>? {
        val entry = map.remove(chatId) ?: return null
        map[chatId] = entry
        return entry.messages
    }

    /**
     * 这条是否确认已落盘。**不移动访问顺序** —— 读一个标记不该改变淘汰顺序
     * （否则一次遍历式调用就会把后台会话"续命"）。
     */
    @Synchronized
    fun isDiskBacked(chatId: String): Boolean =
        map[chatId]?.diskBacked == true

    @Synchronized
    fun remove(chatId: String) {
        map.remove(chatId)
    }

    /** 只为测试与诊断：当前缓存了哪些会话，**按最近使用从旧到新**。 */
    @Synchronized
    fun keys(): List<String> = map.keys.toList()

    @Synchronized
    fun size(): Int = map.size
}

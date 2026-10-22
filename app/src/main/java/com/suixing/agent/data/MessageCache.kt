package com.suixing.agent.data

/**
 * 最近打开过的会话正文，按**访问顺序**淘汰（LRU），容量固定。
 *
 * 为什么单独一个类：它的行为（淘汰顺序、容量、线程安全）比"嵌在 ViewModel 里的一行
 * LinkedHashMap"更容易出错，而 ViewModel 需要 Android Context 起不来单测。
 * 这里纯 Kotlin、无依赖，可以直接测。
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

    private val map = object : LinkedHashMap<String, List<StoredMessage>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<StoredMessage>>?,
        ): Boolean = size > capacity
    }

    /** 命中的条目会被移到"最近使用"那一端。空列表**不写入**：它没有信息量。 */
    @Synchronized
    fun put(chatId: String, messages: List<StoredMessage>) {
        if (chatId.isEmpty() || messages.isEmpty()) return
        map[chatId] = messages
    }

    @Synchronized
    fun get(chatId: String): List<StoredMessage>? = map[chatId]

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

package com.suixing.agent.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「最新状态说了算」的单写者队列（issue #66 P0-1 / #68 P1-B 共用）。
 *
 * 纯 Mutex 不够：排队顺序是**提交时刻**的，而并发写完成顺序可能与内容新旧无关 ——
 * 先发起的整表快照可能后落盘，把较新的状态覆盖回旧值。
 *
 * 做法：每次 [enqueue] 领一个单调修订号，拿到锁后比对"还是不是最新号"，不是就跳过写盘。
 * 写失败不推进已写修订号 ⇒ 同样内容可以重试。
 */
internal class LatestWriteQueue<T>(
    private val write: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private val lock = Any()
    private var latestRevision = 0L
    private var writtenRevision = 0L

    fun revision(): Long = synchronized(lock) { latestRevision }

    /** 返回 true 表示这次真的写了；false 表示有更新的内容在后面，这次被合并掉。 */
    suspend fun enqueue(value: T): Boolean {
        val token = synchronized(lock) { ++latestRevision }
        return mutex.withLock {
            val isLatest = synchronized(lock) { token == latestRevision && token > writtenRevision }
            if (!isLatest) return@withLock false
            write(value)
            synchronized(lock) { if (token > writtenRevision) writtenRevision = token }
            true
        }
    }
}

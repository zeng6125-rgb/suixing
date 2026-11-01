package com.suixing.agent.ui.drawer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 排序偏好的**单写者**（issue #66 P0-1）。
 *
 * 问题是"旧快照后入队"：两个 `save()` 并发时，先发起的那次可能后完成，把较新的顺序覆盖回旧值。
 * 只在外面套一把 Mutex 不够 —— 排队顺序是提交时刻的，不是**内容新旧**的。
 *
 * 这里用单调修订号解决：每次 [enqueue] 领一个号，写之前比对"现在还是不是最新号"，
 * 不是就跳过这次写盘（最新那次紧接着会写）。这样：
 * - 同一时刻最多一个写任务在跑（Mutex）；
 * - 过期内容永远不落盘，不管它排队排得多晚；
 * - 写失败不改变"最新号"，下次同内容再 enqueue 仍会被写入。
 */
internal class OrderWriteQueue(
    private val write: suspend (DrawerOrder) -> Unit,
) {
    private val mutex = Mutex()
    private val lock = Any()
    private var latestRevision = 0L
    private var writtenRevision = 0L

    /** 当前最新修订号（测试与日志用）。 */
    fun revision(): Long = synchronized(lock) { latestRevision }

    /**
     * 提交一次写。返回 true 表示这次调用真的写了盘；false 表示有更新的顺序排在后面，写被合并掉。
     */
    suspend fun enqueue(order: DrawerOrder): Boolean {
        val token = synchronized(lock) { ++latestRevision }
        return mutex.withLock {
            // 拿到锁之后再判断：排队期间可能已经有更新的顺序进来了。
            val isLatest = synchronized(lock) { token == latestRevision && token > writtenRevision }
            if (!isLatest) return@withLock false
            write(order)
            synchronized(lock) { if (token > writtenRevision) writtenRevision = token }
            true
        }
    }
}

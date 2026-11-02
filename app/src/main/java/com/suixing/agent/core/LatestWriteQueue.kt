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

    /** Reserve order at the caller's event time, before dispatching asynchronous work. */
    fun submit(value: T): LatestWriteRequest<T> = synchronized(lock) {
        LatestWriteRequest(revision = ++latestRevision, value = value)
    }

    /** Whether this request still represents the newest submitted state. */
    fun isCurrent(request: LatestWriteRequest<T>): Boolean = synchronized(lock) {
        request.revision == latestRevision
    }

    /**
     * Commit a previously submitted request. Returns false when a newer request superseded it
     * before it reached the writer. A failed write leaves the request retryable.
     */
    suspend fun commit(request: LatestWriteRequest<T>): Boolean {
        return mutex.withLock {
            val isLatest = synchronized(lock) {
                request.revision == latestRevision && request.revision > writtenRevision
            }
            if (!isLatest) return@withLock false
            write(request.value)
            synchronized(lock) {
                if (request.revision > writtenRevision) writtenRevision = request.revision
            }
            true
        }
    }

    /** Run recovery under the same writer lock, but only while its failed request is current. */
    suspend fun recoverIfCurrent(
        request: LatestWriteRequest<T>,
        recover: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val canRecover = synchronized(lock) {
            request.revision == latestRevision && request.revision > writtenRevision
        }
        if (!canRecover) return@withLock false
        recover()
        true
    }

    /** Convenience for callers whose submission and commit are already in the same coroutine. */
    suspend fun enqueue(value: T): Boolean = commit(submit(value))

    /** Wait for a currently active commit to finish before reading the backing store. */
    suspend fun awaitIdle() {
        mutex.withLock { }
    }
}

internal data class LatestWriteRequest<T>(
    val revision: Long,
    val value: T,
)

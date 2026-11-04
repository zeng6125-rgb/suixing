package com.suixing.agent.agent

import com.suixing.agent.data.Goal
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.GoalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Continuation budget and wake state for one engine handle. The store owns the durable state. */
internal class GoalRun(private val store: GoalStore) {
    @Volatile var id = ""
        private set
    private var turns = 0
    private var lastRevision = -1
    private var plainRepliesWithoutProgress = 0

    fun activate(id: String) {
        turns = 0
        lastRevision = -1
        plainRepliesWithoutProgress = 0
        this.id = id
    }

    fun snapshot(): Goal? = store.read().takeIf { it.id == id && id.isNotEmpty() }

    suspend fun beforeRequest(): Goal? {
        val goal = snapshot() ?: return null
        if (goal.status != GoalStatus.ACTIVE) return goal
        if (turns >= 24) {
            block("已达到本次目标的运行上限，可以继续")
            return snapshot()
        }
        turns++
        if (goal.wakeAt > 0L) {
            val remaining = goal.wakeAt - System.currentTimeMillis()
            if (remaining > 0L) withTimeoutOrNull(remaining) {
                store.changes.first { it == null || it.id != id || it.status != GoalStatus.ACTIVE }
            }
            withContext(Dispatchers.IO) { store.consumeWake(id, goal.wakeAt) }
        }
        return snapshot()
    }

    suspend fun continueAfterPlainReply(): Boolean {
        val goal = snapshot() ?: return false
        if (goal.status != GoalStatus.ACTIVE) return false
        if (lastRevision != goal.revision) {
            lastRevision = goal.revision
            plainRepliesWithoutProgress = 0
        }
        if (++plainRepliesWithoutProgress < 2) return true
        block("连续两次没有收到目标进度，可以继续或补充要求")
        return false
    }

    suspend fun block(reason: String) = withContext(Dispatchers.IO) {
        store.pause(id, reason, blocked = true)
    }

    fun pauseOnExit(cancelled: Boolean) {
        store.pause(id, if (cancelled) "用户已停止，可以继续" else "运行已结束，可以继续")
    }
}

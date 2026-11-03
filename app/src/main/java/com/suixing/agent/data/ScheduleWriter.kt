package com.suixing.agent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one serialized writer for reminders.
 *
 * A screen can hold an old board, an agent tool can create a reminder, and a fired alarm
 * can move its own next time — all three used to rewrite the whole file independently.
 * This writer applies each mutation to the *latest* stored board under one lock, then
 * arms from the same commit, so an old screen cannot erase a newer reminder and the alarm
 * always describes the bytes that were just written.
 */
class ScheduleWriter(
    private val store: ScheduleStore,
    private val arm: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun mutate(block: (List<Schedule>) -> List<Schedule>): List<Schedule> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                store.mutate(block).also { arm() }
            }
        }

    /** Re-arm the current board without changing it. */
    suspend fun rearm() = mutex.withLock {
        withContext(Dispatchers.IO) { arm() }
    }

    suspend fun awaitIdle() {
        mutex.withLock { }
    }
}
package com.suixing.agent.ui.chat

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A current-value projection without an eager worker; collection exists only with a reader. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal fun <T, R> StateFlow<T>.projectState(transform: (T) -> R): StateFlow<R> {
    val source = this
    return object : StateFlow<R> {
        override val value: R get() = transform(source.value)
        override val replayCache: List<R> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            source.map(transform).distinctUntilChanged().collect(collector)
            error("A StateFlow cannot complete normally")
        }
    }
}

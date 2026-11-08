package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.suixing.agent.R
import kotlinx.coroutines.flow.first

/**
 * Keep event parameters stable when their implementation closes over values that change while
 * the screen is composing (for example a streamed run state). The latest body is installed after
 * composition, while children keep the same callback identity and can skip unrelated updates.
 */
@Composable
internal fun <R> rememberStableCallback0(block: () -> R): () -> R {
    val latest = rememberUpdatedState(block)
    return remember { { latest.value() } }
}

@Composable
internal fun <A> rememberStableCallback1(block: (A) -> Unit): (A) -> Unit {
    val latest = rememberUpdatedState(block)
    return remember { { value: A -> latest.value(value) } }
}

@Composable
internal fun <A, B, R> rememberStableCallback2(block: (A, B) -> R): (A, B) -> R {
    val latest = rememberUpdatedState(block)
    return remember { { first: A, second: B -> latest.value(first, second) } }
}

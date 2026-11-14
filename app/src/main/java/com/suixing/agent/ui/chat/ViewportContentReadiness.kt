package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first

/** Initial preparations belong to this mounted viewport and end with their leaf's lifetime. */
internal class ViewportContentReadiness {
    private var pending by mutableIntStateOf(0)

    fun hold(): () -> Unit {
        pending++
        var held = true
        return {
            if (held) {
                held = false
                pending--
            }
        }
    }

    suspend fun awaitReady() {
        snapshotFlow { pending == 0 }.first { it }
    }
}

internal val LocalViewportContentReadiness = staticCompositionLocalOf<ViewportContentReadiness?> { null }

@Composable
internal fun InitialViewportContentReady(ready: Boolean) {
    val owner = LocalViewportContentReadiness.current ?: return
    val release = remember(owner) { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(owner) {
        release[0] = owner.hold()
        onDispose { release[0]?.invoke() }
    }
    SideEffect {
        if (ready) release[0]?.invoke()
    }
}

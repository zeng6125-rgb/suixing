package com.suixing.agent.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI-thread edits, coalesced by field without overwriting other concurrent settings. */
internal class PendingSettingsWrites {
    private val edits = linkedMapOf<String, (AppSettings) -> AppSettings>()
    var revision by mutableIntStateOf(0)
        private set

    fun put(field: String, edit: (AppSettings) -> AppSettings) {
        edits[field] = edit
        revision++
    }

    fun take(): ((AppSettings) -> AppSettings)? {
        if (edits.isEmpty()) return null
        val batch = edits.values.toList()
        // Consume before dispatch so leaving the page cannot send the same batch twice.
        edits.clear()
        return { settings -> batch.fold(settings) { current, edit -> edit(current) } }
    }
}

@Composable
internal fun rememberDebouncedSettingsWrites(): PendingSettingsWrites {
    val pending = remember { PendingSettingsWrites() }
    val tail = remember { arrayOfNulls<Job>(1) }
    val dispatch: ((AppSettings) -> AppSettings) -> Unit = remember {
        { edit ->
            val previous = tail[0]
            tail[0] = Core.appScope.launch {
                previous?.join()
                Core.settings.update(edit)
            }
        }
    }
    LaunchedEffect(pending.revision) {
        delay(400)
        pending.take()?.let(dispatch)
    }
    DisposableEffect(pending) {
        onDispose { pending.take()?.let(dispatch) }
    }
    return pending
}

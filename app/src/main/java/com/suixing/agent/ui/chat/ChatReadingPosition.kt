package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import com.suixing.agent.data.AppSettings
import kotlinx.coroutines.flow.first

/** Character anchoring reacts in the layout/effect scopes, not at screen composition frequency. */
@Composable
internal fun rememberChatReadingPosition(
    state: ChatUiState,
    settings: AppSettings,
    transcript: ChatTranscriptProjection,
    gesture: ChatReadingGesture,
    scroll: ChatScroll,
    reasoningExpansion: ReasoningExpansion,
    overscrollActiveNow: () -> Boolean = { false },
): TranscriptPosition = with(gesture) {
    val listState = transcript.listState
    val viewport = transcript.viewport
    val transcriptPosition = remember(listState) { TranscriptPosition(listState) }
    val nestedScrollingNow = remember(transcriptPosition) { { transcriptPosition.nestedScrolling } }
    val followPositionNow = rememberUpdatedState {
        settings.autoFollow && scroll.stickToBottom && !scroll.restoring &&
            !pressed && !pressGuardOn && !listState.isScrollInProgress && !overscrollActiveNow()
    }
    val readingPositionNow = rememberUpdatedState {
        !scroll.stickToBottom && !scroll.restoring && !pressed && !pressGuardOn &&
            !listState.isScrollInProgress && !overscrollActiveNow() && viewport.presented && viewport.entrance.value >= 1f
    }
    SideEffect {
        scroll.nestedScrolling = nestedScrollingNow
        transcriptPosition.following = { followPositionNow.value() }
        transcriptPosition.reading = { readingPositionNow.value() }
        transcriptPosition.latestStillWanted = { scroll.stickToBottom }
    }
    // Install before composing rows: a first derived read must observe these snapshot inputs,
    // rather than caching the initial callback's constant false until an unrelated change.
    reasoningExpansion.holdCompletion = { runId ->
        // The task finishes immediately; only a visible live row's automatic geometry
        // hand-over waits until character anchoring can run without fighting a gesture or spring rebound.
        !scroll.stickToBottom && !scroll.restoring &&
            (pressed || pressGuardOn || listState.isScrollInProgress || overscrollActiveNow() || transcriptPosition.nestedScrolling) &&
            listState.layoutInfo.visibleItemsInfo.any {
                it.key == liveRowKey(state.conversationId, runId)
            }
    }
    LaunchedEffect(transcriptPosition) {
        // **位掩码而不是 listOf**（性能静态审查②）：原来每次快照读取都新建一个 List，
        // 而这几个状态在拖动/滚动/弹性过卷回弹时变化，打包成一个 Int 即可，零分配、语义不变。
        snapshotFlow {
            (if (scroll.stickToBottom) 1 else 0) or
                (if (scroll.restoring) 2 else 0) or
                (if (pressed) 4 else 0) or
                (if (pressGuardOn) 8 else 0) or
                (if (listState.isScrollInProgress) 16 else 0) or
                (if (overscrollActiveNow()) 32 else 0)
        }.collect { transcriptPosition.interactionChanged() }
    }


    transcriptPosition
}

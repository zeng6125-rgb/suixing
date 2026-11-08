package com.suixing.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/** Existing floating controls and context readout; idle access to child conversations stays in overflow. */
@Composable
internal fun ChatScreenHeader(
    state: ChatUiState,
    actions: ChatScreenActions,
    contextReadout: ChatContextReadout,
    subagentUi: SubagentUiState,
    canvasColor: Color,
    onOpenBrowser: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val contextUsed = contextReadout.contextUsed
    val reading = contextReadout.reading
    val contextWindow = contextReadout.contextWindow
    val sessionTokens = contextReadout.sessionTokens
    val sessionEstimate = contextReadout.sessionEstimate
    val sessionSplit = contextReadout.sessionSplit
    val sessionTurns = contextReadout.sessionTurns
    val session = contextReadout.session
    val sessionElapsedMs = contextReadout.sessionElapsedMs
    val activeModel = contextReadout.activeModel
    // Content scrolling behind the floating header fades out instead of being
    // cut by it. It starts at y=0 — behind the status strip too, or the first
    // line still hard-cuts under the clock — and runs statusBar + 96dp, the
    // taller band the ChatGPT header wears. Three stops (full, half, gone)
    // soften the edge the way a two-stop ramp cannot.
    val statusBarInset = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarInset + 96.dp)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to canvasColor,
                        0.55f to canvasColor.copy(alpha = 0.55f),
                        1f to canvasColor.copy(alpha = 0f),
                    ),
                ),
            ),
    )
    // 顶部浮层提示条已移除（用户：「有红色回执卡的提示」就够）：失败原因一律落在转录里
    // 那张红色回执卡上（RunReasonLine / FailureReceiptRow），不再浮一条会自己消失的横幅。
    // The floating header: two discs and the title capsule, no bar of its own.
    ChatTopBar(
        title = state.title,
        onNew = { actions.newConversation() },
        onOpenBrowser = onOpenBrowser,
        onOpenTerminal = onOpenTerminal,
        onViewSubagents = if (state.runningHere) null else { { subagentUi.show() } },
        contextEntry = {
            TopBarContextEntry(
                used = contextUsed,
                estimated = reading?.estimated == true,
                window = contextWindow,
                sessionTokens = sessionTokens,
                sessionEstimate = sessionEstimate,
                sessionSplit = sessionSplit,
                sessionTurns = sessionTurns,
                sessionPerSecond = session?.perSecond ?: 0.0,
                sessionCachePercent = session?.cachePercent ?: 0.0,
                sessionElapsedMs = sessionElapsedMs,
                statsFlow = actions.liveStats,
                model = activeModel,
            )
        },
    )

}

package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.agent.ChildRunSummary
import com.suixing.agent.agent.SubagentRecords
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Stable
internal class SubagentUiState {
    var open by mutableStateOf(false)
    var selectedId by mutableStateOf<String?>(null)
    fun show(id: String? = null) { selectedId = id; open = true }
}

internal fun subagentQueueFor(tasks: List<ChildRunSummary>, runId: String, running: Boolean): List<ChildRunSummary> =
    if (!running || runId.isBlank()) emptyList() else tasks.filter { it.parentRunId == runId }

/** Same 36dp capsule language as the existing queue above the input field. */
@Composable
internal fun SubagentInlineQueue(chatId: String, runId: String, running: Boolean, onSelect: (String) -> Unit) {
    val queue by remember(chatId, runId, running) {
        SubagentRecords.summaries(chatId).map { subagentQueueFor(it, runId, running) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (queue.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = STEER_QUEUE_INSET)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(queue, key = { it.id }, contentType = { "subagent-queue" }) { task ->
                Surface(onClick = { onSelect(task.id) }, shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(36.dp).widthIn(max = 220.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.name, modifier = Modifier.widthIn(max = 136.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subagentStatusLabel(task.status), style = MaterialTheme.typography.labelSmall,
                            color = if (task.status.isActive()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
internal fun SubagentDialogHost(chatId: String, state: SubagentUiState) {
    val readingState = rememberSaveableStateHolder()
    LaunchedEffect(chatId) { SubagentRecords.load(chatId) }
    if (!state.open) return
    val tasks by remember(chatId) { SubagentRecords.summaries(chatId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    SubagentRunViewer(chatId, tasks, state.selectedId, { state.selectedId = it }, readingState) { state.open = false }
}

package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.agent.SubagentRecords
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.navBarContentPadding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SubagentListScreen(chatId: String, onBack: () -> Unit, onOpenChild: (String) -> Unit) {
    LaunchedEffect(chatId) { SubagentRecords.load(chatId) }
    val tasks by remember(chatId) { SubagentRecords.summaries(chatId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val bottomPadding = navBarContentPadding(16.dp).calculateBottomPadding()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { PageTopBar("子智能体", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tasks.isEmpty()) item {
                Text("当前会话还没有子智能体记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(tasks, key = { it.id }, contentType = { "subagent" }) { child ->
                Surface(
                    onClick = { onOpenChild(child.id) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(child.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subagentStatusLabel(child.status), style = MaterialTheme.typography.bodySmall,
                            color = if (child.status.isActive()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(child.config.model, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Each navigation destination opens one persistent child conversation. */
@Composable
internal fun SubagentConversationScreen(
    chatId: String,
    id: String,
    readingState: SaveableStateHolder,
    onBack: () -> Unit,
) {
    LaunchedEffect(chatId) { SubagentRecords.load(chatId) }
    val heading by remember(id) {
        SubagentRecords.record(id).map { child -> child?.let { it.name to it.status } }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    var details by remember(id) { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            PageTopBar(
                title = heading?.first ?: "子智能体",
                onBack = onBack,
                subtitle = heading?.second?.let { "子智能体 · ${subagentStatusLabel(it)}" },
                actions = { if (heading != null) TextButton(onClick = { details = true }) { Text("详情") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        readingState.SaveableStateProvider("child:$id") {
            SubagentConversation(
                chatId, id, details, onDismissDetails = { details = false },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

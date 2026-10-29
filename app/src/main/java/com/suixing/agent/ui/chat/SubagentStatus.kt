package com.suixing.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.agent.SubagentSnapshot
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.core.Core
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Discrete task events only; neither a per-token subscription nor an elapsed-time ticker. */
@Composable
internal fun SubagentStatusAction(chatId: String) {
    val tasks by remember(chatId) {
        Core.engine.subagentsFlow.map { it[chatId].orEmpty() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (tasks.isEmpty()) return
    var open by remember(chatId) { mutableStateOf(false) }
    val completed = tasks.count { it.status == SubagentStatus.COMPLETED }
    // Stay in the existing controls row: task events must not change the composer/list height.
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        BadgedBox(badge = { Badge { Text(tasks.size.toString()) } }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_list_checks), "子任务：$completed/${tasks.size} 已完成",
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (open) SuixingDialog(
        onDismissRequest = { open = false }, title = { Text("子任务 · $completed/${tasks.size} 已完成") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }, contentType = { "subagent-state" }) { task ->
                    SubagentRow(task, cancel = { Core.engine.cancelSubagent(chatId, task.id) })
                }
            }
        },
        confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen { open = false } }) { Text("关闭") } },
    )
}

@Composable
private fun SubagentRow(task: SubagentSnapshot, cancel: () -> Unit) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val active = task.status == SubagentStatus.RUNNING || task.status == SubagentStatus.QUEUED
    val label = when (task.status) {
        SubagentStatus.QUEUED -> "排队中"
        SubagentStatus.RUNNING -> "执行中"
        SubagentStatus.COMPLETED -> "已完成"
        SubagentStatus.FAILED -> "失败"
        SubagentStatus.CANCELLED -> "已取消"
    }
    Column(Modifier.fillMaxWidth().clickable(enabled = task.result.isNotBlank()) { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(task.name, style = SettingsStyle.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, style = SettingsStyle.description,
                    color = if (task.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) IconButton(onClick = cancel) {
                Icon(ImageVector.vectorResource(R.drawable.ic_x), "取消此子任务", Modifier.size(18.dp))
            }
        }
        if (expanded) Text(task.result.take(2000), style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 16, overflow = TextOverflow.Ellipsis)
    }
}

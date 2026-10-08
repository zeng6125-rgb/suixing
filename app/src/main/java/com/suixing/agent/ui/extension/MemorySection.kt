package com.suixing.agent.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.ui.components.SuixingCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A memory row being typed in the editor. [isNew] locks the name field. */
data class MemoryDraft(val key: String, val value: String, val isNew: Boolean)

/**
 * The agent's own notes.
 *
 * The model writes these through `memory_write`, and the whole set is prepended to
 * every request — so this screen is not a viewer, it is the prompt. Editing or
 * deleting here changes what the model knows on the next turn.
 */
@Composable
fun MemorySection(
    refresh: Int,
    newRequest: Int,
    onNotify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by Core.memory.entries.collectAsState()
    var editing by remember { mutableStateOf<MemoryDraft?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) { Core.memory.load() }
    LaunchedEffect(newRequest) {
        if (newRequest > 0) editing = MemoryDraft("", "", isNew = true)
    }

    val rows = remember(entries) { entries.entries.sortedByDescending { it.value.updatedAt } }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        text = "模型自己写下的长期备忘，每次对话都会带上。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "它用 memory_write 记东西，记满 40 条；这里改动会立刻影响它下次回答。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text("还没有任何备忘", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "让它「记住我用的是联通宽带」之类的话，这里就会出现一条；" +
                                "右上角的 + 也可以自己加。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(rows, key = { it.key }) { row ->
                SuixingCard(onClick = { editing = MemoryDraft(row.key, row.value.value, isNew = false) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.key,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { deleting = row.key },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "删除 ${row.key}",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = row.value.value,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.value.updatedAt > 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "更新于 ${formatMemoryTime(row.value.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        MemoryEditorDialog(
            draft = draft,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch {
                    val ok = Core.memory.put(saved.key, saved.value)
                    onNotify(if (ok) "已保存「${saved.key}」" else "保存失败")
                }
            },
        )
    }

    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条备忘") },
            text = { Text("「$key」会从模型下次看到的记忆里消失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            val ok = Core.memory.remove(key)
                            onNotify(if (ok) "已删除「$key」" else "删除失败")
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    draft: MemoryDraft,
    onDismiss: () -> Unit,
    onSave: (MemoryDraft) -> Unit,
) {
    var key by remember { mutableStateOf(draft.key) }
    var value by remember { mutableStateOf(draft.value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isNew) "新增记忆" else "修改记忆") },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = draft.isNew,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank(),
                onClick = { onSave(draft.copy(key = key.trim(), value = value)) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val MEMORY_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatMemoryTime(timestamp: Long): String =
    MEMORY_TIME_FORMAT.format(Date(timestamp))

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.tasks

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TODO_PENDING
import com.suixing.agent.data.TodoItem
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The agent's task board: what was promised, what is moving, what landed.
 *
 * The board is the model's own list, so editing here is deliberately blunt — a tap walks
 * a line through the three states, a long press removes it. Both write the whole list
 * back, exactly as [com.suixing.agent.data.TodoStore] expects.
 */
@Composable
fun TasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // 长按不再直接删：先记下目标，确认弹窗点「删除」才真的写回。
    var pendingDelete by remember { mutableStateOf<TodoItem?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { Core.todos.read() }
        loaded = true
    }
    val save: (List<TodoItem>) -> Unit = { next ->
        val previous = items
        items = next
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Core.todos.write(next) } }
                .onFailure {
                    // 回滚 + 说出来：以前界面已经改成新样子、磁盘没改，用户以为存上了。
                    items = previous
                    Toast.makeText(context, "保存失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                }
        }
    }
    // 分组只算一次：todoGroup 是 LazyListScope 扩展，它的内容 lambda 不是 @Composable，
    // 放不进 remember —— 而它以前每次重组都对全表 filter 三遍。
    val grouped = remember(items) { items.groupBy { it.status } }
    val onToggle: (TodoItem) -> Unit = { row ->
        // A tap walks the line on: 待办 → 进行中 → 已完成 → 待办.
        val next = when (row.status) {
            TODO_PENDING -> TODO_IN_PROGRESS
            TODO_IN_PROGRESS -> TODO_COMPLETED
            else -> TODO_PENDING
        }
        save(items.map { if (it.id == row.id) it.copy(status = next) else it })
    }
    val onAskDelete: (TodoItem) -> Unit = { pendingDelete = it }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "任务", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loaded && items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "任务板是空的。在对话里让模型做多步任务，它会把步骤记到这里；" +
                            "出现之后点一下切换状态，长按删除。",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            todoGroup("进行中", TODO_IN_PROGRESS, scheme.primary, grouped[TODO_IN_PROGRESS].orEmpty(), onToggle, onAskDelete)
            todoGroup("待办", TODO_PENDING, scheme.onSurfaceVariant, grouped[TODO_PENDING].orEmpty(), onToggle, onAskDelete)
            todoGroup("已完成", TODO_COMPLETED, scheme.tertiary, grouped[TODO_COMPLETED].orEmpty(), onToggle, onAskDelete)
        }
    }

    // 长按删除要过一道确认：整表覆盖写回，误触就永久丢一条。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("确定要删除「${target.subject}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    save(items.filterNot { it.id == target.id })
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** One status section: a header with its count, then that status' lines. */
private fun LazyListScope.todoGroup(
    title: String,
    status: String,
    dot: Color,
    rows: List<TodoItem>,
    onToggle: (TodoItem) -> Unit,
    onDelete: (TodoItem) -> Unit,
) {
    item(key = "head-$status") {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dot, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${rows.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    // key 里必须带位置：id 由模型自报（TodoTools.kt:79），而 TodoStore 只补空 id、不去重
    // （TodoStore.kt:61 `item.id.ifBlank { "t${index + 1}" }`）——同一个 id 出现两次时
    // LazyColumn 会抛 `Key "t1" was already used`，打开任务页即崩。
    itemsIndexed(rows, key = { index, row -> "$status#$index#${row.id}" }) { _, row ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onToggle(row) },
                        // 长按只发请求，真正写回要等确认弹窗点「删除」。
                        onLongClick = { onDelete(row) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dot, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status == TODO_COMPLETED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

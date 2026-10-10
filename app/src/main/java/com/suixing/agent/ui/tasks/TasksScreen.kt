@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.tasks

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { Core.todos.read() }
        loaded = true
    }
    val save: (List<TodoItem>) -> Unit = { next ->
        items = next
        scope.launch { withContext(Dispatchers.IO) { Core.todos.write(next) } }
    }
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
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
            todoGroup("进行中", TODO_IN_PROGRESS, scheme.primary, items, save)
            todoGroup("待办", TODO_PENDING, scheme.onSurfaceVariant, items, save)
            todoGroup("已完成", TODO_COMPLETED, scheme.tertiary, items, save)
        }
    }
}

/** One status section: a header with its count, then that status' lines. */
private fun LazyListScope.todoGroup(
    title: String,
    status: String,
    dot: Color,
    board: List<TodoItem>,
    save: (List<TodoItem>) -> Unit,
) {
    val rows = board.filter { it.status == status }
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
    items(rows, key = { it.id }) { row ->
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
                        // A tap walks the line on: 待办 → 进行中 → 已完成 → 待办.
                        onClick = {
                            val next = when (row.status) {
                                TODO_PENDING -> TODO_IN_PROGRESS
                                TODO_IN_PROGRESS -> TODO_COMPLETED
                                else -> TODO_PENDING
                            }
                            save(board.map { if (it.id == row.id) it.copy(status = next) else it })
                        },
                        onLongClick = { save(board.filterNot { it.id == row.id }) },
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

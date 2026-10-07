package com.lingshu.agent.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import com.lingshu.agent.skills.Skill
import com.lingshu.agent.ui.components.LingshuCard
import com.lingshu.agent.ui.components.SoftIcon
import com.lingshu.agent.ui.theme.MonoStyle

/**
 * Skills are instruction documents. Only their names and one-line descriptions
 * reach the model up front; the body is pulled in with the `skill_read` tool, so
 * a big library stays cheap.
 */
@Composable
fun SkillsSection(
    refresh: Int,
    onView: (Skill) -> Unit,
    onDelete: (Skill) -> Unit,
) {
    var skills by remember { mutableStateOf<List<Skill>?>(null) }
    val rootPath = remember { Core.skills.root.absolutePath }

    LaunchedEffect(refresh) {
        skills = Core.skills.list()
    }

    val loaded = skills
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SkillIntroCard(rootPath) }

        when {
            loaded == null -> item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            loaded.isEmpty() -> item {
                Text(
                    text = "还没有技能。把写好的 SKILL.md 文件夹拷到上面的目录，" +
                        "或点右上角「导入技能」从文件管理器选一个文件夹。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> items(loaded, key = { it.path }) { skill ->
                SkillCard(skill = skill, onView = { onView(skill) }, onDelete = { onDelete(skill) })
            }
        }
    }
}

@Composable
private fun SkillIntroCard(rootPath: String) {
    LingshuCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(Icons.Outlined.Info, size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text("什么是技能", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "技能是写给模型看的操作手册：一段固定的流程、一套规范、某个 App 的用法。" +
                "模型平时只看到技能的名字和用途，需要时才用 skill_read 读全文，所以装很多也不占上下文。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = "每个技能是一个文件夹，里面放 SKILL.md：\n" +
                    "---\n" +
                    "name: 每日签到\n" +
                    "description: 打开某 App 完成签到\n" +
                    "---\n" +
                    "1. 用 open_app 打开…\n\n" +
                    "也可以直接放一个单独的 .md 文件。",
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = rootPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillCard(skill: Skill, onView: () -> Unit, onDelete: () -> Unit) {
    LingshuCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(Icons.AutoMirrored.Outlined.MenuBook, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = skill.description.ifBlank { "id: ${skill.id}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = skill.path,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onView) { Text("查看全文") }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${skill.body.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SkillViewDialog(skill: Skill, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    text = skill.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

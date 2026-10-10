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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillSource
import com.suixing.agent.skills.groupSkills
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onToggle: (Skill, Boolean) -> Unit,
) {
    var skills by remember { mutableStateOf<List<Skill>?>(null) }
    val rootPath = remember { Core.skills.root.absolutePath }

    LaunchedEffect(refresh) {
        skills = Core.skills.list()
    }

    val loaded = skills
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SkillIntroCard(rootPath) }

        when {
            loaded == null -> item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            loaded.isEmpty() -> item {
                Text(
                    text = "还没有技能。可以在右上角「导入技能」里选一个含 SKILL.md 的文件夹、" +
                        "选一个 .md 文件，或直接填一个 SKILL.md 的链接导入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                // Grouped by where the skill came from. The switch is a badge on the card, not
                // a group of its own: a switched-off download is still a download, and a user
                // who turned something off is looking for it where they left it.
                for (group in groupSkills(loaded)) {
                    item(key = "group-${group.title}") { SkillGroupHeader(group.title, group.skills.size) }
                    items(group.skills, key = { it.path }) { skill ->
                        SkillCard(
                            skill = skill,
                            onView = { onView(skill) },
                            onDelete = { onDelete(skill) },
                            onToggle = { enabled -> onToggle(skill, enabled) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「来源：链接 · 2026-10-11」 — the two facts that make a downloaded file checkable, and the
 * only place they are shown. A skill made on this device has no URL to show.
 */
private fun downloadedLine(skill: Skill): String {
    val when_ = if (skill.sourceAt > 0L) {
        " · " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(skill.sourceAt))
    } else {
        ""
    }
    return "来源：${skill.sourceUrl}$when_"
}

private fun sourceLabel(source: SkillSource): String = when (source) {
    SkillSource.USER -> "我创建的"
    SkillSource.DOWNLOADED -> "网络下载"
    SkillSource.BUILTIN -> "内置"
}

@Composable
private fun SkillIntroCard(rootPath: String) {
    SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_info), size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text("什么是技能", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "技能是写给模型看的操作手册：一段固定的流程、一套规范、某个 App 的用法。" +
                "模型平时只看到技能的名字和用途，需要时才用 skill_read 读全文，所以装很多也不占上下文。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // Why the switch starts off, said where the user first meets it: the catalogue
            // goes into the system prompt, so an unopened file would be instructions the
            // model follows on the user's behalf without the user ever having read them.
            text = "新导入、新下载或模型新建的技能都是关闭的：看过内容、确认没问题，再打开开关，" +
                "它才会进入模型的技能目录。关掉的技能留在本机，只是模型看不到。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SkillCard(
    skill: Skill,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_book_open), tint = MaterialTheme.colorScheme.tertiary)
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
            Spacer(Modifier.width(4.dp))
            // Off means the skill stays on disk but leaves the prompt catalogue and
            // becomes unreadable to the model — the cheap way to park a skill.
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_trash),
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(
                text = sourceLabel(skill.source),
                tone = if (skill.source == SkillSource.DOWNLOADED) Tone.WARN else Tone.INFO,
            )
            // A skill that is off is not in the system prompt, so it must not look like one
            // that is: the switch alone is easy to miss when scrolling a list of cards.
            if (!skill.enabled) StatusPill(text = "已关闭 · 不生效", tone = Tone.OFF)
        }
        if (skill.source == SkillSource.DOWNLOADED && skill.sourceUrl.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = downloadedLine(skill),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

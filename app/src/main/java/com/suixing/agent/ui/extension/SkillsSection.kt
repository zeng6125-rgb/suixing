package com.suixing.agent.ui.extension

import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.settingsPagePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.suixing.agent.ui.components.SettingsSwitch
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
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** 一份就够：只在组合里读（主线程），每次重组现建等于白花一次 Locale 查询与分配。 */
private val SKILL_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

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
    var error by remember { mutableStateOf<String?>(null) }
    val rootPath = remember { Core.skills.root.absolutePath }

    LaunchedEffect(refresh) {
        // Listing needs metadata only; the selected dialog loads one full document.
        // External-storage failure is shown separately from the initial loading state.
        try {
            skills = Core.skills.listHeaders()
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "读取技能失败：${failure.message ?: failure::class.java.simpleName}"
        }
    }

    val loaded = skills
    val failure = error
    // 分组结果与 `loaded` 一一对应：`LazyListScope` 里不能 remember，所以在这里取一次
    // （原来在列表内容 lambda 里每次重组都跑 2 次 filter + 建 2 个 List）。
    val groups = remember(loaded) { loaded?.let { groupSkills(it) }.orEmpty() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = settingsPagePadding(),
        verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
    ) {
        item { SkillIntroCard(rootPath) }

        when {
            failure != null -> item {
                Text(
                    text = failure,
                    style = SettingsStyle.body,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 首帧不画任何东西：读盘只要几毫秒，而"闪一下"正是这一帧造成的 —— 进度条画一帧再被列表
            // 顶掉，看起来和"先空后满"一样是闪。空态只在**确实读完了且确实是空的**时候画（下一支）。
            loaded == null -> Unit

            loaded.isEmpty() -> item {
                Text(
                    text = "还没有技能。可以在右上角「导入技能」里选一个含 SKILL.md 的文件夹、" +
                        "选一个 .md 文件，或直接填一个 SKILL.md 的链接导入。",
                    style = SettingsStyle.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                // Grouped by where the skill came from. The switch is a badge on the card, not
                // a group of its own: a switched-off download is still a download, and a user
                // who turned something off is looking for it where they left it.
                for (group in groups) {
                    item(key = "group-${group.title}") { SkillGroupHeader(group.title, group.skills.size) }
                    items(
                        group.skills,
                        key = { it.path },
                        contentType = { "skill-row" },
                    ) { skill ->
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
        Text(title, style = SettingsStyle.sectionTitle)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count",
            style = SettingsStyle.badge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「来源：链接 · 2026-10-11」 — the two facts that make a downloaded file checkable, and the
 * only place they are shown. A skill made on this device has no URL to show.
 */
private fun downloadedLine(skill: Skill): String {
    val stamp = if (skill.sourceAt > 0L) {
        " · " + SKILL_DATE_FORMAT.format(Date(skill.sourceAt))
    } else {
        ""
    }
    return "来源：${skill.sourceUrl}$stamp"
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
            Text("什么是技能", style = SettingsStyle.cardTitle)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "技能是写给模型看的操作手册：一段固定的流程、一套规范、某个 App 的用法。" +
                "模型平时只看到技能的名字和用途，需要时才用 skill_read 读全文，所以装很多也不占上下文。",
            style = SettingsStyle.description,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // The rule the user asked for, said where they first meet it: what was written in the
            // conversation works at once, what came in from outside waits for a human to read it.
            // The catalogue goes into the system prompt, so an unopened downloaded file would be
            // instructions the model follows on the user's behalf without the user ever having
            // read them — that is the whole reason the two cases differ.
            text = "自己创建的（在对话里让模型新建的）默认开启，存下来就能用；" +
                "从链接或文件导入的默认关闭，确认内容没问题再打开开关——" +
                "下载来的文字是别人写的，模型会当成指令执行。",
            style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "关掉的技能留在本机，只是不进模型的技能目录、skill_read 也读不到。",
            style = SettingsStyle.description,
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
            style = SettingsStyle.badge,
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
    // 「来源：链接 · 日期」要一次 SimpleDateFormat.format。只跟这两项有关，按它们记住即可：
    // 开关、正文长度之类变化引起的重组不必再格式化一遍。文案与分隔符完全不变。
    val downloaded = remember(skill.sourceUrl, skill.sourceAt) { downloadedLine(skill) }
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_book_open), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = SettingsStyle.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = skill.description.ifBlank { "id: ${skill.id}" },
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            // Off means the skill stays on disk but leaves the prompt catalogue and
            // becomes unreadable to the model — the cheap way to park a skill.
            SettingsSwitch(checked = skill.enabled, onCheckedChange = onToggle)
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
                text = downloaded,
                style = SettingsStyle.badge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = skill.path,
            style = SettingsStyle.badge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onView) { Text("查看全文") }
            Spacer(Modifier.weight(1f))
            if (skill.body.isNotEmpty()) {
                Text(
                    text = "${skill.body.length} 字",
                    style = SettingsStyle.badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SkillViewDialog(skill: Skill, onDismiss: () -> Unit) {
    var loaded by remember(skill.id, skill.body) {
        mutableStateOf(skill.takeIf { it.body.isNotEmpty() })
    }
    var failure by remember(skill.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(skill.id, skill.body) {
        if (loaded != null) return@LaunchedEffect
        try {
            loaded = Core.skills.load(skill.id)
            if (loaded == null) failure = "技能已不存在，请刷新列表。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = "读取技能失败：${error.message ?: error::class.java.simpleName}"
        }
    }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = SettingsStyle.badge,
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
                    text = loaded?.body ?: failure ?: "正在读取…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = SettingsStyle.body,
                )
            }
        },
        confirmButton = { scope ->
            TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("关闭") }
        },
    )
}

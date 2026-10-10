package com.suixing.agent.ui.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R
import com.suixing.agent.data.ASSISTANT_AVATARS
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.AssistantAvatarStore
import com.suixing.agent.data.DEFAULT_SYSTEM_PROMPT
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SettingRow
import kotlinx.coroutines.launch

/**
 * Create or edit one persona.
 *
 * A full-screen dialog rather than a route, so the chat screen never loses its
 * scroll position while the user fiddles with a prompt.
 */
@Composable
fun AssistantEditor(
    initial: Assistant,
    settings: AppSettings,
    toolNames: List<String>,
    onSave: (Assistant) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val isNew = settings.assistant(initial.id) == null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Set when picking a picture fails; cleared on the next successful pick. */
    var notice by remember { mutableStateOf<String?>(null) }
    // 删除是不可撤销的：它会把助手连同提示词和头像 PNG 一起从设置里抹掉，所以先问一次。
    var confirmingDelete by remember { mutableStateOf(false) }
    // The picture is written the moment it is picked, not on save: the pick arrives as a
    // content:// uri that is only readable while the picker's grant lasts, and the draft holds
    // the path to the file we made out of it. A pick that fails leaves the old picture alone.
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val previous = draft.avatarPath
            scope.launch {
                val path = AssistantAvatarStore.save(context, draft.id, uri, previous)
                if (path != null) {
                    notice = null
                    draft = draft.copy(avatarPath = path)
                } else {
                    // A pick can fail (huge picture, revoked grant, file already gone). The old
                    // picture is left alone, so without this line the tap would look ignored.
                    notice = "这张图没能保存下来，头像保持原样。可以换一张，或直接用下面的表情。"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    // The shell's drawer handle rides on SuixingTopBar and the composition
                    // local follows the composition into this dialog's window — but the
                    // drawer itself is painted behind the dialog, so the handle was a dead
                    // tap. A dialog is a place with no sidebar: a null local says exactly
                    // that.
                    CompositionLocalProvider(LocalOpenSidebar provides null) {
                        SuixingTopBar(
                            title = if (isNew) "新建助手" else "编辑助手",
                            subtitle = draft.label,
                            leading = {
                                AssistantAvatar(
                                    face = draft.face,
                                    avatarPath = draft.avatarPath,
                                    size = 38.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                )
                            },
                            actions = {
                                TextButton(onClick = { onSave(draft) }) { Text("保存") }
                            },
                        )
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IdentitySection(
                        draft = draft,
                        onDraft = { draft = it },
                        onPickPicture = {
                            pickPicture.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onClearPicture = {
                            // Explicitly "remove my picture", so the file goes with the path:
                            // leaving it would keep a photo the user asked to be rid of, and the
                            // id sweep on delete is not what this is.
                            val previous = draft.avatarPath
                            draft = draft.copy(avatarPath = "")
                            scope.launch { AssistantAvatarStore.delete(context, previous) }
                        },
                        onPickEmoji = { emoji ->
                            // An emoji tap means "this is my face": it takes the picture's place
                            // instead of being hidden behind it, which is what a picture that
                            // wins over the grid would have looked like.
                            val previous = draft.avatarPath
                            draft = draft.copy(avatar = emoji, avatarPath = "")
                            if (previous.isNotBlank()) {
                                scope.launch { AssistantAvatarStore.delete(context, previous) }
                            }
                        },
                    )
                    PromptSection(draft = draft, onDraft = { draft = it })
                    ModelSection(draft = draft, settings = settings, onDraft = { draft = it })
                    ToolSection(draft = draft, toolNames = toolNames, onDraft = { draft = it })
                    notice?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = { confirmingDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("删除这个助手", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        // The delete entry is simply absent when there is one assistant left;
                        // saying why is the difference between a rule and a missing button.
                        Text(
                            text = "至少要留一个助手，所以这个删不掉：先新建一个，再回来删它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "留空的项会跟随「设置」里的当前值，所以只改名字也能用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 名字用 label：空名字的助手在卡片上显示的就是它，弹窗里报一个空引号等于没说是谁。
    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这个助手") },
            text = { Text("确定要删除「${draft.label}」吗？它的提示词和头像会一起删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentitySection(
    draft: Assistant,
    onDraft: (Assistant) -> Unit,
    onPickPicture: () -> Unit,
    onClearPicture: () -> Unit,
    onPickEmoji: (String) -> Unit,
) {
    SectionCard(title = "身份", icon = ImageVector.vectorResource(R.drawable.ic_id_card), subtitle = "名字与头像") {
        // The picture first, and apart from the emoji grid: the two are the same choice, and a
        // photo is what most users reach for. The emoji below stays the fallback for anyone who
        // does not want to hand over a picture.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistantAvatar(
                face = draft.face,
                avatarPath = draft.avatarPath,
                size = 64.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPickPicture) {
                    Text(if (draft.avatarPath.isBlank()) "上传图片" else "换一张")
                }
                if (draft.avatarPath.isNotBlank()) {
                    TextButton(onClick = onClearPicture) { Text("恢复默认图标") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ASSISTANT_AVATARS.forEach { emoji ->
                val selected = draft.avatarPath.isBlank() && draft.avatar == emoji
                Surface(
                    onClick = { onPickEmoji(emoji) },
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        // 19sp 不写死：按 titleLarge 的比例给，用户调「外观→字号」时这个
                        // emoji 跟着缩放（写死 sp 会把缩放静默关掉）。
                        val face = MaterialTheme.typography.titleLarge
                        Text(emoji, style = face.copy(fontSize = face.fontSize * (19f / 22f)))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("名字") },
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onDraft(draft.copy(description = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("一句话说明") },
            placeholder = { Text("例如：只读代码、不改文件") },
            singleLine = true,
        )
    }
}

@Composable
private fun PromptSection(draft: Assistant, onDraft: (Assistant) -> Unit) {
    SectionCard(
        title = "系统提示词",
        icon = ImageVector.vectorResource(R.drawable.ic_sliders_horizontal),
        subtitle = "这个助手是谁、该怎么做事",
        trailing = {
            TextButton(
                onClick = { onDraft(draft.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT)) },
            ) {
                Text("用默认")
            }
        },
    ) {
        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { onDraft(draft.copy(systemPrompt = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            placeholder = { Text("留空则沿用「设置」里的系统提示词") },
        )
    }
}

@Composable
private fun ModelSection(
    draft: Assistant,
    settings: AppSettings,
    onDraft: (Assistant) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val pairs = settings.enabledModels()
    val follow = !draft.pinsModel
    val label = when {
        follow -> "跟随当前（${settings.shortModel.ifBlank { "未设置" }}）"
        else -> draft.model.substringAfter('/')
    }

    SectionCard(title = "模型", icon = ImageVector.vectorResource(R.drawable.ic_cpu), subtitle = "可以让这个助手用另一个模型") {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("跟随当前模型") },
                    onClick = {
                        onDraft(draft.copy(providerId = "", model = ""))
                        open = false
                    },
                )
                pairs.forEach { (provider, modelId) ->
                    DropdownMenuItem(
                        text = { Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onDraft(draft.copy(providerId = provider.id, model = modelId))
                            open = false
                        },
                    )
                }
                if (pairs.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("还没启用模型，去「设置」里拉取") },
                        onClick = { open = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SettingRow(
            title = "单独设置温度",
            subtitle = "关闭则跟随全局温度",
            control = {
                Switch(
                    checked = draft.temperature != null,
                    onCheckedChange = { on ->
                        onDraft(draft.copy(temperature = if (on) 0.6 else null))
                    },
                )
            },
        )
        draft.temperature?.let { value ->
            Slider(
                value = value.toFloat(),
                onValueChange = { onDraft(draft.copy(temperature = it.toDouble())) },
                valueRange = 0f..2f,
                steps = 19,
            )
            Text(
                text = "温度 ${"%.1f".format(value)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSection(
    draft: Assistant,
    toolNames: List<String>,
    onDraft: (Assistant) -> Unit,
) {
    val off = draft.disabledTools.toSet()
    SectionCard(
        title = "工具",
        icon = ImageVector.vectorResource(R.drawable.ic_wrench),
        subtitle = "关掉的工具这个助手不能调用",
        trailing = {
            TextButton(onClick = { onDraft(draft.copy(disabledTools = emptyList())) }) {
                Text("全开")
            }
        },
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            toolNames.forEach { name ->
                val enabled = name !in off
                FilterChip(
                    selected = enabled,
                    onClick = {
                        onDraft(
                            draft.copy(
                                disabledTools = if (enabled) {
                                    draft.disabledTools + name
                                } else {
                                    draft.disabledTools - name
                                },
                            ),
                        )
                    },
                    label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        if (toolNames.isEmpty()) {
            Text(
                text = "工具还在加载…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

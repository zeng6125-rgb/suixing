package com.suixing.agent.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import java.util.UUID

/**
 * The persona picker as a half-screen sheet.
 *
 * The sheet is the window and nothing else: the header, the cards and the editor all come
 * from [AssistantListContent], which the full page
 * ([com.suixing.agent.ui.settings.AssistantScreen]) draws too. Cards rather than a list,
 * because the whole point of an assistant is the face and the name — a row of text would
 * make them all look alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(
    assistants: List<Assistant>,
    /** The persona the conversation on screen speaks as — not the app-wide default. */
    activeId: String,
    settings: AppSettings,
    onUse: (Assistant) -> Unit,
    onSetDefault: (Assistant) -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AssistantListContent(
            assistants = assistants,
            activeId = activeId,
            settings = settings,
            onUse = onUse,
            onSetDefault = onSetDefault,
            onSave = onSave,
            onDelete = onDelete,
            // 选一张卡就是选完了：表要收起来，否则用户看着还开着的半屏列表不知道有没有生效。
            onPicked = onDismiss,
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
                // 表里没有可滚动的父容器，高度只能在这里封顶（表头 + 列表 ≈ 540dp），
                // 列表自己的 verticalScroll 才拿得到有界的高度。
                .heightIn(max = 540.dp),
        )
    }
}

/**
 * The persona grid, its 新建 button and the editor — one implementation, two hosts.
 *
 * A page and the half-screen sheet differ in exactly three ways, and those are the three
 * parameters: the page's own top bar already says 助手 ([title] is null there), a page has
 * the whole window to fill ([modifier] carries its `weight`), and picking a persona closes
 * the sheet but must not close the page ([onPicked]). Everything else — the grid, the
 * 「当前 / 默认 / 设为默认」 semantics and the editor — is shared, so there is no second
 * copy of the picker to keep in step.
 */
@Composable
fun AssistantListContent(
    assistants: List<Assistant>,
    activeId: String,
    settings: AppSettings,
    onUse: (Assistant) -> Unit,
    onSetDefault: (Assistant) -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    /** What picking a persona means to the host: the sheet closes, the page stays. */
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null when the host's own bar already carries the title. */
    title: String? = "助手",
) {
    // Kept as an id rather than the object so a rotation no longer closes the editor: an
    // existing persona is looked up in settings again, and the one id that cannot be found
    // is the freshly created draft, which is rebuilt from its id and the same defaults.
    // The in-progress edits still live in the editor's own draft.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id ->
        assistants.firstOrNull { it.id == id } ?: Assistant(id = id, avatar = "🧭")
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "每个助手有自己的名字、提示词、模型和工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = { editingId = UUID.randomUUID().toString() },
            ) {
                Icon(ImageVector.vectorResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建")
            }
        }
        Spacer(Modifier.height(16.dp))
        // weight(fill = false)：宿主给的高度有界时列表占满剩余空间并自己滚动；列表短时
        // 仍按内容收拢，半屏的表不会因此被撑成整屏。这里的 460dp 上限换成了宿主的封顶
        // （表 540dp / 整页整屏），两种宿主都拿得到有界高度。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            assistants.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { assistant ->
                        AssistantCard(
                            assistant = assistant,
                            active = assistant.id == activeId,
                            isDefault = assistant.id == settings.activeAssistantId,
                            modelLabel = modelLabel(settings, assistant),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUse(assistant)
                                onPicked()
                            },
                            onEdit = { editingId = assistant.id },
                            onSetDefault = { onSetDefault(assistant) },
                        )
                    }
                    // An odd count leaves the last row half empty; a spacer keeps
                    // the single card at half width instead of stretching it.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    editing?.let { target ->
        AssistantEditor(
            initial = target,
            settings = settings,
            toolNames = Core.tools.names(),
            onSave = {
                onSave(it)
                editingId = null
            },
            onDelete = if (assistants.size > 1) {
                {
                    onDelete(target)
                    editingId = null
                }
            } else {
                null
            },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun AssistantCard(
    assistant: Assistant,
    active: Boolean,
    isDefault: Boolean,
    modelLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (active) {
            scheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            scheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (active) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(start = 13.dp, end = 6.dp, top = 12.dp, bottom = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(assistant.face, fontSize = 20.sp)
                }
                Spacer(Modifier.weight(1f))
                // One slot for "this is the app-wide default" and "make it one": the star is
                // the action, and once taken it greys out rather than disappearing, so cards
                // keep the same geometry in both states. The state itself is spelled out on
                // the model line, where there is room for words.
                IconButton(
                    onClick = onSetDefault,
                    enabled = !isDefault,
                    modifier = Modifier.minimumInteractiveComponentSize().size(30.dp),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_star),
                        contentDescription = if (isDefault) "默认" else "设为默认",
                        // Spelled out because an explicit tint outlives the disabled colour
                        // IconButton would otherwise apply on its own.
                        tint = if (isDefault) {
                            scheme.onSurfaceVariant.copy(alpha = 0.38f)
                        } else {
                            scheme.primary
                        },
                        modifier = Modifier.size(15.dp),
                    )
                }
                // The 30dp disc stays the visual; the 48dp minimum sits outside it so the
                // card's spacing does not change and the ripple stays on the disc.
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.minimumInteractiveComponentSize().size(30.dp),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_square_pen),
                        contentDescription = "编辑",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = assistant.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_circle_check),
                        contentDescription = "当前",
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The app-wide default is a weaker fact than "this chat speaks as it", so it
                // is a word in secondary ink on the quiet line, not a second badge.
                if (isDefault) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (assistant.description.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = assistant.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The model a card advertises: its own pin, or whatever the app is on now. */
private fun modelLabel(settings: AppSettings, assistant: Assistant): String =
    if (assistant.pinsModel) {
        assistant.model.substringAfter('/')
    } else {
        settings.shortModel.ifBlank { "跟随当前模型" }
    }

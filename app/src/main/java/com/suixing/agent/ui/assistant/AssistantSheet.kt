package com.suixing.agent.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    /** 点「编辑/新建」：宿主自己决定编辑器画在哪（主窗口覆盖层），表先收起来。 */
    onOpenEditor: (String) -> Unit,
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
            onOpenEditor = onOpenEditor,
            // 选一张卡就是选完了：表要收起来，否则用户看着还开着的半屏列表不知道有没有生效。
            onPicked = onDismiss,
            modifier = Modifier
                .padding(horizontal = 16.dp)
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
    /** 点「编辑」或「新建」：编辑器画在哪由宿主决定（主窗口全屏覆盖层），这里只报 id。 */
    onOpenEditor: (String) -> Unit,
    /** What picking a persona means to the host: the sheet closes, the page stays. */
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null when the host's own bar already carries the title. */
    title: String? = "助手",
) {
    // 两两一行是布局用的中间结果，不是状态：不 remember 就每次重组都重建一层嵌套 List。
    val rows = remember(assistants) { assistants.chunked(2) }

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
                onClick = { onOpenEditor(UUID.randomUUID().toString()) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            onEdit = { onOpenEditor(assistant.id) },
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
                // The persona's picture, its emoji, or the brand feather — one composable for
                // all three, so this card cannot disagree with the chat bubble about the face.
                AssistantAvatar(
                    face = assistant.face,
                    avatarPath = assistant.avatarPath,
                    size = 38.dp,
                    shape = MaterialTheme.shapes.small,
                    containerColor = scheme.surfaceContainerHigh,
                )
                Spacer(Modifier.width(2.dp))
                // One slot for "this is the app-wide default" and "make it one": the star is
                // the action, and once taken it greys out rather than disappearing, so cards
                // keep the same geometry in both states. The state itself is spelled out on
                // the model line, where there is room for words.
                //
                // 紧挨头像（用户两次点名「星号离头像太远」）：视觉间隙只剩 2dp spacer + 盒内
                // 7.5dp。**不给 48dp 触摸下限**——那个下限把 15dp 图标居中在 48dp 盒里，两侧各
                // 多 16.5dp 空气，正是截图里的「远」。行高由编辑按钮的 48dp 下限与 38dp 头像
                // 撑着，收窄这里不动卡片几何。
                IconButton(
                    onClick = onSetDefault,
                    enabled = !isDefault,
                    modifier = Modifier.size(30.dp),
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
                Spacer(Modifier.weight(1f))
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

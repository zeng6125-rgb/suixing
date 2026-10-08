package com.lingshu.agent.ui.assistant

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.data.Assistant
import java.util.UUID

/**
 * The persona picker.
 *
 * Cards rather than a list, because the whole point of an assistant is the face
 * and the name — a row of text would make them all look alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(
    assistants: List<Assistant>,
    activeId: String,
    settings: AppSettings,
    onUse: (Assistant) -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<Assistant?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("助手", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "每个助手有自己的名字、提示词、模型和工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        editing = Assistant(id = UUID.randomUUID().toString(), avatar = "🧭")
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建")
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                assistants.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { assistant ->
                            AssistantCard(
                                assistant = assistant,
                                active = assistant.id == activeId,
                                modelLabel = modelLabel(settings, assistant),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onUse(assistant)
                                    onDismiss()
                                },
                                onEdit = { editing = assistant },
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

    editing?.let { target ->
        AssistantEditor(
            initial = target,
            settings = settings,
            toolNames = Core.tools.names(),
            onSave = {
                onSave(it)
                editing = null
            },
            onDelete = if (assistants.size > 1) {
                {
                    onDelete(target)
                    editing = null
                }
            } else {
                null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AssistantCard(
    assistant: Assistant,
    active: Boolean,
    modelLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onEdit: () -> Unit,
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
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
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
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "当前",
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

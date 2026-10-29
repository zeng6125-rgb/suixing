package com.suixing.agent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog

/** A real read-only form field; its label, padding and container match editable fields exactly. */
@Composable
private fun ModelSelectionAnchor(label: String, value: String, enabled: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val rotation = animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "modelMenuArrow")
    Box(Modifier.fillMaxWidth()) {
        CompactOutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}.focusProperties { canFocus = false },
            trailingIcon = {
                Icon(ImageVector.vectorResource(R.drawable.ic_chevron_down), null,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = rotation.value })
            },
        )
        // The field paints the native M3 decoration; this button owns focus, touch and ripple.
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "$label：$value"
                stateDescription = if (expanded) "已展开" else "已收起"
            })
    }
}

/** Both connection selectors open the same quiet, anchor-width native menu. */
@Composable
private fun ModelDropdownMenu(
    expanded: Boolean, onDismiss: () -> Unit, anchorWidth: Int,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = (if (anchorWidth > 0) Modifier.width(with(density) { anchorWidth.toDp() }) else Modifier)
            .heightIn(max = 360.dp),
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp, shadowElevation = 8.dp,
        content = content,
    )
}

@Composable
internal fun ProviderDropdown(
    providers: List<ProviderConfig>, selected: ProviderConfig?, enabled: Boolean,
    onSelect: (ProviderConfig) -> Unit, onAdd: () -> Unit, onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val rotation = animateFloatAsState(if (open && enabled) 180f else 0f, tween(160), label = "providerArrow")
    val endpoint = remember(selected?.baseUrl) { providerEndpointLabel(selected?.baseUrl.orEmpty()) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).onSizeChanged { anchorWidth = it.width }) {
        Surface(
            onClick = { if (providers.isEmpty()) onAdd() else open = true }, enabled = enabled,
            shape = MaterialTheme.shapes.small,
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, if (open && enabled) colors.primary else colors.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.38f }.semantics {
                contentDescription = "供应商：${selected?.label ?: "添加供应商"}"
                stateDescription = if (open && enabled) "已展开" else "已收起"
            },
        ) {
            Row(Modifier.heightIn(min = SettingsStyle.ControlHeight).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderIdentity(selected?.label.orEmpty())
                Column(Modifier.weight(1f)) {
                    Text(selected?.label ?: "添加供应商", style = SettingsStyle.body, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selected == null) "连接模型服务或自己的网关" else endpoint,
                        style = SettingsStyle.badge, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(ImageVector.vectorResource(R.drawable.ic_chevron_down), null,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = rotation.value }, tint = colors.onSurfaceVariant)
            }
        }
        ModelDropdownMenu(
            expanded = open && enabled, onDismiss = { open = false }, anchorWidth = anchorWidth,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("切换供应商", style = SettingsStyle.metadata, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(enabled = selected != null && providers.size > 1,
                    onClick = { open = false; onDelete() }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_trash), "删除当前供应商", Modifier.size(16.dp),
                        tint = if (selected != null && providers.size > 1) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f))
                }
            }
            providers.forEach { provider ->
                val chosen = selected?.id == provider.id
                val host = remember(provider.baseUrl) { providerEndpointLabel(provider.baseUrl) }
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(provider.label, style = SettingsStyle.body, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host, style = SettingsStyle.description, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { ProviderIdentity(provider.label) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${provider.enabled.size} 模型", style = SettingsStyle.badge, color = colors.onSurfaceVariant)
                            Box(Modifier.size(18.dp)) {
                                if (chosen) Icon(ImageVector.vectorResource(R.drawable.ic_check), "当前供应商", tint = colors.primary)
                            }
                        }
                    },
                    onClick = { open = false; onSelect(provider) },
                    modifier = Modifier.padding(horizontal = 6.dp).clip(MaterialTheme.shapes.small)
                        .background(if (chosen) colors.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = chosen },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            DropdownMenuItem(
                text = { Text("添加供应商", style = SettingsStyle.controlLabel, color = colors.primary) },
                leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_plus), null, Modifier.size(18.dp), tint = colors.primary) },
                onClick = { open = false; onAdd() },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/** Local text marks keep custom endpoints recognisable without downloading vendor artwork. */
@Composable
internal fun ProviderIdentity(name: String) {
    val mark = remember(name) {
        when (name.lowercase()) {
            "openai" -> "AI"
            "openrouter" -> "OR"
            "deepseek" -> "DS"
            "anthropic" -> "A"
            else -> name.trim().let { if (it.isEmpty()) "+" else String(Character.toChars(it.codePointAt(0))).uppercase() }
        }
    }
    Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        Text(mark, style = SettingsStyle.metadata, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun CompactProtocolPicker(protocol: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val options = remember { listOf(PROTOCOL_OPENAI to "OpenAI 兼容", PROTOCOL_ANTHROPIC to "Anthropic",
        PROTOCOL_RESPONSES to "OpenAI Responses") }
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
        ModelSelectionAnchor("接口协议", options.firstOrNull { it.first == protocol }?.second ?: options.first().second,
            enabled = true, expanded = open) { open = true }
        ModelDropdownMenu(expanded = open, onDismiss = { open = false }, anchorWidth = anchorWidth) {
            options.forEach { (value, label) ->
                val chosen = value == protocol || protocol.isBlank() && value == PROTOCOL_OPENAI
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = SettingsStyle.body,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                            Text(when (value) {
                                PROTOCOL_ANTHROPIC -> "Messages API"
                                PROTOCOL_RESPONSES -> "Responses API"
                                else -> "Chat Completions API"
                            }, style = SettingsStyle.description, color = colors.onSurfaceVariant)
                        }
                    },
                    trailingIcon = {
                        Box(Modifier.size(18.dp)) {
                            if (chosen) Icon(ImageVector.vectorResource(R.drawable.ic_check), "当前协议", tint = colors.primary)
                        }
                    },
                    onClick = { open = false; onPick(value) },
                    modifier = Modifier.padding(horizontal = 6.dp).clip(MaterialTheme.shapes.small)
                        .background(if (chosen) colors.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = chosen },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun FetchedModelsDialog(provider: ProviderConfig, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var query by remember(provider.id) { mutableStateOf("") }
    val groups = remember(provider.models, query) {
        groupModels(provider.models.distinct().filter { query.isBlank() || it.contains(query.trim(), true) })
    }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型 · ${provider.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactOutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
                Text("已启用 ${provider.enabled.size} 个 · 修改后在页面底部保存", style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (groups.isEmpty()) Text(if (provider.models.isEmpty()) "尚无模型，请先获取或添加自定义模型。" else "没有匹配的模型。",
                    style = SettingsStyle.description)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    groups.forEach { (group, ids) ->
                        item(key = "group:$group", contentType = "model-group") {
                            Text(group, style = SettingsStyle.badge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(ids, key = { "model:$it" }, contentType = { "model-choice" }) { id ->
                            Surface(onClick = { onToggle(id, id !in provider.enabled) }, shape = MaterialTheme.shapes.small,
                                color = if (id in provider.enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                                    else androidx.compose.ui.graphics.Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = id in provider.enabled, onCheckedChange = { onToggle(id, it) })
                                    Text(provider.displayModel(id), style = SettingsStyle.body, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("完成") } },
    )
}

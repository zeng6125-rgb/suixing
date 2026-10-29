package com.suixing.agent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.hasProviderDetails
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog

/** Compact native actions: a 40dp visual minimum, with Material's standard touch target. */
@Composable
internal fun ModelActionButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true, busy: Boolean = false, icon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 40.dp), shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (primary) colors.onPrimary else colors.onSurface,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(ImageVector.vectorResource(icon), null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = SettingsStyle.controlLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Supplier creation stays local to this dialog until a complete name/address is confirmed. */
@Composable
internal fun AddProviderDialog(onDismiss: () -> Unit, onAdd: (ProviderConfig) -> Unit) {
    var choosing by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(com.suixing.agent.data.PROTOCOL_OPENAI) }
    val candidate = ProviderConfig(id = "draft", name = name.trim(), baseUrl = address.trim(), apiKey = key, protocol = protocol)
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (choosing) "添加供应商" else "连接配置") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (choosing) 4.dp else 10.dp)) {
                if (choosing) {
                    Text("选择预设，或连接自己的网关", style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (providerTemplates + listOf<ProviderTemplate?>(null)).forEach { template ->
                        Surface(
                            onClick = {
                                name = template?.name.orEmpty()
                                address = template?.baseUrl.orEmpty()
                                key = ""
                                protocol = template?.protocol ?: com.suixing.agent.data.PROTOCOL_OPENAI
                                choosing = false
                            },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProviderIdentity(template?.name.orEmpty())
                                Column(Modifier.weight(1f)) {
                                    Text(template?.name ?: "自定义供应商", style = SettingsStyle.body, fontWeight = FontWeight.Medium)
                                    Text(template?.let { providerEndpointLabel(it.baseUrl) } ?: "局域网服务或兼容网关", style = SettingsStyle.description,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(ImageVector.vectorResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp))
                            }
                        }
                    }
                } else {
                    CompactOutlinedTextField(name, { name = it }, label = { Text("供应商名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    CompactProtocolPicker(protocol, { protocol = it })
                    CompactOutlinedTextField(address, { address = it }, label = { Text("接口地址") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    CompactOutlinedTextField(key, { key = it }, label = { Text("API Key（可选）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text("添加后可获取并启用模型，最后在页面底部保存。", style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { dialog ->
            if (!choosing) ModelActionButton("添加", primary = true, enabled = candidate.hasProviderDetails(), onClick = {
                dialog.closeThen { onAdd(candidate.copy(id = java.util.UUID.randomUUID().toString())) }
            })
        },
        dismissButton = { dialog ->
            Row {
                if (!choosing) TextButton(onClick = { choosing = true }) { Text("上一步") }
                TextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("取消") }
            }
        },
    )
}

/** Enabled entries are already selected; keep their identity and limits in one quiet row. */
@Composable
internal fun EnabledModelCard(
    id: String, alias: String, contextWindow: Int, maxTokens: Int, testModel: Boolean,
    onTest: () -> Unit, onEdit: (String, Int, Int) -> Unit, onRemove: () -> Unit,
) {
    var editing by remember(id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (testModel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onTest).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (testModel) Icon(ImageVector.vectorResource(R.drawable.ic_circle_check), null, Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(alias.ifBlank { id }, style = SettingsStyle.body, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (alias.isNotBlank() && alias != id) Text(id, style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("上下文 ${if (contextWindow > 0) contextWindow.toString() else "不限"} · 输出 ${if (maxTokens > 0) maxTokens.toString() else "默认"}",
                    style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (testModel) Text("测试连接使用此模型", style = SettingsStyle.badge, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { editing = true }) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sliders_horizontal), "编辑模型参数", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (editing) {
        var aliasText by remember(id) { mutableStateOf(alias) }
        var contextText by remember(id) { mutableStateOf(contextWindow.takeIf { it > 0 }?.toString().orEmpty()) }
        var outputText by remember(id) { mutableStateOf(maxTokens.takeIf { it > 0 }?.toString().orEmpty()) }
        val parsedContext = if (contextText.isBlank()) 0 else parseWindowTokens(contextText)
        val parsedOutput = if (outputText.isBlank()) 0 else outputText.toIntOrNull()?.takeIf { it >= 0 }
        SuixingDialog(
            onDismissRequest = { editing = false }, title = { Text("模型参数") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(id, style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CompactOutlinedTextField(aliasText, { aliasText = it }, label = { Text("显示名称（可选）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    CompactOutlinedTextField(contextText, { contextText = it }, label = { Text("上下文窗口") },
                        placeholder = { Text("如 128k，留空不限") }, singleLine = true,
                        isError = parsedContext == null, modifier = Modifier.fillMaxWidth())
                    CompactOutlinedTextField(outputText, { outputText = it }, label = { Text("最大输出 token") },
                        placeholder = { Text("留空使用模型默认值") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = parsedOutput == null, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { editing = false; onRemove() }) {
                        Text("停用此模型", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { dialog ->
                ModelActionButton("确定", primary = true, enabled = parsedContext != null && parsedOutput != null,
                    onClick = { dialog.closeThen { onEdit(aliasText.trim(), parsedContext ?: 0, parsedOutput ?: 0); editing = false } })
            },
            dismissButton = { dialog -> TextButton(onClick = { dialog.closeThen { editing = false } }) { Text("取消") } },
        )
    }
}

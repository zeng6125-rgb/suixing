package com.lingshu.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.data.ApprovalMode
import com.lingshu.agent.llm.ChatMessage
import com.lingshu.agent.llm.LlmClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    var form by remember(stored) { mutableStateOf(stored) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val client = remember { LlmClient() }
    var testing by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("模型接口", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { form = form.copy(baseUrl = it) },
                label = { Text("接口地址") },
                supportingText = { Text("OpenAI 兼容的 /v1 地址，例如 https://api.deepseek.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { form = form.copy(apiKey = it) },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.model,
                onValueChange = { form = form.copy(model = it) },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模型支持图片输入", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "开启后截图会随消息发给模型，需要 gpt-4o / qwen-vl / gemini 这类视觉模型；" +
                            "文字模型开着会直接报错。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.imageInput,
                    onCheckedChange = { form = form.copy(imageInput = it) },
                )
            }

            LabeledSlider(
                label = "温度 ${"%.1f".format(form.temperature)}",
                value = form.temperature.toFloat(),
                range = 0f..2f,
                steps = 0,
                onValueChange = { form = form.copy(temperature = it.toDouble()) },
            )
            LabeledSlider(
                label = "最大工具轮数 ${form.maxToolRounds}",
                value = form.maxToolRounds.toFloat(),
                range = 1f..40f,
                steps = 38,
                onValueChange = { form = form.copy(maxToolRounds = it.toInt()) },
            )

            Text("工具审批", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApprovalMode.entries.forEach { mode ->
                    FilterChip(
                        selected = form.approvalMode == mode,
                        onClick = { form = form.copy(approvalMode = mode) },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
            Text(
                text = when (form.approvalMode) {
                    ApprovalMode.AUTO -> "模型可以连续调用工具，不再打断你。"
                    ApprovalMode.ASK -> "每次调用工具前都会问你一次，可以选「总是允许」。"
                    ApprovalMode.DENY -> "禁止一切工具调用，只能聊天。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (form.approvalMode == ApprovalMode.ASK) {
                OutlinedButton(
                    onClick = {
                        Core.gate.forgetAlways()
                        scope.launch { snackbar.showSnackbar("已清除「总是允许」的记忆") }
                    },
                ) { Text("清除「总是允许」") }
            }

            HorizontalDivider()
            Text("系统提示词", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.systemPrompt,
                onValueChange = { form = form.copy(systemPrompt = it) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (value, label) ->
                    FilterChip(
                        selected = form.themeMode == value,
                        onClick = { form = form.copy(themeMode = value) },
                        label = { Text(label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("动态取色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "安卓 12 以上跟随壁纸配色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.dynamicColor,
                    onCheckedChange = { form = form.copy(dynamicColor = it) },
                )
            }

            HorizontalDivider()
            Text("高级", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许 root 工具", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "开启后 run_shell 可以走 su 执行命令，风险自负",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.enableRoot,
                    onCheckedChange = { form = form.copy(enableRoot = it) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            Core.settings.save(form)
                            snackbar.showSnackbar("已保存")
                        }
                    },
                ) { Text("保存") }
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            Core.settings.save(form)
                            val message = runCatching {
                                client.complete(
                                    settings = form,
                                    messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")),
                                    tools = emptyList(),
                                )
                            }.fold(
                                onSuccess = { "连接成功：${it.text.trim().take(40)}" },
                                onFailure = { "连接失败：${it.message ?: it::class.java.simpleName}" },
                            )
                            testing = false
                            snackbar.showSnackbar(message)
                        }
                    },
                ) { Text(if (testing) "测试中…" else "测试连接") }
            }

            Text(
                text = "API Key 只保存在本机应用私有目录，不会上传到任何第三方服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun modeLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.AUTO -> "自动允许"
    ApprovalMode.ASK -> "每次询问"
    ApprovalMode.DENY -> "禁止"
}

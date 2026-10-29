package com.suixing.agent.ui.onboarding

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.groupModels
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ProviderModel
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.components.SuixingDialog
import kotlinx.coroutines.launch

/**
 * 第三页：选模型、定思考强度。
 *
 * 模型名可以手填，也可以点「获取模型列表」从端点的 `/models` 拉一份清单来挑——**上游说了算**，
 * 所以清单里有什么就有什么，不猜。
 *
 * 思考强度用 [REASONING_LEVELS]（设置页与聊天页读的是同一张表），所以这一页显示的档位名
 * 和别处不可能对不上。
 */
@Composable
fun ModelPage(
    draft: OnboardingDraft,
    stored: AppSettings,
    scroll: ScrollState,
    onChange: (OnboardingDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { LlmClient() }
    var loading by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<ProviderModel>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("选一个模型", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "模型名就是发到接口上的那个 id。填错了接口会报错，拉一份清单来挑最省事。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = draft.model,
            onValueChange = { onChange(draft.copy(model = it, touched = true)) },
            label = { Text("模型名") },
            supportingText = {
                Text(
                    if (draft.model.isBlank()) {
                        "还没有模型名"
                    } else {
                        "记住的是原始 id：这里填什么，发给接口的就是什么"
                    },
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            enabled = !loading && draft.baseUrl.isNotBlank(),
            onClick = {
                scope.launch {
                    loading = true
                    notice = null
                    val probe = stored.mergeOnboarding(draft)
                    runCatching { client.listModelDetails(probe) }.fold(
                        onSuccess = { details ->
                            fetched = details
                            if (fetched.isEmpty()) {
                                notice = "这个接口没有返回任何模型，直接手填模型名吧。"
                            } else {
                                pickerOpen = true
                            }
                        },
                        onFailure = { notice = describeError(it) },
                    )
                    loading = false
                }
            },
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) "获取中…" else "获取模型列表")
        }
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))

        Text("思考强度", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "推理模型想多久。接口不认识这个参数时会自己忽略，不会报错。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            REASONING_LEVELS.forEach { (wire, label) ->
                FilterChip(
                    selected = draft.reasoningEffort == wire,
                    onClick = { onChange(draft.copy(reasoningEffort = wire, touched = true)) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "不确定就别动：默认（空）＝交给模型自己决定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }

    if (pickerOpen) {
        ModelPickerDialog(
            models = fetched,
            current = draft.model,
            onDismiss = { pickerOpen = false },
            onPick = { picked ->
                // 上游报的窗口顺手记下来：设过就不会被下次拉清单覆盖（与设置页同一套规则），
                // 没报就不写——不猜。
                val window = picked.contextLength
                onChange(
                    draft.copy(
                        model = picked.id,
                        touched = true,
                        modelContexts = if (window != null && window > 0) {
                            draft.modelContexts + (picked.id to window)
                        } else {
                            draft.modelContexts
                        },
                    ),
                )
                pickerOpen = false
            },
        )
    }
}

/**
 * 模型清单：按上游厂商分组（[groupModels]，和聊天页的模型切换器是同一套分组规则）。
 */
@Composable
private fun ModelPickerDialog(
    models: List<ProviderModel>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (ProviderModel) -> Unit,
) {
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("选模型") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                groupModels(models.map { it.id }).forEach { (group, ids) ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    ids.forEach { id ->
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (id == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .suixingClickable(MaterialTheme.shapes.small) {
                                    onPick(models.first { it.id == id })
                                }
                                .padding(vertical = 8.dp),
                        )
                        Spacer(Modifier.height(1.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
        confirmButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("关闭") } },
    )
}

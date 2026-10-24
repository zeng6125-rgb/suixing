package com.suixing.agent.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.describeError
import kotlinx.coroutines.launch

/**
 * 第二页：挑一家模型服务，填上地址与 Key。
 *
 * 这一页能直接填 Key（不是「去设置里填」），因为 Key 是这件事里最难顶的一步——让人先离开
 * 引导、再自己找回去，多数人就在这一步走丢了。
 *
 * **换了地址就把 Key 清空**：DeepSeek 的 Key 绝不能被顺手带到 Kimi 去。这是这一页唯一一条
 * 不能商量的规则。
 */
@Composable
fun ProviderPage(
    draft: OnboardingDraft,
    stored: AppSettings,
    scroll: ScrollState,
    onChange: (OnboardingDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { LlmClient() }
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // Key 默认是藏着的（它是密钥，截图与旁观都不该一眼看见），但要能按一下眼睛看个清楚——
    // 手抄错一个字符是这一步最常见的失败。
    var revealKey by remember { mutableStateOf(false) }

    val selected = PROVIDER_PRESETS.firstOrNull { it.id == draft.presetId }

    fun pick(preset: ProviderPreset) {
        val endpointChanged = preset.baseUrl.isNotBlank() && !sameEndpoint(preset.baseUrl, draft.baseUrl)
        // 「还没配过 Key」＝用户还没开始用这个端点：这时草稿里的模型名是 App 的出厂默认，
        // 换成预设的默认才是对的。已经配过了就别动他挑过的模型。
        val firstRun = draft.apiKey.isBlank() && !draft.touched
        onChange(
            draft.copy(
                presetId = preset.id,
                presetName = preset.name,
                // 自填那条不覆盖已有地址：它说的是「我用自己的端点」，不是「把地址清空」。
                baseUrl = if (preset.baseUrl.isNotBlank()) preset.baseUrl else draft.baseUrl,
                // 换端点 ⇒ 换 Key。前一个厂商的 Key 拿到另一个厂商去只会被拒，还要多一次
                // 「为什么一直 401」的排查。
                apiKey = if (endpointChanged) "" else draft.apiKey,
                // 模型名：换端点就换成那家的默认；同一个端点则**保住用户自己填的那个**
                // （他可能特意改过）。只有「还没配过任何 Key」的时候才用预设的模型名——
                // 那时草稿里的模型是 App 的出厂默认，不是用户挑的。
                model = if (endpointChanged || firstRun) preset.model else draft.model,
                // 自填那条不动协议（它没规定该说什么协议）；其余按预设走。
                protocol = if (preset.baseUrl.isBlank()) draft.protocol else preset.protocol,
                touched = true,
            ),
        )
        outcome = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("挑一家模型服务", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "挑一家、把 Key 填上就能开始聊。哪一家都行，也可以跳过，之后在设置里再配。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PROVIDER_PRESETS.forEach { preset ->
            PresetCard(
                preset = preset,
                selected = preset.id == draft.presetId,
                onClick = { pick(preset) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("接口与密钥", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { value ->
                val preset = PROVIDER_PRESETS.firstOrNull { it.id == draft.presetId }
                val stillSame = preset != null && sameEndpoint(preset.baseUrl, value)
                onChange(
                    draft.copy(
                        baseUrl = value,
                        touched = true,
                        presetId = if (stillSame) draft.presetId else "",
                        presetName = if (stillSame) draft.presetName else "",
                    ),
                )
            },
            label = { Text("接口地址") },
            supportingText = {
                Text(
                    if (draft.baseUrl.startsWith("http://")) {
                        "http 开头会用明文传 Key，只在你自己的局域网里用"
                    } else {
                        "填到 /v1 即可"
                    },
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { onChange(draft.copy(apiKey = it, touched = true)) },
            label = { Text("API Key") },
            supportingText = {
                Text(
                    when {
                        draft.apiKey.isNotBlank() -> "已经填好了。测试连接会真的发一次请求。"
                        // 提示的**时机**：用户还没动过的时候别说他"没填"（他可能只是还没走到
                        // 这一步）；动过之后还空着才提示，而且说清"不填也能走"。
                        draft.touched -> "还没填 Key —— 现在还不能聊天，之后在设置里补也行"
                        else -> selected?.keyHint
                            ?: "厂商给你的那串密钥；不填也能往下走，只是还不能聊天"
                    },
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            visualTransformation = if (revealKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { revealKey = !revealKey }) {
                    Icon(
                        imageVector = if (revealKey) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (revealKey) "隐藏 Key" else "显示 Key",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))

        Text("协议", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProtocolChip("OpenAI 兼容", PROTOCOL_OPENAI, draft.protocol) { p ->
                onChange(draft.copy(protocol = p, touched = true))
            }
            ProtocolChip("Anthropic", PROTOCOL_ANTHROPIC, draft.protocol) { p ->
                onChange(draft.copy(protocol = p, touched = true))
            }
            ProtocolChip("Responses", PROTOCOL_RESPONSES, draft.protocol) { p ->
                onChange(draft.copy(protocol = p, touched = true))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "挑预设时已经替你选好了；只有自己填端点、而且那个端点不是 OpenAI 兼容时才需要改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            enabled = !testing && draft.baseUrl.isNotBlank() && draft.apiKey.isNotBlank(),
            onClick = {
                scope.launch {
                    testing = true
                    outcome = null
                    val probe = stored.mergeOnboarding(draft)
                    outcome = runCatching {
                        client.complete(
                            settings = probe,
                            messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")),
                            tools = emptyList(),
                        )
                    }.fold(
                        onSuccess = { "连上了，模型回了：${it.text.trim().take(40)}" },
                        onFailure = { describeError(it) },
                    )
                    failed = !outcome.orEmpty().startsWith("连上了")
                    testing = false
                }
            },
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (testing) "测试中" else "测试连接")
        }
        if (outcome != null) {
            Spacer(Modifier.height(10.dp))
            Boxed(
                text = outcome.orEmpty(),
                failed = failed,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "测不通也没关系：地址、Key 名字、模型名都可能要改一改，这里只是提前告诉你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 一张供应商预设卡。
 *
 * 选中态要说得很清楚：**主色描边 + 浅一档的表面 + 实心对勾**；没选中就老老实实是普通表面 +
 * 一个空心圈（radio 的样子），不要用"看起来也像选中"的填充去误导人。图标位两种状态都占着，
 * 卡片宽度才不会在切换时跳一下。
 *
 * 没有用 [SuixingCard]：它的描边颜色写死成 outlineVariant，选中那一圈要变成主色。
 */
@Composable
private fun PresetCard(
    preset: ProviderPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preset.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = if (selected) "已选" else "未选",
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 协议那一排里的一个选项。 */
@Composable
private fun ProtocolChip(
    label: String,
    wire: String,
    current: String,
    onPick: (String) -> Unit,
) {
    FilterChip(
        selected = current == wire,
        onClick = { onPick(wire) },
        label = { Text(label) },
    )
}

/** 测试结果那一块：成功是主色，失败是错误色，都不靠图标分辨。 */
@Composable
private fun Boxed(text: String, failed: Boolean) {
    val color = if (failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

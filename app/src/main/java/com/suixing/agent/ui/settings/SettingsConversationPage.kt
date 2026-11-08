package com.suixing.agent.ui.settings

import com.suixing.agent.data.SecretBox
import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.CompletionDot
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.suixing.agent.ui.components.SettingsSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SubagentModelSelection
import com.suixing.agent.data.resolve
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.decrypted
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.label
import com.suixing.agent.data.COMPRESS_MAX_PERCENT
import com.suixing.agent.data.COMPRESS_MIN_PERCENT
import com.suixing.agent.data.COMPRESS_STEP_PERCENT
import com.suixing.agent.llm.MAX_RETRY_ATTEMPTS
import com.suixing.agent.llm.MAX_RETRY_DELAY_SECONDS
import com.suixing.agent.llm.MIN_RETRY_DELAY_SECONDS
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LocalNetworkAccess
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.drawer.DrawerOrder
import com.suixing.agent.ui.drawer.DrawerOrderStore
import com.suixing.agent.ui.drawer.drawerSections
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Composable
internal fun ConversationSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allowed by Core.gate.alwaysAllowedTools.collectAsStateWithLifecycle()
    val denied by Core.gate.alwaysDeniedTools.collectAsStateWithLifecycle()
    // 排序只依赖这两个集合：别每次重组都在 items() 里重排一遍。
    val allowSorted = remember(allowed) { allowed.sorted() }
    val denySorted = remember(denied) { denied.sorted() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.CONVERSATION.title, onBack = onBack) },
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding())
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("输入与插话", first = true) }
            item {
                SettingRow(
                    title = "回车键发送",
                    subtitle = "开：按回车直接发送（运行中则当作插话送进去）；关：回车换行，" +
                        "用发送键提交。",
                    control = {
                        SettingsSwitch(
                            checked = stored.enterKeySends,
                            onCheckedChange = { on -> scope.persist { it.copy(enterKeySends = on) } },
                        )
                    },
                )
            }
            item {
                // 0 排队 / 1 立即，见 AppSettings.steerMode：打断在途的回复是有损的，
                // 所以默认排队，只有用户主动选「立即」才掐断。
                LabeledBlock(
                    label = "插话方式",
                    hint = "你干活时插的话落在哪里。排队：等这一轮说完再送进去，模型把它当成" +
                        "中途听到的话；立即：打断在途的回复，模型马上看到，代价是这一轮输出作废。",
                ) {
                    SegmentedPill(
                        options = listOf("排队", "立即"),
                        selectedIndex = stored.steerMode.coerceIn(0, 1),
                        onSelect = { index -> scope.persist { it.copy(steerMode = index) } },
                    )
                }
            }
            item { SectionHeader("工具执行") }
            item {
                // 0 是「不限制」，但把 0 直接摆在标签上读起来像个没配好的空值——用户原话
                // 「把 0 字样改成无限」。存储值不变（仍是 0），引擎那侧 `roundLimit <= 0`
                // 就是不限（AgentEngine.kt:975），所以这里只改给人看的那个词。
                LabeledSlider(
                    label = if (stored.maxToolRounds <= 0) {
                        "最大工具轮数 无限"
                    } else {
                        "最大工具轮数 ${stored.maxToolRounds}"
                    },
                    hint = "一次任务里最多连续调用多少次工具；0＝不限轮数（显示为「无限」），" +
                        "拖到最左就是不限制。",
                    value = stored.maxToolRounds.toFloat(),
                    range = 0f..40f,
                    steps = 39,
                    onValueChange = { turns -> scope.persist { it.copy(maxToolRounds = turns.toInt()) } },
                )
            }
            item {
                // 一轮里最多几条工具调用并发跑。1＝串行（默认，与历史行为一字不差）；N>1 时一轮内
                // 最多 N 条同时执行、结果仍按模型请求的顺序回写。只在 AUTO 审批档生效（ASK 档的
                // 授权弹窗每会话单槽，并发会互相顶掉），故 ASK 时引擎强制串行 —— 这里照常显示设置。
                LabeledSlider(
                    label = if (stored.toolParallel <= 1) {
                        "工具并行数 串行"
                    } else {
                        "工具并行数 ${stored.toolParallel}"
                    },
                    hint = "模型一轮里请求的多个工具，最多几条同时跑。1＝逐个串行（默认，最稳）；" +
                        "调大可加快多工具任务，但 shell 类工具并发可能互相踩（同时写一个文件）。" +
                        "审批档为「每次询问」时强制串行（授权弹窗一次只挂一个）。",
                    value = stored.toolParallel.toFloat(),
                    // 4 个档位（1/2/3/4）＝ steps+2，故 steps=2（与上面 0..40/steps=39 同构）。
                    range = 1f..4f,
                    steps = 2,
                    onValueChange = { n -> scope.persist { it.copy(toolParallel = n.toInt()) } },
                )
            }
            // 「自动整理用户画像」与「画像整理间隔」搬到了用户画像页（ProfileScreen）：它们管的是
            // 那份画像本身怎么维护，与这一页的对话行为不是一回事，而「数据」页是纯入口列表、
            // 放开关会破坏它的形态。字段与存储没动。
            item { SectionHeader("审批与授权") }
            item {
                LabeledBlock(label = "工具审批", hint = "全局默认；未单独设置审批的会话跟随此项。") {
                    SegmentedPill(
                        options = ApprovalMode.entries.map { it.label },
                        selectedIndex = ApprovalMode.entries.indexOf(stored.approvalMode),
                        onSelect = { index ->
                            scope.persist { it.copy(approvalMode = ApprovalMode.entries[index]) }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (stored.approvalMode) {
                        ApprovalMode.AUTO -> "模型可以连续调用工具，不再打断你。"
                        ApprovalMode.ASK -> "每次调用工具前都会问你一次，可以选「总是允许」。"
                        ApprovalMode.DENY -> "禁止一切工具调用，只能聊天。"
                    },
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stored.approvalMode == ApprovalMode.ASK) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是允许的工具", style = SettingsStyle.sectionTitle)
                            Text(
                                text = if (allowed.isEmpty()) {
                                    "还没有。审批时选「总是允许」，这里会记住它，重启也在。"
                                } else {
                                    "${allowed.size} 个，重启后依然有效"
                                },
                                style = SettingsStyle.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (allowed.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetAlways()
                                    scope.launch { snackbar.showSnackbar("已清除全部记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                }
                // Namespaced keys: the two lists share one LazyColumn, and a name that
                // moved from one to the other must not be two items with one key.
                items(allowSorted, key = { "allow-$it" }) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = SettingsStyle.body,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { Core.gate.forgetAlways(name) }) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_x),
                                contentDescription = "忘记 $name",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是拒绝的工具", style = SettingsStyle.sectionTitle)
                            Text(
                                text = if (denied.isEmpty()) {
                                    "还没有。审批时选「总是拒绝」，它不会再问第二次。"
                                } else {
                                    "${denied.size} 个，重启后依然有效"
                                },
                                style = SettingsStyle.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (denied.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetDenied()
                                    scope.launch { snackbar.showSnackbar("已清除全部拒绝记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                }
                items(denySorted, key = { "deny-$it" }) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = SettingsStyle.body,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { Core.gate.forgetDenied(name) }) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_x),
                                contentDescription = "不再拒绝 $name",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { SectionHeader("上下文管理") }
            item {
                SettingRow(
                    title = "自动压缩上下文",
                    subtitle = "上下文接近窗口上限时把旧消息压成摘要。超窗是接口直接报错，" +
                        "压缩最坏只是丢些细节；只在「模型与生成」里设了上下文条数时才可能触发。",
                    control = {
                        SettingsSwitch(
                            checked = stored.autoCompress,
                            onCheckedChange = { on -> scope.persist { it.copy(autoCompress = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "自动压缩阈值",
                    subtitle = "上下文用到窗口的这个比例时，自动把较早的对话压成摘要；" +
                        "调高更省钱但更容易触顶，调低更保险但会提前丢细节。" +
                        (if (stored.autoCompress) "" else "（自动压缩已关闭，这一项暂不生效。）"),
                    control = {
                        // 关掉开关也留着这一行，只是置灰：藏起来用户就找不到它了。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.alpha(if (stored.autoCompress) 1f else 0.38f),
                        ) {
                            IconButton(
                                enabled = stored.autoCompress,
                                onClick = {
                                    scope.persist { s ->
                                        s.copy(
                                            compressAtPercent =
                                                (s.compressAtPercent - COMPRESS_STEP_PERCENT)
                                                    .coerceIn(COMPRESS_MIN_PERCENT, COMPRESS_MAX_PERCENT),
                                        )
                                    }
                                },
                            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            Text(
                                text = "${stored.compressAtPercent.coerceIn(
                                    COMPRESS_MIN_PERCENT,
                                    COMPRESS_MAX_PERCENT,
                                )}%",
                                style = SettingsStyle.rowTitle,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 44.dp),
                            )
                            IconButton(
                                enabled = stored.autoCompress,
                                onClick = {
                                    scope.persist { s ->
                                        s.copy(
                                            compressAtPercent =
                                                (s.compressAtPercent + COMPRESS_STEP_PERCENT)
                                                    .coerceIn(COMPRESS_MIN_PERCENT, COMPRESS_MAX_PERCENT),
                                        )
                                    }
                                },
                            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                    },
                )
            }
            item { SectionHeader("记忆与画像") }
            item {
                SettingRow(
                    title = "注入记忆提示词",
                    subtitle = "开：每次请求都把「你记得的事」（记忆条目）拼进系统提示词；" +
                        "关：这一段完全不出现，模型不知道你记过什么，但省下这部分上下文。",
                    control = {
                        SettingsSwitch(
                            checked = stored.injectMemory,
                            onCheckedChange = { on -> scope.persist { it.copy(injectMemory = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "注入用户画像提示词",
                    subtitle = "开：把「关于用户（画像）」拼进系统提示词，模型先知道「这是谁」；" +
                        "关：整段不出现（画像本身不会被删，只是这次不发给模型）。",
                    control = {
                        SettingsSwitch(
                            checked = stored.injectProfile,
                            onCheckedChange = { on -> scope.persist { it.copy(injectProfile = on) } },
                        )
                    },
                )
            }
            item { SectionHeader("失败重试") }
            item {
                SettingRow(
                    title = "失败自动重试",
                    subtitle = "只对网络中断、超时、5xx 与 429 重试；已经开始回答则不重试——那会把" +
                        "半截回答重来一遍，比重试更糟。",
                    control = {
                        SettingsSwitch(
                            checked = stored.retryOnFailure,
                            onCheckedChange = { on -> scope.persist { it.copy(retryOnFailure = on) } },
                        )
                    },
                )
            }
            item {
                LabeledSlider(
                    label = if (stored.retryMaxAttempts <= 0) {
                        "最多重试次数 不重试"
                    } else {
                        "最多重试次数 ${stored.retryMaxAttempts.coerceIn(0, MAX_RETRY_ATTEMPTS)}"
                    },
                    hint = "一次请求最多重试几次；0＝不重试。只在上面那个开关打开时生效。",
                    value = stored.retryMaxAttempts.coerceIn(0, MAX_RETRY_ATTEMPTS).toFloat(),
                    range = 0f..MAX_RETRY_ATTEMPTS.toFloat(),
                    steps = MAX_RETRY_ATTEMPTS - 1,
                    onValueChange = { n -> scope.persist { it.copy(retryMaxAttempts = n.toInt()) } },
                )
            }
            item {
                LabeledSlider(
                    label = "重试间隔 ${stored.retryDelaySeconds} 秒",
                    hint = "两次重试之间等多久。等得久一点更可能躲过对方的限流。",
                    value = stored.retryDelaySeconds
                        .coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)
                        .toFloat(),
                    range = MIN_RETRY_DELAY_SECONDS.toFloat()..MAX_RETRY_DELAY_SECONDS.toFloat(),
                    steps = MAX_RETRY_DELAY_SECONDS - MIN_RETRY_DELAY_SECONDS - 1,
                    onValueChange = { s -> scope.persist { it.copy(retryDelaySeconds = s.toInt()) } },
                )
            }
            item { SectionHeader("思考过程") }
            item {
                SettingRow(
                    title = "显示思考过程",
                    subtitle = "把模型的思考流式画在回复上方。关掉只是不显示，思考强度照旧生效。",
                    control = {
                        SettingsSwitch(
                            checked = stored.showReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(showReasoning = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "历史思考块展开",
                    subtitle = "正在思考的那一块总是展开。开＝历史思考块也展开；关＝历史块收起成一行标题，点它仍可逐块展开。",
                    control = {
                        SettingsSwitch(
                            checked = stored.expandReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(expandReasoning = on) } },
                        )
                    },
                )
            }
            item {
                LabeledBlock(label = "思考块高度", hint = "展开的思考过程最多长这么高，超出部分在块内滚动。") {
                    SegmentedPill(
                        options = listOf("180 矮", "240 中", "320 高"),
                        selectedIndex = when (stored.reasoningHeight) {
                            240 -> 1
                            320 -> 2
                            else -> 0
                        },
                        onSelect = { index ->
                            val height = when (index) {
                                1 -> 240
                                2 -> 320
                                else -> 180
                            }
                            scope.persist { it.copy(reasoningHeight = height) }
                        },
                    )
                }
            }
            item {
                SettingRow(
                    title = "思考块跟随最新",
                    subtitle = "思考过程流式输出时自动滚到底部；关掉后停在当前位置",
                    control = {
                        SettingsSwitch(
                            checked = stored.followReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(followReasoning = on) } },
                        )
                    },
                )
            }
            item { SectionHeader("消息显示") }
            item {
                SettingRow(
                    title = "自动跟随新消息",
                    subtitle = "在底部时新回复自动滚到底；上滑看历史就暂停跟随。",
                    control = {
                        SettingsSwitch(
                            checked = stored.autoFollow,
                            onCheckedChange = { on -> scope.persist { it.copy(autoFollow = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "显示时间",
                    subtitle = "在每条消息下面显示发送时间。",
                    control = {
                        SettingsSwitch(
                            checked = stored.showTimestamps,
                            onCheckedChange = { on -> scope.persist { it.copy(showTimestamps = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "工具调用默认展开",
                    subtitle = "关掉时工具卡片折叠成一行，点开才看参数与结果。",
                    control = {
                        SettingsSwitch(
                            checked = stored.expandTools,
                            onCheckedChange = { on -> scope.persist { it.copy(expandTools = on) } },
                        )
                    },
                )
            }
        }
    }
}

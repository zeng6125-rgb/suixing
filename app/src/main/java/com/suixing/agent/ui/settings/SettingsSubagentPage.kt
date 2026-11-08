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
internal fun SubagentSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val parallelism = stored.subagentParallelism.coerceIn(1, 3)
    val selection = stored.subagentModel
    val providers = remember(stored.providers) {
        stored.providers.filter { provider -> provider.baseUrl.isNotBlank() && provider.enabled.any { it.isNotBlank() } }
    }
    val effectiveProvider = remember(selection, providers) { selection?.resolve(providers) }
    var choosingModel by rememberSaveable(selection != null) { mutableStateOf(selection != null) }
    var providerId by rememberSaveable(selection?.providerId) { mutableStateOf(selection?.providerId.orEmpty()) }
    val provider = providers.firstOrNull { it.id == providerId }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember(providerId) { mutableStateOf(false) }
    fun dismissMenu(): Boolean = when {
        modelMenuOpen -> { modelMenuOpen = false; true }
        providerMenuOpen -> { providerMenuOpen = false; true }
        else -> false
    }
    androidx.activity.compose.BackHandler(enabled = modelMenuOpen || providerMenuOpen) { dismissMenu() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.SUBAGENT.title, onBack = { if (!dismissMenu()) onBack() }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("任务委派", first = true) }
            item {
                SettingRow(
                    title = "允许委派子任务",
                    subtitle = "助手可将复杂任务拆开，子任务继承父任务的工具权限，可能增加模型调用。",
                    control = {
                        SettingsSwitch(
                            checked = stored.subagentsEnabled,
                            onCheckedChange = { on -> scope.persist { it.copy(subagentsEnabled = on) } },
                        )
                    },
                )
            }
            if (stored.subagentsEnabled) item {
                LabeledSlider(
                    label = "子任务并行数 $parallelism",
                    hint = "每轮最多创建 6 个子任务。父任务正常结束前会等待子任务；停止父任务时一同取消。",
                    value = parallelism.toFloat(),
                    range = 1f..3f,
                    steps = 1,
                    onValueChange = { value -> scope.persist { it.copy(subagentParallelism = value.toInt()) } },
                )
            }
            item { SectionHeader("子任务模型") }
            item {
                LabeledBlock(
                    label = "模型来源",
                    hint = if (selection != null && effectiveProvider == null) {
                        "原先指定的模型已不可用，子任务会跟随当前会话。"
                    } else {
                        "默认跟随当前会话的模型。选好供应商和模型后，从下一次运行起仅对子任务生效。"
                    },
                ) {
                    SegmentedPill(
                        options = listOf("跟随当前会话", "指定模型"),
                        selectedIndex = if (choosingModel) 1 else 0,
                        onSelect = { index ->
                            choosingModel = index == 1
                            dismissMenu()
                            if (index == 0 && selection != null) {
                                scope.persist { it.copy(subagentModel = null) }
                            }
                        },
                    )
                }
            }
            if (choosingModel) {
                item {
                    ProviderDropdown(
                        providers = providers,
                        selected = provider,
                        enabled = true,
                        expanded = providerMenuOpen,
                        onExpandedChange = { providerMenuOpen = it },
                        onSelect = { providerId = it.id },
                    )
                }
                if (providers.isEmpty()) item {
                    Text(
                        "请先在「模型与生成」中启用至少一个模型。",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (provider != null) item {
                    EnabledModelDropdown(
                        provider = provider,
                        selectedId = selection?.model?.takeIf { effectiveProvider?.id == provider.id }.orEmpty(),
                        placeholder = "选择子任务模型",
                        subtitle = "选定后保存，仅用于子任务",
                        enabled = true,
                        expanded = modelMenuOpen,
                        onExpandedChange = { modelMenuOpen = it },
                        onPick = { model ->
                            val choice = SubagentModelSelection(provider.id, model)
                            scope.persist { if (choice.resolve(it.providers) != null) it.copy(subagentModel = choice) else it }
                        },
                    )
                }
            }
            item { SectionHeader("子任务模板") }
            item { SubagentProfilesCard(stored) }
        }
    }
}

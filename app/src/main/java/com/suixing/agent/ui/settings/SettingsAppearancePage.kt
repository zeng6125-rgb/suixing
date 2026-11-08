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
internal fun AppearanceSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.APPEARANCE.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("主题与配色", first = true) }
            item {
                LabeledBlock(label = "主题") {
                    SegmentedPill(
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = stored.themeMode.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(themeMode = index) } },
                    )
                }
            }
            item {
                LabeledBlock(
                    label = "配色",
                    hint = "「跟随壁纸」需要安卓 12 以上，会改用系统动态取色。",
                ) {
                    SegmentedPill(
                        options = AppearancePalette.labels,
                        selectedIndex = AppearancePalette.fromId(stored.paletteMode).ordinal,
                        onSelect = { index ->
                            val palette = AppearancePalette.entries[index]
                            scope.persist { it.copy(paletteMode = palette.id) }
                        },
                    )
                }
            }
            item { SectionHeader("文字与布局") }
            item {
                LabeledBlock(label = "字号", hint = "正文字号，立即生效。") {
                    SegmentedPill(
                        options = listOf("小", "标准", "大", "特大"),
                        selectedIndex = stored.fontScale.coerceIn(0, 3),
                        onSelect = { index -> scope.persist { it.copy(fontScale = index) } },
                    )
                }
            }
            item {
                LabeledBlock(label = "圆角", hint = "卡片、按钮与输入框的圆角大小。") {
                    SegmentedPill(
                        options = listOf("方正", "标准", "圆润"),
                        selectedIndex = stored.cornerScale.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(cornerScale = index) } },
                    )
                }
            }
            item {
                // **助手回复的容器形态**（用户：「去除助手的卡片化设计，也就是不需要气泡，
                // 保留头像以及助手名称」）。做成开关而不是直接换掉，是因为这是纯观感取舍：
                // 卡片让每条回复自成一体，扁平让长对话更像一篇文章。
                // 只影响助手行；用户消息保留自己的气泡。
                LabeledBlock(
                    label = "助手回复样式",
                    hint = "「卡片」保留圆角、底色与描边；「扁平」只留头像和名称，正文直接铺在页面上。",
                ) {
                    SegmentedPill(
                        options = listOf("卡片", "扁平"),
                        selectedIndex = if (stored.assistantCard) 0 else 1,
                        onSelect = { index -> scope.persist { it.copy(assistantCard = index == 0) } },
                    )
                }
            }
            item {
                // 只对「扁平」样式生效：卡片模式下的左右留白来自卡片自己的内边距。
                LabeledBlock(
                    label = "扁平正文留白",
                    hint = "「扁平」样式下正文的左右留白：2 / 6 / 10dp。列表本身还留 16dp，所以正文距屏幕边缘是 18 / 22 / 26dp。",
                ) {
                    SegmentedPill(
                        options = listOf("紧凑", "标准", "宽松"),
                        selectedIndex = stored.flatPadding.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(flatPadding = index) } },
                    )
                }
            }
            item {
                LabeledBlock(label = "气泡宽度", hint = "助手回复最多占屏幕的宽度比例。") {
                    SegmentedPill(
                        options = listOf("窄", "标准", "宽"),
                        selectedIndex = stored.bubbleWidth.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(bubbleWidth = index) } },
                    )
                }
            }
            item { SectionHeader("动效与提示") }
            item {
                LabeledBlock(
                    label = "打字机效果",
                    hint = "正在生成的正文与思考逐字显示；关掉则一收到就整块出现。已结束的回答不受影响。",
                ) {
                    SegmentedPill(
                        options = listOf("开", "关"),
                        selectedIndex = if (stored.typewriter) 0 else 1,
                        onSelect = { index -> scope.persist { it.copy(typewriter = index == 0) } },
                    )
                }
            }
            // 思考块与「看到什么」的六项搬到了「对话配置」页：它们是对话内容的显示方式，不是
            // 主题/排版。字段与存储没动。
            item {
                LabeledBlock(
                    label = "提示条自动消失",
                    hint = "屏幕上方那条提示（例如连接中断）几秒后自己消失；「不消失」只能手点 × 关掉。" +
                        "回答卡片里的原因不受这里影响。",
                ) {
                    SegmentedPill(
                        options = listOf("5 秒", "10 秒", "30 秒", "不消失"),
                        selectedIndex = when (stored.bannerSeconds) {
                            5 -> 0
                            30 -> 2
                            0 -> 3
                            else -> 1
                        },
                        onSelect = { index ->
                            scope.persist { it.copy(bannerSeconds = listOf(5, 10, 30, 0)[index]) }
                        },
                    )
                }
            }
            item {
                TextButton(
                    onClick = {
                        val defaults = AppSettings()
                        scope.persist {
                            it.copy(
                                themeMode = defaults.themeMode,
                                paletteMode = defaults.paletteMode,
                                fontScale = defaults.fontScale,
                                bubbleWidth = defaults.bubbleWidth,
                                cornerScale = defaults.cornerScale,
                                // 只重置此页管理的外观参数；边缘光由 UI自动化页管理。
                                bannerSeconds = defaults.bannerSeconds,
                                typewriter = defaults.typewriter,
                            )
                        }
                    },
                ) { Text("恢复默认外观") }
            }
        }
    }
}

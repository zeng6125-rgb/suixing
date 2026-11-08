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
internal fun NetworkVoiceSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val pendingFields = rememberDebouncedSettingsWrites()
    var searchEndpoint by remember { mutableStateOf(stored.searchEndpoint) }
    // 输入框里必须是明文（issue #58 #4）：持久化形态是 `enc1:` 密文。
    var searchApiKey by remember { mutableStateOf(SecretBox.decrypt(stored.searchApiKey)) }
    var endpointEdited by remember { mutableStateOf(false) }
    var keyEdited by remember { mutableStateOf(false) }
    // 语音转写模型原来逐击键 `persist{}`：一个字符一次「整份 JSON 编码 + Keystore 加密 +
    // 文件重写 + 广播」，而输入框显示的值就来自那条写回 —— 打得快会看到字符回退、光标跳。
    // 与上面两个搜索字段同一套（#54 C-1）：本地态即时显示，**停手 400ms 才落盘**。
    var asrModelText by remember { mutableStateOf(stored.asrModel) }
    var asrModelEdited by remember { mutableStateOf(false) }
    LaunchedEffect(stored.searchEndpoint, stored.searchApiKey, stored.asrModel) {
        // Seed from the first real repository emission, without a delayed disk echo
        // replacing characters typed after an earlier debounced batch was dispatched.
        if (!endpointEdited) searchEndpoint = stored.searchEndpoint
        if (!keyEdited) searchApiKey = SecretBox.decrypt(stored.searchApiKey)
        if (!asrModelEdited) asrModelText = stored.asrModel
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.NETWORK.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("联网搜索", first = true) }
            item {
                OutlinedTextField(
                    textStyle = settingsFieldText,
                    value = searchEndpoint,
                    onValueChange = { value ->
                        endpointEdited = true
                        searchEndpoint = value
                        pendingFields.put("search-endpoint") { it.copy(searchEndpoint = value) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    label = { Text("搜索接口（可留空）") },
                    placeholder = { Text("https://api.tavily.com/search") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    supportingText = {
                        Text("留空时用内置的 DuckDuckGo 抓取，容易被限流；填 Tavily 兼容接口更稳。")
                    },
                )
            }
            item {
                OutlinedTextField(
                    textStyle = settingsFieldText,
                    value = searchApiKey,
                    onValueChange = { value ->
                        keyEdited = true
                        searchApiKey = value
                        pendingFields.put("search-api-key") { it.copy(searchApiKey = value) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    label = { Text("搜索接口 Key（可留空）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            item { SectionHeader("语音朗读") }
            item {
                val engineLabel by Core.speaker.engineLabel.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { Core.speaker.prepare() }
                SettingRow(
                    title = "朗读回复",
                    subtitle = "用系统语音引擎读出来。引擎状态：${engineLabel}。" +
                        "输入框左边的麦克风按钮负责语音输入。",
                    control = {
                        SettingsSwitch(
                            checked = stored.speakReplies,
                            onCheckedChange = { on -> scope.persist { it.copy(speakReplies = on) } },
                        )
                    },
                )
            }
            if (stored.speakReplies) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { Core.speaker.speak("你好，我是 JAgent。") }) {
                            Text("试听")
                        }
                        OutlinedButton(onClick = { Core.speaker.stop() }) { Text("停止朗读") }
                    }
                }
            }
            item { SectionHeader("语音转写") }
            item {
                SettingRow(
                    title = "语音输入",
                    subtitle = "开＝输入框右侧显示麦克风，用JAgent自己的录音发给你配置的转写接口（不会再弹系统识别面板）；" +
                        "关＝完全不显示麦克风。",
                    control = {
                        SettingsSwitch(
                            checked = stored.voiceInput,
                            onCheckedChange = { on -> scope.persist { it.copy(voiceInput = on) } },
                        )
                    },
                )
            }
            if (stored.voiceInput) {
                item {
                    OutlinedTextField(
                        textStyle = settingsFieldText,
                        value = asrModelText,
                        onValueChange = { value ->
                            asrModelEdited = true
                            asrModelText = value
                            pendingFields.put("asr-model") { it.copy(asrModel = value) }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        label = { Text("语音转写模型") },
                        placeholder = { Text("whisper-1") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        supportingText = {
                            Text("留空时点麦克风只会提示来这里填。转写用上面那对服务商（baseUrl 与 Key），接口：/audio/transcriptions。")
                        },
                    )
                }
            }
        }
    }
}

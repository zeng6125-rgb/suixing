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
internal fun ModelSettingsPage(onBack: () -> Unit) {
    val pendingFields = rememberDebouncedSettingsWrites()
    // Null distinguishes loading from a genuinely empty supplier list.
    val loadedStored by produceState<AppSettings?>(null) {
        // **密文没变就不重解密**（性能审计）：`decrypted()` 会解密顶层 apiKey + **每个**
        // provider 的 apiKey，而 `SecretBox.decrypt` 每次都要 `KeyStore.getInstance(
        // "AndroidKeyStore").load(null)` —— N+1 次 Keystore 往返。原来只要 settings 流发射
        // （改主题、改字号、改任何字段）就整套重解一次。
        //
        // 判据用**密文指纹**：顶层 apiKey + 每个 provider 的 apiKey 按顺序。指纹相同时
        // 说明密钥压根没变，只需把非密钥字段换成新的、密钥沿用上次解密结果。
        // 指纹里含 provider 个数 ⇒ 增删供应商必然指纹不同，按位合并不存在错位。
        var lastSecrets: Pair<String, List<String>>? = null
        var lastDecrypted: AppSettings? = null
        Core.settings.flow.collect { incoming ->
            val secrets = incoming.apiKey to incoming.providers.map { it.apiKey }
            val cached = lastDecrypted
            val reused = cached != null && secrets == lastSecrets &&
                cached.providers.size == incoming.providers.size
            val decrypted = if (reused) {
                incoming.copy(
                    apiKey = cached!!.apiKey,
                    providers = incoming.providers.mapIndexed { index, provider ->
                        provider.copy(apiKey = cached.providers[index].apiKey)
                    },
                )
            } else {
                withContext(Dispatchers.IO) { incoming.decrypted() }
            }
            lastSecrets = secrets
            lastDecrypted = decrypted
            value = decrypted
        }
    }
    val stored = loadedStored ?: AppSettings()
    var form by remember { mutableStateOf(stored) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(loadedStored) {
        if (!seeded && loadedStored != null) {
            form = stored
            seeded = true
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        try { Core.settings.cleanupEmptyProviders() }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            snackbar.showSnackbar("供应商清理未保存，请稍后重试")
        }
    }
    val client = remember { Core.llm }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var providerPickerOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var addingModel by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    val context = LocalContext.current
    // Android 17 起访问局域网要先拿到 ACCESS_LOCAL_NETWORK。这里只在**目标像内网**时请求，
    // 公网接口的用户永远不会看到这个系统对话框；授权结果回来后提示「再点一次」，
    // 不自动重放请求（重放会在用户还没点允许时就发出注定超时的请求）。
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        outcome = if (granted) {
            TestOutcome(true, "已获得局域网访问权限，再点一次即可。")
        } else {
            TestOutcome(false, "没有局域网访问权限：Android 17 起访问内网模型必须允许这项权限。")
        }
    }
    fun ensureLocalNetwork(): Boolean {
        if (!LocalNetworkAccess.blockedByMissingPermission(context, form.baseUrl)) return true
        localNetworkLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        outcome = TestOutcome(false, "已请求「局域网访问」权限，允许后再点一次。")
        return false
    }
    // Keep selection by identity while the supplier's editable draft changes.
    var selectedId by remember { mutableStateOf("") }
    // 拉取结果只进候选，不进草稿：点「获取模型列表」不该把页面标成「未保存」。
    // 只有勾选/取消勾选（启用）才写 form，那才是需要保存的改动。
    // 按供应商 id 隔离，换 chip 自动清空，A 的候选不会串到 B。
    var fetchedCandidates by remember(selectedId) { mutableStateOf<List<String>?>(null) }
    var fetchedWindows by remember(selectedId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(stored.providers.map { it.id }) {
        // Re-point only when the chip the user is on has gone away (migration, a deletion
        // elsewhere): the draft is what this page shows, so it stays where the user put it.
        if (stored.providers.none { it.id == selectedId }) {
            selectedId = stored.activeProvider()?.id ?: stored.providers.firstOrNull()?.id.orEmpty()
        }
    }
    // The endpoint the model fields edit; tapping a chip also makes it the active one.
    val selected = form.providers.firstOrNull { it.id == selectedId } ?: form.providers.firstOrNull()

    // ===== 四个端点字段的本地态（任务4 #1：击键只重组输入框，不再整页）=====
    // 原来每键 `form = form.copy(...).mapProvider(...)`：form 引用一换，捕获它的全部组合域
    // （dirty 判断、模型卡、整条 LazyColumn）逐键重跑。现在：本地态即时显示；
    // **400ms 防抖**写回 form（snapshotFlow+collectLatest 合帧，喂 dirty/保存钮）；
    // 所有"消费 form"的出口（保存/离开/换 chip/删除/测试连接/拉取模型）先 flush——
    // 行为与逐键写 form 完全一致，打字期间零整页开销。
    var baseUrlText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var apiKeyText by remember { mutableStateOf("") }
    var testModelText by remember(selected?.id, seeded) {
        mutableStateOf(stored.model.takeIf {
            selected?.id == stored.activeProvider()?.id && it in selected?.enabled.orEmpty()
        }.orEmpty())
    }
    val testModel = testModelText.takeIf { it in selected?.enabled.orEmpty() }.orEmpty()
    var endpointTouched by remember { mutableStateOf(false) }
    // 下拉的展开状态提到页面：BACK 时页面要先收起它们，否则会直接退页、
    // 而浮层窗口在页面退场动画里还挂着（用户：「下拉框还会残留一会」）。
    var testModelMenuOpen by remember(selected?.id) { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var protocolMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(selected?.id, seeded) {
        // 换 provider（chip/删除/初次装载）＝三个字段全部重新播种，触碰标记清零。
        endpointTouched = false
        baseUrlText = selected?.baseUrl.orEmpty()
        nameText = selected?.name.orEmpty()
        apiKeyText = selected?.apiKey.orEmpty()
    }
    fun flushEndpointFields() {
        form = form.copy(baseUrl = baseUrlText, apiKey = apiKeyText)
            .mapProvider(selectedId) {
                it.copy(baseUrl = baseUrlText, name = nameText, apiKey = apiKeyText)
            }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(baseUrlText, nameText, apiKeyText) }
            .drop(1)
            .collectLatest { delay(400); flushEndpointFields() }
    }

    // A chip tap rewrites the endpoint triple (address, key, model) in the draft without
    // writing to disk, so whether the draft holds a hand edit to it is a rule of its own —
    // see ModelDraft.kt, where it is unit tested.
    val endpointEdited = endpointHandEdited(form, stored, selected)
    // Only real edits count: the draft is compared against the migrated stored settings, and
    // before the first emission there is nothing to be dirty about. The endpoint triple is
    // left out on purpose — tapping a chip rewrites it in the draft without writing to disk,
    // so counting it would call a plain switch "unsaved"; a hand edit there is exactly what
    // `endpointEdited` reports. Saving writes the draft (a blank address keeps the live
    // endpoint — see forSaving), so this clears by itself.
    val dirty = seeded && (endpointEdited || form.differsOutsideEndpoint(stored))
    // Names, credentials and models now share the same explicit save boundary.
    var leavingWithDraft by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    val switchProvider: (ProviderConfig) -> Unit = { provider ->
        flushEndpointFields()   // 旧 provider 的未落 form 击键先收编，再换
        selectedId = provider.id
        modelsOpen = false
        outcome = null
        form = form.discardUncommittedProviders(stored, selected?.id).copy(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            protocol = provider.protocol,
        )
    }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val deleteProvider: (ProviderConfig) -> Unit = { provider ->
        flushEndpointFields()
        val rest = form.providers.filterNot { it.id == provider.id }
        val next = rest.firstOrNull()
        selectedId = next?.id.orEmpty()
        form = form.copy(
            providers = rest, providersInitialized = true,
            baseUrl = next?.baseUrl ?: stored.baseUrl,
            apiKey = next?.apiKey ?: stored.apiKey,
            protocol = next?.protocol ?: stored.protocol,
        )
    }
    fun leavePage() {
        flushEndpointFields()
        val kept = form.discardUncommittedProviders(stored, selected?.id)
        val remaining = kept.providers.firstOrNull { it.id == selected?.id }
            ?: kept.activeProvider() ?: kept.providers.firstOrNull()
        form = kept
        selectedId = remaining?.id.orEmpty()
        if (endpointHandEdited(kept, stored, remaining) || kept.differsOutsideEndpoint(stored)) leavingWithDraft = true
        else onBack()
    }
    suspend fun saveForm(): Boolean {
        flushEndpointFields()
        val snapshot = form
        val saved = snapshot.forSaving(stored)
        if (!Core.settings.save(saved)) return false
        // Do not replace edits typed while persistence was running.
        if (form === snapshot) {
            form = saved
            val keptId = saved.providers.firstOrNull { it.id == selectedId }?.id
                ?: saved.activeProvider()?.id ?: saved.providers.firstOrNull()?.id.orEmpty()
            val keptProvider = saved.providers.firstOrNull { it.id == keptId }
            baseUrlText = keptProvider?.baseUrl ?: saved.baseUrl
            nameText = keptProvider?.name.orEmpty()
            apiKeyText = keptProvider?.apiKey ?: saved.apiKey
            testModelText = testModelText.takeIf { it in keptProvider?.enabled.orEmpty() }.orEmpty()
            endpointTouched = false
            selectedId = keptId
        }
        return true
    }
    /** BACK 先收起开着的下拉，再考虑离开页面。 */
    fun closeMenusFirst(): Boolean = when {
        testModelMenuOpen -> { testModelMenuOpen = false; true }
        providerMenuOpen -> { providerMenuOpen = false; true }
        protocolMenuOpen -> { protocolMenuOpen = false; true }
        else -> false
    }
    androidx.activity.compose.BackHandler(enabled = seeded) {
        if (!closeMenusFirst()) leavePage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(
                title = SettingsPage.MODEL.title,
                onBack = {
                    if (seeded) {
                        if (!closeMenusFirst()) leavePage()
                    } else {
                        onBack()
                    }
                },
                // Draft status stays in the fixed action area, without shifting form rows.
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The bar is the bottom-most thing on the page, so it is what
                        // has to clear the navigation bar.
                        .navigationBarsPadding()
                        .padding(horizontal = SettingsStyle.PageMargin)
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    outcome?.let { current ->
                        OutcomeCard(current) { outcome = null }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusColor = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(statusColor.copy(alpha = 0.65f), CircleShape))
                            Text(when {
                                saving -> "保存中"
                                !seeded -> "读取中"
                                dirty -> "未保存"
                                else -> "已保存"
                            }, style = SettingsStyle.description, color = statusColor, maxLines = 1)
                        }
                        ModelActionButton(
                            text = if (testing) "测试中" else "测试连接", busy = testing,
                            enabled = seeded && !saving && !loadingModels && testModel.isNotEmpty(),
                            icon = R.drawable.ic_rotate_cw,
                            onClick = {
                                scope.launch {
                                    flushEndpointFields()   // 测试的是刚敲完的字，别等防抖
                                    if (!ensureLocalNetwork()) return@launch
                                    testing = true
                                    outcome = null
                                    try {
                                        // Test the draft without committing an unsaved supplier.
                                        outcome = runCatching {
                                            client.complete(settings = form.copy(model = testModel),
                                                messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")),
                                                tools = emptyList())
                                        }.fold(
                                            onSuccess = { TestOutcome(true, "连接成功，模型回了：${it.text.trim().take(60)}") },
                                            onFailure = {
                                                if (it is CancellationException) throw it
                                                TestOutcome(false, describeError(it))
                                            },
                                        )
                                    } finally { testing = false }
                                }
                            },
                        )
                        ModelActionButton(
                            text = if (saving) "保存中" else "保存", primary = true, busy = saving,
                            enabled = seeded && !testing && !loadingModels, icon = R.drawable.ic_check,
                            onClick = {
                                scope.launch {
                                    saving = true
                                    val saved = try { saveForm() } finally { saving = false }
                                    if (saved) snackbar.showSnackbar("已保存")
                                }
                            },
                        )
                    }
                }
            }
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
            item {
                ProviderDropdown(
                    providers = form.providers, selected = selected, enabled = seeded && !loadingModels && !testing && !saving,
                    expanded = providerMenuOpen,
                    onExpandedChange = { providerMenuOpen = it },
                    onSelect = { provider ->
                        if (provider.id != selected?.id) {
                            if (endpointEdited && stored.providers.any { it.id == selected?.id }) pendingSwitch = provider.id
                            else switchProvider(provider)
                        }
                    },
                    onAdd = {
                        if (endpointEdited && stored.providers.any { it.id == selected?.id })
                            outcome = TestOutcome(false, "请先保存当前接口改动，再添加供应商。")
                        else providerPickerOpen = true
                    },
                    onDelete = { selected?.let { provider ->
                        if (endpointEdited) pendingDelete = provider.id else deleteProvider(provider)
                    } },
                )
            }
            item { SectionHeader("接口配置") }
            item {
                // 协议在地址之上：换了协议，同一个地址要拼的路径、要带鉴权头都不一样，
                // 所以用户是先选「这个端点说哪种话」，再填地址。
                CompactProtocolPicker(
                    protocol = selected?.protocol.orEmpty(),
                    onPick = { picked ->
                        form = form.copy(protocol = picked).mapProvider(selected?.id) { it.copy(protocol = picked) }
                    },
                    expanded = protocolMenuOpen,
                    onExpandedChange = { protocolMenuOpen = it },
                )
                // Anthropic 的提示只在这个协议下出现：默认地址栏那句「填到 /v1 即可」
                // 对它的意义最具体 —— 路径由客户端补 /messages。
                if (selected?.protocol == PROTOCOL_ANTHROPIC) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "地址填到 /v1 即可，/messages 会自动补上。",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    // 15sp 走比例式而不是 `15.sp`：写死 sp 会关掉「外观 → 字号」的缩放（规范第 6 条），
                    // 详见文件底部 settingsFieldText 的 KDoc。
                    textStyle = settingsFieldText,
                    value = baseUrlText,
                    onValueChange = { value ->
                        endpointTouched = true
                        baseUrlText = value
                    },
                    // Compact field shares the 48dp editing minimum with selectors/actions;
                    // floating labels, supporting text and larger fonts may grow naturally.
                    label = { Text("接口地址") },
                    // 明文 http 是刻意留着的（局域网里的 Ollama / LM Studio 就只有 http），但
                    // key 会跟着明文走——这一句是用户敲下那个地址之前唯一的提醒。
                    supportingText = {
                        Text(
                            "例如 https://api.deepseek.com/v1\n" +
                                "填 http:// 地址时，API Key 会明文传输，仅建议在可信局域网内使用。",
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsStyle.ControlHeight),
                )
            }
            item {
                // The provider's own name, editable in place: the chip row's long-press
                // rename stays, but the name is read every day and typed once a month.
                OutlinedTextField(
                    // Uses CompactOutlinedTextField via the import alias; the shared
                    // editing minimum stays flexible rather than clipping scaled text.
                    textStyle = settingsFieldText,
                    value = nameText,
                    onValueChange = { value ->
                        endpointTouched = true
                        nameText = value
                    },
                    label = { Text("提供方名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsStyle.ControlHeight),
                )
            }
            item {
                SettingRow(
                    title = "允许自签名证书",
                    subtitle = "接口是 https 且证书自签时打开。局域网上的反代（例如 " +
                        "https://192.168.0.50:8914/v1）基本都要开，开了就不再校验证书与域名。",
                    control = {
                        SettingsSwitch(
                            checked = form.allowInsecureTls,
                            onCheckedChange = { form = form.copy(allowInsecureTls = it) },
                        )
                    },
                )
            }
            item {
                ProviderApiKeyField(
                    value = apiKeyText,
                    onValueChange = { value ->
                        endpointTouched = true
                        apiKeyText = value
                    },
                    providerId = selected?.id,
                )
            }
            item {
                // **Key 存在哪**（用户要求「更加保密化」后新增）：现在是 Android Keystore 加密后
                // 落盘，密钥在系统里、不随应用数据走 ⇒ 换机后解不开，用户要在这里重填一次。
                // 说清楚比不说好 —— 否则用户换了手机发现 Key 没了，以为是 bug。
                Text(
                    text = "Key 用系统 Keystore 加密后存在这台手机上，不随备份迁移；换机后需要重填一次。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            item { SectionHeader("模型列表") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !loadingModels && selected != null && baseUrlText.isNotBlank(),
                        modifier = Modifier.heightIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.small,
                        onClick = {
                            scope.launch {
                                // 内网地址且还没授权时，先弹系统授权；不发出注定超时的请求。
                                if (!ensureLocalNetwork()) return@launch
                                loadingModels = true
                                outcome = null
                                val requestForm = form
                                val requestProviderId = selectedId
                                runCatching { client.listModelDetails(requestForm) }.fold(
                                    onSuccess = fetchedModels@{ details ->
                                        if (selectedId != requestProviderId || form.baseUrl != requestForm.baseUrl ||
                                            form.apiKey != requestForm.apiKey || form.protocol != requestForm.protocol) {
                                            outcome = TestOutcome(false, "接口配置已变化，请重新获取模型列表。")
                                            return@fetchedModels
                                        }
                                        val fetched = details.map { it.id }.distinct()
                                        // **上游说了算**（issue #3）：/models 是唯一来源，不做并集。
                                        // 结果放进页面级候选，不写 form —— 拉取不是编辑，
                                        // 不触发底部「未保存」；只有「启用」才改草稿。
                                        fetchedCandidates = fetched
                                        // 上游声明的窗口暂存，等用户真正启用某个模型时才并入草稿。
                                        fetchedWindows = details.mapNotNull { model ->
                                            model.contextLength?.let { model.id to it }
                                        }.toMap()
                                        modelsOpen = true
                                        if (fetched.isEmpty()) {
                                            outcome = TestOutcome(false, "接口没返回任何模型，直接手填吧。")
                                        } else {
                                            outcome = TestOutcome(true, "已获取 ${fetched.size} 个模型，勾选即启用。")
                                        }
                                    },
                                    onFailure = {
                                        if (it is CancellationException) throw it
                                        outcome = TestOutcome(false, describeError(it))
                                    },
                                )
                                loadingModels = false
                            }
                        },
                    ) {
                        if (loadingModels) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (loadingModels) "获取中…" else "获取模型列表", style = SettingsStyle.metadata)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "已启用 ${selected?.enabled?.size ?: 0} 个",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "从接口获取后在弹窗勾选启用模型；只有启用/停用需要点底部保存。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("已启用模型") }
            if (selected?.enabled.isNullOrEmpty()) {
                item { Text("尚未启用模型。获取模型列表后勾选，或添加自定义模型。",
                    style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(selected?.enabled.orEmpty(), key = { "enabled:${selected?.id}:$it" },
                contentType = { "enabled-model" }) { id ->
                EnabledModelCard(
                    id = id, alias = selected?.aliases?.get(id).orEmpty(),
                    contextWindow = form.modelContexts[id] ?: 0, maxTokens = form.modelMaxTokens[id] ?: 0,
                    testModel = testModel == id,
                    onTest = { testModelText = id },
                    onEdit = { alias, contextWindow, output ->
                        form = form.mapProvider(selected?.id) {
                            it.copy(aliases = if (alias.isBlank()) it.aliases - id else it.aliases + (id to alias))
                        }.let { updated -> updated.copy(
                            modelContexts = updated.modelContexts.withContextWindow(id, contextWindow),
                            modelMaxTokens = updated.modelMaxTokens.withMaxTokens(id, output),
                        ) }
                    },
                    onRemove = { form = form.mapProvider(selected?.id) { it.withModelEnabled(id, false) } },
                )
            }
            item {
                TextButton(onClick = { addingModel = true }) { Text("添加自定义模型") }
            }
            item {
                // **测试用的模型只属于测试**（用户：「下拉只给测试用，不动全局模型」）：
                // 选项只列当前供应商已启用的模型；这里选什么都不会改写全局聊天模型。
                EnabledModelDropdown(
                    provider = selected,
                    selectedId = testModel,
                    placeholder = "选择测试模型",
                    subtitle = "仅用于测试连接",
                    enabled = seeded && !testing && !saving && !loadingModels,
                    expanded = testModelMenuOpen,
                    onExpandedChange = { testModelMenuOpen = it },
                    onPick = { testModelText = it },
                )
            }
            item {
                SettingRow(
                    title = "支持图片输入",
                    subtitle = "截图会随消息发给模型，需要视觉模型；纯文字模型开着会直接报错。",
                    control = {
                        SettingsSwitch(
                            checked = form.imageInput,
                            onCheckedChange = { form = form.copy(imageInput = it) },
                        )
                    },
                )
            }
            item { SectionHeader("生成参数") }
            // 「最大工具轮数」与「显示思考过程」搬到了「对话配置」页：一个是它一次任务能跑几轮、
            // 一个是对话里显示什么，都不属于模型端点本身。字段与存储没动。
            //
            // 「系统提示词」编辑器已从这里移除（用户：「模型设置页不再支持修改系统提示词，
            // 反正助手页可更改」）：人设属于助手，改人设去「助手」页；这里再放一个全局编辑框，
            // 等于同一个东西有两个出处。settings.systemPrompt 字段本身保留 —— 引擎每轮仍然
            // 把它拼进请求（AgentEngine.systemPrompt 的第一段），助手页写的就是它。
            // Sampling lives behind a fold: these three are set once and then never
            // touched, and they were the rows pushing the daily switches off the screen.
            item {
                // **思考强度是初级参数**（用户 2026-10-23：「思考强度不用放进高级参数里面」）：
                // 它是日常最常调的那一个，藏在折叠里每次都要多点两下。
                val own = selected?.takeIf { it.ownSampling }
                    // 滑杆而不是 pill：六档在一行 pill 上每格只剩两个字的宽度，滑杆顺带
                    // 把线上值写进标题，不用另起一行解释 reasoning_effort 收到的是什么。
                    // 同源显示：provider(own) > global，与 forAssistant 的解析顺序一致。
                    val effortWire = own?.reasoningEffort ?: form.reasoningEffort
                    val effort = REASONING_LEVELS
                        .indexOfFirst { it.first == effortWire }
                        // 旧的/接口自造的值只显示成「默认」，不写回：拨动滑杆才落盘。
                        .coerceAtLeast(0)
                    val (wire, name) = REASONING_LEVELS[effort]
                    LabeledSlider(
                        // 七档全英文之后（Default/Low/…），旧判据 `wire == name` 永远不成立
                        // ⇒ 会显示成「Minimal (minimal)」。改成忽略大小写比较，只在真正不同
                        // （接口自造的值）时才附上 wire。
                        label = "思考强度 · " +
                            if (wire.isEmpty() || wire.equals(name, ignoreCase = true)) {
                                name
                            } else {
                                "$name ($wire)"
                            },
                        hint = "对应接口的 reasoning_effort；xhigh / max 仅部分模型支持，不支持的忽略。",
                        value = effort.toFloat(),
                        range = 0f..REASONING_LEVELS.lastIndex.toFloat(),
                        steps = REASONING_LEVELS.size - 2,
                        onValueChange = { index ->
                            val level = REASONING_LEVELS[index.toInt()].first
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(reasoningEffort = level) }
                            } else {
                                form.copy(reasoningEffort = level)
                            }
                        },
                    )
            }
            item {
                AdvancedSection {
                    // 独立采样: off = the four knobs below read and write the globals,
                    // on = they read and write this provider's nullable overrides.
                    SettingRow(
                        title = "独立采样参数",
                        subtitle = "开启后温度 / Top P / 思考强度只作用于当前提供方。",
                        control = {
                            SettingsSwitch(
                                checked = selected?.ownSampling == true,
                                onCheckedChange = { on ->
                                    form = form.mapProvider(selected?.id) { it.copy(ownSampling = on) }
                                },
                            )
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    val own = selected?.takeIf { it.ownSampling }
                    LabeledSlider(
                        label = if (!form.sendTemperature) {
                            "温度 · 不发送"
                        } else {
                            "温度 ${"%.1f".format(own?.temperature ?: form.temperature)}"
                        },
                        // **最左一档 = 不发送**（用户 2026-10-23：「只保留滑块就行了，
                        // 俩滑块左侧或者右侧就是不发的档次」）。
                        // 档位：0 = 不发送；1..21 = 0.0 .. 2.0（步长 0.1）。
                        hint = "最左「不发」；其余是采样温度（推理型端点常拒收 temperature）。",
                        value = if (!form.sendTemperature) 0f
                        else (((own?.temperature ?: form.temperature) * 10.0) + 1.0)
                            .toFloat().coerceIn(1f, 21f),
                        range = 0f..21f,
                        steps = 20,
                        onValueChange = { v ->
                            val stop = v.toInt()
                            val send = stop > 0
                            val temp = if (send) (stop - 1) / 10.0 else (own?.temperature ?: form.temperature)
                            form = if (own != null) {
                                form.mapProvider(selected?.id) {
                                    it.copy(temperature = temp)
                                }.copy(sendTemperature = send)
                            } else {
                                form.copy(temperature = temp, sendTemperature = send)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = if (!form.sendTopP) {
                            "Top P · 不发送"
                        } else {
                            "Top P ${"%.2f".format(own?.topP ?: form.topP)}"
                        },
                        // 档位：0 = 不发送；1..10 = 0.1 .. 1.0。
                        hint = "最左「不发」；其余是核采样阈值（1.0 = 不过滤）。",
                        value = if (!form.sendTopP) 0f
                        else ((own?.topP ?: form.topP) * 10f).roundToInt().toFloat().coerceIn(1f, 10f),
                        range = 0f..10f,
                        steps = 9,
                        onValueChange = { v ->
                            val stop = v.toInt()
                            val send = stop > 0
                            val p = if (send) stop / 10f else (own?.topP ?: form.topP)
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(topP = p) }
                                    .copy(sendTopP = send)
                            } else {
                                form.copy(topP = p, sendTopP = send)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    // 自定义请求头（用户：「模型与生成要加入请求头的设置有些需要，加到高级参数」）：
                    // 一行一条 `Name: Value`，随每个请求发出（对话与模型列表都带）。raw 文本跟手指
                    // 走、form 解析后单向写 —— 与最大输出输入框同一条教训：value 从 form 反推会在
                    // 每次击键重组时丢焦点。
                    var headersRaw by remember {
                        mutableStateOf(
                            // 请求头在表单里也必须是明文，否则用户看到 `enc1:…` 并把它再存一遍。
                            form.requestHeaders.entries.joinToString("\n") {
                                "${it.key}: ${SecretBox.decrypt(it.value)}"
                            },
                        )
                    }
                    LabeledBlock(
                        label = "自定义请求头",
                        hint = "一行一条，格式 Name: Value；随每个请求发送（含模型列表拉取）。留空＝不加。",
                    ) {
                        OutlinedTextField(
                            textStyle = SettingsStyle.fieldText,
                            value = headersRaw,
                            onValueChange = { input ->
                                headersRaw = input
                                val parsed = input.lines()
                                    .mapNotNull { line ->
                                        val at = line.indexOf(':')
                                        if (at <= 0) return@mapNotNull null
                                        val name = line.substring(0, at).trim()
                                        val value = line.substring(at + 1).trim()
                                        if (name.isEmpty()) return@mapNotNull null
                                        name to value
                                    }
                                    .toMap()
                                form = form.copy(requestHeaders = parsed)
                                pendingFields.put("request-headers") { it.copy(requestHeaders = parsed) }
                            },
                            minLines = 3,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("X-Custom-Header: value") },
                        )
                    }
                }
            }
        }
    }

    if (modelsOpen && selected != null) {
        androidx.compose.runtime.key(selected.id) {
            FetchedModelsDialog(
                provider = selected,
                // 候选优先用本次拉取结果；没拉过就退回草稿里已有的清单（自定义模型也能勾）。
                candidates = fetchedCandidates ?: selected.models,
                onToggle = { id, enabled ->
                    // 只有「启用/停用」写草稿，这才是需要保存的改动。
                    form = form.mapProvider(selected.id) { it.withModelEnabled(id, enabled) }
                        .let { updated ->
                            // 上游窗口只在启用时按「不改用户已设值」补缺，随草稿一起保存。
                            if (enabled && fetchedWindows.containsKey(id)) {
                                updated.copy(modelContexts = updated.modelContexts.withUpstreamDefaults(fetchedWindows))
                            } else updated
                        }
                },
                onDismiss = { modelsOpen = false },
            )
        }
    }
    if (providerPickerOpen) {
        AddProviderDialog(onDismiss = { providerPickerOpen = false }, onAdd = { provider ->
            val retained = form.discardUncommittedProviders(stored, selected?.id)
            form = retained.copy(providers = retained.providers + provider,
                baseUrl = provider.baseUrl, apiKey = provider.apiKey, model = "", protocol = provider.protocol)
            selectedId = provider.id
            modelsOpen = false
            providerPickerOpen = false
        })
    }
    if (addingModel) {
        AddModelDialog(onDismiss = { addingModel = false }, onConfirm = { id ->
            form = form.mapProvider(selected?.id) { it.withModelEnabled(id, true) }
            addingModel = false
        })
    }
    if (leavingWithDraft) {
        SuixingDialog(
            onDismissRequest = { leavingWithDraft = false },
            title = { Text("保存模型配置？") },
            text = { Text("供应商、已启用模型或生成参数还有未保存的改动。") },
            confirmButton = { dialog ->
                TextButton(onClick = { dialog.closeThen {
                    leavingWithDraft = false
                    scope.launch {
                        if (saveForm()) onBack()
                    }
                } }) { Text("保存并返回") }
            },
            dismissButton = { dialog ->
                Row {
                    TextButton(onClick = { dialog.closeThen { leavingWithDraft = false; onBack() } }) { Text("放弃") }
                    TextButton(onClick = { dialog.closeThen { leavingWithDraft = false } }) { Text("继续编辑") }
                }
            },
        )
    }

    pendingSwitch?.let { id ->
        SuixingDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换提供方？") },
            text = { Text("当前提供方的接口地址、API Key 或模型还没保存，切过去会丢掉这些改动。") },
            confirmButton = { scope ->
                TextButton(
                    onClick = {
                        scope.closeThen {
                            pendingSwitch = null
                            form.providers.firstOrNull { it.id == id }?.let(switchProvider)
                        }
                    },
                ) { Text("继续") }
            },
            dismissButton = { scope -> TextButton(onClick = { scope.closeThen { pendingSwitch = null } }) { Text("取消") } },
        )
    }

    pendingDelete?.let { id ->
        SuixingDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除提供方？") },
            text = { Text("当前提供方还有未保存的改动，删除会连同这些改动一起丢掉，并切到下一个提供方。") },
            confirmButton = { scope ->
                TextButton(
                    onClick = {
                        scope.closeThen {
                            pendingDelete = null
                            form.providers.firstOrNull { it.id == id }?.let(deleteProvider)
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { scope -> TextButton(onClick = { scope.closeThen { pendingDelete = null } }) { Text("取消") } },
        )
    }
}

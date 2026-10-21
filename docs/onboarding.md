# 随行 Suixing · 首次启动引导页（Onboarding）调研与方案

> **性质**：方案文档。本文档不包含任何产品代码改动，实现落点只在第 2.5 节以清单形式列出。
> **调研基准**：仓库 `D:\dsh\fixbug\suixing`，`app/build.gradle.kts:17` → `versionName = "0.8.299"`（versionCode 310，`:16`），`minSdk = 26`（`:14`），`targetSdk = 35`（`:15`）。
> **标注约定**（全文严格执行）：
> - **【事实】** = 在本仓库代码 / 公开文档里查到的，附 `文件:行号` 或来源 URL。
> - **【建议】** = 本方案的设计主张，尚未实现。
> - **【推断】** = 对外部项目（Aether 扶摇）的行为推测，或需要二次核验才能落地的值。
> - **【待核】** = 建议值，但上线前必须回到官方文档逐条核对。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 现有首启/引导实现 | **不存在**。全项目没有首启页、欢迎页、权限引导流程，也没有「已看过引导」标记【事实】 |
| 现有持久化 | 只有 DataStore Preferences 一个文件；**没有 SharedPreferences**【事实】 |
| 模型配置存哪 | 整份 `AppSettings` 序列化成 JSON，存进 DataStore 的**唯一一个 key** `settings_json`【事实】 |
| API Key 存哪 | 与其它设置同文件、**明文**；云备份已排除，**换机迁移仍会带走**【事实】 |
| 内置供应商 | **代码里一个都没有**，只有 `AppSettings` 的默认三元组（DeepSeek）【事实】→ 需新建预设表 |
| 运行时权限 | 通知（首启弹）、麦克风（用到才问）、无障碍/Shizuku/root/悬浮窗/所有文件（**跳系统设置**，不弹框）【事实】 |
| 电池优化白名单 | **代码里完全没有**（grep 无 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）【事实】→ 不该进引导页 |
| 引导页插在哪 | `SuixingApp.kt` 的 `NavHost`（`ui/SuixingApp.kt:536`）作为**一条覆盖层路由**，与设置子页同级 |
| 建议页数 | **5 页**（欢迎 / 选供应商填 Key / 选模型与思考强度 / 权限 / 开始使用），全程可跳过 |
| 最大的不确定点 | Key 要不要为「换机免重填」牺牲加密强度（见 2.7 与 3.4） |

---

## 1. 调研（事实部分）

### 1.1 现有「首次启动 / 引导」相关实现 —— 结论：没有

**【事实】** 在 `app/src/main` 全量搜索 `onboard|welcome|引导|首启|firstLaunch|firstRun`，命中的全部是**页面内的介绍卡**，没有任何一处是「App 启动时出现、看完才进主界面」的第一屏：

| 位置 | 内容 | 性质 |
|---|---|---|
| `app/src/main/java/com/suixing/agent/ui/chat/ChatTopBar.kt:452-515` | `EmptyHero(...)` —— 空会话页的欢迎面（`SuixingMark(size = 92.dp)` +「我们开始吧」+ 建议 chip） | 会话页内的空态 |
| `app/src/main/java/com/suixing/agent/ui/chat/ChatTopBar.kt:509-513` | 文案「首次使用请先到「能力」页打开权限。」 | 唯一一句提到「首次使用」的地方，指向能力页而非引导 |
| `app/src/main/java/com/suixing/agent/ui/extension/SkillsSection.kt:91, 171` | `SkillIntroCard(rootPath)` | 工具页内介绍卡 |
| `app/src/main/java/com/suixing/agent/ui/settings/ToolsScreen.kt:570, 578` | `PluginsIntroCard()` | 工具页内介绍卡 |
| `app/src/main/java/com/suixing/agent/ui/settings/ToolsScreen.kt:677, 726` | `McpIntroCard()` | 工具页内介绍卡 |

**【事实】** 权限申请目前发生在**用到的时刻**，不在首启：
- `app/src/main/java/com/suixing/agent/MainActivity.kt:69` → `requestNotificationPermission()`，实现 `:117-127`（仅 API ≥ 33 走 `ActivityCompat.requestPermissions`，请求码 `REQUEST_NOTIFICATIONS = 1001` 在 `:130`）。**注意：它是在 `onCreate` 里无条件弹的**（已授权则跳过，未授权则弹），与首启引导无关。
- `app/src/main/java/com/suixing/agent/ui/chat/ChatScreen.kt:366-367` → `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`，麦克风 `RECORD_AUDIO`，**用户点麦克风时才问**。
- `app/src/main/java/com/suixing/agent/ui/capability/CapabilityScreen.kt:91-93`（悬浮窗）、`:102-104`（所有文件）→ 走 `StartActivityForResult` 跳系统设置页，回来后**重读**授权状态（因为授权决定在系统进程里，不在本进程）。

**【事实】** 持久化只有 DataStore，**没有 SharedPreferences**（`grep -rn "SharedPreferences\|getSharedPreferences\|PreferenceManager" app/src/main` 命中数为 0）：
- `app/src/main/java/com/suixing/agent/data/Settings.kt:15` — `val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "Suixing_settings")`
- `app/src/main/java/com/suixing/agent/data/Settings.kt:538` — `private val key = stringPreferencesKey("settings_json")`
- `app/src/main/java/com/suixing/agent/data/Settings.kt:525` — `class SettingsRepository(private val context: Context)`；`save` 在 `:544-548`，`update` 在 `:550-558`，`decode` 在 `:564-572`。
- **没有「已看过引导」这类标记**，也没有任何 `firstRun` / `onboardSeen` 字段。

**【事实】** 唯一与「是否已配置」等价的判据是 `AppSettings.configured`：`app/src/main/java/com/suixing/agent/data/Settings.kt:426`
```kotlin
val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
```
即**没配 API Key = 不能聊天**，这是当前的既有事实（也是「引导可跳过」的合法性依据）。

### 1.2 模型供应商与模型配置的数据结构

**【事实】** 全部集中在 `app/src/main/java/com/suixing/agent/data/Settings.kt`：

| 概念 | 位置 | 内容 |
|---|---|---|
| **端点（供应商）** | `Settings.kt:66-101` | `@Serializable data class ProviderConfig`：`id` / `name` / `baseUrl` / `apiKey` / `models`（`/models` 拉的**全部**）/ `enabled`（用户勾选的**子集**）/ `temperature` / `topP` / `maxTokens` / `reasoningEffort` / `ownSampling` / `aliases` / `protocol` |
| **生效三元组** | `Settings.kt:145-147` | `AppSettings.baseUrl = "https://api.deepseek.com/v1"`、`apiKey = ""`、`model = "deepseek-chat"` —— 这是**当前正在用的那一套**，切换模型就是一次 `copy()` |
| **端点清单** | `Settings.kt:293` | `val providers: List<ProviderConfig> = emptyList()`（注释说明扁平三元组保留是为了「切换模型只需一次 copy()」） |
| **协议常量** | `Settings.kt:62-64` | `PROTOCOL_OPENAI = ""`（空串 = 老配置无歧义）、`PROTOCOL_ANTHROPIC = "anthropic"`、`PROTOCOL_RESPONSES = "openai-responses"` |
| **按上游厂商分组** | `Settings.kt:108-109` | `modelGroupOf(modelId)` = `modelId.substringBefore('/', "")`，空前缀落「其他」 |
| **分组工具** | `Settings.kt:112-113` | `groupModels(ids): List<Pair<String, List<String>>>` |
| **模型切换器的数据源** | `Settings.kt:120-141` | `modelOffers(providers, model, active)`：只返回 `enabled` 非空的端点，当前模型若不在列表里会被补回 |
| **显示名** | `Settings.kt:94` | `ProviderConfig.displayModel(id)` = `aliases[id] ?: id` |
| **端点匹配** | `Settings.kt:443-444` | `activeProvider()` 按 `baseUrl`（去尾斜杠后）匹配 |
| **三级优先级** | `Settings.kt:670-689` | `AppSettings.choiceFor(conversationProviderId, conversationModel, assistant)`：会话自选 > 助手钉住 > 全局默认 |
| **最终请求参数** | `Settings.kt:652-662` | `data class LlmChoice(providerId, baseUrl, apiKey, model, protocol)` |

**【事实】** API Key 有**两处**存放，且都是普通 `String`，**明文**：
- `ProviderConfig.apiKey`（`Settings.kt:70`）
- `AppSettings.apiKey`（`Settings.kt:146`）
两者都被 `json.encodeToString(AppSettings.serializer(), ...)` 写进同一个 `settings_json`（`Settings.kt:545-546`）。

**【事实】** 老配置的迁移链在 `Settings.kt:579-580`：
```kotlin
private fun AppSettings.migrated(): AppSettings =
    withProviders().withAssistants().withMaxTokensMigration()
```
`withProviders()`（`:598-609`）把旧的扁平三元组包成唯一一个 `id = "default"`、`name = "默认"` 的 `ProviderConfig`；`withAssistants()` 在 `:616-623`。

**【事实】** **代码里没有任何供应商白名单或内置清单**。`AppSettings` 的默认三元组（DeepSeek 那一组）是唯一的「默认」。新增供应商走设置页的「新建」按钮现造一个 `id = "p" + 时间戳36进制` 的 `ProviderConfig`（`app/src/main/java/com/suixing/agent/ui/settings/SettingsScreen.kt:728-752`，`AssistChip` 标签「新建」在 `:744`）—— **不需要改任何硬编码文件**。

**【事实】** 设置页入口与「模型」子页（`app/src/main/java/com/suixing/agent/ui/settings/SettingsScreen.kt`，共 2974 行）：

| 元素 | 行号 | 说明 |
|---|---|---|
| `SettingsScreen(onOpen, onBack)` | `:163` | 设置主页 |
| `SettingsPageRoute(route, onOpen, onBack, onOpenConversation)` | `:179` | 设置子页按 route 分发 |
| 「能力与连接」分组 | `:255-261` | 权限行 → `onOpen("capability")`（`:260`）、工具行 → `onOpen("tools")`、网络与语音行 |
| `ModelSettingsPage(onBack)` | `:431` | **唯一带草稿 + 保存按钮的页** |
| 草稿 | `:440` | `var form by remember { mutableStateOf(stored) }`（注释 `:439` 说明：用 `remember` 而不是 `rememberSaveable`，否则会丢未保存的 baseUrl/apiKey/model 改动） |
| 当前端点 | `:468` | `val selected = form.providers.firstOrNull { it.id == selectedId } ?: form.providers.firstOrNull()` |
| 端点 chip 行 | `:645-780` | 每个端点一个 `FilterChip`（`:657`）+「新建」`AssistChip`（`:728-752`）+ 删除（`:511-536`） |
| 协议选择 | `:785-802` | `ProtocolPicker` |
| **接口地址输入框** | `:805-831` | `OutlinedTextField`，`label = { Text("接口地址") }`（`:818`）；`:824` 提示「填 http:// 地址时，API Key 会明文传输，仅建议在可信局域网内使用。」 |
| **API Key 输入框** | `:875-886` | `label = { Text("API Key") }`（`:882`），`visualTransformation = PasswordVisualTransformation()`（`:884`）—— **已做掩码** |
| **获取模型列表** | `:912-992` | 打 `/models` 拉清单；按钮文案 `:981`「获取模型列表」；`:992` 说明「从接口的 /models 拉一份清单，勾选后按提供方分组出现在顶部的模型切换里」；`:923` 注释「这个 App 没有手填模型的入口，`/models` 就是唯一…」 |
| 模型多选弹窗 | `:2454` | `ModelPickerDialog`，按上游厂商分组 |
| 思考强度 | `:1104-1119` | 写 `own?.reasoningEffort ?: form.reasoningEffort` |
| **保存按钮** | `:571-579` | `Core.settings.save(form.forSaving(stored))`，文案「保存改动」/「保存」 |
| **测试连接按钮** | `:580-616` | 跑 `client.complete(settings = form, messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")), tools = emptyList())`；成功文案「连接成功，模型回了：…」（`:597`），失败 `describeError(it)` |
| 落盘助手 | `:407-409` | `internal fun CoroutineScope.persist(block)` |
| 按端点落盘 | `:418-425` | `private fun CoroutineScope.persistProvider(id, block)` |

**【事实】** 草稿与落盘规则抽在纯函数文件 `app/src/main/java/com/suixing/agent/ui/settings/ModelDraft.kt`（可直接单测）：`mapProvider` `:14`、`differsOutsideEndpoint` `:29`、`endpointHandEdited` `:48`、`forSaving` `:65`（空地址时保留线上三元组）、`withContextWindow` `:81`、`withMaxTokens` `:88`、`withUpstreamDefaults` `:100`、`suggestedContextWindow` `:113-126`（按模型名猜窗口：claude 200k / gemini 1M / deepseek 64k / gpt-4·5·o1·o3·o4 128k / qwen 32k / glm·kimi·moonshot·llama 128k / mistral 32k）。

**【事实】** 顶部「模型胶囊」的取数链（`app/src/main/java/com/suixing/agent/ui/chat/ChatTopBar.kt`）：
- `reasoningWire(settings, provider, conversationLevel)` `:344-350` —— 会话自选 > 端点自有（仅当 `ownSampling`）> 全局默认
- `UnifiedSelector(...)` `:353-426` —— 模型名 + 思考强度徽章的下拉文字
- 输入区里的「生成设置」弹卡在 `app/src/main/java/com/suixing/agent/ui/chat/ChatComposer.kt:540-739`：**第 1 页 = 当前模型 + 思考强度滑杆**（`:640-704`，`REASONING_LEVELS` 在 `:699`），**第 2 页 = 全部端点的模型清单**（`:705+`）；端点没有勾选任何模型时显示「还没有启用任何模型。」+「去设置里拉取」按钮（`:727-736`）。

### 1.3 权限与前置条件

**【事实】** `app/src/main/AndroidManifest.xml` 声明的权限与组件：

| 权限 | 行号 | 申请方式 |
|---|---|---|
| `INTERNET` | `:5` | 安装即有 |
| `FOREGROUND_SERVICE` / `_DATA_SYNC` / `_SPECIAL_USE` | `:6-8` | 安装即有 |
| `SYSTEM_ALERT_WINDOW` | `:9` | **特殊权限**，跳系统「显示在其他应用上层」 |
| `POST_NOTIFICATIONS` | `:10` | 运行时弹框 |
| `RECEIVE_BOOT_COMPLETED` | `:11` | 安装即有 |
| `RECORD_AUDIO` | `:12` | 运行时弹框（用到才问） |
| `QUERY_ALL_PACKAGES` | `:13` | 安装即有 |
| `SCHEDULE_EXACT_ALARM` | `:17` | 安装即有（注释：声明但从不强求，闹钟会不准一两分钟） |
| `MANAGE_EXTERNAL_STORAGE` | `:19` | **特殊权限**，跳系统「所有文件访问权限」 |
| `READ_EXTERNAL_STORAGE` (maxSdk 32) / `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | `:21-24` | 安装即有 |
| `android:usesCleartextTraffic="true"` | `:37` | 允许 http 明文（配合 `:824` 的告警） |

组件：`MainActivity`（`:40-52`，`singleTask` + LAUNCHER）、`SuixingAccessibilityService`（`:54-65`，`BIND_ACCESSIBILITY_SERVICE` + `@xml/accessibility_service_config`）、`AgentForegroundService`（`:67-70`，`dataSync`）、`SchedulerService`（`:75-82`，`specialUse`）、`FloatConsoleService`（`:86-93`，`specialUse`）、`BootReceiver`（`:104-113`，BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED）、`rikka.shizuku.ShizukuProvider`（`:115-121`）。

**【事实】** **没有电池优化白名单**。全项目 grep `battery|IGNORE_BATTERY|ACTION_REQUEST_IGNORE` 只命中「读电量百分比」的工具实现（`app/src/main/java/com/suixing/agent/tools/SystemTools.kt:140-148`）与插件条件字段，**没有任何请求忽略电池优化的代码**。→ 引导页不应出现「电池优化」这一项。

**【事实】** 能力页 `app/src/main/java/com/suixing/agent/ui/capability/CapabilityScreen.kt`（769 行）就是现成的「权限中心」：

| 卡片 | 行号 | 状态来源 | 「去开启」的动作 |
|---|---|---|---|
| `CapabilityScreen(onBack)` | `:78` | `Core.capabilities.state.collectAsState()`（`:79`） | — |
| 标准档（默认） | `:164-173` | 恒为已装上 | 无需开启 |
| 无障碍 | `:175-183` | `state.accessibility` | `Core.capabilities.openAccessibilitySettings()`（`:182`）→ 跳系统无障碍页 |
| Shizuku（ADB 级） | `:185-213` | `state.shellReady` / `state.shizukuInstalled` | 未装 → `openShizukuStore(context)`（`:195`）；已装但服务没起 → `Core.capabilities.requestShizuku()`（`:204`）否则 `openShizukuApp()`（`:207`）+ 提示「Shizuku 服务未运行，已为你打开 Shizuku 应用：…」（`:208-209`） |
| Root | `:215-231` | `state.rootReady` | `Core.capabilities.probeRoot()`（`:227`），探测中按钮禁用（`:223`） |
| 悬浮窗控制台 | `:233-265` | `FloatConsoleService.canDrawOverlays(context)`（`:90`） | `overlayLauncher.launch(FloatConsoleService.overlayPermissionIntent(context))`（`:254-256`），回调重读授权（`:93`） |
| 所有文件访问权限 | `:267-314` | `Environment.isExternalStorageManager()`（`:99`），仅 API ≥ R（`:85`） | `allFilesAccessIntents(context)` 逐个尝试（`:292-294`），回调重读（`:104`） |
| 「这四档权限分别能做什么」说明 | `:316-330+` | — | 折叠展开 |

**【事实】** 缺权限时的 UI 提示有**两套现成范式**：
1. 顶部提示条（`:130-149`）：`Surface(color = errorContainer, contentColor = onErrorContainer)` + `ic_circle_alert` 图标 + `bodySmall` 文案，变量 `notice`。
2. 卡片自身（`CapabilityCard`）：`title` / `summary` / `granted` / `lines`（能力清单或「未授予：…」）/ `actionLabel`（`if (granted) "打开设置" else "去开启"`）/ `onAction`。
另有 `CapabilityHero(state)`（`:128`）说明「机器现在给到了哪一档」，和 `CapabilityTierPicker`（`:154-161`）让用户选**想要**的档位（写 `settings.preferredCapability`）。

### 1.4 主入口与导航结构

**【事实】** `app/src/main/java/com/suixing/agent/SuixingApplication.kt`（38 行）是唯一的 Application。

**【事实】** 唯一 Activity 是 `app/src/main/java/com/suixing/agent/MainActivity.kt`（145 行，`class MainActivity : ComponentActivity` 在 `:27`）：
- `onCreate` `:45`；启动窗口主题 `setTheme(...)` `:53-59`（读 `Core.themeMode` 同步快照）；`enableEdgeToEdge()` `:62`；`WindowCompat.setDecorFitsSystemWindows(window, false)` `:66`；`transparentNavigationBar()` `:67`（实现 `:141-144`）。
- `requestNotificationPermission()` `:69`（实现 `:117-127`）—— **在 setContent 之前无条件调用**。
- `setContent { ... }` `:70-101`：收集 `Core.settings.flow`（`:71`）算 `dark`（`:73-77`），`LaunchedEffect(dark)` 切 `SystemBarStyle`（`:83-101`）。
- `SuixingTheme(darkTheme = dark, dynamicColor = settings.paletteMode == 1, fontScale, cornerScale)` `:102-107` → `SuixingApp()` `:108`。

**【事实】** 导航全在 `app/src/main/java/com/suixing/agent/ui/SuixingApp.kt`（1352 行，`fun SuixingApp()` 在 `:206`）：

| 机制 | 行号 | 说明 |
|---|---|---|
| `rememberNavController()` | `:208` | 有 `androidx.navigation:navigation-compose:2.8.4`（`app/build.gradle.kts:89`） |
| 当前路由 | `:211` | `backStackEntry?.destination?.route?.substringBefore('?') ?: "empty"` |
| 抽屉切换 `open(target)` | `:277-291` | `navigate` + `popUpTo(startDestination){saveState}` + `launchSingleTop` + `restoreState` |
| 设置子页压栈 `push(target)` | `:293` | 普通 `navigate` |
| 「回聊天」`showChat()` | `:296` | `popBackStack("empty", inclusive = false)` |
| 返回 `goBack()` | `:315` | `popBackStack()` |
| `ModalNavigationDrawer` | `:454-523` | `gesturesEnabled = route == "empty"`（`:461`，**只有会话页能侧滑出抽屉**）；遮罩 45% 黑（`:463`） |
| **chat 常驻在 NavHost 之外** | `:531-533` | `ChatScreen(...)` 永远在组合里，NavHost 只画覆盖层（注释 `:525-530` 说明这是为了避免「返回像重新加载」） |
| `NavHost` | `:536-689` | `startDestination = "empty"`（`:539`） |
| 转场 | `:573-617` | 进页整幅 `slideInHorizontally` 280ms（`NAV_PUSH_MS = 280`，`:189`）+ 旧页左退 1/4 屏；**不用淡化**（注释明确说用户否过残影） |
| 路由全集 | `:620-688` | `empty`(:620) / `files`(:621) / `tasks`(:622) / `schedules`(:625) / `settings`(:626) / `settings/{model,conversation,appearance,network,data,chats,usage,perf}`(:636-647) / `capability`(:663) / `tools`(:664) / `about`(:665) / `extension`(:667) / `profile`(:671) / `assistants`(:677) |
| 返回键 | `:700` | `BackHandler(enabled = drawerState.isOpen && route == "empty")` |

抽屉底部行（`ui/SuixingApp.kt`）：助手 `:969`、定时任务 `:975`、文件 `:981`、悬浮窗控制台 `:990`、设置 `:1004`。**抽屉里没有「能力」行** —— 能力页从设置页进（`ui/settings/SettingsScreen.kt:260`）。

**【事实】** 主题与可复用视觉件：

| 组件 | 位置 | 说明 |
|---|---|---|
| 深色配色 | `app/src/main/java/com/suixing/agent/ui/theme/Color.kt:17-60` | `primary = #8FD6B6`、`background = #0F1412`、`surfaceContainerLow #121815` / `surfaceContainer #171E1A` / `surfaceContainerHigh #1E2621` / `surfaceContainerHighest #262F29`、`outlineVariant = #333C36`（1dp 发丝描边） |
| 浅色配色 | `Color.kt:62-111` | `background = #FFFFFF`、`primary = #1F6B4C`、`surfaceContainer #F1F5F2` / `surfaceContainerHigh #E9EFEB` |
| 状态色 | `Color.kt:125-132` | `enum class Tone { OK, WARN, OFF, INFO }` + `Tone.color(dark)`，注释 `:115-124` 说明是按实测对比度定的 |
| 主题入口 | `ui/theme/Theme.kt:21-59` | `SuixingTheme(darkTheme, dynamicColor, fontScale, cornerScale)`；`dynamicColor` 走 `dynamicDarkColorScheme`（`:30-34`）；`LocalDarkTheme` `:19` |
| 圆角 | `ui/theme/Type.kt:28-37` | `small 12dp` / `medium 16dp` / **`large 20dp`** / `extraLarge 28dp`，随 `cornerScale` 缩放 |
| 品牌标记 | `ui/components/Logo.kt:71-95` | `SuixingMark(size, modifier, featherColor)` —— 白羽 + 品牌渐变圆盘 |
| **引导页标记** | `ui/components/Logo.kt:100-122` | `SuixingOrb(size = 132.dp)`，注释 `:97-99` 原文：**「The mark inside a soft radial glow, sized for empty states and onboarding.」** —— 项目里已经为 onboarding 预留了这个视觉件 |
| 空态组件 | `ui/components/Common.kt:1079-1108` | `EmptyState(title, subtitle, orbSize, content)`，内部用 `SuixingOrb` |
| 其它可复用件 | `Common.kt` | `SuixingCard:172`、`SectionCard:205`、`SoftIcon:239`、`StatusPill:257`、`SettingRow:304`、`SegmentedPill:374`、`SuixingTopBar:427`、`PageTopBar:588`、`PressPill:853` |

> **⚠️ 与任务书不一致的一处事实**：任务书把视觉基准写成「Aether 暖黑底 `#151619` + 柔紫主色 `#C0AEFF` + 米白正文 `#F3F1EC`」，但**本仓库的品牌色已经换成墨绿**（`Color.kt:7-16` 注释：「品牌色是墨绿：一支白羽该配的底色是砚台和竹叶，不是柔紫」）。本方案一律以**代码里的实际主题**为准：引导页用 `MaterialTheme.colorScheme`（深色 `#0F1412` 底 / `#8FD6B6` 主色），**不引入紫、不引入 `#151619`**。Aether 的暖黑+柔紫只作为「排版与间距的参照」。

### 1.5 参考：Aether 扶摇的引导页做法

**【事实】** Aether 官方文档（安装页）明确写了引导流程的**内容**，但**没有**描述它的界面形态：

> 「4. **打开 Aether 并完成引导** 安装结束后点击 打开 。按引导流程授予权限并配置首个 LLM 提供商。」
> —— 来源：<https://aether.baimoqilin.com/docs/zh/installation>（2026-07-31 发布，抓取自该页正文）

同页的「Android 权限」小节：
> 「Aether 会在引导流程或运行时请求以下权限：INTERNET / POST_NOTIFICATIONS / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED / REQUEST_INSTALL_PACKAGES，Termux 与 Shizuku 权限为可选。」
> 「Aether 不会请求任何存储权限（READ_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE 或 MANAGE_ALL_FILES）。所有应用数据仅保存在 Aether 的私有应用目录中。」
> —— 同上

**【事实】** Aether 的文档目录里有独立的「提供商」页（`设置 → 提供商`）与「模型与提供商」核心功能页（`https://aether.baimoqilin.com/docs/zh/installation` 侧栏导航），说明**供应商配置是一个独立的一等页面**，而不是聊天的附属弹窗。

**【事实】** Aether README（`https://raw.githubusercontent.com/Zhou-Shilin/Aether/main/README.md`）的定位与视觉：
- 副标题「**Soar with local AI.** A stunning, localized, highly extensible general-purpose AI Agent for Android, iOS and macOS.」
- 首屏四张截图依次是 `public/welcome.jpg` / `agentmode.jpg` / `chat.jpg` / `research.jpg` —— **welcome 在第一位**。
- 核心能力含「Built-in Alpine VM」（自带 Alpine Linux 环境，开箱跑命令）与「Extensible Host Control」（可选 Shizuku / Termux 集成）。
- 视觉参照：README 的 Visuals 段落点名 ChatGPT、Codex CLI/App、Gemini、Poco Agent。

**【推断】** Aether 的首启引导**很可能就是「权限 + 首个 LLM 提供商」这两件事**，而不是多页轮播：
- 依据：安装页把引导描述成单一动作句「按引导流程授予权限并配置首个 LLM 提供商」；权限表里 Termux/Shizuku 明确标注「可选」。
- **不确定处**：Aether 是否有多页 UI、是否有欢迎页截图、页序如何 —— 公开文档里查不到。`https://aether.baimoqilin.com/docs/quickstart` 与 `/docs/llms.txt` 两次 `web_fetch` 均 30s 超时，未能取得。

**【推断】** 视觉语言可借用的部分（**这些是推断，不是抓到的**）：Aether 的排版参照（正文 15/24、17/28 的行高 —— 本项目 `ui/theme/Type.kt:43` 注释里已写明「行高按 Aether 的取值放大」）、暖黑低饱和底 + 单色强调的克制用色、三层表面阶梯 + 发丝描边、20dp 圆角、edge-to-edge。本项目这些**已经落地**，引导页沿用即可。

---

## 2. 方案（建议部分）

### 2.1 总体结构

**【建议】** 引导 = **一条覆盖层路由 + 内部 5 页分页容器**，不是 5 条 NavHost 路由。

```
onboarding (新路由, 覆盖层)
  └─ OnboardingScreen 内部 5 页 state，页内切换用 AnimatedContent 横滑
```

理由：
1. 引导是**一次性的线性流程**，用 NavHost 路由会让系统返回键逐页回退（用户会以为能退出），而用内部 state 时返回键直接退出整个引导。
2. 与既有结构契合：`ui/SuixingApp.kt:536` 的 NavHost 里所有覆盖层页都是「一条路由 = 一个整页」，引导再拆成 5 条路由会让 `open`/`push`/`goBack` 的语义（`:277-315`）变复杂。
3. 复用 `ui/SuixingApp.kt:573-617` 已有的位移转场规格（280ms `FastOutSlowInEasing` 整幅滑入）——**引导页内部翻页沿用同一套时长与曲线**，视觉上与 App 其它页面一致。

**【建议】** 首启拦截点放在 `ui/SuixingApp.kt`（**不是** `MainActivity`）：
- `MainActivity.kt:108` 的 `SuixingApp()` 之前塞一个 `if (showOnboarding) OnboardingScreen(...) else SuixingApp()` 会让整套 NavHost/抽屉被换掉，**问题更大**（chat ViewModel 会被重建，且 `ModalNavigationDrawer` 的状态会丢）。
- 正确做法：保留 `SuixingApp()` 常态运行，在 `NavHost` **之上**盖一层全屏 `OnboardingScreen`（与 `pending` 审批弹窗同一层级，`ui/SuixingApp.kt:708-709` 已有这个模式的先例）。盖在 NavHost 之上 ⇒ 抽屉、chat、设置页都还在底下，**引导结束后直接掀开，不重建任何东西**。

**【建议】** 引导层的「路由」纪律：
- 盖在 NavHost 之上 ⇒ 抽屉手势被它吃掉（引导页不画 `ModalNavigationDrawer`，底下的抽屉自然不会被拉开）。
- 系统返回键：引导页自己 `BackHandler` 吞掉（第一页 = 退出引导 → 标记为「看过」，直接进主界面）。

### 2.2 逐页设计

**共同规则**：
- 顶部：**不要**标题栏副标题（`ui/capability/CapabilityScreen.kt:118-120` 记录了用户的这条要求：「任何页都不要副标题」）。
- 底部：一枚主按钮 + 一枚「稍后再做」文本按钮（第 2/3/4 页可点；第 1/5 页「跳过」放在左上或底部文本按钮）。
- 页面切换：横向滑动（`AnimatedContent` + `slideInHorizontally`），与 App 既有转场同规格（`ui/SuixingApp.kt:573-588`）。
- 背景：`MaterialTheme.colorScheme.background`（`ui/capability/CapabilityScreen.kt:112-116` 已有「根节点必须自己铺不透明底」的先例，转场里不淡入会露上一页残影）。
- 圆角：`MaterialTheme.shapes.large`（20dp，`ui/theme/Type.kt:34`）。
- 描边：`BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`，照 `ui/chat/ChatTopBar.kt:496` 的既有写法。

---

#### 第 1 页 · 欢迎与定位

| 项 | 内容 |
|---|---|
| **标题** | 「随行」 |
| **副标题/正文** | 「你给它一个模型接口，它就在这台手机上替你动手：读屏、点击、翻文件、跑命令。」 |
| **视觉** | 居中 `SuixingOrb(size = 132.dp)`（`ui/components/Logo.kt:100-122`，注释已写明「sized for empty states and onboarding」）+ 下方标题 + 一段正文 + 三条能力要点（用 `SoftIcon` + `bodyMedium` 的三行列表，不用图） |
| **交互** | 单向「下一步」主按钮；**不**放「跳过」在这页的主位（首屏给一个明确的「开始配置」更自然），但右上角保留一个「跳过」文字按钮（可跳过） |
| **可跳过** | ✅ 可跳过（用户拍板：首启不强制）。跳过 = 写「已看过」标记 + 直接进聊天页，**什么都不配置**（`AppSettings.configured` 仍为 false，用户自己来配） |
| **复用** | `EmptyState`（`ui/components/Common.kt:1079-1108`）已实现「Orb + 标题 + 副标题 + 可选按钮」的骨架；`EmptyHero`（`ui/chat/ChatTopBar.kt:452-515`）的文案「说一句话，它就能读屏、点击、翻文件、跑命令。」是现成可借的措辞 |

---

#### 第 2 页 · 选供应商 · 填接口与 Key

| 项 | 内容 |
|---|---|
| **标题** | 「接上你的模型」 |
| **正文** | 「随行不绑定任何厂商。填一个 OpenAI 兼容的接口地址和 Key 就能用；不确定就用下面的预设。」 |
| **交互** | ① **供应商预设芯片行**（横向 FlowRow，`FilterChip`）：DeepSeek / 智谱 GLM / OpenAI 兼容（自填）/ 本机 Ollama / + 4 个常见厂商（见 2.3）。选中即把 **baseUrl、模型名、Key 提示** 填进下面三个输入框，**Key 框留空**（不预置任何真实 Key）。<br>② **接口地址**输入框（`OutlinedTextField`，`label = "接口地址"`），预填选中预设的 baseUrl。<br>③ **API Key**输入框（`label = "API Key"`，`visualTransformation = PasswordVisualTransformation()`，照 `ui/settings/SettingsScreen.kt:884`）。<br>④ **协议**选择（仅当预设需要时才出现，见 2.3 的「协议」列）。<br>⑤ **「测试连接」按钮**（次要样式）：失败**只提示、不拦人**（用户拍板）；成功则在按钮上方显示一行绿色状态。<br>⑥ 主按钮「下一步」。 |
| **可跳过** | ✅ 可跳过。跳过时**保留已填内容**：填了 Key 就照样保存（用户可能只是路过、顺手填了），不填就不存。 |
| **复用** | 三段逻辑**全部**取自设置页，不重写：<br>· **草稿态**：`ui/settings/ModelDraft.kt:14` `mapProvider` / `:65` `forSaving`（空地址时保留线上三元组）<br>· **落盘**：`ui/settings/SettingsScreen.kt:571-579` 的 `Core.settings.save(form.forSaving(stored))`<br>· **测试连接**：`ui/settings/SettingsScreen.kt:580-616` 的 `client.complete(settings = form, messages = [ChatMessage.user("你好，请只回复两个字：可用")], tools = emptyList())` + `describeError(it)`<br>**建议抽公共函数** `testConnection(settings): TestOutcome`（放 `data/` 或 `net/`），引导页与设置页共用同一份，**避免两份行为漂移**。 |
| **落盘时机** | **建议：进第 3 页时**就落盘（不是等第 5 页）。理由：用户填完 Key 可能直接杀掉 App；早落盘 = 不丢。落盘用 `forSaving` 保住「空地址不覆盖线上」这条既有规则。 |

---

#### 第 3 页 · 选模型与思考强度

| 项 | 内容 |
|---|---|
| **标题** | 「选一个模型」 |
| **正文** | 「同一个接口下可能有几十个模型。先挑一个常用的；模型清单来自接口自己的 /models。」 |
| **交互** | ① **模型列表**：按上游厂商分组的清单（复用 `modelOffers`，`data/Settings.kt:120-141` + `groupModels`，`:112-113`）。若该端点的 `/models` 还没拉过，**引导页提供一个「拉取模型列表」按钮**（复用 `ui/settings/SettingsScreen.kt:912-992` 的同一段逻辑，建议抽成 `fetchModels(baseUrl, apiKey, protocol)` 公共函数），拉到的清单勾选后写入 `ProviderConfig.models` + `ProviderConfig.enabled`。<br>② **模型单选**：点一个即设为当前 `AppSettings.model`。<br>③ **思考强度**：一枚 `SegmentedPill` 或滑杆，档位取 `REASONING_LEVELS`（`ui/components/Common.kt`），默认「跟随服务端」；选择写 `AppSettings.reasoningEffort`。<br>④ 主按钮「下一步」。 |
| **可跳过** | ✅ 可跳过。跳过 ⇒ 保留 `AppSettings.model` 的现值（首次安装时是 `deepseek-chat`，`data/Settings.kt:147`），**不写** `enabled`（模型切换器里就没有这个模型，但 `modelOffers` 的 `:129-139` 会把当前模型补回列表，用户仍能切）。 |
| **复用** | · 模型分组：`data/Settings.kt:108-113`、`:120-141`<br>· 思考强度档位：`REASONING_LEVELS`（同 `ui/chat/ChatComposer.kt:699` / `ui/settings/SettingsScreen.kt:1104-1119`）<br>· 思考强度生效链：`ui/chat/ChatTopBar.kt:344-350` `reasoningWire`（会话 > 端点 > 全局）——**引导页只写全局默认值**，不要写端点级 |

---

#### 第 4 页 · 权限（按用户旅程讲，不照搬技术清单）

**【建议】** 这页**不列权限名**，而是列「你想让随行做什么 → 需要什么 → 缺了会怎样」。每条一行三段式：`**要做什么**` / `**需要**`（用日常话，如「让它看屏幕并代替你点」）/ `**缺了会怎样**`（具体后果，不说「无法使用」）。

| 想做的事 | 需要（日常话） | 缺了会怎样 | 「去开启」的动作（**全部复用现有实现**） |
|---|---|---|---|
| **让它替你动手**（读屏、点按钮、输入文字） | 「允许它操作这台手机」 | 「它只能跟你聊天，看不见屏幕也点不了按钮」 | `Core.capabilities.openAccessibilitySettings()`（`ui/capability/CapabilityScreen.kt:182`）→ 跳系统「无障碍」设置页 |
| **让它在后台把事做完** | 「允许它发通知」 | 「任务做完了你不知道，长任务还可能被系统杀掉」 | 通知：`POST_NOTIFICATIONS` 运行时弹框（`MainActivity.kt:117-127`）。**注意**：首启时 `MainActivity.kt:69` 已经弹过一次 ⇒ 引导页这一条要**读当前授权状态**，已授权就显示「已开启」，未授权则**引导页自己再申请一次**（用 `rememberLauncherForActivityResult(RequestPermission())`，照 `ui/chat/ChatScreen.kt:366-367`） |
| **让它随时能被你叫停** | 「允许它显示一个悬浮球」 | 「它跑起来时屏幕上没有球，你没法中途喊停、也没法看它到哪一步了」 | `FloatConsoleService.overlayPermissionIntent(context)`（`ui/capability/CapabilityScreen.kt:254-256`）→ 跳系统「显示在其他应用上层」；**回调重读**（`:93`） |
| **让它翻你的文件** | 「允许它访问这台手机的所有文件」 | 「它只能看见应用自己的私有目录，存进去的东西你在文件管理器里找不到」 | `allFilesAccessIntents(context)`（`ui/capability/CapabilityScreen.kt:292-294`）→ 跳系统「所有文件访问权限」；回调重读（`:104`） |
| **（可选）让它能执行命令** | 「给它更高一档的系统权限」 | 「它做不了需要命令行的活，比如装包、改系统设置」 | Shizuku：`Core.capabilities.requestShizuku()` / `openShizukuApp()` / `openShizukuStore(context)`（`ui/capability/CapabilityScreen.kt:194-211`）；Root：`Core.capabilities.probeRoot()`（`:227`） |

**【建议】** 这页的三条硬约束：
1. **不出现「电池优化」**——【事实】代码里根本没有请求忽略电池优化的逻辑（见 1.3），写上去就是引导用户去做一件 App 不需要的事。
2. **不出现「存储权限」**——【事实】本 App 的存储策略与 Aether 一致：数据在私有目录，文件能力靠 Shizuku / root / 所有文件访问 三档（`ui/capability/CapabilityScreen.kt:316-330` 的说明区已经写明这个口径）。Aether 官方文档也明确「不会请求任何存储权限」（见 1.5）。
3. **全部可跳过、可拒绝、且拒绝后不重复骚扰**。缺权限的提示交给能力页（`ui/capability/CapabilityScreen.kt`）这个常驻入口，引导页只做**一次性告知**。

**【建议】** 交互：每条一个 `CapabilityCard` 形态的卡片（复用 `ui/capability/CapabilityScreen.kt` 的 `CapabilityCard` 形状：`title` / `summary` / `granted` / `lines` / `actionLabel`），「已开启」的用 `StatusPill`（`ui/components/Common.kt:257`）+ `Tone.OK`（`ui/theme/Color.kt:125-132`）标绿。
主按钮「下一步」；次按钮「以后再说」。

---

#### 第 5 页 · 开始使用

| 项 | 内容 |
|---|---|
| **标题** | 「好了」 |
| **正文** | 一段**回顾式**总结（不是又一份清单）：<br>「接口：DeepSeek（`https://api.deepseek.com/v1`）· 模型：`deepseek-chat` · 思考强度：跟随服务端」<br>「权限：只开了通知。其余的以后在「设置 → 权限」里随时能加。」 |
| **交互** | ① 一张 `SuixingCard` 摘要卡（`ui/components/Common.kt:172`），三行 `SettingRow`（`:304`）。<br>② 一条浅色提示：「没配 Key 也能先逛逛 —— 聊天会等你配好接口。」**（这一句是首启可跳过的合法性说明：没 Key 不是错误状态。）**<br>③ 主按钮「开始使用」→ 写「已看过」标记 → 掀掉引导层 → 落在聊天页。<br>④ 次要入口：「回上一页改配置」。 |
| **可跳过** | ✅ 可跳过（等价于「开始使用」）。 |

---

### 2.3 内置供应商预设（新增，代码里目前一个都没有）

**【事实】** 现状：**没有任何内置供应商清单**（见 1.2），只有 `AppSettings` 的默认三元组 DeepSeek（`data/Settings.kt:145-147`）。所以这一节**全部是新增**。

**【建议】** 挑选依据（按这四条筛，缺一条就不进预设）：
1. **协议一致**：是标准 `Authorization: Bearer` + OpenAI 的 `/chat/completions` 形状 —— 现有代码默认协议就是 `PROTOCOL_OPENAI`（`data/Settings.kt:62`），非标准鉴权（要额外 header 的）一律不预置。
2. **国内可直连**：不需要用户额外配代理/自建网关。
3. **有稳定可用的默认模型 ID**：预填的模型名不能是「昨天刚下线的那个」。
4. **不强制**：任何一条不满足，就退回到「自填地址」这条路（预设只是省打字，不是必经之路）。

**【待核】** 下表的 baseUrl / 模型 ID **来自公开文档的印象值，必须在实现前逐条回官方文档核对**；只有 DeepSeek 一行是仓库里查得到的（`data/Settings.kt:145` = `https://api.deepseek.com/v1`）。

| 预设 | baseUrl【待核】 | 默认模型【待核】 | 协议 | Key 提示 | 备注 |
|---|---|---|---|---|---|
| **DeepSeek** | `https://api.deepseek.com/v1` | `deepseek-chat` | OpenAI | 「在 platform.deepseek.com 申请，形如 `sk-…`」 | 仓库默认（`data/Settings.kt:145-147`），**唯一可核的一行** |
| **智谱 GLM** | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash`（更稳）/ `glm-4-plus` | OpenAI | 「在 open.bigmodel.cn 的 API Key 页申请」 | 路径是 `/v4` 不是 `/v1`，**别写错** |
| **OpenAI 兼容（自填）** | *留空* | *留空* | OpenAI | 「聚合网关 / 公司内网 / OneAPI 这类，填它给的地址」 | 这一档**不预填任何东西**，纯粹是「其它都试过了」的兜底 |
| **本机 Ollama** | `http://127.0.0.1:11434/v1` | *留空，让用户自己拉清单* | OpenAI | 「Ollama 默认不校验 Key，留空即可」 | **提示要点**：手机上要填**电脑的局域网 IP**（如 `http://192.168.1.20:11434/v1`），不是 `127.0.0.1` —— 这一点必须写进输入框下方的说明，否则新手必然踩坑 |
| **月之暗面 Kimi** | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | OpenAI | 「在 platform.moonshot.cn 申请」 | 长上下文，K 码系列也在同一地址 |
| **通义千问（DashScope 兼容模式）** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | OpenAI | 「在 bailian.console.aliyun.com 申请」 | 官方 OpenAI 兼容入口是 `/compatible-mode/v1` |
| **硅基流动 SiliconFlow** | `https://api.siliconflow.cn/v1` | *留空，拉 /models 自选* | OpenAI | 「在 cloud.siliconflow.cn 申请」 | 聚合站：模型多、适合「懒得挑模型」的人 |
| **Anthropic Claude** | `https://api.anthropic.com/v1` | `claude-sonnet-4-5` | **Anthropic**（`PROTOCOL_ANTHROPIC`，`data/Settings.kt:63`） | 「在 console.anthropic.com 申请，形如 `sk-ant-…`」 | 唯一走**非 OpenAI 协议**的预设，因为代码里已经有这条协议分支（`data/Settings.kt:63` + `ui/settings/SettingsScreen.kt:785-802` 的 `ProtocolPicker`）；预选它时**自动切协议** |

**【建议】** 不预置的（并说明理由）：
- **Google Gemini**：官方有 OpenAI 兼容端点，但鉴权要额外 header（不是纯 `Bearer`），违反挑选依据第 1 条 ⇒ 走「自填」那档。
- **腾讯混元 / 百度千帆**：接口形态随版本变动较频繁，且千帆的 `/v2` 与混元的 `/v1` 路径易错 ⇒ 宁可少写一个错地址，也不给错地址。**若要加，先按上面 4 条逐条核过再进表。**

**【建议】** 预设表的存储形态：新建 `app/src/main/java/com/suixing/agent/ui/onboarding/ProviderPresets.kt`，一个
```kotlin
data class ProviderPreset(
    val id: String, val label: String, val baseUrl: String, val model: String,
    val protocol: String, val keyHint: String, val note: String = "",
)
```
的 `List` 常量。**不要**塞进 `data/Settings.kt`（那是运行时状态，不是静态表）。预设表是**代码常量**，不进 DataStore。

### 2.4 首启与「再次进入」的触发条件、标记存哪

**【建议】** 标记：`AppSettings` 新增一个字段 `onboardingSeen: Boolean = false`。
- **存哪**：`data/Settings.kt` 的 `AppSettings`（约 `:293` 附近）→ 随 `settings_json` 一起落盘。**理由**：项目里**只有** DataStore 一套持久化（见 1.1），为此新开一个 SharedPreferences 等于引入第二套持久化范式，与「不重复造轮子」相悖。
- **不放哪**：`ProviderConfig`（那是端点）、`CapabilityState`（那是权限）、单独的 `SharedPreferences`（不存在）、`BuildConfig`（不能改）。
- **默认值的坑**：`AppSettings` 的字段都有默认值，老 JSON 读出来是 `false` ⇒ **老用户升级上来会被当成「没看过引导」**。**必须处理**：在 `data/Settings.kt:579-580` 的迁移链里加一步（和 `withProviders()`（`:598-609`）、`withAssistants()`（`:616-623`）同款），例如 `withOnboardingSeen()`：当 `providers` 为空（说明是刚从扁平三元组迁来的老配置）时**保持 false**，但当**检测到已有 apiKey**（老用户本来就配好了）时直接判为 `true`，**不打扰老用户**。这是本次迁移里最容易被忽略的一条。

**【建议】** 触发条件：

| 场景 | 条件 | 行为 |
|---|---|---|
| **首启（全新安装）** | `settings.onboardingSeen == false` **且** `settings.apiKey.isBlank()` | 启动后盖上引导层 |
| **首启但已配过 Key** | `settings.onboardingSeen == false` **且** `settings.apiKey.isNotBlank()` | **不显示引导**（老用户 / 恢复了备份）；同时把 `onboardingSeen` 顺手置 true（迁移里做） |
| **用户主动跳过** | 点「跳过」/ 系统返回键 | 写 `onboardingSeen = true`，**不写任何配置**，进聊天页 |
| **用户走完** | 第 5 页「开始使用」 | 写 `onboardingSeen = true` + 前面各页的配置，落聊天页 |
| **再次进入** | 设置页新增一行「重新看一遍引导」 | **不写标记**，直接 `push("onboarding")` 走同一条路由 ⇒ 引导层是普通覆盖层页，退出即回设置页 |

**【建议】** 「再次进入」的入口落点：`ui/settings/SettingsScreen.kt` 的「能力与连接」分组（`:255-261`）**下面新起一行**，标题「引导」/ 说明「重新看一遍首次配置」/ 副标题「供应商 · 模型 · 权限」。**不进抽屉** —— 抽屉（`ui/SuixingApp.kt:967-1006`）已经 5 行了，且引导是低频操作。

**【建议】** 引导层的展示时机：写 `onboardingSeen` 的**唯一时机**是「跳过」和「走完」两处；**「再次进入」时任何一页的改动都应该落盘**（因为它是一个普通设置页），但**不应该**把 `onboardingSeen` 改回去。

### 2.5 实现落点清单

> 全部是**建议新增/修改**的文件，本任务不改任何代码。

#### 新增（5 个文件，都在 `app/src/main/java/com/suixing/agent/ui/onboarding/`）

| 文件 | 职责 | 备注 |
|---|---|---|
| `OnboardingScreen.kt` | 容器：5 页分页状态机、顶/底栏、跳过与写标记 | 页面切换用 `AnimatedContent` + `slideInHorizontally`（规格照 `ui/SuixingApp.kt:573-588`：280ms / `FastOutSlowInEasing` / 整幅） |
| `OnboardingWelcome.kt` | 第 1 页 | 复用 `SuixingOrb`（`ui/components/Logo.kt:100-122`）与 `EmptyState`（`ui/components/Common.kt:1079-1108`） |
| `OnboardingProvider.kt` | 第 2 页 | 复用 `ModelDraft` 的 `mapProvider`/`forSaving`；「测试连接」改调公共 `testConnection()` |
| `OnboardingModel.kt` | 第 3 页 | 复用 `modelOffers`/`groupModels`/`REASONING_LEVELS` |
| `OnboardingCapability.kt` | 第 4 页 | 复用 `Core.capabilities.state` + 四个 `open*`/`request*`/`probe*` |
| `OnboardingDone.kt` | 第 5 页 | 摘要卡 + 「开始使用」 |
| `ProviderPresets.kt` | 2.3 的预设表 | 静态常量 |
| `OnboardingDraft.kt` | 引导页的草稿态与落盘规则（纯函数，可单测） | 风格照 `ui/settings/ModelDraft.kt` |

#### 修改

| 文件 | 位置 | 改什么 |
|---|---|---|
| `app/src/main/java/com/suixing/agent/data/Settings.kt` | `AppSettings` 字段区（约 `:293` 旁） | 加 `val onboardingSeen: Boolean = false` |
| `app/src/main/java/com/suixing/agent/data/Settings.kt` | `migrated()` `:579-580` | 加一步 `withOnboardingSeen()`：**老用户（有 apiKey）直接判 true，不打扰** |
| `app/src/main/java/com/suixing/agent/data/Settings.kt` | 新增 `Secrets.kt`（或同文件） | Key 的加密/独立存储，见 2.7 |
| `app/src/main/java/com/suixing/agent/ui/SuixingApp.kt` | `NavHost` 注册区 `:620-688` | 加 `composable("onboarding") { OnboardingScreen(onFinish = ...) }`（供「再次进入」用） |
| `app/src/main/java/com/suixing/agent/ui/SuixingApp.kt` | `NavHost` **之上**（与 `:708-709` 的审批弹窗同层） | 盖 `if (needOnboarding) OnboardingLayer(...)` —— 盖在 NavHost 之上 ⇒ 掀开后 chat/抽屉/设置页都还在，**零重建** |
| `app/src/main/java/com/suixing/agent/ui/SuixingApp.kt` | `:461` `gesturesEnabled` | 引导层盖着时抽屉手势天然被吃掉（引导层不画 `ModalNavigationDrawer`），确认无误即可，无需改 |
| `app/src/main/java/com/suixing/agent/ui/settings/SettingsScreen.kt` | 「能力与连接」分组 `:255-261` 之下 | 加一行「引导 / 重新看一遍首次配置」→ `onOpen("onboarding")` |
| `app/src/main/java/com/suixing/agent/MainActivity.kt` | `:69` `requestNotificationPermission()` | **建议改**：把通知权限弹框**推迟**到引导页第 4 页（或引导结束后）统一申请。否则用户还没看懂 App 就要先被系统弹框打断，且首启引导页里那条「允许它发通知」会显示成「未开启」而实际已经弹过框。**保守做法**：保留现状，引导页第 4 页读实时授权状态（已授权就显示「已开启」），不改 `MainActivity`。 |
| `app/src/main/java/com/suixing/agent/ui/capability/CapabilityScreen.kt` | `CapabilityCard`（`:164-331` 区间内） | 若 `CapabilityCard` 是 private，需提成 internal 供引导页复用；否则引导页另写一份简化卡 |
| `app/src/main/java/com/suixing/agent/ui/settings/SettingsScreen.kt` | `:912-992`（拉 `/models`）、`:580-616`（测试连接） | **建议抽成公共函数** `fetchModels(...)` / `testConnection(...)`，引导页与设置页共用，避免两份实现漂移 |
| `app/build.gradle.kts` | `dependencies` `:82-108` | 若采纳 2.7 的 Keystore 方案：**不需要新依赖**（platform Keystore API 即可）。若要用 `androidx.security:security-crypto` 则需加依赖，**但不推荐**（见 2.7） |

#### 主题色接入（**不新增任何配色**）

- 引导页所有颜色取 `MaterialTheme.colorScheme.*`（`ui/theme/Theme.kt:53-58` 注入），深色 `#0F1412` 底 + `#8FD6B6` 主色（`ui/theme/Color.kt:17-60`）。
- 圆角用 `MaterialTheme.shapes.large`（20dp，`ui/theme/Type.kt:34`），卡片描边 `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`。
- 强调状态用 `Tone` 体系（`ui/theme/Color.kt:125-132`）+ `StatusPill`（`ui/components/Common.kt:257`）。
- **不用阴影**（项目自有风格）、**不跟随 Material 动态取色**（`dynamicColor` 由 `settings.paletteMode == 1` 决定，`MainActivity.kt:104`；引导页不额外开启动态取色）。

### 2.6 与现有设置页的能力复用关系

**总原则**：引导页**不实现任何新的配置能力**，它只是「设置页的一个线性子集 + 一个新的入口」。凡是能复用的，一律复用。

| 引导页需要的能力 | 复用谁 | 复用到什么程度 |
|---|---|---|
| 端点（baseUrl / Key / 协议 / 勾选模型） | `data/Settings.kt:66-101` `ProviderConfig` | **完全复用数据结构**。引导页不改数据模型 |
| 草稿与「空地址不覆盖线上」 | `ui/settings/ModelDraft.kt:14, 65` | **完全复用**（纯函数） |
| 保存 | `ui/settings/SettingsScreen.kt:571-579` | 复用 `Core.settings.save(form.forSaving(stored))` 这一句 |
| 测试连接 | `ui/settings/SettingsScreen.kt:580-616` | **抽成公共函数** `testConnection(settings)`，两边调同一个 |
| 拉 `/models` | `ui/settings/SettingsScreen.kt:912-992` | **抽成公共函数** `fetchModels(baseUrl, apiKey, protocol)`，两边调同一个 |
| 模型分组展示 | `data/Settings.kt:108-113, 120-141` | **完全复用** |
| 思考强度档位 | `REASONING_LEVELS`（`ui/components/Common.kt`） | **完全复用** |
| 思考强度生效链 | `ui/chat/ChatTopBar.kt:344-350` `reasoningWire` | **只写全局默认**，不写端点级、不写会话级 |
| 权限状态 | `Core.capabilities.state`（`ui/capability/CapabilityScreen.kt:79`） | **完全复用**同一个 state 流 |
| 权限开启动作 | `openAccessibilitySettings` / `overlayPermissionIntent` / `allFilesAccessIntents` / `requestShizuku` / `probeRoot`（`ui/capability/CapabilityScreen.kt:182, 254-256, 292-294, 194-211, 227`） | **完全复用**，不新写 Intent |
| 能力页作为「权限的常驻入口」 | `ui/SuixingApp.kt:663` `composable("capability")` | 引导页第 4 页每条卡片下加一个「去能力页细看 →」→ `onOpen("capability")` |
| 「已看过引导」标记 | **新增**（`AppSettings.onboardingSeen`） | 这是引导页**唯一**的新增状态 |
| 供应商预设表 | **新增** `ProviderPresets.kt` | 静态常量，不进 DataStore |

**【建议】** 引导页**不**做的事：不做温度/TopP/最大 token 采样滑杆（设置页 `:1023` 附近有）、不做模型别名（`ProviderConfig.aliases`，`data/Settings.kt:83`）、不做上下文窗口（`ModelDraft.kt:113-126`）、不做协议的高级项。这些都属于「调参」，属于设置页，不属于「能不能跑起来」。

### 2.7 Key 的存放方案（用户明确要求的一节）

#### 2.7.1 现状（全部【事实】）

| 维度 | 现状 | 依据 |
|---|---|---|
| **存哪** | DataStore Preferences 文件 `files/datastore/Suixing_settings.preferences_pb` | `data/Settings.kt:15` `preferencesDataStore(name = "Suixing_settings")` |
| **存成什么** | 整份 `AppSettings` 序列化成 **一个 JSON 字符串**，写进**唯一一个 key** `settings_json` | `data/Settings.kt:538` `stringPreferencesKey("settings_json")`；`:544-548` `save`；`:550-558` `update` |
| **Key 在 JSON 里的位置** | `AppSettings.apiKey`（顶层，`:146`）**和** `ProviderConfig.apiKey`（每个端点一份，`:70`）—— **两处** | 同左 |
| **是否明文** | **是**。普通 `String`，`json.encodeToString` 直接序列化，**无加密、无 Keystore、无摘要** | `data/Settings.kt:70, 146`；`:545-546` |
| **UI 掩码** | ✅ 设置页的 API Key 输入框用了 `PasswordVisualTransformation()` | `ui/settings/SettingsScreen.kt:884` |
| **是否写日志** | ✅ 没有。`grep` 全项目 `apiKey` 与 `log/println` 的交集为空 | 全项目 grep |
| **云备份** | ❌ **不含** Key。Android 11 及以下走 `backup_rules.xml` 的 `<exclude domain="file" path="datastore" />`；Android 12+ 走 `data_extraction_rules.xml` 的 `<cloud-backup><exclude domain="file" path="datastore" />` | `app/src/main/res/xml/backup_rules.xml`；`app/src/main/res/xml/data_extraction_rules.xml`；Manifest `:28-31` |
| **换机迁移** | ⚠️ **含** Key。`data_extraction_rules.xml` 的 `<device-transfer><include domain="file" path="datastore" />` —— **明文**随文件转到新机 | `app/src/main/res/xml/data_extraction_rules.xml` |
| **传输** | ⚠️ 允许明文 HTTP：`android:usesCleartextTraffic="true"`；设置页在 http 地址时给出告警「填 http:// 地址时，API Key 会明文传输，仅建议在可信局域网内使用。」 | Manifest `:37`；`ui/settings/SettingsScreen.kt:824` |
| **依赖** | 没有 `androidx.security:security-crypto`；`minSdk = 26`（Keystore 的 AES/GCM 自 API 23 起可用，够用） | `app/build.gradle.kts:82-108`；`:14` |
| **威胁面小结** | ① root/ADB 设备上可直接读 `files/datastore/Suixing_settings.preferences_pb`；② 换机迁移会把明文 Key 复制到新机；③ 走 http 的端点会把 Key 明文发到网络上 | 上述各项推得 |

#### 2.7.2 三个可选方案（【建议】）

**方案 A — 把 Key 从 `settings_json` 里拆出去，单独一个文件**
- **落点**：新建 `app/src/main/java/com/suixing/agent/data/Secrets.kt`
  - `private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "Suixing_secrets")`
  - 存 `providerId → 密文/明文` 的映射
  - `ProviderConfig.apiKey` 改为**不参与序列化**（`@Transient` 或自定义 serializer），序列化时置空，读取时由 `Secrets` 覆盖回来
- **好处**：① 能**单独**控制备份策略（secrets 文件可以单独 exclude / include）；② 明文只集中在一个文件，不再散在整份设置 JSON 里；③ 以后上加密时改动面小。
- **代价**：① 要做一次迁移（旧 `settings_json` 里的 key 挪进 secrets 文件，且**迁移后要把旧文件里的 key 抹掉**）；② `ProviderConfig` 变成「不能独立反序列化的对象」，所有 `json.encodeToString(AppSettings...)` 的地方（只有 `Settings.kt:545, 556` 两处）都要走「装载 → 落盘 → 卸载」两步；③ **安全等级其实没提高**（还是明文，只是换了文件）。
- **换机影响**：可以在 `data_extraction_rules.xml` 里把 secrets 单独 include/exclude，**与其它设置解耦**。

**方案 B — Android Keystore 加密（真正的安全提升）**
- **落点**：新建 `app/src/main/java/com/suixing/agent/data/SecretBox.kt`
  - `KeyStore.getInstance("AndroidKeyStore")`，alias 如 `"suixing_secrets_v1"`
  - `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`，`.setBlockModes(BLOCK_MODE_GCM)`，`.setEncryptionPaddings(ENCRYPTION_PADDING_NONE)`，`.setUserAuthenticationRequired(false)`（**必须 false** —— 否则后台跑任务时读 Key 会失败）
  - 密文格式：`Base64(iv ‖ ciphertext)`，存进 `Suixing_secrets` 的 preferences 或 `filesDir/secrets.enc`
  - 读：`cipher.init(DECRYPT_MODE, key)`；**解不开就当作「Key 丢失」**（`AEADBadTagException` / `KeyPermanentlyInvalidatedException`）→ 让用户重填，**不 crash、不静默吞掉**
- **代价（必须让用户拍板的那几条）**：
  1. **换机必须重填 Key**。Keystore 的密钥**不随备份迁移** ⇒ 新机上的密文解不开。**要保留「换机免重填」就必须放弃 Keystore**（或把 Key 导出成明文再存回 settings —— 那就等于没加密）。**这是本次唯一真正的取舍点。**
  2. **多端/多设备**：同一份 Key 在两台设备上各存一份密文、各用各的 Keystore 密钥，互不相通；「一个 Key 多端用」只能靠用户各填一次。
  3. **不引入新依赖**：直接用 platform Keystore API（`minSdk 26` 够用），符合「无原生依赖」的现状。
  4. **实现复杂度**：+1 个文件 + 1 次迁移 + 3 条异常分支（密钥失效 / 数据被清 / 换机），约 +120~150 行。
  5. **不要开生物/人脸锁**（`setUserAuthenticationRequired(true)`）——后台任务会读不到 Key。
  6. **adb backup / 手动拷 `filesDir`** 这类手段拿不到明文了（好的一面）。
- **不建议用 `EncryptedSharedPreferences`**：`androidx.security:security-crypto` 的 `EncryptedSharedPreferences` 自 1.1.0-alpha06 起被官方标记 deprecated（推荐直接用 Keystore API）；且它要求引入一个**新的 SharedPreferences 文件**，与本项目「全项目无 SharedPreferences」的现状（见 1.1）冲突。

**方案 C — 只改备份规则**
- 把 `data_extraction_rules.xml` 的 `<device-transfer>` 里的 `datastore` include 去掉 ⇒ 换机不带 Key。
- **代价**：**所有设置**（模型选择、采样参数、助手、人像、别名、模型上下文窗口）一起从换机里消失 —— 代价远大于收益。
- **结论：不推荐。**

#### 2.7.3 建议的推进顺序

1. **立刻（零代码）**：在引导页第 2 页的 Key 输入框旁写清「Key 存在这台手机上；换机后需要重填一次」；在第 5 页摘要里也带一句。**这是当前事实下最诚实的说法**（因为 device-transfer 确实会带走它）。
2. **本版本内（方案 A）**：把 Key 拆到独立文件 `Suixing_secrets`，并把该文件在 `data_extraction_rules.xml` 的 **device-transfer** 里也 **exclude** 掉 ⇒ 换机后 Key 一定不跟着走（与 UI 上那句提示一致）。安全等级不变，但**行为变确定**。
3. **下一版本（方案 B，需用户拍板）**：Keystore 加密。前提是用户接受「换机后重填 Key」。
4. **无论哪一档**：UI 上**永远不显示完整 Key**（已满足：`ui/settings/SettingsScreen.kt:884` 的 `PasswordVisualTransformation`），日志里**永不出现 Key**（已满足）。

### 2.8 与 Aether 的对照（事实 vs 推断）

| 维度 | Aether 扶摇 | 随行 Suixing（建议） |
|---|---|---|
| 引导的内容 | 【事实】授予权限 + 配置首个 LLM 提供商（<https://aether.baimoqilin.com/docs/zh/installation>） | 同：权限 + 供应商/Key + 模型 |
| 引导是否强制 | 【推断】不强制（文档说「在引导流程或运行时请求」，即运行时也能拿） | 不强制（用户已拍板） |
| 供应商配置 | 【事实】文档里有独立的「提供商」页 | 设置页已有「模型」子页；引导页复用它 |
| 存储权限 | 【事实】Aether 明确不请求任何存储权限 | 一致：不请求，靠 Shizuku/root/所有文件 三档 |
| 视觉 | 【事实】README 展示 welcome/agentmode/chat/research 四图，README 标榜 ChatGPT/Codex/Gemini/Poco Agent 的观感 | **代码里的墨绿主题 + 三层表面 + 1dp 发丝描边 + 20dp 圆角 + edge-to-edge**（`ui/theme/Color.kt`, `ui/theme/Type.kt`）；**不用 Aether 的紫** |

---

## 3. 风险与取舍

### 3.1 强制 vs 可跳过

| 选项 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 强制（必须配好 Key 才能用） | 用户不会「配了一半」就流失；首屏就把产品价值说清楚 | 用户可能只是想先看看；**没 Key 就不能聊天**（`data/Settings.kt:426`）是硬事实，强制等于强行把用户按在注册流程上 | ❌ |
| **可跳过（本次采用）** | 不打断探索；【事实】`AppSettings.configured == false` 本来就是**支持的正常状态**（`data/Settings.kt:426`） | 首屏可能流失 | ✅ **用户已拍板** |

**【建议】** 可跳过的**具体实现**：跳过 = 只写 `onboardingSeen = true`，**不写任何配置**、**不清空已有配置**（老用户点「重新看一遍引导」再点跳过，原配置必须原样还在）。

### 3.2 Key 校验时机

| 时机 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 引导页里校验，**失败不让走** | 不会带着坏 Key 进主界面 | 用户可能只是手滑/网络抖；**在校验成功前不给用** = 变相强制 | ❌ |
| **引导页里校验，失败只提示（本次采用）** | 符合用户拍板；用户能填一半先走 | 主界面可能带着坏 Key，用户第一次发消息才失败 | ✅ |
| 完全不校验 | 最省事 | 用户填错了都不知道 | ❌ |

**【建议】** 具体规则：
1. 「测试连接」是**可选**动作（按钮，不是「下一步」的门槛）。点它才发请求。
2. 失败**只提示**：用 `ui/capability/CapabilityScreen.kt:130-149` 同款的提示条（`errorContainer` + `ic_circle_alert`），文案带上 `describeError(it)` 的原始错误（`ui/settings/SettingsScreen.kt:597` 用的就是它）。
3. 校验**绝不**改写用户已填的内容（不 trim 掉空格、不改大小写 —— Key 大小写敏感）。
4. 请求体沿用设置页的同一句 `ChatMessage.user("你好，请只回复两个字：可用")`（`ui/settings/SettingsScreen.kt:593`）—— 短、便宜、能同时验证「能连」和「能出字」。

### 3.3 权限拒绝后的降级

| 拒绝/不授予 | 降级行为 | 依据 |
|---|---|---|
| 通知拒绝 | 前台服务照跑，只是**没有可见通知**。**必须在引导页文案里说明这一点**（App 仍然能用，只是你不知道它完成了） | `data/Settings.kt` 无关；`MainActivity.kt:117-127` 已处理拒绝（不崩） |
| 无障碍不开 | 降级到**标准档**：聊天 + 记忆 + 文件工具（只碰应用私有目录） | `ui/capability/CapabilityScreen.kt:164-173` 的标准档定义 |
| 悬浮窗不开 | 降级：无悬浮球。用户仍可在抽屉底部那一行开关处事后开（`ui/SuixingApp.kt:990-1006`） | 同左 |
| 所有文件不开 | 降级：文件工具只能访问应用私有目录 | `ui/capability/CapabilityScreen.kt:267-284` |
| Shizuku / Root 探测失败 | 降级到无障碍档或标准档；**不反复弹** | `ui/capability/CapabilityScreen.kt:194-211, 215-231` |

**【建议】** 降级的统一原则：**引导页只提示一次，拒绝后不再出现在引导里**；常驻的入口永远是「设置 → 权限」（`ui/settings/SettingsScreen.kt:260` → `ui/SuixingApp.kt:663`）。**不要**在聊天页反复弹权限提示（会变成骚扰）。

### 3.4 其它风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **老用户被当成新用户** | `onboardingSeen` 默认 `false` ⇒ 升级上来的老用户会看到引导 | **必须**在 `data/Settings.kt:579-580` 的迁移链里加 `withOnboardingSeen()`：**已有 apiKey 的一律判 true** |
| **引导层盖在 NavHost 之上导致状态错乱** | 掀开后 chat 的 ViewModel 必须还是原来那份 | 盖在 NavHost 之上（`ui/SuixingApp.kt:536` 之外）而不是**替换** `SuixingApp()`；chat 常驻在 `:531-533`，不受影响 |
| **提示框打架** | `MainActivity.kt:69` 首启就弹通知框；引导页第 4 页又要谈通知 | 【建议】把 `MainActivity.kt:69` 的弹框**推迟**到引导结束后；保守做法是引导页读实时授权状态（`ContextCompat.checkSelfPermission`） |
| **预设地址写错** | baseUrl 写错 = 用户第一步就失败 | 2.3 的表全部标【待核】，实现前逐条核对官方文档；**并且**「测试连接」失败不拦人（3.2），写错不会把用户锁死 |
| **Key 加密与「换机免重填」冲突** | 见 2.7 | **需要用户拍板**：宁可换机重填（安全），还是保留明文迁移（省事）。本方案默认推荐**换机重填** |
| **引导页代码膨胀** | 5 个页面 ≈ 800~1200 行 | 每一页都复用既有组件与既有函数（2.6 表），**不复用就要停下来问自己为什么** |
| **多语言** | `app/build.gradle.kts:18` `resourceConfigurations += listOf("zh", "en")` | 引导页文案若要进 `res/values/strings.xml` 就要两套；若先硬编码中文，与其它页现状一致（现有页多为硬编码中文），**先与用户确认口径** |

---

## 4. 未决问题（需要 Lead / 用户拍板）

1. **Key 加密 vs 换机免重填**（最重要）：采纳方案 B（Keystore，换机重填）还是停在方案 A（独立文件，明文，行为确定）？—— 影响 2.7 与 3.4 最后一行。
2. **引导页文案是否进 `strings.xml`**：现有 UI 多为硬编码中文；引导页是新页面，是跟随现状硬编码，还是为将来的英文做双份？
3. **「再次进入」的入口位置**：放在「设置 → 能力与连接」分组里（建议），还是单独放「设置 → 关于」？
4. **是否顺带修 `MainActivity.kt:69` 的通知弹框时机**（推迟到引导后），还是保持现状不动。
5. **2.3 的预设表最终名单与 baseUrl/模型 ID**：需要逐条核对官方文档后定稿（DeepSeek 一条已由代码确认）。
6. **引导页是否要覆盖「助手（人设）」的首次介绍**：`AppSettings.assistants` 由 `withAssistants()`（`data/Settings.kt:616-623`）保证非空；当前建议**不**加这一页（用户是「给个接口就能用」的定位），如需再加第 6 页。

---

## 附录 A · 关键文件行号索引（全部【事实】）

| 主题 | 文件:行号 |
|---|---|
| DataStore 定义 | `app/src/main/java/com/suixing/agent/data/Settings.kt:15` |
| settings_json 唯一 key | `data/Settings.kt:538` |
| `SettingsRepository` / save / update / decode | `data/Settings.kt:525` / `:544-548` / `:550-558` / `:564-572` |
| 迁移链 | `data/Settings.kt:579-580`（`withProviders` `:598-609`、`withAssistants` `:616-623`、`withMaxTokensMigration` `:587-596`） |
| `ProviderConfig` | `data/Settings.kt:66-101` |
| `AppSettings` 扁平三元组 / providers | `data/Settings.kt:145-147` / `:293` |
| 协议常量 | `data/Settings.kt:62-64` |
| `modelGroupOf` / `groupModels` / `modelOffers` | `data/Settings.kt:108-109` / `:112-113` / `:120-141` |
| `configured` / `activeProvider` / `choiceFor` / `LlmChoice` | `data/Settings.kt:426` / `:443-444` / `:670-689` / `:652-662` |
| 设置页入口 / 模型页 / 保存 / 测试连接 | `ui/settings/SettingsScreen.kt:163` / `:431` / `:571-579` / `:580-616` |
| 接口地址 / API Key / 拉模型 / 新建端点 | `ui/settings/SettingsScreen.kt:805-831` / `:875-886` / `:912-992` / `:728-752` |
| 「权限」行 → 能力页 | `ui/settings/SettingsScreen.kt:255-261`（`:260`） |
| 草稿纯函数 | `ui/settings/ModelDraft.kt:14, 29, 48, 65, 81, 88, 100, 113-126` |
| 能力页 | `ui/capability/CapabilityScreen.kt:78`；无障碍 `:175-183`；Shizuku `:185-213`；Root `:215-231`；悬浮窗 `:233-265`；所有文件 `:267-314`；提示条 `:130-149` |
| 通知权限 | `MainActivity.kt:69`（实现 `:117-127`） |
| 麦克风权限 | `ui/chat/ChatScreen.kt:366-367` |
| MainActivity 主题 / edge-to-edge | `MainActivity.kt:53-59` / `:62-67` / `:102-108` |
| NavHost / 路由 / 转场 / 抽屉 | `ui/SuixingApp.kt:536-689` / `:620-688` / `:573-617` / `:454-523` |
| 品牌色 / 圆角 / 主题 | `ui/theme/Color.kt:17-60, 62-111, 125-132` / `ui/theme/Type.kt:28-37` / `ui/theme/Theme.kt:21-59` |
| 引导页标记 / 空态 | `ui/components/Logo.kt:100-122` / `ui/components/Common.kt:1079-1108` |
| 备份规则 | `app/src/main/res/xml/backup_rules.xml`、`app/src/main/res/xml/data_extraction_rules.xml`；Manifest `:28-31, 37` |

## 附录 B · Aether 扶摇来源

| 来源 | 取得的内容 |
|---|---|
| <https://aether.baimoqilin.com/docs/zh/installation> | 引导流程的**内容**（权限 + 首个 LLM 提供商）、权限表、「不请求存储权限」、文档侧栏的「提供商」页 |
| <https://raw.githubusercontent.com/Zhou-Shilin/Aether/main/README.md> | 定位语、Visuals 段落的参照对象、四张首屏截图（welcome/agentmode/chat/research）、Alpine VM / Shizuku / Termux 能力 |
| <https://github.com/Zhou-Shilin/Aether> | 仓库首页（`web_fetch` 命中，导航部分被截断） |
| 抓取失败 | `https://aether.baimoqilin.com/docs/quickstart`、`/docs/llms.txt` —— 两次 `web_fetch` 均 30s 超时；`/docs/zh/quickstart` 同超时。**Aether 引导页的 UI 形态因此无法证实，本文相关处均标【推断】** |

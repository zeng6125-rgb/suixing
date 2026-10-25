# 全仓库优化清单（只读审计汇总）

> 生成时间：2026-10-26 04:1x（Asia/Shanghai）
> 基线：`main` @ `3d554aa`（0.8.499），代码规模 130 个 Kotlin 文件 / 56,664 行，测试 110 文件。
> 审计方式：5 个只读子代理分区并行通读（130 文件 / 56,664 行全覆盖）+ 主代理交叉复核；行号均为当次快照，
> 后续编辑会让行号漂移，按「文件 + 附近代码」定位。
>
> **硬约束（贯穿全清单）**：只做**行为不变**的优化。凡是会改变可见行为、交互、数据流的建议，
> 一律标注 `[改变行为]`，不在本次执行范围内。

---

## 总览

| 区 | 条数 | P0 | P1 | P2 |
|---|---|---|---|---|
| `data/` | 32 | 2 | 11 | 19 |
| `tools/` + `service/` | 34 | 1 | 8 | 25 |
| `agent/` + `llm/` | 25 | 1 | 7 | 17 |
| `ui/components` + `ui/settings` | 25 | 2 | 8 | 15 |
| `ui/chat` | 33 | 1 | 8 | 24 |
| **合计** | **149** | **7** | **42** | **100** |

## 进度（2026-10-26 04:xx 起）

- 已完成：issue #49 + 11 条清单项（见下节「已完成」与各节里打勾的 `[x]`）
- 进行中：按 P0 → P1 → P2 逐条推进（goal-a3468e21）
- 每批次均以 `./gradlew :app:testDebugUnitTest` 全绿 + `git push` 收尾

## 0. 已完成（本次已落地并验证）

| 项 | 位置 | 说明 |
|---|---|---|
| 正则提升（热路径） | `tools/ShellTools.kt` `SETTING_PUT` | `parseSettingWrite` 每次 `run_shell` 成功都会调用，原来每次现场编译 |
| 正则提升 | `tools/WebTools.kt` `RE_SCRIPT/RE_STYLE/RE_NOSCRIPT/RE_COMMENT/RE_LI/RE_BLOCK_END/RE_TAG` | `htmlToText` 每次抓网页现场编译 7 条 |
| 正则提升 | `llm/LlmClient.kt` `WHITESPACE_RUN` | 错误信息清洗每次失败编译一次 |
| 正则提升 | `agent/ToolOutputSpill.kt` `UNSAFE_FILENAME` | 每次工具输出落盘编译一次 |

| 空行短路 | `ui/components/MarkdownText.kt:156` `parseMarkdown` | 空行原本先跑完 4 条 `matchEntire` 才进 `when`，而首支就是空行判断 |
| 去掉多余派生层 | `ui/chat/ChatScreen.kt:431` | `remember(imePxState){ derivedStateOf { imePxState.value } }` 是恒等转发 |
| 修 remember 键 | `ui/chat/ChatScreen.kt:1966` | `heavyRow` 的键原用 `allImages`（每次访问重建 List 的 getter）⇒ 换 `imageCount` |
| 清理未使用 import | `ui/chat/` 7 个文件 | 14 行 `derivedStateOf` / `animateFloatAsState` 未使用 |

验证：`./gradlew :app:testDebugUnitTest :app:assembleRelease` 全绿（APK 16.9MB）。

---

## 1. 数据层 `data/`（32 条：P0×2 / P1×11 / P2×19）

**根因**：`ChatRepository` 用**单把仓库级 Mutex** 同时保护「与会话无关的按需读」和「写 + 多次 fsync」。

### P0

- [x] `data/ChatRepository.kt:203` — 为取 4000 字尾部把整份日志 `readText()`（30MB 会话＝30MB 读 + 最多 60MB 中间串），且发生在仓库级锁内（`:194`）。
  改：`RandomAccessFile` seek 到 `len-20KB` 只读末段再 decode + `takeLast(4000)`。风险低。
- [ ] `data/ChatRepository.kt:405` — 整个 `save()` 在单把 Mutex 内完成追加写 + `raf.fd.sync()`（`ChatStore.kt:371`）+ meta 写 + 目录 `fsync`（`ChatStore.kt:789,803`），期间所有会话的 `readTail/readWire/chatSize` 排队。
  改：按 chatId 分锁，列表状态另用一把锁。风险中。

### P1（同一次操作内重复读同一个 meta 文件，可一次性收掉）

- [ ] `data/ChatRepository.kt:442` / `:726` — `save()` 已读盘中 meta，`writeTail()` 再 `readMeta` 一次。
- [ ] `data/ChatRepository.kt:769` / `:781` — `write()` 先 `readMeta` 判空、紧接着再读同一文件；`:755` `logExists()` 为判断某代日志把整个 `store/` 列一遍。
- [x] `data/ChatStore.kt:216` — `readMeta` 每次 `isFile + readText + decode`，无缓存，被 6 个入口各调一次。

### P1（其余单点）

- [ ] `data/ChatRepository.kt:127` — `updateMeta` 整表 `.map` 一次，`:565/:579/:590` 的 `setPinned/rename/setArchived` 再整表 `.map` 一次（两次结果相等、第二次被 StateFlow 去重）。
- [ ] `data/ChatRepository.kt:544` — 每次 save 都 `filterNot` + `listOf` + `sortedByDescending`：3 份 List + 一次排序，只为把一条挪到首位。
- [ ] `data/ChatRepository.kt:65` — `load()` 把目录列了多遍：`:830` `chats/`、`:844` `store/`、`:864` 再列 `store/`。
- [ ] `data/ChatStore.kt:710` — `encode()` 先 `buildString` 拼整份日志再 `toByteArray`，峰值容量翻倍。
- [x] `data/JobStore.kt:136` — `lastLineOf` 走 `readJobLog(tail=1)` 每次读末尾 256KB 再 `lines()` 全拆。
- [x] `data/JobStore.kt:98` — `find(id)` 实现为 `list().firstOrNull`，为一个 job 把每个 job 的 256KB 日志都读一遍。
- [ ] `data/ChatRepository.kt:621` — `saveLive` 流式期间每秒调用一次（`ChatViewModel.kt:2884,2908`），持锁时先 `encodeToString` 整份草稿再 `fd.sync` + 目录 fsync。
- [ ] `data/ChatRepository.kt:185` — `chatSize()` 只为拿 gen 与尺寸就 `readMeta`（含 checkpoints/activeRuns 全解析），会话列表每行都调。

### P2（19 条，择要做）

- [ ] `ChatStore.kt:386` 同一条 tail 折叠两遍（`tail.fold` + `foldTotals(tail)`）
- [ ] `ChatStore.kt:485` `slicedFrom` 给每条消息建 `SlicedMessage`，`UsageStore.kt:199/:201` 又各 map 一份
- [ ] `ChatStore.kt:497` `from()` = `readEntries().map{it.message}` 全量复制
- [ ] `ChatStore.kt:520` `window()` 每轮重试把更大区间从磁盘重读重解析
- [ ] `ChatStore.kt:605` `offsetOf` 反扫每 1MB 新建 `ByteArray`
- [ ] `Transcript.kt:88` `active()` 内部重建两张全量表（调用方已算过）
- [ ] `Transcript.kt:102` `withoutLastAnswer` 为丢最后一轮构建整份 `turnOf`
- [ ] `WireSanitize.kt:21` `apply()` 对整份历史 4 遍遍历、5+ 中间集合
- [ ] `UsageStore.kt:112` `days/models/totals` 计算型 getter 每次重新合并
- [ ] `UsageStore.kt:163` `refresh()` 无变化也整份 prettyPrint 重写
- [ ] `ScheduleStore.kt:161` 同一次调度 2~3 次全量 stat+解析
- [ ] `PerfMonitor.kt:93` `record()` 在 `synchronized` 内 persist
- [ ] `PerfMonitor.kt:113` `summary()` 4 条 `filter{}.map{}.average()`、8 个中间 List
- [ ] `OffsetCache.kt:29` 键用 `chatId to gen`，每条消息分配一个 Pair
- [ ] `ChatRepository.kt:600` `delete()` 里 `liveFile(id)` 重算 3 次 + 整个 `chats/` listFiles
- [ ] `ChatRepository.kt:39` `dir` getter 每次访问做一次 `exists()`
- [ ] `JobStore.kt:58` `start()` 每次起 job 都 `mkdirs + pruneLogs()`（全目录 stat + 排序）
- [ ] 死代码（只列）：`ChatRepository.kt:224` 不可达分支；`:939` 空函数 `pruneQuarantine` + 仅存于文档的 `CORRUPT_KEEP_MS(:947)`

---

## 2. 工具与服务 `tools/` + `service/`（34 条：P0×1 / P1×8 / P2×25）

### P0

- [x] `service/SuixingAccessibilityService.kt:383` — `takeScreenshot` 回调注册在 `mainExecutor`，`onSuccess(:366-374)` 在**主线程**做 `wrapHardwareBuffer` → `copy(ARGB_8888)` → `createScaledBitmap` → JPEG 压缩。
  改：整段挪到后台线程，只把 `ByteArray` resume 回 continuation。风险低。

### P1

- [ ] `tools/WebTools.kt:293` — `readText` 每次编译 2 条 Regex，且为嗅探 charset 把 ≤1MB 正文整体 `ISO_8859_1` 解码 ⇒ 只解码前 4KB 嗅探。风险中。
- [ ] `tools/WebTools.kt:150` — 下载写盘用裸 `part.outputStream()` + 默认 8KB 缓冲 ⇒ `BufferedOutputStream(64KB)`。
- [ ] `tools/FileTools.kt:181` — 同一份 ≤4MB 字节解码两次（`:181` 得 lines、`:191` 得 revision），两份大 String 同时存活。
- [ ] `tools/FileTools.kt:426` — `search_in_files` 每文件 `readBytes()+lines()`，上限 400 文件 × 2MB ⇒ `BufferedReader.useLines`。风险中。
- [ ] `service/SchedulerService.kt:69` — `onStartCommand` 在 `Core.appScope`(Default) 上调 `arm()`，`:214` 读 `schedules.json` ⇒ `withContext(Dispatchers.IO)`。
- [ ] `service/TouchFx.kt:1532` — `drawGlow` **每帧**调 `edgeShape()` 重建 `edgePath`，而 band 缓存只在几何变化时重建 ⇒ 移进 `haloCacheKey` 变化分支。
- [ ] `service/TouchFx.kt:1550` — 每帧用字符串插值拼 `haloCacheKey`（6 个数值）只做相等比较 ⇒ 改存 6 个数值字段。
- [ ] `tools/FileTools.kt:292` — `lineOf` 用 `substring(0,index)` 复制前缀再 `count` 换行，`:253/:271` 各调一次 ⇒ 单次顺序扫描。

### P2（25 条，择要做）

- [ ] `WebTools.kt:256` `parseDuckDuckGo` 每次搜索编译 2 条 Regex
- [ ] `WebTools.kt:128` 每次 `web_download` 都 `sweepStaleParts`（`walkTopDown` depth 6）
- [ ] `WebTools.kt:319` `ByteArrayOutputStream` 无初始容量（`contentLength` 已在 `:317`）
- [ ] `FileTools.kt:178` `bytes.take(4096)` 把 Byte 装箱成 `List<Byte>`（`:427` 同样）
- [ ] `FileTools.kt:237` 未给 `expected_revision` 时也算全文 `revisionOf(text)`
- [ ] `FileTools.kt:421` `walk` 停止条件与 `:420` 逐字相同，每文件算两遍
- [ ] `FileTools.kt:295/:304` `deleteRecursive`/`copyRecursive` 在 `tools/` 内无调用点（`:496` 注释还写着「已删掉」）
- [ ] `ShellTools.kt:178` 一次 `run_shell` 里 `Shell.bundledBash()` 最多探两次
- [ ] `ShellTools.kt:374` 单引号转义与 `:408` `quote()` 是同一实现第二份
- [ ] `UiTools.kt:306` `renderUiNodes/parseUiDump(:329)` 在 IO 上做纯 CPU 正则 XML 解析 ⇒ Default
- [ ] `UiTools.kt:344` `Base64.encodeToString` 三处在 `withContext(IO)` 之外
- [ ] `UiTools.kt:407` `encodeJpeg` 先整张 decode 再 `createScaledBitmap`，两张全尺寸 ARGB 并存
- [ ] `UiTools.kt:322` 同一张 ui.xml 在 `:322` 与 `:327` 各删一次（`:371/:376` 同）
- [ ] `UiTools.kt:437` `clickText` 对节点列表两趟 `firstOrNull`
- [ ] `UiTools.kt:504` 每次 `ui_key` 新建 `listOf("back"…)`，与 `:146` spec 重复
- [ ] `UiTools.kt:253` `scratch()` 每次 `getExternalFilesDir(null)`（`FileTools.kt:21/:505` 同）
- [ ] `SystemTools.kt:345` `onMainThread` 每次 `new Handler`
- [ ] `SystemTools.kt:171` `listApps` 过滤里每包 `label.lowercase()/pkg.lowercase()`
- [ ] `Tool.kt:232` `all()` 每次新建列表，`names()/promptSummary()` 每轮请求都走
- [ ] `UiDump.kt:115` `unescape` 每属性 6 次全文替换
- [ ] `ScheduleTools.kt:151` `TIMING_KEYS.filter` 三处各算一次
- [ ] `TouchFx.kt:1030` `trace()` 在调用线程拼整行（含每行新建 `SimpleDateFormat`、`caller()` 全栈）
- [ ] `FloatConsoleService.kt:483` `trace()` 在调用线程做检查 + 拼整行

---

## 3. Agent / LLM 热路径 `agent/` `llm/`（25 条：P0×1 / P1×7 / P2×16）

### P0

- [x] `llm/LlmClient.kt:503` — 单行 `data:` 帧先**探针解析**一次，成功后又交 `acceptPayload(:458-459)` 解析同一字符串 ⇒ 最热路径每 delta 两次全量 JSON 解析。
  改：让 `acceptPayload` 返回 `JsonObject?`/布尔，探针复用其返回值。风险中。

### P1（都是「每轮重建」）

- [ ] `llm/LlmClient.kt:1183` / `:1124` — 每轮为每张历史截图重建 `"data:image/jpeg;base64,…"`（几十万字/张，图片预算 2MB）。
- [ ] `llm/LlmClient.kt:1088` — 每轮对每个历史 `tool_call` 重跑 `arguments.toJsonObjectOrEmpty()`。
- [ ] `llm/LlmClient.kt:909` — 每轮 `messages.map { encodeMessage(it) }` 重建整份历史 JsonObject 树（`:981` 还两次 filter）。风险中。
- [ ] `llm/LlmClient.kt:396` — `payload.toString().toRequestBody()` 整份 body（含 2MB base64）序列化成 String 再拷 ByteArray，每轮两份全量拷贝。风险中。
- [ ] `agent/RequestImages.kt:25` — 每轮 `wire.withIndex().filter{…}` 即使零截图也造全量 `IndexedValue`。
- [ ] `agent/AgentEngine.kt:1897` — 每轮无条件 `estimatePromptTokens(wire)`，而 `realPromptTokens>0` 时它多数轮算完即弃。

### P2（16 条，择要做）

- [ ] `AgentEngine.kt:1381` 每 delta `streamed.takeLast(40).toString()` ⇒ `substring(max(0,len-40))`
- [ ] `AgentEngine.kt:1334` 每轮 `toolSpecs.joinToString(",")`，而工具表整 run 已冻结在 `:1319`
- [ ] `AgentEngine.kt:1329` 每轮 `PluginRuntime.hooksFor(pluginSpecs)`
- [ ] `AgentEngine.kt:1474-1481` usage 到达后再扫一遍 wire（同轮 `:1347-1356` 已扫过）
- [ ] `AgentEngine.kt:645` 120ms ticker 常驻，runs 为空也每 120ms 唤醒
- [ ] 死代码：`AgentEngine.kt:2100` `wireChars` 无调用点
- [ ] `AgentEngine.kt:2024` `summarize` 的 `joinToString{renderForSummary(it)}` ⇒ 单 `StringBuilder`
- [ ] `LlmClient.kt:699/:706` 每 delta `containsKey + get` 两次查表
- [ ] `LlmClient.kt:478` 每 chunk `hasToolCalls()` 全表扫
- [ ] `LlmClient.kt:757` 为比 `!= "{}"` 序列化两次
- [ ] `LlmClient.kt:1371` `parseUsage` 的 `path.split('.')` 每次现切
- [ ] `LlmClient.kt:1521` `textOf` JsonArray 分支中间 List
- [ ] `LlmClient.kt:872` `toTurn` 的 `toSortedMap().values.filter{}.mapIndexed{}` 三份中间集合
- [ ] `LlmClient.kt:434` 非 2xx 取证再 `payload.toString()`（`:396` 已有同串）
- [ ] `LlmClient.kt:1625` `describeError` 无条件拼整条 cause 链
- [ ] `ToolOutputSpill.kt:53` 每次落盘成功都 `listFiles()+sortedByDescending`

---

## 4. UI 组件与设置页 `ui/`（25 条：P0×2 / P1×8 / P2×15）

### P0

- [x] `ui/components/MarkdownText.kt:156-159` **已修** — 每行（含空行）在 `when` 之前先跑完 `MD_FENCE/HEADING/BULLET/ORDERED` 四条 `matchEntire`，而首支就是 `trimmed.isEmpty()` ⇒ 空行白跑 4 次。
  改：把测试挪进对应 `when` 分支，顺序不变。风险低。
- [x] `ui/components/MarkdownText.kt:237,249-260` — 段落扫描 `opensBlock` 对同一行再跑 6 条正则，派发阶段已跑过其中 4 条 ⇒ 每正文行 ≥10 次匹配；块边界行被三轮重测。风险中。

### P1

- [ ] `MarkdownText.kt:150` — 解析前无条件两次全串 `replace`（`\r\n`→`\n`、`\r`→`\n`）再 `split` ⇒ 先 `indexOf('\r')<0` 直接 `split('\n')`
- [ ] `MarkdownText.kt:176-177` — 同一段代码体 `joinToString("\n")` 执行两遍
- [ ] `MarkdownText.kt:262-272` — `isTableStart` 对同一行调用两次，每次 `trim` 下一行 + `splitTableRow`
- [ ] `MarkdownText.kt:897-909` — `settledPrefix` 每个候选空行都从头 `count{it=='`'}` ⇒ O(空行×文本长)，流式每发布一次
- [ ] `MarkdownText.kt:991-1002` — 公开 `MarkdownBlock` 每次重组新建 `MarkdownPalette`（`:935` 已为 `MarkdownBlocks` 修过同一问题）
- [ ] `MarkdownText.kt:948` — `cursor=true` 时每次重组 `blocks.indexOfLast{…}` 扫整块列表（思考链可达数千块）
- [ ] `ui/components/Common.kt:1165-1170` — `navBarContentPadding()` 每次调用读 insets + 新建 `PaddingValues`，全 ui 14 个调用点
- [ ] `ui/settings/SettingsScreen.kt:230,458,1358,1791,1965`（另 `SuixingApp.kt:273,840`、`settings/ProfileScreen.kt:72`）— 每页订阅整份 settings flow，任意字段写入整页 body 重跑。风险中。

### P2（15 条，择要做）

- [ ] `MarkdownText.kt:209,215,222,229` 列表首项派发时已 `matchEntire`，循环首轮再算一次
- [ ] `MarkdownText.kt:274-275` `splitTableRow` 每次 ≥4 次分配
- [ ] `MarkdownText.kt:345-354` `InlineScanner` 构造时无条件建 Builder + 3 个 SpanStyle
- [ ] `MarkdownText.kt:1110-1131,1134` 每次重组新建 `drawBehind`/`onTextLayout` lambda（捕获 `Array` 不稳定）
- [ ] `MarkdownText.kt:1179-1185` 每个标题每次重组 `scaled()+copy()`
- [ ] `MarkdownText.kt:1328-1331` 代码块每次重组新建 `TextStyle`
- [ ] `MarkdownText.kt:1382-1386` 表头每次重组新建 `TextStyle`
- [ ] `Common.kt:909` `PressPill` 每次 `new RoundedCornerShape(50)`
- [ ] `Common.kt:183,221,325` 默认参数 `PaddingValues(16.dp)/(0.dp)` 每次重组重新分配
- [ ] `components/Logo.kt:81-85,109-114` 绘制块内每帧新建 `listOf(颜色)` + `Brush.linearGradient`
- [ ] `components/PopupMotion.kt:62-71` 每次调用新建 `graphicsLayer` 元素与 lambda
- [ ] `settings/PerfScreen.kt:331,345` 每行组合期 `String.format`
- [ ] `settings/SettingsScreen.kt:464-466` `produceState` 以 raw 为 key，打字期间反复 Keystore 解密
- [ ] 死代码（只列）：`MarkdownText.kt:1148` `CHR_NL`、`:119` `FENCE`、`:1006` `markdownGap`
- [ ] 重复实现（只列）：`ui/SuixingApp.kt:1326` `exportName` vs `ui/settings/SettingsScreen.kt:2449` `exportFileName`（文件名规则不同）

---

## 5. 会话页 `ui/chat`（33 条：P0×1 / P1×8 / P2×24）

### P0

- [ ] `ui/chat/ChatScreen.kt:2188` — `Composer` 调用处（2188-2287）约 20 个内联 lambda 与 2198 的 `Triple` **每次重组都是新实例** ⇒ 参数恒不等 ⇒ `Composer`（`TurnScrubber.kt:579-1405`，826 行，含 `:961` 的 `BasicTextField`）**永不可跳过**；ChatScreen 每次 state 发射（流式 ~12.5Hz）都整栈重跑。
  改：不随帧变的回调 `remember` 住、会变的值走 `rememberUpdatedState`、`Triple` 用 `remember(liveStatsHere, state.contextBreak)` —— 仓库 `ChatScreen.kt:1988-1996` 对 `onBranch/onRegenerate/onEdit` 就是这套写法（属漏改）。风险中。

### P1

- [ ] `ChatScreen.kt:2292` — 组合期读 `scroll.atBottom`（`ChatScroll.kt:102` `derivedStateOf{!canScrollBackward}`）⇒ 每次离开/回到列表底整屏重组，连带上面那个不可跳过的 Composer。改：把 `if (!scroll.atBottom)` 与圆钮下沉成独立 `@Composable`。
- [ ] `TurnScrubber.kt:452` — `TypingDots` 用 `rememberInfiniteTransition` + `alpha by animateFloat`（455-463）在**组合期**读值再 `.alpha(alpha)`（468）⇒ 可见期间按刷新率持续重组+重绘；同文件 477-489 的 `BlinkingCursor` 已因同款写法实测 213fps 改掉，**此处漏改**。
- [ ] `Typewriter.kt:62` — `LaunchedEffect(Unit){ while(true){ delay(16) } }` **没有出口**：打完字后每个参与过流式的块仍每 16ms 醒一次。
- [ ] `ChatContext.kt:1506` — `ImageBlock` 每次重组重建 `ImageRequest.Builder(...).build()`，`:1500` 的 `ByteBuffer` 也每次新建 ⇒ model 身份每帧变，Coil 当新请求重跑。改：`remember(bytes)` 保住请求，位点用 `asReadOnlyBuffer()`/`duplicate()`。
- [ ] `ChatTopBar.kt:801` / `ChatComposer.kt:673` / `ChatContext.kt:320` — **三处 `PopupPositionProvider` 在组合体里 `new`**，未 `remember`（同仓库 `ChatComposer.kt:1117` 是正确写法）。
- [ ] `ChatComposer.kt:619` — `GenerationSheet` 每次重组重算整条选择链（`choiceFor` + 三次 providers 线性扫描 + `reasoningWire` + `indexOfFirst`）；拖滑杆每档都跑一遍。

### P2（24 条，择要做）

- [ ] `Typewriter.kt:77` `remember(revealed, text){ text.take(revealed) }` 每 16ms 复制累积前缀（900 字≈40 万字符拷贝）
- [ ] `ChatTopBar.kt:742` `open?.let { shown = it }` 在组合期写 `mutableStateOf`
- [ ] `ChatTopBar.kt:862/864/865/868/884` 五处 `shortTokens(...)` 拼串每次重组重算（`:762/852/873` 已 remember）
- [ ] `ChatComposer.kt:1200/1206/1243/1247/1248/1249/1258` 七处 token 拼串（`:1223/1265/1273` 已 remember）
- [ ] `ChatComposer.kt:576` `Brush.horizontalGradient(listOf(...))` 写在 Canvas draw lambda 里
- [ ] `ChatScreen.kt:1577` 浅色画布 `Brush.verticalGradient` 每次重组新建
- [ ] `ChatScreen.kt:2097` 顶部渐隐层 `Brush.verticalGradient(colorStops = arrayOf(...))`
- [ ] `VoiceOverlay.kt:561` `listOf(0.45f,…)` + forEach 里 `RoundedCornerShape(2.dp)`
- [ ] `ChatContext.kt:1053/1055/1070/1087` 四个 shape/BorderStroke 每次新建
- [ ] `ChatContext.kt:708` `TodoQueueRow` 的 `RoundedCornerShape(14.dp)` 与 body lambda
- [ ] `ChatContext.kt:393` `labelSmall.copy(fontWeight=…)`（同型：`ChatComposer.kt:735`、`TurnScrubber.kt:572`）
- [ ] `ChatContext.kt:907` `items(todos)` **缺 key 与 contentType**
- [x] `ChatScreen.kt:1966` `heavyRow` 的 remember 键用 `row.message.allImages`（每次访问重建 list）⇒ 换 `imageCount`
- [ ] `ChatScreen.kt:432` `remember(imePxState){ derivedStateOf { imePxState.value } }` 是多出来的一层派生
- [ ] `ChatScreen.kt:1298` 与 `:589` 对同一 `liveActive` 两次 `collectAsState`
- [ ] `ChatBlocks.kt:1534-1554` `TurnSummary` 两个 `buildList` 每次重组都跑（`:1558` 的 remember 只省了 join）
- [ ] `ChatBlocks.kt:884/991/997` 三个 composable lambda 每次新建 ⇒ `ProcessCard` 不可跳过
- [ ] `ChatTopBar.kt:1132` `Tools` 分支对每块再线性扫到块尾 ⇒ O(n²)
- [ ] `ChatBlocks.kt:1991` / `:2035` 每行新建 `RoundedCornerShape`
- [ ] `ChatComposer.kt:371` `MenuCard` 的 `exit = fadeOut+scaleOut+slideOutVertically` 每次重组重建 Transition
- [ ] `ChatRows.kt:763` `Attachment` **缺 `@Immutable`**（含 `ImageBitmap?`）⇒ 收它的组件不可跳过
- [ ] 死代码（只列）：`VoiceOverlay.kt:27-30` 四个动画 import；7 个文件未使用的 `derivedStateOf` import（`ChatBlocks.kt:115` 等）；7 个文件未使用的 `animateFloatAsState` import（`ChatScreen.kt:28` 等）

---

## 6. 范围外：审计中发现的**行为缺陷**（按要求未改）

- `tools/FileTools.kt:432` — `line.contains(query, ignoreCase = caseSensitive)`：`ignoreCase` 绑反了，默认 `case_sensitive=false` 实际是**区分大小写**，与 `:132` spec 文案相反。
- `ui/settings/ToolsScreen.kt:897-903` — 七个 `remember { mutableStateOf(initial…) }` 全缺 key，`initial` 变化时表单不重置。

两者都会改变行为，不在本次执行范围。

---

## 7. 执行建议顺序

1. **P0 三条低风险**：`MarkdownText.kt:156-159`（空行白跑正则）、`ChatRepository.kt:203`（读尾部整份）、`SuixingAccessibilityService.kt:383`（主线程截图）
2. **重复读 meta 三合一**：`ChatRepository.kt:442/:726`、`:769/:781`、`ChatStore.kt:216`
3. **每轮重建**类：`LlmClient.kt:1183/1124/1088`（data URL / tool_call 参数 memo）
4. **Markdown 解析二次匹配**：`MarkdownText.kt:237/249-260`
5. 其余 P1 → P2 按 touched-file 归并批量处理

# 工作会话记忆 — 2026-10-09 | suixing-agent v0.8.x 迭代

> 范围：①「随行 Suixing」Android agent App 从 0.8.0 迭代到 0.8.9（十次出包，全部 adb 直推真机）；②多会话并行引擎重构 + 只读审计修 6 个并发洞 + 1 个真缺陷；③UI 返修 18 轮（顶栏/对话框/过程卡/图标/弹窗）；④测试基建（MockK + ParallelRunTest 5 例）；⑤v0.8 团队契约与三名 teammate 协作。
> 状态：**HEAD `1672491`（0.8.10，已推真机 versionCode 21）；工作区干净；未 push**。

---

## 1. 环境与事实

| 项 | 值 |
|---|---|
| 项目 / 包名 | `D:\dsh\fixbug\lingshu` · `com.suixing.agent` · 分支 `main` · remote `github.com/zeng6125-rgb/lingshu-agent` |
| 构建三件套 | `JAVA_HOME=D:\dsh\fixbug\_toolchain\jdk\jdk-17.0.20.1+1`、`GRADLE_USER_HOME=D:\android\.gradle-pi`、`D:\android\.gradle-pi\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`（仓库刻意不带 gradlew） |
| adb / 装机 | `D:\dsh\fixbug\_toolchain\sdk\platform-tools\adb.exe`；设备 `192.168.0.106`，**无线调试端口会变**（本轮见过 38735→39199→36651），用 `adb mdns services` 重发现 |
| 签名 | sideload 证书 `CN=Lingshu`，SHA-256 `9a33761bfdfa8cc87caaf73f37626b995cbd6acb7121c0d07dfa62df679aa8ee`；验签用 `build-tools\36.0.0\apksigner.bat`（`keytool -printcert` 对 v2/v3 会误报「非签名 jar」） |
| 测试框架事实 | 项目测试用 **org.junit**（`kotlin.test` 不在类路径）；依赖只有 `kotlinx-coroutines-core`（**无 coroutines-test**）；本轮新增 **MockK** 用于替身 Android 绑定类 |
| 团队契约 | `D:\dsh\fixbug\lingshu\docs\v0.8-contract.md`（§0：teammate 禁打包/禁 git 写、写域划分、验证命令、增量编译自救、子代理写序） |
| 出包纪律 | 用户明令：**只出 release，不构建 debug**；打包必须用户放行 |
| meow-memory 语言 | `promptLang: 'zh'`（写在插件自带 patch `…\profiles\web\node_modules\meow-memory\cordis.patch.yml`；该文件在 node_modules 内，插件升级可能被覆盖，重加即可） |

## 2. 问题与根因

### 2.1 多会话并行（只读审计 + lead 亲修）
- `AgentEngine.kt:80/:83/:122-124/:133-135` — 审批门 `_pending` 与 `waiter` 是**全局单槽**：两会话同时要审批时，B 覆盖 A 的 waiter → **A 的 `await` 永不返回**，`finally` 到不了 → `runs[A]` 永留 → 会话被永久锁死。
- `AgentEngine.kt:142-146` — `cancelPending()` 无差别 complete 当前 waiter：停 A 会把 B 的审批判成「用户拒绝」。
- `AgentEngine.kt:356/:362` — `runs.value = runs.value ± …` 非原子（StateFlow setter 不做 CAS）：并发 start 时 **lost-update**，A 在跑却不在 map 里 → `isRunning` 假、`cancel` 掐不断、ticker 空转。
- `AgentEngine.kt:349-354` — 重入守卫是 check-then-act，与 `:356` 注册之间有窗口 → 同会话可起两个 run 写同一 transcript。
- `AgentEngine.kt:277/:285-287` vs `:393/:504-506` — `stateOf()` 读 `states` map，而 `publish()` 只写 `RunHandle._state`，**两个 flow 从未连通** → `stateOf(chatId)` 恒 Idle（聊天页按会话状态全死；悬浮窗读 handle.state 所以正常）。
- `AgentEngine.kt:85/:87` — `alwaysAllowed/alwaysDenied` 是 `mutableSetOf`，被多 run 线程与 UI 线程并发写。
- `AgentEngine.kt:750-752`（真缺陷，由「放宽测试断言」的质疑逼出）— 用户点停时 `inFlight.cancel()` 让 OkHttp 抛 `IOException("Canceled")`，落进 `catch (error: Throwable)` → **publish(Failed)** → VM `ChatViewModel.kt:625-626` 转成 `failure` → **点停弹错误卡**。

### 2.2 会话隔离与草稿（UI 层单槽）
- `ChatViewModel.kt:191`（旧）单槽 `session` + `:543` `onEvent = ::handleEvent` 不带 chatId → 并行事件汇同一槽：后覆盖前=「界面不动」，前写进后=「流污染」。
- `ChatScreen.kt` 局部 `var input by remember` 是**全局单槽草稿** → A 打字切 B 字还在。

### 2.3 工具详情闪退（P0）
- `ChatScreen.kt:2042-2054`（旧）`ToolDetail` 的输出 `Text` 用 `Modifier.fillMaxWidth().verticalScroll(scroll)`，**没有有界高度**；它被 `:1983`（单调用展开）与 `:2159`（落定路径）调用，而两者都在 LazyColumn item 内 → item 拿到**无限 maxHeight** → Compose 抛 `IllegalArgumentException: Vertically scrollable component was measured with an infinity maximum height constraints`。`ImageBlock`（`:3378`）有 `runCatching`，不是崩溃点。

### 2.4 模型选择器空列表
- `ChatScreen.kt:2916` `provider = settings.providers.firstOrNull { it.id == assistant?.providerId }` → 助手未绑 providerId 时为 null → `:2920 models = provider?.enabled.orEmpty()` 为空 → 弹窗显示「这个提供方还没启用模型，去设置里拉取」。

### 2.5 其他定位
- `ChatScreen.kt:1791-1796` 思考内滚硬编码 320dp（正文被挤出屏）。
- `ShellTools.kt` 只有 `capabilities` 无 Context、无特效：agent 用 `run_shell "input tap …"` 驱动应用时**不画点击环**（只有 ui_tap 路径画）。
- `ChatViewModel.kt:599-613` `startRun` 的 onEvent 闭包捕获 chatId；`:620-626` 的 `catch (CancellationException)` → 「已停止」、`catch (Throwable)` → failure。

## 3. 改了什么 + 验证

- **引擎六修**（`AgentEngine.kt`）：waiters/pending 按 chatId 键控；`cancelPending(chatId)`；`runs.update{}` + 按身份删除；注册进 `synchronized` 临界区；`publish()` 镜像 `stateFlowOf(chatId)`（新增私有访问器，因 `StateFlow` 不可写）；允许/拒绝集合改 `ConcurrentHashMap.newKeySet()`。重复启动不再把在跑的 run 打成 Failed。
- **取消语义**（`AgentEngine.kt` catch-all）：`if (cancelled.get()) { finishCancelled(onEvent); return produced }` → 停止落 `Cancelled`，不再冒泡成错误。
- **服务域迁移**：`SchedulerService.kt` fire 改 `startRun(chatId=会话 id)`、提醒永远自跑新会话；`FloatConsoleService.kt` `attachIfRunning/observeRun/stopRun/sendSteering` 全改 runsFlow 键控（球跟 `startedAt` 最大的 run）。
- **UI 层隔离**：`ChatViewModel.kt:213/612/639` sessions map + 闭包捕获 + `handleEvent(chatId,…)` 归属校验；`:78/:218/:235/:239` per-chat 草稿。
- **崩溃修复**：`ToolDetail` 输出 Box 加 `.heightIn(max = 132.dp)`（在 `verticalScroll` 之前），并删掉 `Text` 的 `maxLines=12`（有界后全文可滚）。
- **思考块高度**：320dp→240dp（`ChatScreen.kt:1791-1796`），再引入数据键 `Settings.kt` `val reasoningHeight: Int = 180`（默认 180，外观页三档可调，UI 接线在第 28 批）。
- **input 特效**：`ShellTools.kt` 加 `context` 参数 + file-scope `FX_TAP/FX_LONG_PRESS/FX_SWIPE` 正则 + `drawInputFx()`；`Tool.kt` 调用点改 `ShellTools(context, capabilities)`。
- **测试**：新增 `app/src/test/java/com/suixing/agent/agent/ParallelRunTest.kt` 5 例（两审批共存、停一不误伤、双击只跑一次、stateOf 实时镜像+保终态、取消=掐线 IOException 必落 Cancelled）。
- **第 28 批 UI（0.8.10，ui-shell-2 做、lead 读码验真）**：对话框控件行三枚同 40dp 占位、icon 22dp、`+` 与麦克风纯 icon 无圆底（`ChatScreen.kt:2810-2812`）、麦克入口按 `SpeechRecognizer.isRecognitionAvailable` 门控（`:2644`，无识别器即不显示）；UnifiedSelector 去 Bolt（全仓零命中）；`GenerationSheet`→`Popup` 300dp/20dp 圆角锚 composer 栈顶（`:2965`）、provider 四级回退解析、清单永远含当前模型（`:2964 (enabled + settings.model).distinct()`）、空态「去设置里拉取」经 `onOpenSettings`→`SuixingApp open("settings")`；顶栏换自绘 `res/drawable/ic_menu_soft.xml` + `ic_compose.xml`（painterResource 22dp，`ChatScreen:699-733`）；列表 bottom `composerH + 40.dp`（`:422/:445`）；思考块高度走 `settings.reasoningHeight`（`:458/:497` → ReasoningBlock `:1759/:1811`），外观页三档 `SettingsScreen.kt:1240-1256`。
- 验证：`:app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL 47s，179 tests / 0 failures**（XML 重生成 19:51:13，非缓存）；`:app:assembleRelease` → 3m18s / 3m SUCCESS。
- 出包链（本轮十一次）：bump versionCode/versionName → 写 `dist/notes/release-notes-<ver>.md` → commit → assembleRelease → 归档 `dist/suixing-<ver>.apk` → apksigner 验签 → mdns 重发现端口 → `adb -s <ip:port> install -r` → `dumpsys package` 核对 versionCode/lastUpdateTime。

## 4. 踩过的坑

1. `mergeDexRelease FAILED`（`classes.dex` 被占用）⇒ Gradle 守护进程持有 `app\build\intermediates\dex\release\mergeDexRelease\classes.dex` ⇒ `gradle --stop` 后重构建即绿（不是代码问题）。
2. `kotlin.test` 不在类路径（项目用 org.junit）⇒ 整文件 `import kotlin.test.*` 全部 Unresolved，且编译器只报前 N 条，看起来像局部错误 ⇒ 换 `org.junit.Assert.*`；注意 **JUnit 的 message 参数在第一位**。
3. 无 `kotlinx-coroutines-test` ⇒ `runTest/testScheduler` 不可用 ⇒ 用 `runBlocking` + `CoroutineStart.UNDISPATCHED` 起并发请求。
4. `mockk` 的 `lastArg<(Call) -> Unit>()` 对具名/默认参数取值不可靠（ClassCastException 指向 `execute$1`）⇒ 不要依赖 onCall 注入，改用「测试侧置 flag → mock 抛 IOException」模拟掐线。
5. `wire.started.values.first()` 在 map 还空时**立即抛** `NoSuchElementException`（不是等待）⇒ 必须轮询 `while(true){…delay(10)}` + `withTimeout`。
6. `runBlocking` 里的**局部函数** `fun start() = async(…)` 让类型推断失败并级联出一片「assert 未解析」⇒ 显式写 `: Deferred<List<StoredMessage>>` 或直接内联。
7. Kotlin 增量编译假故障（跨文件符号 Unresolved 但定义文件零错误、class 产物齐全）⇒ 删 `app\build\kotlin\compileDebugKotlin` 与 `app\build\tmp\kotlin-classes\debug` 重编（契约 §0）。
8. 无线调试：同机被登记两个名字（IP 与 mdns 别名）⇒ `adb install` 报 `more than one device` ⇒ `-s 192.168.0.106:<port>` 指定；端口失效报 `10061 目标计算机积极拒绝` ⇒ `adb mdns services` 重发现。
9. 多人/多子代理**同文件写会互相覆盖**（曾出现「已批准改动被回退」「修好又乱」）⇒ 契约 §0 立「子代理写序」条：同一文件单一写者，子代理完全结束后验证才有效。
10. Playwright 拦 `file:` 协议 ⇒ 本地 `python -m http.server` 起静态服务再看；截图落盘相对 Playwright 自己的 cwd（不在页面目录）。

## 5. 当前状态与待办

- 已提交：`1672491` 随行 0.8.10（工具详情闪退修复 + 对话框三钮统一 + 模型弹窗 provider 四级回退 + 顶栏自绘 icon + 思考块高度可配默认 180dp）；本轮完整版本链：0.8.0 `4ca87b3`(vc11) → 0.8.1 `84be7b1`(12) → 0.8.2 `bc21f64`(13) → 0.8.3 `5e0d33f`+`2af9b14`(14) → 0.8.4 `a63c3d6`(15) → 0.8.5 `66c7c49`(16) → 0.8.6 `2fcfe50`(17) → 0.8.7 `7a9df99`(18) → 0.8.8 `18260d4`(19) → 0.8.9 `0fa08bb`(20) → 0.8.10 `1672491`(21)。
- 工作区：**干净**（0.8.10 已全部提交）；`dist/` 被 gitignore，APK 与 release notes 留本地。
- 真机现状：`versionCode=21 / 0.8.10`，`lastUpdateTime=2026-10-09 21:53:06`（adb 直推 Success）。
- 待办：等用户 0.8.10 真机反馈；已知未验证项——语音悬浮卡在真机是否有识别器（无则入口自动隐藏）、任务板/用量页细节、`proot+Alpine`（跑 Pi/Node 生态）为可选项待用户勾；下一个会话继续按「截图 → 派单 → 读码验真 → 出包 → adb 直推」流程。

## 6. 经验（可复用）

1. 「点停弹错误卡」这类**用户能感知的语义 bug**，往往藏在 catch-all 里：掐线抛的是 IOException，不是 CancellationException——凡是「用户主动中断」的路径都要有独立判定，别指望异常类型。
2. 单槽状态是并行架构的头号敌人：审批 waiter、事件漏斗、草稿、状态流——凡是「一个全局变量装一个会话的东西」，多会话一开就会互相污染或死锁。
3. 审计子代理（只读、不写文件）交给它「找洞 + 给文件:行号 + 最小修法」，比让它改代码安全得多，也不与写者冲突。
4. 测试写不动时先问「框架在不在类路径」：`kotlin.test`/`coroutines-test` 缺失会伪装成一堆语法错误。
5. 放宽断言之前先读实现：本轮正是「放宽取消断言」的质疑逼出了真缺陷（否则永远不知道点停会弹错误卡）。
6. 用户对 UI 的判定以**真机截图**为准；下规格必须钉死数值（dp/字号/间距/状态色），否则每轮都会「改了但没改到」。

## 7. ★ 已实测否掉、勿再加回来

| 候选 | 结果 |
|---|---|
| `import kotlin.test.*` | 该类路径不存在，整片 Unresolved ✗ |
| `kotlinx.coroutines.test.runTest` / `testScheduler` | 依赖未引入，编译不过 ✗ |
| 用 `mockk` `lastArg<(Call)->Unit>()` 注入 onCall 来模拟掐线 | ClassCastException（取到 `execute$1`）✗ |
| 把「取消落 Cancelled」断言放宽为「任意终态」 | 掩盖了「点停弹错误卡」真缺陷 ✗ |
| HTML 界面模拟（`ui-mock/`，六屏 + 浅深色） | 用户评价「丑不垃圾的」，已删目录与服务 ✗ |
| 思考块展开用「自然高 Column」（第 17 条） | 撑到几屏高，用户改判为有界内滚（320→240→180 可配）✗ |
| 顶栏纯色实体条（lead 自选的分支 A） | 用户否决，改回透明浮空圆钮 + 标题胶囊 ✗ |
| 模型选择器照搬参考图的闪电图标 | 用户明确「闪电就不需要了，我只是给你图片参考」✗ |
| 给麦克风/加号做圆底按钮 | 用户多次否决（要纯 icon，三钮同 40dp，发送实心圆）✗ |
| 构建 debug APK | 用户明令只要 release，别浪费时间 ✗ |
| 顶栏用 material 默认字形（Menu/Edit） | 用户三次否决「icon 丑」，改自绘矢量 ✗ |
| 模型选择用全屏 ModalBottomSheet | 用户要紧凑弹窗（锚对话框上方 300dp）✗ |
| 生成设置卡里显示「这个提供方还没启用模型」死文案 | 列表必须含当前模型 + 给「去设置里拉取」可点入口 ✗ |

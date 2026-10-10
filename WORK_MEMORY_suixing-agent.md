# 随行 Suixing · 唯一工作记忆（合并版）

> 本文件是**唯一**记忆文件。原 `WORK_SESSION_2026-10-09_suixing-agent-v08.md` 与
> `WORK_SESSION_2026-10-10_suixing-agent-ui-iterations.md` 已**移动**到
> `dist/archive/memory-history/`（`dist/` 在 .gitignore 内）。`dist/TODO-LEDGER.md` 是**账本不是记忆**，保持原位、不许动。
> 素材来源：`git log --format="%H%n%ad%n%B"` 全量提交正文（70 个提交）、`dist/notes/release-notes-*.md`（27 份）、
> `dist/commit-msg-*.txt`（39 份）、`dist/TODO-LEDGER.md`、两份旧 WORK_SESSION、`README.md`、`docs/v0.8-contract.md`、当前工作树源码（只读核对）。
> 行号口径：**以当前工作树为准**（HEAD `707da4b` ＋ 若干未提交改动，见 §6）；旧行号一律标注「旧行号」。
> ⚠️ 工作树当时还有其他并行改动（`ui/chat/ChatViewModel.kt`、`ui/components/Common.kt`、`ui/settings/SettingsScreen.kt`、`service/TouchFx.kt`、`test/.../InterruptPersistenceTest.kt` 均为 `M`），**行号会随后续改动漂移**；引用前先按符号名 grep 复核。

---

## 1. 项目与环境事实

| 项 | 值 |
|---|---|
| 仓库路径 | `D:\dsh\fixbug\suixing`（**曾用名** `D:\dsh\fixbug\lingshu`；改名必须先退出 DSH，它的 node 进程持有工作区否则 Rename-Item 报 used by another process；Git 无需处理，仓库内全是相对路径） |
| 包名 | `com.suixing.agent`（0.7.0 起从 `com.lingshu.agent` 更名 ⇒ **全新安装**，会话与设置不迁移）；debug 变体 `applicationIdSuffix ".debug"`、`versionNameSuffix "-debug"`（`app/build.gradle.kts:13`、`:32-33`） |
| 当前版本号 | `versionCode 85` / `versionName "0.8.74"`（`app/build.gradle.kts:16-17`） |
| 构建三件套 | `JAVA_HOME=D:\dsh\fixbug\_toolchain\jdk\jdk-17.0.20.1+1`；`GRADLE_USER_HOME=D:\android\.gradle-pi`；gradle 可执行 = `D:\android\.gradle-pi\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`（**仓库刻意不带 gradlew**）。README 记：JDK 17 + Android SDK（`platforms;android-35`、`build-tools;35.0.0`） |
| adb | `D:\dsh\fixbug\_toolchain\sdk\platform-tools\adb.exe`；无线调试，真机序列号形如 `adb-d2e1026e-PZkPcd._adb-tls-connect._tcp`（**每次会变**）。**`adb devices` 空列表 ＝ 无线调试断了**（不是端口变了、不是设备不在）；曾错误归因被纠正。旧记录里见过 IP 端口 `192.168.0.106` + `38735→39199→36651`，用 `adb mdns services` 重发现 |
| 验签 | `build-tools\36.0.0\apksigner.bat`（`keytool -printcert` 对 v2/v3 包会误报「非签名 jar」，别用它判签名） |
| 签名 | `app/sideload.keystore` 自签，**alias / 密码均为 `lingshu`**（`app/build.gradle.kts:23-26`、`README.md:171`）——**不是路径、不要改**。证书 CN=Lingshu，SHA-256 `9a33761bfdfa8cc87caaf73f37626b995cbd6acb7121c0d07dfa62df679aa8ee`，同钥可覆盖升级 |
| 仓库/Release | remote `https://github.com/zeng6125-rgb/suixing.git`（**由 lingshu-agent 改名而来**，已公开）；分支 `main`；已打 tag `v0.1.1 v0.2.0 v0.3.0 v0.4.0 v0.4.1 v0.5.0 v0.6.0 v0.7.0 v0.8.70 v0.8.72 v0.8.73`（**无 v0.8.74**） |
| logcat | **完全不可用**（`logcat -c` 与 `-d` 都报 `Logcat read failure: No such file or directory`，root 同样）→ 崩溃排查走 `dumpsys dropbox --print`（历史 dump 存 `dist/dropbox*.txt`） |
| screenrecord | **不可靠**（连续多次 `moov atom not found`，`dist/rec*.mp4` 里多个 0 字节）→ 逐帧验证改用**应用内临时日志**（临时写 `files/*.log`，量完删） |
| 单测基线 | **241/241 绿**（28 类，failures=0/errors=0），含 `GroupRowsTest` 6 / `SteerRenderTest` 5 / `InterruptPersistenceTest` 4；命令 `:app:testDebugUnitTest` |
| 测试框架事实 | 项目用 **org.junit**（`kotlin.test` **不在类路径**）；依赖只有 `kotlinx-coroutines-core`（**无 coroutines-test**）；0.8.9 起新增 MockK |
| 出包纪律 | 用户明令**只出 release 不构建 debug**；打包**必须用户放行**；release 构建走 `:app:assembleRelease`，产物归档 `dist/suixing-<ver>.apk` |
| 团队契约 | `docs/v0.8-contract.md`（§0 teammate 禁打包/禁 git 写、写域划分、验证命令、增量编译自救、子代理写序） |
| meow-memory | promptLang `'zh'` 写在 `~/.dsh/profiles/web/node_modules/meow-memory/cordis.patch.yml` |

### 出包链（十一次以上，固定顺序）
bump `versionCode`/`versionName` → 写 `dist/notes/release-notes-<ver>.md` → commit → `:app:assembleRelease` →
归档 `dist/suixing-<ver>.apk` → `apksigner` 验签 → `adb mdns services` 重发现端口 →
`adb -s <ip:port> install -r` → `dumpsys package com.suixing.agent` 核对 `versionName`/`versionCode`/`lastUpdateTime`。

---

## 2. 版本时间线（最早 → 0.8.74）

**灵枢 Lingshu 期（包名 `com.lingshu.agent`，无独立记忆文件，事实全部来自 commit 正文）**

| 版本 | commit | 内容 | 证据 |
|---|---|---|---|
| 0.1.0 | `a092077` 2026-10-08 02:07 | 手机端 AI Agent：纯 Kotlin+Compose，minSdk 26 / targetSdk 35，无 NDK；三档能力（无障碍/Shizuku/root）；30 工具（系统 8/文件 9/Shell 2/UI 11）；OpenAI 兼容 SSE 流式 + tool_calls 组装；审批三模式；四界面；CI 出 debug+release | commit 正文 |
| 0.1.1 | `e6799be` 02:31 | 修审查发现的 6 项 P1 + 11 项 P2：允许明文 HTTP、审批弹窗移出 NavHost、发送前清悬空 tool_calls（修「停止后永久 400」）、工具处重抛 CancellationException、`delete_path` 改 canonicalPath+保护清单（recursive 默认 false）、find_files/search_in_files 支持中断、截图/列目录/device_info 移出主线程、图片输入开关、备份排除 datastore | commit 正文；`dist/notes/release-notes-0.1.1.md` |
| 0.2.0 | `f79b9d7` 02:55 | MCP 客户端（协议层/stdio+http 传输/连接管理，工具注册为 `mcp__服务器__工具`）+ 技能库（SKILL.md 解析、catalogue 注入系统提示词、按需 skill_read、SAF 批量导入）+ 3 个技能工具（内置 30→33）+ 扩展页（MCP/技能两 tab）+ 8 单测（MCP 协议 6 + stdio 真实子进程 2）；vc 2→3 | commit 正文；notes-0.2.0 |
| 0.3.0 | `79a7969` 03:08 | 联网（抓取/下载/搜索）+ 语音（TTS 朗读、语音输入） | commit 正文；notes-0.3.0 |
| 0.4.0 | `f5ef0c5` 03:59 | 界面重做（配色对齐 Aether：暖黑 `#151619` / 柔紫 `#C0AEFF`）；新增 `ui/theme/Color.kt`/`Type.kt`、`ui/components/`（LingshuMark/LingshuOrb/LingshuCard/LingshuTopBar/SegmentedPill/StatusPill/SoftIcon/SettingRow/EmptyState）；五界面重写；edge-to-edge + 浮动胶囊导航；图标改细腰四角星；vc5，单测 14/14 | commit 正文；notes-0.4.0 |
| 0.4.1 | `ed2f4ed` 12:16 | 获取模型列表 + 允许自签名证书 + 报错翻译 | commit 正文；notes-0.4.1 |
| 0.5.0 | `a62653c` 12:47 | 多提供方接口（胶囊切换/新建/删除，地址与 Key 随切换）、模型按厂商分组多选（每组可全选）、推理模型 `reasoning_content` 实时流出包在可折叠「思考过程」块、修工具轮空文本灰气泡；新增 ProviderModelTest 10 例，单测总数 35 | commit 正文；notes-0.5.0 |
| 0.5.1 | `3ece402` 13:02 | 工具调用收进回复气泡，一轮对话一个整块 | commit 正文；notes-0.5.1 |
| 0.6.0 | `79b8b26` 14:00 + `b7c68cd` 14:06 | 助手（人设/模型/工具开关）与消息分叉（重新回答产生候选、可切换）；历史消息气泡也显示当前助手头像 | commit 正文；notes-0.6.0 |
| （无版本号） | `a578267` 15:11 | 忽略 `dist/`：打包产物与发布记录留在本地 | commit 正文 |

**随行 Suixing 期**

| 版本 | commit（vc） | 内容 | 证据 |
|---|---|---|---|
| 0.7.0 | `0ea2763` 2026-10-08 18:22 | 更名 + 界面重做（侧边抽屉、Markdown 渲染、工具合卡、消息可编辑、token 统计、生成参数） | commit 正文；APK `dist/suixing-0.7.0.apk`（**无 notes**，素材缺失） |
| 0.8.0 | `4ca87b3` 22:11（vc11） | 运行态解耦（运行中可切会话/点停即停/插话）、左栏与气泡重做、白羽图标、真屏注入回执与悬浮窗控制台、状态落盘补全；root 探测落盘缓存、alwaysDeniedTools 落盘；80 tests/8 suite（含 UiDumpTest 11） | commit 正文；notes-0.8.0 |
| 0.8.1 | `84be7b1` 23:06（vc12） | 对齐四张参考截图的界面重做（透明顶栏/悬浮胶囊/parts 时序/工具折叠）、插话双模式与落盘、上下文自动压缩+监控、设置七节、墨绿品牌色与渐变图标底；82 tests（Transcript 14 含 steer 落盘 2） | commit 正文；notes-0.8.1 |
| 0.8.2 | `bc21f64` 2026-10-09 00:45（vc13） | 悬浮窗控制台重做（开关门控/换肤/详情行/前台收起）、屏幕点击特效（扩散环+轨迹+边缘闪 800ms 节流）、SKILL.md 技能生态、Markdown 排版 overhaul、顶栏四槽与设置两层；105 tests | commit 正文；notes-0.8.2 |
| 0.8.3 | `5e0d33f` 01:57（vc14）+ `2af9b14` 02:02 | 单气泡流式+实时工具行+顶部折叠汇总卡、追底真修（programmatic 标志）、透明底栏与扩散阴影、上下文分解弹窗与会话状态条、轮次导航、思考 6 档与 per-model 上下文窗口上模型页；`2af9b14` 补：设置页草稿去键 + 上下文窗口两处 write-through；105 tests 0 failures | commit 正文；notes-0.8.3 |
| 0.8.4 | `a63c3d6` 03:37（vc15） | 双气泡根因修、落定折叠只留末段正文、透明顶栏圆钮、胶囊真阴影、底部沉浸、追底归位、横线轮次导航、白羽图标族、思考 pill 进对话框、provider 独立采样与结构即落盘；105/105 | commit 正文；notes-0.8.4 |
| 0.8.5 | `66c7c49` 11:57（vc16）+ `b37315c` + `b26cb38` + `ec45752` + `dfc90e0` + `f2d4afb` + `67e02fc`（均 04:40–04:48） | harness 能力块：后台 shell 三件套 `job_start/job_list/job_output/job_kill`（日志 `filesDir/jobs/<id>.log`，kill=SIGTERM→2s→SIGKILL）、持久任务板 `todo_write/todo_read` + 任务页、定时提醒 `schedule_*` + AlarmManager 单闹钟自 arm + BootReceiver 自愈、用量页（三卡/30 日柱状/按模型小计）、MCP 指数退避重连 2/4/8/16/30s 封顶、skill 开关落盘 `skill-state.json`；UI 返修 13 条；147/147（15 suite） | commit 正文；notes-0.8.5 |
| 0.8.6 | `2fcfe50` 13:01（vc17） | 思考流式真修（根因在 `LlmClient` 字段抽取：8 候选键位 + 结构化拍平 + 空白过滤，ReasoningStreamTest 22 条其中 6 条旧代码必败）；UI 9 条（汇总卡矮化/首行预览/删除确认/顶栏边距/渐隐过渡/scrubber 4 槽/pill 入胶囊）；169/169（16 suite） | commit 正文；notes-0.8.6 |
| 0.8.7 | `7a9df99` 16:08（vc18） | 过程块族对齐（Build 图标/自展自收/工具行预览/同卡单行/展开自然高）、live 气泡助手名根因修、错误横幅锚位、涟漪有界、provider 名称与模型别名/自定义/移出；169/169 | commit 正文；notes-0.8.7 |
| 0.8.8 | `18260d4` 18:02（vc19） | 会话独立运行（`AgentEngine` 单 run → 按 chatId 多 run，新 API `runsFlow/stateOf(chatId)/isRunning/cancel(chatId)/steer/startRun`，旧全局签名删净）+ UI 18–22 条（顶栏三槽/抽屉开关行/汇总卡 36dp/生成设置卡/思考内滚/IME 实测高/围栏根因修/icon 同插槽）；174/174 | commit 正文；notes-0.8.8 |
| 0.8.9 | `0fa08bb` 20:01（vc20） | 多会话并行引擎审计六修（审批门键控/原子注册/stateOf 镜像/点停不再报错）+ 会话隔离与 per-chat 草稿 + 工具输出与截图渲染 + `input tap` 特效补漏；新增 ParallelRunTest 5 例；179/179（`--rerun-tasks` 非缓存） | commit 正文；notes-0.8.9 |
| 0.8.10 | `1672491` 21:48（vc21）+ `ee8611c` 22:00 | 工具详情闪退修复（无界滚动）+ 对话框三钮统一与模型弹窗（provider 四级回退/永远含当前模型）+ 顶栏自绘 icon + 思考块高度可配（默认 180dp）；`ee8611c` = 忽略 meow-memory 本地库目录、工作记忆更新至 0.8.10；179/179 | commit 正文；notes-0.8.10 |
| 0.8.11 | `8c64c1c` 23:51（vc22） | 会话隔离（模型/助手按会话各记各的，`choiceFor`）+ 插话排队（steer 带 id、`AgentEvent.Steered` 送达才出气泡）+ 压缩 checkpoint 落盘复用 + 中断留存（LiveDraft 1s 节流 + 冷启动合并「（中断）」）+ 语音可见化 + 上下文圆环 + 打开/切换应用边缘流光；**前缀一致性实测：同任务内 prompt cache 命中 99%、整体 93%，隔 5 分钟以上首轮掉到 21–25%**；208 全绿（21 XML mtime 23:49:51） | commit 正文；notes-0.8.11（标注「草稿·待最终复跑」） |
| 0.8.12 | `4bf7ae6` 2026-10-10 01:27（vc23） | **0.8.11 启动即闪退修复** + 设置页串数据 + 冷启动骨架与「退出时的会话」+ 图标统一 Lucide（55 drawable/110 处）+ 模型弹窗两页化 + 插话排队行 + 边缘呼吸光；216 全绿（23 XML mtime 01:23:54） | commit 正文；notes-0.8.12 |
| 0.8.13 | `6dc1502` 02:30（vc24） | provider 改名顶栏抖动根因（副标题脏标记每键闪动）+ 停止图标实心方块 `ic_stop_filled.xml` + 圆环「设置窗口」对话框（k 单位/留空=不限/建议值不静默写入）+ 呼吸光四条（0.16↔0.40、圆角路径 r=52px 内缩 8px、run 并集+5s 宽限+灯亮不摘窗、驱动后拉回前台）；219 全绿（24 XML mtime 02:28:44） | commit 正文；notes-0.8.13 |
| 0.8.14 | `33560fb` 03:48（vc25） | 截图按字符计价高估 100~900 倍 → 改按张 1000 token/张 + 以「上轮真实 promptTokens+本轮新增」为主；流式残影（Markdown 缓存键=整段文本）→ 增量合并 50ms 发布 + 四处强制冲刷；长按可选复制；上游 `context_window` 默认写入（Qwen3.8-Flash 声明 180000）；圆环改纯展示；`fx-trace.log` 诊断；思考强度按会话隔离；226 全绿（25 XML mtime 03:47:59） | commit 正文；notes-0.8.14 |
| 0.8.15 | `fa35290` 04:40（vc26） | 撤回自加 IME 动画（与平台打架 + 多算一条导航栏 inset）；边缘光圆角 52→96px、内缩 8→3px、线宽 10→14px、透明度 0.22–0.55；长按 >450ms 停止追底；Markdown 增量解析（空行前复用、围栏内空行跳过）+ 发布 50→80ms；用时/用量两按钮拆分；tokens≥1000k 用 M；**会话累计对账 1262 条消息 prompt 143,798,167 + completion 463,731 = 144,261,898 与界面一致**；226 全绿 | commit 正文；notes-0.8.15 |
| 0.8.16 | `d583c02` 13:08（vc27） | 抬起时最后一张卡改动画跟随；手势条 inset 排除被键盘盖住部分；背景画在窗口层；新条目等一帧后再钉一次；边缘光只跟随动作 4s 熄灭 + `FLAG_LAYOUT_IN_SCREEN` + 三层泛光最宽 56px；用时/用量移到右侧同组、图标 15dp、详情卡数值右对齐；思考块「默认展开」只作用流式块；226 全绿（25 XML mtime 13:05:50） | commit 正文；notes-0.8.16 |
| 0.8.17 | `b47bbb9` 13:32（vc28） | 跟随动画回退为瞬时定位（与平台动画打架＝先上冲再回落）+ 保留「等一帧后再定位一次」；「正在思考…」真源是 VM status 字符串（界面兜底不生效）→「正在思考中」/「正在调用 x」；详情弹窗 `IntrinsicSize.Max` 按最宽行取宽；键盘圆角缝＝窗口背景色夜间 `#151619` vs 应用 `#0F1412` → 统一并随主题同步；状态行上 16dp 下 0；226 全绿。**结尾作者自述：上一版把「键盘圆角/状态行被挡/顶起动画」当已解决上报但未真机验证，属错误** | commit 正文；notes-0.8.17 |
| 0.8.18 | `2e29ba2` 13:49（vc28+） | uiautomator 实测 gap：修前收起 48px(16dp)/抬起 108px(36dp)，多出 60px 因底部留白按「输入框高度」算 → 改按「输入框顶边→列表底边」实测距离（`onGloballyPositioned` 取两端），修后两态均 48px；状态行遮挡同根因（按视口高度变化触发 + 新条目测量后补一次）；详情卡宽度＝最宽行+最少 208dp；226 全绿。**无 release notes**（素材缺失，事实取自 commit 正文 + `dist/commit-msg-0.8.18.txt`） | commit 正文 |
| 0.8.19 | — | **素材缺失**：无 commit、无 notes，仅存在 `dist/suixing-0.8.19.apk` | — |
| 0.8.20 | `1a2f821` 14:21 | 按视口高度变化量补偿会漂移（留白本身随键盘变）→ 改「动画钉到底部」`animateScrollToItem`+`animateScrollBy`；实测打字时应用区像素 `#DEDFE2/#D9DADD` vs 收起 `#F7F9F8/#F8FAF8`（约 10% 均匀变暗）；`dumpsys window` 证明遮罩来自输入法 WeType（fillxfill/TRANSPARENT）与 MIUI FloatingWindow/AntiMistakeTouchView，**不是随行画的** | commit 正文；`dist/commit-msg-0.8.20.txt` |
| 0.8.21 | `8bf113f` 14:54 | 用户指出「明明就加了动画」属实——`ChatScreen.kt` 旧 `:499-500`、`:539-540` 两处 `animateScrollToItem/animateScrollBy`，改回瞬时（=0.8.15 行为）；composer 自身无自加动画，跟随平台 IME inset 动画 | commit 正文；`commit-msg-0.8.21.txt` |
| 0.8.22 | — | **素材缺失**：无 commit、无 notes，仅存在 `dist/suixing-0.8.22.apk`（与 0.8.18–0.8.21 同为 13,070,842 B） | — |
| 0.8.23 | `c75050c` 15:33 | 键盘处理整体回退到 0.8.15（0.8.16~0.8.22 改动全撤销）；manifest 回 `adjustResize`；收起单调平滑，抬起仍有约 15px(2 帧)先下后上，三种实现（含 0.8.15 原样）都存在 ⇒ 判为平台/ROM 两步 inset（**待验证假设，未证实**） | commit 正文；`commit-msg-0.8.23.txt` |
| 0.8.24 | `786bed1` 15:42 | 恢复实测边距 `stackFootprint`，保持无动画 | commit 正文 |
| 0.8.25 / 0.8.26 | — | **素材缺失**（无 commit、无 notes）；仅从 0.8.27 正文得知：**这两次安装无效**（正则替换失败 / 构建失败仍装了旧包） | 0.8.27 commit 正文 |
| 0.8.27 | `25e9ed2` 15:49 | 0.8.24 录屏实测：键盘收起动画结束后约 4 秒对话框自己再下沉 68px（≈手势条高度），来源 composer 底部 `windowInsetsPadding(navigationBars.exclude(ime))` 在 IME 完全消失后才加回手势条 → 改回 `navigationBarsPadding()`；复测起 2225→终 2222~2225 无漂移 | commit 正文；`commit-msg-0.8.27.txt` |
| 0.8.28 / 0.8.29 | `5a21a66` 15:59 | 麦克风图标 22→18dp、按钮 40→36dp；圆环视觉 22→18dp、描边 2.5→2dp、触摸区 32dp；实测收起 gap=48px / 抬起 gap=48px 两态一致 | commit 正文；`commit-msg-0.8.29.txt` |
| 0.8.30 | — | **素材缺失**：无 commit、无 notes，仅存在 `dist/suixing-0.8.30.apk` | — |
| 0.8.31 | `e4f20ab` 16:08 | 收起「先沉后弹」根因＝列表底部留白逐帧跟输入框顶边但滚动偏移不动 → 改 `scrollBy(-Δviewport)` 同帧回滚；录屏 11.4fps 收起 cy 1586→2190 单调减速、仅末帧 −6px；图标间距收紧（麦克风 30dp/圆环 26dp/spacedBy 4dp） | commit 正文；`commit-msg-0.8.31.txt` |
| 0.8.32 | — | **素材缺失**（无 commit、无 notes）；仅从 0.8.33 得知它是「视口高度帧循环补偿」那版，用户报**抬起时卡片不顶起** | 0.8.33 commit 正文 |
| 0.8.33 | `20af4a3` 16:19 | 0.8.31/0.8.32 的「视口高度帧循环补偿」抬起方向失效 → 回退 0.8.15 触发器 `LaunchedEffect(ime inset)`+瞬时贴底；保留 `navigationBarsPadding`、`stackFootprint` 两态 16dp；逐帧复测 10.1fps 抬起 2180→1574 单调、收起 1586→2203 仅末帧 −5.2px | commit 正文；`commit-msg-0.8.33.txt` |
| 0.8.34 | （并入 `b2d9126`） | 方向钳制（反向 ≤24px 视为回弹不跟随） | 0.8.37 commit 正文 |
| 0.8.35 | （并入 `b2d9126`） | `ime-trace.log` 诊断（平台 inset 真值+viewport+滚动偏移，400ms 节流） | 同上 |
| 0.8.36 | （并入 `b2d9126`） | 滚动 watcher 加 120ms 时间戳门（修「把自己 pin 误判为用户拖拽 → following 被关」的竞态） | 同上 |
| 0.8.37 | `b2d9126` 16:37 | 收起方向不再 pin（交给列表 clamp），消末尾 +7px 跳变；**用户 120Hz 真机确认「下沉消失、接近完美」** | commit 正文；`commit-msg-0.8.37.txt` |
| 0.8.38 / 0.8.39 | — | **素材缺失**（无 commit、无 notes）；仅从 0.8.40 得知 **0.8.39 去掉了 `withFrameNanos`**（它是 `LaunchedEffect(keyboardInset)` 里的第一个挂起点） | 0.8.40 commit 正文 |
| 0.8.40 | `282521c` 16:49 | 根因日志实锤——`LaunchedEffect(keyboardInset)` 每帧重启，`scrollToItem/scrollBy` 是挂起函数，在挂起点被下一帧重启取消 → off 全程冻结＝抬起生硬；换非挂起 `dispatchRawDelta`；日志抬起 off 3273→4173 逐帧跟随 viewport 2466→1550 | commit 正文；`commit-msg-0.8.40.txt` |
| 0.8.41 | （并入 `03999f2`） | `stackFootprint` 端点先取整到 Int 像素再相减（Float→Dp→px 两次舍入致两态差 1-2px） | commit 正文 |
| 0.8.42 | （并入 `03999f2`） | manifest 改 `adjustNothing`（窗口不被键盘改尺寸，圆角处不再露窗口底色） | 同上 |
| 0.8.43 | （并入 `03999f2`） | 根容器背景 `background(白)` → `surface`（画布色）；实测圆角处 `#FFFFFF` vs 画布 `#F7F9F8` | 同上 |
| 0.8.44 | — | **素材缺失**：无 commit、无 notes，仅存在 `dist/suixing-0.8.44.apk` | — |
| 0.8.45 | `5daabad` 16:59 | 窗口/根容器/Scaffold 三层全改主题 surface 后圆角像素仍 `#FFFFFF` ⇒ 判该白色与上方约 4% 变暗出自输入法自身全屏窗口/键盘体绘制，非随行所画 | commit 正文；`commit-msg-0.8.45.txt`；`dist/verify-0845.log` |
| 0.8.46 / 0.8.47 / 0.8.48 / 0.8.49 | — | **素材缺失**（无 commit、无 notes）；仅从 0.8.50 得知 **0.8.47/0.8.48 的「根层避开 IME + 窗口透明」组合当时未校验 `mInputShown`，采样无效，不能作为结论** | 0.8.50 commit 正文 |
| 0.8.50 | `960fd50` 17:21 | 品红探针证实圆角像素由随行根容器背景层绘制，但根容器画 surface(纯白) 与画布渐变浅绿 `#F8FAF8` 本就不同色 | commit 正文；`commit-msg-0.8.50.txt` |
| 0.8.51 | `47cf416` 17:26 | 根层对整窗画与画布同一渐变（暗色纯色）+ 窗口背景透明 + `adjustNothing`；实测左右圆角 `#FFFFFF` → `#F8FAF8/#F7F9F8/#F8FAF9` 与画布同色；清理 MainActivity 死常量 `LIGHT_BACKGROUND/DARK_BACKGROUND` | commit 正文；`commit-msg-0.8.51.txt` |
| 0.8.52 / 0.8.53 | — | **素材缺失**（无 commit、无 notes）；从 0.8.54 得知：0.8.52 = 根层避键盘（圆角变白）、0.8.53 = `windowIsTranslucent`（圆角仍白），**两次实验均实测失败已撤销** | 0.8.54 commit 正文 |
| 0.8.54 | `2e0ca03` 17:41 | 撤销 0.8.52/0.8.53；`themes.xml` 恢复 `windowBackground=@color/window_background`、删 `windowIsTranslucent`；`stackFootprint` 改先减后转（修两态 1-2px，**未复测**）；实测圆角 `#C6C9C7` ＝画布色被输入法背景压暗值 | commit 正文；`commit-msg-0.8.54.txt` |
| 0.8.55 | `1bb1f55` 17:46 | 根因几何非颜色——根层渐变跨 0..2510、画布渐变跨 0..1550，同一渐变两种跨度在键盘顶边那行进度不同（62% vs 100%）；修法根层渐变 `endY=窗口高-IME 高`（=画布底边）；实测 `mInputShown=true`、采样按 IME touchable region 顶边 y0=1550 相对取：左圆角 `#F2F6F4`、右圆角 `#F3F6F4`、键盘正上方画布 `#F0F4F2` 同族连续；(578,1490) 的 `#FFFFFF` 是输入框胶囊自身白色非缝 | commit 正文；`commit-msg-0.8.55.txt` |
| 0.8.56 | `e768dc6` 17:51 | 圆角封板（根层渐变 `endY`=画布底边）+ 键盘跟随封板（抬起逐帧 `dispatchRawDelta`、收起靠 clamp、无自加动画）；删 `ChatScreen.kt` 里 `ime-trace.log` 诊断块（1010 字符） | commit 正文；`commit-msg-0.8.56.txt` |
| 0.8.57 | — | **素材缺失**：无 commit、无 notes，仅存在 `dist/suixing-0.8.57.apk` | — |
| 0.8.58 | `1b8a7ce` 18:01 | 收起不再「列表 clamp + 300ms 后补 pin」（clamp 末帧与 padding 不同步停 8px 短，实测 B 态 gap=40 vs A 态 48）；改两向同一机制每帧非挂起 `dispatchRawDelta`；实测 A 新鲜到底 gap=48 / B 抬起再收起 gap=48 严格一致 | commit 正文；`commit-msg-0.8.58.txt` |
| 0.8.59 | `b3a549d` 18:14 | 回弹根因＝pin 跑在 inset effect 里（布局之前）用上一帧输入框位置；pin 移入 `onTopInWindow`（布局之后当帧）；模型弹窗改右对齐输入框内容区右缘，删除 `modelButtonCenterPx` 全链 | commit 正文；`commit-msg-0.8.59.txt` |
| 0.8.60 | `2eef7ed` 18:35 | 添加按钮弹窗 offset −8dp→−48dp；模型弹窗锚点移回模型胶囊自身 Box；追底释放改向上拖 12dp 即释放（长按 450ms 保留）；中断落盘：半截答案带 reasoning、未应答工具调用补 TOOL 消息「已中断：任务被停止，这次调用没有结果」、sanitize 不丢被中断轮；229/229（InterruptPersistenceTest 3 条） | commit 正文；`commit-msg-0.8.60.txt` |
| 0.8.61 / 0.8.62 / 0.8.63 | — | **素材缺失**（无 commit、无 notes）；0.8.61-0.8.63 曾被（错误地）判为「全坏」，实为**写死坐标采样量到键盘体内导致的误判** | 旧 WORK_SESSION 踩坑记录 |
| 0.8.64 | `5bd2dfd` 19:03 + 补漏 `5e25ddf` 19:08 | Bug A 气泡分裂＝`groupRows` 的 TOOL 分支在纯 tool-call 轮（`isVisible=false`）没把 `openIndex/openRun` 指到当前卡；Bug B 插话不实时＝新增 `LivePart.Steer`；特效改 `TYPE_ACCESSIBILITY_OVERLAY`（回退 `TYPE_APPLICATION_OVERLAY`）、边缘路径用 `currentWindowMetrics` 真实屏幕 bounds(inset 0)、保持窗 4s→6s、服务断开强制拆窗；键盘/圆角配置回退 0.8.60；230/230。补漏 `5e25ddf`：tool-only 卡片用 `row.parts` 重建（保住同卡后续工具调用与插话行） | commit 正文；`commit-msg-0.8.64.txt` |
| 0.8.65 | `8511e03` 19:33 | 模型弹窗锚到胶囊顶边零高探针；右对齐 nudge 补 `anchorRight` 写入（原恒 0）；压缩入口常显；分组标题统一 `labelMedium`、弹窗宽 300→340dp；RingDetail 数值色 alpha 0.72；`prefetchWindows` 遍历全部 provider；SuixingApp inset 钳制回归；新增 `GroupRowsTest`（rounds 只计可见轮为**有意设计**）；236/236 绿（27 XML） | commit 正文；`commit-msg-0.8.65.txt` |
| 0.8.66 | `491e0f8` 20:26 | composer 底部 inset＝「导航栏 inset − 已钳制键盘高度」（原来 `navigationBarsPadding` 在键盘盖住手势条时仍生效＝那条大空白），钳制值经 `LocalHeldImePx` 下发；SuixingApp 钳制块提到 `CompositionLocalProvider` 之前；`TurnScrubber` 三处对齐 Aether（fadeOut 150→110ms、气泡 y 改 `animateFloatAsState(110ms, CubicBezierEasing)`、松手取消判据改 rail±28dp 四边盒且仅对已过 18dp 阈值手势生效）；travel 改 `max(|dy|,|dx|)`；241/241（28 类） | commit 正文；`commit-msg-0.8.66.txt` |
| 0.8.67 | `a7a11c4` 21:12（vc78） | 彩虹边缘光（SweepGradient 八色首尾同色 + 2s 旋转、一笔闭合 `addRoundRect`、API31 `Display.getRoundedCorner` 真实圆角、线宽 14→8px）+ 工具页三 tab（48 工具 10 组）+ 权限四档 + 聊天页四修（弹窗宽度/锚点/键盘不抢焦点/`SelectionContainer` 延帧）+ `LocalHeldImePx` 改 `compositionLocalOf` | commit 正文；`commit-msg-0.8.67.txt` |
| 0.8.68 | `fcc17fd` 22:03（vc79） | NavHost 转场 + `PageTopBar` 56→64dp + 发丝线 + 12dp 空隙 + 权限「执行档次」四段（`AppSettings.preferredCapability`，**执行链未接入**）+ `compressAtPercent` 20–95 默认 80 + 工具页行高固定 56dp + 弹窗文案回简 + 数值 SemiBold + 模型组头统一 `bodyMedium`（上限 300dp） | commit 正文；`commit-msg-0.8.68.txt` |
| 0.8.69 | `ca33cb3` 22:15（vc80） | 设置页 IA 整理（数据页只留 用户记忆/助手设置/会话文件；MCP 迁入工具页；新增 `AboutScreen.kt`、about/assistants 路由；关于独立页） | commit 正文；`commit-msg-0.8.69.txt` |
| 0.8.70 | `abf32c0` 22:27（vc81） | 用户画像 v1（`data/UserProfile.kt` → `filesDir/profile.md` 上限 8000、tmp+rename、坏文件 `profile.corrupt.md`；注入顺序 用户提示词→可用工具→技能目录→「## 你记得的事」→「## 关于用户（画像）」→工具约定；工具 `profile_write/profile_read` tier=none；`ProfileScreen` minLines=8）+ 顶栏蒙版三档 18%/6%/0；**Release v0.8.70 已发** | commit 正文；`commit-msg-0.8.70.txt`；TODO-LEDGER:41 |
| 0.8.71 | **无独立 commit**（vc82） | 转场整幅推入 + 顶栏去分割线 + 助手独立整页（抽 `AssistantListContent`）+ 用户画像模型自动总结（`agent/ProfileSummarizer.kt` + `filesDir/profile.state.json`、`autoProfile` 默认 true、`profileEveryTurns` 默认 8、整篇替换、独立请求不进主 wire）+ 用量弹窗「提供方 / 模型」+ 圆环内百分比 7sp Bold | TODO-LEDGER:38 |
| 0.8.72 | `0a603b7` 23:29（vc83） | **边缘光覆盖状态栏**（AOSP：`setFitInsetsTypes(0)` + cutout ALWAYS + `maximumWindowMetrics`）+ 16 段渐隐光晕 `(1-t)^1.55`、宽 `min(w,h)*0.046` + **中断留存真根因**（`LiveDraft` 补 reasoning/messages）+ 过程卡 `PROCESS_TITLE_MAX=160.dp` + 预览气泡 `fillMaxWidth` + 圆环缓存命中改 `SessionStats.cachePercent` 总命中率；**Release v0.8.72 已发** | commit 正文；`commit-msg-0.8.72.txt`；TODO-LEDGER:39,41 |
| 0.8.73 | `a11dc8b` 23:41（vc84） | 思考块流式跟随（即时 `scrollToItem` + `snapshotFlow` 观察用户接管 + 120ms 护栏、不 consume 手势）+ `AppSettings.followReasoning` 默认 true + 「展开全部 N 行」`heightIn(min=32dp)` + 全站按钮统一（盘径 40dp 默认/44dp 两把手、图标 20dp、触摸区 ≥48dp、新增共享 `PressPill`，3 处故意例外写进 KDoc）；**Release v0.8.73 已发** | commit 正文；`commit-msg-0.8.73.txt`；TODO-LEDGER:40-41 |
| 0.8.74 | `707da4b` 2026-10-11 00:00（vc85，**HEAD**） | **B1 长按选中文字时滚动跳变修复**：手指按下列表那一刻就释放追底，抬手时只有「短按（<450ms）且未上滑（<12dp）」才还原按下前状态；手势仍只在 `PointerEventPass.Initial` 观察、不 consume；`HOLD_TO_SELECT_MS`/`RELEASE_DRAG_DP` 数值语义不变。证据：单测 241/241（28 类 failures=0）、`:app:assembleRelease` BUILD SUCCESSFUL、`adb install -r` 后 `versionName 0.8.74 / versionCode 85 / lastUpdateTime 2026-10-11 00:02:23`；APK `dist/suixing-0.8.74.apk` 12.54MB。**Release v0.8.74 未发（等用户放行）** | commit 正文；`dist/notes/release-notes-0.8.74.md`；TODO-LEDGER:6,42 |

**「素材缺失」版本汇总（只有 APK 或只在别版正文里被提及，无自身 commit/notes）**：
`0.8.19`、`0.8.22`、`0.8.25`、`0.8.26`、`0.8.30`、`0.8.32`、`0.8.38`、`0.8.39`、`0.8.44`、`0.8.46`、`0.8.47`、`0.8.48`、`0.8.49`、`0.8.52`、`0.8.53`、`0.8.57`、`0.8.61`、`0.8.62`、`0.8.63`；另有 `0.7.0`（有 APK，**无 notes**，仅 commit 正文）。
其中 `0.8.34/0.8.35/0.8.36` 的改动被 `b2d9126`（0.8.37）合并提交、`0.8.41/0.8.42/0.8.43` 被 `03999f2` 合并提交、`0.8.71` 无独立 commit。

---

## 3. 根因档案（问题 → 根因 + `文件:行号` → 修法）

> 行号以**当前工作树**为准；括号里标注「旧行号」的是旧记忆里已失效、无法在当前树复现的锚点。
> 0.8.18 及更早的 UI 行号（`ChatScreen.kt:499-500`、`:539-540`、`:2042-2054`、`:2916`、`:3378` 等）已随重构失效，**只作历史标注**。

### 引擎 / 并发（0.8.9-0.8.11）
- **两会话同时审批 → 前一个 run 永久挂死、会话锁死**：审批门 `_pending` 与 waiter 是全局单槽（旧 `AgentEngine.kt:80/:83/:122-124/:133-135`）→ 现按 chatId 键控：`_pending` 为 `MutableStateFlow<Map<String, Request>>`（`agent/AgentEngine.kt:110-111`），写入 `_pending.update { it + (chatId to Request(...)) }`（`:155`）、清除 `:158`、`cancelPending(chatId)` `:175-177`。
- **停 A 会把 B 的审批判成「用户拒绝」**：旧 `cancelPending()` 无差别 complete（旧 `:142-146`）→ 改按 chatId（现 `:175`；调用点 `ui/chat/ChatViewModel.kt:897`、`:1636`）。
- **runs map 读-改-写非原子 → lost-update（run 在跑却查不到、停不下来）**：旧 `runs.value = runs.value ±`（旧 `:356/:362`）→ 改 `update{}` + 按身份删除。
- **同会话双击可能两个 run 写同一 transcript**：重入守卫 check-then-act（旧 `:349-354`）→ 注册进 `synchronized` 临界区。
- **聊天页按会话状态永远 Idle**：`stateOf()` 读 `states` map 而 `publish()` 只写 `RunHandle._state`，两条 flow 从未连通（旧 `:277/:285-287` vs `:393/:504-506`）→ publish 镜像 per-chat flow；现 `fun stateOf(chatId: String): StateFlow<RunState> = stateFlowOf(chatId)`（`agent/AgentEngine.kt:320`），源码注释仍在提醒 `:797`。
- **允许/拒绝集合并发写损坏**：`alwaysAllowed/alwaysDenied` 曾是 `mutableSetOf`（旧 `:85/:87`）→ 改并发集合；现 `alwaysDeniedTools: StateFlow<Set<String>>`（`:128`）、落盘 `:229`。
- **点停弹错误卡**：点停 `inFlight.cancel()`（旧 `:750-752`）→ OkHttp 抛 IOException 落 `catch(Throwable)` → `publish(Failed)` → VM 转 failure（旧 `ChatViewModel.kt:625-626`）→ 引擎识别「用户停的」= `Cancelled`。
- **点停会取消调用者协程**（0.8.11）→ run 自持子 job（`Job(parent)` + `withContext` + `complete()`）：用户点停＝正常返回已产出消息；调用者作用域消失＝照旧上抛。
- **`preferredCapability = none` 与 TIER_SHELL 闸门冲突**（未修，B2）：闸门在 `agent/AgentEngine.kt:1663-1664`（`TIER_SHELL -> if (state.shellReady || state.rootReady) null else "缺少 Shizuku（或 root）权限…"`）；`preferredCapability` 定义 `data/Settings.kt:244`，读取 `ui/capability/CapabilityScreen.kt:141`、`:144`、`:461-535`。
- **shell 取消、输出、FATAL**（0.8.11）：取消改 200ms 轮询 + destroy、输出 32KB 上限、包名正则与路径引号转义、`SchedulerService` 失败不再 FATAL、并行 run 的前台服务守卫。

### 数据 / 落盘
- **中断不留存（0.8.11 报，0.8.72 才找到真根因）**：`LiveDraft` 只有 `chatId/runId/at/text`（旧 `data/ChatRepository.kt:203-210`），`writeLiveDraft` 只写 `live.streaming`（旧 `ChatViewModel.kt:1406-1425`）、`mergeLiveDraft` 只填 text 再 `clearLive`（旧 `:1448-1494`）；证据＝真机 JSON 里该 runId 只有一条 assistant 消息、`reasoning` null、text 以「\n\n（中断）」结尾，而该后缀全仓唯一写在 `mergeLiveDraft`。修法：`LiveDraft` 补 `reasoning: String?` 与 `messages: List<StoredMessage>`（`data/ChatRepository.kt:204-224`），写/合并同时带 thinking 与已产出的轮（`ui/chat/ChatViewModel.kt:1415-1429`、`:1442-1503`，后缀串 `:1476`）。渲染链无辜（旧 `ChatScreen.kt:1596/:1694` 正常读 `message.reasoning`）。
- **用量统计把「运行中草稿」当会话读（幽灵会话）**：`LIVE_SUFFIX = ".live.json"` 提为顶层常量并过滤（`data/ChatRepository.kt:21`、`:36`；`data/UsageStore.kt:139`）。
- **`job_list` 最后一行显示空白**：日志尾部读取把末尾换行当内容（0.8.11 修）。
- **会话/记忆/任务/提醒落盘**：改 tmp+rename + 坏文件隔离 `.corrupt` + 并发读写加锁；`Settings.update` 进同一 DataStore 事务；用量按会话记账 + 条数水位线。

### UI / Compose
- **0.8.11 启动即闪退**：`key(index) { }` 组内出现非局部返回（`return@forEachIndexed`）→ Compose 抛 `Start/end imbalance`；守卫提到 `key` 组之外。
- **模型设置页串数据（改 A 的名字写进 B）**：编辑页「当前选中的 provider」随每次写盘重算（解析按 baseUrl 匹配、匹配不到落第一个），名称框每敲一字就落盘 → 选中被拽走。修：选中态与磁盘解耦（打字中目标永不漂移）+ 落盘按 id 只写那一个 provider。
- **改 provider 名字顶栏标题一直抖**：副标题「有未保存的改动」每键出现/消失把垂直居中的标题挤动 → ① 名字不参与脏判定 ② 名字落盘改停止输入 400ms 去抖 + 离页补写（`ui/settings/ModelDraft.kt` 相关逻辑；回归锁 `ModelDraftTest` 第 8 条）。
- **状态行「用时」跨轮串**：计时表 key 在某帧退化成固定键且从不清理 → key 改「会话 + run」，run 结束清条目。
- **思考块有时不自动折叠**：自动折叠依赖观察到一次「流式结束」转换，三种时序下观察不到 → 落定块默认折叠，手动展开过的永久保持；「思考过程默认展开」只作用于流式中的块（`ui/chat/ChatScreen.kt:2726-2736` 的 `followReasoning` 门）。
- **流式残影/重影**：Markdown 渲染缓存键＝整段文本，每个增量改一次状态 → 每帧缓存失效、每帧全量重解析 → ① VM 层合并增量、最多每 50ms（后放宽 80ms）发布一次，四处强制冲刷；② 解析器只解析新增段（最后一个空行前已定型直接复用，代码围栏内空行跳过）。
- **流式追底抢手**：原来要上滑很多才挣脱 → 一拖动、内容还能往回滚就立即放手；滚回底部或点「跳最新」恢复。
- **长按消息不能选择/复制**：用户气泡与助手落定正文改可长按选择复制；用户气泡长按不再直接打开编辑（编辑保留按钮入口）；流式正文去掉 `SelectionContainer`（落定块保留）。
- **长按选字时滚动跳变（B1，0.8.74）**：追底释放判定是「按住 ≥450ms」或「上滑 >12dp」，两阈值之前 `following` 仍为 true，而流式每来一段就 pin 一次（`LaunchedEffect(itemCount, streaming…)` 与 composer 的 `onPlaced`），手指底下列表被拉到底 → 按下即释放 + 抬手「短按且未上滑」才还原：`ui/chat/ChatScreen.kt:733-772`（常量 `:5822` `FOLLOW_SCROLL_PX = 200_000f`、`:5831` `HOLD_TO_SELECT_MS = 450L`、`:5834` `RELEASE_DRAG_DP = 12.dp`）。
- **气泡分裂（Bug A，0.8.64）**：`groupRows` 的 TOOL 分支在纯 tool-call 轮（`isVisible=false`）没把 `openIndex/openRun` 指到当前卡 → 补 `openIndex = rows.lastIndex` / `openRun = message.runId.orEmpty()`：`ui/chat/ChatScreen.kt:1678-1685`（`openIndex/openRun` 声明 `:1651-1652`；补漏 `5e25ddf` 用 `row.parts` 重建 tool-only 卡）。
- **插话不实时（Bug B，0.8.64）**：新增 `LivePart.Steer`，`Steered` 事件把插话行挂进 live 卡 parts，steer 消息补 `runId`（否则已落盘 steer 与 live 行重复出现两次）：`ui/chat/ChatViewModel.kt:1177-1202`（`LivePart.Steer(line)` 在 `:1195`，`runId = base.runId` 在 `:1191`）。
- **导航条长按预览气泡看不到（0.8.72）**：外层 `Box` 宽度＝`SCRUBBER_RAIL`（24dp），Compose 子节点永远不会比父宽 ⇒ `widthIn(max=180dp)` 失效 → 改 `fillMaxWidth`。现锚点：`ui/chat/ChatScreen.kt:3447-3484`（`SCRUBBER_RAIL` 定义 `:3675`）。
- **过程卡「sh 字样」= `run_shell` 被腰斩（0.8.72）**：`Modifier.weight(1f, fill=false)` 份额保留、用不掉的落到行尾 → 改 `widthIn(max=160dp)`；`PROCESS_TITLE_MAX = 160.dp`（`ui/chat/ChatScreen.kt:2426`，使用点 `:2510`、`:3114`）。
- **思考流式块不跟随（0.8.73）**：`animateScrollToItem` 每帧被下一次内容变更打断并静默丢弃 → 改即时 `scrollToItem`；`ui/chat/ChatScreen.kt:2726-2736`。
- **页面转场「页面共存」（0.8.68→0.8.71）**：`scaleIn(0.96)` + 交叉淡化时新页四周留缝透出上一页（`Color.kt` 里 background 与 surface 同值）→ 改整幅横移推入。
- **详情弹窗被撑到满屏宽（0.8.17）**：给每行加 `fillMaxWidth` 想让数字对齐 → 弹窗跟着整屏宽 → 改按最宽一行取宽 `IntrinsicSize.Max`。
- **上下文圆环「填了也显示未设置」（0.8.13）**：圆环按钮跳设置页，而设置页编辑的是「全局默认模型」的窗口，与「会话实际在用模型」常不是同一个 id → 改「设置窗口」对话框直接设当前会话模型；回归锁 `ContextWindowTest`（按裸模型 id 存取、按显示名读不到）；读取口径 `data/Settings.kt:304` `contextWindowFor(model) = modelContexts[model] ?: 0`（键是裸模型 id，`ui/settings/ModelDraft.kt:76` 注释确认）。
- **思考块内滚硬编码 320dp**（旧 `ChatScreen.kt:1791-1796`）→ `Settings.reasoningHeight` 默认 **180dp**（`data/Settings.kt:234`），设置页三档 180/240/320（`ui/settings/SettingsScreen.kt:1510-1521`）；工具清单内滚保持 320dp。
- **顶栏图标用 material 默认字形（被否决三次）** → 自绘 `ic_menu_soft.xml`（三条圆头横线）、`ic_compose.xml`（圆角方框+斜铅笔），22dp 由 tint 控色（旧锚 `ChatScreen:699-733`）。
- **麦克风/加号做圆底按钮（被否决）** → 纯图标 22dp 无 clip/background（旧锚 `ChatScreen.kt:2810-2812`）。
- **麦克风入口死控件**：按 `SpeechRecognizer.isRecognitionAvailable` 门控（旧锚 `:2644`）。
- **工具详情闪退（0.8.10 P0）**：工具输出用无界 `verticalScroll`，所在 LazyColumn item 拿的是无限高约束 → Compose 直接抛异常（`ImageBlock` 旧 `:3378` 有 `runCatching` 不是崩溃点）→ 输出框先 `heightIn(max = 132.dp)` 再滚动并去掉 `maxLines`。
- **模型弹窗空列表（0.8.10）**：原先只按「助手的 providerId」找提供方，助手没绑就解析成 null → 四级回退（助手绑定 → 按接口地址匹配 → 首个有启用模型的 → null）；清单永远包含当前模型（旧锚 `:2964`、`:2920`）。

### 键盘 / IME / 圆角（0.8.15-0.8.66，最长一条线）
- **键盘 inset 自加 180ms 动画**（0.8.14 加、0.8.15 撤）：平台本来逐帧动画化 IME inset，再动画一次＝两套动画打架（看着像「回退/膈应」）；且直接拿 IME 总高度做 padding 没扣输入框已有的导航栏 inset，**多算一条导航栏高度**＝那条大缝隙 → 回 平台原生 `imePadding()`（`ui/SuixingApp.kt:523`），聊天页贴底跟随改读**原始 inset**。
- **键盘抬起时最后一张卡顶起生硬**（0.8.16 改动画跟随、0.8.17 回退）：动画滚动与平台键盘动画打架（先上冲再回落）→ 回瞬时定位，只保留「新条目加入那帧高度还没测量、第一次定位短一截 → 等一帧后再定位一次」（这是「刚发消息状态行被输入框挡住」的真原因）。
- **抬起方向完全冻结 28 帧**（0.8.39-0.8.40 实锤）：`LaunchedEffect(keyboardInset)` 每帧重启，`scrollToItem/scrollBy` 是挂起函数，在挂起点被下一帧重启取消；`withFrameNanos` 是第一个挂起点（0.8.39 去掉），滚动是第二个 → 换非挂起 `listState.dispatchRawDelta(...)`（现锚 `ui/chat/ChatScreen.kt:629-644`，同帧补偿含 `navBarPad` 差 `:637-640`）。
- **收起回弹/末段 1-6px**（0.8.58-0.8.59、0.8.72）：① 「列表 clamp + 300ms 后补 pin」末帧与 padding 不同步 → 两向同一机制每帧非挂起 `dispatchRawDelta`；② pin 跑在 inset effect 里（布局之前）用上一帧输入框位置 → pin 移入 `onTopInWindow`（布局之后当帧，现锚 `onGloballyPositioned { onPlaced() }` `ui/chat/ChatScreen.kt:4063`）；③ `stackFootprint` 由两个 `onGloballyPositioned` 边界相减永远滞后一帧 → 改 composerH + navBarPad(heldIme) + `COMPOSER_BOTTOM_PAD(12dp)` 并纳入同帧补偿（现锚 `:276-277`，常量 `:5843`）。
- **composer 底部多一条导航栏空白**（0.8.66）：`navigationBarsPadding` 在键盘盖住手势条时仍生效 → 改 `bottom = (WindowInsets.navigationBars.getBottom(this) - LocalHeldImePx.current).coerceAtLeast(0)`（`ui/chat/ChatScreen.kt:4050-4056`，钳制值下发 `ui/SuixingApp.kt:250`、`ui/components/Common.kt:418` `val LocalHeldImePx = compositionLocalOf { 0 }`——**该行号 2026-10-11 复核为 418；此前一次读取是 419，工作树在并行变动**）。
- **收起后约 4 秒对话框自己再下沉 68px**（0.8.27）：composer 底部 `windowInsetsPadding(navigationBars.exclude(ime))` 在 IME 完全消失后才加回手势条 → 改回 `navigationBarsPadding()`。
- **键盘圆角处浅色缝/穿透（0.8.43-0.8.56，五个版本才封板）**：
  - 0.8.43 根容器 `background(白)` → `surface`（画布色）：实测圆角 `#FFFFFF` vs 画布 `#F7F9F8`；
  - 0.8.45 三层全改 surface 后仍 `#FFFFFF` ⇒ 误判出自输入法自身窗口；
  - 0.8.50 品红探针证实是随行根容器画的，但根容器画纯白、画布是渐变浅绿，本就不同色；0.8.47/0.8.48 的组合当时**未校验 `mInputShown`，采样无效**；
  - 0.8.54 撤销 0.8.52（根层避键盘）/0.8.53（`windowIsTranslucent`）两次失败实验；
  - **0.8.55 真根因是几何不是颜色**：根层渐变跨 0..2510、画布渐变跨 0..1550，同一渐变两种跨度在键盘顶边那行进度不同（62% vs 100%）→ 修法**根层渐变 `endY = 窗口高 − IME 高`（=画布底边）**，实测圆角与画布同族连续（左 `#F2F6F4` / 右 `#F3F6F4` / 键盘正上方 `#F0F4F2`）；0.8.56 封板。
- **键盘遮罩约 10% 均匀变暗**（0.8.20）：`dumpsys window` 证明遮罩来自输入法 WeType（fillxfill/TRANSPARENT）与 MIUI `FloatingWindow/AntiMistakeTouchView`，**不是随行画的**（0.8.45 同结论）。

### 边缘光 / 特效（0.8.2-0.8.72）
- **边缘光不含状态栏（0.8.72）**：`service/TouchFx.kt` 的 `WindowManager.LayoutParams` 的 `mFitInsetsTypes` 默认 `systemBars()`，`FLAG_LAYOUT_IN_SCREEN` 不影响它（AOSP `WindowLayout.computeFrames()` 用它从 `displayFrame` 扣 inset）→ `setFitInsetsTypes(0)` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`（API≥R）/`SHORT_EDGES`（P≤API<R）+ `maximumWindowMetrics`。现锚：`service/TouchFx.kt:620-630`、`:679`（注释里留了 AOSP 依据 `DisplayPolicy.java:2799`、`WindowLayout.java:108-111`）。**Operit 的 params 里没有这一行**（已 fetch `UIOperationOverlay.kt` 原文核对），照抄抄不到解药。
- **呼吸光断裂**（0.8.13）：根因是「驱动那一轮一结束灯立刻灭、悬浮层被摘掉」→ 驱动过的 run 记并集 + 5s 宽限 + 灯亮期间绝不摘窗。
- **0.8.16 → 0.8.64 特效宿主变更**：`FLAG_LAYOUT_IN_SCREEN`（0.8.16）→ `TYPE_APPLICATION_OVERLAY` → `TYPE_ACCESSIBILITY_OVERLAY`（0.8.64，回退前者）+ 边缘路径用 `currentWindowMetrics` 真实屏幕 bounds(inset 0)。
- **`input tap/swipe/longpress` 不画特效**（0.8.9 补）：`run_shell` 驱动应用时执行前画环（与 `ui_tap` 同款）；0.8.11 补 `input -d 0 tap …` 带前缀写法、`input swipe X Y X Y 800`（同点长按）从「零长度拖拽」修正为「按住不放」、`keyevent HOME/APP_SWITCH` 闪边缘。

### 上下文计价 / 窗口
- **截图按字符计价高估 100~900 倍**（0.8.14）：一张手机截图 ≈ 40 万~270 万字符 → 被算成 10 万~90 万 token，而视觉端点实际只收 ≈1000 token/张 → 上下文里只要有一张截图就能单独冲破压缩阈值；同一错误还让**裁剪历史静默丢掉其余历史**（表现为「它忘了前面」）→ 截图**按张 1000 token/张**，判据改「上一轮真实 promptTokens + 本轮新增」为主，压缩说明带证据「已压缩上下文（估算 812k / 上轮实际 574k）」。
- **上游 `context_window` 不写入**（0.8.14/0.8.15）：原来只在手动点「拉取模型列表」时写 → 改**打开模型弹层时自动拉一次**（每个 endpoint 每进程一次），**只填空缺、绝不覆盖手改值**；网关字段 `context_window`（`Qwen3.8-Flash` 声明 180000，实测会话跑到 574k ⇒ 声明值偏小）。
- **压缩 checkpoint 复用**：run 起手用最新 checkpoint 重建 wire（不重复压缩/付费）；重建路径必须镜像实时文案（插话包装串、截图说明、未开图片输入的补白），**新增实时文案必须同步到 `appendToWire`**（`agent/AgentEngine.kt:1605`，注释 `:1349`、`:937`），否则前缀 cache 全失效且模型看到的与 transcript 不一致。

---

## 4. 踩过的坑 / 教训

1. **并发写同一文件**：多人/多子代理同时改同一文件 → 中间态编译红甚至互相覆盖（为「谁写 `ChatScreen.kt`」返工多次）。**规则：同一文件同时只有一个写者，串行派单**；每个写者改完必须自己跑 `:app:compileDebugKotlin` 并**如实报「跑没跑、是否 UP-TO-DATE」**。
2. **`--rerun-tasks` 会触发无关任务**：`--rerun-tasks` 触发了 `mergeDebugResources`（`values-hu/values-hy.xml`）失败 → 日常用普通 `:app:compileDebugKotlin`；**Gradle up-to-date 是内容哈希判定，改 mtime 不触发重编**。
3. **写死坐标采样误判**：uiautomator 采样写死坐标会量到键盘体内（IME 窗口几何每次不同，`mGivenContentInsets` 与 touchable region 可能不一致）→ 曾据此误判「0.8.61-63 全坏」并错误回滚。**规则：按 IME touchable region 顶边相对取点，并校验 `mInputShown`**。
4. **git push 被网络挡住 ≠ 端口变了**：第 1 次 `git push` 报 `Failed to connect to github.com port 443 after 21120 ms`，**重试即成功**（`abf32c0..707da4b main -> main`）；`gh release create` 走 API 能成功。另：`adb devices` 空列表是**无线调试断了**，不是端口变了（曾错误归因，被 supervisor 纠正）。
5. **两次「装了个旧包」**：0.8.25 正则替换失败、0.8.26 构建失败却仍装了旧包 → 出包链每步都要核 `dumpsys package` 的 `versionCode/lastUpdateTime`，别信「我改了源码」。
6. **`mergeDexRelease FAILED`**：`classes.dex` 被 Gradle 守护进程占用 → `gradle --stop` 后重试。
7. **Kotlin 增量编译假故障**：删 `app\build\kotlin\compileDebugKotlin` 与 `app\build\tmp\kotlin-classes\debug` 再编。
8. **测试基建坑**：`kotlin.test` 不在类路径且编译器只报前 N 条像局部错误（JUnit 的 message 参数在**第一位**）；无 `coroutines-test` 时用 `runBlocking` + `CoroutineStart.UNDISPATCHED`；`mockk lastArg<(Call)->Unit>()` 不可靠（ClassCastException 指向 `execute$1`）；`wire.started.values.first()` 空 map 立即抛 `NoSuchElementException`，须轮询 + `withTimeout`；`runBlocking` 里写局部 `fun` 让类型推断失败。
9. **无线调试两个名字**：同机两个 mDNS 名字致 `more than one device`；端口失效报 10061 → 用 `adb mdns services` 重发现。
10. **Playwright 拦 `file:` 协议** → 需起 `python -m http.server`；截图会落到 Playwright 自己的 cwd。
11. **自写花括号扫描器不可信**：未处理 `${}` 模板与 raw string，对已知平衡的文件报 depth=62 → 扫描器先用 HEAD 校验（net 0）才可信。
12. **compress 的 block id 蒸馏**：`bN..bM` 多次报 reversed range → 改用 `acp_status` drilldown 的 `mN` 引用。
13. **「感觉类」用户反馈背后往往有结构性数值原因**：24dp 父约束导致 `widthIn` 失效、双份 8dp 间距、`scale` 露缝、`weight(fill=false)` 份额保留。
14. **先取证再动手**：真机 JSON / 应用内日志 / AOSP 源码，比读码推断省轮次；adbd 侧受限时优先「应用内临时日志 + 量完删」。
15. **`key(index)` 组内不能非局部返回**（`return@forEachIndexed` 会让 Compose 抛 `Start/end imbalance` 直接闪退）。
16. **别把「编译+单测过了」当「真机已验证」**：0.8.17 作者自述把「键盘圆角/状态行被挡/顶起动画」当已解决上报但未真机验证，属错误。

---

## 5. ★ 已实测否掉、勿再加回来

> 合并自 `dist/TODO-LEDGER.md`（12 行）与两份旧 WORK_SESSION 的同类清单，去重后 **23 条**，每条的失败原因保留。

| # | 候选 | 结果 / 失败原因 |
|---|---|---|
| 1 | 键盘 inset 自加动画 | 与平台逐帧 IME 动画打架 → 回退感 ✗（0.8.15 撤回） |
| 2 | 视口差手算补偿 `animateScrollBy(-delta)` | 每次动画后位置漂移 **53px** ✗ |
| 3 | 视口高度帧循环 pin | 抬起方向完全不跟（off 冻结 **28 帧**）✗ |
| 4 | 延迟 300ms 矫正 pin | 可见二次运动（下沉再上浮）✗ |
| 5 | `windowIsTranslucent` + 根层避键盘 | 圆角仍白 ✗（0.8.52/0.8.53 两次实验均失败；正解＝根层渐变 `endY`=画布底边） |
| 6 | `staticCompositionLocalOf` 传 IME 高度 | IME 每帧重组整个 NavHost ✗（改 `compositionLocalOf`） |
| 7 | 转场 `scaleIn(0.96)` + 交叉淡化 | 新页四周露上一页 =「页面共存」✗（改整幅横移） |
| 8 | 长按 450ms / 12dp 位移才释放追底 | 阈值前仍被 pin 拽回 ⇒ 选中文字时滚动跳变 ✗（0.8.74 已改「按下即释放 + 短按未上滑抬手还原」） |
| 9 | 导航条「单 Canvas」 | 未照抄（`Column` + 单 `pointerInput` 已够）✓ **保留现状** |
| 10 | 写死坐标像素采样 | 量到键盘体内 → 误判 ✗（曾据此误判 0.8.61-63 全坏） |
| 11 | `import kotlin.test.*` | 不在类路径 ✗ |
| 12 | `kotlinx.coroutines.test.runTest/testScheduler` | 无 `coroutines-test` 依赖 ✗ |
| 13 | mockk `lastArg` 注入 `onCall` 模拟掐线 | `ClassCastException`（指向编译后 `execute$1`）✗（改轮询 + `withTimeout`） |
| 14 | 把「取消落 Cancelled」断言放宽为「任意终态」 | 掩盖「点停弹错误卡」真缺陷 ✗ |
| 15 | HTML 界面模拟 `ui-mock/` | 用户评价「丑不垃圾的」，目录与服务已删 ✗ |
| 16 | 思考块展开用自然高 `Column` | 撑几屏高 ✗（改判有界内滚 320→240→180dp） |
| 17 | 顶栏纯色实体条 | 用户否决 ✗（改透明浮空圆钮 + 标题胶囊） |
| 18 | 模型选择器照搬闪电图标 | 那只是参考图元素 ✗（去掉） |
| 19 | 给麦克风/加号做圆底按钮 | 用户否决 ✗（改纯图标 22dp） |
| 20 | 构建 debug APK | 用户明令只出 release ✗ |
| 21 | 顶栏用 material 默认字形（Menu/Edit） | 三次否决 ✗（改自绘 `ic_menu_soft.xml`/`ic_compose.xml`） |
| 22 | 模型选择用全屏 `ModalBottomSheet` | 要紧凑弹窗 ✗（改 300dp 弹窗锚对话框上方） |
| 23 | 生成设置卡死文案「这个提供方还没启用模型」 | ✗（改回简文案） |

---

## 6. 当前状态与待办

### 状态
- **HEAD `707da4b`（vc85 / 0.8.74）**，与 `origin/main` 同步（第 2 次 push 成功，`0a603b7`/`a11dc8b`/`707da4b` 三个提交都在远端）。
- **装机：0.8.74（vc85）**，`dumpsys package com.suixing.agent` → `versionName 0.8.74 / versionCode 85 / lastUpdateTime 2026-10-11 00:02:23`；APK `dist/suixing-0.8.74.apk`（12.54MB）。
- **Release 已发：v0.8.70 / v0.8.72 / v0.8.73**（含 APK）；**v0.8.74 未发，等用户放行**。
- 真机序列号：`adb-d2e1026e-PZkPcd._adb-tls-connect._tcp`（`adb devices` 空列表＝无线调试断了；0.8.74 装机时它自己恢复了，不是端口问题）。
- ⚠️ **工作树不干净（账本未记）**：`git status --short` 显示 ` M app/src/main/java/com/suixing/agent/ui/chat/ChatViewModel.kt`（+63/−59，**未提交**）与 `?? WORK_SESSION_2026-10-10_suixing-agent-ui-iterations.md`（已按用户要求移动进 `dist/archive/memory-history/`）。该未提交改动是「中断留存」的重构：把 `writeLiveDraft`/`mergeLiveDraft` 里的内联构造抽成顶层 `liveDraftFor(...)`（`ui/chat/ChatViewModel.kt:1644`）与 `LiveDraft.sameContentAs(other)`（`:1669`），常量 `LIVE_DRAFT_INTERVAL_MS = 1000L`（`:1686`）、`LIVE_DRAFT_PROBE_CHARS = 64`（`:1712`）；功能等价、**是否已编译/单测未验证**。§3 的行号取自这一工作树。

### 待用户真机验证（0.8.71-0.8.74 装进去的，逐条等回报；原文见 `dist/TODO-LEDGER.md:9-25`）
- V1 边缘光覆盖状态栏（从屏幕物理顶边 y=0 起、向内渐隐约 50px、底部手势条贴底、16 段渐隐光晕而非硬描边）
- V2 中断留存（思考块与工具卡都在；`files/chats/<id>.json` 里该 runId 的 assistant `reasoning` 非 null、TOOL 消息成对）
- V3 思考块流式跟随（关掉「外观 → 思考块跟随最新」后应全程不自动滚）
- V4 导航条长按预览气泡（220ms → 完整可读一行，不再夹成 24dp 一个字）
- V5 按钮统一观感（顶部两盘同直径同图标大小；「展开全部 N 行」明显收紧；触摸响应范围 > 视觉圆盘）
- V6 页面转场（整幅横移推入，不再两页叠着；抽屉仍 300dp 左滑）
- V7 顶栏（蒙版 26%/10%/0 是否够；分割线已去掉是否满意）
- V8 助手独立整页（编辑态旋转不丢草稿；点另一张卡勾移动且页面不退）
- V9 用户画像自动总结（聊满 8 轮 → 状态变「最近更新 HH:mm」，`profile.md` 有摘要而 `memory.json` 无画像文字）
- V10 压缩阈值可配（改小后下一次 run 更早压缩）
- V11 权限档次自选（**注意：选择已持久化但执行链尚未接入**，见 B2）
- V12 圆环与用量（圆环中心百分比 7sp 加粗；卡片「缓存命中 xx.x%」为**全会话总命中率**；用量弹窗首行「提供方 / 模型」）
- V13 工具页（行高 56dp、描述单行截断；Skill/MCP tab 能导入/新建；数据页里**没有** MCP/技能入口）
- V14 过程卡首行（「思考过程 8dp 1658 tokens 7dp 32.2s 8dp 预览… ⌄」，无 16dp 空档、chevron 贴右缘；`run_shell` 完整显示不腰斩）
- V15 键盘/圆角（圆角穿透 ✓、抬起逐帧跟随 ✓、两态边距 48px ✓、收起回弹已修（0.8.72 改 `stackFootprint`）——**回弹请再确认一次**）
- V16 **B1 修复（0.8.74 新装）**：长按气泡里的文字选中 → 列表位置不再被拽回底部、选区不动；普通点击仍照旧追底；上滑阅读后滑回底部松手恢复追底

### 排队（写域空闲即派）
- **B2 权限档次接进执行链**：`preferredCapability` 已持久化但未接 → 最小改法＝`CapabilityHub` 暴露 `preferred` 流 + `CapabilityState.shellTier(preferred)` 单点解析 + 4 处调用点（**账本旧锚**：`UiTools.kt:226-237`/`:251-277`、`ShellTools.kt:123-132`/`:222-232`；**当前树实际锚点**：`tools/UiTools.kt:219`（`capabilities.state.value.let { it.rootReady || it.shellReady }`）与 `:231`（`state.shellReady -> Shell.shizuku(command)`）、`tools/ShellTools.kt:129`（`capabilities.state.value.shellReady -> "shizuku"`）与 `:223`（`if (!state.shellReady && !state.rootReady)`））；注意 `preferred = none` 与 `agent/AgentEngine.kt:1663-1664` 的 `TIER_SHELL` 闸门冲突需一并处理（账本旧锚写 `AgentEngine.kt:1597`，**已失效**）。
- **B3 全局工具开关**：工具页现在是只读总览；现有禁用链只有按助手的 `Assistant.disabledTools` → 需新增设置字段 + 在工具装配处叠加，**等用户拍板**。
- **B4 会话记录管理界面**：「会话文件」指向的是工作区文件浏览器；若要会话列表/导出属**新功能**。
- **B5 归档/清理**：`dist/` 下的诊断日志（`fx-trace.log`、`top-trace.log`、`ime-clamp.log`、`verify*.log`）与旧 apk 可清；另有本次新增的 `dist/_memlog_fulllog.txt`（105KB，合并记忆用的临时素材，可删）。

---

## 7. 可复用经验

1. **派单 prompt 写死四件事**：**写域**（改哪些文件）+ **禁碰文件** + **编译验证要求**（改完自己跑 `:app:compileDebugKotlin` 并如实报 UP-TO-DATE 与否）+ **回报格式** —— 显著减少并发事故。
2. **同一文件串行派单**：并发写同一文件必出中间态编译红与互相覆盖；宁可排队也别并行。
3. **先取证再动手**：真机 JSON / 应用内临时日志 / AOSP 源码 > 读码推断。`logcat` 不可用时走 `dumpsys dropbox --print`；`screenrecord` 不可靠时用「应用内临时日志 + 量完删」。
4. **出包每一步都要真机核对**：`dumpsys package` 的 `versionCode`/`lastUpdateTime` 是唯一可信的「装上了没」；构建失败/替换失败会静默装旧包。
5. **验收读数是「对某一棵树」的**：release notes 里记的 mtime 冻结锚点（如 0.8.12 的十个文件 mtime）一旦有新的 main 源码写入即失效 —— 记读数时同时记树锚点。
6. **用户说「感觉不对」时去找结构性数值**：父约束尺寸、双份间距、`weight(fill=false)` 份额、`scale` 露缝、渐变跨度不一致，都是「感觉」背后的真原因。
7. **压缩/计价的判据要用实测真值**：估算（字符数）不能当 token；优先用「上一轮真实 `promptTokens` + 本轮新增」，并在文案里带证据（估算 vs 实际）。
8. **重建路径必须与实时路径逐字节一致**：`appendToWire` 与实时文案不同步 ⇒ 前缀 cache 全失效且模型看到的与 transcript 不一致；压缩后的上下文与「用 checkpoint 重建」必须一致（实测同任务内命中 99%、整体 93%）。
9. **账本（TODO-LEDGER）与记忆（本文件）分工**：账本记「用户点名的逐条核销」，记忆记「项目事实 + 版本史 + 根因 + 教训 + 否掉清单」；两者都不许删行/编造。
10. **写记忆只留一个文件**：历史记忆移到 `dist/archive/memory-history/`，根目录只保留本文件。

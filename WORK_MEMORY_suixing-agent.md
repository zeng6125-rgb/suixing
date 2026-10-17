# 随行 Suixing · 唯一工作记忆（合并版）

> 本文件是**唯一**记忆文件。原 `WORK_SESSION_2026-10-09_suixing-agent-v08.md` 与
> `WORK_SESSION_2026-10-10_suixing-agent-ui-iterations.md` 已**移动**到
> `dist/archive/memory-history/`（`dist/` 在 .gitignore 内）。`dist/TODO-LEDGER.md` 是**账本不是记忆**，保持原位、不许动。
> 素材来源：`git log --format="%H%n%ad%n%B"` 全量提交正文（70 个提交）、`dist/notes/release-notes-*.md`（27 份）、
> `dist/commit-msg-*.txt`（39 份）、`dist/TODO-LEDGER.md`、两份旧 WORK_SESSION、`README.md`、`docs/v0.8-contract.md`、当前工作树源码（只读核对）。
> 行号口径：**以当前工作树为准**（HEAD `dd30477`＝0.8.79）；旧行号一律标注「旧行号」。
> ⚠️ 工作树在并行变动，**行号会漂移**；引用前先按符号名 grep 复核。
> 最近三次更新：2026-10-11 03:1x（补 0.8.77/0.8.78/0.8.79、14 条新根因、四条新坑、第四批待办）、2026-10-11 01:3x（补 0.8.76 与第三批）、2026-10-11 00:4x（首轮合并）。

---

## 1. 项目与环境事实

| 项 | 值 |
|---|---|
| 仓库路径 | `D:\dsh\fixbug\suixing`（**曾用名** `D:\dsh\fixbug\lingshu`；改名必须先退出 DSH，它的 node 进程持有工作区否则 Rename-Item 报 used by another process；Git 无需处理，仓库内全是相对路径） |
| 包名 | `com.suixing.agent`（0.7.0 起从 `com.lingshu.agent` 更名 ⇒ **全新安装**，会话与设置不迁移）；debug 变体 `applicationIdSuffix ".debug"`、`versionNameSuffix "-debug"`（`app/build.gradle.kts:13`、`:32-33`） |
| 当前版本号 | `versionCode 108` / `versionName "0.8.97"`（**已发版**，commit `39d06bd`；`app/build.gradle.kts:16-17` 本轮实测 108/0.8.97，随每次 bump 变，引用前现读）。APK `dist/suixing-0.8.97.apk` |
| 已发 Release / tag | **tag 已在远端确认到 `v0.8.97`**（Release 页**未逐页核实**——`gh release list` 本轮没跑；「tag 存在」是必要条件、不是发布证据，§4 第 23 条）（远端实测 `ls-remote --tags`，走代理）：`v0.8.70 / 72 / 73 / 74 / 75 / 76 / 77 / 78 / 79 / 80 / 81 / 82 / 83 / 84 / 85 / 86 / 87 / 88 / 89 / 90 / 91 / 92 / 93 / 94 / 95 / 96 / 97`。tag→commit（本轮走代理 `ls-remote` 复核）：v0.8.86→`6854b24`、87→`b29bb9b`、88→`33a424d`、89→`d0171d0`、90→`f44b3e7`、91→`966b186`、92→`23901ad`、93→`434308c`、94→`ac5567d`、95→`5ca8804`、96→`4316730`、97→`39d06bd`；远端 `refs/heads/main` = `39d06bd`（= 本地 HEAD）。⚠️ **本机直连 GitHub 常态失败，查 tag / 推送都必须走代理 `127.0.0.1:7890`**（否则看到「tag 缺失」假象，§4 第 17b 条）；本地 `git tag` 仍只 fetch 到 v0.8.80（20 个），**86~97 只在远端核实过**。**发布状态以 `gh release list` 为准，不是账本**（§4 第 23 条） |
| 0.8.81~0.8.86 本地产物（已核） | `dist/suixing-0.8.81.apk`（13,268,706 B @2026-10-11 03:28:18）、`-0.8.82.apk`（同尺寸 @12:53:35）、`-0.8.83.apk`（@14:05:33）、`-0.8.84.apk`（@14:33:08）、`-0.8.85.apk`（@15:30:08）、`-0.8.86.apk`（@16:28:54）——**81~86 六个 APK 字节数完全相同（13,268,706 B）**，是同一份构建被反复复制/覆盖的迹象，判断「装的是哪一版」只能靠 `aapt` 读包内 versionName（§4 第 26 条）。notes：`release-notes-0.8.81.md`（3,780 B）、`0.8.82`（2,788 B）、`0.8.83`（4,981 B）、`0.8.84`（2,760 B）、`0.8.85`（3,812 B）；**无 0.8.86 的 notes**（未发版） |
| 0.8.87~0.8.97 本地产物（已核） | APK：`-0.8.87.apk` ~ `-0.8.90.apk`、`-0.8.91.apk`（13,399,778 B @2026-10-12 02:39:47）、**`-0.8.92.apk` ~ `-0.8.97.apk` 七个字节数完全相同（13,399,778 B）**（@03:19:13 / 03:28:06 / 03:37:10 / 03:38:11 / 03:48:32 / 12:54:10）⇒ 又一轮「同尺寸 APK」（§4 第 28 条），判版本只能 `aapt`。**notes 只到 `release-notes-0.8.90.md`（4,621 B）**；**0.8.91~0.8.97 没有 notes / commit-msg 文件**（`dist/commit-msg-0.8.9*.txt` 只有 0.8.90）⇒ 这七版的细节只能靠 commit 正文（§2 行里已标「notes 缺失」） |
| 0.8.78 本地产物（已核） | `dist/suixing-0.8.78.apk`（13,235,358 B，2026-10-11 02:45:33）、`dist/notes/release-notes-0.8.78.md`（10,510 B）、`dist/commit-msg-0.8.78.txt`（10,510 B） |
| 0.8.76 本地产物（已核） | `dist/suixing-0.8.76.apk`（13,169,146 B ≈12.56MB，2026-10-11 01:24:41）、`dist/notes/release-notes-0.8.76.md`（8,112 B）、`dist/commit-msg-0.8.76.txt`（5,272 B） |
| 0.8.75 本地产物（已核） | `dist/suixing-0.8.75.apk`（13,152,762 B，2026-10-11 00:23:17）、`dist/notes/release-notes-0.8.75.md`（3,879 B）、`dist/commit-msg-0.8.75.txt`（5,213 B） |
| 构建三件套 | `JAVA_HOME=D:\dsh\fixbug\_toolchain\jdk\jdk-17.0.20.1+1`；`GRADLE_USER_HOME=D:\android\.gradle-pi`；gradle 可执行 = `D:\android\.gradle-pi\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`（**仓库刻意不带 gradlew**）。README 记：JDK 17 + Android SDK（`platforms;android-35`、`build-tools;35.0.0`） |
| adb | `D:\dsh\fixbug\_toolchain\sdk\platform-tools\adb.exe`；无线调试，真机序列号形如 `adb-d2e1026e-PZkPcd._adb-tls-connect._tcp`（**每次会变**）。**`adb devices` 空列表 ＝ 无线调试断了**（不是端口变了、不是设备不在）；曾错误归因被纠正。旧记录里见过 IP 端口 `192.168.0.106` + `38735→39199→36651`，用 `adb mdns services` 重发现 |
| 验签 | `build-tools\36.0.0\apksigner.bat`（`keytool -printcert` 对 v2/v3 包会误报「非签名 jar」，别用它判签名） |
| 签名 | `app/sideload.keystore` 自签，**alias / 密码均为 `lingshu`**（`app/build.gradle.kts:23-26`、`README.md:171`）——**不是路径、不要改**。证书 CN=Lingshu，SHA-256 `9a33761bfdfa8cc87caaf73f37626b995cbd6acb7121c0d07dfa62df679aa8ee`，同钥可覆盖升级 |
| 仓库/Release | remote **实测** `origin  https://github.com/zeng6125-rgb/suixing.git (fetch/push)`；分支 `main`，**HEAD `dd30477` = `origin/main`（已同步）**；本地 tag **20 个**（走代理 `fetch --tags` 后；**v0.8.81~85 本地尚未 fetch、远端已有**）＝`v0.1.0 v0.1.1 v0.2.0 v0.3.0 v0.4.0 v0.4.1 v0.5.0 v0.5.1 v0.6.0 v0.7.0 v0.8.70 v0.8.72 v0.8.73 v0.8.74 v0.8.75 v0.8.76 v0.8.77 v0.8.78 v0.8.79`，与远端一致（`git ls-remote --tags origin` 走代理复核：v0.8.70/72/73 三个 tag 都指向 `abf32c0`，v0.8.74→`707da4b`、v0.8.75→`a404e1c`、v0.8.76→`2b3c2a9`、v0.8.77→`794c916`、v0.8.78→`0530e1a`、v0.8.79→`dd30477`）。「由 lingshu-agent 改名而来、已公开」是**早期记录**，本轮未再核（`gh repo view` 未跑） |
| 诊断日志（0.8.79 现状，排查用） | `filesDir/fx-trace.log`（256/128KB 有界，daemon 低优先级线程）、`filesDir/console-trace.log`（有界，每行带 `resident=`，含 `service=taskRemoved` 行）、`filesDir/voice-trace.log`（32/16KB）、`filesDir/scroll-diag.log`（与其它走同一个有界 worker；**0.8.76 的临时诊断，是否已删待用户复现长按跳变后确认**）。⚠️ 0.8.79 修掉了 fx-trace「按字符截字节」的单位错误 |
| logcat | **完全不可用**（`logcat -c` 与 `-d` 都报 `Logcat read failure: No such file or directory`，root 同样）→ 崩溃排查走 `dumpsys dropbox --print`（历史 dump 存 `dist/dropbox*.txt`） |
| screenrecord | **不可靠**（连续多次 `moov atom not found`，`dist/rec*.mp4` 里多个 0 字节）→ 逐帧验证改用**应用内临时日志**（临时写 `files/*.log`，量完删） |
| 单测基线 | **435/435 绿**（**当前**，0.8.97）；历史：427（0.8.91）、385（0.8.85 后）、384（0.8.84）、383（0.8.83）、367（0.8.81/0.8.82）、355（0.8.79/0.8.80）、355（0.8.79/0.8.80），`dist/notes/release-notes-0.8.79.md` 记 `355 tests / 0 failures / 0 errors / 0 skipped` + `assembleRelease` BUILD SUCCESSFUL + 装机 `versionName=0.8.79 / versionCode=90`）；历史：336（0.8.78）、309（0.8.77 前）、272（0.8.76）、246（0.8.75）、241（28 类，含 `GroupRowsTest` 6 / `SteerRenderTest` 5 / `InterruptPersistenceTest` 4）；命令 `:app:testDebugUnitTest`。⚠️ 读数来自 commit/notes，**本记忆未跑 gradle、未上真机** |
| 测试框架事实 | 项目用 **org.junit**（`kotlin.test` **不在类路径**）；依赖只有 `kotlinx-coroutines-core`（**无 coroutines-test**）；0.8.9 起新增 MockK |
| 出包纪律 | 用户明令**只出 release 不构建 debug**；打包**必须用户放行**；release 构建走 `:app:assembleRelease`，产物归档 `dist/suixing-<ver>.apk` |
| 团队契约 | `docs/v0.8-contract.md`（§0 teammate 禁打包/禁 git 写、写域划分、验证命令、增量编译自救、子代理写序） |
| meow-memory | promptLang `'zh'` 写在 `~/.dsh/profiles/web/node_modules/meow-memory/cordis.patch.yml` |
| 本地视频分析工具 | **`D:\dsh\fixbug\suixing\video\node_modules\@ffmpeg-installer\win32-x64\ffmpeg.exe`**（本轮实测存在）——用户录屏可用它**逐帧分析**（替代 adb 录屏：见 §4 第 30 条，**不再用 adb 操作用户手机**）。仓库里另有 `gpt-contact-16.png` / `gpt-transitions.png`（用户发的对照截图，未跟踪） |

### 出包链（十一次以上，固定顺序）
bump `versionCode`/`versionName` → 写 `dist/notes/release-notes-<ver>.md` → commit → `:app:assembleRelease` →
归档 `dist/suixing-<ver>.apk` → `apksigner` 验签 → `adb mdns services` 重发现端口 →
`adb -s <ip:port> install -r` → `dumpsys package com.suixing.agent` 核对 `versionName`/`versionCode`/`lastUpdateTime`。

---

## 2. 版本时间线（最早 → 0.8.97 已发；0.8.90~0.8.97 详见本节末尾）

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
| 0.8.74 | `707da4b` 2026-10-11 00:00（vc85，0.8.75 的父提交） | **B1 长按选中文字时滚动跳变修复**：手指按下列表那一刻就释放追底，抬手时只有「短按（<450ms）且未上滑（<12dp）」才还原按下前状态；手势仍只在 `PointerEventPass.Initial` 观察、不 consume；`HOLD_TO_SELECT_MS`/`RELEASE_DRAG_DP` 数值语义不变。证据：单测 241/241（28 类 failures=0）、`:app:assembleRelease` BUILD SUCCESSFUL、`adb install -r` 后 `versionName 0.8.74 / versionCode 85 / lastUpdateTime 2026-10-11 00:02:23`；APK `dist/suixing-0.8.74.apk` 12.54MB。**Release v0.8.74 已发（lead 直接证据订正）**：`gh release list` 列出「随行 0.8.74：长按选中文字时滚动跳变修复」→ tag `v0.8.74`，创建时间 **2026-10-10T16:04:49Z**；同一次列表里 0.8.73–0.8.80 全在。⚠️ **账本 `dist/TODO-LEDGER.md:6,42` 那句「未发，等用户放行」是过时记录**（当时确实还没发，后来用户说「release 照样发」就发了，账本没回填）——**发布状态以 `gh release list` 为准，别只信账本**（见 §4 第 23 条）。早先记的「`git tag` 里没有 `v0.8.74`」也确认为**假象**（本地从未成功 `git fetch --tags`，详见 §1 与 §4 第 17b 条） | commit 正文；`dist/notes/release-notes-0.8.74.md`；`gh release list`（lead 提供）；TODO-LEDGER:6,42（**过时**） |

| 0.8.75 | `a404e1c` 2026-10-11 00:23（vc86，**发版提交**；前置 dev 提交 `52f8c67`「导航条触摸区收回轨道本身」、`5a1a973` 记忆合并提交） | **长按跳变真因＝导航条手势层是整幅宽**：`TurnScrubber` 的手势 `Column` 原 `fillMaxWidth` 且 `down.consume()` ⇒ 屏幕右侧一整条横带都在吃按下，手指落在轨道左边空白处长按被导航条接走 → 选中某一轮 → 抬手跳转、选区跟着动 ⇒ 手势层只占 `SCRUBBER_RAIL`(24dp) × 行高、右对齐，外层 Box 仍 `fillMaxWidth` 但**无手势修饰符**（只给预览气泡留测量约束），轨道判定与取消阈值不变（正文自标 `ChatScreen.kt:3468-3512`；当前树锚点 `.pointerInput(Unit)` `:3584`、`awaitEachGesture` `:3591`、`down.consume()` `:3595`、`SCRUBBER_RAIL` `:3834`）。**中断留存真根因＝0.8.72 只补容器没动产出**：writer 的两道 text 闸门（`if (text.isBlank() \|\| text == written) continue`，正文自标 `ChatViewModel.kt:1415-1435`）让「只流了思考」「只发了工具调用」的轮次**根本不产出 draft 文件**（`text.isBlank` 直接跳过 ⇒ `mergeLiveDraft` 在 `readLive ?: return` 早退），正文没变时的思考/工具增量也永不落盘 ⇒ 改每秒写一次（内容没变才跳过），写「正文 + 思考 + 本轮已产出的消息」（`liveDraftFor` / `LiveDraft.sameContentAs`）；「一行正文都没有、只在思考」就被中断的轮次现在也留「（中断）」卡。**圆环缓存命中消失＝Int 溢出**：`cached * 100 / prompt` 在 `cached > 21,474,837` 时溢出为负、<1% 又被整除截断成 0 ⇒ 改 `(cached.toLong() * 100.0 / prompt).roundToInt()` 且 0% 也照画（正文自标 `ChatViewModel.kt:242`）。其它八处：圆环中心数字 7→8sp（lineHeight 8→9，Bold）、模型弹窗三处组头（provider 组头/厂商组头/设置页 GroupHeader）加两行上限+省略号、聊天页弹窗列表页宽回 340dp（首页仍 300dp）、无 info 串的 markdown 代码块标题条显示「Markdown」、`PageTopBar` 分割线删（14 页统一）、设置 6 子页拆真路由（有转场）、自动返回 ticker 修复 + **3 秒安静期** `FX_RETURN_QUIET_MS = 3000L`（`service/TouchFx.kt:407`，`postDelayed(returnCheck, …)` `:445`）、圆环弹窗去掉「会话」前缀（会话总用时→总用时 / 会话输出速度→输出速度 / 会话累计用量→累计用量；属于本轮的行仍写「本轮」）。证据：单测 **246/246** 绿、`:app:assembleRelease` 成功、`adb install -r` 后 `versionName 0.8.75 / versionCode 86 / lastUpdateTime 2026-10-11 00:23:20`；**Release v0.8.75 已发** | commit 正文（已核 `git log -1 --format`）；`a404e1c` `git show --stat` |
| 0.8.76 | `2b3c2a9` 2026-10-11 01:25（vc87，**HEAD／发版提交**，已 push `origin/main`） | 用户第三批反馈 14 项。**长按跳变补闸**：追底 pin 是 `LaunchedEffect` 且**键里没有 `following`** ⇒ 按下置 false 后**在飞的那趟 pin 照样滚到底**；冷启动最明显因为第一次 pin 走**两遍路径**（首帧高度未知，等一帧再滚，`pinnedCount = -1`）⇒ pin 内**每次滚动调用前**重判 `following && autoFollow`，冷启动那条 pin 也各自重判；另加**临时诊断** `scrollDiag()`（`ui/chat/ChatScreen.kt:5826` 定义，调用点 `:509 :535 :582 :597 :794 :807`，均带「量完删」，写 `filesDir/scroll-diag.log`）——**待真机复现后按日志定论、用完删**。**模型弹窗组头裁字**：该行自身 `.clip(shapes.small)` + **横向 0 padding** ⇒ `q` 的**降部**扎进左下圆弧；`workbuddy/test` 首字母无降部故无损 ⇒ 组头加 `start/end = 4.dp`。**语音两弹窗重叠**：图 B「正在倾听/四条灰条」是 **MIUI 自己的 ASR 面板**（全仓无此字符串），重叠发生在**主路径**（我方卡 `phase=Listening, handedOff=false` 时 MIUI 面板同时出现）⇒ `handedOff` 交接即收起（交接失败需还原，否则提示永远看不见）+ 给系统识别**成功**分支补 `onDismiss`（原来只有取消/失败才关，成功后会留下「看不见但开着」的状态）；绿色方块＝失败态「重试」按钮被卡片圆角裁在右缘 ⇒ 卡片横向 40→24dp + 按钮拆两行（`handedOff` 现锚 `ui/chat/ChatScreen.kt:5227`、置位 `:5326`、还原 `:5341`、`if (handedOff) return` `:5373`）。**会话懒加载**：默认渲染最近 **12 轮**（`ui/chat/ChatScreen.kt:1843` `PAGE_TURNS = 12`，＝导航条 6 行窗口的两倍），滑到顶「加载更早的 12 轮 · 上面还有 N 轮」（`:858`，点击 `:854`）；导航条滑到最顶那条自动向上解锁、抬手归零、**每次进会话重置**（`var shownTurns by remember(state.conversationId)` `:331`）；轮次编号改绝对轮次。**取舍：只做渲染层，`ChatRepository.load()` 仍整份读盘**（真分页要动 engine 写入路径）。**权限档次接执行链**：唯一解析点 `CapabilityState.shellTier(preferred)`（`capability/CapabilityHub.kt:97`，`CapabilityHub.shellTier()` `:154`），口径＝**自动（未指定/未知值）取当前可达的最高档**、**显式固定档只走那一档且不可用时只向下退（绝不偷偷升 root）**；`""`（自动）与 `"none"`（显式固定标准档）是**两个不同状态，别混**；调用点 `tools/ShellTools.kt:129`/`:224`、`tools/UiTools.kt:220`/`:228`/`:235`、闸门 `agent/AgentEngine.kt:1665`/`:1672`。**文件页可出应用目录**：根原钉死在 `getExternalFilesDir` 且上一级走不出去（文案却承诺能看 `/sdcard`）⇒ 顶栏可切「应用目录 ↔ /sdcard」（`ui/files/FilesScreen.kt:98` `rootLabel`、`:167` 按钮文案、`:202-208` 提示；未授权引导开「所有文件访问权限」，回来自动落到 `/sdcard` `:92`；上一级夹在当前根内 `:178`）。**全站风格统一（10 条规范）**：横向 16dp 唯一；卡片内边距默认 **18→16dp**（`ui/components/Common.kt:81`、`:116` 已是 `PaddingValues(16.dp)`；12 个调用点里 5 个本就往下覆盖，18 才是异类）；圆角一律走主题（写死 20dp 那张卡在圆润档不跟卡片变，最扎眼）；页面 `top = 0`（顶栏已给 12dp）、底部统一 72dp；清掉 3 处 `.copy(fontSize = 字面量)`（会静默关掉「外观→字号」缩放）；分割线统一 `outlineVariant`。**刻意不动**：聊天气泡 24dp、聊天浮层头、≤8dp 微形状、空状态 24dp、顶栏 `heightIn(min = 64.dp)`、MarkdownText 自成一套的比例尺寸；`ui/capability/CapabilityScreen.kt` 的 42dp 图标方块 / 14dp 也**故意保留**（换 `shapes.medium` 在圆润档会变正圆）。另：**密度与按档查看权限**——工具页搜索框 56→44dp、工具行 10→7dp、间距 8→6dp、MCP 地址框 6dp/2 行、编辑弹窗输入框 2→1 行；权限页卡片 18→16/12dp、卡内 12→8dp、行内 2→1dp、按钮统一 36dp、说明行 10→6dp、Hero 18→14dp，四段档次选择器下逐条列出该档解锁了什么（自动档按实际生效最高档显示），与四张卡共用同一份文案。其它：抽屉助手恢复半屏弹窗（设置页助手仍整页）、抽屉状态恢复（回来源页自动开）、抽屉退场 200→120ms、顶栏与页面同色、回车键发送开关（`data/Settings.kt:282` `enterKeySends = true`，缺键＝开；已接 composer）。证据：单测 **246 → 272/272 全绿**（+26：懒加载与导航条 19、权限档次 7）、`:app:assembleRelease` 成功、`adb install -r` 后 `versionName 0.8.76 / versionCode 87 / lastUpdateTime 2026-10-11 01:24:50`；**Release v0.8.76 已发（lead 口径）** | commit 正文（已核 `git show --stat 2b3c2a9`）；`dist/notes/release-notes-0.8.76.md`；`dist/commit-msg-0.8.76.txt` |
| 0.8.77 | `794c916`（vc88，2026-10-11 02:xx 前） | 提交标题：「**滚动追底根因修复 + 输入法不再被弹窗收起 + 导航条上拉解锁 + skill 系统 + 悬浮窗与边缘光**」。⚠️ **本轮未读该提交正文**（素材缺失），正文细节待补；已知：0.8.78 notes 记本版单测基线 **309**；skill 系统的「导入/下载默认关闭」信任边界在本版建立，0.8.79 才把「模型新建＝默认开启」分开（见下两行）。 | `git log --oneline`（标题）；0.8.78 notes 里的 309 基线 |
| 0.8.78 | `0530e1a` 2026-10-11 02:45（vc89，发版提交） | 用户点名逐条 + 二十多处自查。**圆环慢的真因**＝每次状态发射（流式期间每 80ms 一次）都对**整条会话走 6 遍**求和（`SessionStats.of`）且在主线程，另有 3 个 `remember(state.messages)` 因 `List.equals` 逐元素比较每次重组再各走一遍；「暂无数据」＝那个数只来自端点 usage，而**不支持的网关永远不返回**（`LlmClient` KDoc 自己就写着「大多数中文网关」才支持）⇒ 三级回落（端点 → 会话最新一轮 → 本次 run 字符估算，标「约」）+ 累计组移出重组热路径；删掉「本轮输入 / 本轮输出」两行；顺序按语义分组（上下文占用 → 缓存命中 → 累计用量·N 轮 → 未缓存输入 → 缓存读取 → 输出 → 输出速度 → 总用时），token 一律 `%,d tok`（只有上下文占用按比例保留 k 形），缺数据不画。**思考块「展开全部 xxx 行」真因**＝块内硬编码 `cap = 200` + 三处自动折叠 ⇒ 删上限、默认全展开、点标题收起、按会话记住，性能靠限高 + 块内滚动（该块本就是 LazyColumn）；设置开关语义改成「历史思考块展开」。**有序列表序号全是 1**＝思维链按**行**渲染、每行解析成单元素列表而 marker 画 `index + 1`（index 恒 0）⇒ 字面数字进 AST（`MdBlock.Bullet.start`）+ marker 用 `start + index`（9 条新单测：1→51、按行渲染保号、从 3 开始、tight/loose、嵌套、`1)`、超宽数字）。**工具审批**：加号右侧快捷图标三选一（自动运行/每次询问/禁止，默认每次询问）、图标随档变色（禁止＝红）、注明「下一次任务生效」（档位是任务启动时的快照）、键盘弹起时点它不收键盘；文案统一为「自动运行」；**禁止档明确告诉模型「这一轮不能调工具，请直接回答」**。**语音换自己的**：设置新增语音输入开关**默认关**（关着麦克风图标完全不显示）；开着＝自录音 + 复用已配 provider 的 OpenAI 兼容 `/audio/transcriptions`（AudioRecord + 自拼 WAV + 端点检测 + 转写模型可填），**主路径完全不碰系统识别**；界面自绘贴输入框上方的细胶囊（5 根音量竖条 + 实时提示 + ×），未配置模型时不录音直接提示；系统识别降级为**用户主动点**的兜底。**todo 清单**：与插话条同款位置/样式，模型调 `todo_write` 时出现，完成的条目从条上消失且计数 +1，全完成显示最后 3 条（灰色带勾），跑完/停止整条收起，超 3 项显示「还有 N 项」，任务开始清空。**助手头像自定义**：助手编辑页可上传（系统照片选择器，不需存储权限）→ 缩到 256px 重编码存私有目录 → 消息卡/流式气泡/空状态大图/助手列表全跟着变，可「恢复默认图标」，删助手顺带清头像文件。**插件 tab**：工具页＝`工具｜技能｜MCP｜插件`，插件页先只放说明 + 空态（**不编造假插件**）；顺带修「加第四栏后插件页冒出『添加 MCP 服务器』按钮」。**抽屉「任务」→「定时任务」**真列表（标题/时机/下一次/暂停开关/删除 + 空态话术），原任务板仍在（从 todo 条进）。**全站统一**：转场四条一套规格（淡入淡出 + 8% 位移）零例外、返回不再重开侧边栏；风格逐页过 17 页 32 处改动、**写死字号清零**、「标题+副标题+右侧控件」行统一到一个实现、6 类行故意不合并（理由写进注释）。**自查真问题**：MCP 工具名可能超 64（服务端拒）⇒ 保证 ≤64 + 长服务器名短哈希防撞名（6 条单测）；MCP `timeoutMs` 被 HTTP 传输**完全忽略**（只有 stdio 遵守）⇒ 现在真用上；MCP 上报版本号写死 `0.7.0` ⇒ 读真实包版本。证据：单测 **309 → 336 全绿**、`:app:assembleRelease`、装机 vc89。**悬浮窗**：本版先按「`TYPE_APPLICATION_OVERLAY` 被 MIUI 在后台隐藏」改成**双宿主**（优先无障碍宿主 `TYPE_ACCESSIBILITY_OVERLAY`，没连无障碍才回落）——⚠️ **这条解释在 0.8.79 被推翻（见下行）** | `dist/notes/release-notes-0.8.78.md`（已读）；`dist/commit-msg-0.8.78.txt`；`git log --oneline` |
| 0.8.79 | `dd30477` 2026-10-11 03:12（vc90，**HEAD**，与 `origin/main` 同步） | **全项目自审 + 修复**（用户授权「你睡觉时我自查全项目」），改动 30+ 文件。**悬浮窗常驻不显示的真因（推翻 0.8.78 的 MIUI 解释）**＝`service/AgentForegroundService.onDestroy()` **无条件** `FloatConsoleService.stop(...)`：run 开始把 console 服务起回来（球显示）、run 一结束就杀掉它，而抽屉开关仍 ON、无人再拉起 ⇒「常驻」永不成立（**用户反证：运行时在后台能显示**，所以与 MIUI 隐藏 overlay 无关）。修法＝`onDestroy` 只在**抽屉开关为关**时才 stop（`service/AgentForegroundService.kt:101-107`，`if (!Core.settings.current().floatingConsole)`，注释 `:92-100` 写明「这就是常驻曾经死掉的地方」），并补 `MainActivity.onStart` 兜底自启（`MainActivity.kt:36-40`）；诊断 `filesDir/console-trace.log` 每行带 `resident=`、新增 `service=taskRemoved` 行。**圆环整卡「暂无数据」**＝端点不报 usage 且老会话消息里也没有 usage ⇒ 三级回落全为 0 ⇒ 加**第四级文本估算**（与引擎同口径，`agent/AgentEngine.kt:1864` `internal fun estimateTokens(message: StoredMessage)` 已改 internal 复用；`ui/chat/ChatScreen.kt:181` import、`:416` `state.messages.sumOf { estimateTokens(it) }`，口径注释 `:414`）+「累计用量」兜底行 + 空会话文案改「新会话 · 还没有用量」；**另修统计缓存键漏了会话 id**（两个形状相同的会话会串）。**思考块「超过 200 行就自己变了」**＝`ReasoningBlock` 内硬编码 `cap = 200` + 三处自动折叠（流式结束自动收起、stored 写死 `startExpanded = false`）⇒ 默认展开、点标题收起。**markdown 有序列表序号全是 1**＝思维链按行渲染 ⇒ 字面数字进 AST + `internal fun orderedMarker(block: MdBlock.Bullet, index: Int) = \"${block.start + index}.\"`（`ui/components/MarkdownText.kt:804`，调用 `:841`；AST 侧 `:127`/`:218`；9 条新单测）。**`PageTopBar` 的 12dp 在 Scaffold 宿主页不生效**＝`Surface` 与 `Spacer` 是**兄弟节点**，M3 1.3.1 的 Scaffold 用 `maxByOrNull { it.height }` 取槽高（子节点都落在 (0,0)）⇒ 10 个页面栏下没有 12dp；修法＝把 Spacer 移进 Surface 自己的 Column（`ui/components/Common.kt`）。**任务页崩溃**＝`items(key = { it.id })` 而模型自报 id、store 只补空 id 不去重 ⇒ 重复 key 抛 `Key \"t1\" was already used`；修法＝`itemsIndexed(rows, key = { index, row -> \"$status#$index#${row.id}\" })`（`ui/tasks/TasksScreen.kt:179`，import `:25`；聊天列表同法 `ui/chat/ChatScreen.kt:934`）。**上下文窗口对话框**：`current/1000` 取整 + `take(4)`（131072→1310，差 60 倍）+ **浮点截断**（1001 显示 1.001、写回 1000；`Math.round` 修）；KDoc 写明「内部按 tokens 存、显示与解析全精度、不再有第二次取整」。**定时任务**：改时区/校时/重装后不重排（manifest 只有 `BOOT_COMPLETED`）⇒ 补 `TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED`（`app/src/main/AndroidManifest.xml:107-116`，注释 `:105`）+ `SchedulerService.refresh()`（`service/SchedulerService.kt:214` 调 `expireMissed`）；**错过的一次性提醒**永久「已启用」却永不响 ⇒ `Schedule.missedAt`（`data/ScheduleStore.kt:56`）+ `expireMissed(now)`（`:161-167`，arm 时扫、**不补响**）+ 页面文案「已过期（错过了触发时间，需要的话重新建一条）」（`ui/schedules/SchedulesScreen.kt:193`/`:207`/`:212`）+ 工具输出同口径（`tools/ScheduleTools.kt:121-124`/`:165-166`）。**引擎 `sanitize()` 只丢一半**：丢弃「有未回答 tool_call 的 assistant」却留下孤儿 TOOL 消息 ⇒ 端点照样 400，已补镜像过滤（`agent/AgentEngine.kt:1608` `private fun sanitize(...)`，调用 `:942`/`:947`，注释 `:1268`）；压缩函数 `wire.first()` 无守卫 ⇒ `firstOrNull() ?: return`（`:1431`）。**MCP**：工具名可能超 64 ⇒ 保证 ≤64 + 短哈希防撞名；`timeoutMs` 被 HTTP 传输忽略 ⇒ 每次调用 `newBuilder().callTimeout(deadline, MILLISECONDS)`（`mcp/McpTransport.kt:251`）；子进程 `close()` 只发 SIGTERM ⇒ destroy → 等 300ms → 强杀（等待在 IO）；版本号写死 `0.7.0` ⇒ 读真实包版本。**数据安全**：三个 store（MCP/定时/todo）不是原子写、损坏静默清空 ⇒ temp+rename + `*.corrupt.json` 隔离（`data/McpStore.kt:59`、`data/ScheduleStore.kt:124-126`、`data/TodoStore.kt:44-46`；与既有的 `data/ChatRepository.kt:189-190`/`:217` `CORRUPT_SUFFIX`、`data/Memory.kt:146-149`、`data/UserProfile.kt:169-172` 一致），隔离文件 30 天后清理；`web_fetch` 全量读内存 ⇒ 流式 + 1MB 上限（`tools/WebTools.kt`）；下载 `.part` + 成功后改名 + 超期清扫；相对路径含 `..` 直接拒绝。**权限档次**：`enableRoot` 从「没人读的死字段」变成**真开关**（`data/Settings.kt:184` `val enableRoot: Boolean = true`；`capability/CapabilityHub.kt:155`/`:169` `applyRootSwitch(saved.enableRoot)`；UI `ui/capability/CapabilityScreen.kt:211`/`:221`/`:231-233`）；**显式 `mode=\"root\"/\"shizuku\"` 只是请求、不能超过固定档**（`minOf(requested, resolved)`，14 条测试）。⚠️ **历史事实**：该字段原本 `false` 且无人读 ⇒ **所有人实际都在用 root**，所以默认值必须留 `true`（改成关等于「什么都没点却掉一档能力」）。**skill 默认值**（复核过代码）：模型新建＝**默认开启**（`tools/SkillTools.kt:126` `skills.save(id, …, enabled = true)`，用户要求）；URL/SAF 导入＝**仍默认关闭**（`tools/SkillTools.kt:133` `importUrl`；信任边界）。⚠️ 残留风险：外部内容若诱使模型调 `skill_save`，该技能会**当场生效**（旧规则下会躺着等人点）。**`messagesRevision`**：用「唯一收口处打戳」（公开 `state` 流里按 `===` 引用比较自增）替代「每次重组逐元素比较整条消息列表」，覆盖全部 26 处写入路径（`ui/chat/ChatViewModel.kt:90` 字段、`:298`/`:303`/`:321` 打戳、`:966` 消费；`ui/chat/ChatScreen.kt:366`/`:373` 按它 remember）；`editMessage` 的 KDoc 写明依赖它。另：`MenuCard` 的 `focusable` 默认改 `false`；导航条跳转 `turnIndices[turn]` 加 `getOrNull`（窗口缩小时会越界崩溃）；主线程写 1.9MB 录音文件挪 IO；文件页预览连点两文件的**竞态**（后完成覆盖先完成）⇒ 过期请求丢弃、复制失败用 Toast；助手选图失败给提示；权限页探测中禁用「检测」按钮；工具页搜索框去掉写死高度（0.8.76 收紧密度时压过头，文字下缘被裁）、「已启用 N / 共 N」改「共 N 个工具」并写明**全局工具只读**；`Common.kt` 与 `ChatScreen.kt` 最后两处 `!!` 去掉（全仓 `!!` 归零）。证据：`dist/notes/release-notes-0.8.79.md` 记 **355 tests / 0 failures / 0 errors / 0 skipped**、`:app:assembleRelease` BUILD SUCCESSFUL、装机 `versionName=0.8.79 / versionCode=90`（本记忆已实测 `app/build.gradle.kts:16-17` = 90/0.8.79，且 `HEAD dd30477 = origin/main`）。**Release v0.8.79 已发（lead 口径）** | `dist/notes/release-notes-0.8.79.md`（已读）、`dist/commit-msg-0.8.79.txt`、`git log -1`、`git status -sb` |
| 0.8.80 | `2ad3513` 2026-10-11 03:19（vc91，**HEAD**；tag `v0.8.80`，远端 `refs/heads/main` 也指向它，本轮走代理 fetch 后本地 tag 20 个到 v0.8.80） | 承接 0.8.79 自审的第二轮 + 用户睡前口径。**侧边栏返回的轻过渡**：进页照旧（淡入 + 8% 位移）；**返回来源页那一趟改纯淡入淡出 140ms、零位移**（去掉位移的理由＝抽屉此刻已在屏幕上，页面再横向滑 8% 会被遮罩读成「两层各动一半」）；抽屉始终在、不动、不重放滑入动画；其余导航（设置子页返回列表、权限/工具页返回等）**保持**带位移的统一转场。判据是**精确判断**而非「回到 chat 就算」（从设置页拉开抽屉再点一行时被弹掉的是设置页，用「回到 chat」会给出「无位移 + 抽屉没恢复」的不一致组合）。**冷启动不再先闪一帧反色**：原因是启动窗口颜色由**系统 uiMode** 决定、app 配色由设置里的深浅决定 ⇒ 强制深色而系统浅色时会先按系统画一帧白；修法＝`Core` 暴露**同步可读**的 `themeMode` 快照（`Core.init` 同步读一次 + 之后跟设置流更新），`MainActivity.onCreate` 在 `setTheme` **之前**按它挑 `Theme.Suixing.Light` / `Theme.Suixing.Dark`（两套只覆盖 `windowBackground`，其余继承；快照为空按「跟系统」）。**如实说明限制**：只修了「进程内那几帧」；**系统画的启动画面**（Android 12+ 启动窗口）在进程存在前就按系统 uiMode 画好了，app 内设置改不到——若仍白 200~300ms，只能靠「给所有人定一个固定启动底色」（产品取舍，未擅自做）。**浅色主题可读性（实测比值）**：状态胶囊文字 13sp 浅色下 OK 3.72 / WARN 2.98 / OFF 2.72（需 ≥4.5:1）⇒ 按最小压深调到 **4.56 / 4.57 / 4.60**，色相保持分开（绿 167° / 琥珀 35° / 中性灰 / 蓝 199°），深色侧不动；`outline`（输入框描边与焦点环）浅色 **1.70:1** ⇒ 提到 **3.52**（纯白底）/ **3.02**（最暗卡片底），拟合目标是「最暗的浅色容器」；`outlineVariant`（发丝线/分割线）**没动**（装饰性，改深会让满屏线变重）；两处都写了带实测数字的注释防「被调亮回去」。**LLM 三处**：① `data: [DONE]` 原来是 `continue` 而非 `break` ⇒ 网关在 `[DONE]` 后保持连接时整轮**卡到读超时甚至 10 分钟上限**（答案其实已全在手上）；② `JsonNull` 让整条回答崩掉（`jsonArray`/`jsonObject` 在 `null` 上**抛异常**，而网关确实会发 `"tool_calls": null` 这类帧）⇒ 全改 `as?` 跳过该帧；③ 超时文案：OkHttp 整轮超时抛的是 `InterruptedIOException`（不是 `SocketTimeoutException`），原来落到兜底分支把类名显示给用户 ⇒ 改成「这一轮超过 10 分钟被中断（回答太长或服务器一直没结束），可以再发一次或把要求拆成几步」。**关于页**：链接打不开时给原因（「设备上没有可用的浏览器」）而非静默无反应。**资源层核查结论**：62 个 drawable **零未使用**、3 条字符串**零死项**；`values-night/colors.xml` **不是死资源**（它是**启动窗口**的颜色，删了会闪桌面）——**不要动**。**引擎/工具层核对结论**：51 个工具**全部已注册**、每个声明参数都被读到、每个读取都有声明；`AgentEvent` **9 个分支全部有消费者**（`when` 无 `else` ⇒ 将来新增事件会是编译错误而不是静默丢失）；提示词里没有提到已不存在的工具/设置项。证据：`dist/notes/release-notes-0.8.80.md` 记单测 **355 tests / 0 failures**、`:app:assembleRelease` BUILD SUCCESSFUL、装机后 `versionName=0.8.80 / versionCode=91`；本记忆已实测 `app/build.gradle.kts:16-17` = 91/0.8.80、APK `dist/suixing-0.8.80.apk`（13,268,494 B @03:20:47）、notes/commit-msg 均 5,239 B。**Release v0.8.80 已发（lead 口径 + `gh release list`）** | `dist/notes/release-notes-0.8.80.md`（= commit 正文，已通读 40 行）；`git show --stat 2ad3513`；`git log -1`；`git ls-remote origin main`（走代理） |

| 0.8.81 | `fb6520a` 2026-10-11 03:2x（vc92，**lead 本轮未列，本记忆从 git log + notes 补上**） | **隐私与发布加固**（承接 0.8.79/0.8.80 自审的构建/发布层 + 测试层审查），**无用户可见行为变化**（除权限列表与备份范围）。**云备份不再带走内容**：`allowBackup` 一直开着、此前只排除 `datastore` ⇒ 现在 `data_extraction_rules.xml` 的 `<cloud-backup>` 共 **14 条 exclude**，新增排掉 `chats`（会话）、`memory.json`、`profile.md`/`profile.state.json`、`todos.json`、`schedules.json`、`mcp.json`、`jobs`，本轮再排 **`assistants`（上传的头像照片）** 与四个诊断日志（`fx-trace.log`/`voice-trace.log`/`scroll-diag.log`/`console-trace.log`，含工具输出片段）；**换机恢复（device-transfer）不受影响**（仍带走设置与会话——刻意取舍）；**API ≤30 的老系统只有一套规则文件（云备份与换机共用），那里保持不排除会话**，文件内已写注释防被当漏项补上；`usage.json`（用量）保留在云备份内。**发布加固**：`proguard-rules.pro` 补 kotlinx-serialization 的 R8 规则（`-if @Serializable` + `-keepattributes RuntimeVisibleAnnotations,AnnotationDefault`）——minify **本轮没开**（开它必须真机冒烟），但一旦开，`data/` 里 **19 个 `@Serializable` 类**会因改名在首次读写时抛 `SerializationException`；清掉三个零引用权限 `VIBRATE`/`WAKE_LOCK`/`ACCESS_NETWORK_STATE`（触感走 `performHapticFeedback`），权限 **16 → 13 条**。审查结论（未改）：`exported` 全对、无 `debuggable`、签名硬编码是自签侧载刻意设计、release 里 `Log.*`/`println` 零命中、API 守卫无缺口（`createOnDeviceSpeechRecognizer` 全仓不存在）、lint 关闭、`usesCleartextTraffic` 保留（局域网模型服务需要）。**测试**：新增 `MemoryStoreTest`（7 条）+ `McpStoreTest`（5 条，含超长拒写且一个字节都不写、0.7.0 旧格式升级、损坏文件隔离成 `*.corrupt.json`）；测试质量审查结论＝**没有假绿**（`@Ignore` 0、`assertTrue(true)` 0、`Thread.sleep` 0、吞断言 0；355 用例 / 815 断言；`ZoneId` 显式钉 UTC）。证据：单测 **355 → 367 全绿**、`assembleRelease` BUILD SUCCESSFUL、装机 `versionName=0.8.81 / versionCode=92` | `dist/notes/release-notes-0.8.81.md`（已读）；`git log --oneline`；`git ls-remote --tags origin`（走代理） |
| 0.8.82 | `ee806fd` 2026-10-11 12:5x（vc93，发版提交） | **侧边栏换成库实现**（用户指出两条：自绘只能整段出现/消失、边缘不跟手；返回时在副作用里把「打开」置上去、比目标页晚一帧 ⇒ 先闪一下页面）。改用 **M3 `ModalNavigationDrawer`**：边缘拖动跟手（拖到一半松手回弹）、遮罩同步变深、动画时序归库（不再两套动画打架）；**视觉零变化**（用户要求一致）：宽度仍 **300dp**、圆角仍**右侧两角 30dp**、底色仍 `surfaceContainerHigh`、tonal elevation **0**、遮罩仍**黑色 45%**、`WindowInsets` 仍由面板内那层自己管——**都与库默认不同，逐项显式覆盖并写理由**；面板里的行/图标/选中态/头部**一行未改**。**返回不再闪**：新增统一返回入口——当「这次返回的目的地就是抽屉来源页」时，**在 pop 之前、同一帧**把抽屉**直接摆成打开**（`snapTo`，不播动画＝用户要的「已经在那儿」）再 pop；文件里 **12 处返回**全接这个入口，系统返回键由单独处理覆盖，其余返回不拦（保留系统预测性返回）。顺带：抽屉里点行进入页面的**退出时长压到 120ms**（只这一条路径，让新页入场当主角）；删掉自绘时期两个状态标志、一个空 Box、两个孤儿 import。证据：单测 **367/367**、`assembleRelease`、装机 `0.8.82 / vc93` | `dist/notes/release-notes-0.8.82.md`（已读）；`git log --oneline` |
| 0.8.83 | `c81a81c` 2026-10-11 13:5x（vc94）+ 补丁 `ababb8b`（「0.8.83 补：GroupRowsTest 期望同步（`Transcript.chosenRuns` 修复后不再钉住旧现状）」） | 单测 **367 → 383 全绿**。**侧边栏三条**：① 换 M3 `ModalNavigationDrawer`（边缘拖动跟手、视觉逐项一致）；② **系统返回/手势返回也走同一条处理**（以前只有点顶栏返回按钮才生效，因为返回键派发被 NavHost 抢走）⇒ 改成**从路由派生**（`SideEffect` + `snapTo` + `UNDISPATCHED`），在**目标页出现那一帧**抽屉就已在那儿 ⇒ **四条返回路径（按钮/返回键/手势/预测性返回）观感一致**；③ **返回时底下页面不再动**（这一趟 pop 改**两侧零动画**，其余返回仍走统一规格）；抽屉点行进入＝抽屉 120ms 快速退出 + 新页入场。**边缘光只跟「正在操作手机」**（用户报的判定错误）：旧规则「驱动过的 run 还活着 ⇒ 一直亮」⇒ 跑十几分钟的任务里模型长时间思考/写答案（根本没碰手机）灯也一直亮；现在**只有两档**——**亮＝距上次操作手机 < 5 秒**（5 秒盖住最长一次滑动，避免动作中途熄灯）、**灭＝其它一切**（任务在跑但没碰手机、任务结束、你在随行里）；日志 `fx-trace.log` 每行带 `light=active|off`、`inFlight=`、`sinceAction=`、`activeWindow=5000ms`，判读口诀：**`drivingAlive=true` 而 `light=off` 现在是正常的**。**失败自动重试（新增）**：三个设置（**UI 待落**，要放进「对话配置」页）`retryOnFailure`（默认开）、`retryMaxAttempts`（默认 2）、`retryDelaySeconds`（默认 5）；**只对可恢复失败重试**（连接重置/超时/5xx/429），**429 优先用服务端 `Retry-After`**（可读数字夹到 1–60 秒，`0` → 1 秒；HTTP-date 之类不可读回落设置值）；**两条硬护栏**：①**已开始收到正文/思考就不再重试**（否则重发、可能重复计费且用户会看到重复内容）；②**用户点停止 ⇒ 立刻结束等待**（不等满）；4xx（除 429）/认证失败/参数错误一律不重试；重试期间实时行显示「第 N 次重试（Ms 后）· 原因」，最终失败信息带「（已重试 N 次）」；**15 条纯函数用例 `RetryPolicyTest`**。**其它**：**「允许使用 Root」开关撤掉**（用户说档次选择器就够、root 自己探测）⇒ 恢复「照常自动探测 root」、`enableRoot` 字段删除，但**保留**「显式点名 `mode="root"/"shizuku"` 只是请求、不能超过固定档次」；**一次回答被拆成两张卡**真因之一＝折叠条件里的 `run.isNotEmpty()` 让 `BASE_RUN`（分支出现前的老消息）两轮**永远不能折叠** ⇒ 去掉该条件（**另一条更严重的已定位待修**：无 runId 的消息会压过该回合真实 run ⇒ 正文被整条过滤掉，修点在 `data/Transcript.kt`）；**工具轮数上限为 0 时界面显示「无限」**；**输入框那排收紧**（左侧「+」与「工具审批」按钮 40→32dp、图标 22/20→18dp、行首内边距 12→4dp、间距 4→2dp，**触控区仍保证 48dp**，顺带修掉旧写法 `size(40.dp)` 把 M3 最小触控压到 40dp 的问题）；**失败信息说人话**（对话页失败行走 `describeError`：连接超时/证书不被信任/找不到域名/401 都有可行动文案，不再显示 `error.message` 原文）。证据：单测 **383 tests / 0 failures**、`assembleRelease`、装机 `0.8.83 / vc94` | `dist/notes/release-notes-0.8.83.md`（已读）；`git log --oneline` |
| 0.8.84 | `a65a74d` 2026-10-11 14:3x（vc95） | 单测 **384/384**。**① 点返回按钮时页面还在滑动——根因是「判据被提前清掉了」**：上一版把恢复逻辑改成「从路由派生」，并在**同一个 `SideEffect` 里立刻把 `drawerReturnTo` 清成 null**，而**转场规格是在转场开始时才求值的**，那时它已是 null ⇒ 这一趟又走了带滑动的常规规格（也就是说上一版直接调返回函数时那条「零动画」确实生效过，**改成路由派生之后就失效了**，作者自述「我没验证就交了」）。**现在**：新增只在「这次返回」期间为真的独立标志 `drawerReturning`——点顶栏返回按钮在 pop **之前**置真；系统返回键/手势返回由路由派生副作用在**同一帧**置真（`snapTo` 也是同帧，抽屉「已经在那儿」）；**转场判据取「或」**（`drawerReturning || 目标路由 == drawerReturnTo`）⇒ 无论转场 lambda 在哪种时序求值至少一条成立；标志要 **400ms**（> 最长转场 280ms）之后才落下。**判据：点按钮、按返回键、手势返回三条路都是零运动**。**② 进页「残留上一页残影」——根因是新页在淡入**：新页从 alpha 0 淡入的那 240ms 里旧页正在它后面淡出 ⇒ 旧页「透」过新页；现在**新页第一帧就不透明**（进页/返回进都只做位移、不做淡入），**只让旧页淡出 + 滑出**。**残影的另一半根因**：**4 个目的地根节点原本是透底的**（权限页、扩展页、文件页、工具页）⇒ 全部补上不透明底（现在 **15 个目的地没有一个透底**）。证据：单测 384/0、`assembleRelease`；**装机前用 aapt 核对 APK `versionName=0.8.84 / versionCode=95`**（上一版把旧包当新包发过一次，这条检查现在每次都做） | `dist/notes/release-notes-0.8.84.md`（已读） |
| 0.8.85 | `cf6459f` 2026-10-11 15:32（vc96，**当前 HEAD**） | 单测 **385/385**（该版 notes 自记 384；**lead 口径：0.8.85 之后 `FollowReleaseTest` 随长按阈值 450→500 调整并新增 1 条 ⇒ 现为 385/385**）。**① 抽屉：离开与返回都零动画**（去掉用户看到的「被关掉的残影」）：**离开（点行导航）**上一版把关闭压到 120ms 但**仍然是动画**⇒ 用户能看见它滑走，现在改成**同帧直接收起（零动画）**，新页干净入场；**返回**仍是同帧直接「在那儿」（零动画）；两边对称就不是「硬把它摆出来」了；**用户主动开合仍走库的动画**（汉堡键打开、左缘拖动、点遮罩、返回键关抽屉——这些是用户自己在操作，需要有动画）。可核对证据：侧边栏确实是库控件——`ModalNavigationDrawer`（`ui/SuixingApp.kt:379`，注释 `:202`）、`ModalDrawerSheet`、`rememberDrawerState`，来自 `compose-bom 2024.10.01` 解析出的 **material3 1.3.1**（notes 里记的 `SuixingApp.kt:409`/`:674`/`:216` 是**旧行号**）；自绘那套（手写遮罩/面板/滑入）在 0.8.82 已整块删除。**② 返回不再像「重新加载」**（作者确认是真 bug）：**没有重新加载会话**（ViewModel 在 NavHost 之外不会重建；`loading` 只在创建时可能为真、骨架屏不会二次出现）——是**三处视图状态被复位**，零动画让它们更明显：主因＝冷启动那次「跳到最新」的初始化 `LaunchedEffect` **每次回到这个页面都会重跑** ⇒ 滚动位置刚恢复就被顶掉、列表弹到底部；`following`（是否追底）每次回来复位成「追」⇒ 下一帧流式输出又把列表拽到底；「加载更早」加载过的页数每次回来缩回一页 ⇒ 内容「变少了」。**现在**：①那次初始化改成**每个会话只跑一次**（标志跟着滚动位置一起保存/恢复）；②`following` 与③已加载页数都改成**可保存状态**（`rememberSaveable`）⇒ 离开时在哪回来就在哪；**验收**：进设置 → 返回两次，`filesDir/scroll-diag.log` 里 `cold-pin:begin` **不应新增**（每个会话一行，而不是每次返回一行），全程不该再看到骨架屏。**③ 输入框那排：这次改的是「间距」**（上一版改错了地方）：上一版把按钮**圆盘**从 40 收到 32dp，但 M3 图标按钮**默认不画容器** ⇒ 用户看到的只有字形、等于没改；而把触控盒从 40 涨到 48dp，反而让**字形间距从 23dp 变成 32dp**（更松）。这次改真正看得见的间距：输入框左缘→「+」字形 **21.0 → 11.0dp**；「+」→审批字形之间 **23.0dp**（上一版 32.0）；审批字形→模型胶囊 **18.0 → 16.0dp**（上一版 21.0）；每个按钮触控区 **40×48 → 44×48dp**（取舍：为把左边距砍掉近一半又不把字形缩到 18dp 以下，触控盒取 44dp；若中间那道 23dp 还要更紧，代价是把触控降到 40dp）。证据：单测 385/0（该版 notes 记 384，后随 `FollowReleaseTest` 阈值调整 +1 ⇒ 385）、`assembleRelease`、**装机前 aapt 核对 APK 为 `0.8.85 / vc96`** | `dist/notes/release-notes-0.8.85.md`（已读） |
| 0.8.86 | `6854b24` 2026-10-11 16:xx（vc97；**后来已发版**——远端有 tag `v0.8.86`） | 提交标题：**自审修复 9 个潜在 bug + 侧边栏/返回/长按/插话图片/对话配置/边缘光 v2**。当时（0.8.86 轮）记为「工作树未打包」：`app/build.gradle.kts:16-17` = **97 / 0.8.86**、本地已构建 `dist/suixing-0.8.86.apk`（@16:28:54，**当时未装机**）。内容（lead 口径 + 当时实测锚点）：**侧边栏 ChatGPT 式双向动画**（**撤掉 snapTo**；点行＝抽屉 `close()` ∥ 新页入场同帧，返回＝抽屉 `open()` ∥ 页面退出同帧）——⚠️ **该口径在 0.8.97 已被推翻**，最终以 0.8.91 的 `snapTo + UNDISPATCHED` 为准（见 §3 第 1 条）；**chat 常驻 + 覆盖层**结构重构（`NavHost(startDestination = "empty")` `ui/SuixingApp.kt:450` + 栈底 `composable("empty") { }` `:492`）；**长按跳变**（当时的修法，后被 §3 第 4 条取代）；**插话带图片**（`imageBase64` 贯穿 `Steer`/`AgentEvent.Steered`/`PendingSteer`）；**排队＝新一轮**（`steerMode=0` 走 `pendingTurns`）；**「对话配置」新子页**；**边缘光 v2 回合模型**（`EPISODE_QUIET_MS = 3000L`，`service/TouchFx.kt:134`）。当时改动的文件：`app/build.gradle.kts`、`agent/AgentEngine.kt`、`data/Settings.kt`、`service/TouchFx.kt`、`tools/UiTools.kt`、`ui/SuixingApp.kt`、`ui/chat/ChatScreen.kt`、`ui/chat/ChatViewModel.kt`、`ui/settings/ProfileScreen.kt`、`ui/settings/SettingsScreen.kt`、`app/src/test/java/com/suixing/agent/ui/chat/FollowReleaseTest.kt`。**notes 缺失**（`dist/notes/` 无 0.8.86） | `git log --oneline`；`git ls-remote --tags origin`（走代理，`v0.8.86 → 6854b24`）；当时 `git status --short` |

| 0.8.90 | `f44b3e7` 2026-10-12 01:3x（vc101 前） | 提交标题：**涟漪统一 + 文本框收紧 + 顶栏下移 + 抽屉只在会话页 + 编辑助手页 + 头像裁剪 + 会话管理 + 用量页 + 画像收紧**。**有 notes**：`dist/notes/release-notes-0.8.90.md`（4,621 B，本轮未通读）；APK `dist/suixing-0.8.90.apk`（13,301,474 B @01:39:35）。 | `git log --oneline`；notes 存在 |
| 0.8.91 | `966b186` 2026-10-12 02:3x（vc102 前） | ★ **用户唯一认可的转场版本**：**进页 `slideInHorizontally(+1/12, 200ms)`、出 `ExitTransition.None`**（当前树 `ui/SuixingApp.kt:518-525`，常量 `NAV_SLIDE_MS=200` `:168`、`NAV_SHIFT_DIVISOR=12` `:171`）。提交正文（已读）：**长按跳变真机日志定位**——636px 那次滚动是**框架选择机制**把选中行拉进视野、**不是我们的任何路径**；修法＝按下期间**按帧采样** + 静止长按后把位置还原到按下处（触摸斜率判据），**+5 用例**；**提示条**上移到顶栏下方 **64dp**、默认 **10 秒**自动消失（外观页可设 5/10/30/不消失）、中断/错误原因改为**渲染在卡片内**；**任务清单**改单行（进行中 + 完成/总数），点击开半屏完整清单；**边缘光**参考 **Operit（RainbowBorderOverlay）源码**从布尔开关改成**连续量**（`targetAlpha` + 每帧缓动），驱动过的 run 存活期间**低亮呼吸**、整轮结束才灭；截断提示改文案（留空时我们**根本不发** `max_tokens`，是上游截断）+ 系统提示词加分段写文件规则；设置页输入框统一 **14sp + `heightIn(min=52dp)`**（M3 无 contentPadding，写死高度会裁字）；**插件系统 M1**＝`PluginStore` + `ManifestValidator`（**13 个 reason code**）+ 插件页 UI（列表/白盒详情/安装确认/env）+ Core 接线，**26 条校验器单测**。证据：单测 **427/427**。**notes 缺失**（`dist/notes/` 无 0.8.91） | `git show -s --format=%B 966b186`（已读）；`git log --oneline` |
| 0.8.92 | `23901ad` 2026-10-12 03:1x | 提交标题：**置顶图标 + 顶栏 icon/标题对齐 + 转场分流（子页返回从左滑回 / 抽屉路线淡入 110ms）+ 对话框窗口 inset 兜底 + 折叠块统一 + 输入框 15sp/50dp + 助手卡片星号贴头像**。⚠️ **其中的转场改动被用户否**（属 0.8.92~0.8.97 六次被否的改动之一）。**notes 缺失** | `git log --oneline` |
| 0.8.93 | `434308c` 2026-10-12 03:2x | 提交标题：**修插件页闪退（LazyColumn 套 LazyColumn）+ 返回淡出（消硬切闪动）**。**notes 缺失** | `git log --oneline` |
| 0.8.94 | `ac5567d` 2026-10-12 03:3x | 提交标题：**抽屉路线转场回退到 0.8.91（用户认可的那版）+ 插件/技能页首帧空态闪烁修复**。**notes 缺失** | `git log --oneline` |
| 0.8.95 | `5ca8804` 2026-10-12 03:3x | 提交标题：**六个设置子页改走真路由（终于吃到返回动画）+ 聊天页浮层头标题光学下移 1.5dp**。**notes 缺失** | `git log --oneline` |
| 0.8.96 | `4316730` 2026-10-12 03:4x | 提交标题：**返回时底部不再闪（底色等转场跑完才落透明）+ 设置子页顶栏标题下移 9dp（符号写反了）+ 子页返回＝上层向右滑走**。⚠️ **「opaque 底色 + delay」那套已废弃，别再引入**（见 §5）；顶栏 ±9dp 补偿数字也被 0.8.97 的结构性对齐取代。**notes 缺失** | `git log --oneline`；lead 口径 |
| 0.8.97 | `39d06bd` 2026-10-12 12:54（vc108，**当前 HEAD**；远端 main 同指） | 提交正文（已读）：**插件 M2 读管道**——接线点 A＝`AgentEngine` 组装 payload 时**追加启用插件的工具**（`<id>.<tool>`、**schema 透传**、**重名跳过并记日志**，`agent/AgentEngine.kt:1058-1063`）+ 系统提示固定文案；新增 `plugins/PluginRuntime.kt`（`object PluginRuntime` `:24`，`suspend fun enabledPlugins()` `:34` 用 `runCatching` 兜底）+ **8 条单测**；**容错**＝`enabledPlugins` 读插件失败 / `Core.plugins` 未初始化时**返回空表**（**此前会把整轮 run 带崩**，7 条测试挂 `UninitializedPropertyAccessException`）；**文件页三根（菜单）**＝工作目录 / `/sdcard`（带权限闸门）/ **插件目录（只读、无闸门）**（`ui/files/FilesScreen.kt:87-89` 第三个根＝`File(context.filesDir, "plugins")`、`:217-220` 未创建先 `mkdirs` 不再误报权限不足；三态文案 `:167-189`）；插件详情页显示「目录」绝对路径、点击复制；**返回第一帧不再空白**＝**删掉外壳那层「跟 route 走」的不透明底**（每页自带不透明底）；**顶栏结构性对齐**＝标题 `Box(Modifier.weight(1f)) + align(Alignment.CenterStart)`（`ui/components/Common.kt:592-599`，注释 `:579-591` 写明「不再依赖任何 ±Ndp 补偿」，并记历史：曾用 `.offset(y = if (subtitle != null) 9.dp else 0.dp)`、**还写反过一次符号**）；**长按冻结**＝按住期间用 `NestedScrollConnection` **吃掉滚动增量**（`ui/chat/ChatScreen.kt:979-991`，`if (pressed && !fingerDragged) available else Offset.Zero`；注释 `:970-976` 说明为什么不能只靠事后还原），拖动/fling 放行，`restoresPositionAfterLongPress`（`:7544-7549`）保留作兜底；插件页 env 按钮条件渲染、启用/停用二选一、空态不残留。证据：单测 **435/435**。⚠️ **正文与代码不一致**：正文写「点抽屉一行：抽屉 `close()` 滑出 + 页面 `fadeIn` 同帧」，但**当前代码是 `snapTo(DrawerValue.Closed) + UNDISPATCHED`**（`ui/SuixingApp.kt:252-255`、返回 `:285-287`、注释 `:239`/`:252`/`:295`，明写「0.8.91 原样」）——**以代码与 lead 的最终口径为准**（见 §3）。**notes 缺失** | `git show -s --format=%B 39d06bd`（已读）；`git status`；`git ls-remote origin main`（走代理） |

**「素材缺失」版本汇总**（只有 APK 或只在别版正文里被提及，无自身 commit/notes）：
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

### 0.8.75 修复的根因（已发版，行号已核）
- **长按文字时列表跳变（真因）**：不是追底阈值，而是 `TurnScrubber` 的手势层是**整幅宽**——手势 `Column` 原 `fillMaxWidth` + `down.consume()`，屏幕右侧一整条横带都吃按下，手指落在轨道左边空白处长按被导航条接走 ⇒ 选中某一轮 ⇒ 抬手跳转。修：手势层只占 `SCRUBBER_RAIL`(24dp) × 行高、右对齐；外层 Box 仍 `fillMaxWidth` 但无手势修饰符（只给预览气泡留测量约束）。当前树锚点：`ui/chat/ChatScreen.kt:3584`（`.pointerInput(Unit)`）、`:3591`（`awaitEachGesture`）、`:3595`（`down.consume()`）、`:3666`（`change.consume()`）、`:3834`（`SCRUBBER_RAIL = 24.dp`）；commit 正文自标 `ChatScreen.kt:3468-3512`（写提交时的行号，现为 3551-3595 一带）。
- **中断留存（0.8.72 的修法无效的真因）**：0.8.72 只给 `LiveDraft` 补了 `reasoning`/`messages` 字段，但 writer 的两道 text 闸门 `val text = live.streaming; if (text.isBlank() || text == written) continue` 让**只流思考、只发工具调用的轮次根本不产出 draft 文件**（`mergeLiveDraft` 于是在 `readLive ?: return` 早退），正文没变时的思考/工具增量也永不落盘 ⇒ 改「每秒写一次、内容没变才跳过」，写下正文 + 思考 + 本轮已产出的消息（`liveDraftFor`，现锚 `ui/chat/ChatViewModel.kt:1650`；`LiveDraft.sameContentAs` `:1677`；`LIVE_DRAFT_INTERVAL_MS = 1000L` `:1738`）；另：一行正文都没出、只在思考时被中断的轮次现在也留「（中断）」卡。commit 正文自标 `ChatViewModel.kt:1415-1435`（写提交时行号，现已漂移）。
- **圆环「缓存命中」那行消失**：`cachePercent = cached * 100 / prompt` 是 Int 运算，会话累计 cached 一旦 > **21,474,837**（≈2147 万 token）就溢出成负数、<1% 又被整除截断成 0，UI 判为「没有」而不画 ⇒ 改 `(cached.toLong() * 100.0 / prompt).roundToInt()` 且 **0% 也照画**（现锚 `ui/chat/ChatViewModel.kt:248`，字段声明 `:221`；commit 正文自标 `:242`）。圆环弹窗标签同时去掉「会话」前缀（总用时 / 输出速度 / 累计用量），属于本轮的行仍写「本轮」。

### 0.8.76 修复的根因（已发版，行号已核）〔第三批，下表含当时的任务编号〕
- **模型弹窗 provider 组头首字形被裁（`qwen` 的 `q`）**：该行自身带 `.clip(MaterialTheme.shapes.small)`（随「外观 → 圆角」三档 6.6/12/18dp）+ **横向 0 padding** ⇒ `q` 的降部扎进左下圆角弧；`workbuddy/test` 无降部故无损。改法＝加 `start/end = 4.dp`（task-9；`ui/chat/ChatScreen.kt` 组头渲染点）。
- **启动文案**：`LoadingSkeleton()`（`ui/chat/ChatScreen.kt:1390`）里的 `text = "正在打开…"`（`:1393`，全仓唯一一处）→ 改「加载会话中…」（task-9）。
- **语音「半残废」**：`VoiceOverlay` 是聊天页**内嵌浮层**（`ui/chat/ChatScreen.kt:5273` 的 `useSystemInput`、`:5297` 调用、`:5396` 的「改用系统语音输入」）。`ERROR_CLIENT` 自动 `useSystemInput()` 把系统识别 Activity 拉到前台却**不收起自己那张卡** ⇒ 两层同屏（参考图 B 里的「正在倾听 + 灰条」是系统/MIUI 自己的界面，**不是本项目的 Canvas**，全仓 grep 零命中）；参考图 A 右下角绿色方块＝失败态「重试」M3 按钮被卡片 `.clip()` 切在右缘（三按钮一行塞不下，`defaultMinSize` 不缩）。改法＝交接即收起 + 失败态按钮拆两行（task-9）。
- **文件页「不可用」**：根被钉死在 `getExternalFilesDir`（`tools/FileTools.kt:416-417` 的 `workspaceDir(context)`，同文件 `:21` 的 `workspace` 属性；`tools/UiTools.kt:243`、`skills/SkillStore.kt:36`、`ui/chat/ChatScreen.kt:6130` 的 inbox 同样根），而 `:17` 的注释与页面文案却承诺「授权后可访问 `/sdcard/...`」却没有对应代码 ⇒ 改成可切「应用目录 ↔ /sdcard」（task-8）。
- **权限档次接执行链**（task-5）：`preferredCapability` 已持久化（`data/Settings.kt:244`）但执行链写死档位（`tools/UiTools.kt:219`/`:230-231`/`:237`、`tools/ShellTools.kt:128-129`/`:223`/`:231-232`、闸门 `agent/AgentEngine.kt:1663-1664`）；口径＝**未指定时取权限最高的可用档**。
- **会话懒加载 + 导航条向上解锁 + 进会话重置**（task-6，写者 b1-scroll）。
- **回车 = 换行 / 发送设置**（task-7 数据层与设置页已完成）：`data/Settings.kt:282` `val enterKeySends: Boolean = true`（`:279` 注释：缺键=true=现行行为）+ 设置页开关行 `ui/settings/SettingsScreen.kt:1197-1198`；**composer 行为未接**——`ui/chat/ChatScreen.kt:4350` 仍是写死的 `KeyboardOptions(imeAction = ImeAction.Send)`，等 task-6 让出文件。
- **长按复制仍跳变（只在重开软件后）**：recon 结论＝app 内无任何「长按即滚动」代码（`BringIntoView*`/focus API 全仓 0 命中）；嫌疑＝冷启动首次 pin 走两遍路径（`pinnedCount = -1` → `withFrameNanos` 后再 `scrollBy`），且 pin 的 `LaunchedEffect` 键不含 `following` ⇒ 已在飞的 pin 在手指按下后仍滚到底。改法＝每次滚动前重判 `following && autoFollow` + 临时诊断（task-9；相关锚点 `ui/chat/ChatScreen.kt:629-644` 的 heldIme pin、`:742-772` 的按下即释放）。
- **顶栏与页面同色**：`ui/components/Common.kt` 的 `surfaceContainerLow` → `background`（本调色板 background ≡ surface）；**抽屉状态恢复 + 动画错开**；**抽屉助手改半屏弹窗**（设置页助手仍整页）——写者 settings-routes，落点 `ui/SuixingApp.kt` + `ui/components/Common.kt`。
- **全局风格统一**（字体/边距/设计）用户点名但当时**尚未派单**（0.8.78 已完成第一/二轮，见 §2 0.8.78 行）。

### 0.8.76 修复的根因（已发版；本节为核实后的行号版本，上一节保留当时的 task 编号条目）
- **长按复制仍跳变、且只在重开软件之后**：真根因＝追底 pin 是 `LaunchedEffect` 且**键里没有 `following`** ⇒ 手指按下把 `following` 置 false 之后，**在飞的那趟 pin 依然把列表滚到底**；冷启动窗口最宽，因为**第一次 pin 走两遍路径**（首帧高度未知，等一帧再滚一次，`pinnedCount = -1`）⇒ pin 内**每次滚动调用前**重判一次闸（`following && autoFollow`），冷启动那条 pin 也各自重判（现锚 `ui/chat/ChatScreen.kt:535`/`:582`/`:597` 三处 diag 所在的 pin 路径）。**临时诊断**：`scrollDiag(context, tag, listState, following, itemCount)`（定义 `ui/chat/ChatScreen.kt:5826`）在 6 处调用点各带「量完删」注释——`:509`（`watch:`）、`:535`（`cold-pin:begin`）、`:582`（`pin:added`）、`:597`（`pin:second`）、`:794`（`press:down`）、`:807`（`press:up`），写 `filesDir/scroll-diag.log`；**复现后按日志定论、用完删**。
- **模型弹窗「qwen」的 q 被裁**：不是宽度也不是省略号——该行自带的 `.clip(MaterialTheme.shapes.small)`（随「外观→圆角」三档 6.6/12/18dp）+ **横向 0 padding** ⇒ `q` 的**降部**扎进左下圆弧被削；`workbuddy/test` 首字母无降部所以无损 ⇒ 组头加 `start/end = 4.dp`（与厂商组头对齐）。
- **语音两个弹窗重叠**：「正在倾听 + 四条灰条」那屏是 **MIUI 自己的 ASR 面板**（全仓无此字符串），重叠发生在**主路径**（我方卡 `phase = Listening, handedOff = false` 时 MIUI 面板同时出现）⇒ **交接即收起**（`handedOff = true` `ui/chat/ChatScreen.kt:5326`；交接失败需还原 `:5341`，否则提示永远看不见；`:5373` `if (handedOff) return`），并给系统输入的**成功**分支补 `onDismiss`（原来只有取消/失败才关，成功后会留下「看不见但开着」的状态）。绿色方块＝失败态「重试」按钮被卡片圆角裁在右缘 ⇒ 卡片横向 40→24dp + 按钮拆两行。
- **会话懒加载 + 导航条向上解锁**：默认只渲染最近 **12 轮**（`ui/chat/ChatScreen.kt:1843 internal const val PAGE_TURNS = 12`，＝导航条 6 行窗口的两倍：向上解锁还有一整窗余量才撞到已加载边界），滑到顶有「加载更早的 12 轮 · 上面还有 N 轮」（`:858` 文案、`:854` 点击 `shownTurns += PAGE_TURNS`，计数 `var shownTurns by remember(state.conversationId) { mutableIntStateOf(PAGE_TURNS) }` `:331`）；导航条滑到最顶那条时自动向上解锁临近轮次、抬手归零、**每次进会话重置**（`:329` 注释）；轮次编号改**绝对轮次**。**取舍如实说明：省的是渲染与流式重算，磁盘仍整份读（`ChatRepository.load()` 未动）**——要做真分页必须动 engine 写入路径。
- **权限档次真正接进执行链**：只有**一个解析点** `CapabilityState.shellTier(preferred)`（`capability/CapabilityHub.kt:97`；`CapabilityHub.shellTier()` `:154`，读 `preferred` 流）——口径＝**自动（未指定）取当前可达的最高档**（用户口径，KDoc 逐字写明）、**显式固定的档只走那一档，不可用时只往下退，绝不偷偷升 root**；`""`（自动）与 `"none"`（显式固定标准档）是**两个不同状态，别混**。调用点：`tools/ShellTools.kt:129`（`else -> when (capabilities.shellTier())`，注释 `:128`）、`:224`；`tools/UiTools.kt:220`（`privileged()`）、`:228`、`:235`（`shellName()`，原来 LOCAL 会说成 root/Shizuku）；闸门 `agent/AgentEngine.kt:1665`、`:1672`。7 条单测覆盖无偏好/有偏好/向下回退/未知值当自动（本记忆复核＝**提交现在树上确有** `app/src/test/java/com/suixing/agent/capability/ShellTierTest.kt`（4,245 B，6 个 `@Test`；提交正文按用例条数记作 7）。**证据边界**：代码与测试文件是我逐行读过的，272/272 + assembleRelease + 装机读数来自 commit 正文与 `dist/notes/release-notes-0.8.76.md`——**本记忆没跑 gradle、没上真机**，真机行为仍属「待用户复核」）。
- **文件页终于能出应用目录**：根原来钉死在 `getExternalFilesDir` 且上一级走不出去（文案却承诺能看 `/sdcard`）⇒ 顶栏可切「应用目录 ↔ /sdcard」：`ui/files/FilesScreen.kt:98` `rootLabel = if (root == appRoot) "工作目录" else "/sdcard"`、`:167` 按钮文案（未授权时「授权后查看 /sdcard」）、`:92` 回来自动落到 `/sdcard`、`:178` 上一级夹在当前根之内、`:202-208` 三档提示文案。
- **密度与按档查看权限**：工具页搜索框 56→44dp、工具行 10→7dp、间距 8→6dp、MCP 地址框 6dp/2 行、编辑弹窗输入框 2→1 行；权限页卡片 18→16/12dp、卡内 12→8dp、行内 2→1dp、按钮统一 36dp、说明行 10→6dp、Hero 18→14dp；四段档次选择器下**逐条列出该档解锁了什么**（自动档按实际生效最高档显示），与四张卡共用同一份文案。

---

### 0.8.78 / 0.8.79 修复的根因（已发版，行号已核）
- **悬浮窗常驻不显示（0.8.78 的解释是错的）**：真因＝`service/AgentForegroundService.onDestroy()` **无条件** `FloatConsoleService.stop(...)` —— run 开始把 console 服务起回来（球显示）、run 一结束就把它杀掉，而抽屉开关仍 ON、无人再拉起 ⇒「常驻」永不成立。**推翻**：0.8.78 记的「MIUI 在后台隐藏 `TYPE_APPLICATION_OVERLAY`」**是错的**（用户反证：运行时在后台能显示）。修法＝`onDestroy` 只在抽屉开关为关时才 stop（`service/AgentForegroundService.kt:101-107`，`if (!Core.settings.current().floatingConsole)`；注释 `:92-100`）+ `MainActivity.onStart` 兜底自启（`MainActivity.kt:36-40`）；诊断 `filesDir/console-trace.log` 带 `resident=` 与 `service=taskRemoved`。
- **圆环整卡「暂无数据」**：端点不报 usage + 老会话消息里也没有 usage ⇒ 三级回落全为 0。修法＝**第四级文本估算**，复用引擎同一口径（`agent/AgentEngine.kt:1864` `internal fun estimateTokens(message: StoredMessage)` 改 internal；`ui/chat/ChatScreen.kt:181` import、`:416` `sumOf { estimateTokens(it) }`，口径注释 `:414`），标「约」+「累计用量」兜底行 + 空会话文案改「新会话 · 还没有用量」。**另一个 bug**：统计缓存键**漏了会话 id**（两个形状相同的会话会串）。
- **思考块「超过 200 行就自己变了」**：`ReasoningBlock` 内硬编码 `cap = 200` + 三处自动折叠（流式结束自动收起、stored 写死 `startExpanded = false`）⇒ 改默认展开、点标题收起、按会话记住；性能靠限高 + 块内滚动（该块本就是 LazyColumn）。
- **markdown 有序列表序号全是 1**：思维链按**行**渲染（每行一个 `MarkdownText`）⇒ 每行解析成单项列表、marker 画 `index + 1`（index 恒 0）。修法＝字面数字进 AST（`MdBlock.Bullet.start`，`ui/components/MarkdownText.kt:127`/`:218`）+ `internal fun orderedMarker(block: MdBlock.Bullet, index: Int) = "${block.start + index}."`（`:804`，调用 `:841`）；顺带修好正文里「从 3 开始」的列表。9 条新单测。
- **`PageTopBar` 的 12dp 在 Scaffold 宿主页不生效**：`Surface` 与 `Spacer` 是**兄弟节点**，M3 1.3.1 的 Scaffold 用 `maxByOrNull { it.height }` 取槽高（子节点都落在 (0,0)）⇒ 10 个页面栏下没有 12dp。修法＝把 Spacer 移进 Surface 自己的 Column（`ui/components/Common.kt`）。
- **任务页崩溃**：`items(key = { it.id })` 而模型自报 id、store 只补空 id 不去重 ⇒ 重复 key 抛 `Key "t1" was already used`。修法＝`itemsIndexed(rows, key = { index, row -> "$status#$index#${row.id}" })`（`ui/tasks/TasksScreen.kt:179`；聊天列表 `ui/chat/ChatScreen.kt:934` 同法）。
- **上下文窗口对话框数值错**：`current/1000` 取整 + `take(4)`（131072→1310，差 60 倍）+ **浮点截断**（1001 显示 1.001、写回 1000）。修法＝全链路按完整精度往返 + `Math.round`；KDoc 写明「内部按 tokens 存、显示与解析全精度、不再有第二次取整」。
- **定时任务不重排 / 错过的一次性提醒永不响**：manifest 只有 `BOOT_COMPLETED` ⇒ 改时区/校时/重装后不重排（「每天 9:00」会在错的本地时间响）⇒ 补 `TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED`（`app/src/main/AndroidManifest.xml:107-116`，注释 `:105`）+ `SchedulerService.refresh()`（`service/SchedulerService.kt:214`）；错过的 one-shot 永久「已启用」却永不响 ⇒ `Schedule.missedAt`（`data/ScheduleStore.kt:56`）+ `expireMissed(now)`（`:161-167`，arm 时扫、**不补响**）+ 列表/工具输出显示「已过期」（`ui/schedules/SchedulesScreen.kt:193`/`:207`/`:212`、`tools/ScheduleTools.kt:121-124`/`:165-166`）。
- **引擎 `sanitize()` 只丢一半**：丢弃「有未回答 tool_call 的 assistant」却留下**孤儿 TOOL 消息** ⇒ 端点照样 400。修法＝补镜像过滤（`agent/AgentEngine.kt:1608` `private fun sanitize(messages: List<StoredMessage>)`，调用 `:942`/`:947`，注释 `:1268`）；压缩函数 `wire.first()` 无守卫 ⇒ `firstOrNull() ?: return`（`:1431`）。
- **MCP 四处**：工具名可能超 64（服务端拒）⇒ 保证 ≤64 + 长服务器名短哈希防撞名（6 条单测）；`timeoutMs` 被 HTTP 传输**完全忽略**（只有 stdio 遵守）⇒ 每次调用 `newBuilder().callTimeout(deadline, TimeUnit.MILLISECONDS)`（`mcp/McpTransport.kt:251`）；子进程 `close()` 只发 SIGTERM ⇒ destroy → 等 300ms → 强杀（等待放 IO）；上报版本号写死 `0.7.0` ⇒ 读真实包版本。
- **数据安全**：MCP/定时/todo 三个 store **不是原子写、损坏会静默清空** ⇒ temp+rename + `*.corrupt.json` 隔离（`data/McpStore.kt:59`、`data/ScheduleStore.kt:124-126`、`data/TodoStore.kt:44-46`；与既有 `data/ChatRepository.kt:189-190`/`:217` `CORRUPT_SUFFIX`、`data/Memory.kt:146-149`、`data/UserProfile.kt:169-172` 一致），隔离文件 30 天后清理；`web_fetch` 全量读内存（OOM 风险）⇒ 流式 + 1MB 上限（`tools/WebTools.kt`）；下载改 `.part` + 成功后改名 + 超期清扫；相对路径含 `..` 直接拒绝。
- **权限档次：显式档位不再能越级**：`enableRoot` 从「没人读的死字段」变成**真开关**（`data/Settings.kt:184` `val enableRoot: Boolean = true`；`capability/CapabilityHub.kt:155` 注释 + `:169` `applyRootSwitch(saved.enableRoot)`；UI `ui/capability/CapabilityScreen.kt:211`/`:221`/`:231-233`）；显式 `mode="root"/"shizuku"` 只是**请求**、不能超过固定档（`minOf(requested, resolved)`，14 条测试）。⚠️ **历史事实**：该字段原本 `false` 且无人读 ⇒ **所有人实际都在用 root**，所以默认值必须留 `true`（默认改成关＝「什么都没点却掉一档能力」，且冷启动会弹 superuser 框）。
- **skill 默认值（复核过代码）**：模型新建＝**默认开启**（`tools/SkillTools.kt:126` `skills.save(id, skills.compose(...), enabled = true)`，用户要求「你在对话里让它记的东西不该还要手动开一次」）；URL/SAF 导入＝**仍默认关闭**（`tools/SkillTools.kt:133` `importUrl`；信任边界：技能的名字+描述会拼进系统提示词，而工具链里有 shell/删文件/无障碍点击）。⚠️ **残留风险**：外部内容若诱使模型调 `skill_save`，该技能会**当场生效**（旧规则下它会躺着等人点）。
- **`messagesRevision`（性能 + 正确性）**：用「唯一收口处打戳」（公开 `state` 流里按 `===` 引用比较自增）替代「每次重组逐元素比较整条消息列表」，覆盖全部 26 处写入路径（`ui/chat/ChatViewModel.kt:90` 字段、`:298`/`:303`/`:321` 打戳、`:966` 说明；消费方 `ui/chat/ChatScreen.kt:366`/`:373`）；`editMessage` 的 KDoc 写明依赖它。
- **其它 0.8.79 一致性/健壮性**：`MenuCard` 的 `focusable` 默认改 `false`（点圆环收输入法的坑）；导航条跳转 `turnIndices[turn]` 加 `getOrNull`（窗口缩小时越界崩溃）；主线程写 1.9MB 录音文件挪 IO；文件页预览连点两文件的竞态 ⇒ 过期请求丢弃、复制失败用 Toast；助手选图失败给提示；权限页探测中禁用「检测」按钮；工具页搜索框去掉写死高度（0.8.76 收紧密度时压过头 ⇒ 文字下缘被裁）；「已启用 N / 共 N」改「共 N 个工具」并写明**全局工具只读**；`Common.kt` 与 `ChatScreen.kt` 最后两处 `!!` 去掉（**全仓 `!!` 归零**）；`TouchFx` 的 `appContext` 加 `@Volatile`、`drivingIds` 两处读-改-写加锁（丢更新会导致「该亮不亮 / 该返回不返回」）、trace 线程改 daemon 低优先级。

---

### 0.8.82~0.8.86 修复的根因（**均已发版**，0.8.86 随 `6854b24` 发出；行号已核）
1. **侧边栏「返回闪一下页面」**＝自绘抽屉 + 在副作用里把「打开」置上去、比目标页晚一帧 ⇒ 0.8.82 改用 M3 `ModalNavigationDrawer`（拖动跟手、遮罩/位移归库）+ pop 前同帧 `snapTo` 摆成打开。**视觉逐项显式覆盖**（300dp、右两角 30dp、`surfaceContainerHigh`、elevation 0、黑 45% 遮罩、insets 自管）——它们都与库默认不同，不写就会被库改掉。
2. **点返回按钮页面还在滑动（0.8.83 修了一半）**＝**判据被提前清掉**：在同一个 `SideEffect` 里立刻把 `drawerReturnTo` 清成 null，而**转场规格是转场开始时才求值的**，那时已是 null ⇒ 又走带滑动的常规规格。修法＝独立标志 `drawerReturning`（pop 前置真 / 路由副作用同帧置真）+ **判据取「或」**（`drawerReturning || 目标路由 == drawerReturnTo`）+ 标志 **400ms**（> 最长转场 280ms）后才落下。**教训：转场 lambda 的求值时机不等于「你设置标志的时机」，别在同一作用域里立刻清掉它。**
3. **进页「上一页残影」**＝新页从 alpha 0 淡入的 240ms 里旧页正在后面淡出 ⇒ 旧页「透」过新页；**另一半根因**＝4 个目的地根节点（权限页/扩展页/文件页/工具页）**原本透底**。修法＝**新页第一帧就不透明（进页只位移不淡入）**+ 旧页淡出滑出 + 4 个目的地补不透明底（**15 个目的地无一透底**）。
4. **长按复制跳变（第五次才找到的真凶）**＝按下时追底被 `snapshotFlow` 观察者的 **else 分支**（`!scrolling && atEnd ⇒ following = true`）在**手指还按着**时还原（每次 pin 结束都会 emit 一次）⇒ 下一帧 pin 跳到底。历次修复（0.8.74 按下即释放、0.8.76 pin 补闸、0.8.85 冷启动 pin 一次性、阈值 450→500）**全在「按下之后 / 松手时」**，所以都没治到。**修法**：`pressed` 瞬态标志（**按着时只许关不许开**，`ui/chat/ChatScreen.kt:570` 声明、`:595`/`:599` 的 else 分支加 `!pressed` 守卫）+ **把 `following` 提为 pin 的 key**（按下即**取消**在飞的 `scrollBy`）+ 阈值读平台真值 `LocalViewConfiguration.current.longPressTimeoutMillis`（`ui/chat/ChatScreen.kt:876`；我们原来写 450 < 平台 500，那 50ms 是「系统已算长按、我们当成轻点」的窗口；`:6972` 注释说明平台值 500）。**库已排除**：反查 foundation 1.7.5 aar，`text/selection/` 下 **0 个 bring-into-view 类**。
5. **插话带图片**：三处载荷（`AgentEngine.Steer` `agent/AgentEngine.kt:97`、`AgentEvent.Steered` `:86`、`ChatViewModel.PendingSteer` `ui/chat/ChatViewModel.kt:67`）加 `imageBase64`（默认值兼容）；**实时注入与历史回放两条路都要带图**（只改实时会造成「这轮看得见、后续重建历史时丢图」——`appendToWire` 的 KDoc 早有「实时加的东西回放必须同 commit 跟上」的约定，`AgentEngine.kt:1398-1407` 是实时路、`:1713-1748` 是回放路）；插话气泡走 `UserBubble` ⇒ 缩略图 + 全屏预览自动具备（`ui/chat/ChatScreen.kt:3363`）。
6. **排队＝新一轮**：`steerMode=0`（`data/Settings.kt:154`）不进 `PendingSteer`、不画插话气泡，入内存 `pendingTurns`（`ui/chat/ChatViewModel.kt:461` 声明、`:1096` 入队、`:1136-1139` 出队），run 结束后 `send(text, image)` 发新一轮；**两处 drain**（状态观察者 + run 的 `finally`）——因为引擎**先 publish 终态、后在 `finally` 注销 run**，只挂观察者会卡住；**成功才出队**；**进程死即丢**（落盘会变成「重启后自己发消息」）。
7. **边缘光 v2 回合模型**（用户转来的规格书）：**亮＝一次 UI 自动化回合进行中**；触发集**含读屏**（`ui_dump`/`ui_screenshot`，与更早「读屏不算驱动」相反，规格书称用户已确认）+ 控制类 + `run_shell` 里的 `input tap/swipe/text/keyevent/drag/roll`、`am start`；**静默 3s**（`EPISODE_QUIET_MS = 3000L`，`service/TouchFx.kt:134`）或**整轮 run 结束**即灭；非 UI 工具中性；单窗口常驻 + alpha 亮灭（淡尽停重绘）；trace 加 `episode=` 字段（`:563-565`）。触发集定义 `FX_UI_EPISODE_TOOLS` `:137-138`，注释 `:127-128`/`:450`/`:485`。
8. **「对话配置」新子页**：四段（输入/运行/失败处理/显示）、**迁入 14 项**、**原「插话与运行」整页删除**、画像两行进用户画像页、外观页「恢复默认」只重置留在该页的五项；**唯一行为变化**＝来自模型页的两项（轮数 / 显示思考过程）改即改即存。

---

### 0.8.90~0.8.97：动画最终口径 / 返回空白 / 顶栏 / 长按 / 插件（已发版；行号已核）
1. **动画最终口径（唯一被用户认可的是 0.8.91 `966b186`）**：**进页＝0.8.91 逐字原样**——`slideInHorizontally(initialOffsetX = { it / NAV_SHIFT_DIVISOR }, tween(NAV_SLIDE_MS))`，**抽屉路线与设置子页都一样**（`ui/SuixingApp.kt:518-521`；常量 `NAV_SLIDE_MS = 200` `:168`、`NAV_SHIFT_DIVISOR = 12` `:171`）；**出（exit）＝`ExitTransition.None`**（`:525`，旧页当帧让位、不与新页叠加）。**只有「返回」按用户要求**：`popEnterTransition = EnterTransition.None`（`:528`）+ `popExitTransition = fadeOut(tween(NAV_FADE_MS, easing = FastOutSlowInEasing))`（`:529-530`，`NAV_FADE_MS = 200` `:177`）——用户原话口径是「蒙版由深到没有」。**抽屉**：点行与点会话行**都** `drawerState.snapTo(DrawerValue.Closed)` + **`UNDISPATCHED`**（`:239`/`:252-255`，注释明写「**0.8.91 原样**：瞬消，不是库那条 ~250ms 的滑出」）；返回来源页时 `snapTo(DrawerValue.Open)` 同条路径（`:285-287`、`:295`、`:305`）。
2. **返回第一帧空白**＝外壳那层**「跟 `route` 走」的不透明底**在 pop 第一帧**仍在**（于是露底色）⇒ **已删掉**（改为**每页自带不透明底**）。**⚠️「`opaque` + `delay`（等转场跑完才落透明）」那套已废弃，别再引入**（0.8.96 试过：`4316730`「返回时底部不再闪（底色等转场跑完才落透明）」）。
3. **顶栏 `PageTopBar` 改成结构性对齐**（不再用补偿数字）：标题放进 `Box(Modifier.weight(1f))` 并 `align(Alignment.CenterStart)` ⇒ **标题中心恒等于返回箭头 disc 中心**；副标题用 **`offset` 挂出布局**（`offset` 不参与测量 ⇒ Box 高 = 标题行高 ⇒ 行垂直居中即标题居中）；行高换算用 `LocalDensity`（吃 fontScale，不写死 9/18）。定义 `ui/components/Common.kt:533`，对齐块 `:592-599`，理由/历史注释 `:579-591`（**历史坑：曾用 `.offset(y = if (subtitle != null) 9.dp else 0.dp)`，还写反过符号 ⇒ 标题比不改更歪 18dp**）。**实测**（用户截图，密度 2.75）：箭头字形中心 **287.5px**、标题中心 **289px** ⇒ 差 **1.5px ≈ 0.5dp**（已对齐）。
4. **长按跳变最终修法（第五次之后的第六次、这次是真机日志定位）**：真机日志证明那次 **636px 滚动不是我们的任何路径**——是**框架的选择机制**把选中行拉进视野（`ui/chat/ChatScreen.kt:970-976` 注释）。**最终修法＝按住期间用 `NestedScrollConnection` 吃掉滚动增量**（`:979-991`，`if (pressed && !fingerDragged) available else Offset.Zero`；`pressed`/`fingerDragged` 是长按路径原有的两个瞬态量 `:620`/`:1019`/`:1046`），**拖动 / fling 放行**；`restoresPositionAfterLongPress`（`:7544-7549`）**保留作兜底**（判据 `!fingerDragged && listMoved && heldMs >= holdMs`）。
5. **插件系统 M1 + M2 + M3 已落地**：M1（0.8.91）＝`PluginStore` + `ManifestValidator`（13 reason code）+ 插件页 UI + Core 接线；M2（0.8.97）＝**读管道**（`plugins/PluginRuntime.kt`，`AgentEngine.kt:1058-1063` 把 `<id>.<tool>` 追加进工具表、schema 透传、重名跳过并记日志；**内置优先、绝不覆盖**）+ **容错**（`enabledPlugins()` 用 `runCatching`，失败/未初始化返回**空表**——此前会把整轮 run 带崩）。插件目录 `files/plugins/<id>/`（`plugins/PluginStore.kt:13` 注明「**真源是目录**，`.known_plugins.json` 只是列表缓存」；env 落 `plugins/<id>/env.json` 并 `chmod 600` `:207`）；文件页新增**插件目录根（只读）**。**M3（执行管道 + hooks）＝本会话实现（未提交/未打包）**：`PluginRuntime.kt` 补 `hooksFor()`/`preExecute()`/`execute()` + 纯函数 `render()`/`shape()`/`pickPath()`/`blockedByDeps()`；引擎接线点 B＝`agent/AgentEngine.kt:1241-1298`（内置 `registry.find` 找不到才 `PluginRuntime.resolve` 落到插件）。要点与两处真缺陷修复、单测 435→453、真机未验等**全部证据见 `dist/TODO-LEDGER.md`「插件系统」节末条**（不在这里重复）。

---

## 4. 踩过的坑 / 教训

1. **并发写同一文件**：多人/多子代理同时改同一文件 → 中间态编译红甚至互相覆盖（为「谁写 `ChatScreen.kt`」返工多次）。**规则：同一文件同时只有一个写者，串行派单**；每个写者改完必须自己跑 `:app:compileDebugKotlin` 并**如实报「跑没跑、是否 UP-TO-DATE」**。
2. **`--rerun-tasks` 会触发无关任务**：`--rerun-tasks` 触发了 `mergeDebugResources`（`values-hu/values-hy.xml`）失败 → 日常用普通 `:app:compileDebugKotlin`；**Gradle up-to-date 是内容哈希判定，改 mtime 不触发重编**。
2b. **同树并发跑 gradle 的处置顺序**：`gradle --stop` → 删 `app\build` → 重跑；看到「核心符号都 Unresolved」先怀疑 daemon 被并发搞崩，别去改代码（详见第 18 条）。
3. **写死坐标采样误判**：uiautomator 采样写死坐标会量到键盘体内（IME 窗口几何每次不同，`mGivenContentInsets` 与 touchable region 可能不一致）→ 曾据此误判「0.8.61-63 全坏」并错误回滚。**规则：按 IME touchable region 顶边相对取点，并校验 `mInputShown`**。
4. **`git push` 直连 `github.com:443` 已从「偶发」变成「常态失败」**（`Failed to connect to github.com port 443 after 21120 ms` / `Recv failure: Connection was reset`；早期一次重试即成功 `abf32c0..707da4b`，本轮**连试 4 次全挂**）。**稳定绕法**：`git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push origin main`（本机 `127.0.0.1:7890` 有代理；`api.github.com:443` 直连通；`gh` 走 API 用 `$env:HTTPS_PROXY`，不受影响）。**规则：先试直连，失败立刻上代理，别连试 4 次浪费时间；仍未成功就明确报告「没推上去」，别当成已同步。** 另：`adb devices` 空列表是**无线调试断了**，不是端口变了（曾错误归因，被 supervisor 纠正）。
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
17. **`git push` 直连 `github.com:443` 会失败**（`Failed to connect to github.com port 443 after 21120 ms` / `Recv failure: Connection was reset`，连试 **4 次全挂**），但 **`api.github.com:443` 通**、本机 `127.0.0.1:7890` 有代理。可用命令：
    `git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push origin main`
    （`gh` 走 API，用 `$env:HTTPS_PROXY`）。**规则：先重试直连，仍失败就明确报告「没推上去」，别当成已同步**；`gh release create` 不受影响。
17b. **⚠️ 「tag 只到 v0.8.73」是假象，害人（lead 差点被误导）**：tag **一直在远端**，本地看不到只是因为**从没成功 `git fetch --tags`**（fetch 也走直连 ⇒ 常态失败）。**查 tag / 查远端分支前先走代理**：
    `git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 fetch --tags`
    （或 `git -c … ls-remote --tags origin` 免 fetch 直接看）。走代理 fetch 后本地 tag 从 13 个变成 19 个、一直到 `v0.8.79`，与 `gh release list` 完全对上。**教训：凡是「本地缺失 vs 远端存在」的差异，先怀疑是没 fetch 成功，而不是远端真的没有。**
18. **同一棵工作树上并发跑 gradle 会互删 `app/build/tmp/kotlin-classes`**：Kotlin daemon 崩 + 吐出一大串**假的** `Unresolved reference`——连 `AppSettings`/`Roles` 这种核心符号都会「找不到」（照报错去改代码只会越改越乱），或死在 `shrunk-classpath-snapshot.bin` 文件锁 / `Unable to delete directory 'app\build'` 上。**处置**：`gradle --stop` → 删 `app\build` → 重跑。**规则：一个仓库同时只有一个写者跑 gradle**（本轮流程已改为「写者只报 diff、lead 统一串行跑编译与测试」）。
19. **派活前先让写者核实现状，别把自己的猜测当事实写进任务描述**：本轮 lead 把「日志无上限 / 裸 `appendText`」当既有缺陷写进任务，两个写者都核出**原实现早有上限、也在 worker 线程上**（随后更正）。教训：任务描述里的「现状」要么附 `文件:行号` 证据，要么明确写「请先核实」。
20. **中途态会连累别人的读数**：本轮至少 4 次编译红都是「调用点已改、定义未落」的窗口（`PAGE_TURNS`、`TODO_TOOL`、`shellChannel`、`kiloText`/`kiloToTokens`）。**判断方法**：报错集中在**同一个符号**的「未解析」时，先问写者是不是在飞，别急着改代码。
21. **浮点/整数行为不能靠推理断言**：写者实测发现 `(k * 1000).toInt()` 对 `1.001` 会截断成 `1000`（而他原以为出问题的是 `131.072`，实测那个正常）⇒ 涉及取整/截断的改动一律写往返测试。
22. **`ByteArray.takeLast(n)` 返回的是 `List<Byte>`**（不是 `ByteArray`）⇒ 链式 `.toString(Charset)` 会落到无参 `toString()`，报错长得像「Too many arguments」；先 `toByteArray()`。
23. **发布状态以 `gh release list` 为准，别只信账本**：账本 `dist/TODO-LEDGER.md` 里「v0.8.74 未发，等用户放行」**是过时记录**——当时确实没发，后来用户说「release 照样发」就发了，账本没回填；`gh release list` 显示 0.8.73–0.8.80 全在（v0.8.74 创建于 2026-10-10T16:04:49Z）。**同理：本地 `git tag` 缺版本 ≠ 远端没有**（见第 17b 条），两者都要走代理查远端才算数。
24. **`@Composable` 注解叠了两份**：插入新函数时把上一个函数的注解留下 ⇒ 报 `annotation is not repeatable` + 两条级联假错。
25. **`delay` 只收 `Long` / `Duration`**：常量写成 `Int` 会报 "None of the following candidates is applicable"。
26. **⚠️ 出包纪律（真实事故，别重犯）**：0.8.83 第一次出包时全量测试红了一条 ⇒ `assembleRelease` **没跑**，而把**旧 APK 当新包装机 + 发 Release**（直到装机读数显示 0.8.82 才暴露）。**现在每次装机前必须 `aapt` 核对 APK 的 `versionName`/`versionCode`**，发错了用 `gh release upload --clobber` 覆盖附件。**并且：只在用户说「打包」时才出包**（用户原话：「改动一小点就打包安装了」要停）。
27. **「中途态连累编译」在本轮又出现 6 次**：调用点已改、定义/import 未落的窗口（`TODO_TOOL`、`shellChannel`、`kiloText`、`TouchFx` import、`NAV_RETURN_FLAG_MS` 类型、`@Composable`）。判断法同第 20 条：**同一符号集中「未解析」先问写者是不是在飞**。
28. **同尺寸 APK 不代表同一版本**：`dist/suixing-0.8.81.apk` ~ `-0.8.86.apk` 六个文件**字节数完全相同（13,268,706 B）**，靠文件大小/时间戳分不出装的是哪一版 ⇒ 只能 `aapt dump badging` 读包内版本（与第 26 条同一套动作）。**0.8.91~0.8.97 又一轮**：七个包全是 13,399,778 B。
29. **⚠️ 「观感类」改动必须一次只改一处，并等用户确认**（今天最贵的教训）：转场动画在 0.8.92~0.8.97 之间**连改 6 次全被否**（淡入 / 位移 / 抽屉 `snapTo`↔`close()` / `opaque` 底色 …），最后**回退到用户唯一认可的 0.8.91 原样**（0.8.94 `ac5567d` 就是「抽屉路线转场回退到 0.8.91」）。⇒ 动画/间距/颜色这类「只能眼睛验收」的东西，**一次只动一个变量、改完停下等确认**，不要连发多版。
30. **⚠️ 不再用 adb 操作用户的手机**（截图 / 点击 / 录屏**都算**）：需要证据就让用户发**截图或录屏**，本地用 `D:\dsh\fixbug\suixing\video\node_modules\@ffmpeg-installer\win32-x64\ffmpeg.exe` **逐帧分析**。
31. **⚠️ 流程纪律（lead 自述的三条错）**：①「**频繁出包**」被用户明确批评 ⇒ **只在用户说「打包」时才打包**；②多次把「**派单**」说成「**已改**」；③把**推测当结论**（如把「框架选择机制滚动」当成我们的路径，直到真机日志才定位）。⇒ 汇报只报**可核验状态**（改没改、跑没跑、谁在飞），推测一律标注为推测。
32. **`notes` 不是每版都有**：0.8.91~0.8.97 **没有** `dist/notes/release-notes-0.8.9x.md`（只有 0.8.90 及以前）⇒ 事后只能靠 commit 正文；**要留档就当场写 notes**（正文里的关键数字——阈值、px、dp、测试条数——是唯一线索）。

---

## 5. ★ 已实测否掉、勿再加回来
> **2026-10-12 逐条 grep 核实（未跑 gradle）**：下列各条**均未被重新引入**。逐条证据见 `dist/TODO-LEDGER.md` 的「★ 2026-10-12 逐条核实表」第 9 行。**唯一需解释的一处**：`ui/chat/ChatScreen.kt:4999` 仍有 `.height(48.dp)`，但那是**按钮行的触控高度**（`:4992-5003` 注释：行内 padding 是唯一能移动「+」到胶囊边缘距离的杠杆），**不是**被否掉的「给文本/输入框写死高度」（那条的教训记在 `ui/assistant/AssistantEditor.kt:456`）。

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
| 24 | 返回/进页加淡入（`fadeIn`） | 新页从 alpha 0 淡入的那 240ms 旧页在后面淡出 ⇒ **「上一页残影」** ✗（0.8.84 改：新页第一帧就不透明、只让旧页淡出+滑出） |
| 25 | 抽屉点行用库的 `close()` 滑出（~250ms） | 用户能看见它滑走＝残影 ✗（改 `snapTo(Closed)` + `UNDISPATCHED`，**0.8.91 原样**） |
| 26 | 抽屉路线改 `snapTo`↔`close()` 反复调 + 转场分流（子页从左滑回 / 抽屉路线淡入 110ms） | 0.8.92~0.8.97 **连改 6 次全被否** ✗ ⇒ **回退到 0.8.91**（0.8.94 `ac5567d`），最终口径见 §3 第 1 条 |
| 27 | 「`opaque` 不透明底 + `delay`（等转场跑完才落透明）」修返回空白 | 已废弃 ✗（0.8.96 `4316730` 试过；**正解＝删掉外壳那层跟 `route` 走的不透明底，每页自带不透明底**） |
| 28 | 顶栏用 `.offset(y = if (subtitle != null) 9.dp else 0.dp)` 补偿对齐 | 补偿数字，**还写反过一次符号**（标题比不改更歪 18dp）✗ ⇒ 0.8.97 改**结构性对齐**（Box + `align(CenterStart)`，中心恒等） |
| 29 | 只靠「事后把位置还原」修长按跳变 | 框架选择机制会把选中行拉进视野（真机日志实测 636px、**不是我们的路径**）⇒ 先滚走再滚回来仍可见 ✗ ⇒ 正解＝按住期间 `NestedScrollConnection` **吃掉滚动增量**（还原逻辑降为兜底） |
| 30 | 插件读管道不做容错（`enabledPlugins` 直接读 `Core.plugins`） | `Core.plugins` 未初始化 / 读盘失败会**把整轮 run 带崩**（7 条测试挂 `UninitializedPropertyAccessException`）✗ ⇒ 失败返回**空表** |

---

## 6. 当前状态与待办

### 状态
- **HEAD `39d06bd`（0.8.97 / versionCode 108，2026-10-12 12:54）**；远端 `refs/heads/main` 同指 `39d06bd`（**本轮实跑** `git -c http.proxy=… ls-remote origin main` → `39d06bd862e3fa62a933e724ddcdc3127ddde10e`）。今日提交链：`39d06bd`(97) ← `4316730`(96) ← `5ca8804`(95) ← `ac5567d`(94) ← `434308c`(93) ← `23901ad`(92) ← `966b186`(91) ← `f44b3e7`(90) ← `d0171d0`(89) ← `33a424d`(88) ← `b29bb9b`(87) ← `6854b24`(86) ← `cf6459f`(85)。
- **工作树**：`git status --short` + `git diff --stat` 实测（2026-10-12 核实）——` M` = 本记忆文件 + **9 个代码文件**：`ui/SuixingApp.kt`(+40)、`ui/assistant/AssistantEditor.kt`(+46)、`ui/assistant/AvatarCropper.kt`(+54)、`ui/capability/CapabilityScreen.kt`(±4)、`ui/chat/ChatScreen.kt`(+55)、`ui/extension/ExtensionScreen.kt`(±3)、`ui/files/FilesScreen.kt`(±3)、`ui/settings/SettingsScreen.kt`(+16)、`ui/settings/ToolsScreen.kt`(±8)；未跟踪 = `.playwright-mcp/`、`gpt-contact-16.png`、`gpt-transitions.png`、`video/`。**这一批含**：动画回 0.8.91 口径 + 返回淡出、返回不空白、顶栏结构性对齐、**5 处副标题全删**、状态行 18/18、长按冻结、裁剪页/助手页沉浸（`DialogEdgeToEdge`）、**插话清空单一写入者**（`ChatViewModel.kt:563-565`）。⚠️ 与 §1「当前版本号＝0.8.97」并存：`app/build.gradle.kts` 仍是 108/0.8.97。
- **装机版本**：**仍未核实**（需 `dumpsys package`；本机今天没装机）。今天出了 0.8.90~0.8.97 八个包；**装机/读数一律以 `dumpsys package` + `aapt` 为准**（§4 第 26 条）。✅ **设备无线调试已恢复在线**（2026-10-12 实测 `adb devices` = `adb-d2e1026e-PZkPcd._adb-tls-connect._tcp  device`）。
- **Release / tag 已发到 `v0.8.97`**。**证据（本轮实跑，非推断）**：`git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 ls-remote --tags origin` → 逐行返回 `refs/tags/v0.8.86 → 6854b24`、`87 → b29bb9b`、`88 → 33a424d`、`89 → d0171d0`、`90 → f44b3e7`、`91 → 966b186`、`92 → 23901ad`、`93 → 434308c`、`94 → ac5567d`、`95 → 5ca8804`、`96 → 4316730`、`97 → 39d06bd`；同一条命令查 `origin main` → `39d06bd`（= 本地 HEAD）。⚠️ **`gh release list` 本轮没跑**（所以「Release 页已发布」是 lead 口径 + tag 存在的推定，未逐页核对）。**本地 `git tag` 只 fetch 到 v0.8.80（20 个），86~97 未 fetch 到本地**。⚠️ 查 tag 必须走代理 `127.0.0.1:7890`（§4 第 17b 条）；发布状态以 `gh release list` 为准（§4 第 23 条）。
- 真机序列号：`adb-d2e1026e-PZkPcd._adb-tls-connect._tcp`（`adb devices` 空列表＝无线调试断了，不是端口变了）。
- **本地工具**：录屏逐帧分析用 `D:\dsh\fixbug\suixing\video\node_modules\@ffmpeg-installer\win32-x64\ffmpeg.exe`（§1）；**不再用 adb 操作用户手机**（§4 第 30 条）。
- **诊断日志现状**（排查用）：`fx-trace.log`（256/128KB 有界，daemon 低优先级线程；**0.8.83 起每行带 `light=active|off`/`inFlight=`/`sinceAction=`/`activeWindow=5000ms`**，0.8.86 边缘光 v2 再加 `episode=`）、`console-trace.log`（有界，带 `resident=` / `taskRemoved`）、`voice-trace.log`（32/16KB）、`scroll-diag.log`（走同一个有界 worker；0.8.85 用它验收「返回不再像重载」：`cold-pin:begin` 每会话只应有一行；**0.8.97 长按冻结落地后是否还需要它，待真机确认再删**）。
- **0.8.81 起云备份范围已收窄**：`data_extraction_rules.xml` 的 `<cloud-backup>` 14 条 exclude（会话/记忆/画像/todo/提醒/MCP/任务日志/头像照片/四个诊断日志都不再进云备份）；**换机恢复仍带走设置与会话**；API ≤30 老系统保持不排除会话（文件内有注释）。

### 第八批（下一轮）—— 待办 / 待用户拍板
1. **插件 M3（执行管道 + hooks）＝本会话已实现（未提交/未打包）**：历史成因＝M2 只做读管道，模型「看得见」插件工具但「调用会失败」，是插件系统唯一剩下的主缺口。现状＝`PluginRuntime.kt` 补执行管道 + `AgentEngine.kt:1241-1298` 接线，单测 **435→453**、`:app:compileDebugKotlin` 绿。全部要点与两处真缺陷修复见 `dist/TODO-LEDGER.md`「插件系统」节末条 + §3 第 5 条。⚠️ **遗留风险：真机未验**——http/shell 实发与 `env.json` 读取要 `Core` + 网络，本机 JVM 单测覆盖不到；出包后须装机验一次「装一个 http 插件→模型调用→拿到真响应」。
2. **「任何页都不要副标题」——5 处调用点**：**✔ 已删（在工作树，未提交；2026-10-12 核实）**。证据＝HEAD 与工作树的 `subtitle = ` 计数对比：`ui/capability/CapabilityScreen.kt` 2→1、`ui/extension/ExtensionScreen.kt` 1→**0**、`ui/files/FilesScreen.kt` 2→1、`ui/settings/ToolsScreen.kt` 2→1、`ui/settings/SettingsScreen.kt` 20→19（每个文件正好少 1 行）。⚠️ `SettingsScreen` 那处「有未保存的改动」按用户要求要**搬进内容区**，需真机确认它现在长什么样（`subtitle` 已不在 `PageTopBar` 上）。
3. **打包/装机待用户放行**（用户明确：**只在他说「打包」时才打包**，§4 第 31 条）；出包前按 §1 出包链 + **`aapt` 核对版本**（§4 第 26 条）。✅ **设备已在线**（2026-10-12 实测 `adb devices` 有设备）⇒ 放行即可装机。
4. **待真机确认的观感项**（0.8.91 口径）：进页 `slideIn(+1/12, 200ms)` / 返回 `fadeOut(200ms)` / 抽屉 `snapTo + UNDISPATCHED`；顶栏标题与返回箭头中心对齐（实测差 0.5dp）；长按按住期间不滚动；插件页空态不残留。
5. **`notes` 缺失待补**（0.8.91~0.8.97 七版无 notes/commit-msg，§4 第 32 条）——若还要留档，从 commit 正文回填。

### 第三批（0.8.76）—— 已全部落地并发版（存档）
| 项 | 结果 / 落点 |
|---|---|
| 圆环 9sp + 圆环弹窗新增三行「未缓存输入 / 缓存读取 / 输出」（`%,d tok`） | ✅ 已随 0.8.76 发版：`ui/chat/ChatScreen.kt:5618`（`RingDetail("缓存命中", …)`）、`:5650-5651`（`未缓存输入` / `缓存读取`）、`:2126/:2128/:2133`（本轮三行）；口径注释 `:410`、`:5609` |
| 无语言 markdown 代码块标题条 | ✅ `ui/components/MarkdownText.kt:845` `text = block.language.ifBlank { "Markdown" }` |
| 启动文案「正在打开…」→「加载会话中…」 | ✅ 已改（`LoadingSkeleton()` 内，原 `ui/chat/ChatScreen.kt:1393`） |
| 长按复制跳变 / 模型弹窗组头裁字 / 语音两弹窗重叠 / 文件页 / 权限档次 / 会话懒加载 / 回车键 / 顶栏同色 / 抽屉 | ✅ 全部随 0.8.76 落地，根因见 §3「0.8.76 修复的根因」 |

### 第四批（0.8.77/0.8.78）—— 已发版（存档）
- **① skill 系统**：0.8.77 落地（技能页分类/开关/网络下载/模型可创建），0.8.78 修信任边界（模型新建默认开、导入默认关），0.8.79 复核代码确认。原始 recon 见 §3「0.8.78/0.8.79 修复的根因」末两条。
- **② 悬浮窗图标偏大 + 左右弹窗自适应**：0.8.78 已落（悬浮球 44dp + 专用 vector + 去阴影；边缘光收紧＝只绑 run + 前台门 + 重建补点亮）。
- 其余随 0.8.77/0.8.78 一并发出：滚动追底根因修复、输入法不再被弹窗收起、导航条上拉解锁、todo 清单、助手头像自定义、语音换成自己的、工具页截断修复 + 插件 tab、定时任务页、风格统一第二轮、转场统一。

### 第五批（0.8.79~0.8.81 轮）—— 存档（未完成项仍开放）
**待用户拍板（5 条）**
1. **skill 默认开启的残留风险要不要加护栏**：选项＝①只在「本轮无外部内容进入上下文」时默认开；②保存后在**对话里**弹一次确认（不用去技能页找）。背景：外部内容诱使模型调 `skill_save` 时该技能会**当场生效**。
2. **轮数无上限、没有「反复调同一个失败工具」的熔断器**（属模型行为）——是否加。
3. **`collectAsStateWithLifecycle` 全仓统一**（现未统一）。
4. **`ui/capability/CapabilityScreen.kt:343` 的 10dp** 与几处 `bottom = 100dp` 是否统一。
5. **`ui/assistant/AssistantSheet.kt:228` 的 13/6/12/13 内边距**是否统一到 16。
**未完成**
- `AboutScreen`「打不开链接给提示」**0.8.80 已做**（链接打不开时给原因「设备上没有可用的浏览器」），原先记的「没核实到」可视为已闭环——待真机确认一次。
- `scroll-diag.log` 诊断链是否删除——**等用户复现长按跳变后确认**（见 V16；0.8.86 工作树仍在用它）。

### 第六批（0.8.82~0.8.86 轮）—— 存档（**当前状态见上「状态」与「第八批」；下面 4 条是 2026-10-11 当时的快照，勿当现状**）
- 〔当时快照〕**HEAD `cf6459f`（0.8.85 / vc96，2026-10-11 15:32）**；其前 `a65a74d`（0.8.84）、`ababb8b`+`c81a81c`（0.8.83 + 补丁）、`ee806fd`（0.8.82）、`fb6520a`（0.8.81）、`2ad3513`（0.8.80）。
- 〔当时快照〕**工作树＝`0.8.86 / vc97`，未打包未装机**（本地已构建过一版 `dist/suixing-0.8.86.apk`，未装）。**⚠️ 用户明确要求：只在他说「打包」时才出包**（这条**长期有效**）。
- 〔当时快照〕**设备无线调试掉线**：当时 `adb devices` = `List of devices attached`（空）⇒ 装机/读数做不了；恢复后按「`adb mdns services` 重发现 → `aapt` 核对 APK 版本 → `install -r` → `dumpsys package` 核对」走。
- 〔当时快照〕**Release / tag 当时只到 v0.8.85**、0.8.86 未发——**现已到 v0.8.97**（§1）。查 tag 必须走代理（§4 第 17b 条），发布状态以 `gh release list` 为准（§4 第 23 条）。
- 未跟踪只有 `.playwright-mcp/`、`video/`；0.8.86 改动的 11 个文件见 §2 的 0.8.86 行。

### 第七批（0.8.86 轮）—— 存档（打包项已被第八批取代）
- 排队行「修改不带回图片」/「图文混合无『含图片』标记」两个小缺口**仍开放**（未在 0.8.87~0.8.97 的提交标题里出现）；边缘光两处续期（立即插话 / 跨 run 重放）与排队行缩略图（现只有「（图片）」占位，`ui/chat/ChatScreen.kt:6688` 那处 label）同样仍开放。
- ~~打包 + 装机（0.8.86/vc97）~~ → **已被第八批第 3 条取代**（现在要装的是 0.8.97/vc108）。

### 待用户真机验证（0.8.71-0.8.85 装进去的；**0.8.86 及以后的验证项见「第八批」第 4 条**；原文见 `dist/TODO-LEDGER.md:9-25`）
- V1 边缘光覆盖状态栏（从屏幕物理顶边 y=0 起、向内渐隐约 50px、底部手势条贴底、16 段渐隐光晕而非硬描边）
- V2 中断留存（**0.8.75 才修对**：只流思考/只发工具调用的轮次现在也产 draft、也留「（中断）」卡；核对 `files/chats/<id>.json` 里该 runId 的 assistant `reasoning` 非 null、TOOL 消息成对）
- V3 思考块流式跟随（关掉「外观 → 思考块跟随最新」后应全程不自动滚）
- V4 导航条长按预览气泡（220ms → 完整可读一行，不再夹成 24dp 一个字）
- V5 按钮统一观感（顶部两盘同直径同图标大小；**「展开全部 N 行」在 0.8.78 已随 `cap = 200` 一起删除**，不再出现；触摸响应范围 > 视觉圆盘）
- V6 页面转场（**0.8.78 起进/出/返回四条一套规格：淡入淡出 + 8% 位移、零例外**；抽屉仍 300dp 左滑；设置 6 子页 0.8.75 起走真路由）
- V7 顶栏（蒙版 26%/10%/0 是否够；`PageTopBar` 分割线 0.8.75 已删，14 页统一）
- V8 助手独立整页（编辑态旋转不丢草稿；点另一张卡勾移动且页面不退）
- V9 用户画像自动总结（聊满 8 轮 → 状态变「最近更新 HH:mm」，`profile.md` 有摘要而 `memory.json` 无画像文字）
- V10 压缩阈值可配（改小后下一次 run 更早压缩）
- V11 权限档次自选（**代码与单测已核**：唯一解析点 `CapabilityState.shellTier(preferred)` = `capability/CapabilityHub.kt:97`（`fixed` 映射 `:99-104`、向下退档 `:107-115`），单测 `app/src/test/java/com/suixing/agent/capability/ShellTierTest.kt`（6 个 `@Test`，覆盖「无偏好取最高档 / 未知值当自动 / 固定档只走该档 / 标准档绝不升 root / 固定档不可用只向下退 / 未探测 root 不算 ready」，含 `rootProbed = false` 断言）；**真机尚未验证**——发布证据是 commit/notes 的 272/272 + assembleRelease + 装机读数，我未独立复核。真机重点：固定 Shizuku 时不会偷偷升 root、工具名/提示不再把 LOCAL 说成 root）
- V12 圆环与用量（0.8.75 起中心数字 8sp、弹窗标签去掉「会话」前缀；0.8.76 加三行 `%,d tok`；**0.8.78 删掉「本轮输入 / 本轮输出」两行并按语义重排**：上下文占用 → 缓存命中 → 累计用量·N 轮 → 未缓存输入 → 缓存读取 → 输出 → 输出速度 → 总用时，缺数据不画；**0.8.79 起老会话/不报 usage 的网关也有「约」的估算值**，空会话文案「新会话 · 还没有用量」）
- V13 工具页（行高 56→**7dp 内边距**、搜索框 56→44dp、描述单行截断；Skill/MCP tab 能导入/新建；数据页里**没有** MCP/技能入口）
- V14 过程卡首行（「思考过程 8dp 1658 tokens 7dp 32.2s 8dp 预览… ⌄」，无 16dp 空档、chevron 贴右缘；`run_shell` 完整显示不腰斩）
- V15 键盘/圆角（圆角穿透 ✓、抬起逐帧跟随 ✓、两态边距 48px ✓、收起回弹已修——**回弹请再确认一次**）
- V16 **长按选中跳变**：0.8.74 改「按下即释放追底」，0.8.75 收窄导航条手势层到 24dp 轨道，0.8.76 再补闸（pin 内每次重判 `following && autoFollow`），**0.8.86 工作树才找到真凶**（按下时被 `snapshotFlow` 的 else 分支还原 `following` ⇒ 下一帧 pin 跳到底；修法＝`pressed` 瞬态标志 + `following` 进 pin key + 阈值读平台 `longPressTimeoutMillis`，见 §3「0.8.82~0.8.86」第 4 条）⇒ **五版改动叠加、等真机复核**；`scrollDiag` 临时诊断（`filesDir/scroll-diag.log`）**复现后按日志定论、用完删**。
- V17（新）**会话懒加载**：进会话只画 12 轮、滑到顶出现「加载更早的 12 轮 · 上面还有 N 轮」；导航条滑到最顶自动向上解锁、抬手归零、切走再回来重置为 12 轮；轮次编号为绝对轮次。
- V18（新）**文件页**：顶栏文件夹按钮可切「应用目录 ↔ /sdcard」（未授权引导开「所有文件访问权限」，回来自动落 /sdcard）。
- V19（新）**语音交接**：`ERROR_CLIENT` 拉系统识别时我方卡片应立即收起（不再两层同屏）；识别**成功**后也要正确关闭自己；失败态「重试」不再被卡片圆角裁在右缘。
- V20（新）**模型弹窗组头**：`qwen` 的 `q` 降部不再被裁（组头左右 4dp）。
- V21（新）**全站风格统一**：卡片内边距 16dp、页面底部 72dp、分割线 `outlineVariant`、字号不再被写死（「外观→字号」缩放应真的生效）。
- V22（0.8.78/79）**悬浮窗常驻**：抽屉开关打开 → 切到别的 app、**什么都不跑** ⇒ 球应一直在；跑完一次任务后也不该消失；关掉开关立刻消失（**0.8.78 的解释是错的，真因见 §3 第一条**）。诊断 `console-trace.log` 带 `resident=`。
- V23（0.8.79）**抽屉**：从抽屉进设置 → 返回 ⇒ 侧边栏是**开着的**（且不重放滑入动画）。
- V24（0.8.79）**思考块**：长思考内容**一直显示全部**（不再出现「展开全部 xxx 行」）；点标题可收起；设置里开关语义是「历史思考块展开」。
- V25（0.8.79）**有序列表**：让模型列 10 项以上 ⇒ 序号 1、2、3…（不再全是 1）。
- V26（0.8.78/79）**skill**：让模型记一条技能 ⇒ 技能页应显示**已开启**；从链接/文件导入的仍**关闭**。
- V27（0.8.79 加、**0.8.83 又撤掉**）**权限 Root 开关**：~~权限页新增「允许使用 Root」~~ ⇒ **0.8.83 已撤销该开关**（用户说档次选择器就够了、root 自己探测；`enableRoot` 字段删除，恢复照常自动探测 root）；**仍保留**的规则＝显式点名 `mode="root"/"shizuku"` 只是请求、**不能超过你在权限页固定的档次**（固定标准档时点名 shizuku 也落到 local，输出前缀写实际通道）。
- V28（0.8.79）**定时任务**：改一次手机时区（或校时）后「每天 9:00」的提醒仍在 9:00 响；错过的一次性提醒显示「已过期」而**不补响**。
- V29（0.8.78）**语音**：设置里语音输入开关默认关（关着麦克风图标完全不显示）；开启后自绘细胶囊（5 根音量竖条 + 提示 + ×），未配转写模型时不录音直接提示；不再弹系统识别面板。
- V30（0.8.78）**todo 清单**：模型调 `todo_write` 时输入框上方出现同款条，完成条目消失且计数 +1，超 3 项显示「还有 N 项」，跑完收起；任务开始清空。
- V31（0.8.78）**助手头像**：编辑页上传图片 → 消息卡/流式气泡/空状态/助手列表全跟着变，可恢复默认，删助手清头像文件。
- V32（0.8.78）**工具页插件 tab**：`工具｜技能｜MCP｜插件`，插件页只有说明 + 空态（无假插件）；抽屉「任务」应指向**定时任务**真列表。
- V33（0.8.82）**侧边栏跟手**：从屏幕左缘往右拖 ⇒ 面板跟手指走、遮罩同步变深、拖到一半松手回弹（自绘那版做不到）；点遮罩/返回键关闭且不退出应用。
- V34（0.8.82/83/84/85）**返回观感（四条路径）**：抽屉 → 设置 → 点顶栏返回 / 按返回键 / 手势返回 / 预测性返回 ⇒ **三条零运动**（页面不滑不淡、抽屉已在）；其余返回（设置子页回列表、设置→工具/权限/关于）**照常演动画**；**任意进页不该再有上一页残影**，权限/工具/文件/扩展页进页不透底。
- V35（0.8.85）**返回不再像「重新加载」**：聊天里上滑到历史中段 → 进设置 → 返回 ⇒ **位置不动、不跳底**；在底部 → 返回仍在底部；点过「加载更早」→ 返回页数保持；**全程不该看到骨架屏**；`scroll-diag.log` 里 `cold-pin:begin` 每个会话只应有一行。
- V36（0.8.83）**边缘光判定**：让随行跑长任务，模型思考/写答案时**不该亮**；它真的点/滑手机时亮、**5 秒内没动作就灭**；你在随行里一律不亮。判读口诀：`drivingAlive=true` 而 `light=off` 现在是正常的。
- V37（0.8.83）**失败自动重试**：断网跑一次 ⇒ 应看到「第 1 次重试（5s 后）…」；**点停止 ⇒ 立刻停**（不等满）；已开始收到正文/思考后不再重试。
- V38（0.8.83）**输入框那排**：左下「+」明显更靠近左边缘（21→11dp）、两个图标之间比上一版紧凑（审批→模型胶囊 18→16dp）；点起来仍好按（触控 44×48dp）；工具轮数上限拖到最左显示「**无限**」。
- V39（0.8.83）**失败信息**：断网/证书/域名/401 时对话页失败行是**人话**（走 `describeError`），不再显示异常类名或 `error.message` 原文。
- V40（0.8.84/85 回归项）**一次回答不该裂成两张卡**（`run.isNotEmpty()` 已去；**另一条「无 runId 消息压过真实 run ⇒ 正文被整条过滤」当时已定位待修**，见 §3 第 8 条与 §6 第七批）。

### 排队（写域空闲即派）
- ~~**B2 权限档次接进执行链**~~ → **代码已落地并已提交（0.8.76，`2b3c2a9`）**：唯一解析点 `CapabilityState.shellTier(preferred)`（`capability/CapabilityHub.kt:97`，逐字读过：`fixed` 映射 `:99-104` + 「只向下退、绝不升档」`:107-115`），单测 `capability/ShellTierTest.kt`（6 例）；**发版与真机行为待用户复核**（本记忆不替它宣布已验证）。0.8.79 又补了「允许使用 Root」真开关与 `minOf(requested, resolved)` 不越级。
- **B3 全局工具开关**：工具页现在是只读总览；现有禁用链只有按助手的 `Assistant.disabledTools`（`data/Assistant.kt:27`，生效点 `ui/chat/ChatViewModel.kt:1037`，文档旧锚写 `:1031`）→ 需新增设置字段 + 在工具装配处叠加，**等用户拍板**。
- **B4 会话记录管理界面**：「会话文件」指向的是工作区文件浏览器；若要会话列表/导出属**新功能**。
- **B5 归档/清理**：`dist/` 下的诊断日志（`fx-trace.log`、`top-trace.log`、`ime-clamp.log`、`verify*.log`、以及本文件 §3/§6 提到的 `scroll-diag.log` 在真机上）与旧 apk 可清；另有合并记忆用的临时素材 `dist/_memlog_fulllog.txt`（105KB，可删）。
- **B6 技能系统（第四批主线）**：见上「第四批 ① skill 系统」的 recon 与四条待拍板项——**这是 0.8.77 的主体工作**。
- **B7 悬浮窗图标偏大 + 左右弹窗自适应**：见上「第四批 ②」，recon 中。

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
11. **「灯下找钥匙」式排查要有日志证据**：`logcat` 不可用、`adb` 不可用时，用**应用内自记账**（`scrollDiag` → `filesDir/scroll-diag.log`、`ime-trace.log`、`fx-trace.log`）——调用点一律带「量完删」注释，复现后按日志定论再删，不要靠读码猜时序（本轮「在飞的 pin 没重判闸」正是这么定位的）。
12. **下载/外部来源的内容进 prompt 前必须先降权**：技能 description 直接进 system prompt 时，默认 `enabled = true` 等于**未经确认就当指令执行**——模型自己新建的可以默认开（用户意图明确），**外部导入/下载的必须默认关**，并在页面写明这条规则。
13. **别把「日志无上限」「裸 appendText」这类猜测写进任务描述**：先让写者核实现状（本轮两次被写者纠正：原实现早有上限、也在 worker 线程上）。任务描述里的「现状」要么附 `文件:行号`，要么写「请先核实」。
14. **同一符号集中「未解析」时先问是不是中途态**：调用点已改、定义未落会产生成串假错误（0.8.76 轮 4 次、0.8.82~86 轮 6 次）；**浮点/取整行为必须写往返测试**，不能靠推理断言（`(1.001 * 1000).toInt() == 1000`）。
15. **出包三件套（每次装机都做）**：① `aapt dump badging` 核对 APK 的 `versionName`/`versionCode`（**同尺寸 APK 分不出是哪一版**：0.8.81~86 六个包字节数完全相同）；② 确认 `assembleRelease` 真的跑过（测试红过 ⇒ 构建可能没跑，会把旧包当新包发——0.8.83 真实事故）；③ 只在用户说「打包」时才出包。发错了用 `gh release upload --clobber` 覆盖。
16. **改「看得见的间距」别改「看不见的容器」**：0.8.83 把 M3 图标按钮的圆盘 40→32dp，但 M3 图标按钮**默认不画容器** ⇒ 用户只看到字形、等于没改，反而因为触控盒 40→48dp 让字形间距从 23dp 变 32dp（更松）；0.8.85 改成调真实间距（左缘 21→11dp、审批→胶囊 18→16dp、触控 44×48dp）才生效。**结论：Compose 里「尺寸」和「视觉间距」常常不是同一个东西，先量再改。**
17. **观感类改动＝一次一个变量，改完停下等确认**：转场在 0.8.92~0.8.97 连改 6 次全被否，最后回退 0.8.91 原样（§4 第 29 条）。**「用户认可过的那一版」要原样留档（commit 号 + 参数逐字）**，它就是回退基线。
18. **对齐用结构，不用补偿数字**：`Box(weight(1f)) + align(CenterStart)` 让「标题中心 = 行中心 = 箭头中心」在**布局层面**恒成立；补偿式 `.offset(y = 9.dp)` 既会随字号档失效，还会写反符号（实测歪 18dp）。**能用约束表达的，不要用数字补。**
19. **「事后补救」不如「事前拦住」**：长按跳变先做的是「滚走后再还原」（可见跳变）；正解是按住期间**吃掉滚动增量**（`NestedScrollConnection`，拖动/fling 放行）。**面对「不该发生的事」，先想怎么不让它发生，再想怎么撤销它。**
20. **留档要当场写**：0.8.91~0.8.97 七版没有 notes/commit-msg ⇒ 事后只能靠 commit 正文（§4 第 32 条）。**关键数字（阈值/px/dp/测试条数）写进正文或 notes，别只留在对话里。**

## 交接（2026-10-12 17:40 原写；**2026-10-12 本会话续：插件 M3 已实现**，为换新会话留存；新会话读本节即可接手）
- **当前**：HEAD = Release = 装机 = **0.8.99（vc110, 26ce0e4）**；单测 **453/453**（本会话 M3 后实测；上一基线 435/435）；设备在线（db-d2e1026e-PZkPcd…）；工作树干净（未跟踪：.playwright-mcp/、gpt-*.png、ideo/、dist/vid/＝分析产物可删）。
- **0.8.99 唯一改动**：AssistantEditor.kt 的 DialogEdgeToEdge() 加 setLayout(MATCH_PARENT, MATCH_PARENT)（Dialog 窗口默认 wrap-content ⇒ 状态栏露窗口外内容＝用户截图的白条/露底）。**待真机验证**；若仍露 ⇒ 改走「编辑/裁剪页换成 NavHost 目的地」结构路，**不再碰 Dialog inset**。
- **待办（优先级）**：① ✔ **插件 M3 执行管道【本会话已实现，未提交/未打包；单测 435→453；证据见 `dist/TODO-LEDGER.md`「插件系统」节末条 + §3 第 5 条 + 第八批①】**（plugins/PluginRuntime.kt:100-103 的 xecute()/hooksFor() 是空实现；引擎分发处内置工具找不到名字时落到插件；模板求值 {{param}}/{{env.*}}/{{plugin_dir}}、esponse.pick 点路径、max_chars 截断、shell 固定 local 档除非 
eedsRoot；M2 接线在 AgentEngine.kt:1056-1063）② 0.8.99 沉浸验证 ③ **状态行边距**：现 18/18 相等（ChatScreen.kt:1303-1307/:1332-1333/:1146）；用户要 8dp ⇒ 需改两个共用值（卡片下内边距 10dp、列表底余白 16dp），**波及全局，等用户点头** ④ ProfileScreen 「重新总结」转圈丢按钮文字 ⑤ 导航条拖到已加载边界的提示（W45②，未做）⑥ B3 全局工具开关（待拍板）⑦ 排队行「编辑」不带回图片 / 图文混合行无「含图片」标记（两个小缺口，开放）。
- **硬规矩（新会话必须守）**：只在用户说「打包」时出包；出包前 aapt 核 versionName；push 直连必失败 ⇒ 代理 127.0.0.1:7890 重试并报、release 附件按字节大小核对；**不用 adb 操作用户手机**（点击/截屏/录屏都禁止），证据靠用户发图或本地 ffmpeg（suixing/video/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe）逐帧分析用户录屏；观感类一次只改一处、先给代码/数值证据；**禁止脚本批量写文件**（曾删掉 6 个文件输入框）；团队 8 名额满且用户要求清队 ⇒ **剩余活自己干、不派单**。
- **动画基线（勿再动）**：＝0.8.91（966b186）：进页 slideIn(+1/12, 200ms)、exit None；返回 popEnter None + popExit fadeOut(200ms)；抽屉点行/点会话 snapTo(Closed)+UNDISPATCHED、返回来源页 snapTo(Open)。**否掉清单**：fadeIn 残影、opaque+delay、close() 滑出、±9dp 补偿、子页淡入 —— 见「★ 已实测否掉」表。
- **关键锚点**：转场 SuixingApp.kt:518-530、抽屉 :255/:287/:305；顶栏结构性对齐 Common.kt PageTopBar（Box+align(CenterStart)，副标题已全站删除）；长按冻结 ChatScreen.kt:979-991；插话单一写入者 ChatViewModel.kt:563-565；插件 M1/M2 plugins/。

## 8. 0.8.175 → 0.8.195：上下文治理 / 性能页 / 聊天页拆分（2026-10-18 追加）

> 本会话把聊天页从单文件 7880 行拆成 9 个文件，并做了上下文与性能治理。行号以当前工作树为准。

### 8.1 已落地（都有单测保护）

| 模块 | 文件 | 要点 |
|---|---|---|
| 图片预算 | agent/RequestImages.kt | 只降级**工具截图**（ChatMessage.toolScreenshot），**用户上传附件不动**；超 MAX_REQUEST_IMAGE_CHARS=2MiB 才动；最老先降；占位给出 ui_screenshot path= 取回 |
| 工具输出落盘 | agent/ToolOutputSpill.kt | 超 MAX_TOOL_OUTPUT=8000 全文落 filesDir/toolout/*.txt；wire 留头部+路径+read_file offset/limit+「不要重跑」；写失败也如实说；只留 40 个 |
| 压缩切法 | agent/CompactionPlan.kt | 末 6 条不动、切点不落 tool 消息、摘要里每条工具结果取前 2000 字符 |
| 无 run 压缩 | AgentEngine.compressNow + ChatViewModel.compressNow() | 按已落盘历史压一次 → ContextCheckpoint → 转录里出现压缩块 |
| 性能记录 | data/PerfMonitor.kt + Core.perf | 有界 200 条、落盘 perf.json；每轮结束从**最后一条 assistant 消息**取 ttft/tokens/cached（StoredMessage 自带），不估算 |
| 性能页 | ui/settings/PerfScreen.kt + SuixingApp.kt 路由 settings/perf | 顶部均值 + 最近 20 轮；没采集到的项显示「—」 |
| seccomp 兜底 | capability/Shell.kt | exit 159 = 128+SIGSYS ⇒ **逐条命令**回退 sh -c 并写明原因（**不做**进程级永久降级） |

### 8.2 聊天页拆分与当前间距（改动前先读这里）

- 文件：ChatScreen / ChatComposer / ChatContext / ChatTopBar / ChatBlocks / TurnScrubber / ChatRows（纯函数模型层）/ ChatScroll / VoiceOverlay + ChatViewModel
- 让位：stackFootprint = composerH + COMPOSER_BOTTOM_PAD + navBarPad(heldIme) + (运行12/空闲16dp)
- **part 之间 6dp**（ChatTopBar.kt 7 处 Spacer，原 10dp；EmptyHero 的两处本来就是 6dp，未动）
- 折叠块内：内容区 top **0**、args 上 **6**、args→分割线 **6**、分割线→「输出」**2**、内容区 bottom **6**
- ToolDetail：虚拟列表 + 命令不截断 + 输出逐行 + heightIn(max=180dp)；「输出」行 CenterVertically
- 追底：ChatScroll 用 snapshotFlow 盯「离底多少」增量滚动；折叠块展开用 expandVertically，LocalReadingPinned 时 0 时长

### 8.3 提示词语言规则

- **模型可见 → 英文**：系统提示词四段、COMPRESS_PROMPT、COMPRESS_CONTEXT_SPEC.description
- **必须保留 answer the user in Chinese**，否则模型改用英文回答
- **用户可见 → 中文**：ui/settings/ToolCopy.kt；分组由 ToolGroup.group 自声明，页面自动生成

### 8.4 本会话新增的坑

1. **禁止用脚本批量改源码**：-replace 参数写错导致 9 个工具文件被清成 0 字节，git checkout 恢复后仍丢了未提交的 edit_file。**只用 read+edit。**
2. **新路由必须注册**：SuixingApp.kt 有显式 listOf("settings/...") 路由表，漏了会**点进就崩**。
3. **Modifier.padding 没有 (horizontal, top, bottom) 重载**，要用 (start, top, end, bottom)。
4. **replace_all 会波及其他位置**：改前先 grep 全部命中并核对归属（曾把 EmptyHero 的两处 6dp 误报为改动）。
5. **git diff 对未跟踪文件无效**：本会话拆出来的 ChatTopBar.kt 等是新建未跟踪文件，回溯只能看改动前的 grep 输出。
6. **压缩工具会拒**：区间被 active blocks 覆盖时返回 Range contains no compressible messages，先用 acp_status 取可用区间。
7. **push 直连不通**：github.com:443 超时（codeload.github.com 通），需代理。

### 8.5 状态与待办

- 工作树：编译 0 错误、单测 **503/503**；已装 **0.8.195(206)**；本地提交 4df6de8（**未推送**）
- **例外未验证**：用量文案 入/出 → 输入/输出（ChatBlocks.kt 6 处）已改但未编译
- **待办**：① 编译验证上述文案并出包 ② 工具完成→下次响应的本地阻塞（候选：breakTools 每轮序列化全量 schema、每轮整份会话 JSON 落盘、PluginRuntime.enabledPlugins()/skills.list() 每轮读盘；需性能页数据支撑）③ 真机验收性能页/多工具块/手动压缩/seccomp 提示 ④ 推送

### 8.6 模型设置页改造（0.8.196 装机）

1. **提供方分组移到页面顶部**（原在第 822 行的 item 移到 LazyColumn 首位）
2. **提供方可见「删除」**（「新建」旁新增 chip；只剩一个时禁用；与长按菜单同一条 deleteProvider 路径）
3. **移除「系统提示词」编辑器**（助手页改；引擎读 assistant.systemPrompt 优先、全局兜底，Settings.kt:579）
4. **provider 不再默认指定模型**（删掉 onConfirm 里的 form.model = picked.first()）
5. 获取模型列表 → 勾选即进 provider.enabled（原有行为，已核实）
6. **上下文窗口 / 最大输出 token 只在 ModelPickerDialog 里按模型设置**（页面级「上下文窗口」整块已删）
7. **DEFAULT_CONTEXT_WINDOW = 256_000**（Settings.kt:55）；`contextWindowFor = modelContexts[model]?.takeIf{>0} ?: 256_000`（Settings.kt:422）

### 8.7 折叠块首行间距：三次返工与当前状态

- 曾用 `BLOCK_CONTENT_TOP = 6.dp`（ChatBlocks.kt:803）统一思考块/工具块首行间距
- 思考块先后试过：容器 6dp → 2dp → 0dp → 列表 `contentPadding(top=6dp)`（0.8.201）
- `thinkingStyle()` 已加 `includeFontPadding=false` + `LineHeightStyle(trim = FirstLineTop)`（去字体 padding 与首行半截行距）
- **未解决**：用户报「思考块分割线下方有空白条，列表滑下去它还在」。**关键矛盾**：0.8.200 里思考块已无任何容器内边距，空白仍在 ⇒ 归因于容器 padding 与事实不符，**尚未定位真因**
- 下一步必须先确认：用户测的是哪一版（设置→关于）+ 展开并滑动后的截图（看分割线到首行距离是否随滚动变化）

### 8.8 版本与提交

- 已装机轨迹：0.8.195(206) → 0.8.196(207) → 0.8.197(208) → 0.8.198(209) → 0.8.199(210) → 0.8.200(211) → **0.8.201(212)**
- 本地提交 `4df6de8`（75 files）之后的新改动**未提交**；`git push` 因 `github.com:443` 不通失败（`codeload.github.com` 通）
- 单测基线：**503/503**（每次改动后清 test-results 重跑确认）

### 8.9 本轮新增的坑

8. **「空白条滑不走」≠ 一定是容器 padding**：容器 padding 确实在滚动区外，但 0.8.200 已无容器 padding 而空白仍在 ⇒ 未定位前不要宣称根因。
9. **PowerShell here-string 里的反引号会截断**：Markdown 代码块含反引号时必须用单引号 here-string `@'...'@`，否则报 ParserError 且中文乱码。
10. **`PlatformTextStyle(includeFontPadding=false)` 不能去掉 lineHeight 的行距**，必须配 `LineHeightStyle(trim = FirstLineTop)`。
11. **改完源码要清 `app/build/test-results/testDebugUnitTest`** 再跑，否则会读到上一轮缓存的「全绿」。

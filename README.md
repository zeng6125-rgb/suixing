# 随行 Suixing

手机端的 AI Agent。给它一个 OpenAI 兼容的模型接口，它就能读你的屏幕、点你的手机、改你的文件、跑你的命令、按时提醒你。

> 0.7.0 起项目从「灵枢 / Lingshu」更名为「随行 / Suixing」，包名同步从 `com.lingshu.agent` 改为 `com.suixing.agent`。**包名变了意味着这是一次全新安装**：旧版本请先卸载，会话记录与设置不会迁移。

> **会话存储**：`chats/store/<id>.meta`（元数据 + 累计统计）+ `<id>.<gen>.jsonl`（每行一条消息的追加日志）。保存一轮只追加尾部，不再全量重写——32 MB 的会话每轮保存从「序列化 + 写 32 MB + rename」降到「追加几十字节到几 KB」。重排类操作（编辑重答、分支切换）会换代重写，旧代留一代作回退。**装回 0.8.316 及更早版本看不到这些会话**；更早的 `<id>.json` 单文件格式已不再支持读取（迁移代码随兼容层一起删了），磁盘上残留的这类文件不会出现在列表里。

- 纯 Kotlin + Jetpack Compose（Material 3，Compose BOM 2026.09.00 / Kotlin 2.4.21 / AGP 9.4.1 / Gradle 9.8.2），没有 NDK 源码；随包只带两个预编译的静态 shell 二进制（bash / BusyBox，见[第三方声明](#开源许可与第三方声明)）
- 目标平台：Android 8.0 (API 26) 及以上；`compileSdk` / `targetSdk` = 37
- 图片走 Coil 3（内存 + 磁盘双缓存、自动降采样），不再手写 `BitmapFactory` 与 LRU
- 不做云同步、不做遥测，API Key 只存在本机应用私有目录
- 支持 MCP：任何 MCP 服务器提供的工具都会并进模型可调用的工具表
- 支持技能（Skill）：把写好的操作手册放进手机，模型需要时自己读
- 支持插件（Plugin）：安装一个 zip 就能带来新工具、新的上下文注入与新的执行闸门
- 当前源码版本：**0.8.561**（`versionCode` 582）

## 界面与设计

视觉取舍参考了 [Aether 扶摇](https://github.com/Zhou-Shilin/Aether)——不跟随 Material 动态取色、层级靠表面阶梯而不是阴影、卡片走大圆角、整页 edge-to-edge；布局与交互为本项目自研。随行自己的配色是一套手调的**墨绿**：深色侧底色 `#0F1412`、主色 `#8FD6B6`、正文 `#E8EFEA`，浅色侧白底 + 主色 `#1F6B4C`。几个刻意的选择：

- **不跟随 Material 动态取色**。默认用一套手调的「随行配色」，想要跟壁纸就在「设置 → 外观 → 配色」里切到「跟随壁纸」（Android 12 以上，会改用系统动态取色）。
- **不用阴影**。层级靠表面阶梯（`surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh`，另有两级 Lowest / Highest）加 1dp 发丝描边区分。
- **卡片 20dp 圆角、左侧抽屉导航**。没有底部导航条，会话页的纵向空间全部留给消息；顶栏左上角的按钮拉出抽屉（会话列表 + 助手 / 定时任务 / 文件 / 悬浮窗控制台 / 设置）。
- **edge-to-edge**。状态栏与导航栏透明，内容自己处理 insets；顶栏自带 `statusBarsPadding()`。列表页的底部让位走 **`contentPadding`**——内容画到手势条下面、滚到底时末行才避让，而不是给列表挂 `navigationBarsPadding()`（后者会让内容停在手势条上方，底下留一条空背景带）。`navigationBarsPadding()` 只留给 `SnackbarHost` 这类不该穿底的浮层。
- 会话页空状态是一个品牌标记加四条建议提示词（「看看现在屏幕上是什么」等），点一下直接填进输入框。
- 输入框那一行常驻一枚**模型胶囊**（当前模型名 + 思考强度徽标），点开是一张两页的卡：第一页是当前模型与思考强度滑杆，第二页是按端点、再按上游厂商分组的模型列表。
- **折叠块展开/收起时钉住块顶边**。思考过程、工具条展开时，用同一套逐帧补偿把块顶边按在原位，内容往下长而不是把上面的东西顶走。
- 切会话时转录有一次 **240ms** 的入场：淡入 + 从下方 12dp 升起（不缩放）。这一层是常驻的——条件挂载会让列表的修饰符链在切换时重建，展开/收起折叠块就会出现残影；恒等态下它不改变任何像素，合成器直接跳过。
- 外观页可调主题（跟随系统 / 浅色 / 深色）、配色、字号、圆角、气泡宽度、打字机效果、边缘光、提示条停留时间。

## 功能

### 对话

- **多会话**：左侧抽屉顶部是会话列表，支持改名、置顶、归档、导出、删除；默认只渲染最近 12 轮，顶部可以「加载更早的 12 轮」。
- **流式输出**：正文、思考、工具调用分三路流式回来，思考段与工具段会把正文段切开，合并回一条回复里。
- **Markdown 渲染**（自研实现，不依赖第三方库）：标题、有序 / 无序列表（支持嵌套）、表格、引用、代码块（含语言标签与复制按钮）、分割线、行内的加粗 / 斜体 / 删除线 / 行内代码、可点链接与自动识别的裸链接、文件路径可点。
- **前台服务保活**：`AgentForegroundService` 在任务运行期间挂一条常驻通知（通知渠道「任务执行」），切后台不中断。
- **思考过程折叠块**：推理模型（DeepSeek-R1 系、国产网关的 `reasoning_content`）的思考实时流出来，包在「思考过程」折叠条里。
  - 正在流的那一块**总是展开**（看着它长出来正是用推理模型的意义）；结束后按「设置 → 对话配置 → 历史思考块展开」决定，默认收起。
  - 点标题逐块展开/收起，选择按会话记住。
  - 整段思维链**逐行**渲染在一张倒序虚拟列表里（几千行也不会把主列表拖慢），高度上限默认 180dp，可在「设置 → 对话配置 → 思考块高度」调成 240 / 320。
  - 流式时自动追尾，手指往上拖就停跟，真滚到底再跟回来（「思考块跟随最新」可关）。
- **工具条**：一轮里跑的**所有工具合成一张卡**，头部写「调用 N 个工具」与失败数，每行一个工具，每个工具**各自**点开看详情。
  - 详情里有入参（等宽字体、不截断）、一条分割线、「输出」标签与复制按钮、输出逐行进虚拟列表，以及工具返回的截图。
  - 工具条本身是普通 `Column`（不做虚拟列表、不设高度上限，所以不会被截断），只有单个工具的详情保留 180dp 上限。
- **每条回复下方有 token 统计**：入 / 出 / 思考 / 缓存命中 / 耗时 / 输出速度（tok/s），接口没返回 usage 时这一行不显示；「用时详情」「用量详情」可展开成卡片。
- **上下文圆环**：输入框那一行显示当前对话占上下文窗口的比例，快满时变红。分子只认**实测**口径（网关回报的 `prompt_tokens`，含按会话保持的最近一次；其次读落盘的 `meta.totals.contextTokens`），只有整个会话从没被报过用量时才用字符数 ÷4 估算兜底并标「约」。分母按**模型**取（`/models` 报过的自动填，没报过的落到 256k 默认）——换模型会换分母，所以同一个会话在不同模型下的百分比不同是正常的。
- **发送图片**（多模态模型可直接看图；用纯文本模型时请保持设置里「模型支持图片输入」关闭，否则带图请求会被接口拒绝）；附件也可以是文件。
- **语音输入**：默认关闭。打开后输入框旁出现麦克风，走随行自己的录音链路（AudioRecord → WAV → 你配置的 OpenAI 兼容 `/audio/transcriptions`），**不弹系统识别面板**；失败时可手动降级到系统识别。
- **语音输出**：「朗读回复」打开后，每条回复结束用系统 TTS 读出；模型也能自己调 `speak` 工具主动说话。
- **消息可改**：长按任意一条（或点它下面的「修改」）就地编辑。用户消息可以「只保存」也可以「重新发送」——重新发送会丢掉这条之后的所有内容并让模型重新回答；助手回复只能改文字。
- **消息分叉**：对最后一个问题点「重新回答」，新答案作为**另一个候选**追加在同一个问题上，不覆盖旧的；气泡下方的 `‹ 1/2 ›` 式切换器在候选之间来回切，切换只改显示，后面的对话都留着。
- **停止 / 插话**：运行中发送键变「停止」，点停即停（不等当前工具跑完）；输入框里有内容时它变成「插话发送」，在下一个模型边界把这句话插进同一轮。插话可以排队（插话方式：排队 / 立即）。
- **任务清单**：模型调 `todo_write` 时，输入框上方出现一条待办行（`已完成/总数`），点它可以展开完整清单；完成的条目从行上消失，跑完或停止整条收起。
- **自动跟随**：在底部时新回复自动滚到底，上滑看历史就暂停跟随，出现「回到最新」按钮。
- **右侧轮次导航条**：常驻 5 格，越往回读逐级解锁到最多 12 格；长按画出全部已加载轮次。点一格跳到那一轮，往上拉可以解锁更早的轮次。

**助手（人设）**

抽屉里的「助手」一行打开半屏助手面板（设置 → 数据 → 助手设置 是同一份内容的整页）。每个助手有自己的名字、头像、一句话说明、系统提示词、模型、可选的单独温度，以及一份工具开关。

所有字段都是**加法**：留空的项跟随当前设置，所以只改个名字也能用。

| 字段 | 留空时 |
| --- | --- |
| 名字 / 头像 / 一句话说明 | 只影响显示 |
| 系统提示词 | 用设置页的系统提示词 |
| 模型 | 用当前模型（也可以钉死某个模型或某个提供方） |
| 温度 | 跟随全局温度（打开「单独设置温度」才用滑块的值） |
| 工具 | 全部可用（在助手的编辑页里把不想给的取消勾选） |

面板里点卡片＝切换，点右上角铅笔＝编辑；至少要留一个助手，所以最后一个删不掉（会直接告诉你为什么）。头像是可以用系统照片选择器上传的（不需要存储权限），会自动缩到 256px 存进私有目录，消息卡、流式气泡、空状态、助手列表都跟着变。模型也可以自己调 `create_assistant` 新建一个助手。

### 工具

内置 **52 个工具**，分 12 个工具包；分组由工具包自己声明，加一个工具、删一个工具，分组和人数自动跟着变。

| 分组 | 数量 | 工具 |
| --- | --- | --- |
| 系统 | 10 | `device_info` `list_apps` `open_app` `open_url` `clipboard_read` `clipboard_write` `memory_write` `memory_read` `profile_write` `profile_read` |
| 文件 | 6 | `read_file` `write_file` `edit_file` `list_dir` `find_files` `grep_files`（前三个与 `list_dir`/`find_files`/`grep_files` 都在应用内执行，不 fork 进程；`mkdir`/`stat`/`mv`/`rm` 直接用 `run_shell`） |
| 界面操作 | 11 | `ui_dump` `ui_screenshot` `ui_tap` `ui_long_press` `ui_swipe` `ui_click_text` `ui_type` `ui_type_into` `ui_key` `ui_current_app` `ui_wait` |
| 后台任务 | 4 | `job_start` `job_list` `job_output` `job_kill` |
| 定时提醒 | 4 | `schedule_create` `schedule_list` `schedule_update` `schedule_delete` |
| 技能 | 4 | `skill_list` `skill_read` `skill_save` `skill_download` |
| 网络 | 3 | `web_fetch` `web_download` `web_search` |
| 任务清单 | 2 | `todo_write` `todo_read` |
| 语音 | 2 | `speak` `stop_speaking` |
| Shell | 4 | `run_shell` `app_action`（start / force_stop / clear_data / uninstall / disable / enable / info / permissions / install）`settings_get` `settings_put` |
| 其它（未声明分组） | 1 | `create_assistant` |
| 会话 | 1 | `get_session_status` |

- 另有引擎自己拦截的**虚拟工具** `compress_context`（手动压缩上下文），以及动态接入的 MCP 工具 `mcp__服务器名__工具名` 与插件工具 `<插件id>_<工具名>`。
- `get_session_status` 让模型随时能问「现在用的是哪个模型、哪个接口、上下文窗口多大、当前助手是谁、会话总数多少」——它是**只读**的，不碰会话内容。
- 文件工具的默认工作目录是 `Android/data/com.suixing.agent/files`（应用外部私有目录，不需要任何存储权限）；`~` 即该目录，绝对路径原样使用，相对路径里带 `..` 直接拒绝。
- 工具输出太长会被「溢写」到 `filesDir/toolout/`（保留头部，附一条让模型改用 `read_file` 去读的提示），不撑爆上下文。
- **工具审批**三档：自动运行 / 每次询问（默认）/ 禁止。被记住的「总是允许」与「总是拒绝」都可在一键清除。
- **并行工具数**可调（默认串行）；只有「自动运行」档才并行，「每次询问」档一次只问一个。
- **哪些工具能并行由工具名决定，不看运行时能力**（`agent/ToolBatching.kt`）：`ui_*` 手势与 `run_shell` / `app_action` 各自独占一组、前后都是屏障；纯读类（`read_file`、`grep_files`、`ui_dump`、`ui_screenshot`…）照旧并行。两个手势并发 = 一个按在另一个刚滑走的位置上，同一个 shell 会话里并发提交两条命令 = stdout 互相插队。
- **工具目录在一个 run 内冻结**：能力中途丢失时工具仍留在表里，执行前复核并返回明确错误；新启用的能力追加到末尾。这是保住 prompt 前缀缓存的关键——目录每轮重拼会让缓存命中率崩掉。
- **写类操作读回核验**：`settings_put` 写入后立刻 `settings get` 读回，回报 `before` / `after` / `verified`。`settings put` 退出 0 不等于生效（SELinux 拒绝、namespace 写错、写到了别的 user 都会静默失败），所以命令成功与「真的改了」是两件事。
- **敏感数据不进会话记录**（`data/TranscriptRedaction.kt`）：`clipboard_read` 的结果、`ui_type` / `ui_type_into` / `clipboard_write` 的参数，**这一轮发给模型的仍是原文，落盘时替换成占位说明**。`run_shell` 与 `read_file` 刻意不屏蔽——它们能读到的东西无限广，一刀切会把会话记录掏空。
- 界面操作类工具走无障碍通道，Shell 类工具走 shell 通道，权限档次见下。

**联网**：`web_fetch` 抓网页（HTML 自动转纯文本，带编码嗅探，1MB 上限）、`web_download` 存文件到本机、`web_search` 搜网页。搜索默认走内置的 DuckDuckGo 抓取，零配置但容易被限流；想要稳定就在「设置 → 网络与语音」里填一个 Tavily 兼容接口（地址 + Key），`web_search` 会自动改走它。

### 模型

接口只要兼容 OpenAI 的 `/chat/completions` 就能用（DeepSeek、通义、Kimi、OpenRouter、vLLM、Ollama、局域网自建反代都行）。另外两种协议也在同一处选：**Anthropic Messages**（`/v1/messages`）与 **OpenAI Responses**（`/responses`）。

**多个接口**：一个地址一个「提供方」，设置页顶部用胶囊切换，`新建` 加一个、`删除` 去掉当前这个。切换提供方等于把它设为当前接口——地址、Key、模型一起换过去，不用来回改。

**不用手打模型名**：填好接口地址和 Key，点「获取模型列表」，随行会去请求 `{地址}/models`，把返回的模型按上游厂商分组列出来（聚合反代返回的 `deepseek/deepseek-chat`、`openai/gpt-4o` 会自动归到 `deepseek`、`openai` 两组，没有前缀的归到「其他」）。**可以多选**，每组还能一键全选；勾中的模型才会出现在模型切换器里。

- **地址写 `.../v1` 就行**，不用补 `/chat/completions`；补了也认。
- **局域网自签名证书**：如果你的反代是 `https://192.168.x.x:端口/v1` 这类自签证书，安卓会以 `Trust anchor for certification path not found` 拒绝。打开「设置 → 模型与生成 → 允许自签名证书」即可放行（只对模型请求生效，且只跳过证书校验，不影响别的）。更省事的办法是把地址写成 `http://`（应用已允许明文流量），或者在手机上把该证书装进系统信任库。
- **报错说人话**：401 会说「API Key 不对或已失效」，404 会提醒地址要以 `/v1` 结尾，连不上会说「检查地址和网络」，证书问题会直接告诉你打开上面那个开关；被截断的流会明确说「这一轮超过 10 分钟被中断」。
- **失败自动重试**（默认开）：只对连接重置、超时、5xx 与 429 重试，429 优先用服务端的 `Retry-After`；已经开始输出正文就绝不重试（那会重复计费）；用户点停止立刻结束等待。
- **上下文自动压缩**：上下文接近窗口上限时（阈值 20%–95%，默认 80%）把较早的对话压成摘要，摘要只进请求不落盘。也可以在输入框的「+」里手动「压缩上下文」。

**生成参数**（设置页「模型与生成」）——这几个都是加法：留默认就跟随接口自己的行为。

| 项 | 作用 |
| --- | --- |
| 显示思考过程 | 只控制画不画。关掉不代表不让模型思考，思考强度照旧生效 |
| 思考强度 | 对应接口的 `reasoning_effort`，七档：默认 / minimal / 低 / 中 / 高 / xhigh / max。不支持的模型会忽略这一项 |
| 上下文窗口（tokens） | 这个模型一次能读多少 token，可写 `128k` / `1m`。**按模型逐条配置**，上游 `/models` 报过窗口的会自动填上；没报过的落到全局默认 256k（保守上界，不是「不限」——0 会让压缩阈值失效）。它也决定输入框那行圆环的分母 |
| 上下文条数 | 每次请求只带最近多少条消息，默认不限。历史很长时调小可以明显提速、省钱 |
| Top P | 核采样，1.00 表示不过滤，一般不用动 |
| 最大输出 tokens | **按模型配置**（全局与提供方级的旧字段已退役，老文件里的值会自动搬进按模型的表）。0 或缺席＝不说，由模型/端点默认决定 |

温度在「模型与生成」卡片里；每个助手还可以单独覆盖温度与提示词。模型切换器里的思考强度滑杆是**按会话**记的，不会把别的会话也改了。

### 扩展

「工具」页有四个标签：**工具 / 技能 / MCP / 插件**。

**工具**页是只读总览：可以搜索、看每个工具的名字、用途和它需要的权限；全局没有「禁用某个工具」的开关，唯一的禁用名单是按助手存的（去那个助手的编辑页取消勾选）。

**MCP（外挂工具）** 可以接入任意 [Model Context Protocol](https://modelcontextprotocol.io/) 服务器，接入后它提供的工具会和内置工具并列出现在模型面前，同样走审批闸门。两种传输方式：

| 传输 | 填什么 | 适用 |
| --- | --- | --- |
| `stdio` | 一条启动命令，如 `python /sdcard/mcp/server.py` | 服务器是个本地进程，随行把它当子进程跑，用管道收发换行分隔的 JSON-RPC |
| `http` | 服务器地址，如 `http://127.0.0.1:3000/mcp` | 服务器跑在 Termux、电脑或公网上（兼容 `streamable HTTP` 与 SSE 响应） |

- 工具名形如 `mcp__服务器名__工具名`，超长会自动截断并加短哈希防撞名（OpenAI 的函数名上限 64 字符）。
- 连接断了会后台指数退避重连（2s 起、最多 5 次），期间工具调用会返回「MCP 连接中断，正在后台重连」。
- 关于 stdio 的现实约束：安卓上子进程以应用自己的身份运行，读不到 Termux 的私有目录（`/data/data/com.termux` 是 0700）。所以手机本机跑 stdio 服务器有两条实用路径——把脚本放在应用能读的位置（如 `/sdcard`），或者设备已 root 时用 `su -c "node /data/local/tmp/server.js"` 借 root 跑。跑在 Termux 里的服务器更推荐走 `http`。
- 「测试」按钮会用一次性连接做完整握手（initialize → tools/list）并报出服务名、协议版本与工具数量，不会注册任何东西。

**技能（外挂知识）** 是写给模型看的操作手册——一段固定流程、一套规范、某个 App 的用法。和 MCP 的区别在于：MCP 给的是**工具**，技能给的是**怎么做**。

放在 `Android/data/com.suixing.agent/files/skills/` 下，两种形态都认：

```
skills/
├── daily-checkin/SKILL.md      # 一个文件夹一个技能
└── 修图规范.md                  # 或直接放一个 .md 文件
```

`SKILL.md` 的格式是 frontmatter + 正文：

```markdown
---
name: 每日签到
description: 打开某 App 完成签到，用户说「签到」时使用
---

1. 用 open_app 打开「XX」
2. 用 ui_dump 找到「签到」按钮并 ui_click_text 点击
3. 用 ui_dump 确认结果，截图回报
```

**为什么装很多也不占上下文**：系统提示词里只放技能的名字和 `description`，正文要用时由模型调用 `skill_read` 现读。所以 `description` 要写清「什么时候用」，模型才判断得出来。

「工具 → 技能」页可以查看全文、逐个开关、从系统文件管理器导入一个文件夹（批量）或一个 `.md`、从链接导入（超过 2 MB 的文件自动跳过）。导入的技能**默认关闭**，看过内容再打开开关；模型自己用 `skill_save` 存出来的技能默认开启。

**插件** 是一个 zip 包（包里有 `manifest.json` 和若干文件；选文件时选任意文件都行，由校验器判断它是不是插件）。装上去之前会先校验并把「会执行的内容」逐条摆出来给你确认；装好后可以单独开关、填它需要的环境变量（密钥写进 `env.json`，权限 0600）、看它注入了什么上下文、随时卸载（卸载进回收区，保留 7 天）。

- 清单字段：`id` / `version` / `displayName` / `description` / `author` / `env` / `tools` / `hooks` / `context` / `deps`。
- 命名规则：`id` 小写字母开头、只含小写字母 / 数字 / 横杠、≤32 字、不含点号；工具名同规则但可含下划线；注册名是 `<id>_<tool>`。
- 工具的执行方式只有两种：`http`（自定义请求模板）或 `shell`（本地命令，可声明需要 root）。
- 钩子目前只有 `tools/pre-execute` 一个事件，可以否决、要求确认或只记一条日志。
- 上下文注入支持 `{{device.model}}` `{{device.battery}}` `{{device.storage_free}}` `{{time.now}}` `{{foreground.app}}` 这几个变量。
- 插件按能力分四级：L1 只有说明、L2 带 `INSTRUCTIONS.md`、L3 带脚本或资源、L4 有依赖。

### 系统

**四档能力**（对应「能力」页的四张卡）——四档是叠加的：标准档人人都有，拿到 Shizuku 就自动拥有无障碍的能力，root 又覆盖前两档。

| 档位 | 获取方式 | 能做什么 |
| --- | --- | --- |
| 标准档（默认） | 装上就有，不需要任何授权 | 聊天、记忆、待办、定时、技能、网络与文件工具 |
| 无障碍 | 系统设置里打开「随行 · 屏幕操作」 | 读屏（UI 树 dump）、截图、点击、长按、滑动、输入文本、按键 |
| Shizuku（ADB 级） | 安装 [Shizuku](https://shizuku.rikka.app/) 并授权随行 | 以 `shell` 身份执行 `am` / `pm` / `input` / `dumpsys` / `screencap` 等，可读写 `Android/data` 与 `/sdcard` |
| Root | 设备已 root | `su -c` 执行任意命令，直接读写系统文件 |

- 执行档次可以固定（「点已选中的那一档可回到自动」）。固定某一档时只走那一档，不可用时只向下退，绝不偷偷升到 root。
- 能力探测的结果会落盘：冷启动不再触发 superuser 弹窗，只有缓存不存在或你点「检测」时才重探。Shizuku 与无障碍的状态在回到前台时刷新。
- 没开也不会崩——相关工具会返回「权限不足 + 怎么开」的提示。
- 「所有文件访问权限」是另一条独立的授权，用来在文件页访问 `/sdcard`；拿不到时可以用 Shizuku 或 root 走全盘。

**悬浮窗控制台**：agent 跑起来时，屏幕上有一个可拖动的悬浮球，点开是一块面板，显示当前跑到哪了，有「停止」按钮和一个插话输入框，还能「回到对话」。抽屉里那一行既是开关也是授权入口。

**任务清单**：模型做多步任务时把步骤记下来，分「进行中 / 待办 / 已完成」；聊天页输入框上方的待办行点开就是完整清单。

**定时任务**：到点自己跑的一条提醒。`SchedulerService` 一次只挂一个闹钟（最近的那条），响完重排；开机、校时、换时区、换包之后都会重新排。错过的一次性提醒会标成「已过期」，不会补响。

**记忆**（模型在任务里主动记下的键值对）与**用户画像**（模型定期从对话里读出来的滚动摘要，`filesDir/profile.md`）是两回事：前者由模型自己决定记什么，后者按「画像整理间隔」定期重写，并且每次请求都注入提示词。画像页可以手动改，也可以「重新总结」。

**用量**页给的是 token：累计 tokens、会话数、最长单轮、近 30 天的每日柱状图、按模型的分布。

**性能**页分三段：最近若干轮的首字延迟 / 整轮耗时 / 缓存命中 / 输出速度均值；设备实时（CPU、GPU、帧率、内存、Java 堆、线程）；会话健康（p50 / p90、抖动、无 usage 回报的轮数、慢轮）。

**文件**页可以浏览三个根：工作目录（`Android/data/com.suixing.agent/files`）、`/sdcard`（需要「所有文件访问权限」）、插件目录。支持面包屑导航、上一级、刷新、文本预览与复制（预览上限 20KB）。这一页是只读的——要改文件走工具。

**会话管理**可以打开、导出（SAF 选位置，导出 JSON）、删除会话。

## 构建与出包

需要 **JDK 17** 与 Android SDK（`platforms;android-37`，以及与它配套的 build-tools 37.x）。SDK 路径写在 `local.properties` 的 `sdk.dir`（该文件不入库）。

**仓库现在带 `gradlew` / `gradle/wrapper/`**（0.8.4xx 起补上的），wrapper 固定 **Gradle 9.8.2**——AGP 9.4.1 要求 Gradle 9.x。本地直接 `./gradlew` 即可，不再需要一个已解包的 Gradle 发行版。

```bash
# 只查语法，不出包
./gradlew :app:compileDebugKotlin

# 单元测试（1012 个 @Test / 149 个文件 —— 这是静态注解计数，不是某次运行的通过数：
# 协议层往返、HTML 转纯文本、Markdown 解析与增量复用、
# 工具批次的并行判定、敏感数据屏蔽、会话日志的追加/换代/自愈、接口报错翻译、
# 提供方与模型分组、消息分叉的选取逻辑等，无需设备）
./gradlew :app:testDebugUnitTest

# 出包（只出 release）
./gradlew :app:assembleRelease
# 产物 app/build/outputs/apk/release/app-release.apk
```

- 本机（Windows）没有 `gradle` 也不在 PATH 时，用一个**转发脚本**把 `gradlew.bat` 指到已解包的 Gradle（脚本只做一件事：设好 `JAVA_HOME` / `GRADLE_USER_HOME` 后 `call` 真实的 `gradle.bat %*`），任何 gradle 任务都能透传。转发脚本放在仓库之外，不入库。
- **CI 跟仓库 wrapper 走**：`.github/workflows/android.yml` 用 `gradle/actions/setup-gradle@v6` + `./gradlew`，不再由 `gradle-version` 钉死版本，Gradle 版本只由 `gradle/wrapper/` 决定（曾经出现过的 `Minimum supported Gradle version is 9.6.0` 已修）。
- **出包纪律**：只出 release，不构建 debug——debug 的 `applicationId` 带 `.debug` 后缀，装上去会变成另一个应用。打包要等放行。
- 装机前必须 `aapt dump badging` 核对 APK 的 `versionName` / `versionCode`（历史上出过一次「构建没跑、把旧包当新包装机」的事故；几个版本的 APK 字节数完全相同，光看大小分不出来）。发错了用 `gh release upload --clobber` 覆盖附件。
- 推送到 GitHub 后 `.github/workflows/android.yml` 会自动跑：`./gradlew :app:testReleaseUnitTest` → 上传测试报告 → `./gradlew :app:assembleDebug :app:assembleRelease` → 上传 `suixing-debug` / `suixing-release` 两个 APK artifact。单测跑的是 **Release 变体**（与出包同一份编译配置，Release-only 的问题才拦得住）。
- release 用仓库内的 `app/sideload.keystore` 自签（alias / 密码都是更名前的 `lingshu`。**不要改**：keystore 就是用这个名字生成的，改了等于换签名，已装的版本将无法覆盖安装）——这是自用侧载包，不是上架包；该 keystore 必须入库，否则覆盖安装会失败。release 目前不开 minify（`isMinifyEnabled = false`），`proguard-rules.pro` 里的规则是为将来开混淆准备的。

## 目录结构

```
app/src/main/java/com/suixing/agent/
├── MainActivity.kt              入口：edge-to-edge、主题、导航
├── SuixingApplication.kt        初始化 Core
├── core/Core.kt                 全局单例（settings / chats / capabilities / skills / speaker / tools / mcp / plugins / perf）
├── data/                        设置（DataStore）、会话存储（meta + JSONL 追加日志）、助手、画像、记忆、
│                                待办、定时、任务日志、用量、性能、MCP 配置、权限记忆
│   ├── ChatStore.kt             日志读写：追加 / 换代 / 切窗 / 自愈
│   ├── ChatRepository.kt        会话门面：列表只持 meta，正文按需读
│   ├── MessageCache.kt          最近打开的会话正文 LRU
│   ├── OffsetCache.kt           消息 id → 日志字节偏移（免全文件扫描）
│   ├── SecretBox.kt             API Key 的 Keystore 加解密
│   ├── LiveMetrics.kt           运行中的实时指标快照
│   ├── ConversationReads.kt     阅读位置
│   ├── ConversationViewports.kt 每会话的滚动位置 / 书签持久化（删会话时清理）
│   └── TranscriptRedaction.kt   落盘时的敏感数据屏蔽
├── capability/                  能力探测：无障碍状态、Shizuku 绑定、root 探测、Shell 执行
├── service/                     无障碍服务、前台保活服务、悬浮窗控制台、定时任务服务、开机广播
├── tools/                       工具层：Tool 契约 + ToolRegistry + 12 组工具
├── mcp/                         MCP 客户端：协议、stdio/http 传输、连接管理
├── plugins/                     插件：清单模型、校验、运行时（工具 / 钩子 / 上下文注入）、安装与启用
├── skills/SkillStore.kt         技能库：扫描 / 解析 frontmatter / 导入与开关
├── voice/                       录音转写与系统 TTS
├── llm/LlmClient.kt             OpenAI / Anthropic / Responses 兼容的流式客户端
│                                （SSE + tool_calls 组装、思考流、模型列表、报错翻译、重试）
├── agent/                       AgentEngine 主循环：模型 → 工具 → 模型，含审批闸门、
│                                上下文压缩、工具输出溢写、插话与中断；另有画像摘要与请求图片预算
│   ├── ToolBatching.kt          哪些工具能并行（GUI/Shell 串行，纯读并行）
│   ├── CompactionPlan.kt        压缩切点规划
│   ├── HistoryTrim.kt           按 token 预算裁历史
│   ├── InjectedUserContext.kt   插话的包装文本
│   ├── PromptMessages.kt        系统提示与消息装配
│   └── ProfileSummarizer.kt     画像摘要
└── ui/
    ├── SuixingApp.kt            外壳：路由表、抽屉状态与全局 CompositionLocal
    ├── theme/                   配色（手调 M3 角色）、排版、圆角、文件类型色
    ├── components/              通用组件、品牌标记、Markdown 渲染器、预解析注入（PreparedMarkdown）
    ├── drawer/                  抽屉：会话列表、行手势与长按菜单、拖动排序（DrawerOrderStore 落盘）
    ├── chat/                    会话页（39 个文件）：转录虚拟列表、折叠块、工具条、输入区、滚动、
    │                            轮次导航条，以及
    │                            DeltaBuffer（流式增量缓冲）、LiveParts（流式增量规则）、
    │                            LivePinState（读历史时的钉住状态）、ConversationViewport（阅读窗口）
    ├── assistant/               助手面板（半屏）与助手编辑页
    ├── settings/                设置各子页、能力页、工具页、关于页、插件页、性能页、画像页
    ├── extension/               记忆页与技能页
    ├── onboarding/              首次引导（五页）与供应商预设
    ├── files/ tasks/ schedules/ usage/   文件、任务清单、定时任务、用量
```

- `dist/` 放本机的打包产物与发布记录，已在 `.gitignore` 里，不入库。

## 首次使用

**第一次打开会有一个引导页**（五页，可随时跳过）：欢迎 → 选供应商并填接口与 Key → 选模型与思考
强度 → 权限（按「要做什么 → 需要什么 → 缺了会怎样」讲）→ 开始使用。里面预置了 DeepSeek、智谱
GLM、OpenAI 兼容（自填地址）、本机 Ollama、Kimi、千问、硅基流动、Claude 八个接口，点一下就
预填地址与模型名。

跳过也没关系——没配 Key 一样能先逛逛，聊天会等你配好接口。**「设置 → 数据与关于 → 新手引导」**
可以随时再看一遍。

不用引导页的话，手动配也一样：

1. 安装 APK，打开「随行」
2. 「设置 → 模型与生成」→ 填接口地址（如 `https://api.deepseek.com/v1`）、API Key → 点「获取模型列表」，勾选要用的模型（可多选，按上游厂商分组）→「测试连接」→「保存」（局域网自签证书见上一节）
3. 「能力」→ 打开无障碍服务（不开也能聊，但用不了 UI 自动化）；想要 ADB 级能力就装 Shizuku 并授权
4. 回会话页，直接说人话，例如「打开微信，给文件传输助手发一条『你好』」

> **API Key 存在哪**：用系统 Keystore（AES/GCM）加密后存在这台手机上，密钥不随应用数据走 ⇒
> 换机后需要重填一次。这是「更保密」与「换机免重填」之间的取舍，取前者。

## 已知限制

- 无障碍截图需要 Android 11 (API 30) 及以上；低版本 `ui_screenshot` 会返回失败提示
- 国产 ROM 可能把第三方应用加进无障碍黑名单（vivo/OriginOS 等），此时无障碍工具不可用
- 安卓 16+ 的「高级保护模式」会拦截未知来源安装并限制无障碍，请勿开启
- Shizuku 在设备重启后需要重新启动（无线调试或 root 方式）
- stdio 方式的 MCP 服务器读不到 Termux 的私有目录（见上面 MCP 一节）

## 路线图

- **v0.9**：虚拟屏（Shower 式虚拟显示，独立 native 组件）——真屏注入已在 v0.8 覆盖日常自动化，虚拟屏留到下一轮

## 性能与稳定性（v0.8.4xx 起）

这一轮把「感觉慢、数字乱跳、偶发闪退」当作主线做了一轮收口，几处值得写下来：

- **流式渲染三层节流**：模型 delta → `DeltaBuffer`（合并后按拍发布）→ 打字机（恒定揭示速度，不按积压量加速）。生成速度 100~200 字/秒，任何「追得上」的加速都会让积压多时一次吐出成整块。块的真伪只按「诞生 500ms 内第一次组合」判定：刚出现的块从 0 打，早就存在的块直接给全文。
- **会话列表只读 meta**：抽屉只画标题，不物化正文。10 个会话 54 MB 全部物化曾让 RSS 到 413 MB。正文按需读：打开会话取最近 12 轮、喂模型从最后一个 checkpoint 往后、导出取全文。
- **工具目录在一个 run 内冻结**，不每轮重拼——这是保住 prompt 前缀缓存的关键（日常缓存命中率约 95%）。
- **统计只认实测**：上下文圆环的分子优先用网关回报的 `prompt_tokens`，只有整个会话从没被报过用量才用 `/4` 估算兜底并标「约」。落盘的 `meta.totals.contextTokens` 是会话级权威值，不受「最后一条消息有没有 usage」影响。
- **写类操作读回核验**，未生效时回报 `verified=false` 并明确告诉模型「请勿假设已修改」。
- **敏感数据不进转录**：剪贴板读取的结果、文本输入类的参数，当轮给模型原文，落盘换成占位说明。

### 0.8.5xx：审计后的第二轮

对全仓 5.8 万行做了一次只读审计（147 项），逐项复核后 115 项已修，其后又收口了 19 项残留。用户能感觉到的几处：

- **转录回到虚拟列表**：按消息 id 取 `key`、按角色取 `contentType`，配 `LazyLayoutCacheWindow`。上一版为了消掉滚动时的首次组合开销，把整页装进单个 `item`——代价是行数随「加载更早」线性累积、最终退化成全量组合。现在滚出视口的行不再常驻。
- **打字不触发整屏重组**：草稿文本从顶层 `ChatUiState` 剥离，改由输入框自己订阅。按一次键只重组合输入框那一行，不再带动整个会话页。
- **抽屉滚动**：`onGloballyPositioned` 只在长按菜单打开时挂载，不再每帧给每个可见行写一次测量状态；拖动排序期间只更新预览顺序，松手才落盘。
- **冷启动不读盘**：`Application.onCreate` 不再 `runBlocking` 读 DataStore，主题模式改从一个几百字节的 `SharedPreferences` 同步读；DataStore 的收集器继续负责后续更新。
- **审批弹卡**：待审批请求带单调序号，显式取最早的一条，不再依赖 `Map` 的迭代顺序。

## 开源许可与第三方声明

- 本项目自身的代码以 **MIT** 许可发布，见 [LICENSE](LICENSE)。
- 随 APK 分发的两个**静态编译 AArch64 二进制**仍在各自的 GPL 许可下，完整声明、SHA-256 与复现步骤见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：
  - `lib/arm64-v8a/libbash.so` —— GNU Bash 5.3，GPL-3.0-or-later
  - `lib/arm64-v8a/libbusybox.so` —— BusyBox 1.38.0，GPL-2.0-only
  - 它们放在 `jniLibs` 并开启 legacy packaging，是因为 Android 10 起 `targetSdk >= 29` 不允许执行自己数据目录里的文件（W^X），唯一可执行自身文件的目录是 `nativeLibraryDir`。
- 图标来自 [Lucide](https://lucide.dev)（ISC License）。
- 功能设计参照了 [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0）与 [Aether 扶摇](https://github.com/Zhou-Shilin/Aether)（GPL-3.0）的能力清单与交互约定，视觉取值参考了后者。本项目为独立实现，未复制上述项目的源代码。
- 应用内「设置 → 关于」页里有指向本仓库、发布页、问题反馈与构建说明的链接，以及上面这些项目的出处。

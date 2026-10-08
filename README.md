# 随行 Suixing

手机端的 AI Agent。给它一个 OpenAI 兼容的模型接口，它就能读你的屏幕、点你的手机、改你的文件、跑你的命令。

> 0.7.0 起项目从「灵枢 / Lingshu」更名为「随行 / Suixing」，包名同步从 `com.lingshu.agent` 改为 `com.suixing.agent`。**包名变了意味着这是一次全新安装**：旧版本请先卸载，会话记录与设置不会迁移。

- 纯 Kotlin + Jetpack Compose，无 NDK、无原生依赖，clone 下来几分钟就能出包
- 目标平台：Android 8.0 (API 26) 及以上
- 不做云同步、不做遥测，API Key 只存在本机应用私有目录
- 支持 MCP：任何 MCP 服务器提供的工具都会并进模型可调用的工具表
- 支持技能（Skill）：把写好的操作手册放进手机，模型需要时自己读

## 界面

配色与排版对齐 [Aether 扶摇](https://github.com/Zhou-Shilin/Aether) 的视觉取值（暖黑底 `#151619`、柔紫主色 `#C0AEFF`、米白正文 `#F3F1EC`，浅色侧则是白底 + `#AD7BF9`），布局与交互为本项目自研。几个刻意的选择：

- **不跟随 Material 动态取色**。默认用一套手调的「随行配色」，想要跟壁纸就在「设置 → 外观 → 配色」里切过去。
- **不用阴影**。层级靠三层表面阶梯（`surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh`）加 1dp 发丝描边区分。
- **卡片 20dp 圆角、右侧抽屉导航**。没有底部导航条，会话页的纵向空间全部留给消息；顶栏左上角的按钮拉出抽屉（会话 / 能力 / 文件 / 扩展 / 设置）。
- **edge-to-edge**。状态栏与导航栏透明，内容自己处理 insets；顶栏自带 `statusBarsPadding()`，输入框自带 `navigationBarsPadding()`。
- 会话页空状态是一个径向光晕的品牌标记加四条建议提示词，点一下直接填进输入框。
- 顶栏右侧常驻一枚模型胶囊（显示当前模型名），点开就是分组好的模型切换菜单。
- 顶栏左上角那张脸是当前助手的头像，点开就是助手面板（切换 / 新建 / 编辑）。

## 功能

**对话**
- 多会话、流式输出、**Markdown 渲染**（标题 / 列表 / 表格 / 引用 / 代码块 / 行内样式 / 可点链接）、前台服务保活（切后台不中断）
- 顶栏右侧的模型胶囊是快捷切换器：点开就是所有已勾选的模型，按提供方分组，当前那个带勾
- 推理模型（DeepSeek-R1 系、国产网关的 `reasoning_content`）的思考过程会实时流出来，包在可折叠的「思考过程」块里——**默认展开**，长思考用内层虚拟列表逐行渲染（不会因为几千行把主列表拖慢），思考中自动滚到底，随时可手动收起
- 工具调用就画在回复自己的气泡里：正文下面是这一轮跑的**所有工具合成的一张卡**（头部写「调用 N 个工具」和失败数，每行一个工具，点开看参数、输出与截图），一轮对话是一个整块，不会散成一串独立卡片；只有工具、没有正文的那一轮直接并进上面的气泡，不留空气泡
- **每条回复下方有 token 统计**：入 / 出 / 思考 / 缓存命中 / 耗时，接口没返回用量时这一行不显示
- 支持发送图片（多模态模型可直接看图；用纯文本模型时请保持设置里「模型支持图片输入」关闭，否则带图请求会被接口拒绝）
- 语音输入：输入框左边的麦克风调用系统语音识别，识别结果直接落到输入框
- 语音输出：可选「朗读回复」，每条回复结束后用系统 TTS 读出；模型也能自己调 `speak` 工具主动说话
- **消息可改**：长按任意一条（或点它下面的「修改」）就地编辑。用户消息可以「只保存」也可以「重新发送」——重新发送会从这条起重新问一遍；助手回复只能改文字，不改后续
- **消息分叉**：对最后一条回复点「重新回答」，新答案会作为**另一个候选**追加在同一个问题上，不覆盖旧的；气泡下方的 `‹ 1/2 ›` 来回切，切换只改显示，后面的对话都留着
- 输入框获得焦点时列表自动滚到底，不会出现「键盘弹起来看不到自己刚打的字」

**助手**（顶栏左上角那张脸，点开是助手面板）

每个助手有自己的名字、头像、提示词、模型和工具开关。会话随时可以换助手，换完下一句就用新的人设。所有字段都是**加法**：留空的项跟随「设置」里的当前值，所以只改个名字也能用。

| 字段 | 留空时 |
| --- | --- |
| 名字 / 头像 / 一句话说明 | 只影响显示 |
| 提示词 | 用设置页的系统提示词 |
| 模型 | 用当前模型（也可以钉死某个模型或某个提供方） |
| 温度 | 跟随全局温度（打开「单独设置温度」才用滑块的值） |
| 工具 | 全部可用（在「工具」一节里把不想给的取消勾选） |

面板里点卡片＝切换，点右上角铅笔＝编辑，最后一个助手删不掉。

**三档权限**（对应「能力」页三张卡片）

| 档位 | 获取方式 | 能做什么 |
| --- | --- | --- |
| 无障碍 | 系统设置里打开「随行」无障碍服务 | 读屏（UI 树 dump）、截图、点击、滑动、输入文本 |
| Shizuku | 安装 [Shizuku](https://shizuku.rikka.app/) 并授权随行 | 以 `shell` 身份执行 `am` / `pm` / `input` / `screencap` / `dumpsys` 等，可读写 `Android/data` |
| Root | 设备已 root，且在设置里打开「允许 root 工具」 | `su -c` 执行任意命令 |

三档互相独立、可叠加，没开也不会崩——相关工具会返回「权限不足 + 怎么开」的提示。

**工具审批**
- 自动允许：模型连续调用工具不打断
- 每次询问（默认）：每次调用前弹窗，可选「总是允许」记住该工具
- 禁止：完全不调用工具，只当聊天用

审批弹窗挂在导航栏之上，切到「能力」「文件」「设置」页也会照常弹出；被记住的「总是允许」可在「设置」页一键清除。

## 模型接入

接口只要兼容 OpenAI 的 `/chat/completions` 就能用（DeepSeek、通义、Kimi、OpenRouter、vLLM、Ollama、局域网自建反代都行）。

**多个接口**：一个地址一个「提供方」，设置页顶部用胶囊切换，`新建` 加一个、`删除` 去掉当前这个。切换提供方等于把它设为当前接口——地址、Key、模型一起换过去，不用来回改。

**不用手打模型名**：填好接口地址和 Key，点「获取模型列表」，随行会去请求 `{地址}/models`，把返回的模型按上游厂商分组列出来（聚合反代返回的 `deepseek/deepseek-chat`、`openai/gpt-4o` 会自动归到 `deepseek`、`openai` 两组，没有前缀的归到「其他」）。**可以多选**，每组还能一键全选；勾中的模型才会出现在顶部的模型切换器里，没勾的只留在列表里不碍事。

- **地址写 `.../v1` 就行**，不用补 `/chat/completions`；补了也认。
- **局域网自签名证书**：如果你的反代是 `https://192.168.x.x:端口/v1` 这类自签证书，安卓会以 `Trust anchor for certification path not found` 拒绝。打开「设置 → 模型 → 允许自签名证书」即可放行（只对模型请求生效，且只跳过证书校验，不影响别的）。更省事的办法是把地址写成 `http://`（应用已允许明文流量），或者在手机上把该证书装进系统信任库。
- **报错说人话**：401 会说「API Key 不对」，404 会提醒地址要以 `/v1` 结尾，连不上会说「检查地址和网络」，证书问题会直接告诉你打开上面那个开关。

**生成参数**（设置页「生成参数」卡片）——这几个都是加法：留默认就跟随接口自己的行为。

| 项 | 作用 |
| --- | --- |
| 显示思考过程 | 只控制画不画。关掉不代表不让模型思考，思考强度照旧生效 |
| 思考强度 | 对应接口的 `reasoning_effort`，四档：默认 / 低 / 中 / 高。不支持的模型会忽略这一项 |
| 上下文条数 | 每次请求只带最近多少条消息，默认不限。历史很长时调小可以明显提速、省钱 |
| Top P | 核采样，1.00 表示不过滤，一般不用动 |
| 最大输出 tokens | 留空 = 不限。有些接口不给上限会中途截断，填上更稳 |

温度在「模型」卡片里；每个助手还可以单独覆盖温度与提示词。

## 内置工具（38 个）

- 系统（8）：`device_info` `list_apps` `open_app` `open_url` `clipboard_read` `clipboard_write` `memory_write` `memory_read`
- 文件（9）：`read_file` `write_file` `list_dir` `make_dir` `stat_path` `move_path` `delete_path` `find_files` `search_in_files`
- Shell（2）：`run_shell` `app_action`（start / force_stop / clear_data / uninstall / disable / enable / info / permissions / install）
- UI 自动化（11）：`ui_dump` `ui_screenshot` `ui_tap` `ui_long_press` `ui_swipe` `ui_click_text` `ui_type` `ui_type_into` `ui_key` `ui_current_app` `ui_wait`
- 技能（3）：`skill_list` `skill_read` `skill_save`
- 网络（3）：`web_fetch` `web_download` `web_search`
- 语音（2）：`speak` `stop_speaking`
- MCP（数量取决于你接的服务器）：每个远程工具注册为 `mcp__服务器名__工具名`

文件工具的默认工作目录是 `Android/data/com.suixing.agent/files`（应用外部私有目录，不需要任何存储权限）；`~` 即该目录，绝对路径原样使用。想访问全盘就开 Shizuku 或 Root。

## 联网

三个网络工具让模型自己查资料，而不是靠记忆编：`web_fetch` 抓网页（HTML 自动转纯文本，带编码嗅探）、`web_download` 存文件到本机（上限 64 MB）、`web_search` 搜网页。

搜索默认走内置的 DuckDuckGo 抓取，零配置但容易被限流。想要稳定就在「设置 → 联网搜索」里填一个 Tavily 兼容接口（地址 + Key），`web_search` 会自动改走它。

## MCP（外挂工具）

「MCP」在「扩展」页的第一个标签下，可以接入任意 [Model Context Protocol](https://modelcontextprotocol.io/) 服务器，接入后它提供的工具会和内置工具并列出现在模型面前，同样走审批闸门。两种传输方式：

| 传输 | 填什么 | 适用 |
| --- | --- | --- |
| `stdio` | 一条启动命令，如 `python /sdcard/mcp/server.py` | 服务器是个本地进程，随行把它当子进程跑，用管道收发换行分隔的 JSON-RPC |
| `http` | 服务器地址，如 `http://127.0.0.1:3000/mcp` | 服务器跑在 Termux、电脑或公网上（兼容 `streamable HTTP` 与 SSE 响应） |

关于 stdio 的现实约束：安卓上子进程以应用自己的身份运行，读不到 Termux 的私有目录（`/data/data/com.termux` 是 0700）。所以手机本机跑 stdio 服务器有两条实用路径——把脚本放在应用能读的位置（如 `/sdcard`），或者设备已 root 时用 `su -c "node /data/local/tmp/server.js"` 借 root 跑。跑在 Termux 里的服务器更推荐走 `http`。

「测试」按钮会用一次性连接做完整握手（initialize → tools/list）并报出服务名、协议版本与工具数量，不会注册任何东西。

## 技能（外挂知识）

技能是写给模型看的操作手册——一段固定流程、一套规范、某个 App 的用法。和 MCP 的区别在于：MCP 给的是**工具**，技能给的是**怎么做**。

放在 `Android/data/com.suixing.agent/files/skills/` 下，两种形态都认：

```
skills/
├── daily-checkin/SKILL.md      # 一个文件夹一个技能
└── 修图规范.md                  # 或直接放一个 .md 文件
```

`SKILL.md` 的格式是 YAML frontmatter + 正文：

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

「扩展 → 技能」页可以查看全文、删除，或用「导入技能」从系统文件管理器选一个文件夹批量导入（自动跳过超过 2 MB 的文件）。模型也可以自己用 `skill_save` 把刚摸索出来的流程存成技能。

## 构建

需要 JDK 17 与 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
# 本地
gradle :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk

# 单元测试（MCP 协议层与 stdio 传输的真实往返、HTML 转纯文本、Markdown 块级与行内解析、接口报错翻译、提供方与模型分组、消息分叉的选取逻辑，无需设备）
gradle :app:testDebugUnitTest
```

仓库刻意不带 `gradle-wrapper.jar`，CI 用 `gradle/actions/setup-gradle` 指定 8.11.1，本地用任意 8.11+ 的 Gradle 发行版即可。

推送到 GitHub 后 `.github/workflows/android.yml` 会自动构建 debug 与 release 两个 APK 并上传 artifact。release 用仓库内的 `app/sideload.keystore` 自签（alias / 密码均为 `lingshu`）——这是自用侧载包，不是上架包。

## 目录结构

```
app/src/main/java/com/suixing/agent/
├── MainActivity.kt              入口，主题与导航
├── SuixingApplication.kt        初始化 Core
├── core/Core.kt                 全局单例（settings / chats / capabilities / skills / speaker / tools / mcp）
├── data/                        设置（DataStore）、会话存储（JSON 文件）、助手、MCP 配置
├── capability/                  能力探测：无障碍状态、Shizuku 绑定、root 探测、Shell 执行
├── service/                     无障碍服务、前台保活服务
├── tools/                       工具层：Tool 契约 + ToolRegistry + 七组工具
├── mcp/                         MCP 客户端：协议、stdio/http 传输、连接管理
├── skills/SkillStore.kt         技能库：扫描 / 解析 frontmatter / 导入导出
├── voice/Speaker.kt             系统 TTS 封装（朗读回复、speak 工具）
├── llm/LlmClient.kt             OpenAI 兼容流式客户端（SSE + tool_calls 组装、思考流、模型列表、报错翻译）
├── agent/AgentEngine.kt         主循环：模型 → 工具 → 模型，含审批闸门
└── ui/
    ├── theme/                   配色（手调 M3 角色）、排版、圆角
    ├── components/              品牌标记、卡片、顶栏、分段控件、Markdown 渲染器
    ├── assistant/               助手面板与助手编辑页
    └── chat / capability / files / extension / settings   五个界面
```

## 首次使用

1. 安装 APK，打开「随行」
2. 「设置」→ 填接口地址（如 `https://api.deepseek.com/v1`）、API Key → 点「获取模型列表」，勾选要用的模型（可多选，按上游厂商分组）→「测试连接」→「保存」（局域网自签证书见上一节）
3. 「能力」→ 打开无障碍服务（不开也能聊，但用不了 UI 自动化）
4. 可选：装 Shizuku 并授权，获得 ADB 级能力
5. 回「会话」，直接说人话，例如「打开微信，给文件传输助手发一条『你好』」

## 已知限制

- 无障碍截图需要 Android 11 (API 30) 及以上；低版本 `ui_screenshot` 会返回失败提示
- 国产 ROM 可能把第三方应用加进无障碍黑名单（vivo/OriginOS 等），此时无障碍工具不可用
- 安卓 16+ 的「高级保护模式」会拦截未知来源安装并限制无障碍，请勿开启
- Shizuku 在设备重启后需要重新启动（无线调试或 root 方式）

## 致谢

功能设计参照了 [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0）与 [Aether 扶摇](https://github.com/Zhou-Shilin/Aether)（GPL-3.0）的能力清单与交互约定，CI 约定参考了后者的构建流程。本项目为独立实现，未复制上述项目的源代码。

## 许可证

MIT，见 [LICENSE](LICENSE)。

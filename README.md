# 灵枢 Lingshu

手机端的 AI Agent。给它一个 OpenAI 兼容的模型接口，它就能读你的屏幕、点你的手机、改你的文件、跑你的命令。

- 纯 Kotlin + Jetpack Compose，无 NDK、无原生依赖，clone 下来几分钟就能出包
- 目标平台：Android 8.0 (API 26) 及以上
- 不做云同步、不做遥测，API Key 只存在本机应用私有目录
- 支持 MCP：任何 MCP 服务器提供的工具都会并进模型可调用的工具表
- 支持技能（Skill）：把写好的操作手册放进手机，模型需要时自己读

## 功能

**对话**
- 多会话、流式输出、Markdown 渲染、前台服务保活（切后台不中断）
- 支持发送图片（多模态模型可直接看图；用纯文本模型时请保持设置里「模型支持图片输入」关闭，否则带图请求会被接口拒绝）

**三档权限**（对应「能力」页三张卡片）

| 档位 | 获取方式 | 能做什么 |
| --- | --- | --- |
| 无障碍 | 系统设置里打开「灵枢」无障碍服务 | 读屏（UI 树 dump）、截图、点击、滑动、输入文本 |
| Shizuku | 安装 [Shizuku](https://shizuku.rikka.app/) 并授权灵枢 | 以 `shell` 身份执行 `am` / `pm` / `input` / `screencap` / `dumpsys` 等，可读写 `Android/data` |
| Root | 设备已 root，且在设置里打开「允许 root 工具」 | `su -c` 执行任意命令 |

三档互相独立、可叠加，没开也不会崩——相关工具会返回「权限不足 + 怎么开」的提示。

**工具审批**
- 自动允许：模型连续调用工具不打断
- 每次询问（默认）：每次调用前弹窗，可选「总是允许」记住该工具
- 禁止：完全不调用工具，只当聊天用

审批弹窗挂在导航栏之上，切到「能力」「文件」「设置」页也会照常弹出；被记住的「总是允许」可在「设置」页一键清除。

## 内置工具（33 个）

- 系统（8）：`device_info` `list_apps` `open_app` `open_url` `clipboard_read` `clipboard_write` `memory_write` `memory_read`
- 文件（9）：`read_file` `write_file` `list_dir` `make_dir` `stat_path` `move_path` `delete_path` `find_files` `search_in_files`
- Shell（2）：`run_shell` `app_action`（start / force_stop / clear_data / uninstall / disable / enable / info / permissions / install）
- UI 自动化（11）：`ui_dump` `ui_screenshot` `ui_tap` `ui_long_press` `ui_swipe` `ui_click_text` `ui_type` `ui_type_into` `ui_key` `ui_current_app` `ui_wait`
- 技能（3）：`skill_list` `skill_read` `skill_save`
- MCP（数量取决于你接的服务器）：每个远程工具注册为 `mcp__服务器名__工具名`

文件工具的默认工作目录是 `Android/data/com.lingshu.agent/files`（应用外部私有目录，不需要任何存储权限）；`~` 即该目录，绝对路径原样使用。想访问全盘就开 Shizuku 或 Root。

## MCP（外挂工具）

「MCP」在「扩展」页的第一个标签下，可以接入任意 [Model Context Protocol](https://modelcontextprotocol.io/) 服务器，接入后它提供的工具会和内置工具并列出现在模型面前，同样走审批闸门。两种传输方式：

| 传输 | 填什么 | 适用 |
| --- | --- | --- |
| `stdio` | 一条启动命令，如 `python /sdcard/mcp/server.py` | 服务器是个本地进程，灵枢把它当子进程跑，用管道收发换行分隔的 JSON-RPC |
| `http` | 服务器地址，如 `http://127.0.0.1:3000/mcp` | 服务器跑在 Termux、电脑或公网上（兼容 `streamable HTTP` 与 SSE 响应） |

关于 stdio 的现实约束：安卓上子进程以应用自己的身份运行，读不到 Termux 的私有目录（`/data/data/com.termux` 是 0700）。所以手机本机跑 stdio 服务器有两条实用路径——把脚本放在应用能读的位置（如 `/sdcard`），或者设备已 root 时用 `su -c "node /data/local/tmp/server.js"` 借 root 跑。跑在 Termux 里的服务器更推荐走 `http`。

「测试」按钮会用一次性连接做完整握手（initialize → tools/list）并报出服务名、协议版本与工具数量，不会注册任何东西。

## 技能（外挂知识）

技能是写给模型看的操作手册——一段固定流程、一套规范、某个 App 的用法。和 MCP 的区别在于：MCP 给的是**工具**，技能给的是**怎么做**。

放在 `Android/data/com.lingshu.agent/files/skills/` 下，两种形态都认：

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

# 单元测试（MCP 协议层与 stdio 传输的真实往返，无需设备）
gradle :app:testDebugUnitTest
```

仓库刻意不带 `gradle-wrapper.jar`，CI 用 `gradle/actions/setup-gradle` 指定 8.11.1，本地用任意 8.11+ 的 Gradle 发行版即可。

推送到 GitHub 后 `.github/workflows/android.yml` 会自动构建 debug 与 release 两个 APK 并上传 artifact。release 用仓库内的 `app/sideload.keystore` 自签（alias / 密码均为 `lingshu`）——这是自用侧载包，不是上架包。

## 目录结构

```
app/src/main/java/com/lingshu/agent/
├── MainActivity.kt              入口，主题与导航
├── LingshuApplication.kt        初始化 Core
├── core/Core.kt                 全局单例（settings / chats / capabilities / skills / tools / mcp）
├── data/                        设置（DataStore）、会话存储（JSON 文件）、MCP 配置
├── capability/                  能力探测：无障碍状态、Shizuku 绑定、root 探测、Shell 执行
├── service/                     无障碍服务、前台保活服务
├── tools/                       工具层：Tool 契约 + ToolRegistry + 五组工具
├── mcp/                         MCP 客户端：协议、stdio/http 传输、连接管理
├── skills/SkillStore.kt         技能库：扫描 / 解析 frontmatter / 导入导出
├── llm/LlmClient.kt             OpenAI 兼容流式客户端（SSE + tool_calls 组装）
├── agent/AgentEngine.kt         主循环：模型 → 工具 → 模型，含审批闸门
└── ui/                          会话 / 能力 / 文件 / 扩展（MCP + 技能）/ 设置 五个界面
```

## 首次使用

1. 安装 APK，打开「灵枢」
2. 「设置」→ 填接口地址（如 `https://api.deepseek.com/v1`）、API Key、模型名 → 点「测试连接」→「保存」
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

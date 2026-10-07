# 灵枢 Lingshu

手机端的 AI Agent。给它一个 OpenAI 兼容的模型接口，它就能读你的屏幕、点你的手机、改你的文件、跑你的命令。

- 纯 Kotlin + Jetpack Compose，无 NDK、无原生依赖，clone 下来几分钟就能出包
- 目标平台：Android 8.0 (API 26) 及以上
- 不做云同步、不做遥测，API Key 只存在本机应用私有目录

## 功能

**对话**
- 多会话、流式输出、Markdown 渲染、前台服务保活（切后台不中断）
- 支持发送图片（多模态模型可直接看图）

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

## 内置工具（30 个）

- 系统（8）：`device_info` `list_apps` `open_app` `open_url` `clipboard_read` `clipboard_write` `memory_write` `memory_read`
- 文件（9）：`read_file` `write_file` `list_dir` `make_dir` `stat_path` `move_path` `delete_path` `find_files` `search_in_files`
- Shell（2）：`run_shell` `app_action`（start / force_stop / clear_data / uninstall / disable / enable / info / permissions / install）
- UI 自动化（11）：`ui_dump` `ui_screenshot` `ui_tap` `ui_long_press` `ui_swipe` `ui_click_text` `ui_type` `ui_type_into` `ui_key` `ui_current_app` `ui_wait`

文件工具的默认工作目录是 `Android/data/com.lingshu.agent/files`（应用外部私有目录，不需要任何存储权限）；`~` 即该目录，绝对路径原样使用。想访问全盘就开 Shizuku 或 Root。

## 构建

需要 JDK 17 与 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
# 本地
gradle :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

仓库刻意不带 `gradle-wrapper.jar`，CI 用 `gradle/actions/setup-gradle` 指定 8.11.1，本地用任意 8.11+ 的 Gradle 发行版即可。

推送到 GitHub 后 `.github/workflows/android.yml` 会自动构建 debug 与 release 两个 APK 并上传 artifact。release 用仓库内的 `app/sideload.keystore` 自签（alias / 密码均为 `lingshu`）——这是自用侧载包，不是上架包。

## 目录结构

```
app/src/main/java/com/lingshu/agent/
├── MainActivity.kt              入口，主题与导航
├── LingshuApplication.kt        初始化 Core
├── core/Core.kt                 全局单例（settings / chats / capabilities / tools）
├── data/                        设置（DataStore）与会话存储（JSON 文件）
├── capability/                  能力探测：无障碍状态、Shizuku 绑定、root 探测、Shell 执行
├── service/                     无障碍服务、前台保活服务
├── tools/                       工具层：Tool 契约 + ToolRegistry + 四组工具
├── llm/LlmClient.kt             OpenAI 兼容流式客户端（SSE + tool_calls 组装）
├── agent/AgentEngine.kt         主循环：模型 → 工具 → 模型，含审批闸门
└── ui/                          会话 / 能力 / 文件 / 设置 四个界面
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

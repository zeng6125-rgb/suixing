# 聊天呈现 B 方案实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 用真实消息列表、每会话书签和首次就位后呈现替代整页正文的分批挂载/事后纠偏，同时修复选区关闭与设置风格。

**Architecture:** 书签采用独立 UI 偏好和串行持久化，列表状态按会话隔离。消息使用稳定 Lazy key，有界预取和后台 Markdown 准备；分页依靠原生 key 锚定，入场只发生在有效首屏之后。

**Tech Stack:** Kotlin 2.4.21、Compose Foundation 1.12.1、DataStore Preferences、协程。

**Spec:** `docs/superpowers/specs/2026-10-27-chat-viewport-design.md`，用户已明确批准 B，并允许 P4 必要内部定位适配。

## Global Constraints

- 只编译 Release；不运行 Debug 编译/测试/打包，不出 APK，不安装、不操作手机，不取日志数据。
- 保留工作树里既有 534 改动，不改聊天日志/恢复副本协议、缓存容量 8、超长表格折叠规则。
- P4 外观和交互等用户指示；只更换定位所需的数据适配。
- 绿点、输入法、折叠与抽屉收起规格保持；已读要等目标正文实际呈现。
- 本轮不自动提交、push 或发布；不按字符串出现与否删除 import。
- 用户已授权完整实施，不在任务间重复要求审批；唯一权威进度记录仍为 WORK_MEMORY。

## Review Focus

- 准备中的会话被切走或暂停，默认位置不能覆盖有效书签。
- 仅 live/空会话能完成准备；逐 token 增量不反复取消入场。
- 深历史超出缓存仍能读回目标；目标删除/分支改变有明确回落。
- live 转定稿、状态项增删时，历史阅读不能突然回底。
- 短点关闭选区后迟到的“全选”不能出现，输入框焦点不受误清。

### Task 1: 唯一阅读书签及持久化

**Files:** 新建 `data/ConversationViewports.kt`、`ui/chat/ConversationViewport.kt`；修改 `core/Core.kt`。
**Interfaces:** `ViewportBookmark` 保存稳定 key/消息 ID、像素偏移、窗口轮数、布局指纹；`ConversationViewports` 提供 ready/get/save/forget；UI 控制器只采集有效布局。

- [x] 实现独立偏好、内存立即更新与串行 IO 写入，删除会话清理。
- [x] 定义 key→位置、书签失效和 followLatest 的单一恢复规则。
- [x] 仅运行 `:app:compileReleaseKotlin --offline --console=plain` 检查生产代码。

### Task 2: 消息列表、分页与会话呈现

**Files:** 修改 `chat/ChatScreen.kt`、`chat/ChatScroll.kt`、`chat/ChatViewModel.kt`、`chat/ConversationReadObserver.kt`、`ui/SuixingApp.kt`；按职责新增小型 UI 辅助文件。
**Interfaces:** 按会话的 LazyListState、唯一 viewport 快照回调、presentedConversationId；后台解析准备只覆盖首屏/相邻项。

- [x] 去除整页 transcript item、filledRows 和显示后 P1 scrollBy 纠偏，使用稳定消息 key/contentType。
- [x] 使用实际依赖提供的有界缓存窗口，准备目标及相邻行 Markdown。
- [x] 首次有效位置后短入场；冷热/深历史/live-only/空会话均完成准备；陈旧任务取消。
- [x] 保存离开/暂停位置，绿点仅在正文可见后已读。
- [x] P4 只适配可见项高亮输入与按稳定 key 跳转的内部定位。
- [x] 编译 Release，逐项审查恢复、分页和 live 定稿交接。

### Task 3: 显式选区生命周期

**Files:** 修改 `chat/SelectionFocus.kt`、`chat/ChatRows.kt`、`chat/ChatScreen.kt`。
**Interfaces:** 每个选区 owner 登记 clear 回调及有效选区读取；正文菜单只在有效选区期间提供动作。

- [x] 使用 SelectionState.clear，保留同一回答跨块选择，过滤结束后的菜单数据。
- [x] 在空白短点、换会话、销毁时释放 owner；保留编辑框独立焦点。
- [x] 编译 Release，核对框架公开 API 与取消顺序。

### Task 4: 设置控件和分隔规则

**Files:** 修改 `components/SettingControls.kt`、`components/SettingsStyle.kt` 及实际有重复分隔的设置子页。

- [x] 统一 Switch 与低饱和圆角分段选择；提供选择语义和足够触控区域。
- [x] 逐处删除无语义的行后分隔，用组标题/留白区分；保留必要列表或危险操作边界。
- [x] 更新 `docs/settings-style.md` 并编译 Release。

### Task 5: 最终核验与记录

- [x] 独立复核本轮差异，重点检查 Review Focus 与深历史、分页和导航依赖。
- [x] 修复发现的实质问题，再编译 Release；运行 diff 格式和范围核对。
- [x] 更新设计状态、issue50 记录、唯一工作记忆；明确源码完成与尚未真机验收的区别。

## 状态（2026-10-27 交接时补记）

- Task 1–4 源码已实现并通过 `:app:compileReleaseKotlin --offline`（Codex 中途停下，未回填勾选；本次按实际代码核对后勾上）。
- 交接复核另修：`ChatScreen.kt` 三处仍把列表描述成“整页装进单个 item”的过期注释（结构已改逐消息项）。
- **未验收**：全部真机行为（切换动画、位置、P1 原位、P2 菜单、设置观感、掉帧）。没有真机轨迹，不得据源码宣称体验通过。
- **未覆盖**：`presentationKeys` / `bookmarkRow` 无单测；本项目无 Release 单测任务，而用户已禁止 Debug 任务 ⇒ 新逻辑当前无可执行的测试手段。
- **已知残留死代码**：`TurnMarks.topFedIndex` 在逐消息项结构下已无生产调用点（仅注释与 `TurnMarksTest` 引用），未删除。
- R4（恢复副本 2s/3s）仍是死分支，本轮未动。

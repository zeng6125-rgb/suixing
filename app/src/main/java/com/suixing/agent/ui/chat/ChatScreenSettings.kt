package com.suixing.agent.ui.chat

import com.suixing.agent.data.AppSettings

/**
 * 「这两份设置对本页是等价的吗」—— 只比较 **ChatScreen 会读** 的字段，用来让写入无关字段
 * （例如其它页面的开关）不触发本页重组。
 *
 * **实现是黑名单**（issue #56 R17 记录结论）：把三个「读了但不影响重组」的字段抹掉再 `==`。
 * 新增「被读取且应影响重组」的字段自动纳入比较（正确方向）；新增「被读取但不该影响重组」
 * 的字段会漏。这条风险由 `ChatSettingsEquivalenceTest` 兜着 —— 它对这类新字段显式断言，
 * 加字段时测试会失败并提醒补进黑名单。所以这里不需要改成白名单。
 */
/**
 * 两份设置**在聊天页看来**是否等价。
 *
 * 本页按值订阅整份 [AppSettings]，而整份里任何一个字段变了都会让它整页重新跑一遍。这里抹平的
 * 是「本页一个都不读、但别处写得挺勤」的四个字段：
 *
 * - `consoleWidthDp` / `consoleHeightDp` / `floatingConsole`：悬浮控制台拖动结束时写
 *   （`service/FloatConsoleService.kt:912`、`:924`），而控制台正是盖在聊天页上拖的。
 *
 * **`asrModel` 不在这个名单里，别再加回来**：本页自己不读它，但把整份 `settings` 传给了
 * `VoiceOverlay`（`ChatScreen.kt:1784`），那里 `VoiceOverlay.kt:109` 用它判断「转写是否已配置」、
 * `:325-326` 直接显示模型名。抹平它 = 用户改完转写模型回来，语音卡仍显示旧模型或「未配置」，
 * 而且不会自愈。**判断"本页不读"必须连子组合一起看**，只看本文件会漏（这条就是这么发现的）。
 *
 * 抹平之后这些写入不再触发整页重组；本页真正读的字段（`assistants`、`providers`、
 * `showReasoning`、`bubbleWidthFraction`…）照常下发。
 *
 * **黑名单式**，故意不做白名单：将来新增字段若忘了写进来，最坏只是少省一次重组，
 * 绝不会让页面读到过期值。
 */
internal fun AppSettings.sameForChat(other: AppSettings): Boolean =
    copy(consoleWidthDp = 0, consoleHeightDp = 0, floatingConsole = false) ==
        other.copy(consoleWidthDp = 0, consoleHeightDp = 0, floatingConsole = false)

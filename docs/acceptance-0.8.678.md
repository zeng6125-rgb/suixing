# 0.8.678 / vc699 体验验收

这一批把品牌统一到 JAgent、默认配色换成 JAgent 配色（原 Claude 暖纸色 / 陶土橙），重做内置终端页，并收口了转写位置的几处跳变与追底回归。0.8.676 / 0.8.677 只做过装机、未提交，本包是三者累积后的落库版本。安装完成不等于手感验收通过。

## 本包变化

- **品牌与配色**：应用名与全仓「随行」字样统一为 JAgent（用户可见文案、默认系统提示词、通知、悬浮控制台、代码注释与测试共 88 处；`app_name` 本就是 JAgent）。`AppearancePalette` 移除 `id=0` 的墨绿项；`id=2` 由「Claude」更名为「**JAgent 配色**」并作为默认，老设置存着 0 由 `fromId` 落到它。墨绿色板降级为「跟随壁纸」动态取色的基底，不再是独立选项或品牌色。代码标识符与存档 id 不变，已有设置不丢。
- **会话列表状态色**：完成态 `CompletionDot` 从固定绿 `Tone.OK` 改为 `MaterialTheme.colorScheme.primary`，与运行态弧、选中态同一色；`Tone.OK` 的语义绿只留给设置页「成功 / 警告」对照。悬浮球 `FloatConsoleService.paletteFor` 整份 Palette 取自 `AppearancePalette.fromId(paletteMode).scheme(dark)`，边缘光 `TouchFx` 的 `FX_COLOR_BRAND` 固定墨绿改为按 themeMode / paletteMode 取 primary，随设置刷新。不再遗留原墨绿风格。
- **终端页重做**：按参考图极简浅色版式——顶栏、单个「Android」纯标识、复制 / Ctrl-C 中断 / 清空三个动作、等宽转录、单行提示符、底部发丝线 + 键条。删掉 Debian 假入口与它的 Toast。键条按参考图顺序 `Esc · Tab · Ctrl · Alt · ← ↑ ↓ → · 键盘`，方向键用真箭头符号；Ctrl / Alt 改为**粘滞修饰键**（点亮作用于下一次按键、按完熄灭），字节按 xterm 标准（Alt = ESC 前缀、Ctrl + 方向 = `CSI 1;5 A/B/C/D`，无标准序列的退回裸字节），`TerminalUiTest` 12 项钉住字节与布局。新增 `ic_keyboard.xml`，替换被误用作键盘按钮的悬浮球羽毛 `ic_console_feather`。
- **终端顺序 / 宽度 / 空隙**：`sh -i` 自带 PS1 会把整条 cwd 吐进转录，与 UI 自画提示符叠成倒序；cpp 注入 `PS1=$ `，UI 把输入行移到转录之后（输出自旧到新往下流、新提示符落在最后一行下面）。转录去掉 `horizontalScroll`，长行按视口宽度折行；PTY 列数按视口宽 ÷ 等宽字宽计算并 `resize()`，不再写死 80×24（`terminalSize()` 钳到 20–240 列 / 6–120 行）。输入行固定 24dp 行盒 + 24sp 行高，先英文后中文不再撑高行盒。删掉 Scaffold 之外多叠的一次 `navigationBarsPadding()`，键条收紧到 40dp 最小高、6dp 纵向 padding。
- **转写位置**：恢复流式追底（`viewportPlaced` 与基线一致，`following()` 时无条件 pin 到末尾）；`NavigationAnchorHandoffTest` 新增 2 项钉住「远离末尾仍 pin」与「不可前滚时不 pin」，另 1 项钉住「真滚动丢弃旧锚点、从新几何重捕获」，共 7 项。新轮次收口跳变的治本修复在 `ChatViewModel.endSession`：与 `liveRunId = ""` **同一次** `_state.update` 落下 `runningHere = false`，消除「runId 已空但 live item 仍以空 parts 挂着」先塌再弹的中间帧。滚动窗口仍保留「位置变化即清锚点」，避免把读者按旧 Y 拉回、与手势冲突。
- **清理**：删 6 个确证死声明（`SCRUBBER_SELECT_MS`、`haloBand`、`ContextBreakdownDialog` 及行助手、`TypingDots`、`rememberTypedTextFromBirth`、`popupNudgeCentred`）、孤儿 import 与 22 行空白；修正 4 类误导注释（漂移行号、谎称 `MAX_SEARCH_BYTES` 已删、失效 A/B 开关 KDoc、与代码相反的缩放 / 阴影说明）。抽屉打开算法经查证 `a4e9495` 已充分优化，未投机重做。

## 手动回归

1. 「设置 → 外观 → 配色」只剩「跟随壁纸 / JAgent 配色 / Codex」三项，默认选中 JAgent 配色；墨绿项不再出现，旧设置也不会停留在它。
2. 会话列表的运行态弧、已完成未读圆点、选中行底色应同色；切换配色后悬浮球面板与边缘光一起变色。
3. 终端页：提示符在转录最后一行之下（不是顶在最上面）；输入 `ls` 等命令后输出往下累积，长路径不再冲出屏幕而是折行；键条下方没有大块空白；点 Ctrl / Alt 再点方向键应发出对应组合序列，点键盘图标唤起 IME；「显示键盘」用的是键盘图标而非羽毛。
4. 中英混排输入框：先打英文再打中文，行高不应抖动。
5. 聊天页：新轮次流式输出时列表持续追底；新轮次完成且手指按着 / 正在滚动时不应出现位置跳变；用户主动滚动后不应被拉回旧位置。

## 验证与安装

同一产品源码经 `gradle-run` 包装执行 `:app:compileReleaseKotlin :app:testReleaseUnitTest --offline`，exit 0。XML 汇总 **264 类 / 1558 项 / 0 失败 / 0 错误 / 0 跳过**；其中 `TerminalUiTest` 12、`NavigationAnchorHandoffTest` 7、`AppearancePaletteTest` 4、`TerminalKeysTest` 3 全绿。新增的 JVM 用例覆盖键条字节、粘滞修饰、尺寸换算、追底 pin 与锚点失效，但不替代真实 Compose 布局、native PTY 或设备帧率测量。

- `:app:assembleRelease --offline` exit 0，589.242 秒（含 native 重建）；四个 ABI 的 `libsuixing_terminal.so` 均在包内，cpp 的 PS1 改动随 .so 生效。
- 产物版本：**0.8.678 / vc699**，包名 `com.suixing.agent`。
- APK 大小：**6,702,133 B**。
- SHA256：`10eb15fbe6dc294cebf961394eb36ea3541b0c6df782b5e69de14d5a4153fe7e`。
- aapt2 版本核对、apksigner V2 验签（证书 `CN=Lingshu`，SHA-256 `9a33761b…aa8ee`）与 16KB 对齐检查均通过；`drawable/ic_keyboard` 在资源表内；`ball_motion` 48 帧与源逐字节一致。
- **2026-11-05 19:02:52（Asia/Shanghai）覆盖安装成功**，从 0.8.677/vc698 升级至 0.8.678/vc699；设备 `dumpsys package` 版本与本地一致，设备 `base.apk` 的 SHA256 与上列产物一致。无线调试由 mDNS 重新发现 `192.168.0.102:46723`。

仅做覆盖安装及包信息 / 哈希核对；以上手动回归仍待用户体验确认。本轮不操作手机界面、不采日志 / 截图 / 帧数据。

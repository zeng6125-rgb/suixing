# 随行性能、掉帧与滚动优化实施方案

审查日期：2026-10-23  
审查基线：本地 `main`，HEAD `92a25f3`；`app/build.gradle.kts` 当前为 `0.8.394 / versionCode 415`。  
状态：本文件是交接用实施方案和代码草图；本轮没有改 App 源码、编译、运行测试或操作设备。

## 1. 先读这个

这是 Android + Kotlin + Jetpack Compose 单模块项目。当前源码不是 GitHub 上可确认的版本：本地 `main` 比本地 `origin/main` 跟踪引用领先 35 个提交；本轮网络不可用，GitHub Open issues 列表和 issue 正文都没有实时读取成功。

本地文档状态不一致，开始代码任务前应先核对并修订：

- `README.md` 和 `TODO_NEXT.md` 仍写 0.8.300；`WORK_MEMORY_suixing-agent.md` 的状态摘要写 0.8.325；实际构建脚本是 0.8.394 / vc415。
- `TODO_NEXT.md` 仍把“冻结 feed”写成进行中的 #17 方案；工作记忆记录该方案已被用户否决。
- `docs/scroll-redesign.md` 基线是 0.8.246，只可作为根因/失败方案档案，不可直接照着改当前行号或方案。
- 用户历史明确否决：冻结流式内容、把会动态变高的 item 移出 `LazyColumn`、逐帧写滚动位置纠偏。不要重新引入。
- 用户要求：只出 release；打包、安装、推送需要单独授权；不要擅自操作手机。当前任务只写计划，不触碰这些流程。

本地记录提到 #17、#23、#25、#26、#27、#34 等未关闭或未验证项，#35/#36 仍缺体感/帧数据。**这些不是本轮实时验证的 GitHub 状态**，恢复网络后先逐项刷新 issue，再更新本节。

## 2. 当前代码证据与已有优化

不要从头重做下面已经落地的路径；先测有没有回归：

- #17：滚动/流式路径集中在 `ChatScreen.kt`、`ChatScroll.kt`、`LivePinState.kt`。当前包含 `pinnedLiveHeight`、pin 状态机和布局高度修饰符；每帧滚动纠偏已被撤掉。现有 `pin.log` 与 `tools/verify_device.sh` 用于复现后取证。
- #26：导航条当前用延迟读取 `derivedStateOf`，避免滚动时重组整个 `ChatScreen`；旧边缘光 `Path.op` 热路径已从当前代码移除。代码变化不等于设备掉帧已解决。
- #27：`Surface(border)` 曾被怀疑，但历史复核没有证据证明它是 12–20ms 卡帧的根因；测量、布局、文字排版或首次进入视野的成本仍待帧轨迹区分。
- #35/#36：会话窗口读取、流式期缓存操作、wire 构建/裁剪等已有优化。不要把整个 Agent loop 一股脑迁到 `Dispatchers.Default`；网络回调、工具执行、事件顺序各有线程约束。
- #38：SSE 多行 JSON 括号配平；流式正文使用 growable buffer 并避免正文双份存储；正文/思考共享增量数据结构；Markdown 定稿块有进程级缓存；截图有降采样与小容量 LRU。
- #39/#40：图片计数和新会话存储格式路径近期已修改，应作为回归检查对象，避免把存储/统计的收益改回去。
- `groupRows` 的重复扫描、`Usage.of` 多遍扫描、会话列表排序重算已有 memo/单遍优化。

## 3. 执行顺序

### P0 — 对齐文档与 issue 基线（不改业务代码）

1. 联网后刷新 GitHub issues；把“Open / Closed / 仅待设备验证”分开记录，附上更新时间和当前源码 HEAD。
2. 更新 `README.md`、`TODO_NEXT.md`、`WORK_MEMORY_suixing-agent.md` 的当前版本与 #17 状态；旧方案保留为历史记录并标明“不要直接套用”。
3. 把当前本地分支是否已推送、性能优化提交是否进入发布版分开写清楚。静态审查不能代表用户装机版本。

### P1 — 先测基线，再决定是否改滚动

固定同一台设备、同一刷新率、同一安装包；每场景做 3 次手指操作，记录系统版本、刷新率、热状态、样本大小和帧耗时 p50/p90/p95、janky frame 比例。60Hz 的单帧预算约 16.7ms；120Hz 约 8.3ms。不能把不同设备或热状态下的结果直接比较。

场景：

1. 12 / 36 / 100+ 轮会话的慢速滚动与快速滚动；
2. 10k 字符以上流式正文、长思考、Markdown 代码块/表格/未闭合代码围栏；
3. 流式过程中停在历史中、继续输出、手动滑回最新、模型结束；
4. 单工具/多工具详情展开收起，思考块展开收起；
5. 切换两条长会话、返回已加载超过 12 轮的会话；
6. 截图滚出屏幕后再滚回来；插件列表单独复现 #34。

用 Perfetto / Frame Timeline 或 `gfxinfo framestats` 区分主线程、Compose measure/layout、draw、RenderThread/GPU、GC/图片解码。聊天滚动应通过手指操作复现；`adb input swipe` 不能代替真实手势体感。性能页 FPS 只用于粗略观察，不作为根因证据，也不要让性能页一直开着充当 profiler。

### P1 — #17 用现有 pin 痕迹判因

复现后复制性能页的“流式上翻痕迹”，按下面分支处理：

| 痕迹 | 下一步 |
|---|---|
| 没有 `arm` 或出现 `arm-timeout` | 查 `natural` 高度何时量到、pin 信号是否捕获最新会话/流式状态、布局更新时序。修复必须证明状态变化可触发重新判定。 |
| `arm` 后、`release` 前 `drift` 明显非 0，且期间无错误/重试提示 | 用帧轨迹看流式 item、前缀 item、输入区/insets 哪个发生布局变化；只改证据对应的那条链。 |
| `drift≈0`，但释放时用户仍看到跳动 | 检查 `runEnd` / `atBottom` 释放、状态行消失、输入区高度与回底时序；不要再调 pin 捕获。 |
| 漂移只伴随多行错误/重试提示 | 先确认是否命中已记录的状态行高度例外；错误原因不得为了固定高度而被隐藏或截断。 |

修改目标优先限于 `ChatScreen.kt` 中 pin/释放状态与 `liveRowLagHeight`，以及 `LivePinState.kt` 的纯状态转移。每次只改一个条件，保留现有 `pin.log` 判读能力。禁止逐帧 `dispatchRawDelta` / `scrollToItem` 对账、冻结 feed 或将动态 item 搬出主列表。

### P1 — #26/#27 按帧阶段定位

- 若 UI 线程长帧：对照具体组合/测量调用，不重复实施已完成的导航条延迟读取或边缘光删除。
- 若 measure/layout 长帧：分开测普通文字、Markdown 长段、代码块、表格、思考块；只优化轨迹中实际超预算的类型。
- 若 draw / RenderThread 长帧：才分析裁剪、描边、图层和截图；针对每个改动做视觉残影回归。
- 不把 `BorderStroke` 或 `graphicsLayer` 先验认定为根因。历史上 layer 改法出现过残影并回退。

### P2 — 若长流式文本 CPU/分配确实突出，再做增量渲染

重点文件：`MarkdownText.kt` 的 `MarkdownCache.blocksFor/settledPrefix/InlineText`；`ChatBlocks.kt` 思考块 `remember(text) { text.split('\\n') }`；`LiveParts.kt` 的 `GrowableText`。当前 Markdown 已避免反复解析已封闭块，但仍有两个有依据的热点候选：

1. `settledPrefix(next)` 每次更新仍需在当前全文上找空行并检查围栏状态；
2. `InlineText` 的 `AnnotatedString` 以整段 `text` 为 key，文本每次增长时会重新扫描正在变化的段；思考块也会重切整份行列表。

不要直接把“代码草图”复制进生产代码；应先保留一遍式 parser 作为正确性参考，并设计 append-only 与 reset 两种入口。append-only 情况只接收新增 delta；编辑、分支切换、重答、历史载入走 reset/full parse。需保留 CommonMark 边界、反斜杠转义、链接、嵌套列表和代码围栏语义。

#### 代码结构草图（说明边界，不是可直接替换的完整 parser）

```kotlin
internal class StreamingMarkdownCache {
    private val source = StringBuilder()
    private val stableBlocks = mutableListOf<MdBlock>()
    private var activeBlockStart = 0
    private var fenceOpen = false

    fun append(delta: String): List<MdBlock> {
        source.append(delta)
        // 实施时：只扫描 delta 更新换行/围栏状态；只有确认块边界后才把稳定前缀
        // 交给 parseMarkdown。未封闭的活动块单独重解析，不能误把代码块内部空行当边界。
        return stableBlocks + parseMarkdown(source.substring(activeBlockStart))
    }

    fun reset(text: String): List<MdBlock> {
        source.setLength(0)
        stableBlocks.clear()
        activeBlockStart = 0
        fenceOpen = false
        return append(text)
    }
}
```

此草图仅说明 API 方向：`append()` 若每次重解析一个极长、没有段落边界的活动块，仍然会累积 O(n²) 工作。只有 profile 证明该场景显著时，才继续把行内语法 scanner 改为可续扫状态机；否则保持现有 parser，避免为了理论复杂度重写 Markdown。

思考块可以同样增加按 delta 追加的行索引，只重建最后一行和新增行；保留绝对行号作为 LazyColumn key，避免新行导致所有可见行重组。完成后的历史内容仍应走现有 Markdown LRU。

#### 性能改动验收

- 10k / 50k 字符正文和思考分别录制 CPU、分配量、GC 次数、p95 帧时间；改前改后使用同设备、同模型节流和相同内容。
- 解析结果与 `parseMarkdown(fullText)` 的结果一致；额外覆盖换行边界、未闭合/闭合围栏、CRLF、列表、表格、行内代码/链接、反斜杠转义。
- 不丢字、不重复字、不改变块顺序；流式末尾转成已完成气泡时无闪跳；滚出再返回时图片/Markdown 缓存仍正确。
- 只有改善超过重复测量噪声且其他滚动场景没有回归，才保留改动。报告 p50/p90/p95 和 jank rate，不只报告均值。

### P2 — #35/#36/#34 单独处理

- #35 切会话：记录点击到列表首帧、正文尾窗读取时间、缓存命中、恢复滚动位置；重点看 `ChatViewModel.openConversation`、`ChatRepository.readTailWindow` 和会话 drawer 分区列表。不要恢复流式期间每 80ms 刷 LRU 的旧路径。
- #36 CPU：确认 `AgentEngine` 的 wire 构建/裁剪/统计确实不在主线程；若仍有主线程长帧，基于 trace 定位剩下的具体工作，不能仅凭“协程很多”调整 dispatcher。
- #34 插件卡顿：记录插件数、卡顿动作和对应帧；分别看 `PluginsTab.kt` 组合/列表与 `PluginStore/PluginRuntime` 的载入，不与聊天列表优化混成一个大 patch。

## 4. 建议的交给实施模型的任务顺序

1. 先刷新 issue 和修正文档状态，不改 App 行为。
2. 拿当前 release 基线与 #17/#26/#27 的复现痕迹；如果拿不到设备数据，只做静态审查结论，不提交猜测性性能 patch。
3. 一次只实施一个被 trace 证明的热点；小 patch、说明预期收益和副作用，再做对应的单元/真机验收。
4. 高风险滚动、Markdown 与线程改动需独立代码复核；完整记录测试、真机版本、场景、帧数据和未覆盖条件。
5. 不在本任务中推送、打包或安装；这些需用户另行授权。

# 滚动核心重设计（#14 / #16 / #17 / #23）

> 状态：**本文档作者只写了这一个 .md，没有修改任何 .kt**。
>
> 基线 = 当前工作树 = HEAD = `4b67dcb`（0.8.246，回滚到 afc3775 / 0.8.242 的滚动实现）。
> 已核对：`ChatScreen.kt` / `ChatScroll.kt` / `ChatBlocks.kt` / `ChatRows.kt` 的 `git hash-object`
> 与 `HEAD:` 逐字节相同，`git status` 只有本文件未跟踪。**本文 75 处 `文件:行号` 引用已逐一比对源码，全部落在有效行且内容相符。**
>
> 写作期间曾有队友 `ui-motion` 并发改动 `ChatScreen.kt`（并越界删过 W3），**该改动随后被丢弃、未进入任何提交**，
> 不影响本文任何行号或结论。经过见 **§9 附录**（那里也记了一条给 lead 的提醒）。
>
> 本文的写法约定：**「已核实」**= 我读过代码/算过数据，能给出文件:行号；**「推断」**= 看起来如此但没有实测证据；
> **「未知」**= 我不知道，需要一次测量。§7 把「未知」集中列出，请不要把 §4 的方案当成「已证明能修好」。

---

## 0. 一句话结论

六版都失败，不是因为「每版细节没调好」，而是因为**有两处结构性缺陷每次都被绕过、从来没被正面处理**：

1. **恢复位置的那段代码在跑到一半时把自己取消了**（`openedPinned` 既是 `LaunchedEffect` 的 key、又在函数体里被写）。
   → 那次「按 key 精确校正」几乎从不执行，实际决定位置的永远是前面那一步 `feedIndexOf` 粗定位，而它依赖 `feedPrefix`。
   这正是 #16「一对话就乱」反复出现的直接原因。**已核实**（§2.1）。
2. **`shownTurns`（分页窗口）切会话就重置回 12 轮**，于是切回来的列表比离开时短，任何「记住 index」的恢复都会被 LazyColumn 钳到边界。
   这条此前从未被记进任何 issue，但它同时打掉了「手工恢复」和「每会话独立 state」两种方案。**已核实**（§2.2）。

所以「每会话独立 LazyListState」（0.8.244）方向本身不错，但它**不是充分条件**，而且 0.8.244 的失败另有两条具体原因（§2.3，均可定位、可修）。
建议的最小方案是「独立 state + 补上这两个前提 + 删掉 8 套手工机制」，见 §4 候选 A。

---

## 1. 现状清点：有几套机制在动滚动位置

### 1.1 会**写**滚动位置的调用点（全部）

| # | 位置 | 调用 | 触发条件 | 归咎 |
|---|---|---|---|---|
| W1 | `ChatScroll.kt:112-114` `pinToBottom()` | `scrollToItem(0)` | 被下面 W2/W3 及 `toBottom()` 调用 | 底锚贴底的基础原语 |
| W2 | `ChatScroll.kt:169-174` 追底 collector | `scrollToItem(0)` | `stickToBottom && !pressed && !pressGuardOn && (index≠0 ‖ offset≠0)` | #21 曾在此与导航条跳转抢列表 |
| ~~W3~~ | ~~`ChatScreen.kt:723-736`~~ | ~~`scrollToItem(target.index, lockOffset)`~~ | **已删除**（`e1a6184`），换成 `scrollTrace("lock-would-correct …")` 只记录 | 删它是因为它伤害正常滑动，详见 §4 #14 |
| W4 | `ChatScreen.kt:885` | `scrollToItem(fedIndexOf(approx), saved.second)` | 切回会话，**粗定位** | **#16 的主要嫌疑** |
| W5 | `ChatScreen.kt:895` | `scrollToItem(target.index, saved.second)` | 切回会话，按 key 精确校正 | #16 的修法，但**基本跑不到**（§2.1） |
| W6 | `ChatScreen.kt:1400` | `animateScrollToItem(fedIndexOf(index - rowOffset))` | 点导航条跳轮次 | #23 / #21 |
| W7 | `ChatBlocks.kt:405-406` | `outer.dispatchRawDelta(px*progress - oldH)` | `RevealHeight` 展开动画**逐帧** | #13 / #22 |
| W8 | `ChatBlocks.kt:417-421` | `outer.dispatchRawDelta(-drift)` | 展开动画收尾按实测位移对账 | #22 |
| W9 | `ChatBlocks.kt:797/823` | `inner.scrollToItem(0)` | **思考链内层**列表自己的追尾 | #10 家族，与主列表无关 |
| W10 | `ChatScreen.kt:872 / 901 / 918 / 1486` | `toBottom()` | 无记忆、key 找不到、流式收尾、发送 | —— |

`scrollBy` 在 7 个文件里**只有 import、没有调用点**（ChatComposer / ChatContext / ChatRows / VoiceOverlay / ChatTopBar / TurnScrubber / ChatScreen）—— 死 import，与本文无关，**建议原样留着**（工作约定：不删无关死代码）。

### 1.2 会**读**状态做判断的地点

| 位置 | 读什么 | 用途 |
|---|---|---|
| `ChatScroll.kt:88` | `!canScrollBackward` | `atBottom`（「回到最新」浮钮是否显示，`ChatScreen.kt:1519`） |
| `ChatScroll.kt:144-149` | `canScrollBackward to isScrollInProgress` | 真回到底才重开追底 |
| `ChatScroll.kt:160-173` | `firstVisibleItemIndex/Offset` | 追底判定载荷 |
| `ChatScroll.kt:188-207` | `firstVisibleItemIndex`、`layoutInfo.visibleItemsInfo`、`firstVisibleItemScrollOffset`、`isScrollInProgress` | **记录** lastSeen / spotKey / spotOffset / spots |
| `ChatScreen.kt:673` | `firstVisibleItemIndex` | → 导航条 `currentTurn`（见 §3） |
| `ChatScreen.kt:701-713` | `layoutInfo`、`firstVisibleItemIndex/Offset`、`canScrollBackward` | `scrollTrace` 诊断写 `files/scroll-jump.log` |
| `ChatScreen.kt:723-724` | `layoutInfo.visibleItemsInfo`、`firstVisibleItemScrollOffset` | 长按守护的锁点 |
| `ChatScreen.kt:734-735` | `layoutInfo` 按 key 找 | 守护纠正 |
| `ChatScreen.kt:813-820` | `layoutInfo` + `firstVisibleItemIndex/Offset` | **记录** anchors |
| `ChatScreen.kt:891-893` | `layoutInfo` 按 key 找 | 恢复精确校正（跑不到） |
| `ChatBlocks.kt:405/417` | `canScrollBackward` | 展开补偿的闸门 |
| `ChatBlocks.kt:798-820` | 内层四件套 | 思考链追尾 |

### 1.3 记住「读到哪」的存储：**同一件事有 5 套并行实现**

| 存储 | 位置 | 写入者 | 读取者 |
|---|---|---|---|
| `spots` | `ChatScroll.kt:49` | `saveSpot`(:94)、`ChatScroll.kt:203` 直写 | `takeSpot`(:102) → `ChatScreen.kt:866` |
| `spotKey`/`spotOffset` | `ChatScroll.kt:56-65` | `ChatScroll.kt:198-199`、`ChatScreen.kt:879-880` | 没人真正用它恢复（只被写） |
| `lastSeen` | `ChatScroll.kt:79-85` | `ChatScroll.kt:197` | 只喂 `saveSpot` |
| `anchors` | `ChatScreen.kt:790` | `ChatScreen.kt:821` | `ChatScreen.kt:866`（**优先于 spots**） |
| 闸门：`openedPinned`(:858) / `restoring`(:52) | —— | 恢复流程 | 恢复流程 / 追底 collector |

加上 W1–W10 的写入点，**共 10 个写入口、4 个位置存储、2 个闸门，全部围绕 1 个共享 `listState`（`ChatScreen.kt:276`）**。
`#16` 评论 2 说「9 套机制」，数量级一致。

### 1.4 还有一套**没人写**的机制，但它一直在动位置

`LazyColumn(state, reverseLayout=true)` 自身在**数据变化时按 item key 重新锚定首可见项**。这不是我们的代码，但它和上面 10 个写入口抢同一份列表 —— #14 的「长按位置会变」、#17 的「流式漂移」都发生在这个层面。
**任何「检测偏移 → 纠正」的方案都是在和它抢**，这也是 #17 已经三次失败的机理。

---

## 2. 根因：三条已核实的结论

### 2.1 #16 的直接原因 —— 恢复代码自我取消（已核实）

`ChatScreen.kt:859-906`：

```kotlin
LaunchedEffect(state.conversationId, itemCount, openedPinned) {   // :859  openedPinned 是 key
    if (openedPinned || itemCount <= 0) return@LaunchedEffect
    openedPinned = true                                          // :861  ← 写了自己这个 key
    ...
    listState.scrollToItem(fedIndexOf(approx), saved.second)      // :885  粗定位（挂起）
    withFrameNanos { }                                           // :886  ← 在这里被取消
    val target = listState.layoutInfo.visibleItemsInfo...         // :891-893  几乎跑不到
    listState.scrollToItem(target.index, saved.second)            // :895  几乎跑不到
```

写 `openedPinned = true` 会让组合重组 → `LaunchedEffect` 的 key 变了 → **旧协程在第一个挂起点（`:885` 的 `scrollToItem` 或 `:886` 的 `withFrameNanos`）被取消**，
然后新实例起来、看到 `openedPinned == true` 立刻 `return`。`finally` 把 `restoring` 放回 `false`。

后果链条（这就是「位置仍不对」）：

1. 「按 key 精确校正」（W5）**基本不执行** —— 也就是说 0.8.240「三步修法」的第三步从来没跑过；
2. 真正决定位置的只剩 W4 的 `fedIndexOf(approx)`，而它**依赖 `feedPrefix`**（`ChatScreen.kt:668/671`），`feedPrefix` 随 `live` 在 0/1 之间变；
3. 取消后 `restoring=false`，`ChatScroll.kt:144-149` 的「到底就重开追底」collector 若认为在底，会立刻 `stickToBottom=true` → **回到底部**，正是 #16 报的现象。

`itemCount` 也在 key 里，流式期间它变 → 同样取消。**所以这条链路在流式会话里几乎必然失效，在空闲会话里则碰巧没事** —— 与用户「没有对话时正常，一对话就乱了」完全吻合。
补充：`openedPinned` 是普通 `remember`（`ChatScreen.kt:858`，非 `rememberSaveable`，理由见 #15），切回同一会话时它确实是 `false`，所以「闸门」本身没错，**错在它在 key 里**。

> 结论：**不需要新的位置机制，只需要让这段已经写好的恢复代码真正跑完。**

### 2.2 `shownTurns` 切会话重置（已核实，此前无人记录）

```kotlin
// ChatScreen.kt:420
var shownTurns by rememberSaveable(state.conversationId) { mutableIntStateOf(PAGE_TURNS) }
```

`rememberSaveable(key)` 在 **key 变化时会重新初始化为 `PAGE_TURNS`（12 轮）**，不是按会话各存一份。
于是：会话 A 点两次「加载更早」（12 → 36 轮）→ 切到 B → 切回 A，A 又只剩 12 轮，`visible` 变短。
`page` 也随之重算（`ChatScreen.kt:437`）。

对位置恢复的打击是**结构性的**：

- 手工方案：存下的 index 指向 36 轮窗口里的位置，恢复到 12 轮窗口 → LazyColumn 把 index 钳到上界 → 落在顶端，不是阅读位。
- **每会话独立 `LazyListState` 方案同样中招**：state 记住的是 index，而 index 的含义随 `visible` 长度变化。#16 评论 2 那句「state 存的是 index，不是 key」在这里正好是缺点。

**这条必须在任何方案之前修掉，否则两种方案都会再失败一次。** 修法很小：把 `shownTurns` 改成按会话存（与 `stickByConversation` 同一形状的 map），或写入 `rememberSaveable` 的同一个 map 结构。**注意这算改 .kt**，属于方案的一部分，需要用户拍板后执行。

### 2.3 0.8.244（每会话独立 state）到底为什么把导航条搞坏（已核实）

`git show 7c25eaf` 对照 `afc3775`，两条具体缺陷，**都不是「独立 state 这个方向错了」**：

1. **`ChatScreen.kt:673` 没跟着改**：
   ```kotlin
   val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
   ```
   `remember {}` **无 key**，lambda 捕获的是**第一次组合时的那个 `listState` 实例**。0.8.244 把它换成了「按会话取 state」（`:283-284`），
   于是切会话后 `firstVisible` 永远报告**旧会话的 state** 的 index → `currentTurn` / 导航条窗口全错。
   同一形状的未 key 捕获还会影响 `:790 anchors`、`ChatScroll.kt:186 wasScrolling`（后者无害）。
2. **`ChatScroll` 被 `remember(listState)` 重建**（`ChatScroll.kt:137`）：`remember(listState) { ChatScroll(listState) }`。
   独立 state 后 `listState` 每次切会话都换 → `ChatScroll` 整个重建 → `stickByConversation`（`ChatScroll.kt:40`）**每次切换被清空** → 追底意图丢失（每个会话都退回默认 `true`）；`spots` 同样被清空。
3. 附带：`ChatListStates.forget()` **零调用点**（我 grep 过整个 `app/src/main/java`），删会话时 state 泄漏；每会话几百字节，有界，但不干净。

**这三条是可枚举、可修的**，且第 1、2 条正是 0.8.246 提交说明里「导航条换算失效」的真身。

---

## 3. 依赖图：谁依赖 `listState` 的什么

```
                          ┌──────────────────────────────┐
                          │ listState (ChatScreen.kt:276)│
                          └──────────────┬───────────────┘
       ┌───────────────────┬─────────────┼──────────────────┬────────────────────┐
       ▼                   ▼             ▼                  ▼                    ▼
  ① TurnScrubber      ② 追底       ③ 长按守护        ④ RevealHeight        ⑤ 内层思考链
  导航条显示           ChatScroll    pressGuard        展开补偿             (自己的 state)
```

| 功能 | 依赖 `listState` 的 | 代码位置 | 换「每会话独立 state」后 |
|---|---|---|---|
| ① **TurnScrubber 导航条** | `firstVisibleItemIndex`（**无 key 的 `remember`**） | `ChatScreen.kt:673` | **失效**（捕获旧实例）→ 0.8.244 就是这里断的 |
| ① 同上 | `visible.size`、`feedPrefix`、`rowOffset`、`turnIndices` | `:636/654/668/675/677` | 可用，但**依赖 `feedPrefix` 仍会抖**（#23 根因 B） |
| ① 同上 | 跳转 `animateScrollToItem` | `:1400` | 可用 |
| ② 追底 | `canScrollBackward`、`isScrollInProgress`、`firstVisibleItemIndex/Offset` | `ChatScroll.kt:88/144/160` | 可用；但 **`ChatScroll` 实例被重建 → `stickByConversation` 清空** → 失效 |
| ② 记录位置 | `layoutInfo.visibleItemsInfo`、offset | `ChatScroll.kt:188-207` | 变成死代码（应删） |
| ③ 长按守护 | `layoutInfo`、`firstVisibleItemScrollOffset`、`firstVisibleItemIndex` | `ChatScreen.kt:723-736` | **基本可用**；风险：`LaunchedEffect(pressGuardOn)` 只以 `pressGuardOn` 为 key，若守护中途换 state，闭包里是旧实例 |
| ④ RevealHeight | `LocalChatListState`（`ChatBlocks.kt:305/364`，由 `ChatScreen.kt:1000` 提供） | `ChatBlocks.kt:405/417` | **可用**（provider 每次组合给新 state） |
| ⑤ 内层思考链 | 自己的 `rememberLazyListState()` | `ChatBlocks.kt:797` | 无关 |
| 「回到最新」浮钮 | `!canScrollBackward` | `ChatScroll.kt:88` → `:1518` | 可用（若 `ChatScroll` 不重建） |

**结论**：独立 state 只**真的**打坏 ① 和 ②，且两处原因都已定位到行（§2.3）。③④⑤ 不受影响。

---

## 4. 最小方案

原则：**先减机制，再加能力**。下面两个候选都**不新增**「检测偏移→纠正」类逻辑（#17 已证明这条路走不通）。

### 候选 A（推荐）：独立 state + 修掉 3 个前提 + 删 5 套存储

**改动文件：`ChatScroll.kt`、`ChatScreen.kt`**（`ChatBlocks.kt` 不改）

前提（缺一不可，顺序也别换）：

| 步骤 | 内容 | 为什么必须先做 |
|---|---|---|
| **A0** | `shownTurns` 按会话存（§2.2） | 否则 index 含义随窗口变，独立 state 也失效 |
| A1 | `ChatScreen.kt:276` → `scroll.stateFor(conversationId)`；`ChatListStates` 用 `mutableMapOf` + `getOrPut` | 架构本体 |
| A2 | `ChatScreen.kt:673` 改为 `val firstVisible by remember(listState) { derivedStateOf { ... } }`（**key 上 `listState`**） | 不加就是 0.8.244 的复发 |
| A3 | `ChatScroll.kt:137` → `remember { ChatScroll() }`（**不再把 listState 当 key**），`listState` 改为参数/属性传入 | 不加就是 `stickByConversation` 每次切换被清空 |
| A4 | `ChatListStates.forget(id)` 接到「删除会话」路径 | 清理泄漏 |

**删除清单**（这才是「减少机制」）：

| 删 | 位置 | 行数 |
|---|---|---|
| `spots` / `saveSpot` / `takeSpot` | `ChatScroll.kt:49,94-102,203` | ~12 |
| `spotKey` / `spotOffset` / `spotByConversation` | `ChatScroll.kt:55-65,198-199`；`ChatScreen.kt:879-880` | ~15 |
| `lastSeen` / `lastSeenByConversation` | `ChatScroll.kt:79-85,197` | ~10 |
| 记录位置的整个 collector | `ChatScroll.kt:186-208` | ~22 |
| `anchors` + 记录锚点的 `LaunchedEffect` | `ChatScreen.kt:790,806-823` | ~20 |
| 恢复位置的整个 `LaunchedEffect` | `ChatScreen.kt:859-906` | ~48 |
| `openedPinned`、`restoring` 两个闸门 | `ChatScreen.kt:858`；`ChatScroll.kt:52,146-149` | ~8 |
| `spotKey = null`（发送处） | `ChatScreen.kt:1471` | 1 |

**净效果：约 -130 行，+20 行**（`ChatListStates` + key 修正）。写入口从 10 个降到 5 个（W1/W2/W3/W6/W7/W8/W9 里，与位置记忆相关的 W4/W5 整条消失）。
`feedPrefix` / `fedIndexOf` **保留**（仍用于喂序↔visible 换算与导航条跳转），但**不再参与「记住位置」**。

### 候选 B（保守）：只修 #16，不动架构

不做独立 state，只做三件小事：

1. `ChatScreen.kt:859` 的 `LaunchedEffect` **把 `openedPinned` 从 key 里去掉**，改用「按会话记的已恢复集合」判定（写它不会 re-key）→ 让 §2.1 的取消消失，W5 真正执行；
2. `A0`（`shownTurns` 按会话存）照做；
3. 恢复时**先按 key、再兜底按 index**：把 W4 粗定位改成「若 `savedKey` 在 `visible` 里，直接用 `visible.size - 1 - idx + rowOffset + feedPrefix`；否则跳过粗定位，等 W5 按 key 校正」。

改动约 30 行、不删机制。**代价：5 套存储与 2 个闸门全留着，下次还会踩。** 只有在用户不接受候选 A 的风险时才选它。

### 关于另外两条 issue（不属于「位置记忆」，但同批决策）

- **#17（流式上翻漂）**：建议按 issue 自己的结论走**方案 3「上翻时冻结」**（不在底部就不把新内容渲进 `visible`），这是唯一不靠「检测-纠正」的路。它和候选 A 有协同：冻结只影响渲染，不影响 state。**但它会改变交互**（上翻期间看不到新输出），需要用户单独点头。
- **#14（长按位置变）**：建议 **`ChatBlocks.kt:1150` 的 `ToolDetail` 内层 `LazyColumn` → `Column + verticalScroll`**（嵌套滚动层 3→2）。
  守护 W3 **已被删除**（`e1a6184`）：它拿的是**按下那一刻**的 lockKey/lockOffset，正常上滑后
  index 变了而那行仍在屏内 ⇒ 把你纠回按下点（用户：「滑上一点，就滚下来一点」）；滑得够远时
  那行已出屏、`target == null` ⇒ 不纠（「滑多了反而正常」）。两半现象完全吻合，所以删它是**止血**，
  不是「修 #14」。**不要把它装回来。**
  代价：**#14 现在是「零缓解」** —— 长按选字时位置仍可能变，这是已知代价，不是回归。
  真正修 #14 仍需上面那条「内层列表去嵌套」。

  **本节结论的核实依据**（2026-10-20，供后来者判断这是新证据而非改写）：
  - `git show e1a6184 --stat` → 该提交即「修『滑上一点就滚下来一点』：pressGuard 的位置纠正改为只记录不执行」
  - `grep -n 'scrollToItem(target.index, lockOffset)' app/src/main/java/com/suixing/agent/ui/chat/ChatScreen.kt` → **无结果**
  - 同文件现在只剩 4 行 `scrollTrace("lock-skip …" / "lock-would-correct …")`（:740 / :744 / :746 / :759）
  - 因此本文 §1.1 的 W3 行、§9 的「给 lead 的建议」都同步标为**已过期/已落地**；旧结论
    「W3 逻辑是对的，保留即可」「应与去嵌套一起做、不要单独落地」**已被这次事实推翻**，留档在 §9。
- **#23（导航条换级抖）**：**机制未证实**，见 §7-①。不建议在本批里改。

---

## 5. 回归清单（用户能在手机上自己验）

每项一条、可勾选。前置：装 0.8.2xx 新包，进一个有 ≥10 轮对话的会话。

| # | 功能 | 验证方法（一句话） |
|---|---|---|
| R1 | 切会话位置 | 会话 A 上滑几屏 → 切 B → 切回 A：**停在原处**，不回到最底 |
| R2 | **对话中**切会话（#16 关键） | A 正在流式输出时上滑到中段 → 切 B → 切回 A：仍停在原处 |
| R3 | 分页后切会话（§2.2） | A 点两次「加载更早」→ 切 B → 切回 A：**「加载更早」不该重新出现**，位置仍在原处 |
| R4 | 回到底部的语义 | A 滚到最底 → 切 B → 切回 A：**回到底部**（这是唯一该回底的情况） |
| R5 | 追底 | 在最底发一条长回复：输出过程中画面一直贴底，不抖 |
| R6 | 上翻取消追底 | 输出时往上滑一下：不再被拉回底部；滑回最底后，新内容重新自动跟随 |
| R7 | 导航条显示（防 0.8.244 复发） | 在 A 里慢慢上下滑：右侧导航条**高亮轮次跟着变**，窗口滑动；切到 B 再滑，**B 的导航条不是 A 的位置** |
| R8 | 导航条跳转 | 按住导航条拖到某一轮松手：跳到该轮且**不被追底拉回** |
| R9 | 长按复制 | 在工具输出块上长按选字、拖动选择手柄：**转录位置不动**，松手后也不动 |
| R10 | 展开补偿 | 在一轮里展开/收起「思考链」与工具详情：画面不跳、不位移（贴底与中段各验一次） |
| R11 | 键盘 | 在底部点输入框（键盘起）与在中段点输入框：阅读位置不被推动 |
| R12 | 「回到最新」浮钮 | 上滑后浮钮出现，点它回到底部且浮钮消失 |
| R13 | 发送后回底 | 在中段直接发送一条：跳到底部并恢复追底 |

---

## 6. 影响面 / 改动量对照

| | 现在（0.8.246） | 候选 A | 候选 B |
|---|---|---|---|
| 位置存储 | 5 套（spots/spotKey/lastSeen/anchors/state index） | **1 套**（`LazyListState` 自带） | 5 套 + 修好 1 处取消 |
| 闸门 | 2（openedPinned / restoring） | **0** | 2 |
| 写滚动位置的入口 | 10 | 8（W4/W5 消失） | 10 |
| 改动 .kt 行数 | —— | 约 -130 / +20 | 约 +30 / -5 |
| 导航条第 ① 项失效风险 | —— | **有，A2/A3 是硬前提** | 无 |
| `#16` 能否真修好 | 不能 | 能（前提是 A0–A3 全做） | 能（前提是 A0 + 去 re-key） |
| 还需修的 issue | —— | #17/#14/#23 各自单独排 | 同左 |

---

## 7. 风险坦白：我现在也没把握的地方

① **#23 的「跳」我没能定位到机制，且我不认为 issue 里写的两个根因成立。**
   - 根因 A（导航条自身高度变化反推主列表）**已核实为不成立**：`TurnScrubber` 是 `ChatScreen.kt:958` 那个 `Box` 的**子节点**（`Modifier.align(Alignment.CenterEnd)`，`:1364-1368`），
     与 `LazyColumn`（`:1002`，同一 `Box` 的另一个子节点）**是兄弟关系，不在 `Column` 的测量链里**；
     列表的底部让位只来自 `contentPadding.bottom = stackFootprint`（`:1081`），而 `stackFootprint` 只由 `composerH` 构成（`:349-354`，`onHeightChanged` 挂在 `Composer` 上，`:1511`）—— **导航条不参与**。
     另外 `railHeightPx` 依赖的 `rowCount = rows.size` 实际**恒为 6**（`SCRUBBER_VISIBLE=6`，`ChatRows.kt:688-695`；我对 turns=3/6/10/20/40 全部算过，turns≥6 时每个 `current` 都是 6）。
   - 根因 B（`feedPrefix` 抖）**通过**：它确实让 `currentTurn` 在流式期间多跨边界（`ChatScreen.kt:675-678`）。但它的直接后果是**导航条窗口滑动**（视觉），
     我看不到它**改变列表滚动位置**的路径。也就是说：用户感到的「位置跳」很可能是**导航条条目在动**，而不是转录在动 —— 但那也可能是我漏了一条链。
   - **判定方法（不改代码）**：`scrollTrace` 已经在写 `files/scroll-jump.log`（`ChatScreen.kt:701-713`），记录 `idx=` / `canBack=` / `total=`。
     让用户复现「每跨一级跳一次」，`adb shell run-as com.suixing.agent cat files/scroll-jump.log`：若跨级前后 `idx` **不变** → 是导航条视觉滑动，改法是「窗口固定槽位/加动画」；若 `idx` 变了 → 我的结构结论有漏，需要重查。**请先做这个测量再决定要不要改 #23。**

② **`#16` 的「自我取消」是读代码推出的，我没有真机日志**。它同时能解释「三步修法为什么没用」，我信心较高，但没跑过。R2 就是它的验收。

③ **候选 A 的 A0（`shownTurns` 按会话存）会改变现有行为**：现在切回来「加载更早」会重置（用户可能已经习惯，也可能正是他抱怨的一部分）。这条需要用户确认，不是我能在文档里替决定的。

④ **`LazyListState` 记住 index 的固有脆弱性仍在**：即便做了 A0，如果某会话的消息被压缩/删除（`ChatScreen` 有 `compress-status` 与分支切换），index 会指向另一条消息。`LazyListState` 会自动 clamp，**不崩，但可能停在不完全对的位置**。我没有找到「按消息 id 恢复」且不引入「检测-纠正」的干净做法 —— 这是候选 A 已知的残余缺口，请用户知道。

⑤ **未做任何构建/编译**：本文只读代码，没有跑 `gradlew`。候选 A 涉及 `ChatScroll` 构造函数签名变化（A3），实际编译会牵动所有 `rememberChatScroll` 调用点，改动量可能比我估的 +20 行多一些。

⑥ **`ChatScroll.kt:177-182` 的注释声称「不做任何阅读锚纠正」，但文件里仍有记录逻辑**；`spotKey`/`spotOffset` 目前**只写不读**。我按「死代码」处理（删），但没验证是否有间接读者（grep 结果为无）。

---

## 8. 建议的决策顺序

1. **先做 §7-① 的测量**（不改代码，一次 `scroll-jump.log`）→ 决定 #23 是视觉问题还是位置问题；
2. 用户对「候选 A vs B」「#17 冻结交互」「A0 是否接受」三个选择拍板；
3. 拍板后按 A0 → A1 → A2 → A3 顺序**分四次小提交**，每次只跑 §5 里相关的那几条回归（A2/A3 各自单独验 R7/R6，因为它们是 0.8.244 的复发点）；
4. 每步都可单独回滚 —— 特别是 A1 单独上线时其余机制仍在，属安全态。

---

---

## 9. 附录：写作期间发生过一次并发改动（已消失，但请知情）

**事实（`git status` / `git diff` / `git hash-object` 核实）**：

1. 我开工时工作树是干净的（HEAD = `4b67dcb`）。
2. 我写文档期间（约 16:25–16:27），队友 **`ui-motion`**（task-1 / #12）改了 `ChatScreen.kt` 且未提交：
   - `switchAlpha` 改成按会话 `remember(state.conversationId)` + 210ms；
   - **同时删掉了长按守护里的 `lockKey/lockOffset` + `scrollToItem(target.index, lockOffset)`（本文 W3）** —— 越出了它「只改 switchAlpha」的职责，也进入了本任务冻结的滚动核心。
3. **这些改动随后被丢弃了**：现在 `git status` 只有 `?? docs/scroll-redesign.md`，
   `ChatScreen.kt` / `ChatScroll.kt` / `ChatBlocks.kt` / `ChatRows.kt` 的 `git hash-object` 与 `HEAD:` **逐字节相同**。

**结论**：本文所有行号与结论**基于当前工作树 = HEAD = `4b67dcb`**，与代码一致，W3 存在（`:723-736`）。
上面第 2 步那次改动**没有进入任何提交**，不影响本文任何判断。

**但请 lead 注意两件事**：

- `ui-motion` 有此行为，说明**它认为删 W3 是对的**（理由写在它自己的注释里：lockKey 会把正常滑动纠回去、`!pressMoved` 打补丁会挡掉拖选）。
  这个理由**在机理上说得通**，本文 §4 对 #14 的建议（去掉 `ToolDetail` 内层 `LazyColumn`）与它方向一致、且更彻底。
  **但它删 W3 并不能修 #14**（它自己也这么写了）—— 只删不补，等于让 #14 从「有缓解但可能过纠」变成「完全无缓解」。
- 若之后有人重新引入这次改动，**本文 §1 的 W3 行、§4 的 #14 条目就要同步改**，否则文档会与代码脱节。

**给 lead 的建议（2026-10-20 更新：已过期）**：W3 的删除**已经单独落地**（`e1a6184`），理由见 §4 #14 ——
它是「滑上一点就滚回来」的直接原因，先删止血，用户已亲口确认这个现象被骂了很久。
**#14 因此变成零缓解**，下一步必须做「去嵌套」。任何人若要恢复 W3，先读 §4 #14 的机理推演：
装回去会把用户骂掉的回弹原样复现。

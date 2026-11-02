# 2026-11-02：导航落位、收展、抽屉并行动画与 issue 收尾

本地基线 HEAD `9aca6f2`；实施时手机为 **0.8.642 / vc663**。下文保留当时的实施/验证记录。
随后用户授权出包安装，现已装 **0.8.643 / vc664**（见本文末）；仍未提交或 push。
原有历史加载/涟漪/0.8.641–642 升版等未提交改动全部保留。

## 导航定位（0.8.643历史实现，后续修正见本文末）

- 主动导航开始时，在 `scroll.restoring=true` 后同步清理旧阅读点；结束恢复阅读后同步重新建立锚点。
  旧锚点不能在到达后再把列表拉回。不改变 #17 的全局字符锚定或追底架构。
- `NavigationMotion` 在一个 `LazyLayoutScrollScope` 中滚动。看不见目标时只使用有上限的估算步长，
  看见目标后按真实偏移缓动落位；没有 `snapToItem`、闪现或动画结束后的强制双重定位。
- 如果目标很晚才被测量，终止估算阶段并给它一个新的实测落位阶段，避免最后一帧吃掉整屏剩余距离。
- 回到底部的巨大 offset 是哨兵，不能作为实际距离：屏外估算按 0 offset，测到末项后用
  `offset + size + afterContentPadding - viewportEndOffset` 算剩余距离。
- 无进展检查比较实际首项 index/offset，不能比较会因平均高度重估而相同的“预计距离”。
- 安全边界：最多16个动画阶段、名义 tween 时长预算3000ms；慢帧会延长实际墙钟时间。
  **极远或布局持续改变的目标可能在预算用尽时停在当前实际位置，尚不保证任意长文一次精确抵达。**
  目标删除/边界钳位/用户取消同样停止；下一轮若用户仍遇到问题，先区分预算中止与锚点二次修正。
- `NavigationLandingTest` 使用 fake scope 覆盖估算变化、测后长高、晚测无尾帧突跳、末项对齐、
  钳位和删除；没有 Compose UI 或真机动效证据。

## 总结条展开/收起

`ChatBlocks.kt` 保留 PROCESS_EXPAND_MS=220 的阅读保护兼容常量，实际时长按逻辑 dp 高度连续插值：

| 类型样本 | 自然内容高 | 展开 | 收起 |
|---|---:|---:|---:|
| 极短 | 48dp | 142ms | 120ms |
| 短 | 160dp | 170ms | 142ms |
| 中 | 320dp | 257ms | 214ms |
| 大 | 640dp | 432ms | 365ms |
| 超大 | 1600dp | 680ms | 580ms |

超1200dp不继续增加时长。收起不再与屏幕像素密度绑定，两方向都按高度混合短曲线与对称长曲线，
单调、不回弹。反向点击按剩余 fraction 取时长，最低80ms、相同落点0ms，不重播全程。
高度仍是自然内容高度，**没有视口内可见高度的时长优化**；超高内容在封顶时长下仍需后续体验。
保留展开首测等待、流式长高不重启 effect、begin/endRevealTransition 配对及自动折叠规则。

## 抽屉与切会话

- 抽屉宽度仍300dp，面板改为窄绘制层的浮点 translation，减少对其 LazyColumn 的逐帧放置。
  手势判定、右侧透明冻结层、点击关闭、排序重排和现有 close spec 不变。
- 点击会话先 undispatched 启动关闭，进度一离开完全展开状态就提交最新会话意图，准备/恢复与余下
  关闭阶段并行。保留 token 防连点和 busy 清理，不等完整关闭后才启动读盘。
- 聊天与导航共用300ms/12dp入场，恢复定位后才展示；入场时高亮直接取最终位置，不下扫。
- **并行边界**：冷加载/恢复若超过抽屉关闭时长，仍会晚于关闭结束后呈现，未承诺所有情形完全重叠。
  没有实机 FPS、P95、功耗或内存测量，不能据此声称满帧。

## 文案与 #60 进展

- 独立压缩卡 token 文案加 `start=8dp`，查看原文按钮保持原位置和样式。
- 输入区在窄窗<360dp/系统fontScale>=1.3/App特大字号时，附件/审批/子任务入口与主动作分行。
  常规宽度保留单行。正文与占位字继承主题 bodyLarge，麦克风禁用名称带原因。
- 加载文本增加未知进度/polite liveRegion。空态按实际模型/接口是否为空给出设置入口，
  不再一律要求先开手机权限；建议仍只填草稿。
- 导航在原24dp区域增加范围/当前轮数/setProgress/上一轮/下一轮语义，不扩大手势拦截区域。
- #60 保持 open：完整触控/semantics/截图矩阵、TalkBack和键盘焦点、普通宽度全部48dp可达性，
  以及附件/语音/弹层/大字体组合仍未验收。代码补充不等于以上已通过。

## 后端漏项补修和 issue 状态

| Issue | 结果 | 证据/本轮补修 |
|---|---|---|
| #66 | 已评论关闭 | 原队列/投影/拖动测试；补失败不误报成功、损坏主文件回退.tmp、失败不清dirty |
| #67 | 已评论关闭 | 既有意图代次/冷开/热缓存/真实delay测试；本轮并行起步与共享入场 |
| #68 | 已评论关闭 | 任务页原来实际未接队列，本轮补上共享队列；定时任务失败恢复arm同锁；预览硬上限/取消 |
| #69 | 已评论关闭 | 既有TailReadGate/AtomicFiles/有限纯策略迁出/基准；补TODO原子写和残留宿主错误注释 |
| #70 | 已评论关闭 | 四子项关闭后的代码级总追踪收尾 |
| #71 | 已评论关闭 | A1迟到回调取消测试、A3有界token缓存、A4旧机制清理；A2保留已定签名方案C |
| #60 | open，已补进展评论 | UI/无障碍遗留如上，未假关闭 |
| #61 | open，已补进展评论 | 修nativeRead被EINTR反复重置timeout；PTY平台门槛/终端产品功能仍未交付 |

关闭与评论均由 GitHub 连接器实际执行，随后逐条 fetch_issue 确认状态；搜索索引曾短暂仍显示#70。
评论明确区分：历史主体已在main，本轮补丁仍在本地且未push，手机0.8.642不含本轮新改动。

任务/提醒修复使用 Core 共享 writer，在事件发生时同步submit修订号，之后UNDISPATCHED进入应用级
写入，页面退出不会取消保存；旧失败不回滚新编辑，仅当前请求且页面仍活跃才恢复UI/Toast。
失败恢复 read+arm 与写入同锁，避免旧恢复覆盖新闹钟；UI作业只持applicationContext。
TodoStore改用AtomicFiles，替换失败保留旧有效数据。文件预览最多读取20KiB+1哨兵字节，
文件增长也不无界读取；分块检查取消，面包屑/根tab/返回均作废旧预览。

#61仅修一个已存在的原生等待边界。没有运行API26/29/33/35/37与ROM矩阵，未交付session manager、
终端仿真UI、Agent授权工具或Linux。Release原生编译通过不能代替TTY/Ctrl+C/resize/reap验证。

## 验证与交接

- `:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain` 最终成功53s：
  **224类 / 1311项 / 0失败 / 0错误 / 0跳过**。日志 `verify-out/ui-motion-issues-final-release.log`。
- 首轮1311项有一个新增fake导航样本的近/远步长断言错误，已核对实际292.5px并补完整晚测重启断言，
  然后全量复跑全绿；不是跳过/删除失败用例。展开半程反向测试也修正为剩余半程200ms。
- `:app:externalNativeBuildRelease --offline --console=plain` 成功23s，四ABI，
  日志 `verify-out/ui-motion-native-release.log`。没有APK生成任务或Debug variant任务。
- `git diff --check` 通过；没有手机安装/日志/截图/帧数据，没有git提交/push。
- 下一包须 **0.8.643 / vc664**。未跟踪新源码/测试后续提交必须纳入，尤其
  EarlierPagePosition、PreviewRead、NavigationTargetTest、NavigationLandingTest、PreviewReadTest。

后续验收：先看导航轻点/连点/长按松手与极远跨屏；再按五种长度反复展开收起和中途反向点击；
切热/冷会话观察抽屉重叠程度与导航同步；窄窗/大字号发送、文件预览快速换目录、任务/提醒连点后离页。

## 后续交付：0.8.643 / vc664（2026-11-02）

用户明确授权出Release并安装。assembleRelease成功2m3s，R8/资源收缩开启；APK5,726,417B。
aapt/metadata与16KB zipalign检查通过，SHA256
6A193C7B07CD70510C6572442C1FBA6D2CD11CAF6F113157C33D7AB1AB39513C，设备base.apk完整哈希一致。
无线调试192.168.0.102:38539，覆盖安装Success；设备确认0.8.643/vc664。
未自动启动App/操作UI/采手机数据；未重复单测、Debug、提交push或发布。动效与定位待用户体验。
下包0.8.644/vc665，本文已知限制及#60/#61遗留仍保留。

## 0.8.643反馈后的继续修正：两段移动与收展打磨

用户明确：停止、流式、输入法状态都会出现，最终位置正确，但途中到一次位置后又移动到最终位置。
本轮只做源码修改与Release编译/单测，不升版、不出包、不安装、不提交或push。

### 导航：区分并同时处理两条路径

1. 旧`NavigationMotion`最多16轮，每轮新建AnimationState。目标晚测且进度已过75%时取消当前
   tween，再以零初速度开实测阶段。这个显式的减速/重启就可能呈现“两次到达”，不必等锚点介入。
   新实现改为一个持续的逐帧控制器：估算切实测保留速度，按实时误差临界阻尼收束，不在阶段间重启，
   不加结束snap。每帧最多按32ms积分、速度上限每秒12个视口，方向变化不保留背离目标的速度。
   底部sentinel仍按真实底边误差对齐。保留系统动画时长倍率，关闭系统动画时直接使用库内立即定位。
   屏外估算为0时按目标index相对可见范围推断方向，以视口高度作为有界误差继续推进，
   不把未测到的0误判为已到达；正反两个方向均覆盖完整屏外→实测→抵达路径。
   旧16阶段/名义3s限制已删除，改15s墙钟取消边界；极端距离、持续长高、物理钳位/目标删除仍可能中止，
   不承诺任意无限距离都可在有限时间抵达。
2. 旧交接在`scroll.restoring=false`后立刻`interactionChanged→capture`。
   Foundation 1.12.1的placement-only路径先更新layoutInfo，再invalidate placement；文字坐标可能仍是
   旧帧，导致新列表位置+旧文字Y混合成锚点，下次`contentPlaced`又requestScrollToItem。
   对照[AndroidX LazyListState源码](https://raw.githubusercontent.com/androidx/androidx/5e0747401bf56b243e3b5141ae080dc9bc61d29c/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt)。
   现在导航起止均用`rebaseAfterNavigation`挂起捕获，延迟到TranscriptNode完成child.place后重新建立锚点。
   迟到的interaction通知也不能抢先捕获；普通阅读捕获/后续内容长高补偿仍保留。不是全局改写#17。

`NavigationAnchorHandoffTest`调用真实TranscriptPosition，模拟layoutInfo先更新而文字坐标随后放置：
修正前3例中2例失败（`verify-out/nav-handoff-before-release.log`），修正后3例全过
（`verify-out/nav-handoff-after-release.log`）。同时确认后续真正15px长高仍补偿，普通20px阅读补偿不变。

### 总结：保持普通五档节奏，修正反向与尾帧

- 普通展开/收起保留上表五档时长和按高度混合的曲线；不再整体加慢短块，不改变自动折叠条件。
- 用稳定的effect收集visible目标，保存取消前采样速度；半途反向复用已测高度，完全关闭后重开才重新等测量。
- 仅处理中途打断的路径使用临界阻尼。背离新目标的速度丢弃，顺向速度限定在`omega × 剩余距离`，
  避免“先继续展开一下才收起”之类回弹。保留begin/endRevealTransition配对。
- 换向携带当时实际透明度，消除早期展开反向时从半透明跳为不透明的突变。
- 反向spring的结束阈值为`min(0.01, 0.25px / 实测内容高)`，并据相对阈值调整刚度；避免默认1%fraction让超高内容
  在最后一帧突然补上十几像素。普通五档tween不受此修正影响。
- 使用真实Compose `TargetBasedAnimation`检查5000px卡片在两种反向情况下、60/120Hz结束前的
  可见高度误差≤1px，400ms样本估计时长保持在±2ms；解析轨迹另覆盖单调和速度限幅。

### 本轮验证与剩余边界

- 最终`:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain`成功1m15s，
  24tasks（5executed/19up-to-date），XML实际汇总**225类/1324项/0失败/0错误/0跳过**。
  日志`verify-out/nav-fold-refine-release-final.log`。其中落位控制器10例、交接3例、总结动画12例。
- 首轮同样1324项有1个新增断言失败：16,666,667ns对应的速度限额略大于整200px，
  断言未留浮点误差。改为200/384px的0.001px容差后完整复跑全绿，未移除该用例。
  核对中还修正了zero-estimate fake过早进入visible分支的问题，并断言必须多帧推进才抵达。
- 产品代码新引入的unnecessary safe call警告已清理；旧Clipboard/scroll API弃用及Gradle10提示保留，
  不在本轮扩改。未改native源码，沿用前轮四ABI编译基线，没有重复原生构建。
- 本轮没有Debug、assemble/package APK、安装、启动App、采手机日志/截图/帧数据、版本变更或提交push。
  代码测试证明控制器与交接覆盖范围，**不等于真机动效或满帧验收**。
- 仍保留15s墙钟安全取消、用户取消、目标移除与物理边界中止；没有虚构无限距离保证。
  总结仍按自然内容高度计算普通时长，未引入视口裁剪；流式期间内容高度本身变化也不是动画冻结。
- 后续用户授权装包后：停止/流式/键盘开合分别轻点近远目标，观察一次连续减速落位；
  连点取消/最新底部目标/长按松手也复验。五档总结正常开合、中途反向、快速反向及超高卡片尾部复验。

手机仍为0.8.643/vc664；下一次授权包0.8.644/vc665。#60/#61的旧遗留未在本轮处理，继续保持如实记录。

## 后续交付：0.8.644 / vc665（2026-11-02）

用户当轮授权出Release并安装，包含上一节全部导航/总结修正。assembleRelease成功2m43s，
55tasks（23executed/32up-to-date），R8/资源收缩开启；verify-out/release-0.8.644-build.log。
源码225类/1324项全绿基线保持，此轮仅升版打包未重复单测。APK5,726,425B，
aapt/metadata/16KB zipalign通过；SHA256 C51E1F52A4324CE12681464F6874404AACD02F97C64D293B4EFBEA3693E53B65。
mdns自动发现192.168.0.102:38539，覆盖安装Success，设备确认0.8.644/665、base.apk哈希一致。
未自动启动App/操作UI/采手机数据，未Debug/提交push/发布。定位是否仍二次移动与总结开合待用户体验。
下一次授权包0.8.645/vc666，本文边界/#60/#61遗留继续保留。

## 0.8.644反馈：向上接近目标时急停

用户暂未再见二次移动，但向上比向下生硬；已追问确认是末段突然停住。本轮不改总结条，
不全程统一降速，保留已验证交接；只改NavigationMotion，增加NavigationBrakingTest。

### 根因与修正

[Pinned AndroidX LazyListScrollScope源码](https://raw.githubusercontent.com/androidx/androidx/5e0747401bf56b243e3b5141ae080dc9bc61d29c/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollScope.kt)
在屏外用可见项平均高度估算，可见后切item.offset。上下相同误差的方程本是镜像，
但向上跨过不等高卡片时，估算→实测误差变化可能更剧烈。

新增用例调用依赖内真正的LazyLayoutScrollScope工厂，mock的只是列表坐标源和scroll执行，
用累计行高重建可见范围，保留亚像素滚动余量；不复制/重写被测试的Foundation距离公式。
1000px模拟视口，导航offset=-280px，短目标卡前后有2000px高卡：旧向上位移尾段
约[-200,-200,-200,-200,-164,-75]px，最后一次吃完75px就停。向下对照没有同样失败。

- **高速与新误差不匹配**：虽然是临界阻尼，旧携带速度大于omega*剩余误差时，解析轨迹仍可越过目标；
  最后用min(请求,remaining)钳位，变成一帧吃完然后清零。现在积分前将速度投影到
  min(maxSpeed,omega*abs(error))，积分后按请求后的剩余误差包络限幅。正常不变目标的临界阻尼
  自然轨迹本就满足此上界，只处理不匹配的动量；仍同一控制器，不重开阶段、不越过再拉回。
- **历史顶部不可达offset**：负offset想把第一条放在28%探针时，可能被物理首端限制，
  旧实现到scrollBy消费不足才截断。现在firstVisibleItemIndex==0且原误差<0时，
  按-firstVisibleItemScrollOffset限制可达误差，先减速；可达误差0但原offset仍非0时返回ScrollClamped。
  不把不可达探针位置伪称Arrived，最终仍停在真实边界。此边界样本旧末帧约9.6px且之前仍56px/frame。

### 验证及限制

- RED：Release定向3项/2失败，48s，verify-out/navigation-braking-before-release.log；
  失败分别是上行末段与物理顶端，下行对照通过，失败消息保留真实尾段数据。
- GREEN：`:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain`成功1m5s，
  24tasks（5executed/19up-to-date），实际XML汇总**226类/1327项/0失败/0错误/0跳过**，
  verify-out/navigation-braking-final-release.log。新3项循环60/120Hz，断言准确或最近可达落点、
  单调不越界、最后4帧各≤3px/末帧≤1px。此前文字放置交接3例仍全过。
- 这是模拟几何与真实距离计算的代码验证，不是设备帧率/手感测量；用户“暂时不会”也不等于
  二次移动永久全场景通过。15s安全中止、外界持续几何变化、物理边界等旧限制保留。
- 未Debug/assemble/APK/安装/手机采集/升版/提交push。手机仍0.8.644/vc665，下包0.8.645/vc666。
  下一次装包重点看向上近远目标、短目标邻接长卡片、历史首端的减速，以及有无二次移动回归。

## 后续交付：0.8.645 / vc666（2026-11-02）

用户当轮授权Release出包安装，首次含上述向上末段刹车修正。assembleRelease成功1m51s，
55tasks（23executed/32up-to-date），R8/资源收缩保留；verify-out/release-0.8.645-build.log。
源码226类/1327项全绿基线保持，本轮只升版打包未重复单测；APK5,726,313B，
aapt/metadata/16KB zipalign通过，SHA256 9E6A9260CBA3BC2C2A6247DB33B27C8ED70B7A0F6E6DDE7A881459BAF342F168。
mdns自动发现192.168.0.102:38539，覆盖安装Success，设备确认0.8.645/666且base.apk哈希一致。
没有自动启动App/操作UI/采手机数据，未Debug/提交push/发布；减速手感与二次移动回归待用户验收。
下包0.8.646/vc667，旧安全边界与#60/#61遗留继续保留。

## 0.8.645反馈：大跨度导航适度提速

用户确认末段效果可以，新反馈隔的高度大时动画稍久。保留已验收的减速和原近距节奏，
只调整远距速度上限，避免用统一缩短所有阶段破坏落位。

NavigationMotion按实时剩余视口距离计算cap：≤2屏仍12视口/s；2..10屏用`t²(3-2t)`平滑到18；
≥10屏18。步后动量也使用步后剩余距离的cap，连续控制器/omega14/误差限幅/首端刹车/文字放置交接不变。
这覆盖早期记录“所有距离最大12视口/s”的历史描述；未新增tween阶段、snap或固定总时长截断。
32ms积分、15s墙钟中止及系统动画时长设置仍保留。

固定几何的控制器JVM模拟耗时如下（秒；**不是设备帧率或墙钟测量**）：

| 剩余跨度 | 60Hz旧 | 60Hz新 | 120Hz旧 | 120Hz新 |
|---|---:|---:|---:|---:|
| 0.6屏 | 0.700 | 0.700 | 0.692 | 0.692 |
| 2屏 | 0.800 | 0.800 | 0.792 | 0.792 |
| 5屏 | 1.000 | 0.983 | 1.017 | 1.000 |
| 10屏 | 1.433 | 1.300 | 1.425 | 1.308 |
| 20屏 | 2.267 | 1.850 | 2.258 | 1.858 |
| 50屏 | 4.767 | 3.517 | 4.758 | 3.525 |

新NavigationTravelDurationTest5例覆盖两个刷新率的耗时递增、近距一帧内不变、20/50屏相对基线
缩短15–30%、双向精确/单调不越界、无snap、末帧≤1px，以及最后2屏请求速度仍≤原12视口/s。
超远screen-budget旧断言200/384px相应更新为300/576px（60Hz/32ms，18视口/s），测试保留。

- RED：修正测试基线后5项1失败，仅远距时长预算，verify-out/navigation-travel-red-release.log（41s）。
  初次运行另有60Hz2屏预估基线0.783与JVM实际0.800的偏差；按实测更正后重新跑RED，
  近距生产参数没改，没有把这个测试失败藏进产品调整。
- GREEN：compileReleaseKotlin+testReleaseUnitTest成功1m52s，24tasks（5executed/19up-to-date），
  XML实际汇总**227类/1332项/0失败/0错误/0跳过**，verify-out/navigation-travel-final-release.log。
  旧急停3例与文字交接3例仍过，git diff --check通过（历史CRLF提示保留）。
- 未Debug/APK/安装/版本变更/手机操作采集/提交push；手机仍0.8.645/vc666，下一包0.8.646/vc667。
  NavigationTravelDurationTest为新增未跟踪测试，后续授权提交须纳入。
- 下一次真机验收：跨高差大的近/远目标，确认大跨度更利落、近距仍舒适，末段不急停、不过冲、
  不再出现二次移动。手机实际耗时会受内容布局/慢帧影响，不能把上表说成真机保证；旧边界与issue遗留保留。

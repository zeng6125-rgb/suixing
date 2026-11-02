# 2026-11-02 接续：流式显示、工具守卫与 open issue

基线：`b9804d0`，0.8.626 / vc647。工作树原有 `.icon_ref/`、四份旧 docs 和 tasks 保留。
本轮用户要求处理 open issue（排除 #17）、继续降低渲染成本、改善正文/思考打字机及 agent loop；后续明确不要大总结旋转运行图标。

## 已落代码

- `Typewriter.kt`：用屏幕帧时钟代替固定 delay；按实际帧间隔积累字符额度，60/90/120 Hz 使用同一时间速度；单次停顿最多按 32ms 追赶。批量到达不再一拍显示到仅剩四字，改为分多帧追赶。追平后 snapshotFlow 挂起，历史不重播，完成继续排空尾部。UTF-16 代理对不会被显示一半；这不是完整 Unicode grapheme 分词。
- `ReasoningBlock`：思考正文共用上述揭示器，进度留在 run/part 的 viewport，文本切行仍用增量 splitter。没有把时钟抬到 ChatScreen；未修改 #17 的锚点、追底和列表几何。
- `TurnSummary`：移除大总结的 trailing ProcessSpinner，保留用时和其他块原有状态。
- `AgentEngine`：首块时间只在需要时读取一次；状态行尾串直接 substring；插件检查调度取消继续抛 CancellationException，不把取消吞成放行。
- `HookMatchBudget`：Java regex 的字符访问总预算为两百万，跨同次调用所有 hook 共享；匹配目标不截断。规则超过 1024 字符、参数超过 1 MiB、无效规则、预算超额或检查失败均拒绝本次工具调用。AUTO 原有忽略 require_confirm 的行为保留，安全失败不依赖这个会被忽略的提示。工具不继续执行，也不额外弹审批。
- `SettingsRepository`：凭据任一字段加密失败即中止整个 DataStore 事务；save/update 返回成功结果，写失败事件供外壳提示。模型表单保存失败不清草稿、不显示“已保存”、不执行“保存并返回”。迁移检查覆盖独立 searchApiKey 与 requestHeaders，修复只检查 apiKey 的遗漏。
- `McpManager`：配置修改串行，先持久化、再提交内存配置；短暂不可取消的提交段防止保存成功但协程取消使内存停留旧值。原连接状态更新不会被旧列表快照覆盖。
- `EndpointSecurity`：严格解析私网 IP，拒绝 `10.attacker.com` 一类前缀伪装；LAN 自签兼容保留。LLM / MCP / 插件的网络层拦截公网 HTTP 携带常见凭据头，含重定向交换；LLM 策略拒绝不进入五次网络重试。自定义 Header 名任意，不能据此声称能识别所有任意命名的秘密值。
- 完整 HTTP 转储开关只认可最近 15 分钟创建/更新的 marker；未来时间戳拒绝。此 TTL 控制后续转储，不代表既有转储文件到期自动销毁。
- `ReplyActionsMenu`：小于 360dp 的窗口或系统字号至少 1.3 倍时，回复底部使用 48dp 最小热区的“更多操作”，菜单含复制、修改、重答和分支切换；正常宽度保留既有行顺序。
- `Composer`：键盘/按钮共用八状态动作函数；空回车不停止，插话拒绝保留 send 回退；三个 Popup 开启时关闭其他两个，保留离场动画。

## Issue 状态与遗留

| Issue | 本轮处置 | 仍需做什么 |
|---|---|---|
| #56 | 前序 40 项处置见 HANDOFF §95/104；本轮补取消投递分离、确定顺序的晚到回调测试，补 R19 有界匹配和 fail-closed | 本轮代码未提交/推送，不能把远端 main 描述为已含这些修复；真机项继续按用户“不做实机测试”约束跳过，未声称实测通过 |
| #58 | 补设置事务/失败反馈、MCP 内存与磁盘一致性、诊断 marker TTL、严格 LAN 与公网凭据策略 | 真机备份/迁移与兼容矩阵未运行；诊断文件自身 TTL 清理、任意命名自定义凭据头识别仍未实现；不得关闭为全项完成 |
| #60 | 已有 24905c4 父盒热区/语义；本轮补动作统一与弹层互斥 | 已补窄屏/大字号回复操作菜单；Composer 窄布局、普通宽度下的完整 48dp 可达性、附件/弹层/语音的 UI 回归和 TalkBack 矩阵仍未完成；不得关闭 |
| #61 | 已有 30ab5fe 原生 PTY 探针 | PR-0 真实 ROM 的 TTY/Ctrl-C/resize/reap/FD 门槛未通过验证。该 issue 明确要求先过门槛再做 manager/UI/Linux；本轮无设备操作授权，保留 open，不以编译代替 PTY 验证 |
| #17 | 按用户要求排除 | 不修改、不评论、不关闭 |

## 验证与边界

仅执行 `:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain`。
最终日志：`verify-out/continuation-20261102-final-menu.log`，最终 BUILD SUCCESSFUL 1m2s，24 tasks（6 executed / 18 up-to-date），197 类 / 1215 项 / 0 failures / 0 errors / 0 skipped。
菜单前最终 guard 补强版为 197 类 / 1215 项 / 0 failures / 0 errors / 0 skipped；菜单后的最终验证另见 HANDOFF。
新增验证覆盖取消后晚到回调与响应关闭、MCP 保存失败不发布、设置加密失败、凭据迁移、LAN 前缀伪装、公网明文拒绝、marker 过期、打字机进度交接/代理对/多帧追赶、八种发送状态、hook 预算与大参数。

没有新增 Debug 任务、出 APK、安装、手机采集或 Git 提交/推送。另一 agent 的 R8 构建时间对照独立进行；未改其临时设置，验证开始和结束需核对构建文件。
这些是源码与 JVM 正确性证据，未证明真机满帧。下一步优先 #60 剩余 Composer 窄布局、语义/大字号回归，其次诊断文件清理；#61 受真实 PTY 门槛阻挡。


## 后续出包安装（用户明确授权）

0.8.627/vc648 已覆盖安装；APK 5,696,657 B，SHA256 `4C0CECA5AB7D9E369203022B2B0800FFD379AD29FF2204ECF499AADCCF60BB7C`，本地与设备一致。Release 构建4m32s，16KB对齐检查通过；未重复单测，未自动打开应用。此前“不出包安装”是§124开发轮记录，本次安装见HANDOFF§125。


## 大总结自动折叠与长内容动画（后续源码，未出包）

- 用户偏好：主代理负责规划、方案和复核；具体实施可交由 `gpt-6-luna / max` 子代理。本次确实调用该模型完成动画补丁，主代理复核并统一验证。
- 0.8.627已装源码已提交并push：`bd075d7`。
- 自动折叠优先级：大总结有显式开/关记录时优先采用；否则运行中展开，完成时仅因手动保持打开的内层思考/工具/压缩条而保留展开。默认展开和上翻阅读不算手动展开；内层工具父条显式关闭时不因其已隐藏详情维持打开。完成交接的状态会锁定一次，后续内层关闭不会连带突然收大总结。本批不改这些条件。
- 长内容快硬原因：旧展开时长最高480ms，收起最高350ms；超过约945px内容高度后不再增加时长，距离更长仍用同样时间。同时高度作为effect key在长高时可能重启动画。
- 新展开最长900ms、收起最长700ms；短块原有公式/曲线保持。展开先等当前实测高度，以-1区分未测和合法空高度0；每次toggle只确定一次时长，高度不再作为effect key。保留原begin/end定位保护与无回弹曲线。
- Release验证：`:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain` 成功45s，198类/1218项/0失败/0错误/0跳过；日志`verify-out/reveal-motion-release.log`。新增纯函数测试验证短块旧时长、长块继续增时、上限与收起不长于展开。
- 无新APK或安装；手机0.8.627不含本节新动画。下一次打包须升0.8.628/vc649。无真机动画验收证据。


## 动画版出包安装

用户后续明确授权：0.8.628/vc649已覆盖安装，包含9a3a435新折叠动画。APK5,697,353B，SHA256 `7152F186ED187704732E87C803B632BB49B8BA3C17EEE2BDF36D0A5F52B31FCC`，本地=设备。assembleRelease成功2m11s，16KB对齐通过；未启动应用，待用户体验。此前“新动画未出包”属于开发轮历史。详见HANDOFF§127。


## 128. 2026-11-02（Codex）：展开曲线补修，收起恢复原节奏（未出包）

- 用户0.8.628体验反馈：收起不用改，长展开仍像一下出现。主代理规划/数值取证，gpt-6-luna/max子代理实施，主代理复核和验证。
- 真实根因补充：旧Bezier(.2,0,0,1)在时间10/20/30/50%时，进度为15.63/50/68.81/87.78%；alpha约前17%时间即全不透明。前序只加总时长未解决展开进度前置。以上为曲线数值采样，非真机采帧。
- 收起恢复原extra130ms上限（总350ms），保持旧曲线。展开总时长仍最多900ms，不继续加长。
- 展开首测高度换算dp：<=160dp用旧曲线，>=480dp用(.4,0,.6,1)，中间连续混合两条单调曲线输出。长块20%时间进度约8.65%、中点50%，减少前段整块跳出感。曲线每次toggle固定，保留高度变化不重启和原定位保护；不改#17/自动折叠/布局间距。
- 子代理复核：手动点击不会使remember的Animatable重建到1；顶层总结测自然内容高，内部思考/工具自己的视口上限不等于整个总结高度上限。未为首次恢复本来展开的历史条重播动画。
- Release编译+单测BUILD SUCCESSFUL55s，24tasks（5executed/19up-to-date）。最终测试计数见本节末；日志verify-out/reveal-easing-release.log。测试验证短曲线保持、长曲线前段/中点、按高度混合后的单调有界、恢复收起350ms。
- 仅ChatBlocks.kt与RevealDurationTest.kt有本轮产品/测试改动。现有0.8.628升版及文档修改保留。未Debug、出包、安装、提交或push本轮源码；手机仍0.8.628，下一包升0.8.629/vc650。

- 最终XML统计：198类/1221项/0失败/0错误/0跳过。


## 129. 2026-11-02（Codex）：0.8.629 Release 已安装

- 用户当轮授权出包并安装，体验§128的新展开曲线和恢复的收起效果。版本0.8.629/vc650，README同步。
- `:app:assembleRelease --offline --console=plain` BUILD SUCCESSFUL2m11s，55tasks（23executed/32up-to-date）；R8/资源收缩开启。日志verify-out/release-0.8.629-build.log。未重复单测，§128对应源码198类/1221项全绿。
- APK5,697,413B，aapt/output-metadata确认com.suixing.agent / 650 / 0.8.629；16KB zipalign检查通过。
- SHA256：2382170F8303AEA48E39BE6F86D3DAE936A819217870BD443E2829FD3A176EC5；设备base.apk完整哈希一致。
- 无线调试端口40891→38075，mdns自动发现并连接。构建结束首次install时adb server已重启导致device not found，重新mdns确认地址、connect后覆盖安装Success。
- 手机0.8.628/649→0.8.629/650；设备报告lastUpdateTime=2026-11-02 02:11:49。未自动打开App、操作屏幕或采手机日志/帧数据。
- 本包包含：<=160dp原展开曲线、>=480dp对称新曲线、中间连续混合；展开上限900ms，收起恢复350ms和原曲线；内容增长不重启动画。自动折叠规则保持。
- 本轮未Debug、提交或push；当前未提交内容含这两轮升版、§128曲线补丁/测试及文档。待用户真机体验，不把安装成功当动画验收。


## 130. 2026-11-02（Codex）：展开整体提速、按dp定时（未出包）

- 用户0.8.629反馈：短条太慢，长条还行但略慢，要求继续打磨。主代理规划、gpt-6-luna/max实施，主代理复核/统一验证。
- 仅调整展开时长：0..160dp为150..190ms；160..480dp为190..420ms；480..1200dp为420..680ms；超过1200dp封顶680ms。按实测高度经LocalDensity转换为dp，避免不同显示密度令同逻辑大小时长不同。
- 保留§128的新展开曲线/高度连续混合；首测高度同时决定一次toggle的曲线与时长，流式增长不重新启动。收起仍旧pixel公式/原曲线/350ms上限，没有连带提速。
- PROCESS_EXPAND_MS保持220，因TranscriptPosition引用它作为手势保护基准；注释改正为收起基准/兼容值。不修改#17/自动折叠/布局间距。
- 删除旧“收起总比展开短”的测试前提：用户现在明确要求短展开变快、收起保留，所以150..190ms展开可以短于220ms收起。测试分别约束展开分段/连续单调/cap、短条<=190、代表长条提速、收起旧值和既有曲线性质。
- `:app:compileReleaseKotlin :app:testReleaseUnitTest --offline --console=plain` BUILD SUCCESSFUL1m28s，24tasks（5executed/19up-to-date），日志verify-out/reveal-tempo-release.log。
- 本轮产品/测试修改仅ChatBlocks.kt和RevealDurationTest.kt，已保留此前0.8.629升版/曲线与文档未提交工作；未Debug/出包/安装/提交/push。手机仍0.8.629，不含本次提速，下一次包0.8.630/vc651。无真机体验通过结论。

- 最终XML统计：198类/1223项/0失败/0错误/0跳过。


## 131. 2026-11-02（Codex）：侧滑引起主页纵移——仅排查，用户额度不足要求收尾

### 用户确认与当前结论

- 用户：“侧滑手势进侧边栏的判定继续优化，有时候侧滑还导致主页细微上下移动”。异步澄清已明确答复：“聊天内容上下滚了一小段”。不是仅主页缩放时上下边缘的视觉变化。
- 主代理规划，gpt-6-luna/max子代理drawer_gesture只读排查；尚未批准/实施手势补丁。用户随后明确“没额度了收尾吧”，因此停止推进并记录遗留。**本项未修，不要声称已经优化或通过验收。**

### 已读源码证据

1. `ui/chat/ChatScreen.kt` LazyColumn `.pointerInput(state.conversationId)`约1204–1262：在PointerEventPass.Initial观察指针，只有downY/dy；`abs(dy)>CANCEL_FOLLOW_PX`时修改allowBottomResume/stickToBottom，并将moved/pressMoved设true。`CANCEL_FOLLOW_PX`在ChatComposer.kt约146为固定4px。横向手势的一点y抖动也会提前被记为纵向阅读，且Initial发生在Main归属前。这个观察器不消费指针、不直接scrollBy，故只能确认状态误判路径，不能单凭这处认定实际纵滚已解决。
2. 实际抽屉宿主是自建 `ui/drawer/SuixingDrawer.kt`，不是SuixingApp旧注释写的原生M3宿主。全屏父Box用`detectHorizontalDragGestures`，Main阶段子到父，子LazyColumn/Markdown横滚/选区先处理。没有比较累计abs(dx)/abs(dy)的显式方向优势锁；竞争按各轴先越slop与先消费决定，同帧dy过slop时子列表可能先取得纵向拖动。
3. ChatScreen无显式nestedScroll；纵滚来自LazyColumn。Markdown表格/代码存在横滚；正文SelectionContainer、输入框、Drawer会话列表/排序把手也要保留自己的手势优先权。
4. SuixingDrawer抽屉全开后的主页露出区有独立冻结层：故意全消费纵向、轻点关闭、横拖可关闭。不要误改此层；160ms先收后切、switchFromDrawer/token保护也不动。
5. SuixingApp主页graphicsLayer有scaleY缩放，但用户已明确是内容实际滚位；不要只删缩放动画当作修复。

### 接续要求与候选修复边界

- 先把被动观察器的“发生移动/取消长按”与“纵向滚动意图/取消追底”区分，记录dx+dy。水平主导不应取消追底，移动状态也不能继续仅看dy。
- 不要直接把4px改为touchSlop：历史用户要求轻微纵滑也能取消追底，阈值变大可能重现轻滑被拉回；应保留纵向敏感度并引入方向判定。早期小抖动的方向锁/后续转向如何处理需要设计清楚。
- 仅改观察器不能保证真实纵向滚动竞争消失，仍须复核宿主与LazyColumn的方向所有权。Main尊重子组件消费能保留横滚，但并不能倒过来阻止已经发生的子纵滚；不能把“增加Main方向判断”直接当成完整修复。
- 禁止简单在全页Initial抢先consume：会抢走Markdown表格/代码横滚和文本选区。也不要未经确认把现有全页横滑改成只左边缘。若需更早方向仲裁，先给出能保留子横滚/选区优先权的方案，再做最小修改。
- 不改#17锚点与追底协议，不用事后scrollBy拉回掩盖微动，不全局吞纵向事件。需要时补纯方向分类回归；仅Release验证，不私自操作手机/采日志/帧数据。

### 收尾状态

- 本轮仅只读调查和落档，没有手势源码变更，没有运行新的Gradle/测试，没有出包安装/提交/push。
- HEAD仍9a3a435（已push）；手机0.8.629/vc650，包含§128曲线，不包含§130展开提速。
- 原有未提交保留：README、app/build.gradle.kts（0.8.629）、ChatBlocks.kt、RevealDurationTest.kt（§128曲线+§130提速）、docs/continuation-20261102.md；既有.icon_ref/、旧docs和tasks未跟踪资料保留。
- 最新已验证待出包产品源码是§130：短展开150–190ms、长展开封顶680ms、收起350ms；Release编译+198类/1223项全绿。下一次出包须0.8.630/vc651，并须用户明确授权安装。

## 2026-11-02 新接续：以HANDOFF §144为准

其它agent已提交并发布至9aca6f2 / 0.8.640/vc661；此前本文§131收尾状态是历史记录。
用户已确认侧滑修好，本轮仅修底部操作图标涟漪偏心及加载更早的位置跳变。

- Buttons.kt：26dp点击盒共享InteractionSource，涟漪移到左对齐的15dp图标节点，
  消除盒中心与字形中心5.5dp差，保留尺寸/间距/半径。
- ChatScreen.kt + EarlierPagePosition.kt：排除加载按钮，按存活消息key解析新下标，
  使用-item.offset；等更大窗口与磁盘新head就绪，在新测量前仅请求一次。
  不再等待总项数增长/按数量差定位，也不跨两帧重复拉回。提交时读取当前视口，
  不把读盘期间手动滑动拉回点击时位置。新页先等当前按下/拖动/惯性结束才扩大显示，
  等待期间切会话则退出；不以定位请求打断在途滑动。加载时禁短尾部自动恢复追底。
- EarlierPagePositionTest新增8例。最终Release编译+全量通过（55s）：221类/1293项/
  0失败/0错误/0跳过；日志verify-out/earlier-page-idle-release.log，git diff --check通过。
- 没有真机UI验证、Debug、出包安装、提交push；手机版本保持0.8.640。

## 0.8.641 Release已安装（2026-11-02，HANDOFF §145）

- 用户授权，版本0.8.641/vc662，§144两处修复首次进包。Release构建4m1s成功，R8/资源收缩保留。
- APK5,699,385B，aapt/metadata、16KB zipalign检查通过。
- SHA256 0CD6D8117772BAFB17F80334E229FB1061710093005A3A2E7A7CDF9765E1224C，手机base.apk哈希一致。
- mdns自动发现38539端口，覆盖安装成功，设备确认0.8.641/662。
- 未自动启动或操作手机UI；待用户体验涟漪中心与连续/最后一页历史加载定位。
- 未Debug、重复单测、提交或push。下包0.8.642/vc663。

## 导航继续打磨与独立压缩文案（2026-11-02，HANDOFF§146）

用户确认0.8.641“点击加载历史不跳了”。本轮保留此结果，继续修导航：
长按期间pin窗口避免pendingTurn重居中后二次误跳；释放同步使用最终pull；手势finally清理。
导航按key在等待/预处理后重新查index，目标消失则退出；目标正文提前准备。
主动导航继续刻度补间，会话恢复仍直接取最终高亮、不下扫。近距滚动按距离180–440ms，
零距离略过；远距保留库内animateScrollToItem，不声称已重写远距动效。
独立无run压缩卡元信息只留压缩前约xx tokens，查看原文按钮不变。
窗口规则没有改：超过12个已加载轮次，中段第7根；两端钳位，少于12不补空位。
新增NavigationTargetTest5例，最终Release编译+全量单测1m18s通过：222类/1298项/零失败错误跳过，
日志verify-out/navigation-refine-full-release.log。未做UI自动化或实机验收，本轮未出包安装/升版/提交push。

## 0.8.642已安装（2026-11-02，HANDOFF§147）

用户授权出Release并安装，导航定位/动效与无run压缩文案改动首次进包。
版本0.8.642/vc663，Release构建2m31s成功，R8/资源收缩开启。
APK5,702,453B，aapt/metadata/16KB zipalign检查通过；SHA256
B8CA95ACBC49F44121AB7F9B2441E0B2810DD7820303A5DD7BD1E0985DFAC46B，设备base.apk完整哈希一致。
安装到192.168.0.102:38539成功，设备确认0.8.642/663。未自动启动或操作UI，待用户验收。
源码222类/1298项全绿基线保持，未重复单测、Debug、提交push或发布。下包0.8.643/vc664。

## 2026-11-02 新一轮导航/收展/抽屉与issue收尾

以HANDOFF§148及docs/ui-motion-issues-20261102.md为准。导航改为显式锚点交接与测量滚动落位，
晚测重新开始落位、底部sentinel按真实误差处理；仍有超远16阶段/名义3s预算限制，未实机验收。
总结展开/收起改dp五档连续时长与剩余行程反向；抽屉绘制层平移、起步后并行准备会话、共享300ms/12dp入场。
token文案左加8dp；补#60窄屏/大字Composer与可访问语义；#66/#68抓出未真正完成的任务队列/失败回滚/
排序误报成功/失败恢复arm竞态并补修。预览有硬限与取消；原生PTY只修EINTR超时边界。
GitHub已评论关闭#66/#67/#68/#69/#70/#71；#60/#61仍open，评论明确当前源码未push且不是手机版本已含。
最终Release编译与224类/1311项全绿53s；四ABI原生Release编译23s通过。无APK/安装/Debug/提交push。
手机0.8.642，下包0.8.643/vc664；新文件后续提交需纳入，详见明细文档。

## 0.8.643已安装（2026-11-02，HANDOFF§149）

按用户授权出Release并安装，0.8.643/vc664首次包含§148导航测量落位、五档总结收展、抽屉并行切会话和issue补漏。
构建2m3s成功；APK5,726,417B，aapt/metadata/16KB zipalign通过，SHA256
6A193C7B07CD70510C6572442C1FBA6D2CD11CAF6F113157C33D7AB1AB39513C与设备base.apk一致。
38539端口覆盖安装Success，设备确认0.8.643/664；没有自动启动/操作UI或采手机数据。
未重复1311项单测、Debug、提交push或发布。已知限制仍见明细文档，下包0.8.644/vc665。

## 0.8.643反馈后：导航两段移动与总结反向打磨（HANDOFF§150）

用户澄清停止/流式/键盘状态都会，最终正确但中途分两次；本轮只Release编译/测试。
旧导航晚测取消tween再开阶段，现改持久逐帧控制器保留速度连续落位；无结束snap，
零屏外估算按index方向推进，底部误差/系统动画倍率保留。旧16阶段/3s描述为历史，
新32ms积分/12视口每秒速度上限/15s墙钟取消，极端距离或物理钳位仍可能中止。
另确认Foundation placement-only更新layoutInfo可早于文字坐标；导航起止不再立即capture，
改为最终child.place后建立阅读点，迟到interaction也不能抢先。真实owner回归3例RED2→GREEN0失败。
总结保留普通五档时长/曲线，修复反向重复等测量、背向动量回弹、alpha突变，
spring按0.25物理px阈值并调整刚度；真实Compose 5000px/双向/60和120Hz尾帧用例通过。
最终Release编译+225类/1324项全绿，1m15s，verify-out/nav-fold-refine-release-final.log。
首轮新增帧限额断言浮点误差已补0.001px容差并全量重跑，fake零估算早visible也已修正。
完整说明见docs/ui-motion-issues-20261102.md末节。无UI/实机验收、无Debug/APK/安装/升版/提交push；
手机0.8.643/vc664，下包0.8.644/vc665，新代码/测试/文档后续提交时别漏。

## 0.8.644已安装（2026-11-02，HANDOFF§151）

用户授权出Release安装，0.8.644/vc665首次含§150连续导航、最终放置锚点交接、总结反向与像素尾帧修正。
构建2m43s成功，55tasks（23executed/32up-to-date），R8/资源收缩保留，未重复1324项全绿单测。
APK5,726,425B；aapt/metadata/16KB zipalign通过，SHA256
C51E1F52A4324CE12681464F6874404AACD02F97C64D293B4EFBEA3693E53B65，本地=手机base.apk。
自动发现38539端口，覆盖安装Success，设备确认0.8.644/665，lastUpdateTime17:39:42。
未启动App/操作屏幕/采设备数据，未Debug/提交push/发布；视觉效果待用户验收。下包0.8.645/vc666。

## 向上定位末段急停（HANDOFF§152）

用户0.8.644“暂时不会”二次移动，新增上行快到目标时突然停住。真实Foundation工厂+不等高行/-280offset
新增3例RED2失败，向下对照过；上行旧尾段200→164→75px→0，物理顶端也会消费不足急停。
NavigationMotion把携带速度限定到实际剩余误差的临界阻尼包络，积分后继续按剩余误差限幅；
首项向上按可达-firstVisibleItemScrollOffset提前刹车，残余负offset不可达时返回ScrollClamped。
原统一参数/放置交接/总结不变，无新动画阶段或越过后拉回。
最终Release编译+226类/1327项全绿，1m5s，verify-out/navigation-braking-final-release.log；
新3例在60/120Hz覆盖上下/顶端，末4帧≤3px、最后≤1px，旧交接3例也过。
未出包安装/Debug/升版/提交push/采手机数据；手机0.8.644/vc665，下包0.8.645/vc666。
详细依据与限制见docs/ui-motion-issues-20261102.md末节；新NavigationBrakingTest提交勿漏。

## 0.8.645已安装（2026-11-02，HANDOFF§153）

用户授权出Release安装，0.8.645/vc666首次含向上末段减速/物理顶端提前刹车。
构建1m51s成功，55tasks（23executed/32up-to-date），R8/资源收缩开启；未重复226类/1327项全绿单测。
APK5,726,313B；aapt/metadata/16KB zipalign通过，SHA256
9E6A9260CBA3BC2C2A6247DB33B27C8ED70B7A0F6E6DDE7A881459BAF342F168，本地=设备base.apk。
自动发现38539端口，覆盖安装Success，设备确认0.8.645/666，lastUpdateTime18:06:32。
未启动/操作手机或采设备数据，未Debug/提交push/发布；实际手感待用户验收，下包0.8.646/vc667。

## 大跨度导航提速（HANDOFF§154）

用户0.8.645确认末段可以，要求大跨度稍快。剩余≤2屏保留12视口/s，2..10屏smoothstep升18，
≥10屏18；步后速度cap同步，原omega14/末段误差包络/物理首端/放置交接不变，无新动画阶段。
新TravelDurationTest5例RED仅远距预算失败→最终全绿；首轮基线2屏60Hz0.783预估值已按实测0.800更正后重跑RED。
控制器60Hz模拟20屏2.267→1.850s、50屏4.767→3.517s；短0.6/2屏不变。120Hz远距约短18%/26%。
最后2屏仍原cap、双向准确不越界/末帧≤1px，旧急停3例/放置交接3例都过。
最终Release编译+227类/1332项全绿，1m52s，verify-out/navigation-travel-final-release.log。
无Debug/APK/安装/升版/手机采集/提交push，手机0.8.645/vc666，下包0.8.646/vc667。
逻辑帧模拟不等于实机耗时/FPS，新NavigationTravelDurationTest提交勿漏，详细对照见motion文档末节。

# Issue #50 复核与本轮处理记录

来源：[issue #50](https://github.com/zeng6125-rgb/suixing/issues/50)，审计基线 `23a028e`。本轮从 `9029f8d` 接续，源码版本 `0.8.532 / vc553`。issue 尚有未完成项，保持开启；安装与发布口径见文末。

原文有 **145 个不同的原始编号**，另有未附原始编号的“长会话重新回答缺全文兜底”。不能把标题的 147 当成全部已修复数量。这里逐个覆盖原文实际编号；`CHAT-R-02`、`CHAT-VM-03` 原文未出现，不补造编号。

“修复”表示已经修改源码并经编译，相关纯函数有回归测试；不代表真机 UI 通过。“已具备”表示现有实现已满足或报告前提不成立。“保留”写明具体理由，仍可存在成本，不能算修复。“待验证”是未解决的体验风险，不伪装完成。

初轮为 95 修复、36 保留、11 已具备、3 待验证。2026-10-27 对原 36+3 全部重新复核后的当前统计：

修复：117 | 部分修复：7 | 保留：6 | 已具备：12 | 待验证：1 | 未完成：2。

“部分修复”明确包含尚未完成的工作；“未完成”是已有约束下仍未解决的性能问题；“待验证”同时保留尚未消除的布局峰值，均不计入完整修复。

本轮额外完成：

- 输入法：按系统目标高度逐像素推进，去掉 4px 台阶/定时回弹窗口；底部避让正确缩小固定高度约束，inset 在布局阶段读取。
- 会话切换：保留 160ms 抽屉收起后再切换与连点令牌；取消过期冷读，恢复等待本会话分批内容真正落地，再执行一次定位。
- 导航条：保持右距 4dp、宽 20/16/10dp；当前轮采用视口 28% 探针二分；点击按消息实际坐标跳转，连点/切会话取消旧任务，并有附着等待上限。
- 六处组件归位全部完成；import 使用解析到声明/调用目标的语义分析（包括委托 getValue/setValue），仅清理本轮改动文件；不是名字字符串匹配。
- 未调大正文缓存 5；未拆仓库锁、未削弱 fsync、未删除隔离历史；未新增真机采集。用户随后授权 Release、adb 安装、提交和 push，安装结果见文末。

未编号项：`regenerate()` 先读完整 wire，再移除最后回答；IO 返回后复检会话/run，避免长会话仅凭屏幕尾窗重答。

## CHAT-VM

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| CHAT-VM-01 | 修复 | Finished 从已合入 delta 的 base 收尾，避免丢尾字。ChatViewModel.handleEvent。 |
| CHAT-VM-02 | 修复 | 后台会话无法发送时先恢复已取出的排队消息。drainPendingTurns。 |
| CHAT-VM-04 | 修复 | run Job 注册后启动，完成时按实例移除；不再永久挡住手动压缩。 |
| CHAT-VM-05 | 修复 | state 的 map 本身串行，并非报告所述并发 ++；实际发现同列表下一次发布退回旧版本号，改由 MessageRevisionStamp 持续盖章，有回归测试。 |
| CHAT-VM-06 | 修复 | send 与附件异步处理先捕获 chatId；清草稿、追加附件只写所属会话。 |
| CHAT-VM-07 | 修复 | 返回值明确为“请求已接受”；onComplete 返回实际合并结果，失败也释放恢复闸门。 |
| CHAT-VM-08 | 修复 | 删除 init 中 lastOpenedId 恒空的分支，保留真正的冷启动恢复读取。 |
| CHAT-VM-09 | 修复 | 删除 live 已早退后的无效合并及 live?.messages 分支。 |
| CHAT-VM-10 | 修复 | 编辑重发时先建 keptIds，再过滤 activeRuns，去掉嵌套 any。 |
| CHAT-VM-11 | 修复 | 编辑消息的 checkpoint 过滤共用 firstPositions 索引，保留重复 ID 取首次位置语义。 |
| CHAT-VM-12 | 修复 | 全文编辑重发的 checkpoint 边界共用首次位置索引。 |
| CHAT-VM-13 | 修复 | sessionStats 使用 screenState、settings、conversations；逐字 delta 不再逐次扫描会话元数据。 |
| CHAT-VM-14 | 修复 | 草稿已存在消息的判重改 ID 集合；正文和思考前缀判重仍保留，以免丢恢复内容。 |
| CHAT-VM-15 | 部分修复 | writeLiveDraft 先比较 run、消息/parts 身份和缓冲长度，静默时在复制正文之前退出；按消息身份缓存当前 run 的过滤结果。 尚存：内容持续变化时仍每秒组装完整当前轮并写完整草稿，O(当前轮大小) 成本未解决；没有改恢复格式或周期。 |
| CHAT-VM-16 | 修复 | screenState 去除逐字字段，LiveMessageParts 在实时消息子树单独订阅；未冻结输出。 |
| CHAT-VM-17 | 修复 | 累计统计 stateIn(WhileSubscribed) 共享；圆环与累计明细复用同一次读取。 |
| CHAT-VM-18 | 修复 | liveStats 只在顶栏用量浮层内容实际挂载时收集。 |
| CHAT-VM-19 | 修复 | ImageBlock 的 Base64 解码和内容缓存键在 Default 计算；记忆 ImageRequest，避免重复创建请求。 |
| CHAT-VM-20 | 修复 | 查看原文改有界 LazyColumn，消息逐项组合。 |
| CHAT-VM-21 | 部分修复 | ChatScreen、LiveContent、ComposerDraft、ChatContext 改 collectAsStateWithLifecycle，界面离开 STARTED 时停订阅。 尚存：ViewModel 内部 Eagerly 投影仍活跃；聊天覆盖层仅被另一页盖住、Activity 仍 STARTED 时也不会冻结。 |
| CHAT-VM-22 | 已具备 | visible 仅供纯函数测试，计算属性不保留资源；删除只会迫使测试复制同一生产规则，保留。 |
| CHAT-VM-23 | 修复 | 顶栏用量入口删除无效 anchorCenter/cardWidth 测量状态写入。 |
| CHAT-VM-24 | 修复 | 附件容器改并发不可变列表；forgetConversation 清理；预览只保留最长边 160px 缩略图，完整预览按需解码。未发送附件跨会话保留。 |
| CHAT-VM-25 | 修复 | 草稿使用原子 update；清空和 forgetConversation 移除对应条目。 |
| CHAT-VM-26 | 保留 | 复核 steers/pendingTurns 及 forgetConversation：对应会话删除时清表，失败未送达内容可修改/重发。 尚存：未删除的会话仍会保有这些内容；没有擅设数量上限或静默丢弃策略。 |
| CHAT-VM-27 | 修复 | discardIfUntouched 检查文字和图片草稿；仅磁盘删除成功后 forgetConversation，清理所属状态。 |
| CHAT-VM-28 | 修复 | 与 04 同源；完成回调移除 Job 闭包，不仅靠 stop/onCleared。 |
| CHAT-VM-29 | 修复 | endSession 在最后 flush、摘掉 session 后 drop deltaBuffer，清掉节流时钟。 |

## CHAT-R

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| CHAT-R-01 | 修复 | 抑制折叠块补偿仅在 PIN_LIVE_HEIGHT 且 pinActive 时开启，同源控制。 |
| CHAT-R-03 | 修复 | 屏幕不再按 messagesRevision 另起 sessionTotals IO，复用共享累计统计流。 |
| CHAT-R-04 | 修复 | 后台 run 结束清理 runStartedAt；删除会话时也清理，不仅扫当前前缀。 |
| CHAT-R-05 | 修复 | errorLine 按错误和回执列表记忆，避免无关重组重复全文比较。 |
| CHAT-R-06 | 修复 | 视口探针用有序行坐标二分定位；10,000 行比较次数有上限测试。 |
| CHAT-R-07 | 未完成 | 继续保持单 item，减少逐字订阅、几何快照写入和重复解析；未恢复旧虚拟化。 尚存：已加载正文仍全量组合/测量；长会话布局复杂度没有解决，不能算“不必做”。 |
| CHAT-R-08 | 修复 | 同一消息版本为所有 checkpoint 共建位置索引；checkpointSpan 可使用该索引。 |
| CHAT-R-09 | 修复 | 行坐标移出 SnapshotStateMap；仅首次放置/高度变化更新几何版本，滚动不再逐行写快照坐标。 |
| CHAT-R-10 | 修复 | stickToBottom setter 加等值短路；Compose State 原已按相等值抑制通知，报告的“必然重组”不成立。 |
| CHAT-R-11 | 待验证 | 保持原子锚定、首次打开分批，解析器减少重复处理；未把加载更早偷偷改回逐帧追加。 尚存：大量历史单次组合/测量峰值仍未解决；P1 原位和实际耗时均未真机验证。 |
| CHAT-R-12 | 部分修复 | RevealHeight 用 derivedStateOf 只按“可见或动画未归零”决定内容挂载；progress 在协程和测量阶段读取，不再逐帧触发组合。 尚存：动画仍逐帧测量/放置，补偿与贴底机制保持；展开/收起残影、贴底和上翻需真机验证，未证明 FPS。 |
| CHAT-R-13 | 修复 | 删除无读取者的 wasScrolling 状态写入。 |
| CHAT-R-14 | 已具备 | 思考列表和工具输出列表当前已有 reasoning-line/tool-output contentType。 |
| CHAT-R-15 | 修复 | TurnScrubber 的缩放值改在 graphicsLayer 读取，12 条独立 110ms 动画和宽度/右距保持。 尚存：保留必要的绘制动画成本，导航外观、快速连点和切会话手感待真机确认。 |
| CHAT-R-16 | 修复 | 思考展开表随会话删除清理；保留现存会话的用户展开选择。 |
| CHAT-R-17 | 修复 | 坐标表按会话、消息版本和分页重建；不再是全局快照行顶表，旧坐标随实例释放。 |
| CHAT-R-18 | 修复 | ChatScroll 新增 retainConversations，四张表随会话删除清理；forgetSpot 保留跟底意图是有意的。 |
| CHAT-R-19 | 修复 | 仍在内存保留各会话窗口；Bundle 仅保存当前会话，避免所有访问记录序列化累积。 |
| CHAT-R-20 | 修复 | anchors 随会话删除清理；runStartedAt 同时跟随 run 结束清理。 |

## CHAT-IN

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| CHAT-IN-01 | 修复 | Composer/ContextRing 删除未使用的累计参数及冗余统计订阅；累计明细继续由顶栏 ContextRingCard 展示，不是补回第二套 UI。 |
| CHAT-IN-02 | 修复 | 录音自动结束回调先设置，再进入 Listening 并启动 recorder。 |
| CHAT-IN-03 | 修复 | TurnMetrics 在点击事件同时更新 shown/open，不再在组合期回写 shown。 |
| CHAT-IN-04 | 修复 | ContextRing 冗余 LocalContext import/变量与过期 stats 参数注释已清理。 |
| CHAT-IN-05 | 修复 | 工具合并按连续工具区段记忆，后续文字增量不再让整个 parts 键失效。 |
| CHAT-IN-06 | 修复 | 模型列表改 LazyColumn，按提供方/分组/模型给 key 与 contentType；groupModels 按 offers 预计算。 |
| CHAT-IN-07 | 修复 | LiveParts 逆向下标查找目标，替换元素只复制列表一次；sealThinking 读 buffer.length，不为计数生成全文。 尚存：每次发布仍有一次引用列表复制，这是防 StateFlow 吞更新的现有版本契约；未原地改列表。 |
| CHAT-IN-08 | 修复 | 更正实时 parts 记忆命中相关注释；保留真实文字变化时重新映射，避免再次冻结流式内容。 |
| CHAT-IN-09 | 修复 | 三段进度条的 denominator 移到循环外一次计算。 |
| CHAT-IN-10 | 修复 | ComposerDraftText 独立订阅会话草稿；打字不再进入聊天正文的 screenState。 |
| CHAT-IN-11 | 修复 | VoiceOverlay 的电平改为 Canvas 绘制期读；五根条统一绘制，计时只发布整秒，保持条宽、间距和像素舍入。 尚存：录音电平仍按原 100ms 采样；真实外观和录音交互未真机验证。 |
| CHAT-IN-12 | 修复 | 删除顶栏用量入口无消费者的第二套 popupMotion，动画由 MenuCard 负责。 |
| CHAT-IN-13 | 修复 | TurnMetrics 删除重复的透明 Popup/BackHandler，仅使用 MenuCard 的关闭层。 |
| CHAT-IN-14 | 修复 | 上下文三段进度改 drawBehind 按比例填充固定轨道，保留原像素舍入与圆角裁切。 尚存：数值变更仍需重绘，未以图层整体缩放改变圆角。 |
| CHAT-IN-15 | 修复 | 录音 stop 与临时 wav 删除使用 NonCancellable IO + finally，取消转写也清理临时文件。 |

## COMP

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| COMP-01 | 已具备 | info 与派发已共用同一 LineInfo 和 isTableStart；没有报告所称两套表格判定。 |
| COMP-02 | 保留 | 补纯函数回归：parent → child → next/last，深度和所有文本完整保留。间距继续用现有块间规则。 尚存：嵌套列表仍拆多个块，可能有现有块间距；这是排版取舍，没有把布局数量说成零成本。 |
| COMP-03 | 修复 | 末尾标题、列表末项、代码、引用、表格末单元格都可承接同一绘制期闪烁光标；代码保持 literal 文本，复制内容不带占位。 尚存：水平分隔线没有文本光标；各块流式末尾外观仍待真机确认。 |
| COMP-04 | 修复 | LiveBlocks 统一 CRLF/CR 换行并使用围栏安全前缀，替换文本时清掉旧前缀。 |
| COMP-05 | 已具备 | blinkingCursorAlphaState 仅在 cursor 为 true 的条件分支中调用；分支退出会取消 LaunchedEffect。 |
| COMP-06 | 已具备 | vectorResource 自带资源记忆；不存在每次重组都重新解析 XML 的前提。 |
| COMP-07 | 修复 | settledMarkdownPrefix 用奇偶计数单次反向扫描；随机输入与旧边界规则对比测试。 |
| COMP-08 | 修复 | urlEnd 正向累积括号数，剥尾不再每次重扫 URL。 |
| COMP-09 | 修复 | LineInfo 按空行/围栏/标题/分隔线/引用/表格/无序/有序的原派发顺序短路，只做当前分支所需判定。 尚存：解析语义由全量 Markdown 纯函数回归验证，未新增另一套独立派发规则。 |
| COMP-10 | 修复 | LineInfo 记住 trimmed，块派发/段落复用；代码体 joinToString 只生成一次。 尚存：仍保留语法必需的行拆分/局部字符串，没有宣称零分配。 |
| COMP-11 | 修复 | 边界函数先返回结束下标，仅边界长度或前缀内容改变时重建稳定字符串；两缓存处理同长度替换/清空回归。 尚存：尾段仍须解析；围栏边界仍扫描输入，没有声称整体 O(增量)。 |
| COMP-12 | 未完成 | 本轮减少 parser 重复判断和字符串复制，但缓存未命中的解析仍在组合主线程。 尚存：冷打开大文本依旧可能阻塞主线程；尚无满足不闪烁、不后台整页预热约束的完整替代实现。 |
| COMP-13 | 修复 | 稳定块用内容+重复序号定位；增长尾块仅按块类型维持槽位，追加正文不重建它。纯函数回归覆盖插入、重复、增长和类型改变。 尚存：相同内容重复项按出现次序区分；最后块转为稳定块时允许切换身份，不声称任意编辑仍保住所有选择态。 |
| COMP-14 | 已具备 | AnnotatedString 的光标占位仅随文本变化；闪烁 alpha 在 drawBehind 读取，并非每次闪烁复制文本。 |
| COMP-15 | 修复 | MarkdownBlock 与 MarkdownBlocks 的代码背景统一 0.6 alpha，选中高亮可透出。 |
| COMP-16 | 部分修复 | TableRow 移除 IntrinsicSize.Min；分隔线按实际放置坐标在 drawBehind 画，文本每轮正常测量一次，保持权重舍入/RTL。 尚存：整张表仍全量组合和布局，超长行数成本未解决；表格外观/选择和性能需要真机样例。 |
| COMP-17 | 修复 | SettledBlocks 同时限制 64 条与 512K 字符，单条超限不入缓存，淘汰同步扣减字符计数。 |
| COMP-18 | 保留 | 复核 rememberGrowingMarkdown：LiveBlocks 只在流式分支创建，定稿 MarkdownCache 随组合释放，全局 SettledBlocks 受 64 条/512K 字符限制。 尚存：分支切换可能暂留上一份本地解析，稳定 prefix 是局部字符串；单 transcript 常驻内容的总成本仍见 CHAT-R-07，不能泛称总内存恒定。 |

## ENG

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| ENG-01 | 修复 | ChatRepository.create 整段进入原有 Mutex，避免列表读改写覆盖。 |
| ENG-02 | 修复 | UsageStore.refresh 的读取、计算、写缓存在同一 Mutex；写成功后才发布 snapshot。 |
| ENG-03 | 修复 | 性能缓存采用 fsync 临时文件后原子替换，不再先删旧文件。 |
| ENG-04 | 修复 | 用量缓存补 fd.sync 并原子替换。 |
| ENG-05 | 修复 | drainSteering 将插话 wire 范围同步记录进 spans，两处调用均接线。 |
| ENG-06 | 修复 | PerfMonitor.recent 直接按下标取最后 limit 条并逆序；summary 只快照请求窗口，按原顺序累加。 尚存：锁内仍复制所需条目以取得一致快照；0/负值/超上限语义由原测试覆盖。 |
| ENG-07 | 修复 | UsageSnapshot 每实例懒算一次天/模型/总量聚合；聚合字段不参与持久化，copy 新建缓存。 |
| ENG-08 | 修复 | MessageCache 改插入顺序表，get/put 显式 remove+put 晋升，isDiskBacked 用 O(1) 查询且不晋升。 尚存：容量仍 5，所有入口仍同步；原 LRU/状态窥视不改顺序的测试覆盖。 |
| ENG-09 | 部分修复 | 每轮请求前一起求 historyChars/totalChars/imageCount，完成时复用同一未变请求的合计。 尚存：仍每轮遍历一次消息列表，使用 String.length 估算；未实现跨轮可靠增量估算。 |
| ENG-10 | 修复 | Core 不再 runBlocking 冷读 DataStore 主题；启动外观用小型偏好快照，异步跟随设置更新。 |
| ENG-11 | 部分修复 | saveLive 的 JSON 编码移到锁外，取消检查后仍在原 Mutex 内原子写/fsync；run 收尾 cancelAndJoin writer 防迟写复活。 尚存：appendTail 持久写和冷读仍共享锁，活动写入时仍可等待；没有测出它占用户所述几秒的比例。 |
| ENG-12 | 修复 | ScheduleStore 缓存快照/peek；工具执行、启动 arm 在 IO；通知构建不再同步全表读盘。 |
| ENG-13 | 修复 | 引擎状态表用弱引用+最近 16 项强引用；仍被收集的流保持身份，全部访问在同一同步入口。 |
| ENG-14 | 修复 | 完成的后台进程关闭管道并释放 Process 引用，已结束元数据最多保留 20 条；运行中任务不淘汰。 |
| ENG-15 | 已具备 | LiveSampler 生命周期由性能页控制，报告本身也注明设计正确。 |
| ENG-16 | 部分修复 | 先做输入身份/缓冲长度短路；按当前 run 缓存筛选消息；仅成功保存后推进 written 与输入标记。 尚存：活跃长回答仍每秒完整副本；改日志式增量恢复协议未做，不算“没必要”。 |
| ENG-17 | 保留 | 确认 .corrupt 不进 meta 解码；删掉早已无行为的 pruneQuarantine 空函数/调用及误导的 30 天清理常量与旧注释。 尚存：磁盘占用和目录枚举随隔离文件数量增加仍存在；没有删除任何文件，也没有实现用户手动恢复/清理界面。 |

## SVC

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| SVC-01 | 修复 | 字面与正则检索统一 case_sensitive 语义；新增大小写参数纯函数测试。 |
| SVC-02 | 修复 | Shell reader 与结果快照共用同步 OutputCapture，finally 关闭三条流。 |
| SVC-03 | 修复 | 无障碍 gesture 等待有“手势时长 + 2 秒”超时，迟到回调不恢复已取消 continuation。 |
| SVC-04 | 修复 | 损坏 JSON 响应使挂起请求立即失败；保留普通启动输出噪声与合法无 id 通知，避免错误拒绝正常 MCP 服务。 |
| SVC-05 | 修复 | 悬浮窗 renderConversation 增加修订号和取消检查，旧挂起渲染不能覆盖新结果。 |
| SVC-06 | 修复 | Shell 只把 read<0 当 EOF；0 继续，超时/取消路径关闭管道。 |
| SVC-07 | 修复 | 朗读清洗正则提升到共享规则表，保持替换顺序和文字语义。 |
| SVC-08 | 修复 | WebTools 余下四个正则提升为文件级常量。 |
| SVC-09 | 修复 | 去掉 Byte 前缀装箱；保留现有文件上限与检索结果协议，不更换整套文件读取模型。 |
| SVC-10 | 修复 | 技能目录 oneLine 共用空白正则。 |
| SVC-11 | 修复 | FloatConsoleService 每次发射只排序一次，首项供最新运行状态，同一 orderedRuns 供停止区域。 尚存：保留与实际 run 更新一致的视图刷新，不把停止操作延迟合并。 |
| SVC-12 | 修复 | TouchFx 取消每行抓取完整调用栈，source 改为事件类型，保留工具/事件内容。 尚存：诊断取舍：日志不再提供任意外层调用点的文件:行号；没有采集或读取真机日志。 |
| SVC-13 | 修复 | TouchFx/FloatConsole 共用线程安全 DateTimeFormatter，在后台格式化调用时捕获的事件时间与时区。 尚存：纯函数测试对照旧 SimpleDateFormat，覆盖三个时区/夏令时；日志写入队列与必要字段仍保留。 |
| SVC-14 | 已具备 | 已有阈值回落，约再写 128KB 才重写一次，非报告所述“超限后每行重写”。 |
| SVC-15 | 修复 | renderStopArea 按运行任务键列表等值短路，内容相同不拆建视图。 |
| SVC-16 | 修复 | 状态点运行布尔未变即返回，不重复建 drawable/无限动画。 |
| SVC-17 | 修复 | FloatConsole 的 Settings.canDrawOverlays 移至 traceWorker，其余事件字段/时刻在调用处捕获。 尚存：诊断取舍：权限字段是后台写入时的权限，可能略晚于事件发生时；没有宣称即时权限快照等价。 |
| SVC-18 | 保留 | 复核亮灯/驱动时 1 秒、空闲窗口 5 秒、无窗口 removeCallbacks 三条分支，不改成无恢复机会的彻底停表。 尚存：窗口仍存在时会有周期唤醒，耗电成本存在；未用“可忽略”代替测量，也未新增观测。 |
| SVC-19 | 修复 | MCP stdout/stderr BufferedReader 使用 use，回收进程时关闭流。 |
| SVC-20 | 修复 | 遍历窗口树的 root/child 使用 finally 归还 API<33 节点。 |
| SVC-21 | 修复 | 输入焦点、递归 editable、点击祖先的节点明确所有权，调用方与遍历方分别归还。 |
| SVC-22 | 修复 | close 调用 dispatcher.cancelAll；newBuilder 继续共享 dispatcher/连接池，不把共享客户端误判成连接池泄漏。 |
| SVC-23 | 修复 | Speaker 首次 speak/进入语音设置时懒初始化，pending/inFlight 为空且空闲 60 秒释放；generation 拒绝迟到回调，stop 清队列。 尚存：保留后台朗读和最多 8 条待播；空闲后首句需重新初始化。真实引擎初始化/完成/错误回调未真机验证。 |

## APP

| 编号 | 结论 | 修改或复核依据 |
|---|---|---|
| APP-01 | 修复 | 抽屉行 pointerInput 仅依赖 enabled，回调通过 rememberUpdatedState 更新。 |
| APP-02 | 修复 | Choreographer 帧回调在取消时 remove，恢复前检查 continuation.isActive。 |
| APP-03 | 修复 | 性能记录不再使用毫秒+会话拼接的冲突 key，采用列表位置唯一键。 |
| APP-04 | 修复 | SuixingApp 用 remember 的普通引用持有 drawerOrigin；导航 route 驱动现有组合，返回转场仍读取该目标。 尚存：保留从抽屉进入页的无动画返回规则与路由结算；转场实际外观待真机。 |
| APP-05 | 已具备 | pending 使用保序 Map 增删，按插入顺序审批；不是随机挑选，更不按 UUID 排序抢占当前审批。 |
| APP-06 | 修复 | Drawer 删除内层重复 drawerSections，列表与拖拽读取同一计算结果。 |
| APP-07 | 修复 | 新消息回顶的 normal/dropped 判断使用集合，保持原有顺序与置顶规则。 |
| APP-08 | 修复 | 性能页只读 pin.log 有界尾窗，最多取 30 行；未访问真实设备日志。 |
| APP-09 | 修复 | 抽屉把手的五个比例提升为文件级 FloatArray，绘制复用常量。 尚存：只去分配，不声称它独自解决滚动掉帧。 |
| APP-10 | 修复 | SettingsRoot 三组描述符 remember 一次，动作通过 rememberUpdatedState 取最新回调。 尚存：静态分组和入口文字不变，没有重做设置路由结构。 |
| APP-11 | 修复 | 文件预览二进制检测改有界字节下标扫描，避免前缀装箱。 |
| APP-12 | 修复 | 抽屉行只在长按菜单开启时挂载位置测量，不再滚动逐行写菜单坐标。 |
| APP-13 | 修复 | 外壳 open/onOpenSettings 记忆稳定回调；界面设置订阅使用 lifecycle。设置本身仍需驱动抽屉外观与服务开关。 |
| APP-14 | 修复 | 右侧 DrawerDismissLayer 子作用域读取 targetValue；pointerInput 以 drawerState 稳定，最新 close 经更新引用调用。 尚存：透明点击层仍只覆盖面板右侧；四条收起仍共用 160ms FastOutLinearIn，未改 snapTo/自绘遮罩。 |
| APP-15 | 保留 | 复核 reorderWithinSection 同行/跨区返回 null，仅有效跨行更新 orderState；sections 已按会话+顺序 remember。 尚存：pointerY 是普通 DragTracker 字段，非每个 move 写 Compose 状态；每事件仍查可见行，真实跨行仍重排，未声称零成本。 |
| APP-16 | 已具备 | Kotlin 2.4 Strong Skipping 会记忆组合中的 lambda；无裸 IME 快照读后，不把 lambda 字面量本身当逐帧重组证据。 |
| APP-17 | 修复 | PerfScreen 的 DeviceMetrics 独立子作用域只在 live item 存活时采样；设备与帧率各自 repeatOnLifecycle(STARTED)，取消移除帧回调。 尚存：保留原时间窗/分位数定义；性能页仍有必要采样开销，未拿此结果证明其它页面实际帧率。 |
| APP-18 | 修复 | 补 lifecycle-runtime-compose，点名页面/外壳的流改 collectAsStateWithLifecycle。 |
| APP-19 | 已具备 | 抽屉两个会话区当前已有 conversation contentType。 |
| APP-20 | 修复 | 能力页改按卡片的 LazyColumn；卡片内容与操作保持原样。 |
| APP-21 | 修复 | 模型数 >12 时在原 Surface 内用最高 480dp 的 LazyColumn，按模型 key 逐项挂载；少量模型维持原 Column。 尚存：可见交互变化：长模型卡片在卡片内部滚动；最近 30 天原样，内外嵌套滚动手感未真机验证。 |
| APP-22 | 修复 | PendingApprovals 独立作用域订阅 pending，仅有审批时订阅会话标题；外壳不跟待审批表重组。 |
| APP-23 | 已具备 | 复核 AssistantEditor 选图、裁剪、保存：编辑器退出后组合引用释放，协程完结后可 GC；已有取消/保存完成清理。 尚存：退出时不是同步释放像素；贸然 onDispose.recycle 会与 IO 保存/绘制争用，因此未加不安全回收。未测得实际泄漏。 |
| APP-24 | 修复 | lastSeen 随当前会话 ID 集合 retainAll，删除会话不再留下条目。 |
| APP-25 | 修复 | 头像裁剪缩放用 graphicsLayer，不每次改变 requiredSize 重新测量图片。 |

## 原 36 项保留与 3 项待验证：逐条重新复核

本表固定覆盖初轮的原 39 项，不以新分类遗漏条目。初轮中“规模小”“代码原本如此”“诊断未获授权”等不足以支持保留的理由已改正。源码修复与真机验收分别记录；其它 106 个原始编号继续见上表。

| 原始编号 | 初轮理由重新审视 | 本轮结果与源码依据 | 剩余工作或保留的具体成本 |
|---|---|---|---|
| CHAT-VM-15 | 完整恢复协议需要保留；此前把协议必要性等同于每秒重复组装不可优化，理由过宽。 | **部分修复**：writeLiveDraft 先比较 run、消息/parts 身份和缓冲长度，静默时在复制正文之前退出；按消息身份缓存当前 run 的过滤结果。 | 内容持续变化时仍每秒组装完整当前轮并写完整草稿，O(当前轮大小) 成本未解决；没有改恢复格式或周期。 |
| CHAT-VM-21 | 内部流供后台 run、版本盖章和内部观察者使用，不能一概改成无人订阅就停止；界面订阅则可以收敛。 | **部分修复**：ChatScreen、LiveContent、ComposerDraft、ChatContext 改 collectAsStateWithLifecycle，界面离开 STARTED 时停订阅。 | ViewModel 内部 Eagerly 投影仍活跃；聊天覆盖层仅被另一页盖住、Activity 仍 STARTED 时也不会冻结。 |
| CHAT-VM-26 | 未送达的插话/排队消息是用户内容，不能按已完成任务清掉；原保留理由成立。 | **保留**：复核 steers/pendingTurns 及 forgetConversation：对应会话删除时清表，失败未送达内容可修改/重发。 | 未删除的会话仍会保有这些内容；没有擅设数量上限或静默丢弃策略。 |
| CHAT-R-07 | 单 transcript 是当前底锚、整段选择结构；旧逐行虚拟化曾回归并被否决。这是方案约束，不等于性能问题不存在。 | **未完成**：继续保持单 item，减少逐字订阅、几何快照写入和重复解析；未恢复旧虚拟化。 | 已加载正文仍全量组合/测量；长会话布局复杂度没有解决，不能算“不必做”。 |
| CHAT-R-11 | 同会话“加载更早”一次出现是既定防闪烁要求；不能据此把单次布局峰值归为已处理。 | **待验证**：保持原子锚定、首次打开分批，解析器减少重复处理；未把加载更早偷偷改回逐帧追加。 | 大量历史单次组合/测量峰值仍未解决；P1 原位和实际耗时均未真机验证。 |
| CHAT-R-12 | 以前只说需要真机而没有做可独立审阅的读取范围收敛，不充分。 | **部分修复**：RevealHeight 用 derivedStateOf 只按“可见或动画未归零”决定内容挂载；progress 在协程和测量阶段读取，不再逐帧触发组合。 | 动画仍逐帧测量/放置，补偿与贴底机制保持；展开/收起残影、贴底和上翻需真机验证，未证明 FPS。 |
| CHAT-R-15 | 最多 12 条不代表逐帧组合不可避免；删除动画也不是唯一优化方案。 | **修复**：TurnScrubber 的缩放值改在 graphicsLayer 读取，12 条独立 110ms 动画和宽度/右距保持。 | 保留必要的绘制动画成本，导航外观、快速连点和切会话手感待真机确认。 |
| CHAT-IN-07 | 版本化列表和共享 GrowableText 必须保留，但三段列表拼接并非必要。 | **修复**：LiveParts 逆向下标查找目标，替换元素只复制列表一次；sealThinking 读 buffer.length，不为计数生成全文。 | 每次发布仍有一次引用列表复制，这是防 StateFlow 吞更新的现有版本契约；未原地改列表。 |
| CHAT-IN-11 | 有限叶子不代表 10Hz 尺寸变化只能靠重组/重排。 | **修复**：VoiceOverlay 的电平改为 Canvas 绘制期读；五根条统一绘制，计时只发布整秒，保持条宽、间距和像素舍入。 | 录音电平仍按原 100ms 采样；真实外观和录音交互未真机验证。 |
| CHAT-IN-14 | 比例决定可见长度，但不必决定子布局宽度；原保留理由不成立。 | **修复**：上下文三段进度改 drawBehind 按比例填充固定轨道，保留原像素舍入与圆角裁切。 | 数值变更仍需重绘，未以图层整体缩放改变圆角。 |
| COMP-02 | 缩进改变时退出的是当前列表聚合，外层 while 会继续；“后续父项丢失”的前提不成立。 | **保留**：补纯函数回归：parent → child → next/last，深度和所有文本完整保留。间距继续用现有块间规则。 | 嵌套列表仍拆多个块，可能有现有块间距；这是排版取舍，没有把布局数量说成零成本。 |
| COMP-03 | 代码原来仅给段落光标不等于产品要求只能如此，不能以既有实现为保留理由。 | **修复**：末尾标题、列表末项、代码、引用、表格末单元格都可承接同一绘制期闪烁光标；代码保持 literal 文本，复制内容不带占位。 | 水平分隔线没有文本光标；各块流式末尾外观仍待真机确认。 |
| COMP-09 | 需要共用判定结果，但无需同时执行全部正则；优先级可以显式保持。 | **修复**：LineInfo 按空行/围栏/标题/分隔线/引用/表格/无序/有序的原派发顺序短路，只做当前分支所需判定。 | 解析语义由全量 Markdown 纯函数回归验证，未新增另一套独立派发规则。 |
| COMP-10 | 不能仅以 trim 数量少忽略它；复用既有 LineInfo 即可，无需快照状态。 | **修复**：LineInfo 记住 trimmed，块派发/段落复用；代码体 joinToString 只生成一次。 | 仍保留语法必需的行拆分/局部字符串，没有宣称零分配。 |
| COMP-11 | 增量切分协议需要保留，但稳定前缀不必每个 token 重新 substring。 | **修复**：边界函数先返回结束下标，仅边界长度或前缀内容改变时重建稳定字符串；两缓存处理同长度替换/清空回归。 | 尾段仍须解析；围栏边界仍扫描输入，没有声称整体 O(增量)。 |
| COMP-12 | 整页后台预热曾被用户否决，不能复活；这不能变成“冷解析不必处理”。 | **未完成**：本轮减少 parser 重复判断和字符串复制，但缓存未命中的解析仍在组合主线程。 | 冷打开大文本依旧可能阻塞主线程；尚无满足不闪烁、不后台整页预热约束的完整替代实现。 |
| COMP-13 | 全文键会重建流式尾块，但无 key 也不是唯一选择。 | **修复**：稳定块用内容+重复序号定位；增长尾块仅按块类型维持槽位，追加正文不重建它。纯函数回归覆盖插入、重复、增长和类型改变。 | 相同内容重复项按出现次序区分；最后块转为稳定块时允许切换身份，不声称任意编辑仍保住所有选择态。 |
| COMP-16 | 超长表格不能直接分页改变选择/列宽，但可以去掉独立的重复测量。 | **部分修复**：TableRow 移除 IntrinsicSize.Min；分隔线按实际放置坐标在 drawBehind 画，文本每轮正常测量一次，保持权重舍入/RTL。 | 整张表仍全量组合和布局，超长行数成本未解决；表格外观/选择和性能需要真机样例。 |
| COMP-18 | 缓存保存 last text 多为同一个 String 引用，并非每次额外复制全文；保留增量比较依据。 | **保留**：复核 rememberGrowingMarkdown：LiveBlocks 只在流式分支创建，定稿 MarkdownCache 随组合释放，全局 SettledBlocks 受 64 条/512K 字符限制。 | 分支切换可能暂留上一份本地解析，稳定 prefix 是局部字符串；单 transcript 常驻内容的总成本仍见 CHAT-R-07，不能泛称总内存恒定。 |
| ENG-06 | 200 条上限只限制规模，不阻止取少量记录时先复制全部历史。 | **修复**：PerfMonitor.recent 直接按下标取最后 limit 条并逆序；summary 只快照请求窗口，按原顺序累加。 | 锁内仍复制所需条目以取得一致快照；0/负值/超上限语义由原测试覆盖。 |
| ENG-08 | accessOrder=true 的 map[] 会晋升，但可以把晋升改为显式执行，原理由不是不可优化的约束。 | **修复**：MessageCache 改插入顺序表，get/put 显式 remove+put 晋升，isDiskBacked 用 O(1) 查询且不晋升。 | 容量仍 5，所有入口仍同步；原 LRU/状态窥视不改顺序的测试覆盖。 |
| ENG-09 | wire 可替换/压缩/插话，盲目增量字符计数会漂移；但同轮请求前后重复扫描可复用。 | **部分修复**：每轮请求前一起求 historyChars/totalChars/imageCount，完成时复用同一未变请求的合计。 | 仍每轮遍历一次消息列表，使用 String.length 估算；未实现跨轮可靠增量估算。 |
| ENG-11 | fsync 和仓库互斥是数据安全边界，但纯序列化没有必要占用同一锁。 | **部分修复**：saveLive 的 JSON 编码移到锁外，取消检查后仍在原 Mutex 内原子写/fsync；run 收尾 cancelAndJoin writer 防迟写复活。 | appendTail 持久写和冷读仍共享锁，活动写入时仍可等待；没有测出它占用户所述几秒的比例。 |
| ENG-16 | 同 CHAT-VM-15，恢复副本完整性成立，静默时重复扫描/复制不是必要成本。 | **部分修复**：先做输入身份/缓冲长度短路；按当前 run 缓存筛选消息；仅成功保存后推进 written 与输入标记。 | 活跃长回答仍每秒完整副本；改日志式增量恢复协议未做，不算“没必要”。 |
| ENG-17 | 损坏原始数据是恢复证据，按天数自动删除违反明确的用户数据保护要求。 | **保留**：确认 .corrupt 不进 meta 解码；删掉早已无行为的 pruneQuarantine 空函数/调用及误导的 30 天清理常量与旧注释。 | 磁盘占用和目录枚举随隔离文件数量增加仍存在；没有删除任何文件，也没有实现用户手动恢复/清理界面。 |
| SVC-11 | 最新 run 和全体停止按钮的用途不同，不代表必须反复遍历/排序。 | **修复**：FloatConsoleService 每次发射只排序一次，首项供最新运行状态，同一 orderedRuns 供停止区域。 | 保留与实际 run 更新一致的视图刷新，不把停止操作延迟合并。 |
| SVC-12 | “未获授权不能改诊断契约”是此前自行加的限制，本轮性能修复授权涵盖此处，不能作为不做理由。 | **修复**：TouchFx 取消每行抓取完整调用栈，source 改为事件类型，保留工具/事件内容。 | 诊断取舍：日志不再提供任意外层调用点的文件:行号；没有采集或读取真机日志。 |
| SVC-13 | 低优先不等于不可复用，格式器构造可独立消除。 | **修复**：TouchFx/FloatConsole 共用线程安全 DateTimeFormatter，在后台格式化调用时捕获的事件时间与时区。 | 纯函数测试对照旧 SimpleDateFormat，覆盖三个时区/夏令时；日志写入队列与必要字段仍保留。 |
| SVC-17 | 不在逐帧路径不代表主线程查询系统权限必须保留。 | **修复**：FloatConsole 的 Settings.canDrawOverlays 移至 traceWorker，其余事件字段/时刻在调用处捕获。 | 诊断取舍：权限字段是后台写入时的权限，可能略晚于事件发生时；没有宣称即时权限快照等价。 |
| SVC-18 | TouchFx ticker 在 windowUp 且空闲时 5 秒一次，是 issue #7/#8 孤儿窗口恢复入口；原停表逻辑有意如此。 | **保留**：复核亮灯/驱动时 1 秒、空闲窗口 5 秒、无窗口 removeCallbacks 三条分支，不改成无恢复机会的彻底停表。 | 窗口仍存在时会有周期唤醒，耗电成本存在；未用“可忽略”代替测量，也未新增观测。 |
| SVC-23 | 应用级队列不能随某页面关闭，但不等于必须应用启动就绑定且永久占住 TTS。 | **修复**：Speaker 首次 speak/进入语音设置时懒初始化，pending/inFlight 为空且空闲 60 秒释放；generation 拒绝迟到回调，stop 清队列。 | 保留后台朗读和最多 8 条待播；空闲后首句需重新初始化。真实引擎初始化/完成/错误回调未真机验证。 |
| APP-04 | 导航目标只用作返回动画闩锁，不需要用快照状态额外使外壳组合。 | **修复**：SuixingApp 用 remember 的普通引用持有 drawerOrigin；导航 route 驱动现有组合，返回转场仍读取该目标。 | 保留从抽屉进入页的无动画返回规则与路由结算；转场实际外观待真机。 |
| APP-09 | 小列表不泄漏但逐次绘制分配可直接消除，不需要改变图形。 | **修复**：抽屉把手的五个比例提升为文件级 FloatArray，绘制复用常量。 | 只去分配，不声称它独自解决滚动掉帧。 |
| APP-10 | Strong Skipping 不会免掉调用者已执行的列表/描述符构造，原理由不足。 | **修复**：SettingsRoot 三组描述符 remember 一次，动作通过 rememberUpdatedState 取最新回调。 | 静态分组和入口文字不变，没有重做设置路由结构。 |
| APP-14 | targetValue 不是逐帧变化但也无需让整个外壳订阅。 | **修复**：右侧 DrawerDismissLayer 子作用域读取 targetValue；pointerInput 以 drawerState 稳定，最新 close 经更新引用调用。 | 透明点击层仍只覆盖面板右侧；四条收起仍共用 160ms FastOutLinearIn，未改 snapTo/自绘遮罩。 |
| APP-15 | 实际拖拽中排序应实时跨行改变，不能拖完才排；原行为有产品含义。 | **保留**：复核 reorderWithinSection 同行/跨区返回 null，仅有效跨行更新 orderState；sections 已按会话+顺序 remember。 | pointerY 是普通 DragTracker 字段，非每个 move 写 Compose 状态；每事件仍查可见行，真实跨行仍重排，未声称零成本。 |
| APP-17 | 设备和帧率时间窗不同只是不宜混算，不能阻止隔离订阅和后台停止。 | **修复**：PerfScreen 的 DeviceMetrics 独立子作用域只在 live item 存活时采样；设备与帧率各自 repeatOnLifecycle(STARTED)，取消移除帧回调。 | 保留原时间窗/分位数定义；性能页仍有必要采样开销，未拿此结果证明其它页面实际帧率。 |
| APP-21 | 固定最近 30 天有界，模型数量却没有相同上限；维持圆角卡片并不排斥惰性列表。 | **修复**：模型数 >12 时在原 Surface 内用最高 480dp 的 LazyColumn，按模型 key 逐项挂载；少量模型维持原 Column。 | 可见交互变化：长模型卡片在卡片内部滚动；最近 30 天原样，内外嵌套滚动手感未真机验证。 |
| APP-23 | 1080px 位图在 remember 局部与保存协程持有，未发现全局引用；缺少显式 recycle 不等于永久泄漏。 | **已具备**：复核 AssistantEditor 选图、裁剪、保存：编辑器退出后组合引用释放，协程完结后可 GC；已有取消/保存完成清理。 | 退出时不是同步释放像素；贸然 onDispose.recycle 会与 IO 保存/绘制争用，因此未加不安全回收。未测得实际泄漏。 |

## 验证与范围

初轮纯函数回归为 851 / 0；本轮增加 Markdown 键身份、同长度前缀替换/清空、嵌套列表回到父级、跨时区时间格式回归。最终构建结果和 APK 校验在下方补记。

- 多批 `:app:compileDebugKotlin` 通过，最后按最新指令运行单元测试和 `:app:assembleRelease`，不运行 assembleDebug。
- import 继续使用 Kotlin 前端的声明/隐式调用目标语义分析，不用名字未出现匹配；无法可靠解析的文件不据此删 import。
- 组件纯移动阶段先逐段源码比对；之后的行为修改逐项记录。纯函数测试不覆盖 Compose、TTS 系统回调和窗口动画，不能用通过数替代 UI 等价性或真机帧率。
- P1 原位、P2 单菜单/跨块选择、P3 注入段开关、P4 导航外观，以及 IME、导航连点/切换、折叠、录音条、超长表格、用量卡片嵌套滚动，均未真机验证。线上 issue 保持开启，没有新增真机采集。
- 构建验收不含真机 UI 验收；用户随后授权安装、提交和发布，安装结果见文末。

## 尚未解决的性能工作

1. CHAT-R-07/11：单 transcript 及一次加入历史仍有全量组合/测量成本；原子锚定不等于消除单帧峰值。
2. COMP-12/16：主线程冷 Markdown 解析和超长表格全量布局仍在；去重复处理/IntrinsicSize 不是完整解决。
3. CHAT-VM-15、ENG-16：活跃输出仍每秒组装、编码并保存完整当前轮恢复副本；仅静默和重复扫描成本已消除。
4. ENG-11：JSON 编码已移出锁，但持久写/fsync 与冷读仍共享锁；超过 5 个正文缓存仍会冷读。不能静态断定这就是每次“几秒”的全部原因。
5. CHAT-R-12 等 UI 路径已做可审阅的订阅/测量改动，仍需未来装机授权后验证。没有声称测得 FPS 提升。

## 最终 Release 构建与交付（2026-10-27）

- 最终命令：`gradle :app:testDebugUnitTest :app:assembleRelease --offline --console=plain`，**BUILD SUCCESSFUL，2m 57s**。没有 assembleDebug/packageDebug。
- 单测 XML 汇总：**858 tests / 0 failures / 0 errors / 0 skipped**，120 个测试类；比初轮新增 7 项纯函数回归，比最早 828 基线新增 30 项。不能代替 UI/真机验收。
- 本轮末次语义检查 19 个目标文件 errors=0，清理 **225 条 import**（其中 TurnScrubber 207 条），委托 `getValue/setValue` 保留。分析工具显式使用当前 AGP built_in_kotlinc 输出与 friend module；没有基于字符串缺席删 import。最终 K2 Debug/Release 构建通过。
- APK：`dist/suixing-0.8.532-release.apk`，**17,814,875 bytes**；aapt 核对 `com.suixing.agent / versionName=0.8.532 / versionCode=553`；apksigner 验签通过（v2）。复制后 SHA-256 与 Gradle 原始产物相同。
- SHA-256：`7de4197b2e066ba4e0df912eac0727247d2d50097723d849fcabf06c87d8da0a`。
- 本地构建日志 `verify-out/issue50-final-release.log`，签名/manifest 校验在同目录；随包校验记录 `dist/suixing-0.8.532-release-verification.json`。
- `git diff --check` 通过；构建完成时新文件已用 intent-to-add 纳入普通 diff，之后按用户授权提交。编译有 API 废弃与冗余表达式警告；没有为消警告改掉指定的抽屉动画。
- 此构建阶段没有 adb 或真机采集；包内版本已校验。后续安装见下段，设置→关于和 P1–P4 的真机 UI 验收仍未进行。

## 安装与发布记录（2026-10-27）

- 用户随后明确授权“处理 issue、adb 安装、提交+push”；本轮沿用 push 包含 tag/Release 的既有约定，发布版本为 `v0.8.532`。
- 安装前复核 APK SHA-256 与上述构建产物一致；无线 adb `install -r` 返回 **Success**。
- 安装后仅读安装元数据：`versionName=0.8.532 / versionCode=553`。没有启动/操作应用界面，也没有读取运行日志或应用数据。
- 安装成功不是 UI 验收通过；P1–P4、输入法起落、切会话/导航、折叠、TTS、录音条、表格和卡片滚动仍待用户体验确认。
- 固定源码与交付入口：[v0.8.532 源码](https://github.com/zeng6125-rgb/suixing/tree/v0.8.532)、[Release](https://github.com/zeng6125-rgb/suixing/releases/tag/v0.8.532)。具体未完成项继续按上表跟进，不以版本发布自动关闭 issue #50。

## 中断批次补记与 R2/R3/R4（2026-10-27 晚）

上一轮在“提交 + 更新文档”前中断，改动留在工作区。接手后先验证为绿再提交：

- 提交 `e77734d`：37 改 + 7 新（`PromptMessages`、`InjectedUserContext`、`ProjectedStateFlow`、
  `SelectionFocus`、`TranscriptMount`、`PreparedMarkdown`、`SettingsStyle`）+ 10 个纯函数测试；
  当时实测 `:app:testDebugUnitTest` → **891 tests / 0 failures**（本文档 272 行的 858 是中断前那次记录，非本批）。
- 顺序说明：这份检查点是**先提交、后复核**。未做等价性复核即入库，若复核发现问题需追加修复提交。

接手后新增的三项（测试 891 → **894**）：

| 编号 | 内容 | 实现位置 |
|---|---|---|
| R2 | 正文缓存容量 5 → 8，覆盖“抽屉里最近一屏会话来回切”；内存代价为 8×一页窗口 | `ui/chat/ChatViewModel.kt` 的 `MESSAGE_CACHE_SIZE`（含代价说明，回退只改一个数字） |
| R3 | 冷读也绕过仓库锁：未命中已提交元数据时，先**无锁**读 meta 再打开那一代次日志；任何异常回落加锁路径 | `data/ChatRepository.kt` 的 `readCommittedCold(...)`，接在 `readTailWindow` / `readWindow` 的热快照之后 |
| R4 | 活跃输出的恢复副本按“未落盘增量”自适应间隔（1s / 2s / 3s），减少长回答下每秒整段组装+编码+写盘 | `ui/chat/ChatViewModel.kt` 的 `liveDraftIntervalMs(...)` + `writeLiveDraft` 计数器；`LiveDraftIntervalTest` |

仍未做（需要真机帧数据或用户验收，不静态断言）：

- CHAT-R-07/11：单个 transcript item 与“一次加入历史”的全量组合/测量成本；
- COMP-16：超长表格的全量布局仍存在（COMP-12 的冷解析已由 `PreparedMarkdown` 下沉到 Default）；
- P1–P4：加载更早原位、复制菜单单次消失、注入开关生效、导航条观感，均未真机验证。

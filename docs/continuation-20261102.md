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

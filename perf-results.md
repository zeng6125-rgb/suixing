# 性能基线结果（按 perf-scenarios.md 采集）

**本表只填实测数字。没测就留 `—`，并在备注写"未测"。**
每行至少 3 次重复时，取中位数并在备注注明。

| 场景 | 版本 | 设备/刷新率 | 次数 | p50 | p95 | p99 | janky% | 峰值 PSS | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| A 冷启动 | — | — | — | — | — | — | — | — | 未测（需 `am start -W`） |
| B 首开 12 轮 | — | — | — | — | — | — | — | — | 未测 |
| C 预热长会话滑动 | — | — | — | — | — | — | — | — | 未测 |
| D 20k/50k 流式 | — | — | — | — | — | — | — | — | 未测 |
| E 长卡片展开折叠 | — | — | — | — | — | — | — | — | 未测 |
| F 流式中切会话+抽屉 | — | — | — | — | — | — | — | — | 未测 |

## 设备实测回执（不依赖应用界面，已做）

| 项 | 结果 |
|---|---|
| 安装 | `adb install -r` → Success（0.8.624 / vc645） |
| sha256 | 本地 = 设备 = `AE87C6A2…D568`，与 Release 的 `SHA256SUMS` 一致 |
| `extractNativeLibs` | `true`（legacy 打包生效，原生库解压到 `/data/app/.../lib/arm64/`） |
| 原生库落盘 | `libbash.so` 2,342,728 B / `libbusybox.so` 1,980,720 B 均在位，`rwxr-xr-x` |
| ELF 段对齐 | 两个 `.so` 的 LOAD 段 `p_align = 0x10000`（64KB）⇒ 16KB 页设备上可加载 |
| 已安装包 16KB 对齐 | 从设备 pull 回 `base.apk`（5,275,144 B）后 `zipalign -c -P 16 -v 4` → **Verification successful** |

## R8 静态验收（对已安装包，2026-11-01）

从设备 pull 回 `base.apk`（5,275,144 B，sha256 与构建产物一致）后逐项检查：

| 检查 | 结果 | 为什么重要 |
|---|---|---|
| 清单组件类名 | **7/7 原名保留**（MainActivity、SuixingApplication、AgentForegroundService、BootReceiver、FloatConsoleService、SchedulerService、SuixingAccessibilityService） | 被改名系统就拉不起组件 |
| Shizuku | `rikka/shizuku/Shizuku` 出现 18 次，原名保留 | 反射调用链 |
| kotlinx.serialization | `kotlinx/serialization/json/Json` 出现 53 次，原名保留 | 反射入口 |
| `$$serializer` 类 | 35 个都在，**类名被改**（`AppSettings$$serializer -> ng`），但 `serializer()` 名字保留 | 静态引用即可，改名无害 |
| 16KB 对齐 | `zipalign -c -P 16 -v 4` → Verification successful | 16KB 页设备 |
| 原生库 | `libbash.so`/`libbusybox.so` 已解压，`p_align=0x10000` | execve 路径 |

**运行期仍未验证**：R8 下的实际序列化读写（需应用在前台）。

## JVM 微基准（不等同真机，只看趋势）

| 项 | 规模 | 结果 |
|---|---|---|
| A1 `LiveBlocks` 前缀校验 | 120 帧 / 28,663 字符 | 全量 241.8ms → 受信任游标 34.9ms，**6.93x** |
| A2 打字机截取 | 3000 帧 / 50,000 字符 | `take` 41.1ms → 追平快路径 29.8ms，**1.38x** |
| R39 token 估算 | 单条 20,000 字符 | **1.46ms/次**（原来跑在组合期主线程） |

三条都有断言兜底：A1 断言块结构与整段解析一致；A2 断言两种写法长度序列一致。

**A2 第一版测出 0.83x（更慢）**，原因是没预热、结果没被读取（JIT 把分配消掉了）。
加预热 + 交替两遍 + `@Volatile sink` 后才得到 1.38x —— 基准本身会骗人。

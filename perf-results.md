# 性能基线结果（按 perf-scenarios.md 采集）

**本表只填实测数字。没测就留 `—`，并在备注写"未测"。**
每行至少 3 次重复时，取中位数并在备注注明。

| 场景 | 状态 |
|---|---|
| A 冷启动 | **按规则跳过**（需实机） |
| B 首开 12 轮 | **按规则跳过**（需实机） |
| C 预热长会话滑动 | **按规则跳过**（需实机） |
| D 20k/50k 流式 | **按规则跳过**（需实机） |
| E 长卡片展开折叠 | **按规则跳过**（需实机） |
| F 流式中切会话+抽屉 | **按规则跳过**（需实机） |

> 用户规则：**需要实机测试的改动直接跳过**。本表因此不含任何采样数字；
> 采集脚本与 6 组场景定义保留在仓库里，将来要测时直接可用（`scripts/perf_collect.sh`）。

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

## JVM 微基准（多轮取中位数；**此前的单次数字已撤回**）

| 项 | 规模 | 中位数 | 结论 |
|---|---|---|---|
| A1 `LiveBlocks` 前缀校验 | 120 帧 / 28,663 字符 / 5 轮 | 全量 27.7ms vs 受信任 27.4ms | **1.01x，无可测差异** |
| A2 打字机截取 | 3000 帧 / 50,000 字符 / 7 轮 | take 17.3ms vs 快路径 17.0ms | **1.02x，无可测差异** |
| R39 token 估算 | 单条 20,000 字符 | 1.8ms/次 | 这是从组合期主线程挪走的量 |

**撤回**：先前报告的 A1 6.93x、A2 1.38x 都来自单次测量，先跑的那条路径承担冷 JIT 开销，
后跑的"看起来更快"。交替顺序 + 预热 + 多轮取中位数后，两项都回到 1.0x 附近。

**含义**：这两处优化在 JVM 上**没有可测收益**；真实开销在解析本身（0.23ms/帧）。
正确性仍有测试兜底（块结构与整段解析一致、长度序列一致），但"快了多少"目前**没有证据**。
只有 ART 上的分配率与帧数据能回答，且也可能同样接近 1.0x。

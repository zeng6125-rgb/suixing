# JVM 基准索引（可复现入口）

> issue #69 P2-E 的产物：把散落在各测试类里的性能基准集中成一张索引，写清**跑法、判据、
> 以及这些数字能支撑什么结论**。
>
> **强度声明（重要）**：下面全部是 **JVM 单机单次/少量多轮**观测，**没有做过跨机器方差统计**。
> 它们只能回答「这里有没有数量级上的瓶颈」，**不能**用来宣称帧率、延迟或内存的改善。
> 真机数据一律以「未测量」对待。

## 怎么跑

```bash
./gradlew :app:testReleaseUnitTest --tests "*Benchmark*" --tests "*Microbench*" --console=plain
```

计时通过 `System.nanoTime()` 打印到测试输出（`build/test-results/...` 的 `system-out`）。
每个基准都自带**等价性断言**（同输入必须同输出），所以速度再快也不会悄悄改掉结果。

## 索引

| 基准 | 测什么 | 最近一次观测 | 支撑的结论 |
|---|---|---|---|
| `ui/drawer/DrawerProjectionBenchmarkTest` | 抽屉列表投影（20/200/2000 会话） | 46.6 / 93.8 / 529.1 µs | 2000 会话一次投影约 0.5ms ⇒ **不值得加缓存**（#66 P1-3） |
| `ui/files/FileListProjectionBenchmarkTest` | 目录列表投影（100/1000 文件，仅 lowercase + 排序） | 132.4 / 528.0 µs | 同上量级 ⇒ 不加缓存（#68 P2-D） |
| `ui/chat/markdown/LiveBlocksMicrobenchTest` | `LiveBlocks` 前缀校验 vs 受信任游标 | 多轮中位数 27.7 vs 27.4 ms（1.01x） | JVM 上**无可测收益**；此前单次的 6.93x 是冷 JIT 假象，已撤回 |
| `ui/chat/ChatHotPathMicrobenchTest` | A2 打字机截取 / R39 token 估算 | A2 1.02x（无差异）；R39 约 1.8ms/次（20k 字符） | A2 无可测收益；R39 的价值是把这 1.8ms 从主线程挪走 |

## 方法论（踩过的坑）

1. **单次测量不可信**：先跑的那条路径承担冷 JIT，会得到 6.93x 这种假收益。必须**交替顺序 + 预热 + 多轮取中位数**。
2. **防死代码消除**：结果要累加进 `@Volatile` 字段并读回，否则 JIT 会把整个计算消掉。
3. **IO 不进 JVM 基准**：`isDirectory`/`length`/时间格式化是系统调用，JVM 上量不出真值 —— 这类项目写成「不假装能测」。
4. **等价性断言优先**：每个基准都断言输出不变；没有这条，快了多少都没有意义。

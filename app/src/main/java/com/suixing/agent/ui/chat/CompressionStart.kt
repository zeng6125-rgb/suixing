package com.suixing.agent.ui.chat

/**
 * 「现在能不能开始一次手动压缩」（issue #69 P2-C：把判据从巨型 ViewModel 里抽出来）。
 *
 * 两条拒绝理由，语义不同：
 *
 * - **有 run 在跑**：交给引擎在轮次边界处理，不能并行再压一次（否则两套摘要互相覆盖）；
 * - **已经在压**：防连点，同一会话同时只允许一个压缩任务。
 *
 * 抽成纯函数后这两条可以逐条断言；原来它们是 `compressNow()` 开头两行 `return`，
 * 没有任何测试，删掉一行也不会有人发现。
 */
internal fun canStartCompression(hasRunningJob: Boolean, alreadyCompressing: Boolean): Boolean =
    !hasRunningJob && !alreadyCompressing

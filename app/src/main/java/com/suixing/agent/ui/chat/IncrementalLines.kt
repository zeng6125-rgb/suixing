package com.suixing.agent.ui.chat

/**
 * `text.split('\n')` 的**增量**版：只把新增的那一段切开，接在上一次的结果后面。
 *
 * 为什么：思考链是按拍变长的（一个 delta 一次），`split` 每拍都要把整条链重切一遍、再分配
 * 一个新列表 —— 一千行的思考链在流式期间就是每拍一千次字符串分配。切分本身是纯函数，而流式
 * 文本只有末尾在长，所以上一次的结果里除了**最后一行**之外都是终值。
 *
 * 结果必须与 `text.split('\n')` 逐元素相等（`IncrementalLinesTest` 用逐字符喂的方式守这条）：
 *  · 纯追加一行 —— 追加；
 *  · 末行继续增长 —— **替换**最后一个元素（`a`+`b`，不是 `a`,`b`）；
 *  · 变短 / 不是前缀（换块、重放、流式回退）—— 整体重建。
 *
 * 不是线程安全的：只在组合线程上用，由调用方 `remember` 持有一个实例。
 */
internal class IncrementalLines {
    private var text: String = ""
    private var lines: List<String> = emptyList()
    private var primed = false

    fun linesFor(next: String): List<String> {
        if (!primed) {
            primed = true
            text = next
            lines = next.split('\n')
            return lines
        }
        val prev = text
        // 同一个字符串（重组时文本没变）：连前缀比较都不用做。
        if (next == prev) return lines
        // 不是前缀（变短、换块、重放）：上一次的结果一条都用不上，整体重建。
        if (!next.startsWith(prev)) {
            text = next
            lines = next.split('\n')
            return lines
        }
        // 纯追加。旧的最后一行可能还在长，所以是「去掉旧末行 + 旧末行拼新首段 + 新余段」。
        // 这不是近似：切分只由换行位置决定，而 prev|next 的接缝不在换行上，拼接与整切等价。
        val added = next.substring(prev.length).split('\n')
        val merged = ArrayList<String>(lines.size - 1 + added.size)
        for (i in 0 until lines.lastIndex) merged.add(lines[i])
        merged.add(lines.last() + added[0])
        for (i in 1 until added.size) merged.add(added[i])
        text = next
        lines = merged
        return lines
    }
}

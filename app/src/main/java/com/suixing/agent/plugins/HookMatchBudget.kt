package com.suixing.agent.plugins

/** Java regex is not cooperatively cancellable. Charge actual character reads across all hooks. */
internal class HookMatchBudget(private val maxReads: Int = 2_000_000) {
    private var reads = 0

    fun wrap(text: String): CharSequence = View(text, 0, text.length)

    private inner class View(private val text: String, private val start: Int, override val length: Int) : CharSequence {
        override fun get(index: Int): Char {
            check(++reads <= maxReads) { "插件规则匹配超过计算预算，需要人工确认" }
            require(index in 0 until length)
            return text[start + index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= length)
            return View(text, start + startIndex, endIndex - startIndex)
        }
        override fun toString(): String = text.substring(start, start + length)
    }
}

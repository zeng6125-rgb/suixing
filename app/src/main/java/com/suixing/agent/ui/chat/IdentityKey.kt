package com.suixing.agent.ui.chat

/**
 * **只比引用**的 `remember` 键。
 *
 * Compose 的 `remember` 用 `equals` 比较键，而 `List<Part>` 的元素是 data class ⇒ 键比较会
 * 一路深到**工具输出正文、思考全文、截图 base64**（逐字符比）。这些量在一条长会话里可以
 * 到几十上百 KB，每次键比较都要过一遍。
 *
 * 换成引用相等后：失效条件变成「**实例**换了就失效」，而重算本身只是 O(parts) 的一遍扫描
 * （`processFoldSlots` / `processTailStart` / `filterIsInstance` 都是这种量级）——
 * 拿一次廉价重算换掉一次昂贵深比较。
 *
 * 失效**频率**基本不变：`parts` 本来就跟着 `visible` 一起重建（`ChatScreen` 里
 * `visible = remember(visibleShape) { groupRows(...) }`），所以旧写法在这些点上也是
 * 「实例不同 ⇒ 深比较失败 ⇒ 重算」。
 */
internal class IdentityKey(val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value

    override fun hashCode(): Int = System.identityHashCode(value)
}

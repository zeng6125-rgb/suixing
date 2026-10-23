package com.suixing.agent.agent

import com.suixing.agent.llm.ChatMessage

/**
 * 出站请求里图片的预算控制。
 *
 * 为什么要有：历史里每一张 base64 图片都会随**每一轮**请求原样重传，跑过几十轮截图任务的
 * 会话就是几十 MB 的请求体。这是「工具做完了、下一步迟迟不出」的机制之一。
 *
 * 规则（与 DSH 的 image-offload 同形状，只是预算按字节）：
 * - **只降级工具截图**（[ChatMessage.toolScreenshot]）。用户上传的附件一张都不动 —— 他那张图
 *   是他自己给的东西，静默省掉等于篡改他的输入。
 * - **只在真的超预算时才动**，且**最老的先降级**。短会话完全不受影响。
 * - 降级不是丢弃：原文留在会话文件里，占位写明去哪里重新加载。
 *
 * 已知代价：一旦预算边界移动，被改的那一条会破坏其后的 prompt 缓存前缀。只改一条、且只在
 * 超预算时发生，是这个代价能被接受的原因。
 */
internal fun pruneRequestImages(
    wire: List<ChatMessage>,
    budgetChars: Int,
): List<ChatMessage> {
    if (budgetChars <= 0) return wire
    val carriers = wire.withIndex().filter { entry ->
        entry.value.toolScreenshot && entry.value.imageCount > 0
    }
    if (carriers.isEmpty()) return wire
    var total = carriers.sumOf { entry -> entry.value.allImages.sumOf { it.length } }
    if (total <= budgetChars) return wire

    val demote = mutableSetOf<Int>()
    // 最老先降级：carriers 已按 wire 顺序排列，也就是由旧到新。
    for ((index, message) in carriers) {
        if (total <= budgetChars) break
        demote += index
        total -= message.allImages.sumOf { it.length }
    }
    if (demote.isEmpty()) return wire
    return wire.mapIndexed { index, message ->
        if (index !in demote) message else message.omitImages(IMAGE_OFFLOAD_NOTE)
    }
}

/** 占位文案：既说清「为什么没了」，也给出能拿回来的办法，不承诺不存在的文件。 */
internal const val IMAGE_OFFLOAD_NOTE =
    "\n（更早的截图已从本次请求里省略，避免每轮重传；需要再看时用 ui_screenshot 的 path 参数" +
        "按原路径重新加载，或重新截一张。原图仍保存在会话记录与应用目录里。）"

/**
 * 工具截图默认预算：base64 字符数。约 10 张 1080p JPEG 截图。
 * 超过就按最老的先降级，见 [pruneRequestImages]。
 */
internal const val MAX_REQUEST_IMAGE_CHARS = 2 * 1024 * 1024

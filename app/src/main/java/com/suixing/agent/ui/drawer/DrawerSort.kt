package com.suixing.agent.ui.drawer

import com.suixing.agent.data.Conversation
import com.suixing.agent.ui.drawer.DrawerOrder
import com.suixing.agent.ui.drawer.DrawerSections
/**
 * 把 [dragging] 挪到 [target] 的槽位上，只在**同一区块内**生效。
 *
 * 置顶会话与普通会话之间换位一律返回 null —— 用户原话：「怎么拖都不能越过置顶」。
 * 另一条路（跨区即隐式置顶/取消置顶）看着更顺手，但一次误拖就会改掉置顶状态，不做。
 */
internal fun reorderWithinSection(
    order: DrawerOrder,
    sections: DrawerSections,
    dragging: String,
    target: String,
): DrawerOrder? {
    val pinnedIds = sections.pinned.map { it.id }
    val normalIds = sections.normal.map { it.id }
    val updated = when {
        dragging in pinnedIds && target in pinnedIds -> order.copy(
            pinned = moved(pinnedIds, dragging, target),
            normal = normalIds,
        )
        dragging in normalIds && target in normalIds -> order.copy(
            pinned = pinnedIds,
            normal = moved(normalIds, dragging, target),
        )
        // 跨区，或者指针落在「置顶」标签/分隔线上：什么都不改。
        else -> return null
    }
    return if (updated == order) null else updated
}

/** 把 [item] 从表里取出、插到 [target] 原来的槽位（相邻行的一次次换位就是它重复的结果）。 */
private fun moved(list: List<String>, item: String, target: String): List<String> {
    val from = list.indexOf(item)
    val to = list.indexOf(target)
    if (from < 0 || to < 0 || from == to) return list
    return list.toMutableList().apply {
        removeAt(from)
        add(to.coerceIn(0, size), item)
    }
}

/**
 * 置顶开关对两张表的改动：置顶就追加到置顶表**末尾**（置顶区有它自己的顺序，不抢第一），
 * 同时从普通表里摘掉；取消置顶只从置顶表摘掉 —— 于是它按 updatedAt 回到普通区该在的位置，
 * 而不是回到普通表里那个旧槽位（那个槽位代表「手动基线」，刚取消置顶时按时间排更符合预期）。
 */
internal fun pinToggled(order: DrawerOrder, chat: Conversation): DrawerOrder =
    if (chat.pinned) {
        order.copy(pinned = order.pinned.filterNot { it == chat.id })
    } else {
        order.copy(
            pinned = order.pinned.filterNot { it == chat.id } + chat.id,
            normal = order.normal.filterNot { it == chat.id },
        )
    }

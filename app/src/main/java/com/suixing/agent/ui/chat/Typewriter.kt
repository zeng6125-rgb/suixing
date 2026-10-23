package com.suixing.agent.ui.chat

/**
 * 打字机效果 —— **已停用**（2026-10-24，用户：「别搞什么拍，老老实实流式输出」）。
 *
 * 为什么停用：实测（934+1711 条真机留痕）到达本身就是 1~3 字/片的细碎流，
 * 「收到什么显示什么」天然就是逐字效果；打字机层只在"到达是大块"时才有意义，
 * 而这种情形从未出现。它反而制造了 0.8.381~0.8.404 连续七轮的"瞬移"问题：
 * 显示速度与到达速度的任何失配，都会在 run 结束、落盘行替换 live 行时一次性吐出。
 *
 * 保留这个函数（直接返回原文）是为了不动两处调用点；开关和设置项待后续清理。
 */
internal fun rememberTypedText(text: String, @Suppress("UNUSED_PARAMETER") enabled: Boolean): String = text

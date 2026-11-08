package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage

/**
 * What the user has typed but not sent, per conversation. A single global field is
 * what carried half a sentence from one conversation into the next when the drawer
 * switched; keyed by chat id, each conversation restores its own on the way back.
 */
data class Draft(
    val text: String = "",
    /** 文件附件的标记（单个）：路径注入正文，见 ChatScreen.withAttachment。 */
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    /**
     * 已缓存图片的标签（顺序＝发送顺序）。多附件（用户：「图片为什么只能添加一张」）：图片本体在
     * view model 的 [ChatViewModel.attachmentsFor] 缓存里，这里只放**触发 remember 重读**的标记 ——
     * 与单值时代的 attachmentUri 同一个作用，换成列表而已。
     */
    val imageNames: List<String> = emptyList(),
)

/**
 * A line the user queued while a run was going, before the model has read it.
 *
 * It is not a message yet: nothing reaches the transcript until the engine reports the
 * line was handed over ([AgentEvent.Steered]), and until then the user can still promote,
 * edit or delete it. The queue is deliberately never written to disk — it is the state of
 * a run that is still going, and a restarted process has no such run.
 */
/**
 * 一条排队中的插话。
 *
 * **必须 `@Immutable`**（性能审计 task-3 P1）：它含 `List<String>`，没有注解时 Compiler
 * 判为 unstable ⇒ 消费它的 [com.suixing.agent.ui.chat.SteerQueueRow] 不可跳过 ⇒
 * 每次按键（Composer 重组）都把整排排队行完整重组一遍。
 * 这是真不可变值对象：没有任何地方原地修改 `imagesBase64`（与 `ChatModels.kt` 的既有做法一致）。
 */
@androidx.compose.runtime.Immutable
data class PendingSteer(val id: String, val text: String, val imagesBase64: List<String> = emptyList())

/**
 * A whole turn the user sent while a run was going, in 排队 mode.
 *
 * Deliberately not a steer: nothing about it interrupts the run that is going. It waits, and starts
 * a run of its own once the current one is over — the same path the send button takes, so it lands
 * in the transcript as an ordinary user message rather than as a bubble hanging above the composer.
 * The image travels with it because the composer can hold one, and a queued turn that quietly
 * dropped its picture would be a message the user never wrote.
 *
 * Memory only, like [PendingSteer] and for a stronger reason: this queue is about the run that is
 * going right now. If the process dies, that run dies with it, so there is no "afterwards" left to
 * send into — and a copy on disk would come back as the app talking to itself.
 */
/** 同 [PendingSteer]：含 `List<String>` 且缺 `@Immutable` 会拖累消费方的可跳过性。 */
@androidx.compose.runtime.Immutable
data class PendingTurn(val id: String, val text: String, val imagesBase64: List<String> = emptyList())

/**
 * 最多缓存几个会话的正文（LRU）。用户定的数：在 5 个会话之间来回切不该反复读盘。
 * 超出就淘汰最久没被打开的那一个 —— 与"列表常驻全文"（RSS 413MB 的根因）是两回事。
 */
/**
 * 正文缓存容量（R2：5 → 8）。
 *
 * 原值 5 是“在几个会话之间来回切不该反复读盘”的估算，但抽屉里的会话通常不止 5 个：
 * 超过 5 个就开始**每次切都冷读**（骨架 + 读盘）。8 覆盖“最近一屏抽屉”的常见来回切。
 * 内存代价：每份是**一页尾部窗口**（OPEN_TURNS 轮），8 份 ≈ 8×一页，与“整份正文常驻”
 * 不是一个量级；要回退只需把这一个数字改回去。
 */
internal const val MESSAGE_CACHE_SIZE = 8


/**
 * A tool call as it happens.
 *
 * The row exists from the moment the call starts, so a run that spends ten seconds inside
 * a shell command shows what it is doing for those ten seconds instead of a gap.
 */
data class LiveTool(
    val name: String,
    val args: String,
    val startedAt: Long,
    val done: Boolean = false,
    val ok: Boolean = true,
    val elapsedMs: Long = 0,
    /**
     * The stored TOOL message once it lands: its output and screenshot are what the
     * expanded row shows, and without this link a live call's picture never drew.
     */
    val message: StoredMessage? = null,
)


/** 「要不要重算会话统计」的请求：会话 id + 盘上内容的指纹 + 上下文窗口。 */
internal data class StatsRequest(val chat: String, val key: String, val window: Int)

internal const val TAG = "ChatViewModel"

/** 「记住上次打开」的写盘 debounce：快速连点几个会话不该连写几次。 */
internal const val LAST_OPENED_DEBOUNCE_MS = 1_200L

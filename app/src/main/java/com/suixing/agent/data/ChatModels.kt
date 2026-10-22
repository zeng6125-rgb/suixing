package com.suixing.agent.data

import kotlinx.serialization.Serializable

@Serializable
data class StoredMessage(
    val id: String,
    val role: String,
    val text: String = "",
    /** The model's thinking, when the endpoint streams it apart from the answer. */
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolOutput: String? = null,
    val toolOk: Boolean? = null,
    val toolCallId: String? = null,
    val imageBase64: String? = null,
    /**
     * 多附件（用户：「图片为什么只能添加一张」）：一条用户消息携带的全部图片。旧盘上的单值
     * [imageBase64] 保留 —— 工具截图（一次调用一张）与升级前的会话都还走它；读侧用 [allImages]
     * 聚合，两边都不丢。
     */
    val imagesBase64: List<String> = emptyList(),
    val toolCalls: List<StoredToolCall> = emptyList(),
    /**
     * Which candidate answer this message belongs to inside its turn. Everything
     * one engine run produced shares a value, so re-answering a question appends a
     * second run instead of overwriting the first. Null on user messages and on
     * transcripts written before branching existed.
     */
    val runId: String? = null,
    /**
     * The model that produced this message, for per-model usage rollups. Empty on
     * transcripts written before the field existed and on user messages.
     */
    val model: String = "",
    /**
     * What this message cost, as the endpoint reported it. Only assistant messages
     * carry it, and only when the endpoint sent a usage block at all.
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** How long the model took, wall clock, for the line under the bubble. */
    val elapsedMs: Long = 0,
    /**
     * How long the endpoint took to produce its *first* piece of anything — thinking or
     * text. Measured in the engine, because no usage block carries it and it is the wait a
     * user actually feels; 0 for a round that never streamed, or a message stored before
     * this was recorded.
     */
    val firstTokenMs: Long = 0,
    /** True once the user edited the text by hand, so the UI can say so. */
    val edited: Boolean = false,
    /**
     * 一条**过程留痕**：「自动重试 1 次 · 服务器限流（429）」。只挂在 assistant 消息上。
     *
     * 为什么不走 [AgentEvent.Failed][com.suixing.agent.agent.AgentEvent.Failed]：那一路会写红色回执卡，
     * 而触发重试的这一轮**最后是成功的** —— 用户看到的应该是一块过程记录（和思考块、工具块并排），
     * 不是一张说这轮失败了的卡片。null＝这一轮没发生过值得记的事。
     */
    val traceNote: String? = null,
    /**
     * True for a user message that was injected mid-run as a steer, not asked as a
     * new question. It draws as a user bubble but must not open a new turn, or one
     * engine pass would be split across two bubbles.
     */
    val steer: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** 单值与列表的聚合读侧：旧盘数据 / 工具截图（单值）与多附件（列表）都不丢。 */
    val allImages: List<String> get() = imagesBase64.ifEmpty { listOfNotNull(imageBase64) }

    val totalTokens: Int get() = promptTokens + completionTokens

    /** True when the endpoint never told us what this message cost. */
    val usageKnown: Boolean get() = promptTokens > 0 || completionTokens > 0
}

/** The tool calls an assistant turn asked for; replayed back to the API verbatim. */
@Serializable
data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String = "{}",
)

/**
 * One span of a conversation that the engine has summarised away, recorded at the moment it
 * was compressed.
 *
 * The summary has to outlive the run that produced it: a wire rebuilt from the full history
 * would compress — and pay for — the same messages on every following turn, and the user
 * would have no way to see what was folded away or that the original text is still on disk.
 */
@Serializable
data class ContextCheckpoint(
    val id: String,
    /** When the compression happened, in milliseconds. */
    val at: Long,
    /** First message of the compressed span, inclusive. */
    val fromMessageId: String,
    /** Last message of the compressed span, inclusive. */
    val toMessageId: String,
    /** The summary the model produced, verbatim. */
    val summary: String,
    /** How many messages the span covered. */
    val messages: Int,
    /** Prompt tokens of the wire as it was before compressing, for the usage line. */
    val promptTokens: Int,
    /**
     * 这张记录在转录里挂在**哪条消息之后**。
     *
     * 与 [toMessageId] 的区别：那个是"被折叠掉的最后一条"，属于**旧历史**——按它定位，压缩块会
     * 出现在很早以前的位置（用户：「运行的时候有这个块，会话完成一折叠就没有了」：运行时看到的是
     * 过程区里那一块，跑完它回到旧历史里，屏幕上看不见）。
     * 这里记的是**压缩发生那一刻最后一条已落盘消息**，也就是它真正发生的位置。
     * 旧盘上没有这一位（空串）⇒ 回落到 [toMessageId]，行为与以前一致。
     */
    val anchorId: String = "",
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<StoredMessage> = emptyList(),
    /**
     * 这条会话总共有多少条消息 —— **列表页用它，而不必把整份记录读进内存**。
     *
     * 0 = 未知（老数据、或来自只读了 meta 的列表加载）。需要真实条数时读消息列表本身。
     * 落盘层在写 meta 时维护它；`load()` 只读 meta 时把它填上来，于是
     * `SessionTools` 这种"只要条数"的调用方不必解析几十 MB。
     */
    val messageCount: Int = 0,
    /**
     * 这条会话总共有多少**轮**（一个用户提问 + 它引出的回答算一轮）。
     *
     * 和 [messageCount] 分开是因为**条数 ≠ 轮数**：一轮里有用户消息、可能还有思考/工具/多条助手消息。
     * 导航条的「第 N 轮」「上面还有 N 轮」用的就是这个数；用条数去顶会算错。
     * 0 = 未知（老数据，或列表来自只读 meta 之外的路径）。
     */
    val turnCount: Int = 0,
    /** The persona this conversation speaks as; empty falls back to the active one. */
    val assistantId: String = "",
    /**
     * 抽屉里的置顶。**带默认值**：仓库的 Json 是 `ignoreUnknownKeys = true`，老文件没有这一位
     * 也能解析（读成 false），所以不需要迁移。
     *
     * 它只影响抽屉自己的排序（`ui/SuixingApp.kt` 的 `Drawer` 按「置顶优先、再按时间」排）；
     * [ChatRepository] 里那份列表顺序仍是时间序，会话管理页与「上次打开」都吃它。
     * 写这一位只走 [ChatRepository.setPinned]：正文保存（[ChatRepository.save]）会继承盘上的
     * 值，免得一次消息落盘把用户刚置顶的会话悄悄取消置顶。
     */
    val pinned: Boolean = false,
    /**
     * 归档：抽屉列表不再显示它，数据（消息、检查点、模型选择）原样留着，可从「已归档」翻出来
     * 取消归档。与 [pinned] 同样**带默认值**：仓库的 Json 是 `ignoreUnknownKeys = true`，老文件
     * 没有这一位也能解析（读成 false），所以不需要迁移。
     *
     * 写这一位只走 [ChatRepository.setArchived]：正文保存（[ChatRepository.save]）会继承盘上的
     * 值，免得一次消息落盘把用户刚归档的会话悄悄放回列表。
     */
    val archived: Boolean = false,
    /**
     * Which run of each turn is on screen, keyed by the id of the user message that
     * opened the turn. Missing entries mean "the newest run", which is what a
     * freshly answered question should show.
     */
    val activeRuns: Map<String, String> = emptyMap(),
    /**
     * This conversation's own model choice; null means it never picked one and falls back
     * to the assistant's pin and then to the global default. Stored per conversation on
     * purpose: picking another model in one chat must not move the model of any other.
     */
    val providerId: String? = null,
    val model: String? = null,
    /**
     * This conversation's own thinking level (the wire's `reasoning_effort`); null means it
     * never picked one and falls back to the persona's provider, then the app-wide default.
     * Per conversation for the same reason the model is: a level chosen while debugging one
     * chat must not silently re-time every other chat.
     */
    val reasoningEffort: String? = null,
    /**
     * Every compression this conversation has been through, oldest first. The messages
     * themselves are never deleted — this is the record of which spans were folded into
     * which summary, so a later run can reuse a summary instead of paying to make it again.
     */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
)

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

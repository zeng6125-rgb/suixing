package com.suixing.agent.agent

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.forAssistant
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient

/** 一次画像整理的结果。自动触发的那条路不读它；手动「重新总结」用它说话。 */
sealed interface ProfileSummary {
    /** 写入了新画像。 */
    data object Updated : ProfileSummary

    /** 模型原样返回了旧画像：最近的对话里没有关于这个人的新信息。 */
    data object Unchanged : ProfileSummary

    /** 没有成功，已有画像一个字都没动。 */
    data class Failed(val reason: String) : ProfileSummary
}

/**
 * 画像整理器：把【旧画像】与【最近的对话】交给模型，换回一整篇新画像。
 *
 * 与记忆是两条链路。记忆（[com.suixing.agent.data.MemoryStore]）是模型在任务里**主动**调用
 * 工具写下的键值对，它决定记什么；画像由这里**定期**从对话里读出来，模型只是在整理一份
 * 关于用户这个人的滚动摘要。所以两者共用一个 `filesDir` 却互不相干：这一篇只写 `profile.md`
 * （纯文本、整篇替换、上限 8000 字），一个字都不会进 `memory.json`。
 *
 * 整篇替换而不是追加：追加出来的画像会一轮比一轮长，最后自己把上下文吃满。旧画像与新对话
 * 一起给模型，让它交出完整的新版本——没新信息时它按提示词原样退回旧画像，这一篇也就不写盘。
 *
 * 请求是**独立的一次调用**：走的是同一个端点，但不混进任何 run 的 wire，所以主循环里看不到
 * 它，也没有工具、没有工具结果、没有思考。
 */
class ProfileSummarizer(private val llm: LlmClient) {

    /**
     * 整理一次画像。
     *
     * [settings] 必须是**已经定好端点**的那一套：自动总结传的是这一轮生效的设置（这个会话
     * 自己的模型选择已经在里面），手动总结传 [profileEndpoint] 的结果——否则画像会由另一条线
     * 上的一次运行总结出来。
     *
     * 失败只以返回值说话：自动那条路把它丢掉，用户不会被一次网络抖动打扰，已有画像也不动。
     */
    suspend fun summarize(
        store: UserProfileStore,
        settings: AppSettings,
        messages: List<StoredMessage>,
    ): ProfileSummary {
        val conversation = render(messages)
        if (conversation.isBlank()) return ProfileSummary.Failed("还没有可以总结的对话")
        // 先读一遍盘：进程刚起来就点「重新总结」时 store 还是空的，把已有画像当成没有，
        // 会让模型从头写一份，把用户手写的内容覆盖掉。
        store.load()
        val old = store.text.value.trim()
        val turn = runCatching {
            llm.complete(
                settings = request(settings),
                messages = listOf(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(payload(old, conversation)),
                ),
                tools = emptyList(),
            )
        }.getOrElse { return ProfileSummary.Failed(it.message ?: "请求失败") }
        val next = turn.text.trim()
        if (next.isEmpty()) return ProfileSummary.Failed("模型没有返回内容")
        if (next == old) return ProfileSummary.Unchanged
        if (!store.write(next)) {
            return ProfileSummary.Failed("总结超过 ${UserProfileStore.MAX_LENGTH} 字，未保存")
        }
        store.markSummarized(System.currentTimeMillis())
        return ProfileSummary.Updated
    }

    /**
     * 这一次请求的形状：端点不动，只把两件与「整理」无关的事改掉——不要思考链（它不是推理题，
     * 思考只会更慢更贵），输出上限按一篇画像给（设置里那个是给对话留的，可能小到写不完）。
     */
    private fun request(settings: AppSettings): AppSettings = settings.copy(
        reasoningEffort = "",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    private fun payload(old: String, conversation: String): String = buildString {
        append("【旧画像】\n")
        append(old.ifBlank { "（还没有画像）" })
        append("\n\n【最近的对话】\n")
        append(conversation)
    }

    /**
     * 把对话渲染成整理器要读的那几段文字。
     *
     * 只取用户与助手说的话：工具调用、工具结果、思考过程都不进——画像要的是「这个人是谁」，
     * 不是这次任务干了什么。从最新的往回取，到字数上限为止，所以一次总结的输入是有界的。
     */
    private fun render(messages: List<StoredMessage>): String {
        val lines = mutableListOf<String>()
        var chars = 0
        for (message in messages.asReversed()) {
            val text = message.text.trim()
            if (text.isEmpty()) continue
            val line = when (message.role) {
                Roles.USER -> "用户：$text"
                Roles.ASSISTANT -> "助手：$text"
                else -> continue
            }
            if (chars + line.length > MAX_CONVERSATION_CHARS && lines.isNotEmpty()) break
            lines += line
            chars += line.length
        }
        return lines.asReversed().joinToString("\n")
    }
}

/**
 * 「当前会话」的端点：会话自己的选择 → 助手的固定 → 全局默认，与聊天页读的是同一条链。
 *
 * 手动「重新总结」没有正在跑的会话，只有一份全局设置，所以要现解析一套出来；自动总结用的
 * 是运行中那套设置，不走这里。
 */
fun AppSettings.profileEndpoint(): AppSettings {
    val assistant = activeAssistant()
    val choice = choiceFor(null, null, assistant)
    return forAssistant(assistant).copy(
        baseUrl = choice.baseUrl,
        apiKey = choice.apiKey,
        model = choice.model,
    )
}

/** 整理器的系统提示词。写清「只写长期信息」，也写清没新信息时该怎么办——原样退回。 */
private const val SYSTEM_PROMPT =
    "你是画像整理器。根据【旧画像】与【最近的对话】，输出更新后的用户画像：" +
        "称呼、语言与长度偏好、习惯、环境、长期目标。只写关于用户这个人的长期信息，" +
        "不要写一次性任务细节、不要写工具调用、不要复述对话。中文，Markdown 小标题 + 短句，" +
        "≤800 字。若新对话没有新信息，原样返回旧画像。"

/** 一次总结最多读进去多少字的对话：够看出习惯，又不至于让整理本身变成一次大请求。 */
private const val MAX_CONVERSATION_CHARS = 6000

/** 输出上限：800 字中文留足余量，同时挡住一次跑飞的生成。 */
private const val MAX_OUTPUT_TOKENS = 2000

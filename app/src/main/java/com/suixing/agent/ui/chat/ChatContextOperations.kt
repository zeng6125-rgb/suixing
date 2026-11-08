package com.suixing.agent.ui.chat

import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.forAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatRuntime.resumeGoal() {
    send("/goal resume")
}


/** Clears the visible objective; an active goal's own run is also cancelled. */
internal fun ChatRuntime.clearGoal() {
    val chatId = _state.value.conversationId
    val shown = goal.value?.takeIf { it.visibleIn(chatId) } ?: return
    viewModelScope.launch(Dispatchers.IO) {
        if (environment.goals.clearFor(chatId, expectedId = shown.id) != null &&
            shown.status == GoalStatus.ACTIVE
        ) environment.engine.cancel(chatId)
    }
}


/**
 * Asks the run on screen to fold the conversation at its next turn boundary.
 *
 * Only a flag: the engine consumes it between rounds, so nothing in flight is cut and
 * the answer being written right now still lands. False means no run is going, which is
 * also when compressing would have nothing to do.
 */
internal fun ChatRuntime.requestCompress(): Boolean = environment.engine.requestCompress(_state.value.conversationId)


/**
 * **没有 run 时**手动压缩：按已落盘的历史压一次，并把结果记成一条压缩记录。
 *
 * 记录落进 [ChatUiState.checkpoints]，转录里就会在它覆盖的那一段位置出现一张压缩块 ——
 * 和工具块、思考块一样是卡片，不用另做一套界面（用户：「就气泡＋压缩块就行了」）。
 * 压完顺手刷新圆环的上下文读数。
 */
internal fun ChatRuntime.compressNow() {
    val shown = _state.value
    val chatId = shown.conversationId
    if (chatId.isEmpty()) return
    // 判据抽成纯函数（issue #69 P2-C）：有 run 交给引擎、已在压则防连点。
    if (!canStartCompression(
            hasRunningJob = jobs.containsKey(chatId),
            alreadyCompressing = compressing.contains(chatId),
        )
    ) return
    compressing += chatId
    // 状态行要立刻有反应：开始时刻进状态，ChatScreen 据此画「正在压缩上下文 · 用时 xx」。
    _state.update { it.copy(compressingSince = System.currentTimeMillis()) }
    viewModelScope.launch {
        try {
            val base = environment.settings.current()
            val assistant = base.assistant(shown.assistantId) ?: base.activeAssistant()
            // 与 start() 走同一条端点链：会话自己的选择 → 人设的固定 → 全局默认。
            val persona = base.forAssistant(assistant)
            val choice = base.choiceFor(shown.providerId, shown.model)
            val settings = persona.copy(
                baseUrl = choice.baseUrl,
                apiKey = choice.apiKey,
                model = choice.model,
                // 协议跟着这条链走：会话/人设选了哪个端点，就按那个端点说的话发。
                protocol = choice.protocol,
                reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
            )
            val history = sessions[chatId]?.messages ?: shown.messages
            // **锚点也要能在没有 run 时成立**：单独压缩时没有 live 会话，退回当前转录的
            // 最后一条消息 —— 否则压缩块会按"被折叠的最后一条"落到旧历史里（看不见）。
            val anchorId = sessions[chatId]?.messages?.lastOrNull()?.id
                ?: shown.messages.lastOrNull()?.id.orEmpty()
            environment.engine.compressNow(history, settings, sessions[chatId]?.checkpoints ?: shown.checkpoints) { checkpoint ->
                recordCheckpoint(chatId, checkpoint.copy(anchorId = anchorId))
            }?.let { reason ->
                // 压缩没成就把原因说出来（消息太少 / 网络失败 / 模型没给摘要）——
                // 以前这里把返回值丢了，用户看到的就是"点了没反应"。
                _state.update { it.copy(error = reason) }
            }
        } finally {
            compressing -= chatId
            _state.update { it.copy(compressingSince = 0L) }
        }
    }
}


/** Estimate persona and tool definitions without scanning the transcript again. */
internal fun ChatRuntime.refreshContextOverhead() {
    val shown = _state.value
    val chatId = shown.conversationId
    if (chatId.isEmpty()) return
    contextOverheadJob?.cancel()
    contextOverheadJob = viewModelScope.launch {
        val settings = environment.settings.current()
        val assistant = settings.assistant(shown.assistantId) ?: settings.activeAssistant()
        val persona = settings.forAssistant(assistant)
        val registry = environment.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
        val overhead = withContext(Dispatchers.Default) {
            val builtinSpecs = registry.all().map { it.spec }
            val pluginSpecs = PluginRuntime.enabledPlugins()
            val toolSpecs = builtinSpecs +
                PluginRuntime.toolSpecs(pluginSpecs, builtinSpecs.map { it.name }.toSet())
            val tools = toolSpecs.sumOf {
                it.name.length + it.description.length + it.parameters.toString().length
            } / 4
            val system = (persona.systemPrompt.length + registry.promptSummary().length) / 4
            system to tools
        }
        _state.update {
            if (it.conversationId == chatId && it.assistantId == shown.assistantId) {
                it.copy(contextOverhead = overhead)
            } else {
                it
            }
        }
    }
}


internal fun ChatRuntime.dismissError() {
    _state.update { it.copy(error = null) }
}


/**
 * Records one compression the engine just made.
 *
 * The record is what lets a later run reuse a summary instead of paying to produce it
 * again, so it is saved the moment it arrives. Newest last and capped: an unbounded
 * history of every compression a long conversation ever made would only grow the file.
 */
internal fun ChatRuntime.recordCheckpoint(chatId: String, checkpoint: ContextCheckpoint) {
    val live = sessions[chatId]
    val shown = _state.value.takeIf { it.conversationId == chatId }
    val stored = environment.chats.conversations.value.firstOrNull { it.id == chatId }
    // **锚点＝压缩发生那一刻最后一条已落盘消息**（用户：「运行时有这个块，会话完成一折叠就
    // 没有了」）：以前转录里那张卡按「被折叠的最后一条」定位，那是旧历史，跑完就滚到屏幕上方
    // 看不见了。锚在当下，它才和运行中看到的那一块在同一个位置。
    // 调用方**已经给过锚点**（无 run 的手动压缩，:1475）就用它的 —— 以前这里无条件覆盖成
    // `live?.messages…orEmpty()`，无 run 时把好锚点抹成空串，卡片又落回旧历史。
    val anchored = if (checkpoint.anchorId.isNotEmpty()) {
        checkpoint
    } else {
        checkpoint.copy(anchorId = live?.messages?.lastOrNull()?.id.orEmpty())
    }
    val current = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty()
    // 一趟完成「保序去重 + 取末尾 K 个」：原来 `current + anchored` 拷一次、
    // `distinctBy` 建 LinkedHashSet 再拷一次、`takeLast` 再拷一次。
    // distinctBy 保首次出现，所以先按 id 记首次位置，再取末尾 K 个。
    val unique = ArrayList<ContextCheckpoint>(current.size + 1)
    val seen = HashSet<String>(current.size + 1)
    for (item in current) if (seen.add(item.id)) unique.add(item)
    // `anchored` 是单个 checkpoint（原来靠 `List + 元素` 运算符追加到末尾），
    // 去重规则相同：id 已出现过就不再追加（distinctBy 保首次出现）。
    if (seen.add(anchored.id)) unique.add(anchored)
    val next = if (unique.size <= MAX_CHECKPOINTS) unique
    else unique.subList(unique.size - MAX_CHECKPOINTS, unique.size).toList()
    // Mirrored onto every live copy first, so the next save carries it whether the run
    // is still going or has just landed.
    if (shown != null) _state.update { it.copy(checkpoints = next) }
    live?.let {
        // 运行中的压缩要在过程区**原地**留一个块（用户：「在哪个部分进行的就在哪个部分渲染」：
        // 思考 → 工具 → 压缩 → 继续）。跑完之后由转录里那张 CheckpointCard 接管，锚点见上。
        putSession(
            it.copy(
                checkpoints = next,
                parts = it.parts + LivePart.Compress(anchored),
            ),
        )
    }
    viewModelScope.launch {
        // **只写 meta，不碰正文**（#30）：原来这里走 save(messages = base.messages) ——
        // 一次压缩就把整份转录（几十 MB）重写一遍；而且 `load()` 现在不再物化 messages，
        // `base.messages` 是空的，那个调用会被护栏判成「历史变短」。
        // `updateMeta` 只改字段，O(1)，且不可能动到消息。
        environment.chats.updateMeta(id = chatId, checkpoints = next)
    }
}

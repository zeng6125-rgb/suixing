package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Goal
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TranscriptRedaction
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.Tps
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.FINISH_CUT
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.core.Core
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import com.suixing.agent.agent.AgentRun.Companion.LIVE_LINE_CHARS
import com.suixing.agent.agent.AgentRun.Companion.MAX_TOOL_OUTPUT
import com.suixing.agent.agent.AgentRun.Companion.MIN_LIVE_TOKENS_PER_CHAR
import com.suixing.agent.agent.AgentRun.Companion.MAX_LIVE_TOKENS_PER_CHAR
import com.suixing.agent.agent.AgentRun.Companion.CALIB_MIN_CHARS

/**
 * The run itself, on the run's own job ([job]).
 *
 * Everything it counts or flags is per-handle and starts empty, because the handle was
 * made for this run and is dropped with it.
 */
internal suspend fun AgentRun.runLoop(
    history: List<StoredMessage>,
    checkpoints: List<ContextCheckpoint>,
    settings: AppSettings,
    registry: ToolRegistry,
    capabilities: CapabilityHub,
    skills: SkillStore,
    memory: MemoryStore,
    profile: UserProfileStore,
    gate: ApprovalGate,
    onEvent: (AgentEvent) -> Unit,
    onCheckpoint: (ContextCheckpoint) -> Unit,
    initialTool: ToolCallRequest? = null,
    resumeCheckpoint: ContextCheckpoint? = null,
): List<StoredMessage> {
    // Published here rather than left to the ticker, so a caller sees Running the
    // moment the run starts even if no ticker was ever started.
    publish(RunState.Running(stats()))

    val produced = mutableListOf<StoredMessage>()
    val exitPolicy = createExitPolicy(onEvent)
    fun finish(exit: LoopExit): List<StoredMessage> {
        exitPolicy.finish(exit)
        return produced
    }
    val wire = PromptMessages()
    // Which stored message each stretch of the wire came from. The wire has no ids of its own,
    // and a compression has to be able to name the messages it folded away — a checkpoint that
    // cannot say what it covered could never be resumed from by the next run.
    val spans = mutableListOf<WireSpan>()
    // Built once and measured once: the system prompt carries the skills catalogue and
    // the memory entries, so it is both the largest fixed part of the prompt and the one
    // the user can do least about.
    //
    // The catalogue follows the same switch the tools do. A persona can hide `skill_read`,
    // and ChatViewModel passes a registry with those names already filtered out; a
    // catalogue whose entries cannot be read is worse than none at all — it advertises
    // skills, spends context on them, and leaves the model guessing at procedures it is
    // not allowed to look up.
    //
    // Asked through all() rather than find(): "skill_read is not in the visible set" is the
    // question, and it is the same list the prompt is about to summarise.
    val readable = registry.all().any { it.spec.name == "skill_read" }
    val goalStore = goals
    var goalText = goalStore?.let { withContext(Dispatchers.IO) { it.read().objectiveFor(chatId) } }.orEmpty()
    var goalMode = false
    var goalClosing = false
    var subagentSummaryOnly = false
    fun beginGoalSummary(goal: Goal?): Boolean {
        if (goal?.status != GoalStatus.COMPLETED && goal?.status != GoalStatus.BLOCKED) return false
        goalClosing = true
        wire += ChatMessage.user(
            "Write the final reply to the user in Chinese. The goal is ${goal.status.name.lowercase()}. " +
                "State the verified result, or explain the blocker and ask what is needed. " +
                "Use the recorded outcome as evidence. Do not start another task.\n" +
                "Objective: ${goal.text}\nOutcome: ${goal.detail.ifBlank { goal.progress }}",
        )
        return true
    }
    suspend fun finishGoal(): List<StoredMessage> {
        awaitSubagentsBeforeFinish()
        return finish(LoopExit.GoalStopped)
    }
    val skillCatalog = withContext(Dispatchers.Default) {
        if (readable) skills.catalog(skills.listHeaders()) else ""
    }
    val systemText = withContext(Dispatchers.Default) {
        buildSystemPrompt(settings, registry, skillCatalog, memory.entries.value, profile.text.value, goalText)
    }
    breakSystem = systemText.length / 4
    wire += ChatMessage.system(systemText)

    // A checkpoint stands in for everything up to its last message. Replaying the whole history
    // and summarising it again would pay for the same summary on every following turn, and the
    // prefix would never match the previous request — which is exactly what the endpoint's
    // prompt cache keys on.
    val window = settings.contextWindowFor(settings.model)
    // A disk tail carries its verified summary; a complete transcript verifies both endpoints here.
    val checkpoint = withContext(Dispatchers.Default) {
        resumeCheckpoint ?: reusableCheckpoint(history, checkpoints)
    }
    val resumeAt = checkpoint?.let { saved -> history.indexOfFirst { it.id == saved.toMessageId } }
    val hasCheckpoint = checkpoint != null && resumeAt != null && resumeAt >= 0
    // **这段纯计算挪出主线程**（issue #36）：`trim` 要对历史做 O(条数) 的 token 估算与列表切片，
    // `sanitize`/`ensureQuestionKept` 再各扫一遍 —— 大会话每轮几十万字符。
    // 而 `runLoop` 的调用上下文是 `viewModelScope`（Main），于是**每一轮**都在主线程上做这件事，
    // 正撞上用户在别的会话里滑动（用户反馈：「有会话在跑时，切到别的会话滑动会卡」）。
    // 只搬纯计算：wire 的追加与事件顺序都在块外，线程归属不变。
    val replayed = withContext(Dispatchers.Default) {
        if (hasCheckpoint) {
            // **裁完必须仍有人问的那句话**（issue #37 第一条）：若裁出来的只有一条超大工具结果，
            // `sanitize` 会把它当孤儿丢掉 ⇒ 请求里没有任何 user，模型答非所问。
            sanitize(ensureQuestionKept(trim(history.drop(resumeAt + 1), window), history))
        } else {
            // No checkpoint, or one whose message is no longer in the transcript: the transcript is
            // what decides the prompt, so a checkpoint that no longer lines up is ignored rather
            // than half-applied.
            sanitize(ensureQuestionKept(trim(history, window), history))
        }
    }
    if (hasCheckpoint) wire += ChatMessage.system("[上下文摘要] ${checkpoint.summary}")
    // The messages themselves are never removed from the conversation — the usage watermark
    // counts them — which is why the span is found by id and the replay is a slice, not a rewrite.
    // **消息 → wire 的转换也放后台**（issue #36 同类）：每条都要做字符串拼装
    // （工具输出最长 8000 字符/条、一轮几十条），而它每轮都在主线程上跑一遍。
    // This block exclusively owns wire/spans until it returns; no per-message list/Pair.
    withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        var from = wire.size
        val counts = appendToWireBatch(wire, replayed, settings) { context.ensureActive() }
        for (index in replayed.indices) {
            val end = from + counts[index]
            spans += WireSpan(replayed[index].id, from, end)
            from = end
        }
    }

    // A ceiling of 0 (or less) means the user set none: the loop then ends only when
    // the model stops asking for tools, which is the only limit a long task should
    // have. A positive value is a real ceiling, and the failure below says so.
    val roundLimit = settings.maxToolRounds
    var round = 0
    // Hoisted out of the round on purpose: a stop can land anywhere, including in the outer
    // catch, and what already arrived is then the run's only output. A round-local
    // StringBuilder died with its round, which is how a stopped run showed the user a
    // paragraph that afterwards existed nowhere — not on screen, not on disk.
    var streamed = StringBuilder()
    // The same argument as [streamed], for the thinking: a stopped run kept its paragraph but
    // lost the chain that produced it, so the card came back with an empty reasoning block.
    var reasoning = StringBuilder()
    // Tool calls the model asked for that no result has come back for yet. A stop between the
    // two leaves the call in the transcript with nothing answering it, which drew as an empty
    // tool card — indistinguishable from a tool that returned nothing at all.
    var openCalls: List<StoredToolCall> = emptyList()
    // Retries spent on the round that is currently in flight, so a failure that used them all
    // can say so. Reset per round: the number is about the attempt that finally failed, not
    // about how unlucky the whole run was.
    var retriesThisRound = 0
    // 最近一次重试的原因（「服务器限流（429）」「网络中断」…），与 [retriesThisRound] 同生共死：
    // 这一轮最终成功落盘时，两者一起写成消息上的 [StoredMessage.traceNote]，画成过程块。
    var lastRetryReason = ""
    // 这一轮「已经收到内容、只是流被切断」时的一句话；和重试一起合成 [StoredMessage.traceNote]。
    // null＝没被切断。每轮开头清空，理由同 [retriesThisRound]。
    var cutNote: String? = null

    /**
     * 把一条工具结果**回写**进 run 级状态：transcript、wire、spans、openCalls、live 行与事件。
     *
     * 局部函数（不是成员）：它要写的是 [runLoop] 的局部变量（wire/spans/produced/openCalls），
     * 成员函数够不着。**只在主协程里逐条调用、按模型请求的顺序** —— 端点对 tool 消息顺序敏感，
     * 并行执行的结果也必须排着队回写。执行阶段（[executeOneTool]）不碰这些，所以能并发。
     */
    suspend fun commitTool(outcome: ToolOutcome) {
        val call = outcome.call
        val result = outcome.result
        // 超长输出**不能无声截断**：完整内容落盘、路径写进结果，模型才知道自己没看全、
        // 也才知道去哪取（见 spillToolOutput）。落盘失败时同样会写明"截断了且没存下来"。
        // 切 IO：这一行会 mkdirs + writeText + 列目录清理，原来是跑在 runLoop 的
        // 调用线程上（`viewModelScope` = Main.immediate）⇒ 大输出（>8000 字）时主线程卡一下。
        val spill = if (result.output.length <= MAX_TOOL_OUTPUT) null else withContext(Dispatchers.IO) { spillToolOutput(
            dir = File(Core.appContext.filesDir, "toolout"),
            tool = call.name,
            output = result.output,
            maxKept = MAX_TOOL_OUTPUT,
        ) }
        val output = spill?.kept ?: result.output
        // **落盘的那一份要屏蔽敏感数据，发给模型的那一份仍是原文**（issue #44 第二项）：
        // 剪贴板内容、用户手打的密码只该存在于这一轮，不该进 chats/store/<id>.jsonl
        // 跟着会话一直躺在磁盘上（还会随导出/备份走）。下面的 `wire +=` 用的是原始 `output`。
        val stored = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.TOOL,
            text = "",
            toolName = call.name,
            toolArgs = TranscriptRedaction.argumentsForTranscript(call.name, call.arguments),
            toolOutput = TranscriptRedaction.outputForTranscript(call.name, output),
            toolOk = result.ok,
            toolCallId = call.id,
            imageBase64 = result.imageBase64,
            // A failed later step does not undo mutations independently verified by the tool.
            fileActivities = result.fileActivities,
        )
        produced += stored
        onEvent(AgentEvent.ToolMessage(stored))
        // Answered, so a stop from here on must not answer it a second time.
        openCalls = openCalls.filterNot { it.id == call.id }
        val toolAt = wire.size
        wire += ChatMessage.tool(call.id, output)
        spans += WireSpan(stored.id, toolAt, wire.size)
        result.imageBase64?.let { base64 ->
            if (settings.imageInput) {
                wire += ChatMessage.screenshot("（这是上一步 ${call.name} 返回的截图）", listOf(base64))
            } else {
                wire += ChatMessage.user(
                    "（上一步 ${call.name} 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）",
                )
            }
        }
        liveLine = if (result.ok) {
            String.format(Locale.US, "✓ %s %.1fs", call.name, outcome.toolMs / 1000.0)
        } else {
            "✗ ${call.name}"
        }
        onEvent(AgentEvent.ToolFinished(call.name, result.ok, output, result.todoSnapshot))
        // Only committed outcomes count; hashing large arguments/results must stay off Main.
        val step = ++toolStep
        withContext(Dispatchers.Default) {
            val args = outcome.args ?: JsonObject(mapOf("_invalid_arguments" to JsonPrimitive(call.arguments)))
            ledger.record(step, call.name, args, result)
        }
    }

    suspend fun deliverSubagentResults(): Boolean {
        val pool = subagents ?: return false
        val completed = pool.unreportedResults()
        if (completed.isEmpty()) return false
        val args = JsonObject(mapOf("ids" to JsonArray(completed.map { JsonPrimitive(it.id) })))
        val arguments = args.toString()
        val call = ToolCallRequest(UUID.randomUUID().toString(), "wait_agents", arguments)
        // Use the same persisted tool-call/result pair as an explicit wait. This keeps
        // provider replay valid and gives the parent the evidence before its next request.
        val assistant = StoredMessage(
            id = UUID.randomUUID().toString(), role = Roles.ASSISTANT,
            toolCalls = listOf(StoredToolCall(call.id, call.name, arguments)),
        )
        produced += assistant
        onEvent(AgentEvent.AssistantMessage(assistant))
        val from = wire.size
        wire += ChatMessage.assistant("", listOf(call))
        spans += WireSpan(assistant.id, from, wire.size)
        openCalls = assistant.toolCalls
        onEvent(AgentEvent.ToolStarted(call.name, arguments))
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.Default) { pool.execute(call.name, args) }
        commitTool(ToolOutcome(call, result, System.currentTimeMillis() - started, arguments, args))
        return true
    }

    try {
        // **工具目录在 run 开始时定一次**（issue #44 第三项，见循环内的说明）。
        // 放 Default 上拼：`enabledPlugins()` 在 TTL 过期时扫目录 + 逐插件 readText +
        // JSON 解析，`breakTools` 的 parameters.toString() 是全量序列化 —— 都不该在 Main。
        val delegationAllowed = settings.subagentsEnabled && settings.approvalMode != ApprovalMode.DENY &&
            !registry.isHidden("spawn_agent")
        val frozenTable = withContext(Dispatchers.Default) {
            val delegationSpecs = if (delegationAllowed) SUBAGENT_SPECS.filterNot { registry.isHidden(it.name) } else emptyList()
            val builtinSpecs = registry.all().map { it.spec } +
                listOf(COMPRESS_CONTEXT_SPEC).filterNot { registry.isHidden(it.name) } + delegationSpecs
            val pluginSpecs = PluginRuntime.enabledPlugins()
            val toolSpecs = (builtinSpecs +
                PluginRuntime.toolSpecs(pluginSpecs, builtinSpecs.map { it.name }.toSet()).filterNot { registry.isHidden(it.name) }
                ).filter { childConfig == null || it.name in childConfig.allowedTools }
            ToolTable(
                pluginSpecs,
                toolSpecs,
                toolSpecs.sumOf {
                    it.name.length + it.description.length + it.parameters.toString().length
                } / 4,
            )
        }
        if (delegationAllowed) {
            subagents = createSubagentPool(
                settings, frozenTable.specs, registry, capabilities, skills, memory, profile, gate,
                parentGoalText = { goalText },
            )
            subagentReadyChats.update { it + chatId }
        }
        suspend fun executeDirectTools(): LoopExit? {
            // User-invoked tools use the same approval, execution and transcript path as
            // model tools. They run before complete(), never as a lossy bottom notice.
            while (directTools.isNotEmpty()) {
                if (cancelled.get()) {
                    return LoopExit.Cancelled(streamed, reasoning, openCalls)
                }
                val queued = directTools.removeFirst()
                queued.steer?.let { appendSteer(it, wire, spans, onEvent, settings) }
                val call = queued.call
                val assistant = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    toolCalls = listOf(StoredToolCall(call.id, call.name,
                        TranscriptRedaction.argumentsForTranscript(call.name, call.arguments))),
                )
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                val from = wire.size
                wire += ChatMessage.assistant("", listOf(call))
                spans += WireSpan(assistant.id, from, wire.size)
                openCalls = assistant.toolCalls
                val outcome = executeOneTool(call, registry, frozenTable.plugins, frozenTable.hooks,
                    capabilities, gate, settings, chatId, onEvent, userInvoked = true) ?: run {
                    return LoopExit.Cancelled(streamed, reasoning, openCalls)
                }
                commitTool(outcome)
                if (!outcome.result.ok) {
                    awaitSubagentsBeforeFinish()
                    return LoopExit.DirectToolRejected
                }
            }
            return null
        }
        initialTool?.let { directTools.addLast(QueuedDirectTool(it)) }
        while (true) {
            if (!goalClosing && !subagentSummaryOnly && roundLimit > 0 && round >= roundLimit) {
                if (subagents?.snapshots()?.isNotEmpty() == true) {
                    awaitSubagentsBeforeFinish()
                    deliverSubagentResults()
                    subagentSummaryOnly = true
                    wire += ChatMessage.user("工具轮数预算已用尽。请根据已返回的子任务结果汇总答复，说明未完成或失败项，不再执行工具。")
                }
                if (!subagentSummaryOnly) {
                    if (goalRun != null && goalRun.id.isNotEmpty()) {
                        goalRun.block("已达到设置中的最大工具轮数（$roundLimit），可以继续")
                        return finishGoal()
                    }
                    return finish(LoopExit.StepLimit(roundLimit))
                }
            }
            round++
            // Emptied at the top of the round rather than next to the call: by the time the
            // loop comes back here the previous round's text is already an assistant message,
            // and a stop anywhere in this round — even one thrown out of the compression
            // below — must not hand the same paragraph to [finishCancelled] a second time.
            streamed = StringBuilder()
            reasoning = StringBuilder()
            openCalls = emptyList()
            // Steering lands here, on the boundary between two model turns: the
            // model has finished the previous turn and has not been asked for the
            // next one, which is the only moment the wire can be edited safely.
            if (!goalClosing && !subagentSummaryOnly && directTools.isEmpty()) drainSteering(wire, spans, onEvent, settings)
            if (!goalClosing && !subagentSummaryOnly) {
                executeDirectTools()?.let { return finish(it) }
            }
            // All preceding tool responses are committed before child results join the wire.
            deliverSubagentResults()
            val updatedGoalMode = !goalClosing && !subagentSummaryOnly && goalRun != null && goalRun.id.isNotEmpty()
            val objective = if (updatedGoalMode) goalRun.beforeRequest() else goalStore?.read()
            if (updatedGoalMode && objective?.status != GoalStatus.ACTIVE) {
                if (beginGoalSummary(objective)) continue
                return finishGoal()
            }
            val updatedGoal = objective?.objectiveFor(chatId).orEmpty()
            if (updatedGoal != goalText || updatedGoalMode != goalMode) {
                goalText = updatedGoal
                goalMode = updatedGoalMode
                val updatedSystem = withContext(Dispatchers.Default) {
                    buildSystemPrompt(settings, registry, skillCatalog, memory.entries.value, profile.text.value, goalText, goalMode)
                }
                wire[0] = ChatMessage.system(updatedSystem)
                breakSystem = updatedSystem.length / 4
            }
            // A manual 「压缩」 is spent here, on the boundary: the user asked for the oldest
            // context to be folded away, not for the answer in flight to be cut short.
            val asked = compressNow
            compressNow = false
            compressContext(
                wire, spans, settings, onCheckpoint, asked,
                // **端点实际上报的 prompt token**（issue #44 第七项规则 1）：自动压缩只信这个数，
                // 首次请求返回之前它是 0 ⇒ 不触发（"缺少实际 usage 时不猜容量"）。
                realPromptTokens = lastPromptTokens,
                // 摘要要再发一次模型请求，这几秒里状态行必须说"在压缩"，否则看起来像卡死
                // （用户：「run 的时候状态行也跟上」）。false 时清掉，下一句会覆盖它。
                onBusy = { onEvent(AgentEvent.Compressing(it)) },
            )
            // Cleared with the round: whatever the last one ended with is over, and a
            // line that outlives its step reads as a stall.
            liveLine = ""
            retriesThisRound = 0
            lastRetryReason = ""
            cutNote = null
            if (cancelled.get()) {
                return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
            }

            // All results in the previous tool batch have landed. Append at the request tail
            // rather than between an assistant's tool calls and their responses.
            ledger.warning()?.let { warning ->
                wire += ChatMessage.user("[工具执行记录]\n${ledger.brief()}\n$warning")
            }

            // Measured here, where the wire for this round is final: steering has landed
            // and compression has already run, so the numbers match what is about to be
            // sent rather than what the round started with.
            // **run 期间工具目录冻结**（issue #44 第三项）：原来这里是每轮重新拼装，
            // 于是无障碍/Shizuku/Root 能力一变化（或插件目录一变化）工具表就换掉 ⇒
            // prompt 的工具清单前缀全失效，整个上下文缓存作废，下一轮重新计费 + 变慢。
            // 现在目录在 run 开始时定一次（见循环外的 frozenTable），这里直接复用。
            // 代价：插件热插拔的粒度从"下一个请求"变成"下一次 run"—— 这是刻意的取舍，
            // 缓存前缀的稳定性比"中途装个插件立刻能用"更值钱。
            val toolSpecs = if (goalClosing || subagentSummaryOnly) emptyList() else frozenTable.specs
            // The frozen table's estimate is computed once on Default, including
            // JSON serialization. No signature allocation or schema work per round.
            breakTools = if (goalClosing || subagentSummaryOnly) 0 else frozenTable.estimatedTokens
            // 三个数字都来自同一份 wire：一次遍历算完，别为每个数字各扫一遍
            // （大会话每轮要扫几十万字符，而这三处原本是三次独立的全量遍历）。
            // **这一遍扫描也放后台**（issue #36 同类）：几十万字符的遍历不该占着主线程，
            // 而它每轮都跑一次。只搬计算，赋值仍在原地。
            val (historyChars, promptChars, promptImages) = wire.counts
            val promptEstimate = estimatePromptTokens(promptChars, promptImages)
            breakHistory = historyChars / 4
            // 显示层的「当前请求多大」：wire 在这一刻定型，估算＝端点真实值 + 增量。
            currentPromptEstimate = promptEstimate
            val turnStartedAt = System.currentTimeMillis()
            // When the first piece of anything arrived: the wait a user actually feels,
            // and one the endpoint's own usage block never reports.
            var firstChunkAt = 0L
            // Cleared here rather than after the call: an abort from `steer` can only
            // belong to the round it interrupted, so nothing else may inherit the flag.
            steeringCut.set(false)
            // The console shows the tail of what is being written; the finished text only
            // exists once the turn ends, which is far too late for a "still working" line.
            val turn = try {
                llm.complete(
                    settings = settings,
                    // 出站前按预算降级更早的**工具截图**（见 pruneRequestImages）：live 与
                    // rebuild 都经过这一个函数，所以两条路径发出去的 prompt 仍然一致。
                    messages = pruneRequestImages(wire, MAX_REQUEST_IMAGE_CHARS),
                    tools = toolSpecs,
                    onDelta = { piece ->
                        if (firstChunkAt == 0L || curTurnFirstChunkMs == 0L) {
                            val arrivedAt = System.currentTimeMillis()
                            if (firstChunkAt == 0L) firstChunkAt = arrivedAt
                            if (curTurnFirstChunkMs == 0L) curTurnFirstChunkMs = arrivedAt
                        }
                        outChars.addAndGet(piece.length)
                        streamed.append(piece)
                        liveLine = streamed.substring((streamed.length - LIVE_LINE_CHARS).coerceAtLeast(0))
                        onEvent(AgentEvent.TextDelta(piece))
                    },
                    onReasoning = { piece ->
                        if (firstChunkAt == 0L || curTurnFirstChunkMs == 0L) {
                            val arrivedAt = System.currentTimeMillis()
                            if (firstChunkAt == 0L) firstChunkAt = arrivedAt
                            if (curTurnFirstChunkMs == 0L) curTurnFirstChunkMs = arrivedAt
                        }
                        thinkingChars.addAndGet(piece.length)
                        reasoning.append(piece)
                        liveLine = "思考中…"
                        onEvent(AgentEvent.ReasoningDelta(piece))
                    },
                    onCall = { inFlight = it },
                    // Visible rather than silent: a retry means the user is waiting longer for
                    // nothing they can see, and a console line is the difference between
                    // "it is thinking" and "it is stuck".
                    onRetry = { notice ->
                        retriesThisRound = notice.attempt
                        lastRetryReason = notice.reason
                        liveLine = "第 ${notice.attempt} 次重试（${notice.delayMs / 1000}s 后）· ${notice.reason}"
                        // And out to the screen: the console's live line is not where the user
                        // is looking. Without this, a retrying run looks like one that simply
                        // stopped — which is exactly how 「发出去几秒之后就停止」 was reported.
                        onEvent(
                            AgentEvent.Retrying(
                                attempt = notice.attempt,
                                maxAttempts = notice.maxAttempts,
                                delayMs = notice.delayMs,
                                reason = notice.reason,
                                code = notice.code,
                            ),
                        )
                    },
                    // 收到内容之后流断了、而且还能重试：这一轮作废重来。先把这一轮已经
                    // 流出去的字清干净（缓冲 + 屏幕），再让客户端重发 —— 否则同一段开头会
                    // 在气泡里出现两遍。屏幕那一半靠 RoundRestarted，VM 把本段 live parts 撤掉。
                    onRestart = {
                        streamed = StringBuilder()
                        reasoning = StringBuilder()
                        openCalls = emptyList()
                        onEvent(AgentEvent.RoundRestarted)
                    },
                )
            } catch (error: IOException) {
                // Cancelling a call surfaces as an IOException on the reading
                // thread. It is a stop, not a failure, so it is reported as one.
                if (cancelled.get()) {
                        return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
                }
                // An immediate steer aborts the stream the same way, but nothing is
                // wrong: this round is abandoned so the next one can carry the line.
                // Rethrowing here would kill the run the user just spoke to.
                if (steeringCut.get()) {
                    steeringCut.set(false)
                    continue
                }
                throw error
            } finally {
                inFlight = null
            }
            // The stop can land in the gap between clearing `inFlight` above and the next
            // round's `onCall`: in that window there is no call to cancel yet — the payload is
            // still being serialised — so nothing reads the flag, and this round would stream
            // to the end and report the whole run as Done.
            if (cancelled.get()) {
                return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
            }
            val elapsed = System.currentTimeMillis() - turnStartedAt
            val firstTokenMs = if (firstChunkAt > 0L) firstChunkAt - turnStartedAt else 0L
            // 本轮结束：把它的 decode 段（首字之后→结束）累加进 run 级和，再清零本轮标记，
            // 这样轮间的工具执行期不会被 [stats] 当成 decode 时间。官方多轮聚合＝Σdecode/Σtokens。
            decodeMsAccum += Tps.decodeMs(elapsed, firstTokenMs)
            curTurnFirstChunkMs = 0L
            val usage = turn.usage
            // 用这一轮端点报的**真实** completionTokens 校准 live 估算的每字符 token 数：
            // 本轮字符增量 = 当前累计 − 上次校准时的累计。字符够多、比率落在合理区间才采纳，
            // 否则保留旧值 —— 防一轮畸形数据（纯工具调用轮字符≈0，或极短轮次）把估算带飞。
            // 这修的是「明明 200 tok/s、手机上只有几十」：live 分子此前用 chars/3（英文系数），
            // 对中文低估 ~2.7 倍。校准后从第二轮起 live 读数就贴着真实值。
            usage?.completionTokens?.takeIf { it > 0 }?.let { realTokens ->
                val totalChars = outChars.get() + thinkingChars.get()
                val turnChars = totalChars - calibCharsBaseline
                if (turnChars >= CALIB_MIN_CHARS) {
                    val ratio = (realTokens.toFloat() / turnChars)
                        .coerceIn(MIN_LIVE_TOKENS_PER_CHAR, MAX_LIVE_TOKENS_PER_CHAR)
                    liveTokensPerChar = ratio
                    calibCharsBaseline = totalChars
                }
            }
            // Kept for the context readout and for pricing the next round's growth:
            // only the endpoint knows how big the prompt really was.
            usage?.promptTokens?.takeIf { it > 0 }?.let { reported ->
                lastPromptTokens = reported
                // No wire mutation occurs during complete(); reuse this request's scan.
                lastPromptChars = promptChars
                lastPromptImages = promptImages
            }
            val thinking = turn.reasoning.takeIf { it.isNotBlank() }
            // A truncated turn can end mid-tool-call, so say so instead of silently
            // presenting half an answer as a finished one.
            if (turn.finishReason == "length") {
                // **不归因**，只报事实。用户明确说过：他没设上限、上游上下文是 131K，所以
                // 「达到长度上限」这种说法在他那里是错的 —— 我们并不知道上游的输出上限是多少，
                // 也就没有资格说"是它撞了上限"。这里只把可观察的事实写清楚：网关给的
                // finish_reason 原文、以及它自己报的 completion_tokens；有设置上限时才顺带提一句
                // （是陈述，不是归责）。
                val produced = turn.usage?.completionTokens?.takeIf { it > 0 }
                val ours = settings.maxTokens
                onEvent(
                    AgentEvent.Failed(
                        "上游结束了这一轮（finish_reason=${turn.finishReason}" +
                            (produced?.let { "，它报的 completion_tokens=$it" } ?: "") +
                            (if (ours > 0) "；本轮设置的最大输出是 $ours" else "") +
                            "）。若你并没有设置输出上限，那这是上游模型自己的结束条件" +
                            "（与 131K 那样的上下文窗口是两件事，我们看不到它的输出上限）：" +
                            "把要求拆小一点再试；若是用 write_file 写大文件，让它分几次写" +
                            "（先写头部，再用 `append: true` 追加）。",
                    ),
                )
            }
            // The stream broke after delivering content (see FINISH_CUT): the round is a
            // success — the answer is real and is kept — but the user should know it stopped
            // early rather than wonder why the reply ends mid-sentence.
            // **用户自己按停不算错误**（用户：「不是我自己中断也算错误吗」）：停止同样把流
            // 切断成 FINISH_CUT，不加这道判定就会给"用户刚按下的按钮"发一张错误回执；每停一次
            // 一张，连停五次就是截图里那五条一模一样的红卡。
            if (turn.finishReason == FINISH_CUT && !cancelled.get()) {
                // 流在已经收到内容之后被切断：这一轮**是成功的**（回答是真的、也保留），只是没说完。
                // 以前这里发 `Failed` —— 那会落一张红色回执卡，等于告诉用户"这轮失败了"；用户明确
                // 要求「整轮对话是成功的话 就不要有 错误提示卡片存在」。现在它和重试一样，变成
                // 消息上的过程留痕（[StoredMessage.traceNote]），画成过程块。
                cutNote = "连接中断，这一轮的回答可能不完整"
            }
            if (goalClosing || subagentSummaryOnly || turn.toolCalls.isEmpty()) {
                val assistant = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = turn.text,
                    reasoning = thinking,
                    traceNote = traceNoteOf(retriesThisRound, lastRetryReason, cutNote),
                ).withUsage(usage, elapsed, settings.model, firstTokenMs)
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                // The answer goes into the wire either way: the finish path below never asks
                // the model anything again, and the steering path needs it *in front of* the
                // line the user just said — a steer that read as if it had come before the
                // answer it interrupted would be answered as the earlier turn.
                val answerAt = wire.size
                wire += ChatMessage.assistant(turn.text)
                spans += WireSpan(assistant.id, answerAt, wire.size)
                // A line the user slipped in while this round was streaming: this round is not
                // the last one, so the run takes one more pass to read it. Finishing here
                // instead would leave a steer bubble on screen that the model never saw, with
                // nothing telling the user it went nowhere.
                if (!goalClosing && !subagentSummaryOnly && drainSteering(wire, spans, onEvent, settings) > 0) continue
                if (!goalClosing && !subagentSummaryOnly && goalRun != null && goalRun.id.isNotEmpty()) {
                    if (!goalRun.continueAfterPlainReply()) {
                        if (beginGoalSummary(goalRun.snapshot())) continue
                        return finishGoal()
                    }
                    wire += ChatMessage.user(
                        "Continue the active goal. Take the next concrete action, then use goal_update " +
                            "to report progress, verified completion, or a blocker.",
                    )
                    continue
                }
                if (goalClosing && turn.text.isBlank()) {
                    return finish(LoopExit.MissingGoalSummary)
                } else {
                    awaitSubagentsBeforeFinish()
                    if (deliverSubagentResults()) {
                        // Allow result synthesis at the budget boundary without granting
                        // another tool round or silently throwing away accepted child work.
                        subagentSummaryOnly = roundLimit > 0 && round >= roundLimit
                        if (subagentSummaryOnly) {
                            wire += ChatMessage.user("工具轮数预算已用尽。请根据已返回的子任务结果汇总答复，说明未完成或失败项，不再执行工具。")
                        } else if (!goalClosing && !cancelled.get()) {
                            subagents?.resumeAdmission()
                            subagentReadyChats.update { it + chatId }
                        }
                        continue
                    }
                    return finish(LoopExit.Done(turn.text))
                }
            }
            val assistant = StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.ASSISTANT,
                text = turn.text,
                reasoning = thinking,
                // 同上：参数落盘前按工具定义屏蔽（剪贴板写入、ui_type 的文本可能是密码）。
                toolCalls = turn.toolCalls.map {
                    StoredToolCall(
                        it.id,
                        it.name,
                        TranscriptRedaction.argumentsForTranscript(it.name, it.arguments),
                    )
                },
                traceNote = traceNoteOf(retriesThisRound, lastRetryReason, cutNote),
            ).withUsage(usage, elapsed, settings.model, firstTokenMs)
            produced += assistant
            onEvent(AgentEvent.AssistantMessage(assistant))
            val assistantAt = wire.size
            wire += ChatMessage.assistant(turn.text, turn.toolCalls)
            spans += WireSpan(assistant.id, assistantAt, wire.size)
            // This round's text is a message now, so a stop from here on (before a tool call,
            // or while one is running) must not hand the same paragraph to [finishCancelled]
            // again: it would land in the transcript as a second, identical bubble. The
            // reasoning goes with it — it is already on this message — while the calls it asked
            // for are now the ones a stop would leave unanswered.
            streamed = StringBuilder()
            reasoning = StringBuilder()
            openCalls = assistant.toolCalls

            if (!executeToolRequests(
                    turn.toolCalls, settings, registry, frozenTable, capabilities, gate, onEvent, ::commitTool,
                )) {
                return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
            }
            if (goalRun != null && goalRun.id.isNotEmpty()) {
                val goal = goalRun.snapshot()
                if (goal?.status != GoalStatus.ACTIVE) {
                    if (beginGoalSummary(goal)) continue
                    return finishGoal()
                }
            }
        }
    } catch (cancellation: CancellationException) {
        if (interruptedFileTools.isNotEmpty()) {
            withContext(NonCancellable) {
                for (call in openCalls) interruptedFileTools.remove(call.id)?.let { commitTool(it) }
            }
        }
        inFlight?.cancel()
        // Two different things land here. A stop the user asked for cancels this run's own
        // job while the caller is still alive, and that caller has every right to the messages
        // the run produced — throwing would hand it a cancellation it never asked for, and
        // would kill the finally of whoever merely asked for a run. A cancellation that came
        // from the caller's own scope is the other thing entirely and still travels as one.
        if (cancelled.get() && caller?.isCancelled != true) {
            return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
        }
        // The caller's scope is gone: the stream still has to be taken down, the paragraph
        // that already arrived still has to be kept, and the state still has to say what
        // happened — by hand, because this branch rethrows.
        return finish(LoopExit.CallerCancelled(cancellation, streamed, reasoning, openCalls))
    } catch (error: Throwable) {
        // Cutting the wire makes OkHttp throw IOException("Canceled"), so a run the user
        // stopped lands here rather than in the CancellationException branch above. It is
        // the stop working, not a failure: publishing Failed would put an error card on
        // screen for a button the user just pressed, and the caller would be handed a
        // throwable it turns into one too.
        if (cancelled.get()) {
            return finish(LoopExit.Cancelled(streamed, reasoning, openCalls))
        }
        return finish(LoopExit.Failed(error, retriesThisRound))
    } finally {
        withContext(NonCancellable) {
            if (subagents != null) {
                subagents?.close()
                SubagentRecords.flush(chatId)
            }
        }
        if (goalRun != null && goalRun.id.isNotEmpty()) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { goalRun.pauseOnExit(cancelled.get()) }
            }
        }
        subagents = null
        subagentReadyChats.update { it - chatId }
        childStates.update { it - chatId }
        inFlight = null
        // Nothing is published here. The run has already said how it ended, and that
        // answer is what the conversation's state flow keeps for whoever still reads it.
    }
}

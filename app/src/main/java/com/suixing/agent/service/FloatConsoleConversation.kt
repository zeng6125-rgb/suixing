package com.suixing.agent.service

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.agent.RunState
import com.suixing.agent.core.Core
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.withConversationApproval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.suixing.agent.service.FloatConsoleService.Companion.MESSAGE_PREVIEW_CHARS
import com.suixing.agent.service.FloatConsoleService.Companion.RECENT_MESSAGE_COUNT
import com.suixing.agent.service.FloatConsoleService.Companion.TOOL_LIVE_PREFIX
import com.suixing.agent.service.FloatConsoleService.ConsoleSettings

/**
 * Mirrors the engine's run state into the panel, and keeps the panel's own two lines current.
 *
 * The state comes from [Core.engine] rather than from anything local, so the panel
 * and the chat screen can never tell two different stories about the same run.
 */
internal fun FloatConsoleService.observeRun() {
    scope.launch {
        var lastChatId: String? = null
        // The console follows whichever run started last; the map re-emits on every
        // start/stop, so a newer run takes the panel over and an ended one hands it
        // back to the previous run or hides the panel when none is left.
        Core.engine.runsFlow
            .collectLatest { runs ->
                // The panel follows whichever run started last; the map re-emits on every
                // start/stop, so a newer run takes the panel over and an ended one hands it
                // back to the previous run or hides the panel when none is left. The stop
                // area, unlike the panel, shows every run in flight.
                val orderedRuns = runs.values.sortedByDescending { it.startedAt }
                val handle = orderedRuns.firstOrNull()
                if (handle != null) lastChatId = handle.chatId
                renderStopArea(orderedRuns)
                renderConversation()
                refreshVisibility()
                val stateFlow = lastChatId?.let { Core.engine.stateOf(it) }
                var wasRunning = false
                suspend fun renderState(state: RunState) {
                // **相同文本不重设**（issue #56 R26）：根布局与这些 TextView 都是
                // WRAP_CONTENT，`TextView.setText` 对相同字符串没有可靠短路，会走
                // checkForRelayout() ⇒ requestLayout()+invalidate()。状态流 8Hz 抖动时
                // 内容常常没变，却每秒 8 次整窗 measure/layout/draw。
                stateText.setTextIfChanged(consoleStatus(state, handle))
                showStats(state.detail())
                // The live line is re-read on every state, and the engine ticks its state
                // while a run is in flight, so this stays current without a second stream.
                // 空时整行收起：截图里「正在执行」与会话名之间那条空白带，就是空的 liveText
                // 仍占着一行行高。有内容才显示。
                val live = if (state is RunState.Running) handle?.liveLine.orEmpty() else ""
                liveText.setTextIfChanged(live)
                liveText.visibility = if (live.isBlank()) View.GONE else View.VISIBLE
                // One button, two meanings, decided by the same question the label answers:
                // while something is running a line typed here joins that run, and with
                // nothing running it opens the conversation's next turn. The chat composer
                // reads the same way, so the two never disagree about what a tap will do.
                sendButton.setTextIfChanged(if (state is RunState.Running) "插话" else "发送")
                val running = state is RunState.Running
                ballMotion.setRunning(running)
                // Sparks fly in once on the leading edge of a run, not on every 8Hz tick.
                if (running && !wasRunning) ballMotion.playRunStart()
                wasRunning = running
                }
                if (stateFlow == null) renderState(RunState.Idle)
                else stateFlow.collect { renderState(it) }
            }
    }
    // Which conversation the panel is talking about: the list re-emits on every save, so the
    // title, the last lines and a rename all land without polling.
    scope.launch {
        Core.chats.conversations.collectLatest { renderConversation() }
    }
    // Both visibility switches live in the settings: a flip has to be felt at once, or the
    // ball would sit there disagreeing with the switch that turned it off. This collector is
    // also the only writer of [consoleSettings] — the synchronous copy everything else reads.
    scope.launch {
        Core.settings.flow.collect { stored ->
            settingsLoaded = true
            consoleSettings = ConsoleSettings(
                resident = stored.floatingConsole,
                whileRunning = stored.consoleWhileRunning,
                widthDp = stored.consoleWidthDp,
                heightDp = stored.consoleHeightDp,
            )
            trace("settings resident=${stored.floatingConsole} whileRunning=${stored.consoleWhileRunning} width=${stored.consoleWidthDp} height=${stored.consoleHeightDp}")
            applyPanelWidth()
            applyPanelHeight()
            refreshVisibility()
        }
    }
}

/**
 * The run's own title, except while a `ui_*` tool is on screen.
 *
 * 「正在操作手机」 is what the user wants to read when the agent is the one driving, and the
 * tool's own name says nothing about that. Read off the live line the engine already publishes
 * (`🔧 name…`), so nothing new has to be plumbed through for it.
 */
internal fun FloatConsoleService.consoleStatus(state: RunState, handle: AgentEngine.RunHandle?): String {
    val live = handle?.liveLine.orEmpty()
    if (state is RunState.Running && live.startsWith(TOOL_LIVE_PREFIX)) {
        val tool = live.removePrefix(TOOL_LIVE_PREFIX).trimEnd('…')
        if (tool.startsWith("ui_")) return "正在操作手机（$tool）"
    }
    return state.title()
}

/**
 * The conversation the panel is showing, and its last one or two lines.
 *
 * The one running wins over the one used last, because that is the chat a steer typed here
 * would land in: the panel must name the conversation its own box writes to, or the user would
 * be typing into a chat they cannot see. With nothing running it falls back to the top of the
 * list, which is the conversation the app itself would open.
 */
/**
 * 设置第三行的文案；空串时把这一行整个收起（GONE），而不是留一条空行。
 *
 * Running/Done 时 [detail] 返回空 ⇒ 这行消失，那一格空间让给 [liveText]（正在生成的内容）与
 * [contentScroll]（会话内容）—— 就是用户要的「悬浮窗最主要显示内容，不刷统计」。瞬态反馈
 * （已发送/已插话/发送失败）非空，照常显示。
 */
internal fun FloatConsoleService.showStats(text: String) {
    statsText.setTextIfChanged(text)
    val wanted = if (text.isBlank()) View.GONE else View.VISIBLE
    if (statsText.visibility != wanted) statsText.visibility = wanted
}

/**
 * 内容没变就不碰 TextView。
 *
 * 面板里的文本控件都是 `WRAP_CONTENT`，根 `params` 也是 —— `setText` 即使字符串相同
 * 也没有可靠短路，会触发 `checkForRelayout()` 进而整窗 measure/layout（issue #56 R26）。
 */
private fun TextView.setTextIfChanged(next: CharSequence) {
    if (!text.contentEquals(next)) text = next
}

/**
 * **改成挂起**（#30）：正文要按需从磁盘读（列表只带 meta）。
 * 两个调用点都在协程里（`collectLatest`），所以这里挂起是安全的；
 * 读的是**尾部**而不是全文 —— 悬浮窗只回显最近几条，没必要把 30MB 拉进内存。
 */
internal suspend fun FloatConsoleService.renderConversation() {
    val revision = ++conversationRenderRevision
    val conversations = Core.chats.conversations.value
    val running = Core.engine.newestRun()?.chatId
    val conversation: Conversation? =
        conversations.firstOrNull { it.id == running } ?: conversations.firstOrNull()
    if (conversation == null) {
        conversationText.text = "还没有会话"
        recentText.text = "在下面的输入框里说一句，就会开一个新会话"
        return
    }
    conversationText.text = conversation.title.ifBlank { "新会话" }
    // 只留有正文的行：工具消息（role=TOOL、text=""）与失败回执等以前会显示成一条空的
    // 「JAgent：」，占位却没内容。内容区可滚后回显最近 6 条，拖高就能多看。
    // **正文要自己读**（#30）：列表只带 meta。用 runBlocking 拉全文会很糟，
    // 所以这里只在后台线程取；renderConversation 由 UI 线程调用，改为 post 一次。
    // 只取尾部几条就够回显；`readTail` 给的是「最近 N 轮」，比全文小一个数量级。
    val recentMessages = conversation.messages.ifEmpty {
        // **同键不重读**（issue #69 P1-A）：列表会因标题/其它会话的新消息而频繁发射，
        // 那些都与本会话正文无关。只有会话/条数/更新时间变了才读一次尾部。
        if (tailGate.shouldRead(conversation)) {
            tailCache = runCatching { Core.chats.readTail(conversation.id)?.messages }
                .getOrNull().orEmpty()
        }
        tailCache
    }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (revision != conversationRenderRevision) return
    val recent = recentMessages
        .filter { it.text.isNotBlank() }
        .takeLast(RECENT_MESSAGE_COUNT)
    recentText.text = if (recent.isEmpty()) {
        "这个会话还没有消息"
    } else {
        recent.joinToString("\n\n") { message ->
            val speaker = if (message.role == Roles.USER) "你：" else "JAgent："
            speaker + message.text.replace('\n', ' ').trim().take(MESSAGE_PREVIEW_CHARS)
        }
    }
}

/**
 * Rebuilt whenever a run starts or ends.
 *
 * One run needs no label: there is nothing to disambiguate, so the button is the same one
 * the console has always had. Two or more get a row each, titled, because a single button
 * could only ever stop whichever run happened to be newest — not the one the user was
 * looking at.
 */
internal fun FloatConsoleService.renderStopArea(handles: List<AgentEngine.RunHandle>) {
    val key = handles.map { Triple(it.chatId, it.startedAt, titleOf(it)) }
    if (key == stopAreaKey) return
    stopAreaKey = key
    stopArea.removeAllViews()
    if (handles.size <= 1) {
        // 单 run（或没有）：停止键就落在底部动作行「回到对话」的右侧（[stopButton]），
        // 不再单独占一行 —— 截图里那行孤悬右下角的停止按钮和它上面的死空间就是这么来的。
        stopArea.visibility = View.GONE
        stopButton.visibility = if (handles.isEmpty()) View.GONE else View.VISIBLE
        return
    }
    // 两个以上：每个 run 一条带标题的行（一个按钮猜不出用户想停哪个），底部那枚隐藏。
    stopArea.visibility = View.VISIBLE
    stopButton.visibility = View.GONE
    for (handle in handles) {
        stopArea.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = titleOf(handle)
                        setTextColor(palette.ink)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    },
                )
                addView(actionButton("停止", accent = true) { stopRun(handle) })
            },
        )
    }
}

internal fun FloatConsoleService.stopRun(handle: AgentEngine.RunHandle) {
    val stopped = Core.engine.cancel(handle.chatId)
    // Named, so a stop aimed at one of several runs says which one it hit — and on its own
    // line, because the run it names is about to leave the list and take its row with it.
    feedbackText.text = if (stopped) "已请求停止：${titleOf(handle)}" else "这个任务已经结束了"
    feedbackText.visibility = View.VISIBLE
}

/**
 * The conversation's own title, so a rename is reflected; the title the run started with, for
 * a conversation the list has not caught up with; then a short id, which is at least
 * something the user can match against the chat list.
 */
internal fun FloatConsoleService.titleOf(handle: AgentEngine.RunHandle): String {
    val current = Core.chats.conversations.value.firstOrNull { it.id == handle.chatId }?.title
    return current?.takeIf { it.isNotBlank() }
        ?: handle.title.takeIf { it.isNotBlank() }
        ?: "会话 ${handle.chatId.take(6)}"
}

/**
 * The panel's box: 能发 — a line typed here reaches the conversation the panel names.
 *
 * Two cases, one button. A run in flight gets the line as a steer, which is what the console
 * has always done. With nothing running the line becomes that conversation's next turn, and
 * that goes through [Core.engine]'s own [AgentEngine.startRun] with the app's registry, gate
 * and stores — the same call the chat screen makes, not a second sending path that could start
 * a run the chat screen cannot see or stop.
 */
internal fun FloatConsoleService.sendFromPanel() {
    val text = steerInput.text.toString().trim()
    if (text.isEmpty()) return
    val handle = Core.engine.newestRun()
    if (handle != null) {
        // The line needs an id of its own: the engine's queue is keyed by it, and the console
        // has no way to retract or promote what it says, so nothing else ever reads this one.
        val queued = Core.engine.steer(handle.chatId, UUID.randomUUID().toString(), text)
        if (queued) {
            steerInput.setText("")
            showStats("已插话，模型下一轮就能看到")
            hideKeyboard()
        } else {
            showStats("这句话没有发出：任务刚好结束了")
        }
        return
    }
    steerInput.setText("")
    hideKeyboard()
    showStats("已发送，正在等回答…")
    val chatId = Core.chats.conversations.value.firstOrNull()?.id
    // On the app's own scope, not the service's: the answer outlives the panel being collapsed
    // or the console being taken down, and the run has to keep writing its transcript.
    Core.appScope.launch {
        // A run that fails throws, and this scope has no handler of its own: unguarded, one
        // refused endpoint would take the process down with the question already saved.
        runCatching { startRunFor(chatId, text) }.onFailure { error ->
            // 失败必须说出来：输入框已经被清空，用户只能靠这一行知道消息没发出去。
            val reason = error.message ?: error::class.java.simpleName
            scope.launch { showStats("发送失败：$reason") }
        }
    }
}

/**
 * Starts the conversation's next turn and saves what it produced.
 *
 * The shape is the reminder path's, for the same reason: with no chat screen collecting the
 * run's events, the messages the engine emits on its way out are the only copy of the answer
 * there is — so they are gathered here and merged with what the run returned.
 */
internal suspend fun FloatConsoleService.startRunFor(chatId: String?, text: String) {
    val conversation = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        ?: Core.chats.create(text.take(18))
    val title = conversation.title.ifBlank { text.take(18) }
    val question = StoredMessage(id = UUID.randomUUID().toString(), role = Roles.USER, text = text)
    // **历史要自己读**（#30）：`conversations` 只带 meta，`conversation.messages` 是空的。
    // 悬浮窗发起的这一轮同样要把完整历史交给引擎，否则模型只能看到这一句问题。
    val full = if (conversation.messages.isEmpty()) {
        Core.chats.readAll(conversation.id)
    } else {
        conversation.messages
    }
    val history = full + question
    // The question is saved before the run: a run that is refused, stopped or killed still
    // leaves the user's own line in the transcript to look at.
    Core.chats.save(conversation.copy(title = title, messages = history))
    val emitted = mutableListOf<StoredMessage>()
    try {
        val produced = Core.engine.startRun(
            chatId = conversation.id,
            title = title,
            history = history,
            settings = Core.settings.current().withConversationApproval(conversation.approvalMode),
            registry = Core.tools,
            capabilities = Core.capabilities,
            skills = Core.skills,
            memory = Core.memory,
            profile = Core.profile,
            gate = Core.gate,
            onEvent = { event ->
                when (event) {
                    is AgentEvent.AssistantMessage -> emitted += event.message
                    is AgentEvent.ToolMessage -> emitted += event.message
                    else -> Unit
                }
            },
        )
        // Invariant: `emitted` is `produced` in the same order, plus what the engine emits
        // while ending a stopped run. A short `emitted` falls back to `produced` rather than
        // saving a transcript with the wrong messages in it.
        val transcript = if (emitted.size >= produced.size) {
            produced + emitted.drop(produced.size)
        } else {
            produced
        }
        withContext(NonCancellable) {
            Core.chats.save(conversation.copy(title = title, messages = history + transcript))
        }
    } catch (cancellation: CancellationException) {
        // Stopping the run cancels this coroutine, and a cancelled coroutine refuses to
        // suspend: the save has to run under NonCancellable or the answer the user waited for
        // would be lost with the process still holding it. Rethrown, because swallowing it
        // here would turn a stop into a normal ending.
        withContext(NonCancellable) {
            Core.chats.save(conversation.copy(title = title, messages = history + emitted))
        }
        throw cancellation
    }
}

/** One line for the panel's headline. */
private fun RunState.title(): String = when (this) {
    is RunState.Idle -> "空闲"
    is RunState.Running -> "正在执行"
    is RunState.Done -> "已完成"
    is RunState.Cancelled -> "已停止"
    is RunState.Failed -> "失败"
}

/**
 * The second line: what went wrong, or a short idle note.
 *
 * 悬浮窗**不再刷 token/tps 统计**（用户：「悬浮窗不用显示这些，最主要的是显示内容给用户」）。
 * 那些数字属于聊天页的统计卡与圆环；悬浮窗这一行只保留真正有用的话——失败原因、中断、空闲提示。
 * Running/Done 时返回空串，[statsText] 随之隐藏，把空间让给 liveText（正在生成的内容）。
 */
private fun RunState.detail(): String = when (this) {
    is RunState.Idle -> "没有进行中的任务"
    is RunState.Running -> ""
    is RunState.Done -> ""
    is RunState.Cancelled -> "已中断"
    is RunState.Failed -> message
}

package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.agent.RunState
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.forAssistant
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.service.AgentForegroundService
import com.suixing.agent.ui.settings.withUpstreamDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the user has typed but not sent, per conversation. A single global field is
 * what carried half a sentence from one conversation into the next when the drawer
 * switched; keyed by chat id, each conversation restores its own on the way back.
 */
data class Draft(
    val text: String = "",
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
)

/**
 * A line the user queued while a run was going, before the model has read it.
 *
 * It is not a message yet: nothing reaches the transcript until the engine reports the
 * line was handed over ([AgentEvent.Steered]), and until then the user can still promote,
 * edit or delete it. The queue is deliberately never written to disk — it is the state of
 * a run that is still going, and a restarted process has no such run.
 */
data class PendingSteer(val id: String, val text: String)

data class ChatUiState(
    val conversationId: String = "",
    val title: String = "新会话",
    /**
     * True until the conversation to show is known.
     *
     * The screen draws a bare skeleton while this holds, because an empty state on the way
     * to the real conversation reads as "it opened a new chat first and then jumped" — which
     * is exactly what the user saw on every cold start.
     */
    val loading: Boolean = true,
    val messages: List<StoredMessage> = emptyList(),
    /** Which run of each turn is on screen; see [Transcript]. */
    val activeRuns: Map<String, String> = emptyMap(),
    /** The persona this conversation speaks as. */
    val assistantId: String = "",
    /**
     * This conversation's own model pick, or null while it follows the persona and then
     * the app-wide default. The screen draws its tick from the same [AppSettings.choiceFor]
     * chain the run sends with, so what the picker shows is what goes on the wire.
     */
    val providerId: String? = null,
    val model: String? = null,
    /**
     * This conversation's own thinking level, or null while it follows the persona's
     * provider and then the app-wide default. Kept beside the model pick because the two
     * are chosen in the same sheet and belong to the same chat.
     */
    val reasoningEffort: String? = null,
    val streaming: String = "",
    /** The thinking streamed ahead of [streaming], shown in its own block. */
    val reasoning: String = "",
    /**
     * Whether the conversation on screen has a run in flight, straight off the
     * engine's per-chat state. Runs in other conversations are none of this
     * screen's business: nothing here locks, refuses or narrates them.
     */
    val runningHere: Boolean = false,
    val status: String = "",
    /** The tool the run is inside right now; the tool card names it live. */
    val runningTool: String? = null,
    /**
     * Speed of the run that is streaming, straight off the engine's `RunState`. Only the
     * live bubble reads it; once the reply is stored, its own usage numbers are the
     * frozen record and this is ignored.
     */
    val stats: StreamStats? = null,
    /** The live bubble's pieces; empty unless this conversation owns the running run. */
    val liveParts: List<LivePart> = emptyList(),
    /**
     * The run whose rounds are streaming. Its stored rounds are hidden from the
     * transcript while it runs — the live bubble already draws them, and showing both is
     * what split one reply into two bubbles that later merged.
     */
    val liveRunId: String = "",
    val error: String? = null,
    /** The unsent text of the conversation on screen; see [Draft]. */
    val draft: Draft = Draft(),
    /** Lines queued for the run that is going, oldest first; see [PendingSteer]. */
    val pendingSteers: List<PendingSteer> = emptyList(),
    /**
     * This conversation's compression record, oldest first.
     *
     * Carried with the conversation rather than looked up per run: the engine reuses a
     * summary it has already paid for, and a list rebuilt from scratch would fold — and
     * charge for — the same messages again on every turn.
     */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
) {
    /** The transcript as drawn: one run per turn, newest first pick wins. */
    val visible: List<StoredMessage>
        get() = Transcript.active(messages, activeRuns)
            .filterNot { liveRunId.isNotEmpty() && it.runId == liveRunId }
}

/**
 * The conversation a run is writing into.
 *
 * Kept apart from [ChatUiState] because those answer two different questions: the screen
 * shows whichever conversation the user last opened, while the run keeps streaming into
 * the one that started it. Fusing the two is what used to lock the drawer for the whole
 * length of a run — the run's own conversation was the only one the UI could hold.
 */
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

/** One piece of the reply being streamed, in the order the engine produced it. */
sealed interface LivePart {
    /**
     * [tokens] and [elapsedMs] belong to this block alone: they are frozen when the run
     * moves on to a tool or to prose, so two thinking blocks in one run never show the
     * same clock — sharing the run-level tally is what made every block read 23.3s.
     */
    data class Thinking(val text: String, val tokens: Int = 0, val elapsedMs: Long = 0) : LivePart
    data class Tools(val calls: List<LiveTool>) : LivePart
    data class Text(val text: String) : LivePart

    /**
     * A line the user handed over while this run was going.
     *
     * The run's own stored rounds are hidden from the transcript for as long as it streams,
     * because this bubble already draws them — and a steer stored as a message folds into
     * exactly the row that is hidden. Without its own live part the line the user just typed
     * had nowhere to appear until the run ended.
     */
    data class Steer(val message: StoredMessage) : LivePart
}
private data class RunSession(
    val chatId: String,
    val title: String,
    val assistantId: String,
    /** The conversation's own model pick; carried so a save cannot drop it. */
    val providerId: String? = null,
    val model: String? = null,
    /** The conversation's own thinking level; carried so a save cannot drop it. */
    val reasoningEffort: String? = null,
    /** The conversation's compression record; carried so a save cannot drop it. */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
    val runId: String,
    val messages: List<StoredMessage>,
    val activeRuns: Map<String, String>,
    val streaming: String = "",
    val reasoning: String = "",
    val status: String = "",
    val runningTool: String? = null,
    /** When the still-open thinking block started, so its clock is its own. */
    val thinkingStartedAt: Long = 0L,
    /** What the live bubble draws, in arrival order. */
    val parts: List<LivePart> = emptyList(),
    val error: String? = null,
)

/**
 * The conversation's own tally, for the drawer's status line.
 *
 * Aggregated from the stored usage rows rather than from the engine: the numbers have to
 * survive the run that produced them, and a reopened conversation has no engine state.
 */
data class SessionStats(
    val turns: Int,
    val steps: Int,
    val tokens: Int,
    val cachePercent: Int,
    val perSecond: Double,
    val contextTokens: Int,
    val contextWindow: Int,
) {
    companion object {
        fun of(messages: List<StoredMessage>, contextWindow: Int): SessionStats? {
            val turns = messages.count { it.role == Roles.USER && !it.steer }
            val steps = messages.count { it.role == Roles.TOOL }
            val prompt = messages.sumOf { it.promptTokens }
            val completion = messages.sumOf { it.completionTokens }
            val cached = messages.sumOf { it.cachedTokens }
            val elapsed = messages.sumOf { it.elapsedMs }
            val tokens = prompt + completion
            // Nothing to say about an empty conversation, or one whose endpoint never
            // reported usage.
            if (tokens == 0 && steps == 0) return null
            val newest = messages.lastOrNull { it.usageKnown }
            return SessionStats(
                turns = turns,
                steps = steps,
                tokens = tokens,
                cachePercent = if (prompt > 0) cached * 100 / prompt else 0,
                perSecond = if (elapsed > 0) completion * 1000.0 / elapsed else 0.0,
                contextTokens = newest?.promptTokens ?: 0,
                contextWindow = contextWindow,
            )
        }
    }
}
class ChatViewModel : ViewModel() {

    // The process-wide instance, not a private one: the floating console has to be
    // able to stop and steer the very run this screen started.
    private val engine = Core.engine
    private val gate = Core.gate
    /** Only used to ask an endpoint what windows its models advertise; see [prefetchWindows]. */
    private val llm = LlmClient()
    /** Endpoints already asked in this process, so opening the sheet twice costs one call. */
    private val windowsAsked = mutableSetOf<String>()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    /**
     * The on-screen conversation's tally for the drawer. distinctUntilChanged matters: the
     * state itself changes on every token, while these numbers change once a reply lands.
     */
    val sessionStats: Flow<SessionStats?> = combine(state, Core.settings.flow) { snapshot, settings ->
        // The window of the model this conversation would send with, not the app-wide
        // default's: a chat that picked a smaller-window endpoint must not read its own
        // usage against the wrong denominator.
        val assistant = settings.assistant(snapshot.assistantId) ?: settings.activeAssistant()
        val model = settings.choiceFor(snapshot.providerId, snapshot.model, assistant).model
        SessionStats.of(snapshot.messages, settings.contextWindowFor(model))
    }.distinctUntilChanged()

    /** The per-conversation run-state watcher; replaced whenever the display switches. */
    private var stateWatcher: Job? = null

    /**
     * The conversation whose reply the speaker is reading out, or null once it was
     * stopped. The speaker is a single app-wide queue, so the stop button has to know
     * whether the voice it would cut belongs to the chat it is drawn in.
     */
    private var speakingChatId: String? = null

    /**
     * The last failure per conversation, for the runs that ended while another chat was on
     * screen. The run's copy is gone by then and nothing else remembers why it stopped, so
     * the reason is held here until that conversation is opened again.
     */
    private val lastErrors = mutableMapOf<String, String>()

    /**
     * The conversation this process last showed, remembered so a new screen instance can adopt
     * it before its first frame instead of waiting for the marker file to be read.
     */
    private var lastOpenedId: String? = null

    /**
     * Text waiting to be published, per conversation; see [handleEvent] and [DELTA_PUBLISH_MS].
     *
     * Two buffers, not one: the answer and the reasoning land in different parts of the live
     * bubble, and a single buffer would put half a thought into the answer.
     */
    private val textBuffers = mutableMapOf<String, StringBuilder>()
    private val reasoningBuffers = mutableMapOf<String, StringBuilder>()
    private val lastPublishAt = mutableMapOf<String, Long>()

    /**
     * Queued lines per conversation, oldest first.
     *
     * Kept here rather than only in the on-screen state so a queue survives a look at
     * another chat: the run it belongs to keeps going while the user is away, and coming
     * back must still show what is waiting — and still offer to retract it.
     */
    private val steers = mutableMapOf<String, List<PendingSteer>>()

    /**
     * One live copy per running conversation, keyed by chat id. A single slot is what
     * let two parallel runs pour into one bubble: the later start overwrote the
     * earlier run's copy, and the earlier run's events then landed in the later one's
     * transcript. The engine keys its handles the same way; this map is the view
     * model's half of that contract.
     *
     * Concurrent on purpose: stream events reach [handleEvent] on the OkHttp reader
     * thread while the main thread removes and reads the same entry.
     */
    private val sessions = ConcurrentHashMap<String, RunSession>()

    /** One coroutine per running conversation, so stopping one leaves the others alone. */
    private val jobs = mutableMapOf<String, Job>()

    private val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())

    /**
     * The decoded attachment per conversation. The draft keeps only its uri and name
     * (what a restore can mean); the decoded bitmap lives here for the session, so a
     * chip keeps its thumbnail across a drawer switch without re-decoding.
     */
    private val attachmentCache = mutableMapOf<String, Attachment>()

    fun cacheAttachment(chatId: String, attachment: Attachment?) {
        if (attachment == null) attachmentCache.remove(chatId) else attachmentCache[chatId] = attachment
    }

    fun attachmentFor(chatId: String): Attachment? = attachmentCache[chatId]

    /** Writes [draft] as the conversation's unsent text; the display follows at once. */
    fun onDraftChange(chatId: String, draft: Draft) {
        _drafts.value = _drafts.value + (chatId to draft)
    }

    /** Sending empties the conversation's draft; nothing else touches it. */
    fun clearDraft(chatId: String) {
        _drafts.value = _drafts.value + (chatId to Draft())
        attachmentCache.remove(chatId)
    }

    init {
        // Adopted before the first frame when this process already knows which conversation
        // the user was in — that is the case where nothing needs reading, so nothing may
        // flash. With no memory of it the coroutine below decides, and until then the screen
        // shows the skeleton rather than a conversation the user is not in. The persona is
        // left blank on purpose: an empty id means "follow the app-wide default", which is
        // exactly what a fresh state wants, and resolving it needs a suspend call this
        // constructor cannot make.
        lastOpenedId?.let { id ->
            Core.chats.conversations.value.firstOrNull { it.id == id }?.let { cached ->
                _state.value = cached.toUiState("").copy(loading = false)
            }
        }
        // The engine keys its runs by conversation, so this screen subscribes to the
        // state of whichever conversation is on display: a run elsewhere neither
        // locks this screen nor narrates itself into its status line.
        viewModelScope.launch {
            state.map { it.conversationId }.distinctUntilChanged().collect { chatId ->
                // One watcher per displayed conversation: switching cancels the old
                // subscription before the new one starts, so two chats never feed one
                // status line.
                stateWatcher?.cancel()
                if (chatId.isEmpty()) return@collect
                stateWatcher = viewModelScope.launch {
                    Core.engine.stateOf(chatId).collect { run ->
                    when (run) {
                        is RunState.Running -> _state.update {
                            it.copy(runningHere = true, stats = run.stats)
                        }

                        // Back to typing the moment the run is stopped, without waiting
                        // for the stream to wind down on its own. What already arrived
                        // is saved first: the run is over either way, and only this
                        // copy has it.
                        is RunState.Cancelled -> {
                            // Cancelled is terminal and the engine never follows it with
                            // Idle, so the screen has to be released here — otherwise the
                            // composer keeps its stop button and the conversation stays
                            // locked until the user switches away and back.
                            _state.update { it.copy(runningHere = false) }
                            sessions[chatId]?.let { live ->
                                viewModelScope.launch { saveSession(live) }
                                endSession(chatId, "已停止")
                            }
                        }

                        is RunState.Idle -> _state.update {
                            it.copy(runningHere = false, stats = null)
                        }

                        else -> _state.update { it.copy(runningHere = false) }
                    }
                }
                }
            }
        }
        viewModelScope.launch {
            val settings = Core.settings.current()
            val assistantId = settings.activeAssistant()?.id.orEmpty()
            // Only the cold path reads the files: when the conversations are already in
            // memory the state above is the real one, and loading them again would only
            // make the screen wait for something it already has.
            if (Core.chats.conversations.value.isEmpty()) Core.chats.load()
            val conversations = Core.chats.conversations.value
            // The conversation the user was last in — and only if it is still there: the
            // marker can outlive its conversation (deleted from another entry point), and
            // opening a chat that no longer exists would leave the screen on nothing. The
            // in-memory id is preferred because it is what this process last showed; the
            // file is the cold-start answer.
            val reopened = (lastOpenedId ?: Core.chats.readLastOpened())?.let { id ->
                conversations.firstOrNull { it.id == id }
            }
            val existing = reopened ?: conversations.firstOrNull()
            if (existing == null) {
                val created = Core.chats.create()
                _state.value = ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = assistantId,
                    loading = false,
                )
            } else {
                _state.value = existing.toUiState(assistantId).copy(loading = false)
            }
            // Recorded here as well as on a switch: a session that only ever read one
            // conversation never wrote the marker, and the next launch would fall back to
            // "most recently written" — which is not where the user was.
            rememberOpened(_state.value.conversationId)
            // Same recovery on a cold start: the run that was streaming when the process
            // died has no session any more, only its draft.
            mergeLiveDraft(_state.value.conversationId)
        }
        // The draft follows the displayed conversation: switching restores that
        // conversation's own unsent text without any action at the switch site.
        viewModelScope.launch {
            combine(state.map { it.conversationId }, _drafts) { id, all -> id to (all[id] ?: Draft()) }
                .distinctUntilChanged()
                .collect { (id, draft) ->
                    if (_state.value.conversationId == id && _state.value.draft != draft) {
                        _state.update { it.copy(draft = draft) }
                    }
                }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val settings = Core.settings.current()
            val created = Core.chats.create()
            _state.value = ChatUiState(
                conversationId = created.id,
                title = created.title,
                assistantId = settings.activeAssistant()?.id.orEmpty(),
                loading = false,
            )
            // A brand-new conversation is where the user is now: without this the next
            // launch would reopen whatever was written last, not what they left open.
            rememberOpened(created.id)
        }
    }

    /**
     * Remembers the conversation the user is in, for the next launch.
     *
     * A file of its own rather than a timestamp on the conversation: a transcript can be
     * megabytes, and rewriting one just to say "this is where I was" would be absurd — which
     * is why "touch the conversation so it sorts first" was rejected. Fire and forget: the
     * switch must not wait for a disk write.
     */
    private fun rememberOpened(chatId: String) {
        if (chatId.isEmpty()) return
        lastOpenedId = chatId
        viewModelScope.launch { Core.chats.saveLastOpened(chatId) }
    }

    fun openConversation(conversation: Conversation) {
        // Anything the conversation on screen was still holding is published before the
        // display moves: a buffered tail would otherwise land in the conversation being
        // opened, or be lost with the one being left.
        flushDeltas(_state.value.conversationId)
        val live = sessions[conversation.id]
        // Read-and-clear: a failure that landed while another conversation was on screen
        // is reported the moment its own chat comes back.
        val failure = lastErrors.remove(conversation.id)
        _state.value = if (live != null) {
            // Coming back to the conversation a run owns: pick up what it has streamed
            // since, rather than the older snapshot on disk.
            ChatUiState(
                conversationId = live.chatId,
                title = live.title,
                messages = live.messages,
                activeRuns = live.activeRuns,
                assistantId = live.assistantId,
                providerId = live.providerId,
                model = live.model,
                reasoningEffort = live.reasoningEffort,
                checkpoints = live.checkpoints,
                streaming = live.streaming,
                reasoning = live.reasoning,
                status = live.status,
                runningHere = true,
                // A conversation the screen already has is not "still opening".
                loading = false,
                // The tool it is inside right now: without it the row that says what the
                // run is doing went blank on every switch back.
                runningTool = live.runningTool,
                liveParts = live.parts,
                liveRunId = live.runId,
                error = failure,
                // The queue belongs to the run, not to the screen: coming back to it must
                // still show what is waiting.
                pendingSteers = steers[conversation.id].orEmpty(),
            )
        } else {
            // A conversation that never picked a persona keeps the one already on screen,
            // which was seeded from the settings when this screen was created.
            conversation.toUiState(_state.value.assistantId).let {
                it.copy(
                    error = failure,
                    // A run that ended with lines still queued keeps them: they are the
                    // user's words, and the rows say they never went out.
                    pendingSteers = steers[conversation.id].orEmpty(),
                )
            }
        }
        // A run that was killed left its half-written answer in a draft file: it belongs to
        // this conversation, so it is picked up the moment the conversation is.
        viewModelScope.launch { mergeLiveDraft(conversation.id) }
        // Where the user is, for the next launch.
        rememberOpened(conversation.id)
    }

    /**
     * Remembers the model this conversation picked — and only this conversation's copy of
     * it. The choice belongs to the chat it was made in, so two conversations can run
     * different endpoints side by side while the app-wide default stays the settings
     * page's to own.
     */
    fun chooseModel(providerId: String, model: String) {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return
        val shown = _state.value
        _state.update { it.copy(providerId = providerId, model = model) }
        // The run's own copy carries the pick as well, or the next save would write the
        // conversation back without it and the run after that would fall back to the
        // persona.
        val live = sessions[chatId]
        live?.let { putSession(it.copy(providerId = providerId, model = model)) }
        viewModelScope.launch {
            // The one save path, so the pick rides along with everything else: it keeps
            // createdAt, derives the title and falls back to what is already on disk.
            val title = save(
                chatId = chatId,
                title = live?.title ?: shown.title,
                messages = live?.messages ?: shown.messages,
                assistantId = live?.assistantId ?: shown.assistantId,
                activeRuns = live?.activeRuns ?: shown.activeRuns,
                providerId = providerId,
                model = model,
            ) ?: return@launch
            if (title != shown.title) {
                _state.update { it.copy(title = title) }
                sessions[chatId]?.let { current -> putSession(current.copy(title = title)) }
            }
        }
    }

    /**
     * Remembers the thinking level this conversation picked — and only this conversation's
     * copy of it. Same rule as [chooseModel]: the level decides how long every answer in
     * this chat takes and what it costs, so it belongs to the chat it was chosen in rather
     * than to the app-wide default every other chat also reads.
     */
    fun chooseReasoning(level: String) {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return
        val shown = _state.value
        _state.update { it.copy(reasoningEffort = level) }
        val live = sessions[chatId]
        live?.let { putSession(it.copy(reasoningEffort = level)) }
        viewModelScope.launch {
            save(
                chatId = chatId,
                title = live?.title ?: shown.title,
                messages = live?.messages ?: shown.messages,
                assistantId = live?.assistantId ?: shown.assistantId,
                activeRuns = live?.activeRuns ?: shown.activeRuns,
                providerId = live?.providerId ?: shown.providerId,
                model = live?.model ?: shown.model,
                reasoningEffort = level,
            )
        }
    }

    /**
     * Fills in the context windows the endpoint advertises for its models.
     *
     * Called when the model sheet opens — the moment the user is looking at models — rather
     * than at app start, so no request goes out for someone who never opens the picker, and
     * at most one per endpoint per process.
     *
     * Values the user set by hand are never touched: [withUpstreamDefaults] only fills gaps,
     * which is what keeps 「I raised this window myself」 from being undone by a refresh. A
     * value already raised stays raised.
     */
    fun prefetchWindows() {
        viewModelScope.launch {
            val settings = Core.settings.current()
            // Every endpoint, not just the one this conversation happens to use: the ring and
            // the settings page both read windows by model id, and a provider the user has not
            // switched to yet would otherwise stay 「unlimited」 forever — which is exactly the
            // complaint about a newly added provider. Still at most one probe per endpoint per
            // process, and still only when the picker is opened.
            val gathered = mutableMapOf<String, Int>()
            for (provider in settings.providers) {
                if (provider.baseUrl.isBlank()) continue
                val key = provider.baseUrl.trimEnd('/') + "|" + provider.apiKey
                if (!windowsAsked.add(key)) continue
                val endpoint = settings.copy(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    // /models does not care which model the endpoint copy names; give it one
                    // the provider actually knows so the request shape stays valid.
                    model = provider.enabled.firstOrNull() ?: provider.models.firstOrNull().orEmpty(),
                )
                runCatching { llm.listModelDetails(endpoint) }
                    .onSuccess { details ->
                        for (model in details) {
                            model.contextLength?.let { gathered[model.id] = it }
                        }
                    }
                    // A failed probe is not worth a word on screen: the window simply stays as
                    // it was, and the settings page can be asked to fetch again.
                    .onFailure { windowsAsked.remove(key) }
            }
            if (gathered.isEmpty()) return@launch
            Core.settings.update { stored ->
                stored.copy(modelContexts = stored.modelContexts.withUpstreamDefaults(gathered))
            }
        }
    }

    fun deleteConversation(conversation: Conversation) {
        // Published before the conversation goes: a buffered tail is part of an answer that
        // may still be streaming into a chat that is about to disappear from the list.
        flushDeltas(conversation.id)
        // The run owns its conversation until it ends: deleting it would only have the
        // next save write it straight back.
        if (sessions.containsKey(conversation.id)) return
        // Nothing left to hand a queued line to.
        steers.remove(conversation.id)
        viewModelScope.launch {
            Core.chats.delete(conversation.id)
            if (conversation.id != _state.value.conversationId) return@launch
            val settings = Core.settings.current()
            val next = Core.chats.conversations.value.firstOrNull()
            _state.value = if (next == null) {
                val created = Core.chats.create()
                ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = settings.activeAssistant()?.id.orEmpty(),
                    loading = false,
                )
            } else {
                next.toUiState(settings.activeAssistant()?.id.orEmpty())
            }
            // The deleted conversation may have been the marker's; the one now on screen is
            // where the user is.
            rememberOpened(_state.value.conversationId)
        }
    }

    // ---------------------------------------------------------------- assistants

    /**
     * Makes [assistant] the face of this conversation — this one only. The app-wide
     * default is a separate decision ([setDefaultAssistant]): a persona tried out in one
     * chat must not silently become what every other chat starts as.
     */
    fun useAssistant(assistant: Assistant) {
        editDisplayed { it.copy(assistantId = assistant.id) }
        viewModelScope.launch { persistDisplayed() }
    }

    /** Makes [assistant] what new conversations start as, leaving this one as it is. */
    fun setDefaultAssistant(assistant: Assistant) {
        viewModelScope.launch {
            Core.settings.update { it.copy(activeAssistantId = assistant.id) }
        }
    }

    fun saveAssistant(assistant: Assistant) {
        viewModelScope.launch {
            // Whether this was a new persona or an edit decides what else may move: saving
            // a brand-new one is an explicit "use this", editing one is not.
            var created = false
            Core.settings.update { settings ->
                val exists = settings.assistants.any { it.id == assistant.id }
                created = !exists
                val list = if (exists) {
                    settings.assistants.map { if (it.id == assistant.id) assistant else it }
                } else {
                    settings.assistants + assistant
                }
                settings.copy(
                    assistants = list,
                    // A brand-new persona becomes the default — that is what saving it
                    // means the first time. Editing an existing one must not move the
                    // default out from under every other conversation.
                    activeAssistantId = if (exists) settings.activeAssistantId else assistant.id,
                )
            }
            // An edit is not a switch: renaming a persona must not quietly change what the
            // conversation on screen is speaking as. Switching is [useAssistant].
            if (created) {
                editDisplayed { it.copy(assistantId = assistant.id) }
                persistDisplayed()
            }
        }
    }

    fun deleteAssistant(assistant: Assistant) {
        viewModelScope.launch {
            Core.settings.update { settings ->
                // The app always needs one persona to fall back on.
                if (settings.assistants.size <= 1) return@update settings
                val list = settings.assistants.filterNot { it.id == assistant.id }
                settings.copy(
                    assistants = list,
                    activeAssistantId = if (settings.activeAssistantId == assistant.id) {
                        list.first().id
                    } else {
                        settings.activeAssistantId
                    },
                )
            }
            val settings = Core.settings.current()
            val next = settings.assistant(_state.value.assistantId) ?: settings.activeAssistant()
            editDisplayed { it.copy(assistantId = next?.id.orEmpty()) }
            persistDisplayed()
        }
    }

    // -------------------------------------------------------------------- turns

    /** Returns false when the message was dropped, so the caller keeps the input text. */
    fun send(text: String, imageBase64: String? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty() && imageBase64 == null) return false
        if (_state.value.conversationId.isEmpty()) return false
        // No global lock: the engine refuses a second run in the same conversation
        // (two runs would interleave into one transcript), and every other
        // conversation is free to start its own run right now.
        if (Core.engine.isRunning(_state.value.conversationId)) return false

        val user = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = body,
            imageBase64 = imageBase64,
        )
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = it.messages + user,
                activeRuns = it.activeRuns + (user.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在思考中",
                error = null,
            )
        }
        start(fresh, _state.value.messages)
        clearDraft(_state.value.conversationId)
        return true
    }

    /** Answers the last question again, keeping the old reply as a branch. */
    fun regenerate() {
        if (Core.engine.isRunning(_state.value.conversationId)) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val question = active.lastOrNull { it.role == Roles.USER } ?: return
        val prompt = Transcript.withoutLastAnswer(active)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                activeRuns = it.activeRuns + (question.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, prompt)
    }

    /** Draws another candidate answer for [turnId]; the transcript itself is untouched. */
    fun switchBranch(turnId: String, runId: String) {
        if (_state.value.activeRuns[turnId] == runId) return
        editDisplayed { it.copy(activeRuns = it.activeRuns + (turnId to runId)) }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites a message in place without asking the model anything. This is how a
     * typo gets fixed, or a long reply gets trimmed down to the part worth keeping.
     */
    fun editMessage(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        editDisplayed { snapshot ->
            val index = snapshot.messages.indexOfFirst { it.id == id }
            snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == id) message.copy(text = body, edited = true) else message
                },
                // A summary that covers the edited message no longer describes what the model
                // will read, and a stale one would quietly hand it the text from before the
                // fix — exactly the fact the user came back to correct. Only the records that
                // end before the edit are still accurate.
                checkpoints = if (index < 0) {
                    snapshot.checkpoints
                } else {
                    snapshot.checkpoints.filterNot { checkpoint ->
                        snapshot.messages.indexOfFirst { it.id == checkpoint.toMessageId } >= index
                    }
                },
            )
        }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites the user message [id] and asks again from there. Everything the model
     * said afterwards is dropped: those answers belong to a question that no longer
     * exists, so keeping them would hang them off the wrong prompt.
     */
    fun editAndResend(id: String, text: String) {
        if (Core.engine.isRunning(_state.value.conversationId)) return
        val body = text.trim()
        if (body.isEmpty()) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val index = active.indexOfFirst { it.id == id }
        if (index < 0) return
        val kept = active.take(index) + active[index].copy(text = body, edited = true)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = kept,
                // Runs whose messages just disappeared would only leak memory.
                activeRuns = it.activeRuns.filterKeys { key -> kept.any { row -> row.id == key } } + (id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, kept)
    }

    fun stop() {
        // Only this conversation's reading: the speaker is one app-wide queue, so an
        // unconditional stop here used to cut short a reply another chat was reading out.
        val chatId = _state.value.conversationId
        if (speakingChatId == chatId) {
            Core.speaker.stop()
            speakingChatId = null
        }
        // This conversation's run, by id: the engine keeps one handle per chat, and
        // stopping whatever happens to be newest would cut a run this screen does
        // not even show. Its approval prompt, if any, is answered "refused" for this
        // run only — another conversation's prompt stays on screen.
        gate.cancelPending(chatId)
        Core.engine.cancel(chatId)
        jobs.remove(chatId)?.cancel()
    }

    /**
     * Queues a line for the run that is going instead of waiting for it to end.
     *
     * The line is not written into the transcript here: it waits above the composer, and
     * becomes a message only when the engine says it was handed over — which is what lets
     * the user promote, edit or delete it in the meantime. False means nothing was
     * running, so the caller keeps the text.
     */
    fun steer(text: String): Boolean {
        val body = text.trim()
        val chatId = _state.value.conversationId
        if (body.isEmpty()) return false
        val id = UUID.randomUUID().toString().take(8)
        // 排队 or 立即 comes from the run's own settings — the engine reads steerMode off
        // the live run — so this stays a plain call whose boolean the composer can await.
        if (!Core.engine.steer(chatId, id, body)) return false
        steers[chatId] = steers[chatId].orEmpty() + PendingSteer(id, body)
        publishSteers(chatId)
        clearDraft(chatId)
        return true
    }

    /** Sends a queued line now, cutting the round in flight so the model sees it at once. */
    fun promoteSteer(id: String) {
        val chatId = _state.value.conversationId
        val queued = steers[chatId].orEmpty().firstOrNull { it.id == id } ?: return
        if (Core.engine.promote(chatId, id)) return
        // No round left to interrupt: the line goes out as an ordinary message instead of
        // being silently dropped — but only because the user asked for it now.
        retractSteer(id)
        send(queued.text)
    }

    /** Drops a queued line before the model ever reads it. */
    fun retractSteer(id: String) {
        val chatId = _state.value.conversationId
        Core.engine.retract(chatId, id)
        steers[chatId] = steers[chatId].orEmpty().filterNot { it.id == id }
        publishSteers(chatId)
    }

    /** Mirrors one conversation's queue onto the screen, when that is the one on screen. */
    private fun publishSteers(chatId: String) {
        if (_state.value.conversationId != chatId) return
        _state.update { it.copy(pendingSteers = steers[chatId].orEmpty()) }
    }

    /**
     * Asks the run on screen to fold the conversation at its next turn boundary.
     *
     * Only a flag: the engine consumes it between rounds, so nothing in flight is cut and
     * the answer being written right now still lands. False means no run is going, which is
     * also when compressing would have nothing to do.
     */
    fun requestCompress(): Boolean = Core.engine.requestCompress(_state.value.conversationId)

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun start(runId: String, history: List<StoredMessage>) {
        val shown = _state.value
        val chatId = shown.conversationId
        // Lines left queued by the run that just ended, taken before this one starts: the
        // queue outlives a run, and a line queued from here on is already on its way to the
        // new run, so handing that one over again would deliver it twice.
        val carried = steers[chatId].orEmpty()
        putSession(
            RunSession(
                chatId = chatId,
                title = shown.title,
                assistantId = shown.assistantId,
                providerId = shown.providerId,
                model = shown.model,
                reasoningEffort = shown.reasoningEffort,
                checkpoints = shown.checkpoints,
                runId = runId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
                status = shown.status,
            ),
        )
        jobs[chatId] = viewModelScope.launch {
            AgentForegroundService.start(Core.appContext, "随行正在执行任务")
            // The draft writer rides inside the run's own coroutine, so it stops the moment
            // the run does — no separate job to cancel, and no writes after the answer has
            // landed in the transcript. It is cancelled by hand in the finally as well: a
            // `while (true)` loop left to itself would tick once a second for the life of
            // the view model, and the cancel lands before the draft is cleared, so no write
            // can slip in after it.
            val writer = launch { writeLiveDraft(chatId) }
            // Lines still queued from the run that ended: the queue outlives a run, so the new
            // one has to be told about them. Waiting for Running is what makes this work — the
            // engine refuses a steer for a conversation it is not running yet, and startRun
            // below does not return until the run is over.
            if (carried.isNotEmpty()) {
                launch {
                    Core.engine.stateOf(chatId).first { it is RunState.Running }
                    // The queue is the truth: a line retracted while the run was starting is
                    // gone from the table, and gone from the UI, so it must not go out here.
                    carried.filter { queued -> steers[chatId]?.any { it.id == queued.id } == true }
                        .forEach { queued ->
                            Core.engine.steer(chatId, queued.id, queued.text, immediate = false)
                        }
                }
            }
            // Decided in the catches, applied once in the finally: there is exactly one
            // place that saves and one that closes the run, whichever way it ended.
            var status = ""
            var failure: String? = null
            try {
                sessions[chatId]?.let { saveSession(it) }
                val settings = Core.settings.current()
                val assistant = settings.assistant(shown.assistantId) ?: settings.activeAssistant()
                // The persona bends the live settings; anything it left blank stays as-is.
                val persona = settings.forAssistant(assistant)
                // The wire comes from the one chain the picker also reads: this
                // conversation's own pick, then the persona's pins, then the app-wide
                // default. Everything else keeps the persona's overrides.
                val choice = settings.choiceFor(shown.providerId, shown.model, assistant)
                val effective = persona.copy(
                    baseUrl = choice.baseUrl,
                    apiKey = choice.apiKey,
                    model = choice.model,
                    // The level follows the same chain as the model: this chat's own pick
                    // wins, then whatever the persona's provider declared, then the
                    // app-wide default the persona already carries.
                    reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
                )
                val registry = Core.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
                val produced = engine.startRun(
                    chatId = chatId,
                    title = shown.title,
                    history = history,
                    settings = effective,
                    registry = registry,
                    capabilities = Core.capabilities,
                    skills = Core.skills,
                    memory = Core.memory,
                    gate = gate,
                    // Captured per run: the engine's events carry no chat id, so the
                    // closure is what keeps two parallel runs out of each other's
                    // transcript.
                    onEvent = { event -> handleEvent(chatId, event) },
                    // The compressions this conversation has already paid for, and the one
                    // this run is about to make: without both, every turn would fold — and
                    // charge for — the same span again.
                    checkpoints = shown.checkpoints,
                    onCheckpoint = { checkpoint -> recordCheckpoint(chatId, checkpoint) },
                )
                // Only the run's own last reply is read out, not the whole history.
                if (effective.speakReplies) {
                    produced.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
                        ?.text
                        ?.let {
                            // Remembered before speaking, because the speaker queues the
                            // line and returns at once: the id has to be set by the time
                            // any other screen can reach the stop button.
                            speakingChatId = chatId
                            Core.speaker.speak(it)
                        }
                }
            } catch (cancellation: CancellationException) {
                // RunState.Cancelled usually released the screen first; this is the belt
                // to that pair of braces.
                status = "已停止"
                throw cancellation
            } catch (error: Throwable) {
                failure = error.message ?: error::class.java.simpleName
            } finally {
                AgentForegroundService.stop(Core.appContext)
                withContext(NonCancellable) {
                    // First of all: the writer must not run again, or a tick landing after
                    // the clear below would resurrect a draft for an answer already saved.
                    writer.cancel()
                    // A cancelled coroutine refuses to suspend, so the final save has to
                    // run in a NonCancellable block or a stopped run loses its messages.
                    // Whatever the endpoint sent since the last publish belongs to the answer
                    // too: without this flush a stopped run would save all but its tail.
                    flushDeltas(chatId)
                    sessions[chatId]?.let { saveSession(it) }
                    endSession(chatId, status, failure)
                    // The answer is in the transcript now, whatever way the run ended: a
                    // draft left behind would come back on the next launch as a second
                    // bubble saying the same thing.
                    Core.chats.clearLive(chatId)
                }
            }
        }
    }

    private fun handleEvent(chatId: String, event: AgentEvent) {
        val live = sessions[chatId] ?: return
        // The model can send hundreds of deltas a second, and every one of them used to
        // republish the state — which re-parses the whole Markdown body, because the render
        // cache is keyed by the text itself and a text that changes every frame is a miss
        // every frame. That is what made the ghosting worse the faster the endpoint wrote.
        // Deltas are buffered and published at most every DELTA_PUBLISH_MS; every other event
        // flushes first, so nothing is ever applied out of order and no tail is lost.
        val textDelta = event is AgentEvent.TextDelta
        val reasoningDelta = event is AgentEvent.ReasoningDelta
        if (textDelta) textBuffers.getOrPut(chatId) { StringBuilder() }.append(event.text)
        if (reasoningDelta) reasoningBuffers.getOrPut(chatId) { StringBuilder() }.append(event.text)
        if (textDelta || reasoningDelta) {
            val now = SystemClock.uptimeMillis()
            if (now - (lastPublishAt[chatId] ?: 0L) < DELTA_PUBLISH_MS) return
            lastPublishAt[chatId] = now
        }
        // Whatever has arrived so far, applied in one go. For a non-delta event this is the
        // flush; for a delta it is the publish that got through the interval above.
        val pendingText = textBuffers.remove(chatId)?.toString().orEmpty()
        val pendingReasoning = reasoningBuffers.remove(chatId)?.toString().orEmpty()
        val base = applyPending(live, pendingText, pendingReasoning)
        val next = when (event) {
            // Already folded into `base` above.
            is AgentEvent.TextDelta -> base

            is AgentEvent.ReasoningDelta -> base

            // The round is stored, but it is not drawn from the transcript: the live
            // bubble keeps drawing it until the run lands, so the reply stays one bubble
            // from the first token to the last.
            is AgentEvent.AssistantMessage -> base.copy(
                messages = base.messages + event.message.copy(runId = base.runId),
                parts = base.parts.sealThinking(base.thinkingStartedAt),
            )

            is AgentEvent.ToolStarted -> base.copy(
                // 「正在调用」 rather than a bare verb: the line is a sentence the user reads
                // while waiting, and 「调用 read_file」 read as a log entry.
                status = "正在调用 ${event.name}",
                runningTool = event.name,
                parts = base.parts.sealThinking(base.thinkingStartedAt).startTool(event.name, event.args),
            )

            is AgentEvent.ToolMessage -> base.copy(
                messages = base.messages + event.message.copy(runId = base.runId),
                status = "",
                runningTool = null,
                // The result hangs off the call it belongs to, not only off the
                // transcript: the row needs its output and its screenshot.
                parts = base.parts.attachToolMessage(event.message),
            )

            is AgentEvent.ToolFinished -> base.copy(
                runningTool = null,
                parts = base.parts.finishTool(event.name, event.ok),
            )

            // The error rides with the run: it shows on the error card only while that
            // conversation is the one on screen, and travels with it otherwise.
            is AgentEvent.Failed -> base.copy(error = event.message)

            is AgentEvent.Finished -> live

            // The model has the line now: it leaves the queue and becomes a message in the
            // same breath, so a queued line is never both. Marked `steer` so the transcript
            // keeps reading it as an interruption rather than as the question being answered.
            //
            // It also becomes a live part, and that is the only way the user sees it while the
            // run is going: this run's stored rounds are hidden from the transcript for as
            // long as it streams (the live bubble draws them), and a stored steer folds into
            // exactly the hidden row. The message still lands, because the run is what saves
            // it — it is just not drawable yet.
            //
            // Off `base`, not `live`: the deltas buffered for this event were already taken
            // out of their buffers a few lines up, so building on `live` would have thrown
            // away whatever text and thinking had just arrived.
            is AgentEvent.Steered -> {
                steers[chatId] = steers[chatId].orEmpty().filterNot { it.id == event.id }
                publishSteers(chatId)
                val line = StoredMessage(
                    id = event.id,
                    role = Roles.USER,
                    text = event.text,
                    steer = true,
                    // This run's id, like every other message it writes. The screen hides the
                    // live run's stored rows while the bubble draws them; a steer without the
                    // id slipped past that filter whenever no bubble was open yet (the model
                    // had stored nothing back) and appeared twice — once settled, once live.
                    // Nothing else keys off it: turns are opened by non-steer user messages
                    // alone, and [Transcript.active] keeps every user message whatever its run.
                    runId = base.runId,
                )
                base.copy(
                    messages = base.messages + line,
                    parts = base.parts.sealThinking(base.thinkingStartedAt) + LivePart.Steer(line),
                )
            }
        }
        putSession(next)
        // Saved straight away: a handed-over line that only lived in memory would vanish the
        // moment the user switches conversations, which is when they go looking for it.
        if (event is AgentEvent.Steered) viewModelScope.launch { saveSession(next) }
    }

    /**
     * Folds the buffered deltas into a session: the answer first, then the reasoning.
     *
     * Both fields are separate buffers on purpose — they feed different parts of the bubble,
     * and merging them would put half a thought into the answer. Applied in one go, so one
     * publish covers everything that arrived since the last one.
     */
    private fun applyPending(live: RunSession, text: String, reasoning: String): RunSession {
        var next = live
        if (text.isNotEmpty()) {
            next = next.copy(
                streaming = next.streaming + text,
                parts = next.parts.sealThinking(next.thinkingStartedAt).appendText(text),
                status = "",
            )
        }
        if (reasoning.isNotEmpty()) {
            // A delta after a tool or after prose opens a new block: its clock starts now,
            // not at the run's start.
            val opening = next.parts.lastOrNull() !is LivePart.Thinking
            next = next.copy(
                reasoning = next.reasoning + reasoning,
                parts = next.parts.appendThinking(reasoning),
                status = "",
                thinkingStartedAt = if (opening) System.currentTimeMillis() else next.thinkingStartedAt,
            )
        }
        return next
    }

    /**
     * Publishes whatever is still buffered for [chatId].
     *
     * Called before anything that reads the session as a whole — saving it, switching away
     * from it — because the buffered text is part of the answer and dropping it here is how a
     * stopped or failed run would lose its last words.
     */
    private fun flushDeltas(chatId: String) {
        val live = sessions[chatId] ?: return
        val text = textBuffers.remove(chatId)?.toString().orEmpty()
        val reasoning = reasoningBuffers.remove(chatId)?.toString().orEmpty()
        if (text.isEmpty() && reasoning.isEmpty()) return
        putSession(applyPending(live, text, reasoning))
    }

    /**
     * Appends a text delta to the piece being written, or opens a new one.
     *
     * A tool call between two stretches of prose starts a second piece: the two stretches
     * are not one paragraph, and merging them would draw the model's "let me check" before
     * a tool and its answer after it as a single run-on line.
     */
    private fun List<LivePart>.appendText(delta: String): List<LivePart> =
        when (val last = lastOrNull()) {
            is LivePart.Text -> dropLast(1) + last.copy(text = last.text + delta)
            else -> this + LivePart.Text(delta)
        }

    private fun List<LivePart>.appendThinking(delta: String): List<LivePart> =
        when (val last = lastOrNull()) {
            is LivePart.Thinking -> dropLast(1) + last.copy(text = last.text + delta)
            else -> this + LivePart.Thinking(delta)
        }

    /**
     * Freezes the still-open thinking block the moment the run moves on: its own text
     * length stands in for its token count and its own clock stops here, so a later
     * block cannot inherit this one's elapsed time.
     */
    private fun List<LivePart>.sealThinking(startedAt: Long): List<LivePart> {
        val last = lastOrNull() as? LivePart.Thinking ?: return this
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        return dropLast(1) + last.copy(tokens = last.text.length / 4, elapsedMs = elapsed)
    }

    /** A call joins the round of tools already open, or starts one. */
    private fun List<LivePart>.startTool(name: String, args: String): List<LivePart> {
        val call = LiveTool(name = name, args = args, startedAt = System.currentTimeMillis())
        return when (val last = lastOrNull()) {
            is LivePart.Tools -> dropLast(1) + last.copy(calls = last.calls + call)
            else -> this + LivePart.Tools(listOf(call))
        }
    }

    /**
     * Marks the oldest still-running call of that name as done.
     *
     * Matched by name, not by index: the engine reports the finish by name, and a run that
     * calls the same tool twice must not have the second result close the first row.
     */
    private fun List<LivePart>.finishTool(name: String, ok: Boolean): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val index = part.calls.indexOfFirst { it.name == name && !it.done }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        val call = calls[index]
        calls[index] = call.copy(
            done = true,
            ok = ok,
            elapsedMs = System.currentTimeMillis() - call.startedAt,
        )
        part.copy(calls = calls)
    }

    /**
     * Hangs a landed TOOL message on the call it reports, matching the way
     * [finishTool] matches: oldest open call of that name. The message carries the
     * output and any screenshot, which only the row that owns it may draw.
     */
    private fun List<LivePart>.attachToolMessage(message: StoredMessage): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val name = message.toolName.orEmpty()
        val index = part.calls.indexOfFirst { it.name == name && it.message == null }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        calls[index] = calls[index].copy(message = message)
        part.copy(calls = calls)
    }
    /**
     * Installs [next] as its conversation's live copy and, when that conversation is
     * the one on screen, copies the live parts of it onto the screen. A run in some
     * other conversation changes nothing here at all — the map keeps it apart, and
     * the display only ever reads its own entry.
     */
    private fun putSession(next: RunSession) {
        sessions[next.chatId] = next
        _state.update { snapshot ->
            if (snapshot.conversationId != next.chatId) {
                snapshot
            } else {
                snapshot.copy(
                    title = next.title,
                    messages = next.messages,
                    activeRuns = next.activeRuns,
                    streaming = next.streaming,
                    reasoning = next.reasoning,
                    status = next.status,
                    runningTool = next.runningTool,
                    liveParts = next.parts,
                    liveRunId = next.runId,
                    error = next.error ?: snapshot.error,
                )
            }
        }
    }

    /**
     * Drops the run's claim on its conversation. The screen keeps whatever the user was
     * looking at: the run's own conversation loses its streaming state, any other one is
     * left exactly as it was.
     */
    private fun endSession(chatId: String, status: String, error: String? = null) {
        val live = sessions.remove(chatId)
        val failure = error ?: live?.error
        // A run that failed while another conversation was on screen has no state to land
        // in — its own copy is gone a line above — so the reason waits here until that
        // conversation is opened again. Otherwise the user comes back to half an answer
        // and no explanation.
        if (failure != null && _state.value.conversationId != chatId) lastErrors[chatId] = failure
        _state.update { snapshot ->
            if (live != null && snapshot.conversationId == chatId) {
                snapshot.copy(
                    streaming = "",
                    reasoning = "",
                    stats = null,
                    status = status,
                    runningTool = null,
                    liveParts = emptyList(),
                    liveRunId = "",
                    error = failure ?: snapshot.error,
                )
            } else {
                snapshot
            }
        }
    }

    /**
     * Applies [change] to the conversation on screen. While that conversation is the one
     * a run owns, the change goes into the run's copy as well — otherwise the next
     * streamed token would mirror the older messages back over the edit.
     */
    private fun editDisplayed(change: (ChatUiState) -> ChatUiState) {
        _state.update(change)
        val shown = _state.value
        val live = sessions[shown.conversationId] ?: return
        putSession(
            live.copy(
                title = shown.title,
                assistantId = shown.assistantId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
                // Carried too: an edit can drop a compression record, and a session still
                // holding the old list would write it back at the end of the run.
                checkpoints = shown.checkpoints,
            ),
        )
    }

    /**
     * Keeps the conversation's draft file in step with the answer being streamed.
     *
     * Once a second, and only when the text actually changed: one write is an encode plus a
     * file write plus a rename, and tokens can arrive every few milliseconds. A second is the
     * interval where a kill costs the user the last tick instead of the whole answer, while a
     * stalled stream costs nothing at all.
     */
    private suspend fun writeLiveDraft(chatId: String) {
        var written = ""
        while (true) {
            delay(LIVE_DRAFT_INTERVAL_MS)
            val live = sessions[chatId] ?: continue
            // Only the answer: the reasoning chain is not what the user comes back for, and
            // writing it would double the file for text the transcript redraws anyway.
            val text = live.streaming
            if (text.isBlank() || text == written) continue
            written = text
            Core.chats.saveLive(
                LiveDraft(
                    chatId = chatId,
                    runId = live.runId,
                    at = System.currentTimeMillis(),
                    text = text,
                ),
            )
        }
    }

    /**
     * Turns the draft a killed run left behind back into a message.
     *
     * Only when the run it belongs to is not already in the transcript: a draft that outlived
     * a clean finish would otherwise return as a second bubble saying the same thing. The
     * order is append, save, then clear — clearing first would lose the text for good if the
     * save never happened.
     */
    private suspend fun mergeLiveDraft(chatId: String) {
        val draft = Core.chats.readLive(chatId) ?: return
        val live = sessions[chatId]
        val shown = _state.value.takeIf { it.conversationId == chatId }
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val messages = live?.messages ?: shown?.messages ?: stored?.messages.orEmpty()
        // "This text is already in the transcript", not "this run has messages": a run that
        // used a tool has already stored messages under the same runId, and judging by the
        // run alone would throw the half-written final answer away — the one thing this
        // draft exists to save. The first 64 characters are the fingerprint; a cleanly
        // finished answer starts with them, because the draft was its prefix.
        val probe = draft.text.trim().take(LIVE_DRAFT_PROBE_CHARS)
        val delivered = probe.isNotEmpty() && messages.any { message ->
            message.runId == draft.runId &&
                message.role == Roles.ASSISTANT &&
                message.text.trim().startsWith(probe)
        }
        // An empty draft has no answer in it to save; clearing it is all that is left.
        if (delivered || probe.isEmpty()) {
            Core.chats.clearLive(chatId)
            return
        }
        // The answer is real but unfinished, and nothing else can say so: StoredMessage has
        // no field for it, and a bubble that looks complete would read as the model having
        // stopped mid-sentence for no reason.
        val line = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.ASSISTANT,
            text = draft.text.trimEnd() + "\n\n（中断）",
            runId = draft.runId,
        )
        val next = messages + line
        if (shown != null) _state.update { it.copy(messages = next) }
        live?.let { putSession(it.copy(messages = next)) }
        save(
            chatId = chatId,
            title = live?.title ?: shown?.title ?: stored?.title.orEmpty(),
            messages = next,
            assistantId = live?.assistantId ?: shown?.assistantId ?: stored?.assistantId.orEmpty(),
            activeRuns = live?.activeRuns ?: shown?.activeRuns ?: stored?.activeRuns.orEmpty(),
            providerId = live?.providerId ?: shown?.providerId ?: stored?.providerId,
            model = live?.model ?: shown?.model ?: stored?.model,
            reasoningEffort = live?.reasoningEffort ?: shown?.reasoningEffort ?: stored?.reasoningEffort,
            checkpoints = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty(),
        )
        Core.chats.clearLive(chatId)
    }

    /**
     * Records one compression the engine just made.
     *
     * The record is what lets a later run reuse a summary instead of paying to produce it
     * again, so it is saved the moment it arrives. Newest last and capped: an unbounded
     * history of every compression a long conversation ever made would only grow the file.
     */
    private fun recordCheckpoint(chatId: String, checkpoint: ContextCheckpoint) {
        val live = sessions[chatId]
        val shown = _state.value.takeIf { it.conversationId == chatId }
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val current = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty()
        val next = (current + checkpoint).distinctBy { it.id }.takeLast(MAX_CHECKPOINTS)
        // Mirrored onto every live copy first, so the next save carries it whether the run
        // is still going or has just landed.
        if (shown != null) _state.update { it.copy(checkpoints = next) }
        live?.let { putSession(it.copy(checkpoints = next)) }
        viewModelScope.launch {
            // Read-modify-write of the checkpoints field alone: the run's own copy of the
            // messages is still growing, and saving it here would write a half-finished
            // snapshot back over the transcript. A conversation that is gone has nothing to
            // write to at all — save() upserts, so it would come back as an empty ghost.
            val base = Core.chats.conversations.value.firstOrNull { it.id == chatId }
                ?: return@launch
            save(
                chatId = chatId,
                title = base.title,
                messages = base.messages,
                assistantId = base.assistantId,
                activeRuns = base.activeRuns,
                providerId = base.providerId,
                model = base.model,
                reasoningEffort = base.reasoningEffort,
                checkpoints = next,
            )
        }
    }

    /** Saves the conversation on screen. */
    private suspend fun persistDisplayed() {
        val shown = _state.value
        val title = save(
            chatId = shown.conversationId,
            title = shown.title,
            messages = shown.messages,
            assistantId = shown.assistantId,
            activeRuns = shown.activeRuns,
            providerId = shown.providerId,
            model = shown.model,
            checkpoints = shown.checkpoints,
        ) ?: return
        if (title != shown.title) _state.update { it.copy(title = title) }
    }

    private suspend fun saveSession(live: RunSession) {
        val title = save(
            chatId = live.chatId,
            title = live.title,
            messages = live.messages,
            assistantId = live.assistantId,
            activeRuns = live.activeRuns,
            providerId = live.providerId,
            model = live.model,
            checkpoints = live.checkpoints,
        ) ?: return
        val current = sessions[live.chatId]
        if (current != null && title != current.title) {
            putSession(current.copy(title = title))
        }
    }

    /** Writes one conversation to disk; returns the title it ended up with. */
    private suspend fun save(
        chatId: String,
        title: String,
        messages: List<StoredMessage>,
        assistantId: String,
        activeRuns: Map<String, String>,
        providerId: String?,
        model: String?,
        reasoningEffort: String? = null,
        checkpoints: List<ContextCheckpoint>? = null,
    ): String? {
        if (chatId.isEmpty()) return null
        val previous = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val derived = if (title.isBlank() || title == "新会话") {
            messages.firstOrNull { it.role == Roles.USER }
                ?.text
                ?.replace('\n', ' ')
                ?.take(18)
                ?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { "新会话" }
        } else {
            title
        }
        Core.chats.save(
            Conversation(
                id = chatId,
                title = derived,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                messages = messages,
                assistantId = assistantId,
                activeRuns = activeRuns,
                // The conversation's own model pick survives every save: dropping it here
                // would silently hand the next run back to the persona. Saves that carry
                // no pick (a message landing, a derived title, the end of a run) fall back
                // to what is already on disk instead of clearing it.
                providerId = providerId ?: previous?.providerId,
                model = model ?: previous?.model,
                // Same trap again: the thinking level is picked in the same sheet as the
                // model, so a save that carries none must inherit the one on disk rather
                // than resetting the chat to the app-wide default.
                reasoningEffort = reasoningEffort ?: previous?.reasoningEffort,
                // Same trap as the model pick: a save that carries no checkpoints must
                // inherit the ones on disk, or every message landing would wipe the record
                // of what was compressed — and the next run would compress and pay again.
                checkpoints = checkpoints ?: previous?.checkpoints ?: emptyList(),
            ),
        )
        return derived
    }

    override fun onCleared() {
        // Every prompt this screen's runs put on screen is answered "refused" as the
        // screen dies; other conversations' prompts belong to their own screens.
        jobs.keys.forEach { gate.cancelPending(it) }
        // viewModelScope cancels its children on clear; these two are the explicit
        // belt to that pair of braces.
        stateWatcher?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onCleared()
    }
}

private fun Conversation.toUiState(fallbackAssistantId: String): ChatUiState = ChatUiState(
    conversationId = id,
    title = title,
    messages = messages,
    activeRuns = activeRuns,
    assistantId = assistantId.ifBlank { fallbackAssistantId },
    providerId = providerId,
    model = model,
    reasoningEffort = reasoningEffort,
    checkpoints = checkpoints,
    // A conversation read from disk is not "still opening": this state is what the skeleton
    // is replaced by, so it must not leave the flag set.
    loading = false,
)

/** How many compressions of one conversation are kept; older ones are dropped. */
private const val MAX_CHECKPOINTS = 10

/**
 * How often the answer being streamed is written to the conversation's draft file.
 *
 * A second, not every delta: each write is an encode plus a file write, and a long answer
 * arrives in thousands of deltas. It is the interval where a kill costs the last tick of the
 * answer rather than all of it.
 */
private const val LIVE_DRAFT_INTERVAL_MS = 1000L

/**
 * How often buffered deltas are published to the screen.
 *
 * Under the frame time a reader can notice, and far under the rate a fast endpoint sends at:
 * the state stops changing hundreds of times a second, and with it the Markdown re-parse that
 * every one of those changes forced (the render cache is keyed by the text, so a text that
 * changes every frame is a miss every frame).
 *
 * Eighty rather than fifty is an inference, not a measurement: the user still saw ghosting
 * after the fifty-millisecond version, and the cost that scales with a fast endpoint is the
 * re-parse, so halving the publish rate halves it. Twelve and a half a second is still
 * smoother than reading speed. The measurement-backed fix — reusing the previous parse for
 * the unchanged prefix instead of re-parsing the whole document — needs `MarkdownText` to
 * report where each block starts, which it does not do yet.
 */
private const val DELTA_PUBLISH_MS = 80L

/**
 * How much of a draft is compared against the transcript to decide it was already saved.
 *
 * A prefix, not the whole text: the saved answer is the draft plus whatever arrived after the
 * last write, so equality would call every cleanly finished run "not delivered" and duplicate
 * the bubble. 64 characters are far more than any two answers share by accident.
 */
private const val LIVE_DRAFT_PROBE_CHARS = 64

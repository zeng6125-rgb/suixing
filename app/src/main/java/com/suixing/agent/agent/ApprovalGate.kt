package com.suixing.agent.agent

import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.SettingsRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds a tool call until the user answers. In ASK mode the agent coroutine
 * parks on a [CompletableDeferred], so the run resumes the moment the user taps
 * approve or reject in the UI.
 *
 * The mode arrives per call from the settings snapshot the run started with, so switching it
 * while a run is in flight does not change that run — the next one obeys the new mode. (The
 * composer's picker tells the user the same thing.)
 *
 * 「总是允许」 and 「总是拒绝」 are written to settings rather than kept in a field,
 * because a decision that evaporates on restart is not a memory, it is a nuisance.
 */
class ApprovalGate(
    private val settings: SettingsRepository? = null,
    private val scope: CoroutineScope? = null,
) {
    data class Request(val id: String, val toolName: String, val args: String, val sequence: Long = 0,
        val sourceTitle: String? = null, val rememberDecisions: Boolean = true)

    private val requestSequence = AtomicLong()

    /** Oldest requested approval wins, independently of the map implementation. */
    fun oldestPending(requests: Map<String, Request>): Map.Entry<String, Request>? =
        requests.entries.minByOrNull { it.value.sequence }

    private data class Decision(val approved: Boolean, val always: Boolean)
    private data class Waiter(val requestId: String, val deferred: CompletableDeferred<Decision>)

    private val _pending = MutableStateFlow<Map<String, Request>>(emptyMap())
    val pending: StateFlow<Map<String, Request>> = _pending.asStateFlow()

    /** One waiter per run: two conversations asking at once must not overwrite each other. */
    private val waiters = ConcurrentHashMap<String, Waiter>()

    private val alwaysAllowed = ConcurrentHashMap.newKeySet<String>()

    private val alwaysDenied = ConcurrentHashMap.newKeySet<String>()

    private val _alwaysAllowed = MutableStateFlow<Set<String>>(emptySet())

    private val _alwaysDenied = MutableStateFlow<Set<String>>(emptySet())

    /** The tools the user said 「总是允许」 to, for the settings list. */
    val alwaysAllowedTools: StateFlow<Set<String>> = _alwaysAllowed.asStateFlow()

    /** The tools the user said 「总是拒绝」 to, for the settings list. */
    val alwaysDeniedTools: StateFlow<Set<String>> = _alwaysDenied.asStateFlow()

    init {
        scope?.launch {
            val saved = runCatching { settings?.current() }.getOrNull() ?: return@launch
            alwaysAllowed.addAll(saved.alwaysAllowedTools)
            alwaysDenied.addAll(saved.alwaysDeniedTools)
            // A tool the user both allowed and denied at some point resolves to the
            // refusal: the deny list only ever grows by an explicit tap, so it is the
            // more deliberate of the two answers.
            alwaysAllowed.removeAll(alwaysDenied)
            _alwaysAllowed.value = alwaysAllowed.toSet()
            _alwaysDenied.value = alwaysDenied.toSet()
        }
    }

    suspend fun request(chatId: String, toolName: String, args: String, mode: ApprovalMode,
        sourceTitle: String? = null, rememberDecisions: Boolean = true): Boolean {
        when (mode) {
            ApprovalMode.AUTO -> return true
            ApprovalMode.DENY -> return false
            ApprovalMode.ASK -> Unit
        }
        if (alwaysDenied.contains(toolName)) return false
        if (rememberDecisions && alwaysAllowed.contains(toolName)) return true
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Decision>()
        val waiter = Waiter(id, deferred)
        waiters[chatId] = waiter
        val request = Request(id, toolName, args, requestSequence.incrementAndGet(), sourceTitle, rememberDecisions)
        _pending.update { it + (chatId to request) }
        // 取消/异常时也要摘掉门，否则这个会话的审批门永久残留（issue #54 A-7）。
        val decision = try {
            deferred.await()
        } finally {
            waiters.remove(chatId, waiter)
            _pending.update { if (it[chatId]?.id == id) it - chatId else it }
        }
        if (rememberDecisions && decision.always) {
            if (decision.approved) rememberAlways(toolName) else rememberDenied(toolName)
        }
        return decision.approved
    }

    fun resolve(chatId: String, approved: Boolean, always: Boolean = false, expectedRequestId: String? = null) {
        val waiter = waiters[chatId] ?: return
        if (expectedRequestId == null || waiter.requestId == expectedRequestId) {
            waiter.deferred.complete(Decision(approved, always))
        }
    }

    /**
     * Used when the user stops one run while its prompt is on screen. The state is
     * cleared here rather than in [request] because a cancelled coroutine never
     * resumes, which would otherwise leave the dialog on screen forever. Other runs'
     * prompts stay up: stopping A must not answer B's question for the user.
     */
    fun cancelPending(chatId: String) {
        waiters.remove(chatId)?.deferred?.complete(Decision(false, false))
        _pending.update { it - chatId }
    }

    /** Forgets every remembered tool. */
    fun forgetAlways() {
        alwaysAllowed.clear()
        _alwaysAllowed.value = emptySet()
        persist()
    }

    /** Forgets one remembered tool, from the settings list. */
    fun forgetAlways(toolName: String) {
        if (!alwaysAllowed.remove(toolName)) return
        _alwaysAllowed.value = alwaysAllowed.toSet()
        persist()
    }

    /** Forgets every tool the user answered 「总是拒绝」 to. */
    fun forgetDenied() {
        alwaysDenied.clear()
        _alwaysDenied.value = emptySet()
        persist()
    }

    /** Forgets one refused tool, from the settings list. */
    fun forgetDenied(toolName: String) {
        if (!alwaysDenied.remove(toolName)) return
        _alwaysDenied.value = alwaysDenied.toSet()
        persist()
    }

    private fun rememberAlways(toolName: String) {
        alwaysDenied.remove(toolName)
        _alwaysDenied.value = alwaysDenied.toSet()
        if (!alwaysAllowed.add(toolName)) return
        _alwaysAllowed.value = alwaysAllowed.toSet()
        persist()
    }

    private fun rememberDenied(toolName: String) {
        alwaysAllowed.remove(toolName)
        _alwaysAllowed.value = alwaysAllowed.toSet()
        if (!alwaysDenied.add(toolName)) return
        _alwaysDenied.value = alwaysDenied.toSet()
        persist()
    }

    private fun persist() {
        val repository = settings ?: return
        val allowed = alwaysAllowed.sorted()
        val denied = alwaysDenied.sorted()
        (scope ?: return).launch {
            runCatching { repository.update { it.copy(alwaysAllowedTools = allowed, alwaysDeniedTools = denied) } }
        }
    }
}

package com.suixing.agent.terminal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.asStateFlow

/** Set by the engine after its ordinary approval gate, never taken from model arguments. */
internal class TerminalCaller(val chatId: String, val runId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TerminalCaller>
}

internal class TerminalPolicy(private val ownerChatId: String?) {
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    @Volatile private var revoked = false
    @Volatile private var grant: Pair<String, String>? = null

    @Synchronized fun allow(caller: TerminalCaller): Boolean =
        if (ownerChatId != null) ownerChatId == caller.chatId && !revoked
        else grant == (caller.chatId to caller.runId)

    @Synchronized fun grant(caller: TerminalCaller) {
        check(ownerChatId == null) { "Agent 自建会话请使用恢复控制" }
        grant = caller.chatId to caller.runId
        _revision.value++
    }

    fun canGrant(caller: TerminalCaller): Boolean = ownerChatId == null || ownerChatId == caller.chatId
    @Synchronized fun grantOrResume(caller: TerminalCaller) {
        check(canGrant(caller))
        if (ownerChatId == null) grant(caller) else { revoked = false; _revision.value++ }
    }
    @Synchronized fun takeOver() {
        if (!revoked || grant != null) { revoked = true; grant = null; _revision.value++ }
    }
    @Synchronized fun resumeAgent() { revoked = false; _revision.value++ }
}

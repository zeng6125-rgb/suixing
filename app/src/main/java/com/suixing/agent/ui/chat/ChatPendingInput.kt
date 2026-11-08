package com.suixing.agent.ui.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The queue of one conversation, read under its own monitor. */
internal fun ChatRuntime.steersOf(chatId: String): List<PendingSteer> = steers.list(chatId)


/** Appends a line to a queue — the read and the write are one step. */
internal fun ChatRuntime.enqueueSteer(chatId: String, line: PendingSteer) {
    steers.add(chatId, line)
}


/** Takes a line off a queue by id — the read and the write are one step. */
internal fun ChatRuntime.removeSteer(chatId: String, id: String) {
    steers.take(chatId, id)
}


/** Forgets a conversation's queue entirely. */
internal fun ChatRuntime.clearSteers(chatId: String) {
    steers.forget(chatId)
}


/**
 * Takes a line typed while a run is going, and routes it by the mode the run was started with.
 *
 * A line typed while a run is going belongs to **the engine**, which is the only place that
 * knows what 插话模式 means for this run: 立即 cuts the round and is read now, 排队 is carried at
 * the next round boundary and shown in the queue above the composer. The view model does not
 * second-guess it — an earlier version kept its own local queue for 排队 and held the line until
 * the run ended, which swallowed the user's message (nothing sent, nothing shown, no run left to
 * deliver it) and is exactly the 「运行时我插话怎么发不出去了」 that came back from the device.
 *
 * False means "there is nothing to steer" — no run, or a blank line — and the composer falls
 * back to sending it as an ordinary turn.
 */
internal fun ChatRuntime.steer(text: String, imagesBase64: List<String> = emptyList()): Boolean {
    val body = text.trim()
    val chatId = _state.value.conversationId
    if (body.isEmpty() && imagesBase64.isEmpty()) return false
    if (!environment.engine.isRunning(chatId)) return false
    val command = parseSlashCommand(body)
    val directTool = command?.let { prepareSlashTool(chatId, it, imagesBase64) ?: return false }
    if (!environment.engine.steersImmediately(chatId)) {
        // 排队: the line is the *next turn*, not an interruption. It waits here, is drawn above
        // the composer at once (see publishPendingTurns), and goes out through [send] when this
        // run ends. Its own id, because the row offers the same three actions a queued steer
        // does — 立即插入 (hand it to the run in flight), 编辑 (take it back to the field) and
        // 删除 — and all three have to name the line they act on.
        enqueuePendingTurn(
            chatId,
            PendingTurn(UUID.randomUUID().toString().take(8), body, imagesBase64),
        )
        publishPendingTurns(chatId)
        // The field is cleared here rather than left to the caller. The composer does clear on
        // a true return, but the device is the verdict on trusting that: the line appeared in
        // the queue and the text stayed in the box, so from where the user sat nothing had been
        // sent at all. Queuing is an acceptance, and an acceptance owns the field it took the
        // line from — the 立即 branch below does the same, for the same reason. Writing Draft()
        // twice is harmless: the composer's own clear is idempotent.
        clearDraft(chatId)
        return true
    }
    val id = UUID.randomUUID().toString().take(8)
    if (!environment.engine.steer(chatId, id, body, imagesBase64 = imagesBase64, directTool = directTool)) return false
    // No TouchFx.noteActivity here on purpose. The edge light means "the agent is operating the
    // phone right now" — UI automation — and a line the user typed is not that. A steer that
    // kept the light alive would say the phone is being driven while nothing is driving it. The
    // round's own model/tool activity already lights it and the round's end already puts it out.
    enqueueSteer(chatId, PendingSteer(id, body, imagesBase64))
    publishSteers(chatId)
    clearDraft(chatId)
    return true
}


/** Sends a queued line now, cutting the round in flight so the model sees it at once. */
internal fun ChatRuntime.promoteSteer(id: String) {
    val chatId = _state.value.conversationId
    val queued = steersOf(chatId).firstOrNull { it.id == id } ?: return
    if (environment.engine.promote(chatId, id)) return
    // No round left to interrupt: the line goes out as an ordinary message instead of
    // being silently dropped — but only because the user asked for it now.
    retractSteer(id)
    if (send(queued.text, queued.imagesBase64)) return
    // The send was refused after all — another run in this conversation got there first. The
    // line has already been taken off the board, so putting it back is the only way it is not
    // lost without a word: the row returns and the user can send it again.
    // Read-modify-write under the lock: an append that lost the update would drop a line
    // the user had queued in the meantime.
    enqueueSteer(chatId, queued)
    publishSteers(chatId)
}


/** Drops a queued line before the model ever reads it. */
internal fun ChatRuntime.retractSteer(id: String) {
    val chatId = _state.value.conversationId
    environment.engine.retract(chatId, id)
    removeSteer(chatId, id)
    publishSteers(chatId)
}


/**
 * Drops one waiting 排队 turn.
 *
 * By id: the row carries three actions and each has to name the line it acts on, so the turn
 * needs the same kind of handle a queued steer has.
 */
internal fun ChatRuntime.retractTurn(chatId: String, id: String) {
    removePendingTurn(chatId, id)
    publishPendingTurns(chatId)
}


/**
 * 「立即插入」 on a waiting turn: it stops being the next turn and becomes an interruption of the
 * run in flight — the 立即 path, taken deliberately, one line at a time.
 *
 * The line is taken out of the queue first, so a run that ends in the same instant cannot also
 * send it as the next turn. If the engine refuses (nothing running any more, or the line was
 * already retracted), it goes back to the queue rather than disappearing.
 */
internal fun ChatRuntime.promoteTurn(chatId: String, id: String) {
    val queued = takePendingTurn(chatId, id) ?: return
    val command = parseSlashCommand(queued.text)
    val directTool = command?.let { prepareSlashTool(chatId, it, queued.imagesBase64) }
    if (command != null && directTool == null) {
        enqueuePendingTurn(chatId, queued)
        publishPendingTurns(chatId)
        return
    }
    publishPendingTurns(chatId)
    if (environment.engine.steer(chatId, id, queued.text, imagesBase64 = queued.imagesBase64, directTool = directTool)) {
        // It is an interruption now, so it is drawn as one: the same bubble a steer gets.
        enqueueSteer(chatId, PendingSteer(id, queued.text, queued.imagesBase64))
        publishSteers(chatId)
        return
    }
    enqueuePendingTurn(chatId, queued)
    publishPendingTurns(chatId)
}


/** Mirrors one conversation's queue onto the screen, when that is the one on screen. */
internal fun ChatRuntime.publishSteers(chatId: String) {
    if (_state.value.conversationId != chatId) return
    // The queue is read **inside** the update's lambda, under the lock: the state update is
    // a compare-and-set loop, so a lost race re-reads the queue and publishes the newer
    // one. Reading it once before the update would let a later publish overwrite a newer
    // one with an older list.
    _state.update { it.copy(pendingSteers = steersOf(chatId)) }
}


/**
 * 排队 turn 的队列（issue #29）：跟 [steers] 一样的形状 —— UI 线程写（[steer]/
 * [retractTurn]/[promoteTurn]），IO 线程读改写（`handleEvent` 里的 [drainPendingTurns]），
 * 原来的普通 map 会**静默丢更新**（用户排的 turn 凭空消失）。同一把锁把「读-改-写」收成一步。
 *
 * 队列内部同时保护 drain 防重入标记，避免两个入口重复发送。
 */
/** 队列里的 排队 turn（读，锁内）。 */
internal fun ChatRuntime.pendingTurnsOf(chatId: String): List<PendingTurn> = pendingTurns.list(chatId)


/** 入队（锁内；读-改-写合成一步）。 */
internal fun ChatRuntime.enqueuePendingTurn(chatId: String, turn: PendingTurn) {
    pendingTurns.add(chatId, turn)
}


/** 按 id 摘掉一条（锁内）。 */
internal fun ChatRuntime.removePendingTurn(chatId: String, id: String) {
    pendingTurns.take(chatId, id)
}


/** 按 id 取出一条并同时把它摘掉（锁内，合成一步 ⇒ 不会取到别人已经摘走的那条）。 */
internal fun ChatRuntime.takePendingTurn(chatId: String, id: String): PendingTurn? = pendingTurns.take(chatId, id)


/** The same, for the 排队 turns waiting to become the next run. */
internal fun ChatRuntime.publishPendingTurns(chatId: String) {
    if (_state.value.conversationId != chatId) return
    _state.update { it.copy(pendingTurns = pendingTurnsOf(chatId)) }
}


/**
 * Starts the next 排队 turn, if the run that was blocking it has really finished.
 *
 * **Taken off the queue first, sent second — and that order is the fix.** An earlier version
 * sent first and dropped the head only when `send` returned true, so any path that left the head
 * in place (a refusal while the old run was still registered, or a second hook racing the first)
 * meant the line was delivered again at the end of the next run, and the one after that: the
 * assistant answered, and then the same line was sent again, for as long as the user watched.
 * Sending twice is the worse failure of the two — a dropped line is visible, because its row is
 * still on screen and the user can send it again, while a repeated one looks like the model
 * talking to itself.
 *
 * So: take it, try to send, and put it back **only** if the send was refused (a run still
 * registered, or no conversation on screen to send into). The queue's drain guard keeps hooks from
 * getting in each other's way; a refused turn is restored at the head, in order.
 */
internal fun ChatRuntime.drainPendingTurns(chatId: String) {
    val result = pendingTurns.drain(chatId, isDisplayed = {
        val shown = _state.value
        shown.conversationId == chatId && !shown.loading
    }) { next ->
        publishPendingTurns(chatId)
        send(next.text, next.imagesBase64)
    }
    if (result != PendingMessageQueues.DrainResult.REFUSED) return
    publishPendingTurns(chatId)
    // The terminal state can arrive before the engine unregisters. A refused turn stays
    // at the head and gets one retry when nothing is running; a live run drains at its end.
    if (!environment.engine.isRunning(chatId)) {
        viewModelScope.launch {
            delay(RETRY_TURN_MS)
            drainPendingTurns(chatId)
        }
    }
}


/** Reopening a completed background chat has no future terminal event to drain its queue. */
internal fun ChatRuntime.resumePendingTurns(chatId: String) {
    if (!sessions.containsKey(chatId) && !environment.engine.isRunning(chatId)) drainPendingTurns(chatId)
}

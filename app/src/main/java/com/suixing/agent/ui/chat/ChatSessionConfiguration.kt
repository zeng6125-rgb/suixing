package com.suixing.agent.ui.chat

import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.AssistantAvatarStore
import com.suixing.agent.ui.settings.withUpstreamDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Remembers the model this conversation picked — and only this conversation's copy of
 * it. The choice belongs to the chat it was made in, so two conversations can run
 * different endpoints side by side while the app-wide default stays the settings
 * page's to own.
 */
internal fun ChatRuntime.chooseModel(providerId: String, model: String) {
    val chatId = _state.value.conversationId
    if (chatId.isEmpty()) return
    val shown = _state.value
    _state.update { it.copy(providerId = providerId, model = model) }
    // The run's own copy carries the pick as well, or the next save would write the
    // conversation back without it and the run after that would fall back to the
    // persona.
    val live = sessions[chatId]
    live?.let { putSession(it.copy(providerId = providerId, model = model)) }
    // **最近一次选择 = 新会话的默认**（用户：「最新一次选的为 default 模型，也就是新会话
    // 就用这个……跟 dsh 一样的逻辑」）。只写全局默认这一份；已经在别的会话里选过的模型
    // 不受影响——它们的 `providerId/model` 是自己的那一份，谁都覆盖不了谁。
    viewModelScope.launch {
        val provider = environment.settings.current().providers.firstOrNull { it.id == providerId }
        environment.settings.update { current ->
            if (provider == null) {
                current.copy(model = model)
            } else {
                current.copy(
                    model = model,
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    protocol = provider.protocol,
                )
            }
        }
    }
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
internal fun ChatRuntime.chooseReasoning(level: String) {
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


/** 当前会话单独保存，下一次任务读取；快速连选按点击顺序落盘。 */
internal fun ChatRuntime.chooseApproval(mode: ApprovalMode) {
    val shown = _state.value
    val chatId = shown.conversationId
    if (chatId.isEmpty()) return
    conversationApprovalChoices[chatId] = mode
    _state.update { if (it.conversationId == chatId) it.copy(approvalMode = mode) else it }
    sessions[chatId]?.let { putSession(it.copy(approvalMode = mode)) }
    val previousWrite = approvalSaveJob
    approvalSaveJob = viewModelScope.launch {
        previousWrite?.join()
        runCatchingCancellable {
            checkNotNull(environment.chats.updateMeta(id = chatId, approvalMode = mode)) { "会话已不存在" }
        }
            .onFailure { failure ->
                _state.update {
                    if (it.conversationId == chatId && it.approvalMode == mode) {
                        it.copy(error = "会话审批设置未保存：${failure.message ?: failure.javaClass.simpleName}")
                    } else it
                }
            }
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
internal fun ChatRuntime.prefetchWindows() {
    viewModelScope.launch {
        val settings = environment.settings.current()
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
            runCatchingCancellable { llm.listModelDetails(endpoint) }
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
        environment.settings.update { stored ->
            stored.copy(modelContexts = stored.modelContexts.withUpstreamDefaults(gathered))
        }
    }
}


/**
 * 改名：写盘，并让屏幕上这一份跟着走。
 *
 * 不能只改仓库：这一屏的标题是 [_state] 里的一份拷贝，而每轮结束 [persistDisplayed] 都会把
 * 那份拷贝写回去（[save] 里 `derived = title` 取的就是它）—— 只改仓库的话，用户给**当前
 * 打开的这个会话**改名，下一轮就会被悄悄改回旧标题。别的会话没有这个问题，但两条路都从
 * 这里走，调用方就不必自己判断「改的是不是当前这一个」。
 */
internal fun ChatRuntime.renameConversation(id: String, title: String) {
    val clean = title.trim()
    if (id.isEmpty() || clean.isEmpty()) return
    viewModelScope.launch {
        environment.chats.rename(id, clean)
        if (_state.value.conversationId == id) _state.update { it.copy(title = clean) }
        sessions[id]?.let { putSession(it.copy(title = clean)) }
    }
}


// ---------------------------------------------------------------- assistants

/**
 * Makes [assistant] the face of this conversation — this one only. The app-wide
 * default is a separate decision ([setDefaultAssistant]): a persona tried out in one
 * chat must not silently become what every other chat starts as.
 */
internal fun ChatRuntime.useAssistant(assistant: Assistant) {
    editDisplayed { it.copy(assistantId = assistant.id) }
    viewModelScope.launch { persistDisplayed() }
}


/** Makes [assistant] what new conversations start as, leaving this one as it is. */
internal fun ChatRuntime.setDefaultAssistant(assistant: Assistant) {
    viewModelScope.launch {
        environment.settings.update { it.copy(activeAssistantId = assistant.id) }
    }
}


internal fun ChatRuntime.saveAssistant(assistant: Assistant) {
    viewModelScope.launch {
        // Whether this was a new persona or an edit decides what else may move: saving
        // a brand-new one is an explicit "use this", editing one is not.
        var created = false
        environment.settings.update { settings ->
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


internal fun ChatRuntime.deleteAssistant(assistant: Assistant) {
    viewModelScope.launch {
        var removed = false
        environment.settings.update { settings ->
            // The app always needs one persona to fall back on.
            if (settings.assistants.size <= 1) return@update settings
            removed = true
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
        // The persona's pictures go with the persona, and only if it really went: the sweep
        // is by id, so a picture picked and then abandoned by closing the editor — a file
        // settings never heard about — is taken by the same call. Nothing else would ever
        // name it again.
        if (removed) AssistantAvatarStore.deleteAllFor(environment.appContext, assistant.id)
        val settings = environment.settings.current()
        val next = settings.assistant(_state.value.assistantId) ?: settings.activeAssistant()
        editDisplayed { it.copy(assistantId = next?.id.orEmpty()) }
        persistDisplayed()
    }
}

package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ProviderConfig

/**
 * The pure half of 模型与生成: which provider a draft edit addresses, and whether the draft
 * holds an edit at all. It sits apart from the screen so the rules can be tested without a
 * device — the two that regressed in the field were "tapping a chip is not an edit" and "a
 * name typed for one provider must never land on another".
 */

/** Rewrites one saved endpoint in place; a null or unknown id is a no-op. */
internal fun AppSettings.mapProvider(id: String?, block: (ProviderConfig) -> ProviderConfig): AppSettings {
    if (id == null || providers.none { it.id == id }) return this
    return copy(providers = providers.map { if (it.id == id) block(it) else it })
}

/**
 * True when the two settings differ anywhere that the save button is responsible for. Two
 * fields are left out on purpose:
 *
 * - the endpoint triple, which a chip tap rewrites in the draft without writing to disk (a
 *   hand edit there is what [endpointHandEdited] reports);
 * - provider names, which are written through a beat after typing stops (see the name field).
 *   Counting them would light up 「有未保存的改动」 on every keystroke, and that subtitle
 *   appearing and disappearing is what made the page's title look like it was shaking.
 */
internal fun AppSettings.differsOutsideEndpoint(other: AppSettings): Boolean {
    val mine = copy(baseUrl = other.baseUrl, apiKey = other.apiKey, model = other.model).withoutNames()
    return mine != other.withoutNames()
}

/** The same settings with every provider name blanked, so names can be compared out. */
private fun AppSettings.withoutNames(): AppSettings =
    copy(providers = providers.map { it.copy(name = "") })

/**
 * True when the draft holds a hand edit to the endpoint triple (address, key, model).
 *
 * Both halves are needed. The draft must differ from disk, or a freshly seeded draft would
 * count as an edit the moment its model is not the provider's first enabled one; and it must
 * differ from what a switch to [provider] would write, or tapping a chip — which rewrites
 * those three fields in the draft without saving — would look like one. The model half of the
 * switch target falls back to the draft's own model, because a provider with nothing enabled
 * leaves that field alone.
 */
internal fun endpointHandEdited(form: AppSettings, stored: AppSettings, provider: ProviderConfig?): Boolean {
    val switchModel = provider?.enabled?.firstOrNull() ?: form.model
    val differsFromDisk = form.baseUrl != stored.baseUrl ||
        form.apiKey != stored.apiKey || form.model != stored.model
    val differsFromSwitch = provider != null &&
        (form.baseUrl != provider.baseUrl ||
            form.apiKey != provider.apiKey ||
            form.model != switchModel)
    return differsFromDisk && differsFromSwitch
}

/**
 * What the save button writes: the draft, except that a blank address must not blank the live
 * endpoint. A draft is blank right after 新建, and writing it through would leave the running
 * conversation with no address and no model — it would fail on its next turn instead of
 * keeping the endpoint the user is still on. The rest of the draft saves as usual.
 */
internal fun AppSettings.forSaving(current: AppSettings): AppSettings =
    if (baseUrl.isBlank()) {
        copy(baseUrl = current.baseUrl, apiKey = current.apiKey, model = current.model)
    } else {
        this
    }

/**
 * [model]'s window in the settings map — the one place the key is decided, shared by every
 * writer (this page's presets, the model picker's chip, the chat's ring dialog).
 *
 * The key is the raw model id, which is exactly what [AppSettings.contextWindowFor] reads and
 * what the engine, the view model and the ring all pass in. A window filed under an alias or a
 * display name would be stored and then never read: the ring would keep saying 窗口未设置.
 * A count of zero or less removes the entry, which is what 不限 means.
 */
internal fun Map<String, Int>.withContextWindow(model: String, tokens: Int): Map<String, Int> =
    if (tokens <= 0 || model.isBlank()) this - model else this + (model to tokens)

/**
 * Fills in the windows the endpoint advertised for its models, leaving every window the user
 * set untouched: a key that is already here is never overwritten, so 自定义 wins over 上游 and
 * re-fetching the list cannot undo a choice. Blank ids and non-positive counts are ignored —
 * an endpoint that advertises nothing contributes nothing, and nothing is invented in its place.
 *
 * A window set here is indistinguishable from one set by hand, which is the point: 「设过」 is
 * the key existing at all, and that is what makes 自定义 win on every later fetch.
 */
internal fun Map<String, Int>.withUpstreamDefaults(upstream: Map<String, Int>): Map<String, Int> {
    val missing = upstream.filter { (model, tokens) -> model.isNotBlank() && tokens > 0 && model !in this }
    return this + missing
}

/**
 * A starting point for [model]'s window, or null when the name says nothing recognisable.
 *
 * These are the ceilings the vendors publish for the families that are identifiable by name.
 * It is only ever shown as a suggestion the user taps to accept — never written on its own —
 * and it errs low on purpose: too small reddens the ring a little early, too large lets a
 * request overflow the real window and fail at the endpoint.
 */
internal fun suggestedContextWindow(model: String): Int? {
    val id = model.lowercase()
    if (id.isBlank()) return null
    return when {
        "claude" in id -> 200_000
        "gemini" in id -> 1_000_000
        "deepseek" in id -> 64_000
        "gpt-4" in id || "gpt-5" in id || "o1" in id || "o3" in id || "o4" in id -> 128_000
        "qwen" in id -> 32_000
        "glm" in id || "kimi" in id || "moonshot" in id || "llama" in id -> 128_000
        "mistral" in id -> 32_000
        else -> null
    }
}

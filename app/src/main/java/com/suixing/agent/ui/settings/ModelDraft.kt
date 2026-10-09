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
 * True when the two settings differ anywhere except the endpoint triple. Those three fields
 * are what a chip tap rewrites in the draft without writing to disk, so they must not be read
 * as an edit on their own; a hand edit to them is what [endpointHandEdited] reports.
 */
internal fun AppSettings.differsOutsideEndpoint(other: AppSettings): Boolean =
    copy(baseUrl = other.baseUrl, apiKey = other.apiKey, model = other.model) != other

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

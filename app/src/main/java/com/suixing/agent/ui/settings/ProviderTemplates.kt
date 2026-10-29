package com.suixing.agent.ui.settings

import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI

/** Connection presets only. Model availability always comes from the configured endpoint. */
internal data class ProviderTemplate(val name: String, val baseUrl: String, val protocol: String = PROTOCOL_OPENAI)

internal val providerTemplates = listOf(
    ProviderTemplate("OpenAI", "https://api.openai.com/v1"),
    ProviderTemplate("Anthropic", "https://api.anthropic.com/v1", PROTOCOL_ANTHROPIC),
    ProviderTemplate("DeepSeek", "https://api.deepseek.com/v1"),
    ProviderTemplate("OpenRouter", "https://openrouter.ai/api/v1"),
)

internal fun ProviderTemplate?.newProvider(id: String): ProviderConfig = ProviderConfig(
    id = id,
    name = this?.name.orEmpty(),
    baseUrl = this?.baseUrl.orEmpty(),
    protocol = this?.protocol ?: PROTOCOL_OPENAI,
)

/** Enabling never chooses a global active model or changes connection credentials. */
internal fun ProviderConfig.withModelEnabled(model: String, on: Boolean): ProviderConfig {
    if (model.isBlank()) return this
    return copy(
        models = if (on) (models + model).distinct() else models,
        enabled = if (on) (enabled + model).distinct() else enabled - model,
    )
}

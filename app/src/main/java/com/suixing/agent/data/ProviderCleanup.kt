package com.suixing.agent.data

/** Keys and model catalogs are optional: named LAN endpoints may be keyless or not fetched yet. */
internal fun ProviderConfig.hasProviderDetails(): Boolean =
    id.isNotBlank() && name.trim().let { it.isNotEmpty() && it != "未命名" && it != "未命名供应商" } &&
        baseUrl.isNotBlank()

/** Remove incomplete entries without changing the live endpoint or any model/assistant references. */
internal fun AppSettings.withoutEmptyProviders(): AppSettings {
    val kept = providers.filter { it.hasProviderDetails() }
    return if (kept.size == providers.size) this
    else copy(providers = kept, providersInitialized = true)
}

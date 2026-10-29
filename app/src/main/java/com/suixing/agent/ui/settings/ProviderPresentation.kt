package com.suixing.agent.ui.settings

import java.net.URI

/** Only the authority is useful in a picker; paths, query tokens and userinfo stay out. */
internal fun providerEndpointLabel(address: String): String = runCatching {
    val uri = URI(address.trim())
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching "自定义接口"
    if (uri.port >= 0) "$host:${uri.port}" else host
}.getOrDefault("自定义接口")

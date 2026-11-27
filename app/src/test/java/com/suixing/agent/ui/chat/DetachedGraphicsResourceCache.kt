package com.suixing.agent.ui.chat

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue

/** Detached layouts have no Android owner to supply Compose's optional vector cache. */
internal fun detachedGraphicsResourceCache(): ProvidedValue<*> {
    val locals = Class.forName("androidx.compose.ui.platform.CompositionLocalsKt")
    @Suppress("UNCHECKED_CAST")
    val cache = locals.getMethod("getLocalGraphicsResourceCache").invoke(null) as ProvidableCompositionLocal<Any?>
    return cache provides null
}

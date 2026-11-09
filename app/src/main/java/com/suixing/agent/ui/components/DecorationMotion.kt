package com.suixing.agent.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Read by animated leaves; the provider itself stays unchanged through drawer motion. */
internal val LocalDecorationMotion = staticCompositionLocalOf<() -> Boolean> { { true } }

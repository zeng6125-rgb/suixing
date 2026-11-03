package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import com.suixing.agent.ui.components.LocalDrawerBusy

/** Keep a cold target's temporary placeholder from flashing through a moving drawer. */
@Composable
internal fun ConversationLoading() {
    val drawerBusy = LocalDrawerBusy.current
    // This discrete flag changes at close start/end, not per animation frame. Read it only
    // in this leaf; derivedStateOf would track the same two changes without reducing work.
    if (!drawerBusy.value) LoadingSkeleton()
}

package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.FileActivity

/** One footer inside the reply body; file delivery and actions share the same layout policy. */
@Composable
internal fun AssistantReplyFooter(
    activities: List<FileActivity>,
    streaming: Boolean,
    actions: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sections = replyFooterSections(streaming, activities.size, actions != null)
    if (sections.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sections.forEach { section ->
            when (section) {
                ReplyFooterSection.FILE_ACTIVITY -> ChatFileActivity(activities)
                ReplyFooterSection.ACTIONS -> actions?.invoke()
            }
        }
    }
}

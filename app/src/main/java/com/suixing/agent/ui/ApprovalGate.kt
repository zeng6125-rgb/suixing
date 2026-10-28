package com.suixing.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.data.Conversation
import kotlinx.coroutines.flow.StateFlow
import com.suixing.agent.ui.components.SuixingDialog

/** Subscribe at the approval gate so new prompts do not recompose the whole app shell. */
@Composable
internal fun PendingApprovals(conversations: StateFlow<List<Conversation>>) {
    val pending by Core.gate.pending.collectAsStateWithLifecycle()
    val first = Core.gate.oldestPending(pending) ?: return
    val chats by conversations.collectAsStateWithLifecycle()
    ApprovalDialog(
        chatId = first.key,
        toolName = first.value.toolName,
        args = first.value.args,
        sourceTitle = chats.firstOrNull { it.id == first.key }?.title,
        otherWaiting = pending.size - 1,
    )
}

@Composable
internal fun ApprovalDialog(
    chatId: String,
    toolName: String,
    args: String,
    /** Title of the conversation whose run is asking; null once it is gone from the list. */
    sourceTitle: String?,
    /** How many other conversations are blocked on their own prompt right now. */
    otherWaiting: Int,
) {
    // A deleted or untitled conversation still has to be named: a blank line would leave
    // the user unable to tell the card apart from the chat they are looking at.
    val from = sourceTitle?.takeIf { it.isNotBlank() } ?: "另一个会话"
    SuixingDialog(
        onDismissRequest = { Core.gate.resolve(chatId, false) },
        icon = { Icon(ImageVector.vectorResource(R.drawable.ic_shield_alert), contentDescription = null) },
        title = {
            Column {
                Text("需要你确认")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "来自会话：$from",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Only worth a line when it is true: one blocked chat is the normal case.
                if (otherWaiting > 0) {
                    Text(
                        text = "还有 $otherWaiting 个会话在等待",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column {
                Text("随行想调用工具：$toolName")
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = args,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "拒绝后模型会收到失败结果，可以换个方式继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { scope -> TextButton(onClick = { scope.closeThen { Core.gate.resolve(chatId, true) } }) { Text("允许一次") } },
        dismissButton = { scope ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { scope.closeThen { Core.gate.resolve(chatId, true, always = true) } }) { Text("总是允许") }
                TextButton(onClick = { scope.closeThen { Core.gate.resolve(chatId, false) } }) { Text("拒绝") }
            }
        },
    )
}

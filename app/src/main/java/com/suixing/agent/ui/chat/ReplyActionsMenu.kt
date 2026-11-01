package com.suixing.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.components.MenuCard

/** A non-overlapping alternative to squeezing every reply action into a narrow row. */
@Composable
internal fun ReplyActionsMenu(
    message: StoredMessage,
    branch: Branch?,
    onEdit: (StoredMessage) -> Unit,
    onRegenerate: () -> Unit,
    onBranch: (String, String) -> Unit,
) {
    var open by remember(message.id) { mutableStateOf(false) }
    val copy = rememberCopyToClipboard()
    val cap = (LocalConfiguration.current.screenHeightDp.dp * 0.6f).coerceAtLeast(48.dp)
    BackHandler(open) { open = false }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text("更多操作")
        }
        MenuCard(open, { open = false }, Alignment.TopStart, IntOffset.Zero, modifier = Modifier.width(220.dp)) {
            Column(Modifier.heightIn(max = cap).verticalScroll(rememberScrollState())) {
                @Composable fun Action(label: String, action: () -> Unit) {
                    TextButton(onClick = { open = false; action() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(label)
                    }
                }
                Action("复制回复") { copy(message.text) }
                Action("修改回复") { onEdit(message) }
                if (branch?.canRegenerate == true) Action("重新回答", onRegenerate)
                if (branch != null && branch.index > 0) Action("上一个回答") {
                    onBranch(branch.turnId, branch.runs[branch.index - 1])
                }
                if (branch != null && branch.index < branch.runs.lastIndex) Action("下一个回答") {
                    onBranch(branch.turnId, branch.runs[branch.index + 1])
                }
            }
        }
    }
}

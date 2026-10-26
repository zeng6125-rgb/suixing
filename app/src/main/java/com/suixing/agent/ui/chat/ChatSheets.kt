package com.suixing.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.label
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.SuixingDialog
import kotlin.math.max
import com.suixing.agent.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/** 完整任务清单：输入框上方那一行点开就是它。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoSheet(todos: List<TodoItem>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = todos.count { it.status == TODO_COMPLETED }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("任务清单", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "已完成 $done/${todos.size}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(todos) { item -> TodoSheetRow(item) }
            }
        }
    }
}

/**
 * 文件路径菜单（LINK-CLICK-SPEC §3.2 一期）：点消息里的文件路径弹出来。
 *
 * 一期只有两项，且都是「永远可用」的：复制路径、让随行读取。二期才加 FileProvider 的
 * 「文件管理器打开 / 分享」——spec 说得很明白，ACTION_VIEW 对 mime 与已装 app 依赖太大
 * （`.md` 多半没有对应 app），所以把复制与让模型读这两个不会断的放前面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PathActionSheet(
    path: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onAskModel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 路径本体：等宽、可换行、不截断——用户要核对的就是这串。
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            MenuItemRow(
                icon = ImageVector.vectorResource(R.drawable.ic_copy),
                label = "复制路径",
                onClick = onCopy,
            )
            MenuItemRow(
                icon = ImageVector.vectorResource(R.drawable.ic_bot),
                label = "让随行读取并总结",
                onClick = onAskModel,
            )
        }
    }
}

/** 清单里的一行：状态标记 + 事项；完成的划掉一点、进行中的用品牌色。 */
@Composable
internal fun TodoSheetRow(item: TodoItem) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TODO_COMPLETED
    val active = item.status == TODO_IN_PROGRESS
    val ink = when {
        active -> scheme.primary
        done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(
                when {
                    done -> R.drawable.ic_circle_check
                    // 没有专门的「进行中」图标：箭头朝右＝正在往前走，比再画一个圆点更不含糊。
                    active -> R.drawable.ic_chevron_right
                    else -> R.drawable.ic_list_checks
                },
            ),
            contentDescription = null,
            tint = when {
                active -> scheme.primary
                done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                else -> scheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The board rows worth drawing: the items still open, in the order the model wrote them, and —
 * once everything is done — the last few that finished.
 *
 * Finished items leave the row as they finish, which is what "创建、完成、消失" describes: the
 * plan on screen is what is *left*, and the tally is what says how far along the whole board is.
 * Showing the whole list instead would push the one item being worked on off the bottom of a
 * three-row strip as soon as a plan grew past it.
 */

/**
 * One icon action of a queued row.
 *
 * The touch target is the 40dp node the ripple and the click live on, with the glyph drawn
 * inside it: a 16dp icon with a 16dp hit area is a control the user misses half the time.
 * [label] is the accessibility name and the thing to grep for.
 */
@Composable
internal fun QueueAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Rewrites a message. A question can also be re-asked from the edited text, which
 * throws away everything the model said afterwards — those answers were written for
 * a prompt that no longer exists.
 */
@Composable
internal fun EditDialog(
    message: StoredMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResend: ((String) -> Unit)?,
) {
    var draft by remember(message.id) { mutableStateOf(message.text) }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.role == Roles.USER) "修改这条消息" else "修改这条回复") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                if (message.role == Roles.USER) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "「重新发送」会丢掉这条之后的所有内容，让模型重新回答。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { scope ->
            if (onResend != null) {
                TextButton(
                    onClick = { scope.closeThen { onResend(draft) } },
                    enabled = draft.isNotBlank(),
                ) { Text("重新发送") }
            } else {
                TextButton(onClick = { scope.closeThen { onSave(draft) } }, enabled = draft.isNotBlank()) { Text("保存") }
            }
        },
        dismissButton = { scope ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onResend != null) {
                    TextButton(onClick = { scope.closeThen { onSave(draft) } }, enabled = draft.isNotBlank()) {
                        Text("只保存")
                    }
                }
                TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") }
            }
        },
    )
}

package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 完成态标记与运行态弧、选中态用同一个 `primary`：会话列表里三种状态不该各说一种颜色，
 * 更不该留一套固定墨绿。`Tone.OK` 的语义绿留给设置页那种「成功/警告」对照，不混进这里。
 */
@Composable
internal fun CompletionDot(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(14.dp)
            .semantics { contentDescription = "已完成，未读" }
            .drawBehind { drawCircle(color, radius = 3.5.dp.toPx()) },
    )
}

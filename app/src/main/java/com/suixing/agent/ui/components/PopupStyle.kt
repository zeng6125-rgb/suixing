package com.suixing.agent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 小浮层的**唯一**卡片样式。
 *
 * 用户的要求是「全部小浮层动画 + 卡片样式统一」。原来每个浮层各写一份 `shadow + clip +
 * background`：`MenuCard` 是 20dp 圆角，上下文圆环/会话统计却是 16dp —— 并排看就是两种质感。
 *
 * 收敛到一处之后，圆角、投影、底色只有这一个版本；想改样式只改这里。
 */
internal val PopupCardShape = RoundedCornerShape(20.dp)

/**
 * 投影：**轻**、**不带偏移**（用户：「阴影我不需要很重的，全部改轻，同时阴影不要有偏移，
 * 要有质感的阴影」）。
 *
 * 做法：`ambientColor` 与 `spotColor` 同色同浓度 ⇒ 只有环境光没有方向性偏移，看着是"浮起
 * 一层纸"而不是"贴在某一边"。elevation 从 8dp 降到 3dp，配合 18% 的黑 ⇒ 轻。
 *
 * 用 `ambientColor`/`spotColor` 显式给色，而不是靠 elevation 推算：Material 的默认阴影色
 * 偏灰且随 elevation 加重，用户看到的"重"就是它。
 */
internal fun Modifier.popupCardSurface(color: Color): Modifier = this
    .shadow(
        elevation = 3.dp,
        shape = PopupCardShape,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.18f),
    )
    .clip(PopupCardShape)
    .background(color)

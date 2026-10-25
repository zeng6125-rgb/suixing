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

/** 投影：8dp，同色相的 25% 黑 —— 与抽屉菜单原本的质感一致（用户曾明确说那个阴影「很高级」）。 */
internal fun Modifier.popupCardSurface(color: Color): Modifier = this
    .shadow(
        elevation = 8.dp,
        shape = PopupCardShape,
        ambientColor = Color.Black.copy(alpha = 0.25f),
        spotColor = Color.Black.copy(alpha = 0.25f),
    )
    .clip(PopupCardShape)
    .background(color)

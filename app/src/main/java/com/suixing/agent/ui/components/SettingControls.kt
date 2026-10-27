package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
/**
 * 全站唯一的「列表行」：左边标题（可带前置图标、可带副标题），右边控件。
 * 设置页、关于页、文件页的行都从这里出——曾经每页各写一遍，行高、内边距、
 * 副标题字号各不相同。
 *
 * 标准（改这里就是改全站）：
 *  - 纵向 **12dp**：两行行盒 43dp + 24dp = 67dp；单行 25dp + 24dp = 49dp，仍在 48dp 触控下限之上。
 *  - 标题 `bodyLarge`、副标题 `bodySmall`、控件前 12dp。
 *  - **横向留白默认不加**（[contentPadding] 默认 0）：卡内的行由 SuixingCard 的 16dp 给，
 *    页面级的行由调用方用 [modifier] 传 16dp。默认值给 16 会让卡里的行变成 32dp 缩进。
 *    唯一的例外是 [SettingsGroup]：那里的行要**铺满卡片**（涟漪才碰得到卡片边缘），所以它
 *    自己把 16dp 从容器挪进 [contentPadding]、并把 [shape] 换成与卡片同形。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    /** 行自己的可见形状：涟漪的圆角跟着它走。卡内的行由调用方传卡片形状。 */
    shape: Shape = MaterialTheme.shapes.small,
    /** 内容内边距，落在**点击区之内** ⇒ 涟漪照样铺满整行（横向），内容仍缩进。 */
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    // 行自带圆角点击区：水波纹跟着圆角走，而不是溢成一个方角。
                    // 走共享修饰符：clip → clickable → 上面的 padding，涟漪就等于整行（含 12dp 行高
                    // 内边距），不再只盖住文字那一块。[contentPadding] 在它**之后**，所以横向留白
                    // 也算在点击区里 —— 这正是「涟漪铺满卡片」的关键。
                    Modifier.suixingClickable(shape, onClick = onClick)
                },
            )
            .padding(contentPadding)
            .padding(vertical = SettingsStyle.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(SettingsStyle.ControlGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = SettingsStyle.rowTitle,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (control != null) {
            Spacer(Modifier.width(SettingsStyle.ControlGap))
            control()
        }
    }
}

/** 行的「后面还有一页」标记：一个 18dp 的 chevron、一种颜色，全站只有这一份。 */
@Composable
fun RowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

/** Shared low-contrast track and selected surface for exclusive settings and tabs. */
@Composable
fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    Surface(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        // At most four labels: match segment heights when large text wraps to two lines.
        Row(Modifier.padding(4.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = active, role = role, onClick = { if (!active) onSelect(index) }),
                    shape = MaterialTheme.shapes.small,
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(
                        Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = SettingsStyle.controlLabel,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Every settings destination uses the same switch size, track and disabled treatment. */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.primary,
            checkedTrackColor = colors.primaryContainer,
            checkedBorderColor = colors.primary.copy(alpha = 0.2f),
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.surfaceContainerHighest,
            uncheckedBorderColor = colors.outlineVariant,
        ),
    )
}

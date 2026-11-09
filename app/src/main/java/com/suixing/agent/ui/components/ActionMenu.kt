package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.suixing.agent.R

/** Shared geometry for action menus and anchor-width dropdowns. */
internal object ActionMenuStyle {
    val MinWidth = 208.dp
    val MaxWidth = 360.dp
    val MaxHeight = 360.dp
    val RowHeight = 48.dp
    val HorizontalPadding = 16.dp
    val VerticalPadding = 8.dp
}

/** The platform still owns positioning, dismissal, scrolling and keyboard focus. */
@Composable
internal fun ActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val availableWidth = (configuration.screenWidthDp.dp - 32.dp).coerceAtLeast(0.dp)
    val maxWidth = availableWidth.coerceAtMost(ActionMenuStyle.MaxWidth)
    val widthModifier = if (anchorWidth != null && anchorWidth > 0.dp) {
        Modifier.width(anchorWidth.coerceAtMost(availableWidth))
    } else {
        Modifier.widthIn(min = ActionMenuStyle.MinWidth.coerceAtMost(maxWidth), max = maxWidth)
    }
    val maxHeight = (configuration.screenHeightDp.dp * 0.6f).coerceAtMost(ActionMenuStyle.MaxHeight)
    val colors = MaterialTheme.colorScheme
    val border = remember(colors.outlineVariant) { BorderStroke(1.dp, colors.outlineVariant) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = widthModifier.heightIn(max = maxHeight).then(modifier),
        shape = PopupCardShape,
        containerColor = colors.surfaceContainerHighest,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = border,
    ) {
        MenuRippleHost { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

/** The native item owns its visuals and input; the host only draws its ripple. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = ActionMenuStyle.HorizontalPadding, vertical = ActionMenuStyle.VerticalPadding,
    ),
) {
    val feedback = rememberMenuRippleItem(RectangleShape)
    val configuration = if (feedback == null) rememberMenuRippleConfiguration() else null
    val colors = MaterialTheme.colorScheme
    val rowModifier = modifier.fillMaxWidth().heightIn(min = ActionMenuStyle.RowHeight)
        .background(if (selected) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent)
    val selectionModifier = if (selected) rowModifier.semantics { this.selected = true } else rowModifier
    val check: (@Composable () -> Unit)? = trailingIcon ?: if (selected) {
        { Icon(ImageVector.vectorResource(R.drawable.ic_check), "已选中") }
    } else null
    CompositionLocalProvider(LocalRippleConfiguration provides configuration) {
        DropdownMenuItem(
            text = { ProvideTextStyle(SettingsStyle.body, content = text) },
            onClick = onClick,
            modifier = selectionModifier.menuRipplePosition(feedback),
            leadingIcon = leadingIcon,
            trailingIcon = check,
            enabled = enabled,
            colors = MenuDefaults.itemColors(
                textColor = colors.onSurface,
                leadingIconColor = colors.onSurfaceVariant,
                trailingIconColor = if (selected) colors.primary else colors.onSurfaceVariant,
            ),
            contentPadding = contentPadding,
            interactionSource = feedback?.interactionSource,
        )
    }
}

package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/** Keep the platform menu's original shape, sizing, typography and motion. */
@Composable
internal fun ActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        MenuRippleHost { Column(content = content) }
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
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
) {
    val feedback = rememberMenuRippleItem()
    val configuration = if (feedback == null) rememberMenuRippleConfiguration() else null
    CompositionLocalProvider(LocalRippleConfiguration provides configuration) {
        DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier.menuRipplePosition(feedback),
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            contentPadding = contentPadding,
            interactionSource = feedback?.interactionSource,
        )
    }
}

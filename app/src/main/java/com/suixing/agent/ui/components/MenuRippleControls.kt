package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Native controls retain their layout; feedback keeps each control's original shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuRippleTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = ButtonDefaults.textShape
    val feedback = rememberMenuRippleItem(shape)
    val configuration = if (feedback == null) rememberMenuRippleConfiguration() else null
    CompositionLocalProvider(LocalRippleConfiguration provides configuration) {
        TextButton(onClick = onClick, modifier = modifier.menuRipplePosition(feedback),
            enabled = enabled, shape = shape, interactionSource = feedback?.interactionSource, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuRippleSurface(
    onClick: () -> Unit,
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val feedback = rememberMenuRippleItem(shape)
    val configuration = if (feedback == null) rememberMenuRippleConfiguration() else null
    CompositionLocalProvider(LocalRippleConfiguration provides configuration) {
        Surface(onClick = onClick, shape = shape, color = color,
            modifier = modifier.menuRipplePosition(feedback),
            interactionSource = feedback?.interactionSource, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuRippleCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Checkbox owns its small circular ripple and the internal Press coordinate space.
    // Keeping it native also avoids registering another full-width layer for the same row.
    val configuration = rememberMenuRippleConfiguration()
    CompositionLocalProvider(LocalRippleConfiguration provides configuration) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier)
    }
}

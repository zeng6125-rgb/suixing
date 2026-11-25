package com.suixing.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.suixing.agent.ui.theme.LocalMiuixCornerScale
import com.suixing.agent.ui.theme.miuixScaledCorner
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 同步 Material3 与 Miuix 两套 `LocalContentColor`：
 * 官方 Miuix 容器设置 [MiuixLocalContentColor]，未单独改造的 Material `Text` / `Icon`
 * 读取 Material3 [LocalContentColor]，合并提供才能保证文字/图标在浅色与暗黑 HyperOS 卡片下始终清晰。
 */
@Composable
private fun ProvideDualContentColor(
    color: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        MiuixLocalContentColor provides color,
        content = content,
    )
}

@Composable
internal fun MiuixCardContainer(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(SettingsStyle.CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val corner = miuixScaledCorner(CardDefaults.CornerRadius, LocalMiuixCornerScale.current)
    val contentColor = MiuixTheme.colorScheme.onSurfaceContainer
    val colors = CardDefaults.defaultColors(
        color = containerColor,
        contentColor = contentColor,
    )
    if (onClick == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = corner,
            insideMargin = contentPadding,
            colors = colors,
        ) {
            ProvideDualContentColor(contentColor) { content() }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = corner,
            insideMargin = contentPadding,
            colors = colors,
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = onClick,
        ) {
            ProvideDualContentColor(contentColor) { content() }
        }
    }
}

@Composable
internal fun MiuixPageTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MiuixTheme.colorScheme
    SmallTopAppBar(
        title = title,
        color = scheme.surface,
        titleColor = scheme.onSurface,
        subtitle = subtitle.orEmpty(),
        subtitleColor = scheme.onSurfaceVariantSummary,
        defaultWindowInsetsPadding = true,
        navigationIcon = {
            MiuixIconButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 12.dp),
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = "返回",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        actions = {
            ProvideDualContentColor(scheme.onSurface) {
                actions()
            }
        },
        bottomContent = {
            Spacer(Modifier.height(4.dp))
        },
    )
}

@Composable
internal fun MiuixSettingRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalPadding: Dp = SettingsStyle.RowPadding,
) {
    val scheme = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val direction = LocalLayoutDirection.current
    val insideMargin = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = verticalPadding,
        end = contentPadding.calculateEndPadding(direction),
        bottom = verticalPadding,
    )
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        insideMargin = insideMargin,
        startAction = leading,
        endActions = if (control != null) {
            {
                ProvideDualContentColor(scheme.onSurfaceVariantActions) {
                    control()
                }
            }
        } else {
            {}
        },
        onClick = onClick,
    ) {
        Text(
            text = title,
            style = styles.headline1.copy(fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = styles.body2,
                color = scheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
internal fun MiuixRowChevron() {
    Icon(
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
internal fun MiuixSwitchControl(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled && onCheckedChange != null,
    )
}

@Composable
internal fun MiuixDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    confirmButton: @Composable (DialogScope) -> Unit = {},
    dismissButton: (@Composable (DialogScope) -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    var visible by remember { mutableStateOf(true) }
    var pendingAfter by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = remember {
        DialogScope { after ->
            pendingAfter = after
            visible = false
        }
    }
    WindowDialog(
        show = visible,
        onDismissRequest = if (properties.dismissOnClickOutside) {
            { scope.closeThen { latestDismiss() } }
        } else {
            null
        },
        onDismissFinished = {
            pendingAfter?.invoke()
            pendingAfter = null
        },
        cornerRadius = miuixScaledCorner(DialogDefaults.CornerRadius, LocalMiuixCornerScale.current),
    ) {
        ProvideDualContentColor(scheme.onBackground) {
            Column(Modifier.fillMaxWidth()) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        icon()
                    }
                }
                if (title != null) {
                    ProvideTextStyle(styles.title4.copy(fontWeight = FontWeight.Bold, color = scheme.onBackground)) {
                        Box(Modifier.padding(bottom = if (text != null) 12.dp else 16.dp)) {
                            title()
                        }
                    }
                }
                if (text != null) {
                    ProvideTextStyle(styles.body1.copy(color = scheme.onBackgroundVariant)) {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            text()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke(scope)
                    confirmButton(scope)
                }
            }
        }
    }
}

@Composable
internal fun MiuixActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    WindowListPopup(
        show = expanded,
        popupModifier = modifier,
        onDismissRequest = onDismissRequest,
        maxHeight = maxHeight,
        minWidth = minWidth,
    ) {
        ListPopupColumn {
            ProvideDualContentColor(scheme.onSurfaceContainer) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

@Composable
internal fun MiuixActionMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = 20.dp,
        vertical = 12.dp,
    ),
) {
    val scheme = MiuixTheme.colorScheme
    val contentColor = when {
        !enabled -> scheme.disabledOnSecondaryVariant
        selected -> scheme.onTertiaryContainer
        else -> scheme.onSurfaceContainer
    }
    val rowBackground = if (selected) scheme.tertiaryContainer else scheme.surfaceContainer
    val baseModifier = modifier
        .fillMaxWidth()
        .heightIn(min = ActionMenuStyle.RowHeight)
        .background(rowBackground)
        .let { if (selected) it.semantics { this.selected = true } else it }
        .clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
        .padding(contentPadding)
    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            ProvideDualContentColor(contentColor) {
                leadingIcon()
            }
        }
        Box(Modifier.weight(1f)) {
            ProvideTextStyle(
                MiuixTheme.textStyles.body1.copy(
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ) {
                text()
            }
        }
        val check: (@Composable () -> Unit)? = trailingIcon ?: if (selected) {
            {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = "已选中",
                    tint = scheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        }
        if (check != null) {
            ProvideDualContentColor(contentColor) {
                check()
            }
        }
    }
}

@Composable
internal fun MiuixBottomSheetHost(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var visible by remember { mutableStateOf(true) }
    WindowBottomSheet(
        show = visible,
        title = title,
        backgroundColor = scheme.background,
        cornerRadius = miuixScaledCorner(BottomSheetDefaults.cornerRadius, LocalMiuixCornerScale.current),
        onDismissRequest = { visible = false },
        onDismissFinished = onDismiss,
    ) {
        ProvideDualContentColor(scheme.onBackground) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun MiuixSmallSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    SmallTitle(
        text = text,
        modifier = modifier,
        textColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
        insideMargin = contentPadding,
    )
}

@Composable
internal fun MiuixSettingsSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        showKeyPoints = steps in 1..10,
    )
}

@Composable
internal fun MiuixActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: Int? = null,
) {
    val corner = miuixScaledCorner(12.dp, LocalMiuixCornerScale.current)
    val colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = SettingsStyle.ControlHeight),
        enabled = enabled && !busy,
        cornerRadius = corner,
        minWidth = ButtonDefaults.MinWidth,
        minHeight = SettingsStyle.ControlHeight,
        colors = colors,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (busy) {
            MiuixCircularProgressIndicator(
                size = 14.dp,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(
                painter = suixingIconPainter(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = SettingsStyle.controlLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MiuixActionTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val corner = miuixScaledCorner(12.dp, LocalMiuixCornerScale.current)
    val colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = corner,
        colors = colors,
    )
}

@Composable
internal fun MiuixCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = SettingsStyle.fieldText,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val corner = miuixScaledCorner(12.dp, LocalMiuixCornerScale.current)
    val shape = RoundedCornerShape(corner)
    val textColor = when {
        !enabled -> scheme.disabledOnSurface
        isError -> scheme.error
        else -> scheme.onSurface
    }
    val containerColor = when {
        !enabled -> scheme.disabledSecondary
        isError -> scheme.errorContainer
        else -> scheme.secondaryContainer
    }
    val borderColor = when {
        isError -> scheme.error
        focused -> scheme.primary
        else -> Color.Transparent
    }
    val borderWidth = if (focused || isError) 1.5.dp else 0.dp
    val labelColor = when {
        isError -> scheme.error
        focused -> scheme.primary
        else -> scheme.onSurfaceVariantSummary
    }
    val secondaryTextColor = if (isError) scheme.error else scheme.onSurfaceVariantSummary
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            if (isError) error("输入内容有误")
        },
    ) {
        if (label != null) {
            ProvideTextStyle(
                styles.footnote1.copy(
                    color = labelColor,
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                ProvideDualContentColor(labelColor) {
                    Box(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                        label()
                    }
                }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsStyle.ControlHeight),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.merge(
                TextStyle(
                    color = if (textStyle.color == Color.Unspecified) textColor else textStyle.color,
                ),
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interactions,
            cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SettingsStyle.ControlHeight)
                        .clip(shape)
                        .background(containerColor, shape)
                        .then(
                            if (borderWidth > 0.dp) {
                                Modifier.border(BorderStroke(borderWidth, borderColor), shape)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leadingIcon != null) {
                        ProvideDualContentColor(scheme.onSurfaceVariantActions) {
                            leadingIcon()
                        }
                    }
                    if (prefix != null) {
                        ProvideTextStyle(textStyle.copy(color = scheme.onSurfaceVariantSummary)) {
                            ProvideDualContentColor(scheme.onSurfaceVariantSummary) {
                                prefix()
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            ProvideTextStyle(textStyle.copy(color = scheme.onSurfaceContainerVariant)) {
                                ProvideDualContentColor(scheme.onSurfaceContainerVariant) {
                                    placeholder()
                                }
                            }
                        }
                        inner()
                    }
                    if (suffix != null) {
                        ProvideTextStyle(textStyle.copy(color = scheme.onSurfaceVariantSummary)) {
                            ProvideDualContentColor(scheme.onSurfaceVariantSummary) {
                                suffix()
                            }
                        }
                    }
                    if (trailingIcon != null) {
                        ProvideDualContentColor(scheme.onSurfaceVariantActions) {
                            trailingIcon()
                        }
                    }
                }
            },
        )
        if (supportingText != null) {
            ProvideTextStyle(styles.footnote1.copy(color = secondaryTextColor)) {
                ProvideDualContentColor(secondaryTextColor) {
                    Box(Modifier.padding(start = 4.dp, top = 4.dp)) {
                        supportingText()
                    }
                }
            }
        }
    }
}

internal enum class MiuixButtonVariant { Primary, Tonal, Outlined }

@Composable
internal fun MiuixCheckboxControl(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MiuixCheckbox(
        state = ToggleableState(checked),
        onClick = onCheckedChange?.let { cb -> { cb(!checked) } },
        modifier = modifier,
        enabled = enabled && onCheckedChange != null,
    )
}

@Composable
internal fun MiuixRadioButtonControl(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MiuixRadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && onClick != null,
    )
}

@Composable
internal fun MiuixButtonControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: MiuixButtonVariant = MiuixButtonVariant.Primary,
    cornerRadius: Dp = 12.dp,
    contentPadding: PaddingValues? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val scaledCorner = miuixScaledCorner(cornerRadius, LocalMiuixCornerScale.current)
    val customColors = containerColor != Color.Unspecified || contentColor != Color.Unspecified
    val colors = when (variant) {
        MiuixButtonVariant.Primary -> if (!customColors) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColorsPrimary(
                color = if (containerColor != Color.Unspecified) containerColor else scheme.primary,
                contentColor = if (contentColor != Color.Unspecified) contentColor else scheme.onPrimary,
            )
        }
        MiuixButtonVariant.Tonal -> if (!customColors) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                color = if (containerColor != Color.Unspecified) containerColor else scheme.secondaryVariant,
                contentColor = if (contentColor != Color.Unspecified) contentColor else scheme.onSecondaryVariant,
            )
        }
        MiuixButtonVariant.Outlined -> ButtonDefaults.buttonColors(
            color = if (containerColor != Color.Unspecified) containerColor else scheme.surfaceContainer.copy(alpha = 0.45f),
            disabledColor = scheme.disabledSecondaryVariant,
            contentColor = if (contentColor != Color.Unspecified) contentColor else scheme.onBackground,
            disabledContentColor = scheme.disabledOnSecondaryVariant,
        )
    }
    val shape = RoundedCornerShape(scaledCorner)
    val resolvedModifier = if (variant == MiuixButtonVariant.Outlined) {
        val borderColor = if (enabled) scheme.outline else scheme.dividerLine
        modifier.border(1.dp, borderColor, shape)
    } else {
        modifier
    }
    Button(
        onClick = onClick,
        modifier = resolvedModifier,
        enabled = enabled,
        cornerRadius = scaledCorner,
        minWidth = ButtonDefaults.MinWidth,
        minHeight = 40.dp,
        colors = colors,
        insideMargin = contentPadding ?: ButtonDefaults.InsideMargin,
    ) {
        ProvideDualContentColor(MiuixLocalContentColor.current) {
            content()
        }
    }
}

@Composable
internal fun MiuixFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(miuixScaledCorner(10.dp, LocalMiuixCornerScale.current))
    val bg = if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.secondaryVariant
    val fg = when {
        !enabled -> scheme.disabledOnSecondaryVariant
        selected -> scheme.primary
        else -> scheme.onSecondaryVariant
    }
    val border = if (selected) {
        BorderStroke(1.dp, scheme.primary.copy(alpha = if (enabled) 0.55f else 0.25f))
    } else {
        null
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape)
            .background(bg, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        ProvideDualContentColor(fg) {
            ProvideTextStyle(MiuixTheme.textStyles.footnote1.merge(TextStyle(color = fg))) {
                label()
            }
        }
    }
}

@Composable
internal fun MiuixLinearProgressControl(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    height: Dp = 6.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val colors = if (color == Color.Unspecified && trackColor == Color.Unspecified) {
        ProgressIndicatorDefaults.progressIndicatorColors()
    } else {
        ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = if (color != Color.Unspecified) color else scheme.primary,
            backgroundColor = if (trackColor != Color.Unspecified) trackColor else scheme.secondaryContainer,
        )
    }
    MiuixLinearProgressIndicator(
        modifier = modifier,
        progress = progress?.invoke()?.coerceIn(0f, 1f),
        colors = colors,
        height = height,
    )
}

@Composable
internal fun MiuixCircularProgressControl(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    size: Dp = 20.dp,
) {
    val scheme = MiuixTheme.colorScheme
    val colors = if (color == Color.Unspecified && trackColor == Color.Unspecified) {
        ProgressIndicatorDefaults.progressIndicatorColors()
    } else {
        ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = if (color != Color.Unspecified) color else scheme.primary,
            backgroundColor = if (trackColor != Color.Unspecified) trackColor else scheme.secondaryContainer,
        )
    }
    MiuixCircularProgressIndicator(
        modifier = modifier,
        progress = progress?.invoke()?.coerceIn(0f, 1f),
        colors = colors,
        strokeWidth = strokeWidth,
        size = size,
    )
}

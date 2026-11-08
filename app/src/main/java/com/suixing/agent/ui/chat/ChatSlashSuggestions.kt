package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.suixing.agent.ui.components.bottomInsetPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.ui.chat.GenerationSheet
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.MENU_EXIT_MS
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.menuRippleClickable
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Goal
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.label
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.runtime.mutableFloatStateOf

/** 输入框上方的斜杠命令候选浮层，以及它自己的三条动画规格。 */

internal val SLASH_POPUP_ENTER = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
    expandVertically(tween(180, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom,
        initialHeight = { it * 9 / 10 })
internal val SLASH_POPUP_EXIT = fadeOut(tween(90, easing = FastOutLinearInEasing)) +
    shrinkVertically(tween(120, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom,
        targetHeight = { it * 9 / 10 })
internal val SLASH_POPUP_RESIZE = tween<IntSize>(180, easing = FastOutSlowInEasing)


/**
 * The candidate card opens slightly upward from its bottom edge in its own window. Filtering
 * clears the outgoing text early while height settles; the window uses [popupPositionProvider].
 * [lastItems] stays visible while the whole popup exits, with its actions disabled.
 */
@Composable
internal fun SlashSuggestionPopup(
    visible: Boolean,
    items: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    popupPositionProvider: PopupPositionProvider,
    width: Dp,
    labelForItem: (String) -> String = { "/" + it },
) {
    // Keep the transition state across rapid query changes. AnimatedVisibility retargets from its
    // current values when the user clears and retypes, then removes the popup after exit completes.
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    var lastItems by remember { mutableStateOf(items) }
    SideEffect {
        if (items.isNotEmpty()) lastItems = items
    }
    // During an interrupted entrance, currentState can still be false while the transition is
    // running toward (or back toward) its target. Keep the popup attached until it settles hidden.
    if (visibility.isIdle && !visibility.currentState && !visibility.targetState) return

    Popup(
        popupPositionProvider = popupPositionProvider,
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = SLASH_POPUP_ENTER,
            exit = SLASH_POPUP_EXIT,
        ) {
            SlashSuggestionList(
                items = if (visible) items else lastItems,
                onPick = onPick,
                interactive = visible,
                modifier = Modifier.width(width),
                labelForItem = labelForItem,
            )
        }
    }
}

/** One content/size transition inside the Surface, so its border follows the animated height. */
@Composable
internal fun AnimatedSlashSuggestions(
    transition: Transition<List<Pair<String, String>>>,
    interactive: Boolean,
    content: @Composable (List<Pair<String, String>>, Boolean) -> Unit,
) {
    val selectable = rememberUpdatedState(transition.targetState.takeIf { interactive })
    transition.AnimatedContent(
        contentAlignment = Alignment.BottomStart,
        transitionSpec = {
            (fadeIn(tween(130, delayMillis = 40, easing = LinearOutSlowInEasing)) togetherWith
                fadeOut(tween(60, easing = FastOutLinearInEasing))).using(
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> SLASH_POPUP_RESIZE }),
            )
        },
    ) { shown ->
        content(shown, shown == selectable.value)
    }
}

/**
 * The commands matching what has been typed; tapping one fills in `/<name> `.
 *
 * Drawn in a popup window, so it cannot `fillMaxWidth()` — a popup's content measures against the
 * window, not against the field. The caller passes the width it wants.
 */
@Composable
private fun SlashSuggestionList(
    items: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    labelForItem: (String) -> String = { "/" + it },
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        val transition = updateTransition(items, label = "slash-suggestions")
        com.suixing.agent.ui.components.MenuRippleHost {
        AnimatedSlashSuggestions(transition, interactive) { shown, enabled ->
            Column(Modifier
                .then(if (enabled) Modifier else Modifier.clearAndSetSemantics { })
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState(), enabled = enabled)
                .padding(vertical = 4.dp)) {
                shown.forEach { (name, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuRippleClickable(enabled = enabled) { onPick(name) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labelForItem(name),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        }
    }
}

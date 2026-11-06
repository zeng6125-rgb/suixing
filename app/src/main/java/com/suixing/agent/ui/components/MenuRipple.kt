package com.suixing.agent.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

private val LocalMenuRippleHost = compositionLocalOf<MenuRippleRegistry?> { null }

/** Neutral feedback adapts to the surface's brightness, independently of the accent palette. */
@Composable
internal fun neutralRippleColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() >= 0.5f) Color.Black else Color.White

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMenuRippleConfiguration(): RippleConfiguration {
    val color = neutralRippleColor()
    return remember(color) { RippleConfiguration(color = color) }
}

/** The original content measures the host; the feedback layer only matches its final size. */
@Composable
internal fun MenuRippleHost(
    modifier: Modifier = Modifier,
    horizontalOverflow: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val registry = remember { MenuRippleRegistry() }
    val density = LocalDensity.current
    val overflowPx = with(density) { horizontalOverflow.roundToPx().coerceAtLeast(0) }
    val rippleColor = neutralRippleColor()
    val indication = remember(rippleColor) { ripple(color = rippleColor) }
    SideEffect { registry.horizontalOverflowPx = overflowPx }
    DisposableEffect(registry) {
        onDispose { registry.clear() }
    }
    Box(
        modifier.then(registry.positionModifier),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalMenuRippleHost provides registry, content = content)
        Box(
            Modifier.matchParentSize().layout { measurable, constraints ->
                val extraWidth = overflowPx * 2
                val expanded = constraints.copy(
                    minWidth = constraints.minWidth + extraWidth,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extraWidth
                        else Constraints.Infinity,
                )
                val layer = measurable.measure(expanded)
                layout((layer.width - extraWidth).coerceAtLeast(0), layer.height) {
                    layer.placeRelative(-overflowPx, 0)
                }
            },
        ) {
            registry.items.forEach { item ->
                key(item) {
                    MenuRippleLayer(item, indication)
                }
            }
        }
    }
}

/** A row's sparse height or shape change recomposes only its own feedback layer. */
@Composable
private fun MenuRippleLayer(item: MenuRippleItem, indication: Indication) {
    val density = LocalDensity.current
    val height = with(density) { item.heightPx.toDp() }
    Box(
        Modifier.fillMaxWidth()
            .offset { IntOffset(0, item.topPx.roundToInt()) }
            .height(height)
            .clip(item.shape)
            .drawWithContent {
                val top = (item.visibleTopPx - item.topPx).coerceIn(0f, size.height)
                val bottom = (item.visibleBottomPx - item.topPx).coerceIn(top, size.height)
                if (bottom > top) {
                    clipRect(top = top, bottom = bottom) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .indication(item.interactionSource, indication),
    )
}

/** One feedback owner per original control, with no added click target or semantics. */
@Composable
internal fun rememberMenuRippleItem(shape: Shape = MaterialTheme.shapes.small): MenuRippleItem? {
    val registry = LocalMenuRippleHost.current ?: return null
    val item = remember(registry) { MenuRippleItem(registry, shape) }
    SideEffect { item.shape = shape }
    DisposableEffect(registry, item) {
        registry.items.add(item)
        onDispose {
            registry.items.remove(item)
            item.clear()
        }
    }
    return item
}

internal fun Modifier.menuRipplePosition(item: MenuRippleItem?): Modifier =
    if (item == null) this else then(item.positionModifier)

/** Keep the caller's modifier order and original clickable geometry. */
@Composable
internal fun Modifier.menuRippleClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val item = rememberMenuRippleItem()
    val source = item?.interactionSource ?: remember { MutableInteractionSource() }
    val indication = if (item == null) {
        val color = neutralRippleColor()
        remember(color) { ripple(color = color) }
    } else null
    return menuRipplePosition(item).clickable(
        enabled = enabled,
        role = role,
        interactionSource = source,
        indication = indication,
        onClick = onClick,
    )
}

@Stable
internal class MenuRippleItem internal constructor(private val registry: MenuRippleRegistry, shape: Shape) {
    internal var shape by mutableStateOf(shape)
    private var coordinates: LayoutCoordinates? = null
    private var leftPx = 0f
    internal var topPx by mutableFloatStateOf(0f)
        private set
    internal var heightPx by mutableIntStateOf(0)
        private set
    internal var visibleTopPx by mutableFloatStateOf(0f)
        private set
    internal var visibleBottomPx by mutableFloatStateOf(0f)
        private set
    private val source = HorizontalMenuInteractionSource { leftPx + registry.horizontalOverflowPx }
    val interactionSource: MutableInteractionSource get() = source
    internal val positionModifier: Modifier = Modifier.onGloballyPositioned(::place)

    internal fun place(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        registry.update(this)
    }

    internal fun update(host: LayoutCoordinates?) {
        val control = coordinates
        if (host?.isAttached != true || control?.isAttached != true) {
            visibleTopPx = 0f
            visibleBottomPx = 0f
            return
        }
        val bounds = host.localBoundingBoxOf(control, clipBounds = false)
        val visibleBounds = host.localBoundingBoxOf(control, clipBounds = true)
        leftPx = bounds.left
        topPx = bounds.top
        heightPx = control.size.height
        visibleTopPx = visibleBounds.top
        visibleBottomPx = visibleBounds.bottom
    }

    internal fun clear() {
        coordinates = null
        visibleTopPx = 0f
        visibleBottomPx = 0f
        source.clear()
    }
}

internal class MenuRippleRegistry {
    val items = mutableStateListOf<MenuRippleItem>()
    val positionModifier: Modifier = Modifier.onGloballyPositioned(::placeHost)
    var horizontalOverflowPx = 0
    private var coordinates: LayoutCoordinates? = null

    fun placeHost(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        // Child placement may have arrived before the host's first placement.
        items.forEach { it.update(coordinates) }
    }

    fun update(item: MenuRippleItem) { item.update(coordinates) }

    fun clear() {
        coordinates = null
        items.forEach { it.clear() }
        items.clear()
    }
}

/** Release and Cancel must refer to the remapped Press object seen by the native ripple. */
private class HorizontalMenuInteractionSource(private val left: () -> Float) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    private val presses = IdentityHashMap<PressInteraction.Press, PressInteraction.Press>()
    override val interactions: Flow<Interaction> get() = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        delegate.emit(remap(interaction))
    }

    override fun tryEmit(interaction: Interaction): Boolean = delegate.tryEmit(remap(interaction))

    private fun remap(interaction: Interaction): Interaction = when (interaction) {
        is PressInteraction.Press -> {
            val position = interaction.pressPosition
            PressInteraction.Press(
                if (position.isSpecified) Offset(position.x + left(), position.y) else position,
            ).also { presses[interaction] = it }
        }
        is PressInteraction.Release -> presses.remove(interaction.press)?.let { PressInteraction.Release(it) }
            ?: interaction
        is PressInteraction.Cancel -> presses.remove(interaction.press)?.let { PressInteraction.Cancel(it) }
            ?: interaction
        else -> interaction
    }

    fun clear() { presses.clear() }
}

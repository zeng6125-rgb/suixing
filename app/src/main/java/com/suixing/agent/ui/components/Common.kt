package com.suixing.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color

/**
 * The values the OpenAI-compatible `reasoning_effort` field accepts, wire value to
 * 档位名. The settings slider and the chat's 思考 pill menu read the same list, so the
 * two can never disagree about what a level is called.
 */
val REASONING_LEVELS: List<Pair<String, String>> = listOf(
    "" to "默认",
    "minimal" to "minimal",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "xhigh",
)

/**
 * The one panel shape used everywhere. A hairline border instead of a drop
 * shadow keeps stacked surfaces legible in both schemes.
 */
@Composable
fun SuixingCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** A card that announces its own topic: tinted icon, title, optional subtitle. */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    SuixingCard(modifier = modifier, contentPadding = contentPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                SoftIcon(icon)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** A rounded square holding an icon, tinted so cards get a colour anchor. */
@Composable
fun SoftIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val dark = LocalDarkTheme.current
    val color = tone.color(dark)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        }
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Label on the left, control on the right; the settings screens are made of these. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    control: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (control != null) {
            Spacer(Modifier.width(12.dp))
            control()
        }
    }
}

/** Segmented pill control: one rounded track, the active segment filled. */
@Composable
fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * A screen header with an optional gradient wash, so every tab opens with the
 * same visual anchor instead of a bare title bar.
 *
 * Pass [onSubtitleClick] to turn the subtitle into a control; it then renders in
 * the accent colour with a chevron, which is how the chat header exposes the
 * model switcher.
 */
/**
 * The screen header every tab opens with.
 *
 * The sidebar handle lives here rather than in each screen: the shell publishes
 * [LocalOpenSidebar] once and every header grows the same button, so a new screen
 * cannot forget to offer the way back to the rest of the app.
 */
@Composable
fun SuixingTopBar(
    title: String,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitleTrailing: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                // 三档渐隐，不是两档：两档（10% → 透明）在 68dp 的高度里到一半就淡没了，
                // 按钮与标题看着像浮在文字上。现在顶部 18% 起步、过半仍有 6%，到栏底才归零，
                // 于是这一栏有明确的「底」，又不会在栏下缘留一条硬边。
                Brush.verticalGradient(
                    0f to scheme.primary.copy(alpha = 0.18f),
                    0.55f to scheme.primary.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 8.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // On the left, because the drawer it opens is on the left: a handle on the
            // far side of the header from the panel it reveals reads as a bug even when
            // it works.
            val openSidebar = LocalOpenSidebar.current
            if (openSidebar != null) {
                // The round handle, not the rounded square: the user held the square
                // against a circular reference and read the button as unchanged.
                DrawerButton(size = 38.dp)
                Spacer(Modifier.width(10.dp))
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null || subtitleTrailing != null) {
                    val interactive = onSubtitleClick != null
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subtitle != null) {
                            // One button for the label and its caret. They used to be two
                            // clickables, so TalkBack announced the switch twice and the caret
                            // on its own was a 16dp target. The caret is decoration now and the
                            // pair carries a standard 48dp target — the bar grows by it when a
                            // screen turns the subtitle into a control.
                            Row(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .then(
                                        if (interactive) {
                                            Modifier
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(onClick = onSubtitleClick!!)
                                                .padding(horizontal = 6.dp)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (interactive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (interactive) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_down),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (subtitleTrailing != null) {
                            Spacer(Modifier.width(8.dp))
                            subtitleTrailing()
                        }
                    }
                }
            }
            actions()
        }
    }
}

/**
 * How a screen asks the shell to slide its sidebar out. Null outside the shell,
 * which is what keeps the button from appearing in a preview or a bare screen.
 */
val LocalOpenSidebar = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The keyboard height the shell has clamped to, in pixels.
 *
 * Children that carry an inset of their own have to subtract it: the composer's navigation-bar
 * padding is dead space while the keyboard covers the gesture bar, which is the band that
 * appeared between the field and the keys. Clamped rather than raw so the platform's
 * end-of-animation rebound does not flicker the band back for a frame.
 *
 * `compositionLocalOf`, not `staticCompositionLocalOf`: this value changes on every frame of a
 * keyboard transition, and the static variant has no read tracking — every change would
 * invalidate the whole provided subtree (the entire NavHost) instead of just its readers.
 */
val LocalHeldImePx = compositionLocalOf { 0 }

/**
 * The bar for a page that is not a top-level destination: a back button instead of the
 * drawer handle.
 *
 * The drawer belongs to the chat screen alone. On a sub-page a hamburger offers a way
 * *sideways* out of a screen the user is in the middle of, while the way back — the one
 * they actually want — is not on screen at all.
 */
@Composable
fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // 层级靠 surfaceContainerLow 底 + 1dp 发丝线，不靠阴影（README「不用阴影」）：
    // 阴影在滚动内容上方是一圈灰雾，浅色侧尤其脏；发丝线只画一条边，内容从下面滑过时
    // 边界始终干净。这条线是这一栏唯一的层级信号，所以它不能省。
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // 56 → 64dp：标题 25sp + 副标题 18sp 的行盒只占 43dp，56 的高度把
                    // 两行挤成了紧贴的一坨。min 而非固定高度，字号调到「特大」时这栏
                    // 跟着长，而不是把标题裁掉一半。
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No disc behind the glyph on purpose: this bar is a filled surface of its
                // own, and a button with a second surface colour on top of it read as a
                // sticker. The chat header is the other way round — it floats over the
                // transcript with no fill, so there every button carries its own disc.
                CircleActionButton(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    onClick = onBack,
                    containerColor = Color.Transparent,
                    shadowElevation = 0.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    // The gap every page's first control keeps from the bar. It lives here rather than in the
    // twelve call sites: the bar owns its own breathing room, so a page cannot forget it and
    // look cramped. 12dp reads as deliberate without wasting a row of content.
    Spacer(Modifier.height(12.dp))
}

/**
 * The app's round action button: a filled circle that lifts when pressed.
 *
 * One helper for the header's two buttons, the composer's send/stop/steer and the
 * jump-to-latest button, so the four of them cannot drift apart in size, colour or
 * shadow — they were built separately once and did.
 */
/**
 * A lifted disc button: the chat header floats over the transcript with no bar of its
 * own, so each of its buttons carries its own surface and the same soft shadow the
 * composer's capsule casts — a flat glyph over scrolling content has nothing to sit on.
 */
@Composable
fun FlatIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 26.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The disc the sidebar handle wears. The reference the user gave was about the *glyph* —
 * three thick bars — never the disc, so the disc keeps the neutral `surfaceContainerHigh`
 * it had before: the button reads as one of the header's other actions rather than the
 * single coloured control in the row. Paired with [handleInk] so the chat header and the
 * drawer handle flip together instead of drifting apart.
 */
@Composable
private fun handleDisc(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** The other half of [handleDisc]: the ink that reads on that disc. */
@Composable
private fun handleInk(): Color = MaterialTheme.colorScheme.onSurface

/**
 * The same disc as [FlatIconButton] for glyphs that are vectors of our own rather
 * than material's: the drawer handle and the compose pencil are drawn in
 * res/drawable so their stroke weight is ours to set.
 *
 * The disc and the ink come from [handleDisc]/[handleInk], the same pair the drawer's
 * handle uses: both draw the one button the user pointed at, so neither drifts alone.
 */
@Composable
fun FlatPainterButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = handleInk(),
    iconSize: Dp = 22.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            ),
        shape = CircleShape,
        color = handleDisc(),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    /**
     * 0.dp for a button that must not cast one — the send button is flat by design, and
     * so is the back button on a filled bar. Such a button stays flat when pressed too:
     * see the press rule in the painter overload below.
     */
    shadowElevation: Dp = 2.dp,
) = CircleActionButton(
    painter = rememberVectorPainter(icon),
    contentDescription = contentDescription,
    onClick = onClick,
    modifier = modifier,
    size = size,
    containerColor = containerColor,
    contentColor = contentColor,
    enabled = enabled,
    iconSize = iconSize,
    shadowElevation = shadowElevation,
)

/**
 * The same disc for a glyph that lives in a drawable rather than in code — the drawer
 * handle, which the chat header draws from the same resource. Private on purpose: the
 * public API stays the [ImageVector] one every call site already uses.
 */
@Composable
private fun CircleActionButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    shadowElevation: Dp = 2.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Two boxes on purpose. M3's clickable Surface folds its 48dp minimum touch size into
    // the same box that carries the shape, so a 40dp disc asked for inside a 36dp row came
    // back as a 48x36 ellipse with the ripple bound to that box. Here the outer box is the
    // touch target and the inner one is only the drawing: the disc is always [size] round
    // and a parent that cannot spare 48dp costs touch area — never the shape. The click sits
    // on the outer box so the whole 48dp is reachable: on a child box the hit area would
    // shrink back to the disc, which is what the old Surface did wrong in the other
    // direction.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = contentColor,
                    // Unbounded and disc-sized: a bounded ripple inside a 48dp box grows
                    // into the corners and reads as a circle wider than the button, the
                    // artefact this rewrite exists to remove.
                    bounded = false,
                    radius = size / 2,
                ),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    // The press lifts a disc off the surface it sits on. A button that
                    // asked for no shadow has no disc to lift — the back button on a
                    // filled bar is only a glyph — so it stays flat on press instead of
                    // growing a smudge; every filled button keeps the lift.
                    elevation = if (pressed && shadowElevation > 0.dp) shadowElevation + 2.dp else shadowElevation,
                    shape = CircleShape,
                )
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The handle that opens the drawer. Both headers draw it as a circle now — the shape
 * the user's reference shows — so [SuixingTopBar] takes the default too; the rounded
 * square is still accepted for a caller that wants it, and none does today.
 *
 * Public because the chat header is not a [SuixingTopBar]: it is a solid bar of its own.
 */
@Composable
fun DrawerButton(size: Dp = 40.dp, shape: Shape = CircleShape) {
    val open = LocalOpenSidebar.current ?: return
    // One glyph, drawn once: the chat header's handle is this same drawable through
    // FlatPainterButton, so the two headers cannot drift into two hamburgers.
    val handle = painterResource(R.drawable.ic_menu_soft)
    if (shape == CircleShape) {
        CircleActionButton(
            painter = handle,
            contentDescription = "打开侧边栏",
            onClick = open,
            size = size,
            containerColor = handleDisc(),
            contentColor = handleInk(),
            iconSize = 22.dp,
        )
        return
    }
    // The square handle gets the same treatment as the round one: the 48dp box is the
    // clickable itself and the tile is a child of it, so the whole box answers the thumb
    // while the tile keeps its [size]. A Surface(onClick) inside a sized box only reserves
    // 48dp of layout space — its own hit area stays at [size].
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    bounded = false,
                    radius = size / 2,
                ),
                role = Role.Button,
                onClick = open,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = handle,
                    contentDescription = "打开侧边栏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    orbSize: Dp = 128.dp,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SuixingOrb(size = orbSize)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (content != null) {
            Spacer(Modifier.height(22.dp))
            content()
        }
    }
}

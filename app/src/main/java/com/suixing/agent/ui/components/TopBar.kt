package com.suixing.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.R

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
    /**
     * 行内容的起始内边距。默认 8dp（有抽屉手柄的页：手柄自己带视觉重量）；没有手柄、
     * 直接以头像/返回箭头开头的页传 16dp，与页面内容的 16dp 边距对齐（用户：编辑页
     * 「左边距大一点点」）。
     */
    startPadding: Dp = 8.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                // 三档渐隐，不是两档：两档（10% → 透明）在 68dp 的高度里到一半就淡没了，
                // 按钮与标题看着像浮在文字上。18%/6% 用户仍说「淡」——这一栏背后是整页
                // surface，对比只来自 primary 与 surface 的色差，18% 在浅色主题里只有几个
                // 色阶。现在 26%/10% 起步，到栏底才归零，于是这一栏有明确的「底」，又不会
                // 在栏下缘留一条硬边。
                //
                // 没有叠 surfaceContainerLow(0.35) 的平铺：平铺在渐变最淡的栏底仍是满值，
                // 栏底会立刻撞回页面的 surface，正好把这条渐变要消掉的那条硬边又画回来。
                // 「有底」由 26% 的起步和下面那条 1dp 收边一起给，不需要再加一层。
                remember(scheme.primary) {
                    Brush.verticalGradient(
                        0f to scheme.primary.copy(alpha = 0.26f),
                        0.55f to scheme.primary.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                    )
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = startPadding, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // On the left, because the drawer it opens is on the left: a handle on the
            // far side of the header from the panel it reveals reads as a bug even when
            // it works.
            val openSidebar = LocalOpenSidebar.current
            if (openSidebar != null) {
                // The round handle, not the rounded square: the user held the square
                // against a circular reference and read the button as unchanged. Its 44dp
                // is the chat header's own handle size — this bar and that one are the
                // app's top bars, and their handles were 38dp and 44dp side by side.
                DrawerButton(size = 44.dp)
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
                                        // 可点与否只看这一处：原来靠上面的 `interactive` 保证
                                        // 这里的 `!!` 安全，两处一旦不同步就是点击即崩。
                                        onSubtitleClick?.let { tap ->
                                            Modifier
                                                .heightIn(min = 48.dp)
                                                .suixingClickable(MaterialTheme.shapes.small, onClick = tap)
                                                .padding(horizontal = 6.dp)
                                        } ?: Modifier,
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
        // 栏底不再画任何线：发丝线、渐变收边都去掉了——用户明确说「顶栏不要分割线了」。
        // 这一栏的分层改由渐变自己承担（顶部 26% 起步、到栏底归零），栏下缘本来就是软的。
    }
}

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
    // 栏底不画线，也不再铺另一层底色：底色与页面同一个 background（用户原话「顶栏的颜色和
    // 页面颜色一致就行，不用分色块」——这一栏原本用 surfaceContainerLow，于是只有子页顶上
    // 挂着一块看得见的色块）。分层只剩栏下那 12dp 空隙，也不靠阴影（README「不用阴影」）：
    // 阴影在滚动内容上方是一圈灰雾，浅色侧尤其脏。
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // 56 → 64dp：标题 25sp + 副标题 18sp 的行盒只占 43dp，56 的高度把
                    // 两行挤成了紧贴的一坨。min 而非固定高度，字号调到「特大」时这栏
                    // 跟着长，而不是把标题裁掉一半。
                    .heightIn(min = 64.dp)
                    // 12dp 是这一栏自己的「栏内 inset」，不是页面 gutter——页面横向留白全站
                    // 统一 16dp，两者不是同一层，所以这里故意不跟 16dp 走。别当违规改掉。
                    .padding(horizontal = 12.dp)
                    // 内容整体下移：用户原话「顶栏高度保持不变，icon 和标题向下移一点」。
                    // **用 offset 而不是 top padding**：这一行是垂直居中的，padding 只给一半
                    // 位移（P ⇒ P/2），而 P 一大就出事 —— 返回键那枚 disc 的触控盒是
                    // `minimumInteractiveComponentSize()` 的 48dp，48 + P > 64 时这一行会**长高**
                    // （heightIn 只是下限），栏高就跟着变了。P = 10 时净位移只有 5dp，用户看不出
                    // （原话「根本没变化」）。offset 不参与布局 ⇒ 栏高**证明不变**，位移可以给足：
                    // 16dp 之后内容中心从居中的 32dp 落到 48dp（比上一版的 37dp 再 +11dp），
                    // 内容底沿 72dp 仍在栏（64dp）+ 栏下 12dp 空隙之内，不裁、不压到页面内容。
                    .offset(y = 16.dp),
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
                // **结构性对齐**：标题的中心**必然**等于行的垂直中心（也就等于返回箭头 disc 的中心），
                // 与有没有副标题无关。保证它的是两个 modifier，不是任何补偿数字：
                //   · `Box(Modifier.weight(1f))` —— Box 的高度由**最高的子项**决定，而副标题是用
                //     `offset` 挂出去的（offset 不参与布局）⇒ Box 高 = 标题行高（titleMedium 25dp）
                //     ⇒ Box 被 Row 的 `CenterVertically` 居中 ⇒ **Box 中心 = 行中心**；
                //   · 标题 `align(Alignment.CenterStart)` ⇒ 标题在 Box 内居中 ⇒ **标题中心 = Box 中心**。
                //   串起来：标题中心 = 行中心 = disc 中心 ✔（不再依赖任何 ±Ndp 的补偿）。
                // 副标题：同样 CenterStart 居中，再整体下移「两行行高之和的一半」= 两行紧挨时的中心距；
                // 用 LocalDensity 把 sp 行高换算成 dp（`toDp()` 会算上 fontScale）⇒ 字号档变了自动跟随，
                // 不写死 9/18 这种数字。
                //
                // 历史（别再退回补丁）：这里曾用 `.offset(y = if (subtitle != null) 9.dp else 0.dp)`
                // 上下挪整列 —— 那是补偿数字，还写反过一次符号（−9 让标题比不改更歪 18dp）。
                var opticalShift by remember(title, subtitle) { mutableFloatStateOf(0f) }
                Box(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        // 关掉字体自带的上下留白：默认 includeFontPadding 让文字盒比字形盒高，
                        // 文字看起来比返回图标低半像素到一像素（用户：「标题和 icon 没有严格垂直居中」）。
                        style = MaterialTheme.typography.titleMedium.copy(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                includeFontPadding = false,
                            ),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> opticalShift = opticalCenterShift(layout) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            // 字形视觉中心 vs 行盒中心：字体上伸/下伸不对称，居中盒子并不等于居中字。
                            // 用文字自己报上来的 baseline 算差多少，再补回同样的距离 —— 换字号/字体
                            // 自动跟随，不是写死的 ±Ndp。
                            .offset { IntOffset(0, opticalShift.roundToInt()) },
                    )
                    if (subtitle != null) {
                        // 行高**先取成局部 val**：`MaterialTheme.typography` 是 @Composable 读取，而
                        // `with(LocalDensity.current) { … }` 的 lambda 不是 @Composable ⇒ 在块内读它
                        // 编译不过（TextUnit 的类型推断也跟着塌：Unresolved reference 'plus'）。
                        // 读出来再换算就没这个问题；`(titleLine + subLine)` 保持 TextUnit、`/ 2` 也在
                        // TextUnit 上算，最后 `.toDp()`（会算上 fontScale ⇒ 字号档自动跟随）。
                        val titleLine = MaterialTheme.typography.titleMedium.lineHeight
                        val subLine = MaterialTheme.typography.bodySmall.lineHeight
                        // 用 Dp 相加而不是 TextUnit 相加：TextUnit 的 `+` 在类型推断不稳时会被解析成
                        // 歧义候选（本轮就踩到：Unresolved reference 'plus' + 重载歧义）。先各自 toDp()
                        // 再相加，类型是明确的 Dp。toDp() 会算上 fontScale ⇒ 字号档自动跟随。
                        val density = LocalDensity.current
                        val stackShift: Dp = with(density) { titleLine.toDp() + subLine.toDp() } / 2
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(y = stackShift),
                        )
                    }
                }
                actions()
            }
            // The gap every page's first control keeps from the bar — and why it sits *inside*
            // this Surface instead of beside it: Scaffold's topBar slot only takes the tallest
            // child (M3 1.3.1's ScaffoldLayout uses maxByOrNull { it.height }, every child
            // placed at (0,0)), so a Spacer emitted as a sibling adds no height in a Scaffold
            // host — only a Column host (tools, extension) ever saw it. This bar's colour is the
            // page background, so carrying the gap inside is invisible, and now every host gets
            // the same 12dp. 12dp reads as deliberate without wasting a row of content.
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 让文字在行盒里**视觉**居中需要的位移（px，向下为正）。
 *
 * `Text` 的盒高是上伸 + 下伸，而不是字形的实际高度；中文/拉丁混排时字形压在 baseline 之上，
 * 盒子里下伸那一段是空的 ⇒ 盒子居中后字看起来偏高。这里按 baseline 与字高估出字形中心，
 * 与盒中心求差；字号、字体、行高变了都自动跟随，不需要写死补偿值。
 */
private fun opticalCenterShift(layout: TextLayoutResult): Float {
    if (layout.lineCount <= 0) return 0f
    val baseline = layout.getLineBaseline(0)
    // 字形中心 ≈ baseline 往上半个字高。cap height 取 0.7 em（中英文常见值）。
    val em = layout.layoutInput.style.fontSize
    val emPx = if (em.isSp) with(layout.layoutInput.density) { em.toPx() } else em.value
    val glyphCenter = baseline - emPx * 0.35f
    return layout.size.height / 2f - glyphCenter
}

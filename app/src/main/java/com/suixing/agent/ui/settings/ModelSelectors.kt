package com.suixing.agent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.PopupShadowMargin
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog

/** A real read-only form field; its label, padding and container match editable fields exactly. */
/** Both connection selectors open the same quiet, anchor-width native menu. */
/**
 * 贴锚点下方的下拉位置：水平与锚点左缘对齐（放不下就往左收），垂直优先放下方、放不下翻到上方。
 *
 * 坐标是**窗口坐标**（`PopupPositionProvider` 的约定）；`popupContentSize` 收到的是**卡片**
 * 尺寸 —— [MenuCard] 的 `ShadowPopupPositionProvider` 会先把 24dp 阴影余量减掉再传进来。
 */
private class AnchorBelowProvider(private val gapPx: Int) :
    androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - gapPx - height
        val y = when {
            below + height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - height).coerceAtLeast(0)
        }
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/**
 * 模型页两个下拉的浮层容器。
 *
 * **原来用系统的 `DropdownMenu`**：12dp 圆角、系统 elevation 阴影（偏灰、有方向偏移）、
 * 系统自带的展开入场 —— 而全站其它浮层都是 [MenuCard]（20dp 圆角、10dp elevation、
 * 18% 黑的无偏移阴影、统一的 spring 入场）。并排看就是两种质感，用户的原话是
 * 「模型的两个下拉的浮层感觉不好看」。
 *
 * 现在与 [com.suixing.agent.ui.chat.GenerationPicker] 的下拉同款：同一个 `MenuCard`、
 * 同一套阴影与动效，只把内边距换成一档更小的（菜单项自己还带 contentPadding）。
 */
@Composable
private fun ModelDropdownMenu(
    expanded: Boolean, onDismiss: () -> Unit, anchorWidth: Int,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val width = with(density) { anchorWidth.toDp() }
    val gapPx = with(density) { 4.dp.roundToPx() }
    // provider 必须 remember：否则每次重组都是新实例 ⇒ MenuCard 的参数恒变，开着时每帧重跑定位。
    val provider = remember { AnchorBelowProvider(gapPx) }
    /**
     * 阴影余量按可用空间收。
     *
     * 这两个下拉的卡片宽度 = 锚点宽度（几乎满宽：屏幕 385dp − 页面两侧 16dp = 353dp），
     * 而 [MenuCard] 默认每边预留 24dp 阴影余量 ⇒ 窗口要 353 + 48 = **401dp**，屏幕只有
     * 385dp ⇒ Popup 按约束把卡片压到 337dp，**比按钮窄一圈**（用户：「宽度没有对应上下拉
     * 按钮宽度」）。这里把余量收到「屏幕宽 − 锚点宽」的一半，卡片因此能保持与按钮等宽。
     *
     * 上限仍是 24dp：屏幕够宽时（如平板）不缩。
     */
    val availableDp = LocalConfiguration.current.screenWidthDp.dp - width
    // 每边再让 1dp：窗口正好等于屏幕宽时，insets/取整都可能让它差一点放不下。
    val marginDp = (availableDp / 2 - 1.dp).coerceIn(0.dp, PopupShadowMargin)
    MenuCard(
        visible = expanded,
        onDismiss = onDismiss,
        alignment = Alignment.TopStart,
        offset = IntOffset.Zero,
        popupPositionProvider = provider,
        modifier = if (anchorWidth > 0) Modifier.width(width) else Modifier,
        shadowMargin = marginDp,
        // **边界要两件事一起撑**（用户先报「浮层周边和背景混在一起了」，再报「边缘看不太清」）：
        //  1. 底色换一档 —— `BrandLight` 的 `background` 与 `surface` **字面完全相同**
        //     （都 0xFFFFFFFF），MenuCard 默认 `surface` 等于画在页面底色上。改
        //     `surfaceContainerLow`(#F7FAF8) 才有色差，也与下拉按钮的底色一致。
        //  2. 再加 1dp 描边 —— #F7FAF8 对 #FFFFFF 只差约 3% 亮度；而且为宽度把阴影余量
        //     收到了 14.8dp，阴影边缘也可能被裁。描边与下拉按钮自己那条 `outlineVariant` 同款。
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // **滚动必须在卡片内容里、不能挂在 MenuCard 的 modifier 上**：那个 modifier 在
        // `popupCardSurface()`（画背景与阴影）**外层** ⇒ 滚动时背景和阴影会跟着内容一起挪。
        // 放在这里，卡片本体（圆角 + 阴影）固定，只有菜单项滚。
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}


/**
 * 设置页那种「卡片式下拉按钮」：身份方块 + 主标题 + 副标题 + 箭头。
 *
 * **供应商与接口协议共用它**（用户：「模型页的接口协议的按钮也改成和供应商一样的样式」）。
 * 原来接口协议那枚走的是一个 `CompactOutlinedTextField` 锚点，画出来是个**输入框**，
 * 与上面那张供应商卡片完全不是一个东西；现在两者是同一套外观。
 */
@Composable
private fun SelectionCardButton(
    /** 左边的身份方块。 */
    identity: @Composable () -> Unit,
    title: String,
    subtitle: String,
    expanded: Boolean,
    enabled: Boolean,
    /**
     * 无障碍描述。**不能叫 `contentDescription`** —— `semantics { }` 块里那个名字是语义属性
     * 本身，同名会变成「把 val 赋给 val」（编译器报 "val cannot be reassigned"）。
     */
    description: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rotation = animateFloatAsState(if (expanded && enabled) 180f else 0f, tween(160), label = "selectionArrow")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (expanded && enabled) colors.primary else colors.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.38f }.semantics {
            contentDescription = description
            stateDescription = if (expanded && enabled) "已展开" else "已收起"
        },
    ) {
        Row(
            Modifier.heightIn(min = SettingsStyle.ControlHeight).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            identity()
            Column(Modifier.weight(1f)) {
                Text(title, style = SettingsStyle.body, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = SettingsStyle.badge, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_down), null,
                Modifier.size(18.dp).graphicsLayer { rotationZ = rotation.value }, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProviderDropdown(
    providers: List<ProviderConfig>, selected: ProviderConfig?, enabled: Boolean,
    onSelect: (ProviderConfig) -> Unit, onAdd: () -> Unit, onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val rotation = animateFloatAsState(if (open && enabled) 180f else 0f, tween(160), label = "providerArrow")
    val endpoint = remember(selected?.baseUrl) { providerEndpointLabel(selected?.baseUrl.orEmpty()) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).onSizeChanged { anchorWidth = it.width }) {
        SelectionCardButton(
            identity = { ProviderIdentity(selected?.label.orEmpty()) },
            title = selected?.label ?: "添加供应商",
            subtitle = if (selected == null) "连接模型服务或自己的网关" else endpoint,
            expanded = open,
            enabled = enabled,
            description = "供应商：${selected?.label ?: "添加供应商"}",
            onClick = { if (providers.isEmpty()) onAdd() else open = true },
        )
        ModelDropdownMenu(
            expanded = open && enabled, onDismiss = { open = false }, anchorWidth = anchorWidth,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("切换供应商", style = SettingsStyle.metadata, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(enabled = selected != null && providers.size > 1,
                    onClick = { open = false; onDelete() }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_trash), "删除当前供应商", Modifier.size(16.dp),
                        tint = if (selected != null && providers.size > 1) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f))
                }
            }
            providers.forEach { provider ->
                val chosen = selected?.id == provider.id
                val host = remember(provider.baseUrl) { providerEndpointLabel(provider.baseUrl) }
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(provider.label, style = SettingsStyle.body, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host, style = SettingsStyle.description, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { ProviderIdentity(provider.label) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${provider.enabled.size} 模型", style = SettingsStyle.badge, color = colors.onSurfaceVariant)
                            Box(Modifier.size(18.dp)) {
                                if (chosen) Icon(ImageVector.vectorResource(R.drawable.ic_check), "当前供应商", tint = colors.primary)
                            }
                        }
                    },
                    onClick = { open = false; onSelect(provider) },
                    modifier = Modifier.padding(horizontal = 6.dp).clip(MaterialTheme.shapes.small)
                        .background(if (chosen) colors.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = chosen },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            DropdownMenuItem(
                text = { Text("添加供应商", style = SettingsStyle.controlLabel, color = colors.primary) },
                leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_plus), null, Modifier.size(18.dp), tint = colors.primary) },
                onClick = { open = false; onAdd() },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/** Local text marks keep custom endpoints recognisable without downloading vendor artwork. */
@Composable
internal fun ProviderIdentity(name: String) {
    val mark = remember(name) {
        when (name.lowercase()) {
            "openai", "openai-responses" -> "AI"
            "openrouter" -> "OR"
            "deepseek" -> "DS"
            "anthropic" -> "A"
            else -> name.trim().let { if (it.isEmpty()) "+" else String(Character.toChars(it.codePointAt(0))).uppercase() }
        }
    }
    Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        Text(mark, style = SettingsStyle.metadata, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}


/** 协议在身份方块里的名字：协议 id 本身不适合直接当名字（`PROTOCOL_OPENAI` 是空串）。 */
private fun protocolMarkName(protocol: String): String = when (protocol) {
    PROTOCOL_ANTHROPIC -> "anthropic"
    PROTOCOL_RESPONSES -> "openai-responses"
    else -> "openai"
}

/** 协议对应的那种 API（按钮副标题与菜单项共用一处口径）。 */
private fun protocolApiLabel(protocol: String): String = when (protocol) {
    PROTOCOL_ANTHROPIC -> "Messages API"
    PROTOCOL_RESPONSES -> "Responses API"
    else -> "Chat Completions API"
}

@Composable
internal fun CompactProtocolPicker(protocol: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val options = remember { listOf(PROTOCOL_OPENAI to "OpenAI 兼容", PROTOCOL_ANTHROPIC to "Anthropic",
        PROTOCOL_RESPONSES to "OpenAI Responses") }
    val chosen = options.firstOrNull { it.first == protocol } ?: options.first()
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
        // **与供应商同一套外观**（用户：「模型页的接口协议的按钮也改成和供应商一样的样式」）：
        // 原来是一个 `CompactOutlinedTextField` 锚点，画出来是个输入框，
        // 与上面那张供应商卡片完全不是一个东西。
        SelectionCardButton(
            identity = { ProviderIdentity(protocolMarkName(chosen.first)) },
            title = chosen.second,
            subtitle = protocolApiLabel(chosen.first),
            expanded = open,
            enabled = true,
            description = "接口协议：${chosen.second}",
            onClick = { open = true },
        )
        ModelDropdownMenu(expanded = open, onDismiss = { open = false }, anchorWidth = anchorWidth) {
            options.forEach { (value, label) ->
                val isChosen = value == protocol || protocol.isBlank() && value == PROTOCOL_OPENAI
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = SettingsStyle.body,
                                fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal)
                            Text(protocolApiLabel(value), style = SettingsStyle.description, color = colors.onSurfaceVariant)
                        }
                    },
                    trailingIcon = {
                        Box(Modifier.size(18.dp)) {
                            if (isChosen) Icon(ImageVector.vectorResource(R.drawable.ic_check), "当前协议", tint = colors.primary)
                        }
                    },
                    onClick = { open = false; onPick(value) },
                    modifier = Modifier.padding(horizontal = 6.dp).clip(MaterialTheme.shapes.small)
                        .background(if (isChosen) colors.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = isChosen },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun FetchedModelsDialog(provider: ProviderConfig, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var query by remember(provider.id) { mutableStateOf("") }
    val groups = remember(provider.models, query) {
        groupModels(provider.models.distinct().filter { query.isBlank() || it.contains(query.trim(), true) })
    }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型 · ${provider.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactOutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
                Text("已启用 ${provider.enabled.size} 个 · 修改后在页面底部保存", style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (groups.isEmpty()) Text(if (provider.models.isEmpty()) "尚无模型，请先获取或添加自定义模型。" else "没有匹配的模型。",
                    style = SettingsStyle.description)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    groups.forEach { (group, ids) ->
                        item(key = "group:$group", contentType = "model-group") {
                            Text(group, style = SettingsStyle.badge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(ids, key = { "model:$it" }, contentType = { "model-choice" }) { id ->
                            Surface(onClick = { onToggle(id, id !in provider.enabled) }, shape = MaterialTheme.shapes.small,
                                color = if (id in provider.enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                                    else androidx.compose.ui.graphics.Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = id in provider.enabled, onCheckedChange = { onToggle(id, it) })
                                    Text(provider.displayModel(id), style = SettingsStyle.body, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("完成") } },
    )
}

package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Shared roles for settings and its destination pages; typography follows the app font scale. */
object SettingsStyle {
    val PageMargin = 16.dp
    val CardPadding = 16.dp
    val SectionGap = 12.dp
    val RowGap = 4.dp
    val RowPadding = 12.dp
    val ControlGap = 12.dp
    val FieldGap = 8.dp
    // 分组标题上下的呼吸：8dp 贴得略紧（用户：「上下的间距再大一点点」）。
    // 各加 2dp（10/10）；行内与卡片内边距一概不动。
    val SectionTop = 10.dp
    val SectionBottom = 10.dp
    val SectionIndent = 6.dp

    val rowTitle: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge
    val description: TextStyle @Composable get() = MaterialTheme.typography.bodySmall
    val metadata: TextStyle @Composable get() = MaterialTheme.typography.labelMedium
    val sectionTitle: TextStyle @Composable get() = MaterialTheme.typography.labelLarge
    val cardTitle: TextStyle @Composable get() = MaterialTheme.typography.titleMedium
    val fieldText: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium
    val body: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium
    val badge: TextStyle @Composable get() = MaterialTheme.typography.labelSmall
    val controlLabel: TextStyle @Composable get() = MaterialTheme.typography.labelLarge
}

/** PageTopBar owns the top gap. Keep navigation-bar clearance inside the scroll content. */
@Composable
fun settingsPagePadding(): PaddingValues = PaddingValues(
    start = SettingsStyle.PageMargin,
    end = SettingsStyle.PageMargin,
    top = 0.dp,
    bottom = navBarContentPadding().calculateBottomPadding(),
)

@Composable
fun SettingSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = SettingsStyle.SectionIndent,
        top = SettingsStyle.SectionTop,
        bottom = SettingsStyle.SectionBottom,
    ),
) {
    Text(
        text = text,
        style = SettingsStyle.sectionTitle,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(contentPadding),
    )
}

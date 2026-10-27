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
    val SectionTop = 12.dp
    val SectionBottom = 8.dp

    val rowTitle: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge
    val description: TextStyle @Composable get() = MaterialTheme.typography.bodySmall
    val metadata: TextStyle @Composable get() = MaterialTheme.typography.labelMedium
    val sectionTitle: TextStyle @Composable get() = MaterialTheme.typography.titleSmall
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
fun SettingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SettingsStyle.sectionTitle,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = SettingsStyle.SectionTop, bottom = SettingsStyle.SectionBottom),
    )
}

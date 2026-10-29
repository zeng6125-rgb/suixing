package com.suixing.agent.ui.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.SettingsStyle

/**
 * 「从链接导入」: one URL in, one skill out.
 *
 * The dialog checks only the scheme, which is the same check the import itself makes, so a
 * mistyped address is answered here instead of after a download. Everything else — the
 * ceilings, the folder name, the switched-off default — belongs to `SkillStore.importUrl`.
 */
@Composable
internal fun SkillUrlImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val usable = url.trim().startsWith("http://") || url.trim().startsWith("https://")

    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入技能") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "填一个 SKILL.md 的地址（http 或 https）。导入后技能默认关闭，" +
                        "看过内容再打开开关，它才会进入模型的技能目录。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SettingsStyle.SectionGap))
                OutlinedTextField(
                    textStyle = SettingsStyle.fieldText,
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("技能文件地址") },
                    placeholder = { Text("https://example.com/SKILL.md") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
                if (url.isNotBlank() && !usable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "链接必须以 http:// 或 https:// 开头",
                        style = SettingsStyle.badge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { scope ->
            TextButton(onClick = { scope.closeThen { onImport(url.trim()) } }, enabled = usable) { Text("导入") }
        },
        dismissButton = { scope ->
            TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") }
        },
    )
}

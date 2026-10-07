package com.lingshu.agent.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class Entry(val file: File, val subtitle: String)

@Composable
fun FilesScreen() {
    val root = remember { Core.appContext.getExternalFilesDir(null) ?: Core.appContext.filesDir }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Directory listing and per-entry sizes are disk IO, so they run off the main
    // thread and land in state; doing it inside composition stutters the list.
    LaunchedEffect(current) {
        listing = true
        val children = withContext(Dispatchers.IO) {
            current.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        if (children == null) {
            listError = "无法读取该目录，可能缺少「所有文件访问权限」"
            entries = emptyList()
        } else {
            listError = null
            entries = withContext(Dispatchers.IO) {
                children.map { file ->
                    Entry(file, if (file.isDirectory) "文件夹" else humanSize(file.length()))
                }
            }
        }
        listing = false
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("工作目录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = current.absolutePath.removePrefix(root.parentFile?.absolutePath.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这里是灵枢的默认工作目录，模型用相对路径读写的文件都在这里。" +
                    "授予「所有文件访问权限」后，也可以直接访问 /sdcard。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current != root) {
                TextButton(onClick = { current = current.parentFile ?: root }) { Text("上一级") }
            }
        }

        if (listing || loading) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        listError?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.file.absolutePath }) { entry ->
                val file = entry.file
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                current = file
                            } else {
                                scope.launch {
                                    loading = true
                                    val text = withContext(Dispatchers.IO) { readPreview(file) }
                                    preview = file.name to text
                                    loading = false
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    preview?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
        )
    }
}

private fun readPreview(file: File): String {
    if (file.length() > MAX_PREVIEW_BYTES) return "（文件超过 ${humanSize(MAX_PREVIEW_BYTES)}，不在预览范围内）"
    return runCatching {
        val bytes = file.readBytes()
        if (bytes.take(4096).contains(0)) "（二进制文件）" else String(bytes, Charsets.UTF_8)
    }.getOrElse { "（读取失败：${it.message ?: it::class.java.simpleName}）" }
}

/** Kept small: every byte ends up in a single Text, and a big blob freezes layout. */
private const val MAX_PREVIEW_BYTES = 20L * 1024L

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

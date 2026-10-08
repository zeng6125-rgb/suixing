package com.suixing.agent.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.tools.humanSize
import com.suixing.agent.tools.workspaceDir
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Entry(val file: File, val size: String, val modified: String)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val root = remember { workspaceDir(context) }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Pair<File, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }

    // Directory listing and per-entry sizes are disk IO, so they run off the main
    // thread and land in state; doing it inside composition stutters the list.
    LaunchedEffect(current, reload) {
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
                    Entry(
                        file = file,
                        size = if (file.isDirectory) "文件夹" else humanSize(file.length()),
                        modified = timeFormat.format(Date(file.lastModified())),
                    )
                }
            }
        }
        listing = false
    }

    Column(Modifier.fillMaxSize()) {
        SuixingTopBar(
            title = "文件",
            subtitle = relativePath(root, current),
            actions = {
                IconButton(onClick = { reload++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                }
                if (current != root) {
                    IconButton(onClick = { current = current.parentFile ?: root }) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "上一级", modifier = Modifier.size(20.dp))
                    }
                }
            },
        )

        Breadcrumbs(root = root, current = current) { current = it }

        Spacer(Modifier.height(8.dp))

        if (current == root) {
            Text(
                text = "这里是随行的默认工作目录，模型用相对路径读写的文件都在这里。" +
                    "授予「所有文件访问权限」后，也可以直接访问 /sdcard。",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (listing || loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
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

        if (!listing && entries.isEmpty() && listError == null) {
            Text(
                text = "这个目录是空的",
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.file.absolutePath }) { entry ->
                EntryRow(entry) {
                    if (entry.file.isDirectory) {
                        current = entry.file
                    } else {
                        scope.launch {
                            loading = true
                            val text = withContext(Dispatchers.IO) { readPreview(entry.file) }
                            preview = entry.file to text
                            loading = false
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    preview?.let { (file, content) ->
        PreviewDialog(
            file = file,
            content = content,
            onDismiss = { preview = null },
            onCopy = { copyToClipboard(context, content) },
        )
    }
}

/** Path segments as tappable chips, so any ancestor is one tap away. */
@Composable
private fun Breadcrumbs(root: File, current: File, onJump: (File) -> Unit) {
    val segments = remember(root, current) {
        val tail = ArrayDeque<File>()
        var walk = current
        while (walk != root && walk.parentFile != null) {
            tail.addFirst(walk)
            walk = walk.parentFile!!
        }
        buildList {
            add("工作目录" to root)
            tail.forEach { add(it.name to it) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, file) ->
            val last = index == segments.lastIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (last) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onJump(file) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    color = if (last) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!last) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, onClick: () -> Unit) {
    val (icon, tint) = iconFor(entry.file)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftIcon(icon, size = 36.dp, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.size} · ${entry.modified}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.file.isDirectory) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PreviewDialog(file: File, content: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = humanSize(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    text = content,
                    modifier = Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MonoStyle,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制")
            }
        },
    )
}

/** Extension-driven icon and colour, so the list is scannable without reading names. */
private fun iconFor(file: File): Pair<ImageVector, Color> {
    if (file.isDirectory) return Icons.Rounded.Folder to Color(0xFFAD7BF9)
    return when (file.extension.lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> Icons.Rounded.Image to Color(0xFF89C8AF)
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "rs", "go", "sh",
        "json", "yml", "yaml", "xml", "toml", "gradle", "html", "css", "sql",
        -> Icons.Rounded.Code to Color(0xFF7FB3E8)
        "md", "txt", "log", "csv", "ini", "conf", "properties" -> Icons.Rounded.Description to Color(0xFFB9B4AA)
        "zip", "tar", "gz", "rar", "7z", "apk", "jar" -> Icons.Rounded.FolderZip to Color(0xFFF0C070)
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> Icons.Rounded.AudioFile to Color(0xFFD1A3E8)
        "mp4", "mkv", "mov", "avi", "webm" -> Icons.Rounded.Movie to Color(0xFFE89A9A)
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to Color(0xFF9A958C)
    }
}

private fun relativePath(root: File, current: File): String {
    if (current == root) return "工作目录"
    return current.absolutePath.removePrefix(root.absolutePath).trimStart('/')
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Suixing", text))
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

package com.suixing.agent.ui.chat

import android.content.ActivityNotFoundException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.suixing.agent.R
import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import com.suixing.agent.tools.humanSize
import com.suixing.agent.ui.components.MenuRippleSurface
import com.suixing.agent.ui.components.MenuRippleTextButton
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.files.LocalFilePreview
import com.suixing.agent.ui.files.launchDeliveryFile
import com.suixing.agent.ui.files.loadFilePreview
import com.suixing.agent.ui.files.prepareDeliveryFile
import com.suixing.agent.ui.theme.MonoStyle
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private enum class FileCardAction { PREVIEW, OPEN, SHARE }
private data class FileCardTotals(val title: String, val lineSummary: String?)

/** Only tool-reported metadata is rendered here. Every file read starts with an explicit click. */
@Composable
internal fun ChatFileActivity(activities: List<FileActivity>, modifier: Modifier = Modifier) {
    if (activities.isEmpty()) return
    val totals = remember(IdentityKey(activities)) { fileCardTotals(activities) }
    var expanded by rememberSaveable {
        mutableStateOf(activities.any { it.kind == FileActivityKind.DELIVERED || it.kind == FileActivityKind.DOWNLOADED })
    }
    var preview by remember { mutableStateOf<LocalFilePreview?>(null) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyPath = rememberCopyToClipboard()
    val request = remember { intArrayOf(0) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    fun act(path: String, action: FileCardAction) {
        actionJob?.cancel()
        val mine = ++request[0]
        busyPath = path
        notice = ""
        actionJob = scope.launch {
            try {
                if (action == FileCardAction.PREVIEW) {
                    val result = loadFilePreview(context, path)
                    ensureActive()
                    if (mine == request[0]) preview = result
                } else {
                    val file = prepareDeliveryFile(context, path)
                    ensureActive()
                    if (mine == request[0]) launchDeliveryFile(context, file, share = action == FileCardAction.SHARE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                if (mine == request[0]) notice = "没有可打开此文件的应用，可预览或分享给其它应用"
            } catch (_: SecurityException) {
                if (mine == request[0]) notice = "当前无权读取或打开这个文件"
            } catch (failure: Exception) {
                if (mine == request[0]) notice = failure.message ?: "文件操作失败，请重试"
            } finally {
                if (mine == request[0]) busyPath = null
            }
        }
    }
    val shape = MaterialTheme.shapes.medium
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        MenuRippleSurface(
            onClick = { expanded = !expanded },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().border(1.dp, outline, shape),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_file), contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(totals.title, style = MaterialTheme.typography.labelLarge)
                    totals.lineSummary?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(ImageVector.vectorResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                    contentDescription = if (expanded) "收起文件" else "展开文件", modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(activities, key = { it.path }, contentType = { "file-activity" }) { file ->
                    ChatFileActivityEntry(file, busy = busyPath == file.path,
                        enabled = busyPath == null,
                        onPreview = { act(file.path, FileCardAction.PREVIEW) },
                        onOpen = { act(file.path, FileCardAction.OPEN) },
                        onShare = { act(file.path, FileCardAction.SHARE) },
                        onCopy = { copyPath(file.path) })
                }
                if (totals.lineSummary != null) item(key = "line-count-note") {
                    Text("多次编辑的替换行数累计显示", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, modifier = Modifier.padding(top = 6.dp), style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.error)
    }
    preview?.let { value ->
        ChatFilePreviewDialog(value,
            onDismiss = { preview = null },
            onCopy = { copyPath(value.path) },
            onOpen = { preview = null; act(value.path, FileCardAction.OPEN) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatFileActivityEntry(
    file: FileActivity,
    busy: Boolean,
    enabled: Boolean,
    onPreview: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val name = remember(file.path) { file.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { file.path } }
    val type = remember(name) { name.substringAfterLast('.', "").uppercase(Locale.ROOT).ifBlank { "文件" } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val metadata = remember(file) { buildList {
            add(type)
            file.sizeBytes?.let { add(humanSize(it)) }
            add(when (file.kind) {
                FileActivityKind.CREATED -> "已新建"
                FileActivityKind.MODIFIED -> "已修改"
                FileActivityKind.DOWNLOADED -> "已下载"
                FileActivityKind.DELIVERED -> "可查看"
            })
            file.addedLines?.let { add("+$it") }
            file.removedLines?.let { add("−$it") }
        }.joinToString(" · ") }
        Text(metadata, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (file.description.isNotBlank()) Text(file.description, style = SettingsStyle.description,
            modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (busy) Text("正在准备文件…", style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            MenuRippleTextButton(onClick = onPreview, enabled = enabled) { Text("预览", style = MaterialTheme.typography.labelMedium) }
            MenuRippleTextButton(onClick = onOpen, enabled = enabled) { Text("打开", style = MaterialTheme.typography.labelMedium) }
            MenuRippleTextButton(onClick = onShare, enabled = enabled) { Text("分享", style = MaterialTheme.typography.labelMedium) }
            MenuRippleTextButton(onClick = onCopy) { Text("复制路径", style = MaterialTheme.typography.labelMedium) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ChatFilePreviewDialog(preview: LocalFilePreview, onDismiss: () -> Unit, onCopy: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(preview.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(preview.size, style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            if (preview.imagePath != null) {
                val request = remember(context, preview.imageKey) {
                    ImageRequest.Builder(context).data(File(preview.imagePath)).memoryCacheKey(preview.imageKey).build()
                }
                AsyncImage(request, contentDescription = preview.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(300.dp))
            } else SelectionContainer {
                Text(preview.content, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()), style = MonoStyle)
            }
        },
        confirmButton = { dialog -> MenuRippleTextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("关闭") } },
        dismissButton = { dialog ->
            Row {
                MenuRippleTextButton(onClick = onCopy) { Text("复制路径") }
                MenuRippleTextButton(onClick = { dialog.closeThen(onOpen) }) { Text("打开") }
            }
        },
    )
}

private fun fileCardTotals(files: List<FileActivity>): FileCardTotals {
    val changes = files.filter { it.kind == FileActivityKind.CREATED || it.kind == FileActivityKind.MODIFIED }
    val downloaded = files.count { it.kind == FileActivityKind.DOWNLOADED }
    val title = when {
        changes.size == files.size -> "${files.size} 个文件已更改"
        downloaded == files.size -> "${files.size} 个文件已下载"
        changes.isNotEmpty() -> "${files.size} 个文件 · ${changes.size} 个已更改"
        else -> "${files.size} 个文件可查看"
    }
    val added = changes.mapNotNull { it.addedLines }
    val removed = changes.mapNotNull { it.removedLines }
    val lineSummary = if (added.isEmpty() && removed.isEmpty()) null else buildString {
        append(if (added.size < changes.size || removed.size < changes.size) "已知累计替换行" else "累计替换行")
        if (added.isNotEmpty()) append(" · +").append(added.sumOf { it.toLong() })
        if (removed.isNotEmpty()) append(" · −").append(removed.sumOf { it.toLong() })
    }
    return FileCardTotals(title, lineSummary)
}

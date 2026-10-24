package com.suixing.agent.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.capability.allFilesAccessIntents
import com.suixing.agent.tools.humanSize
import com.suixing.agent.tools.workspaceDir
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.theme.FileTypeColors
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
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Two roots, and the app's own working directory stays the default: it is where the model
    // reads and writes by relative path. /sdcard is the second one, and it only opens after the
    // user grants 「所有文件访问权限」 — without it the listing above it is invisible to us.
    val appRoot = remember { workspaceDir(context) }
    val externalRoot = remember { Environment.getExternalStorageDirectory() }
    // 第三个根：插件目录（filesDir/plugins）。它在应用私有目录里，**文件页本来就能读**，
    // 不需要「所有文件访问权限」—— 那条闸门只属于 /sdcard。
    val pluginRoot = remember { File(context.filesDir, "plugins") }
    val supportsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    var allFilesGranted by remember {
        mutableStateOf(supportsAllFiles && Environment.isExternalStorageManager())
    }
    var root by remember { mutableStateOf(appRoot) }
    var current by remember { mutableStateOf(root) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rootMenu by remember { mutableStateOf(false) }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesGranted = supportsAllFiles && Environment.isExternalStorageManager()
        // Coming back with the grant lands straight in /sdcard: that is what the button was for.
        if (allFilesGranted) {
            root = externalRoot
            current = externalRoot
        }
    }
    // 三个根了，所以标签要认三支；`when` 而不是二选一，免得第四个根再加时又漏一个。
    val rootLabel = when (root) {
        appRoot -> "工作目录"
        externalRoot -> "/sdcard"
        else -> "插件目录"
    }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Pair<File, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    // 预览读取是异步的，连点两个文件会起两个协程；只认最后一次点击，否则先点的那次
    // 晚回来会把弹窗改成不是用户最后选的文件。
    var previewRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Directory listing and per-entry sizes are disk IO, so they run off the main
    // thread and land in state; doing it inside composition stutters the list.
    LaunchedEffect(current, reload) {
        listing = true
        val children = withContext(Dispatchers.IO) {
            // 先取排序键再排序：`isDirectory` 是一次 stat、`name.lowercase()` 是一次分配，
            // 放在比较器里会变成 O(n log n) 次 syscall（1000 个文件约 1 万次）。
            // sortedWith 是稳定排序，键与顺序都和原来逐项相同。
            current.listFiles()
                ?.map { Triple(it, !it.isDirectory, it.name.lowercase()) }
                ?.sortedWith(compareBy({ it.second }, { it.third }))
                ?.map { it.first }
        }
        if (children == null) {
            listError = "无法读取该目录，可能缺少「所有文件访问权限」"
            entries = emptyList()
        } else {
            listError = null
            entries = withContext(Dispatchers.IO) {
                // 每次列目录现建：SimpleDateFormat 不是线程安全的，切目录时上一轮 IO
                // 还没跑完（map/format 之间没有挂起点，取消拦不住它），共用一份会写坏结果。
                val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
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

    // 根节点必须自己铺不透明底：转场里进页是**不淡入**的（见 SuixingApp 顶部那段），这一页
    // 若透出外壳的底，进页的那一帧就会看到上一页的残影。
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        PageTopBar(
            title = "文件",
            // 副标题（当前路径）去掉：顶栏不带副标题，而**面包屑就在下面**（同一屏可见），
            // 路径信息一点没丢。
            onBack = onBack,
            actions = {
                // 三个根 ⇒ 菜单，而不是继续循环。二态时那颗按钮的语义是"切到另一个"，一眼就懂；
                // 三态之后循环按钮必须用文案说明"下一次点会去哪"，而其中一支还带权限闸门（/sdcard），
                // 一个按钮承担不了这些。菜单把三个根一次列全，被闸门挡住的那支也能就地说明。
                Box {
                    IconButton(onClick = { rootMenu = true }) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_folder_open),
                            contentDescription = "切换目录根（当前：$rootLabel）",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("工作目录") },
                            onClick = {
                                rootMenu = false
                                root = appRoot
                                current = appRoot
                                notice = null
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (allFilesGranted) "/sdcard" else "/sdcard（需要所有文件访问权限）")
                            },
                            onClick = {
                                rootMenu = false
                                if (allFilesGranted) {
                                    root = externalRoot
                                    current = externalRoot
                                    notice = null
                                } else {
                                    // The grant lives in the system settings app, so it is reached
                                    // through the launcher and re-read on the way back. Only a ROM
                                    // without either page falls through to the message.
                                    val opened = allFilesAccessIntents(context).any { intent ->
                                        runCatching { allFilesLauncher.launch(intent) }.isSuccess
                                    }
                                    if (!opened) {
                                        notice = "本机打不开「所有文件访问权限」设置，可以改用 Shizuku 或 root 拿全盘访问。"
                                    }
                                }
                            },
                        )
                        // 插件目录没有闸门：filesDir 是应用自己的目录，文件页本来就能读。
                        DropdownMenuItem(
                            text = { Text("插件目录") },
                            onClick = {
                                rootMenu = false
                                // 先确保它存在。目录还没建过时 listFiles() 返回 null，而那条
                                // 失败文案说的是「可能缺少所有文件访问权限」—— 对一个我们自己拥有的
                                // 私有目录，那是误导。PluginStore 自己也是先 mkdirs() 再列。
                                pluginRoot.mkdirs()
                                root = pluginRoot
                                current = pluginRoot
                                notice = null
                            },
                        )
                    }
                }
                IconButton(onClick = { reload++ }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_rotate_cw), contentDescription = "刷新", modifier = Modifier.size(20.dp))
                }
                if (current != root) {
                    // 夹在当前根之内：/sdcard 的上一级是 /storage/emulated，走出去之后面包屑就
                    // 不是一条祖先链了。
                    IconButton(
                        onClick = {
                            current = current.parentFile
                                ?.takeIf { it.absolutePath.startsWith(root.absolutePath) }
                                ?: root
                        },
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_arrow_up), contentDescription = "上一级", modifier = Modifier.size(20.dp))
                    }
                }
            },
        )

        Breadcrumbs(root = root, current = current, rootLabel = rootLabel) { current = it }

        Spacer(Modifier.height(8.dp))

        if (current == root) {
            Text(
                text = when (root) {
                    appRoot -> if (allFilesGranted) {
                        "这里是随行的默认工作目录，模型用相对路径读写的文件都在这里。" +
                            "顶栏的文件夹按钮可以切到 /sdcard。"
                    } else {
                        "这里是随行的默认工作目录，模型用相对路径读写的文件都在这里。" +
                            "顶栏的文件夹按钮会带你去开「所有文件访问权限」，开完就能看 /sdcard。"
                    }

                    externalRoot ->
                        "正在浏览 /sdcard：共享存储里的文件都能进。顶栏的文件夹按钮可以回到应用目录。"

                    // 只读的说明写在这里：这个根是给"看"用的，插件由插件页管理，免得两条写入路径。
                    else ->
                        "这是已安装插件所在的目录（应用私有）。每个插件一个子目录，里面的 manifest.json " +
                            "就是它的说明书；这里只能查看，装卸与开关都在「工具 → 插件」页。"
                },
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

        notice?.let { message ->
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
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(
                entries,
                key = { it.file.absolutePath },
                contentType = { "file-row" },
            ) { entry ->
                EntryRow(entry) {
                    if (entry.file.isDirectory) {
                        current = entry.file
                    } else {
                        val mine = ++previewRequest
                        scope.launch {
                            loading = true
                            val text = withContext(Dispatchers.IO) { readPreview(entry.file) }
                            if (mine == previewRequest) preview = entry.file to text
                            loading = false
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    preview?.let { (file, content) ->
        PreviewDialog(
            file = file,
            content = content,
            onDismiss = { preview = null },
            // 复制按钮不关弹窗，而 notice 渲染在弹窗下面的页面里，此刻看不见；
            // 所以失败必须用 Toast 当场告诉用户，notice 只是关窗后还留着的痕迹。
            onCopy = {
                if (!copyToClipboard(context, content)) {
                    val message = "复制失败：系统未允许写入剪贴板"
                    notice = message
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/** Path segments as tappable chips, so any ancestor is one tap away. */
@Composable
private fun Breadcrumbs(root: File, current: File, rootLabel: String, onJump: (File) -> Unit) {
    val segments = remember(root, current) {
        val tail = ArrayDeque<File>()
        var walk = current
        while (walk != root) {
            val parent = walk.parentFile ?: break
            tail.addFirst(walk)
            walk = parent
        }
        buildList {
            add(rootLabel to root)
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
                    ImageVector.vectorResource(R.drawable.ic_chevron_right),
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
    // 行本身只有一份实现（Common.SettingRow）。这一页的行不在卡片里，所以横向 16dp
    // 由这里给（SettingRow 自己不加横向 padding）；纵向 12dp、标题 bodyLarge、
    // 副标题 bodySmall 都是全站统一值。
    SettingRow(
        title = entry.file.name,
        subtitle = "${entry.size} · ${entry.modified}",
        leading = { SoftIcon(icon, size = 36.dp, tint = tint) },
        control = if (entry.file.isDirectory) ({ RowChevron() }) else null,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp),
        titleMaxLines = 1,
    )
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
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MonoStyle,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = onCopy) {
                Icon(ImageVector.vectorResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制")
            }
        },
    )
}

/** Extension-driven icon and colour, so the list is scannable without reading names. */
@Composable
private fun iconFor(file: File): Pair<ImageVector, Color> {
    if (file.isDirectory) return ImageVector.vectorResource(R.drawable.ic_folder) to FileTypeColors.folder
    return when (file.extension.lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> ImageVector.vectorResource(R.drawable.ic_image) to FileTypeColors.image
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "rs", "go", "sh",
        "json", "yml", "yaml", "xml", "toml", "gradle", "html", "css", "sql",
        -> ImageVector.vectorResource(R.drawable.ic_file_code) to FileTypeColors.code
        "md", "txt", "log", "csv", "ini", "conf", "properties" -> ImageVector.vectorResource(R.drawable.ic_file_text) to FileTypeColors.text
        "zip", "tar", "gz", "rar", "7z", "apk", "jar" -> ImageVector.vectorResource(R.drawable.ic_file_archive) to FileTypeColors.archive
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> ImageVector.vectorResource(R.drawable.ic_music) to FileTypeColors.audio
        "mp4", "mkv", "mov", "avi", "webm" -> ImageVector.vectorResource(R.drawable.ic_film) to FileTypeColors.video
        else -> ImageVector.vectorResource(R.drawable.ic_file) to FileTypeColors.other
    }
}

private fun copyToClipboard(context: Context, text: String): Boolean =
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Suixing", text))
    }.isSuccess

private fun readPreview(file: File): String {
    if (file.length() > MAX_PREVIEW_BYTES) return "（文件超过 ${humanSize(MAX_PREVIEW_BYTES)}，不在预览范围内）"
    return runCatching {
        val bytes = file.readBytes()
        if (bytes.take(4096).contains(0)) "（二进制文件）" else String(bytes, Charsets.UTF_8)
    }.getOrElse { "（读取失败：${it.message ?: it::class.java.simpleName}）" }
}

/** Kept small: every byte ends up in a single Text, and a big blob freezes layout. */
private const val MAX_PREVIEW_BYTES = 20L * 1024L

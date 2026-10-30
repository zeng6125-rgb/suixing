package com.suixing.agent.ui.assistant

import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.settingsPagePadding
import android.graphics.Bitmap
// Android 的 Color（`ColorDrawable(Color.TRANSPARENT)` 用它），不是 Compose 的
// androidx.compose.ui.graphics.Color —— 两个同名类，别混。
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.suixing.agent.ui.components.SettingsSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R
import com.suixing.agent.data.ASSISTANT_AVATARS
import com.suixing.agent.data.AppSettings
import com.suixing.agent.ui.chat.IdentityKey
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.AssistantAvatarStore
import com.suixing.agent.data.DEFAULT_SYSTEM_PROMPT
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SettingRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job

/**
 * 对话框窗口里「系统栏到底占多高」。
 *
 * `decorFitsSystemWindows = false` 让窗口铺满屏幕，inset 就该由内容自己吃 —— 但 **Dialog 自己的
 * 窗口**在不少 ROM 上并不把 inset 分发给 Compose：`WindowInsets.statusBars` 读出来是 0，于是顶栏
 * 里那句 `statusBarsPadding()`（Common.kt:415）等于没加，内容就压到状态栏底下 —— 三层里**吃掉的
 * 是第三层**（窗口没把 inset 送到内容，而不是 Scaffold 或 DialogProperties 的错，那两处都已到位）。
 * 这里在 inset 报 0 时退回平台自己的 `status_bar_height` / `navigation_bar_height`：inset 可用的
 * ROM 走 inset（不重复加），不可用的 ROM 也能对齐。
 */
@Composable
internal fun dialogSystemBarPad(bottom: Boolean = false): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val insetPx = if (bottom) {
        WindowInsets.navigationBars.getBottom(density)
    } else {
        WindowInsets.statusBars.getTop(density)
    }
    if (insetPx > 0) return with(density) { insetPx.toDp() }
    val name = if (bottom) "navigation_bar_height" else "status_bar_height"
    val fallbackPx = remember(context, name) {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    return with(density) { fallbackPx.toDp() }
}

/**
 * Create or edit one persona.
 *
 * A full-screen dialog rather than a route, so the chat screen never loses its
 * scroll position while the user fiddles with a prompt.
 */
@Composable
fun AssistantEditor(
    initial: Assistant,
    settings: AppSettings,
    toolNames: List<String>,
    onSave: (Assistant) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * BACK 是否归这一页收。覆盖层退场的那 200ms 里本页仍在组合（AnimatedVisibility 的语义），
     * 那时 BACK 不该再被它吃掉一次 —— 宿主在退场期间传 false。
     */
    backEnabled: Boolean = true,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val isNew = settings.assistant(initial.id) == null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Set when picking a picture fails; cleared on the next successful pick. */
    var notice by remember { mutableStateOf<String?>(null) }
    /** The decoded picture waiting to be framed; null when the crop screen is not up. */
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    // 删除是不可撤销的：它会把助手连同提示词和头像 PNG 一起从设置里抹掉，所以先问一次。
    var confirmingDelete by remember { mutableStateOf(false) }
    // The picture is written the moment the crop is confirmed, not on save: the pick arrives as a
    // content:// uri that is only readable while the picker's grant lasts, and the draft holds the
    // path to the file we made out of it. A pick that fails leaves the old picture alone.
    //
    // 「换一张」 goes through the crop screen first: the pick is decoded (sampled, never the whole
    // camera roll), the user frames it in the circle, and only 「确定」 writes a file. 「取消」
    // writes nothing at all — the decoded bitmap is the only thing this step holds.
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val source = AssistantAvatarStore.decodeForCrop(context, uri)
                if (source == null) {
                    // A pick can fail (huge picture, revoked grant, file already gone). The old
                    // picture is left alone, so without this line the tap would look ignored.
                    notice = "这张图没能打开，头像保持原样。可以换一张，或直接用下面的表情。"
                } else {
                    notice = null
                    cropping = source
                }
            }
        }
    }

    // 结构路（0.8.105）：编辑页**不再是 Dialog 窗口**，而是主窗口里的全屏一层。Dialog 窗口在这台
    // 设备上四道保险丝（decorFits / 清背景 / MATCH_PARENT / 状态栏色透明）全部实测无效，状态栏那
    // 一条始终不对；主窗口的沉浸行为是真机验证过正确的（聊天页/设置页都对）。宿主决定它画在哪：
    // 设置页就地画，聊天页画在根覆盖层（见 AssistantSheet 的 editorHost）。BACK 由 BackHandler 收。
    Box(Modifier.fillMaxSize()) {
        BackHandler(enabled = backEnabled) { onDismiss() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                // 系统栏内边距交给顶栏自己吃（SuixingTopBar 内部就是 statusBarsPadding()），
                // Scaffold 不再算一份：默认的 contentWindowInsets 会把这套 inset 消费掉，顶栏那句
                // 便读成 0，于是窗口铺到状态栏了、内容却没让开——用户看到的「状态栏不沉浸」。
                // 与设置页各页一致（SettingsScreen 里每页都是 WindowInsets(0,0,0,0)）。
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // The shell's drawer handle rides on SuixingTopBar and the composition
                    // local follows the composition into this dialog's window — but the
                    // drawer itself is painted behind the dialog, so the handle was a dead
                    // tap. A dialog is a place with no sidebar: a null local says exactly
                    // that.
                    CompositionLocalProvider(LocalOpenSidebar provides null) {
                        // 主窗口里 inset 是真分发的，SuixingTopBar 自己那句 statusBarsPadding()
                        // （Common.kt:416）就够；Dialog 时代那道 dialogSystemBarPad 补垫已删，
                        // 留着会把顶栏往下推两遍状态栏高度。
                        SuixingTopBar(
                            title = if (isNew) "新建助手" else "编辑助手",
                            subtitle = draft.label,
                            // 没有抽屉手柄的页：头像直接顶到 8dp 会显得比别的页更贴边，
                            // 与页面内容对齐到 16dp（用户：「左边距大一点点」）。
                            startPadding = 16.dp,
                            leading = {
                                AssistantAvatar(
                                    face = draft.face,
                                    avatarPath = draft.avatarPath,
                                    size = 38.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                )
                            },
                            actions = {
                                TextButton(onClick = { onSave(draft) }) { Text("保存") }
                            },
                        )
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(settingsPagePadding())
                        .padding(top = SettingsStyle.SectionGap),
                    verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
                ) {
                    IdentitySection(
                        draft = draft,
                        onDraft = { draft = it },
                        onPickPicture = {
                            pickPicture.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onClearPicture = {
                            // Explicitly "remove my picture", so the file goes with the path:
                            // leaving it would keep a photo the user asked to be rid of, and the
                            // id sweep on delete is not what this is.
                            val previous = draft.avatarPath
                            draft = draft.copy(avatarPath = "")
                            scope.launch { AssistantAvatarStore.delete(context, previous) }
                        },
                        onPickEmoji = { emoji ->
                            // An emoji tap means "this is my face": it takes the picture's place
                            // instead of being hidden behind it, which is what a picture that
                            // wins over the grid would have looked like.
                            val previous = draft.avatarPath
                            draft = draft.copy(avatar = emoji, avatarPath = "")
                            if (previous.isNotBlank()) {
                                scope.launch { AssistantAvatarStore.delete(context, previous) }
                            }
                        },
                    )
                    PromptSection(draft = draft, onDraft = { draft = it })
                    ModelSection(draft = draft, settings = settings, onDraft = { draft = it })
                    ToolSection(draft = draft, toolNames = toolNames, onDraft = { draft = it })
                    notice?.let { message ->
                        Text(
                            text = message,
                            style = SettingsStyle.description,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = { confirmingDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("删除这个助手", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        // The delete entry is simply absent when there is one assistant left;
                        // saying why is the difference between a rule and a missing button.
                        Text(
                            text = "至少要留一个助手，所以这个删不掉：先新建一个，再回来删它。",
                            style = SettingsStyle.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "留空的项会跟随「设置」里的当前值，所以只改名字也能用。",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 名字用 label：空名字的助手在卡片上显示的就是它，弹窗里报一个空引号等于没说是谁。
    if (confirmingDelete && onDelete != null) {
        SuixingDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这个助手") },
            text = { Text("确定要删除「${draft.label}」吗？它的提示词和头像会一起删除，无法恢复。") },
            confirmButton = { scope ->
                TextButton(onClick = {
                    scope.closeThen {
                        confirmingDelete = false
                        onDelete()
                    }
                }) { Text("删除") }
            },
            dismissButton = { scope ->
                TextButton(onClick = { scope.closeThen { confirmingDelete = false } }) { Text("取消") }
            },
        )
    }

    // 裁剪是选图与保存之间的一步，自己一个全屏对话框：它要占满屏幕才拖得动，而编辑页还开着，
    // 取消回来时用户看到的正是他离开时的那个编辑页。
    cropping?.let { source ->
        val saveJob = remember(source) { arrayOfNulls<Job>(1) }
        DisposableEffect(source) {
            onDispose {
                // A closing editor may still have an IO crop using this bitmap.
                // Job completion waits for that child before releasing its pixels.
                val release = { if (!source.isRecycled) source.recycle() }
                val saving = saveJob[0]
                if (saving == null) release()
                else saving.invokeOnCompletion { release() }
            }
        }
        AvatarCropper(
            source = source,
            onCancel = {
                // 取消：什么都没写，头像仍是原来那张。把解码出来的位图还给系统。
                cropping = null
            },
            onConfirm = { rect ->
                if (saveJob[0]?.isActive == true) return@AvatarCropper
                val previous = draft.avatarPath
                saveJob[0] = scope.launch(start = CoroutineStart.LAZY) {
                    val path = AssistantAvatarStore.saveCropped(context, draft.id, source, rect, previous)
                    cropping = null
                    if (path != null) {
                        notice = null
                        draft = draft.copy(avatarPath = path)
                    } else {
                        // 写盘失败（空间、权限、位图已经回收）：旧头像原样留着，并说清是保存失败，
                        // 与「打开失败」分开——用户下一步该做的事不一样。
                        notice = "这张图没能保存下来，头像保持原样。可以换一张，或直接用下面的表情。"
                    }
                }.also { it.start() }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentitySection(
    draft: Assistant,
    onDraft: (Assistant) -> Unit,
    onPickPicture: () -> Unit,
    onClearPicture: () -> Unit,
    onPickEmoji: (String) -> Unit,
) {
    SectionCard(title = "身份", icon = ImageVector.vectorResource(R.drawable.ic_id_card), subtitle = "名字与头像") {
        // The picture first, and apart from the emoji grid: the two are the same choice, and a
        // photo is what most users reach for. The emoji below stays the fallback for anyone who
        // does not want to hand over a picture.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistantAvatar(
                face = draft.face,
                avatarPath = draft.avatarPath,
                size = 64.dp,
            )
            Spacer(Modifier.width(8.dp))
            // 两个入口并排成一组：竖排时它们各自占满一个 40dp 的按钮框，读起来是「一个在顶、
            // 一个在底」，中间那一大段空白其实是按钮的触控框。并排之后竖向只剩一行，头像右侧的
            // 空档也从 24dp（12dp 间隔 + 12dp 按钮内边距）收到 16dp。
            // FlowRow 而不是 Row：字号放大到一行放不下时它会自己折行，不会把「恢复默认图标」挤出去。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(
                    onClick = onPickPicture,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(if (draft.avatarPath.isBlank()) "上传图片" else "换一张")
                }
                if (draft.avatarPath.isNotBlank()) {
                    TextButton(
                        onClick = onClearPicture,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("恢复默认图标") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 列数与格子尺寸按**可用宽度算**，不写死：先按理想格子（48dp）估能放几列，再把余量平均
        // 分给每个格子，于是最后一列的右缘正好落在容器右缘——不再出现「5 列挤在左边、右边一大片
        // 空白」。格子有 40..56dp 的夹取，超宽屏上按整行居中，而不是把圆圈撑成巨无霸。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val ideal = 48.dp
            val columns = (((maxWidth + gap) / (ideal + gap)).toInt()).coerceIn(4, ASSISTANT_AVATARS.size)
            val cell = ((maxWidth - gap * (columns - 1)) / columns).coerceIn(40.dp, 56.dp)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(gap),
                maxItemsInEachRow = columns,
            ) {
                ASSISTANT_AVATARS.forEach { emoji ->
                    val selected = draft.avatarPath.isBlank() && draft.avatar == emoji
                    Surface(
                        onClick = { onPickEmoji(emoji) },
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                            // 19sp 不写死：按 titleLarge 的比例给，用户调「外观→字号」时这个
                            // emoji 跟着缩放（写死 sp 会把缩放静默关掉）。
                            val face = MaterialTheme.typography.titleLarge
                            Text(emoji, style = face.copy(fontSize = face.fontSize * (19f / 22f)))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            // bodyMedium(14sp)：bodySmall 那次是「为了压高度把字缩了」，用户直接否掉（「字体还
            // 过于小了」）。字号回到正常，高度改由下面那个 heightIn 下限控制。
            //
            // 为什么一个**下限**就能比 M3 默认矮：OutlinedTextField 内部用的是
            // `defaultMinSize(minHeight = 56.dp)`，而 defaultMinSize 只在**外层没给最小约束**
            // 时生效——外面写了 `heightIn(min = 48.dp)`，传入的最小高度就不是 0，内部那句被让位，
            // 于是 48dp 真的落到 48dp（标签行 + 输入行 + 上下 8dp 正好填满）。
            // 反过来，写死 `height(48.dp)` 是**上限**：用户把「外观→字号」调大时字会被裁掉，工具页
            // 搜索框踩过这个坑（ToolsScreen.kt:521-524）。下限不裁：字大了框跟着长高。
            textStyle = SettingsStyle.fieldText,
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            // 下限，不是固定高度：内容需要时它自己长高，字号放大也不会裁字。
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            label = { Text("名字") },
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            // 与「名字」同一个字号、同一个下限：三个框看起来必须是同一种控件。
            textStyle = SettingsStyle.fieldText,
            value = draft.description,
            onValueChange = { onDraft(draft.copy(description = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            label = { Text("一句话说明") },
            placeholder = { Text("例如：只读代码、不改文件") },
            singleLine = true,
        )
    }
}

@Composable
private fun PromptSection(draft: Assistant, onDraft: (Assistant) -> Unit) {
    SectionCard(
        title = "系统提示词",
        icon = ImageVector.vectorResource(R.drawable.ic_sliders_horizontal),
        subtitle = "这个助手是谁、该怎么做事",
        trailing = {
            TextButton(
                onClick = { onDraft(draft.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT)) },
            ) {
                Text("用默认")
            }
        },
    ) {
        OutlinedTextField(
            textStyle = SettingsStyle.fieldText,
            value = draft.systemPrompt,
            onValueChange = { onDraft(draft.copy(systemPrompt = it)) },
            // 150 → 120：提示词框也是"太高"被点名的之一，而这里同样用**下限**——写多长它长多高，
            // 永远不会因为框小而把字裁掉。
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("留空则沿用「设置」里的系统提示词") },
        )
    }
}

@Composable
private fun ModelSection(
    draft: Assistant,
    settings: AppSettings,
    onDraft: (Assistant) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // `enabledModels()` 只读 providers（data/Settings.kt:516）—— 记住它，别每次重组都
    // flatMap 一遍（每击键分配 N×M 个 Pair）。
    val pairs = remember(IdentityKey(settings.providers)) { settings.enabledModels() }
    val follow = !draft.pinsModel
    val label = when {
        follow -> "跟随当前（${settings.shortModel.ifBlank { "未设置" }}）"
        else -> draft.model.substringAfter('/')
    }

    SectionCard(title = "模型", icon = ImageVector.vectorResource(R.drawable.ic_cpu), subtitle = "可以让这个助手用另一个模型") {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = SettingsStyle.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("跟随当前模型") },
                    onClick = {
                        onDraft(draft.copy(providerId = "", model = ""))
                        open = false
                    },
                )
                pairs.forEach { (provider, modelId) ->
                    DropdownMenuItem(
                        text = { Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onDraft(draft.copy(providerId = provider.id, model = modelId))
                            open = false
                        },
                    )
                }
                if (pairs.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("还没启用模型，去「设置」里拉取") },
                        onClick = { open = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SettingRow(
            title = "单独设置温度",
            subtitle = "关闭则跟随全局温度",
            control = {
                SettingsSwitch(
                    checked = draft.temperature != null,
                    onCheckedChange = { on ->
                        onDraft(draft.copy(temperature = if (on) 0.6 else null))
                    },
                )
            },
        )
        draft.temperature?.let { value ->
            // **拖动期间只改本地态，松手才写 draft**（性能审计 P0.3）：原来 `onValueChange`
            // 每帧都写 draft ⇒ 整个助手编辑器每帧重组（16 格 emoji FlowRow + 全部工具
            // FilterChip + 3 个输入框都跟着重跑）。与 SettingsScreen 的 `LabeledSlider`
            // 同一个修法，那份改了这份没改。
            var local by remember { mutableFloatStateOf(value.toFloat()) }
            // 外部值变化（重置草稿）时同步进来；拖动期间外部不会变。
            LaunchedEffect(value) { local = value.toFloat() }
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onDraft(draft.copy(temperature = local.toDouble())) },
                valueRange = 0f..2f,
                steps = 19,
            )
            Text(
                text = "温度 ${"%.1f".format(local)}",
                style = SettingsStyle.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
// M3 1.3 把 LocalMinimumInteractiveComponentSize 标成了废弃（替代做法是整组关掉触控框
// enforcement），但关掉会让 pill 掉到 32dp；这里要的是 40dp 这个折中，所以显式压掉那条告警。
@Suppress("DEPRECATION")
@Composable
private fun ToolSection(
    draft: Assistant,
    toolNames: List<String>,
    onDraft: (Assistant) -> Unit,
) {
    val off = draft.disabledTools.toSet()
    SectionCard(
        title = "工具",
        icon = ImageVector.vectorResource(R.drawable.ic_wrench),
        subtitle = "关掉的工具这个助手不能调用",
        trailing = {
            TextButton(onClick = { onDraft(draft.copy(disabledTools = emptyList())) }) {
                Text("全开")
            }
        },
    ) {
        // 7dp → 4dp：pill 之间的空白由两段组成——这段间距，加上 M3 给每个 chip 的隐形触控框
        // （可见 pill 只有 FilterChipDefaults.Height = 32dp，外面被 minimumInteractiveComponentSize
        // 撑到 48dp）。间距那段收紧了，隐形那段在这里收到 40dp：视觉间距 ~23dp → ~14dp，触控仍
        // 有 40dp。**只作用于这一组 chip**（provider 只包这个 FlowRow），别把它提到页面或主题上——
        // 全站的 48dp 触控下限是刻意的，只有这一屏的 chip 密度值得让 8dp。
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
                verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
            ) {
                toolNames.forEach { name ->
                    val enabled = name !in off
                    FilterChip(
                        selected = enabled,
                        onClick = {
                            onDraft(
                                draft.copy(
                                    disabledTools = if (enabled) {
                                        draft.disabledTools + name
                                    } else {
                                        draft.disabledTools - name
                                    },
                                ),
                            )
                        },
                        label = { Text(name, style = SettingsStyle.badge) },
                    )
                }
            }
        }
        if (toolNames.isEmpty()) {
            Text(
                text = "工具还在加载…",
                style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

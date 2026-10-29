package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import com.suixing.agent.ui.components.SettingsSwitch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.plugins.EnvDecl
import com.suixing.agent.plugins.InstallResult
import com.suixing.agent.plugins.PluginEntry
import com.suixing.agent.plugins.PluginLevel
import com.suixing.agent.plugins.PluginSpec
import com.suixing.agent.plugins.Severity
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.theme.Tone
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * The 「插件」 tab: what is installed, what each one would do, and how to put one in.
 *
 * **M1 is a white-box milestone.** A plugin can be installed, validated and inspected, and its
 * enable switch records a decision — but nothing here reaches the tool table or the system prompt
 * yet. That is why every screen shows the raw material (the exec template, the hooks, the injected
 * text) instead of a friendly summary: the user is being asked to judge a package that will later
 * run, and a prettified view would hide exactly what they need to read.
 *
 * The install flow is deliberately two-step. `install` unpacks into a staging area and returns an
 * [InstallResult] — the manifest, the level, the templates and every issue — and only
 * `confirmInstall` moves it into place. A package the validator rejects never becomes installed, and
 * a package it merely warns about is shown with its warnings before the user agrees.
 */
@Composable
fun PluginsTab(onNotify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<PluginEntry>>(emptyList()) }
    /**
     * Whether the first read has finished — success *or* failure.
     *
     * Without it the empty state is drawn on the very first frame, before the disk has been read:
     * the user sees 「还没有可安装的插件」 for a few milliseconds and then the list, which reads as
     * "it flashed the empty state before loading". Failure counts as loaded too, or the page would
     * stay blank for ever after a read error.
     */
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<PluginSpec?>(null) }
    var pending by remember { mutableStateOf<InstallResult?>(null) }
    var envFor by remember { mutableStateOf<PluginSpec?>(null) }

    LaunchedEffect(refresh) {
        entries = runCatching { Core.plugins.list() }.getOrDefault(emptyList())
        loaded = true
    }

    // SAF: a `.sapkg` is a zip, and a custom extension is not a mime type, so the picker takes
    // anything and the validator is what decides whether it is a plugin.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "plugin-import.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    target
                }.getOrNull()
            }
            if (staged == null) {
                onNotify("读不到这个文件")
                return@launch
            }
            // install() runs the validator and stops at the white-box gate: nothing is in place yet.
            val result = runCatching { Core.plugins.install(staged) }.getOrElse { error ->
                // 失败也要清暂存 zip，否则越积越多（issue #54 D-4）。
                withContext(Dispatchers.IO) { runCatching { staged.delete() } }
                onNotify("导入失败：${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { staged.delete() } }
            pending = result
        }
    }

    // fillMaxWidth, not fillMaxSize: this section is drawn as an item of the tab's LazyColumn, where
    // the height constraint is unbounded — a fillMaxHeight there is meaningless at best.
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsStyle.PageMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (entries.isEmpty()) "" else "已安装 ${entries.size} 个",
                style = SettingsStyle.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("安装插件") }
        }
        if (entries.isEmpty()) {
            // 首帧不画空态：列表是异步读盘的，第一帧还没有数据 —— 先画"还没有可安装的插件"、读完再画
            // 列表，用户看到的就是"闪一下初始状态"。读盘只要几毫秒，所以什么都不画（而不是画进度条：
            // 一个只出现一帧的进度条本身就是那次闪烁）。失败也算读完了，否则页面会永远空白。
            if (loaded) {
                EmptyState(
                    title = "还没有可安装的插件",
                    subtitle = "选一个插件包（zip）安装。\n\n" +
                        "命名规则（硬规则，不符会被拒装）：\n" +
                        "· id 与工具名：小写字母开头，只含小写字母、数字、横杠（id）或下划线（工具名）\n" +
                        "· id 不含点号、总长 ≤32；工具名 ≤32\n" +
                        "· 注册名＝<id>_<tool>，要发给模型网关，网关只收字母数字下划线横杠",
                )
            }
        } else {
            // A plain Column, **not** a LazyColumn. This section is drawn inside the tab's own
            // LazyColumn item, and a lazy list measures its items with an unbounded height — a
            // second vertical scrollable down there is the "infinity maximum height constraints"
            // crash (v0.8.92, tapping the plugins tab). The tab already scrolls; the list is short;
            // there is nothing for a lazy list to do here.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                entries.forEach { entry ->
                    PluginRow(
                        entry = entry,
                        onOpen = { scope.launch { detail = Core.plugins.readSpec(entry.id) } },
                        onToggle = { wanted ->
                            scope.launch {
                                if (wanted) {
                                    val missing = Core.plugins.missingRequiredEnv(entry.id)
                                    if (missing.isNotEmpty()) {
                                        // Enabling without the values a plugin declared it needs would
                                        // install a package that cannot run, so the form comes first.
                                        onNotify("还缺 ${missing.size} 个必填项，先填写")
                                        envFor = Core.plugins.readSpec(entry.id)
                                        return@launch
                                    }
                                }
                                Core.plugins.setEnabled(entry.id, wanted)
                                refresh++
                            }
                        },
                    )
                }
            }
        }
    }

    detail?.let { spec ->
        PluginDetailDialog(
            spec = spec,
            // Read from the list that is already loaded rather than fetching again: the switch in the
            // row and the button in the dialog are the same state, and two reads could disagree.
            enabled = entries.firstOrNull { it.id == spec.id }?.state?.enabled == true,
            // The store's own directory for this plugin (filesDir/plugins/<id>), read from the same
            // loaded entry — not re-derived from a string, so what is shown is what is on disk.
            dir = entries.firstOrNull { it.id == spec.id }?.dir?.absolutePath.orEmpty(),
            onDismiss = { detail = null },
            onFillEnv = { envFor = spec },
            onUninstall = {
                scope.launch {
                    Core.plugins.uninstall(spec.id)
                    detail = null
                    refresh++
                    onNotify("已卸载 ${spec.displayName.zh}")
                }
            },
            onToggle = { wanted ->
                scope.launch {
                    if (wanted && Core.plugins.missingRequiredEnv(spec.id).isNotEmpty()) {
                        envFor = spec
                        return@launch
                    }
                    Core.plugins.setEnabled(spec.id, wanted)
                    detail = null
                    refresh++
                }
            },
        )
    }

    pending?.let { result ->
        InstallConfirmDialog(
            result = result,
            onCancel = {
                // Backing out discards the staged copy: a package that was never confirmed must not
                // be left half-installed for the next launch to find.
                scope.launch {
                    result.spec?.let { Core.plugins.uninstall(it.id) }
                    pending = null
                    refresh++
                }
            },
            onConfirm = {
                scope.launch {
                    result.spec?.let { Core.plugins.confirmInstall(it.id) }
                    pending = null
                    refresh++
                    onNotify("已安装 ${result.summary?.displayName?.zh ?: "插件"}")
                }
            },
        )
    }

    envFor?.let { spec ->
        EnvDialog(
            spec = spec,
            onDismiss = { envFor = null },
            onSave = { values ->
                scope.launch {
                    values.forEach { (name, value) -> Core.plugins.setEnvValue(spec.id, name, value) }
                    envFor = null
                    refresh++
                    onNotify("已保存 ${values.size} 项")
                }
            },
        )
    }
}

/** One installed plugin: name, version, level, risk, and the enable switch. */
@Composable
private fun PluginRow(entry: PluginEntry, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val spec = entry.spec
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = spec?.displayName?.zh ?: entry.id,
                    style = SettingsStyle.sectionTitle,
                )
                Text(
                    text = listOfNotNull(
                        spec?.version?.let { "v$it" },
                        spec?.author?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = SettingsStyle.badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.level?.let { LevelBadge(it) }
            Spacer(Modifier.width(6.dp))
            RiskBadge(spec)
            Spacer(Modifier.width(10.dp))
            SettingsSwitch(checked = entry.state.enabled, onCheckedChange = onToggle)
        }
        // A plugin the validator could not read still has to say so where it is listed, or a broken
        // package looks like an installed one with a blank name.
        entry.issues.take(2).forEach { issue ->
            Text(
                text = "${issue.code}: ${issue.message}",
                style = SettingsStyle.badge,
                color = if (issue.severity == Severity.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(onClick = onOpen) { Text("详情") }
    }
}

@Composable
private fun LevelBadge(level: PluginLevel) {
    val tone = when (level) {
        PluginLevel.L1 -> Tone.OK
        PluginLevel.L2 -> Tone.OK
        PluginLevel.L3 -> Tone.WARN
        PluginLevel.L4 -> Tone.WARN
    }
    StatusPill(text = level.name, tone = tone)
}

@Composable
private fun RiskBadge(spec: PluginSpec?) {
    val risk = spec?.tools?.firstOrNull()?.risk
    if (risk.isNullOrBlank()) return
    StatusPill(
        text = risk,
        tone = if (risk == "read" || risk == "network") Tone.OK else Tone.WARN,
    )
}

/**
 * The white-box view: what this plugin would actually do, in its own words.
 *
 * Every block is the raw material — the exec template with its `{{placeholders}}` intact, the hook
 * rules, the text that would be injected, the env names with secrets masked. Reading it is the
 * point; a summary would be a promise about code the user cannot see.
 */
@Composable
private fun PluginDetailDialog(
    spec: PluginSpec,
    /** Whether this plugin is currently switched on — the action button shows one of the two. */
    enabled: Boolean,
    /**
     * Where this plugin lives on disk, absolute. Shown so the user can find it with a file manager —
     * the same reason the skills page prints its root path.
     */
    dir: String,
    onDismiss: () -> Unit,
    onFillEnv: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var env by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(spec.id) {
        env = runCatching { Core.plugins.env(spec.id) }.getOrDefault(emptyMap())
    }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.displayName.zh) },
        text = {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(spec.description.zh, style = SettingsStyle.description)
                    if (dir.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("目录", style = SettingsStyle.sectionTitle)
                        val clipboard = LocalClipboardManager.current
                        val context = LocalContext.current
                        Text(
                            text = dir,
                            style = SettingsStyle.description.copy(fontFamily = FontFamily.Monospace),
                            // Two lines and an ellipsis: an absolute path under /data/data is long,
                            // and letting it wrap freely would push the buttons off the dialog.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                // The repo's own copy idiom (ChatScreen's message copy): the platform
                                // clipboard plus a toast, so a long path can be pasted somewhere useful.
                                clipboard.setText(AnnotatedString(dir))
                                Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    spec.tools.forEach { tool ->
                        Spacer(Modifier.height(10.dp))
                        Text("工具 ${tool.name}（${tool.risk ?: "read"}）", style = SettingsStyle.sectionTitle)
                        if (tool.description.isNotBlank()) {
                            Text(tool.description, style = SettingsStyle.description)
                        }
                        // The template itself, variables and all: this is the thing being trusted.
                        val template = tool.exec.command ?: tool.exec.url ?: tool.exec.type
                        Text(
                            text = template,
                            style = SettingsStyle.description.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    if (spec.hooks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("hooks", style = SettingsStyle.sectionTitle)
                        spec.hooks.forEach { hook ->
                            Text(
                                text = "${hook.on} → ${hook.action}" +
                                    (hook.`when`.tool?.let { "（工具 $it）" } ?: "") +
                                    (hook.message?.let { "：$it" } ?: ""),
                                style = SettingsStyle.description,
                            )
                        }
                    }
                    if (spec.context.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("注入的上下文", style = SettingsStyle.sectionTitle)
                        spec.context.forEach { block ->
                            Text(block.content, style = SettingsStyle.description)
                        }
                    }
                    if (spec.env.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("env", style = SettingsStyle.sectionTitle)
                        spec.env.forEach { decl ->
                            val value = env[decl.name]
                            // 展示按「env.json 值 > manifest.defaultValue > （未填）」合并
                            // （FEEDBACK-v0.8.97 #5）：执行侧早就是这个优先级，展示侧之前只读 env.json，
                            // 声明了 defaultValue 的字段会显示「（未填）」，看着像没生效。
                            val shown = when {
                                !value.isNullOrEmpty() -> if (decl.secret) "••••••" else value
                                !decl.defaultValue.isNullOrEmpty() ->
                                    if (decl.secret) "••••••（默认值）" else "${decl.defaultValue}（默认值）"
                                else -> "（未填）"
                            }
                            Text(
                                text = decl.name + (if (decl.required) "（必填）" else "") + " = " + shown,
                                style = SettingsStyle.description,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { scope ->
            // 二选一，不是并排两个：一个插件不可能同时"已启用"和"未启用"，两个按钮同时出现会让
            // 用户以为它们是两个不同的动作（而不是同一个开关的两面）。
            if (enabled) {
                TextButton(onClick = { onToggle(false) }) { Text("停用") }
            } else {
                TextButton(onClick = { onToggle(true) }) { Text("启用") }
            }
        },
        dismissButton = { scope ->
            Row {
                // 只在 manifest 真的声明了 env 时才画：没有 env 的插件上这个按钮点了什么也不会发生，
                // 一个按不动的按钮比没有按钮更让人困惑。有 env 但**都不是必填**的也画 —— 选填项也是
                // 用户可以填的东西。
                if (spec.env.isNotEmpty()) {
                    TextButton(onClick = onFillEnv) { Text("填 env") }
                }
                TextButton(onClick = { scope.closeThen(onUninstall) }) { Text("卸载") }
            }
        },
    )
}

/**
 * The confirmation page shown before anything is moved into place.
 *
 * Errors block the confirm button; warnings are listed and do not. Every issue prints its
 * [com.suixing.agent.plugins.ReasonCode] next to its message — a machine-readable code is what makes
 * a report actionable, and a silent failure is what this milestone exists to avoid.
 */
@Composable
private fun InstallConfirmDialog(
    result: InstallResult,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = result.summary
    SuixingDialog(
        onDismissRequest = onCancel,
        title = { Text(if (result.ok) "确认安装" else "这个包不能安装") },
        text = {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = summary?.displayName?.zh ?: "（读不出名字）",
                        style = SettingsStyle.sectionTitle,
                    )
                    Text(
                        text = listOfNotNull(
                            summary?.id,
                            summary?.version?.let { "v$it" },
                            result.level?.name,
                            summary?.risk,
                        ).joinToString(" · "),
                        style = SettingsStyle.badge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 命名规则常驻说明（用户：「插件自行创建的命名规则要求，简洁不允许带点号；
                    // 创建插件的流程要有指令工具提供」）：每次安装确认都看得到，拒装时上面的
                    // issues 会指出具体哪一条不符。
                    Text(
                        text = "命名规则：id 小写字母开头、只含小写字母/数字/横杠、≤32 字、不含点号；" +
                            "工具名同规则但可含下划线；注册名＝<id>_<tool>（发给模型网关，只收字母数字下划线横杠）",
                        style = SettingsStyle.badge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (result.execTemplates.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("会执行的内容", style = SettingsStyle.sectionTitle)
                        result.execTemplates.forEach { template ->
                            Text(
                                text = template,
                                style = SettingsStyle.description.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    if (result.issues.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        result.issues.forEach { issue ->
                            Text(
                                text = "[${issue.severity.name}] ${issue.code}: ${issue.message}",
                                style = SettingsStyle.badge,
                                color = if (issue.severity == Severity.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { scope ->
            TextButton(onClick = { scope.closeThen(onConfirm) }, enabled = result.ok) { Text("安装") }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onCancel) }) { Text("取消") } },
    )
}

/** Fills in what the manifest declared as required, before the plugin can be switched on. */
@Composable
private fun EnvDialog(
    spec: PluginSpec,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val fields = remember(spec.id) { spec.env.filter { it.required } }
    var values by remember(spec.id) { mutableStateOf(fields.associate { it.name to (it.defaultValue ?: "") }) }
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写 ${spec.displayName.zh} 需要的值") },
        text = {
            Column {
                fields.forEach { decl: EnvDecl ->
                    OutlinedTextField(
                        value = values[decl.name].orEmpty(),
                        onValueChange = { next -> values = values + (decl.name to next) },
                        label = { Text(decl.name) },
                        supportingText = decl.description?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { scope ->
            TextButton(
                onClick = { scope.closeThen { onSave(values.filterValues { it.isNotBlank() }) } },
                enabled = fields.all { !values[it.name].isNullOrBlank() },
            ) { Text("保存") }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
    )
}

/**
 * 「插件」 tab: the idea, and now the packages themselves.
 *
 * The user's framing is DSH's: everything the app can do is something you can put in and take out.
 * The intro card explains that; [PluginsTab] below it is the actual store — installed packages,
 * their level and risk, and a white-box view of what each would do. M1 installs and inspects only:
 * nothing here reaches the tool table or the prompt yet, which is why the detail view shows raw
 * templates rather than a friendly summary.
 */
@Composable
internal fun PluginsSection(onNotify: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
        contentPadding = settingsPagePadding(),
        verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
    ) {
        item { PluginsIntroCard() }
        // The store draws its own empty state (the same words this tab used to show), so the list and
        // the "nothing here yet" case stay in one place instead of being decided twice.
        item { PluginsTab(onNotify = onNotify) }
    }
}

@Composable
private fun PluginsIntroCard() {
    SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_cpu), size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text("什么是插件", style = SettingsStyle.cardTitle)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "插件是可以装卸的扩展：随行的每一项能力，将来都以插件的形式提供——" +
                "装一个就多一份本事，卸掉就干净地消失，不用等应用更新。",
            style = SettingsStyle.description,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "现在还没有可安装的插件，这一页先放着，等形态定下来再填。",
            style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

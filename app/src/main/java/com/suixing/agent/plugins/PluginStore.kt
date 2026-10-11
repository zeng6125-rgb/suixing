package com.suixing.agent.plugins

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件的唯一写入口（HOST-DEV §3/§4）：安装 / 确认 / 启停 / 卸载 / 写 env。
 *
 * **真源是目录**（`files/plugins/<id>/`），`.known_plugins.json` 只是列表缓存 —— 所以 [list] 先
 * 扫目录、再拿缓存做差量修正：目录里有而缓存里没有的（别人手动拷进去、上次写到一半被杀）照样
 * 列出来，缓存里有而目录没了的直接丢掉并回写。杀进程不丢状态靠的是每个插件自己的 `state.json`。
 *
 * 三个安全点，改这个文件时别弄丢：
 * 1. **zip-slip**：解压前用 canonicalPath 卡住每个条目，包里的 `../` 或绝对路径一律丢弃 ——
 *    否则一个恶意 zip 可以往 filesDir 外面写文件。
 * 2. **env.json 的权限**：0600（只有本应用可读），写盘后立刻 chmod。
 * 3. **包内的 env.json / state.json 一律丢弃**：那两个文件由宿主写，包里带一份就是想改自己的
 *    凭据或伪造启用位。
 */
class PluginStore(private val baseDir: File) {

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val INSTRUCTIONS_FILE = "INSTRUCTIONS.md"
        const val STATE_FILE = "state.json"
        const val ENV_FILE = "env.json"
        const val KNOWN_FILE = ".known_plugins.json"
        const val TRASH_DIR = ".trash"

        /** 卸载宽限（开放问题#2 拍板）：进 .trash 放 7 天，之后由下一次卸载顺手清掉。 */
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** 0600：只有本应用能读写。 */
        private const val MODE_PRIVATE_RW = 384
    }

    private fun pluginDir(id: String) = File(baseDir, id)

    /**
     * 列出现有插件：**先扫目录**（真源），再用 [KNOWN_FILE] 差量修正。
     *
     * 扫出来的每个目录都要给出 `spec`（解析失败就是 null + 一条 SCHEMA_INVALID），绝不静默跳过 ——
     * 一个装坏了的插件必须出现在列表里、带着原因，而不是凭空消失让用户以为没装上（§0.4）。
     */
    suspend fun list(): List<PluginEntry> = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val installedToolNames = mutableSetOf<String>()
        val dirs = baseDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val entries = dirs.map { dir ->
            val issues = mutableListOf<PluginIssue>()
            val manifest = File(dir, MANIFEST_FILE)
            val spec = if (manifest.exists()) {
                runCatching { PluginJson.decodeFromString<PluginSpec>(manifest.readText()) }
                    .onFailure {
                        issues += PluginIssue(
                            ReasonCode.SCHEMA_INVALID, Severity.ERROR,
                            "manifest.json 读不出来：${it.message ?: it::class.java.simpleName}",
                        )
                    }
                    .getOrNull()
            } else {
                issues += PluginIssue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "目录里没有 manifest.json")
                null
            }
            // 已装插件的注册名，用来查重（同名工具两个插件不能共存）。
            spec?.tools?.forEach { installedToolNames += "${spec.id}.${it.name}" }
            PluginEntry(
                id = dir.name,
                dir = dir,
                spec = spec,
                state = readState(dir),
                level = levelOf(dir, spec),
                issues = issues,
            )
        }.sortedBy { it.id }

        // 差量修正：缓存只是缓存。目录里多的补进去，少的删掉，然后回写。
        val known = readKnown()
        val ids = entries.map { it.id }
        if (known.ids.toSet() != ids.toSet()) writeKnown(KnownPlugins(ids = ids, updatedAt = System.currentTimeMillis()))
        entries
    }

    /** 单个插件的 manifest（读不出来返回 null）。给详情页与运行时用。 */
    suspend fun readSpec(id: String): PluginSpec? = withContext(Dispatchers.IO) {
        val manifest = File(pluginDir(id), MANIFEST_FILE)
        if (!manifest.exists()) return@withContext null
        runCatching { PluginJson.decodeFromString<PluginSpec>(manifest.readText()) }.getOrNull()
    }

    /**
     * 安装：解压到临时目录 → 校验 → 落进 `plugins/<id>/`，返回**白盒数据（未启用）**。
     *
     * 只解压与校验，**不写 state.json**：写它的是 [confirmInstall]（用户在白盒页点过头之后）。
     * 所以「装完还没确认」和「确认了但停用」在 `state.json` 里是分得开的：前者的 `installedAt`
     * 是 0（默认值），后者有真实时间。
     */
    suspend fun install(
        fromZip: File,
        builtinToolNames: Set<String> = emptySet(),
    ): InstallResult = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val staging = File(baseDir, ".staging-${System.currentTimeMillis()}")
        val discarded = mutableListOf<String>()
        try {
            if (!staging.mkdirs()) {
                return@withContext failed("SCHEMA_INVALID", "建不了临时目录，检查存储空间")
            }
            val bad = extract(fromZip, staging, discarded)
            if (bad.isNotEmpty()) {
                return@withContext failed(
                    "DANGEROUS_PATTERN",
                    "压缩包里有指向目录外的条目，已拒绝：${bad.take(3).joinToString("、")}",
                )
            }
            // manifest 可以在根，也可以在一层子目录里（打包时多套了一层文件夹很常见）。
            val folder = locateManifestFolder(staging)
                ?: return@withContext failed("SCHEMA_INVALID", "压缩包里找不到 manifest.json")
            val dirName = folder.name
            val result = ManifestValidator.validate(
                manifestJson = File(folder, MANIFEST_FILE).readText(),
                dirName = dirName,
                builtinToolNames = builtinToolNames,
                installedToolNames = installedToolNames(),
                hasInstructions = File(folder, INSTRUCTIONS_FILE).exists(),
                hasScripts = File(folder, "scripts").isDirectory,
                hasAssets = File(folder, "assets").isDirectory,
            )
            if (!result.ok) return@withContext result

            val id = result.spec!!.id
            val target = pluginDir(id)
            if (target.exists()) {
                // M1 没有 update：同 id 再装一次会覆盖掉用户填过的 env 与启用位，宁可拒绝。
                return@withContext result.copy(
                    issues = result.issues + PluginIssue(
                        ReasonCode.NAME_CONFLICT, Severity.ERROR,
                        "「$id」已经装过了：请先卸载，或用更新（M1 还没有更新）",
                    ),
                )
            }
            if (!folder.renameTo(target)) {
                folder.copyRecursively(target, overwrite = true)
                folder.deleteRecursively()
            }
            // 包里带了宿主该写的文件：已经丢掉，但要说出来（§3）。
            if (discarded.isNotEmpty()) {
                return@withContext result.copy(
                    issues = result.issues + PluginIssue(
                        ReasonCode.SCHEMA_INVALID, Severity.WARNING,
                        "包里的 ${discarded.joinToString("、")} 已丢弃（这两个文件由宿主写）",
                    ),
                )
            }
            result
        } catch (t: Throwable) {
            failed("SCHEMA_INVALID", "安装失败：${t.message ?: t::class.java.simpleName}")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 白盒页确认：写 `state.json`（仍未启用 —— 启用是用户单独的开关）。 */
    suspend fun confirmInstall(id: String) = withContext(Dispatchers.IO) {
        val dir = pluginDir(id)
        if (!dir.isDirectory) return@withContext
        writeState(dir, PluginState(enabled = false, installedAt = System.currentTimeMillis()))
    }

    /**
     * 启停。缺 required env 时**不拦**：拦在启用那一刻会让用户以为开关坏了 —— 调用方
     * （插件页）应该先弹 [missingRequiredEnv] 的填写对话框，填完再调这里。
     */
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val dir = pluginDir(id)
        if (!dir.isDirectory) return@withContext
        val current = readState(dir)
        writeState(dir, current.copy(enabled = enabled))
    }

    /**
     * 卸载。默认进 `.trash/<id>-<ts>/` 放 7 天（开放问题#2 拍板），[wipe] 才是真删。
     * 顺手清掉过期的回收站条目 —— 不然它只会一直长。
     */
    suspend fun uninstall(id: String, wipe: Boolean = false) = withContext(Dispatchers.IO) {
        val dir = pluginDir(id)
        if (!dir.isDirectory) return@withContext
        if (wipe) {
            dir.deleteRecursively()
        } else {
            val trash = File(baseDir, TRASH_DIR).apply { mkdirs() }
            val slot = File(trash, "$id-${System.currentTimeMillis()}")
            if (!dir.renameTo(slot)) {
                dir.copyRecursively(slot, overwrite = true)
                dir.deleteRecursively()
            }
        }
        purgeTrash()
        writeKnown(KnownPlugins(ids = installedIds(), updatedAt = System.currentTimeMillis()))
    }

    /**
     * 写一个 env 值：落在 `plugins/<id>/env.json`，写盘后立刻 chmod 600。
     *
     * **这些值永不进 LLM payload**（§0.5）：它们只在 M3 的 exec 求值里用（`{{env.NAME}}`）。
     * 插件页展示时按 [EnvDecl.secret] 打码。
     */
    suspend fun setEnvValue(id: String, name: String, value: String) = withContext(Dispatchers.IO) {
        val dir = pluginDir(id)
        if (!dir.isDirectory) return@withContext
        val file = File(dir, ENV_FILE)
        val map = readEnvFile(file).toMutableMap()
        if (value.isEmpty()) map.remove(name) else map[name] = value
        file.writeText(PluginJson.encodeToString(map))
        runCatching { Os.chmod(file.absolutePath, MODE_PRIVATE_RW) }
        Unit
    }

    /** 读出 env 实值（给执行管道用；UI 别拿它直接显示）。 */
    suspend fun env(id: String): Map<String, String> = withContext(Dispatchers.IO) {
        readEnvFile(File(pluginDir(id), ENV_FILE))
    }

    /**
     * 还缺哪些必填 env（`required = true` 且没有默认值、也没有填过）。启用前用它决定要不要弹
     * 填写对话框 —— 缺了就不该启用，否则 M3 执行时才失败，用户看到的是「工具报错」而不是
     * 「去填一个变量」。
     */
    suspend fun missingRequiredEnv(id: String): List<String> = withContext(Dispatchers.IO) {
        val spec = readSpec(id) ?: return@withContext emptyList()
        val values = env(id)
        spec.env.filter { it.required && it.defaultValue == null && values[it.name].isNullOrEmpty() }
            .map { it.name }
    }

    // ---------------------------------------------------------------- 内部

    private fun installedIds(): List<String> =
        baseDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().map { it.name }.sorted()

    private fun installedToolNames(): Set<String> {
        val names = mutableSetOf<String>()
        for (dir in baseDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()) {
            val manifest = File(dir, MANIFEST_FILE)
            if (!manifest.exists()) continue
            runCatching { PluginJson.decodeFromString<PluginSpec>(manifest.readText()) }
                .getOrNull()?.tools?.forEach { names += "${dir.name}.${it.name}" }
        }
        return names
    }

    /**
     * 解压。返回**被拒绝的条目名**（zip-slip），并收集被丢弃的宿主文件。
     *
     * 条目名先 canonicalPath 比对再落盘：`../../x` 与 `/data/x` 都会被拦下来。
     */
    private fun extract(zip: File, into: File, discarded: MutableList<String>): List<String> {
        val root = into.canonicalPath + File.separator
        val rejected = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val name = entry.name
                val leaf = name.substringAfterLast('/')
                if (leaf == ENV_FILE || leaf == STATE_FILE) {
                    discarded += leaf
                    entry = input.nextEntry
                    continue
                }
                val target = File(into, name)
                if (!target.canonicalPath.startsWith(root)) {
                    rejected += name
                } else if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                entry = input.nextEntry
            }
        }
        return rejected
    }

    /** manifest 在根目录，或唯一一层子目录里。 */
    private fun locateManifestFolder(staging: File): File? {
        if (File(staging, MANIFEST_FILE).isFile) return staging
        val children = staging.listFiles { f -> f.isDirectory }.orEmpty()
        return children.firstOrNull { File(it, MANIFEST_FILE).isFile }
    }

    /**
     * L 档（§4 第 8 条）：deps 非空 → L4；scripts/ 或 assets/ → L3；INSTRUCTIONS.md → L2；否则 L1。
     *
     * `deps` 看的是 **manifest 里声明的**，不是某个文件的存在 —— 早期草稿按 `deps.json` 判，
     * 那与规范里 `deps: List<DepDecl>` 的形态对不上，会让一个声明了依赖的插件被评成 L1。
     */
    private fun levelOf(dir: File, spec: PluginSpec?): PluginLevel = when {
        spec?.deps?.isNotEmpty() == true -> PluginLevel.L4
        File(dir, "scripts").isDirectory || File(dir, "assets").isDirectory -> PluginLevel.L3
        File(dir, INSTRUCTIONS_FILE).exists() -> PluginLevel.L2
        else -> PluginLevel.L1
    }

    private fun readState(dir: File): PluginState {
        val file = File(dir, STATE_FILE)
        if (!file.exists()) return PluginState()
        return runCatching { PluginJson.decodeFromString<PluginState>(file.readText()) }
            .getOrDefault(PluginState())
    }

    private fun writeState(dir: File, state: PluginState) {
        runCatching { File(dir, STATE_FILE).writeText(PluginJson.encodeToString(PluginState.serializer(), state)) }
    }

    private fun readEnvFile(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            PluginJson.decodeFromString<Map<String, String>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun readKnown(): KnownPlugins {
        val file = File(baseDir, KNOWN_FILE)
        if (!file.exists()) return KnownPlugins()
        return runCatching { PluginJson.decodeFromString<KnownPlugins>(file.readText()) }
            .getOrDefault(KnownPlugins())
    }

    private fun writeKnown(known: KnownPlugins) {
        runCatching { File(baseDir, KNOWN_FILE).writeText(PluginJson.encodeToString(KnownPlugins.serializer(), known)) }
    }

    private fun purgeTrash() {
        val trash = File(baseDir, TRASH_DIR)
        val now = System.currentTimeMillis()
        trash.listFiles().orEmpty().forEach { slot ->
            if (now - slot.lastModified() > TRASH_RETENTION_MS) slot.deleteRecursively()
        }
    }

    private fun failed(code: String, message: String): InstallResult = InstallResult(
        spec = null,
        level = null,
        summary = null,
        execTemplates = emptyList(),
        issues = listOf(
            PluginIssue(
                code = runCatching { ReasonCode.valueOf(code) }.getOrDefault(ReasonCode.SCHEMA_INVALID),
                severity = Severity.ERROR,
                message = message,
            ),
        ),
    )
}

package com.suixing.agent.plugins

import kotlinx.serialization.json.JsonObject

/**
 * manifest 校验器（HOST-DEV §4 的 8 条检查清单）。
 *
 * **纯逻辑**：只吃字符串与几个布尔量，不碰磁盘、不碰 Android API ⇒ 可以在 JVM 单测里跑
 * （`ManifestValidatorTest`）。目录相关的事实（有没有 scripts/、assets/、INSTRUCTIONS.md）由
 * [PluginStore] 数好之后传进来 —— 这样「L 档判定」也能被单测覆盖，而不必造一个真目录。
 *
 * 每条失败都带 [ReasonCode]：UI 按 code 分支、按 message 给用户看（§0.4「失败要显式」）。
 */
object ManifestValidator {

    const val SUPPORTED_SCHEMA = 1

    /** api_version 只比 major：`1.x` 都能装，`2.0` 拒装。 */
    const val SUPPORTED_API_MAJOR = 1

    /**
     * 官方 id：`^[a-z][a-z0-9-]*$`，且**总长 ≤32、不含点号**（0.8.107 硬规则）。
     *
     * 点号为什么必须禁：注册名是 `<id>_<tool>`，要发给模型网关；deepseek 网关按
     * `^[a-zA-Z0-9_-]+$` 校验工具名，带点号的整批拒收（11152）。两段式命名空间（`acme.github`）
     * 因此一并取消 —— 命名空间用横杠写（`acme-github`）。
     */
    private val ID_OFFICIAL = Regex("^[a-z][a-z0-9-]*$")

    private val TOOL_NAME = Regex("^[a-z][a-z0-9_]*$")

    /** id 与工具名的长度上限：注册名 `<id>_<tool>` 要进网关的工具表，简洁是硬要求。 */
    private const val MAX_ID_LEN = 32
    private const val MAX_TOOL_NAME_LEN = 32
    private val ENV_NAME = Regex("^[A-Z][A-Z0-9_]*$")

    /** `{{x}}` 与 `{{env.NAME}}`：变量名允许点号，`env.` 前缀由调用方区分。 */
    private val TEMPLATE_VAR = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}""")

    /** context 模板变量白名单（开放问题#4 拍板：先用文档那 5 个）。 */
    val CONTEXT_VARS = setOf(
        "device.model", "device.battery", "device.storage_free", "time.now", "foreground.app",
    )

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    private val HOOK_ACTIONS = setOf("deny", "require_confirm", "log")
    private val HOOK_EVENTS = setOf("tools/pre-execute")
    private val EXEC_TYPES = setOf("http", "shell")

    private const val MAX_CONTEXT_CHARS = 2000

    /** `rm -rf <目标>`：目标以这些前缀开头才算安全（其余一律危险）。 */
    private val RM_FORCE = Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+([^\s;&|]+)""")
    private val SAFE_RM_PREFIXES = listOf("/sdcard", "/storage", "/data/local/tmp")

    private val SHELL_BLACKLIST: List<Pair<Regex, String>> = listOf(
        Regex("""\bmkfs(\.[a-z0-9]+)?\b""") to "mkfs（格式化）",
        Regex("""\bdd\b[^\n]*\bof=/dev/""") to "dd 直接写块设备",
        Regex("""\|\s*(sh|bash)\b""") to "把内容管道给 shell",
        Regex("""\bchmod\s+777\s+/""") to "chmod 777 /",
        Regex("""\$\([^)]*\$\([^)]*\)""") to "未加引号的嵌套 $()",
        Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+/(\s|$)""") to "rm -rf /",
    )

    /**
     * 校验一份 manifest。
     *
     * @param manifestJson 包内 `manifest.json` 的原文。
     * @param dirName 解压后的目录名 —— 必须与 `id` 一致（目录名是 id 的真源）。
     * @param builtinToolNames 宿主内置工具名，用来查重名。
     * @param installedToolNames 已装插件占用的注册名（`<id>.<tool>`），同样查重。
     * @param hasInstructions / [hasScripts] / [hasAssets] 决定 L 档（§4 第 8 条）。
     */
    fun validate(
        manifestJson: String,
        dirName: String,
        builtinToolNames: Set<String> = emptySet(),
        installedToolNames: Set<String> = emptySet(),
        hasInstructions: Boolean = false,
        hasScripts: Boolean = false,
        hasAssets: Boolean = false,
    ): InstallResult {
        val issues = mutableListOf<PluginIssue>()

        // 1. JSON 可解析 + schema_version + api_version
        val spec = runCatching { PluginJson.decodeFromString<PluginSpec>(manifestJson) }
            .getOrElse { error ->
                return InstallResult(
                    spec = null,
                    level = null,
                    summary = null,
                    execTemplates = emptyList(),
                    issues = listOf(
                        issue(
                            ReasonCode.SCHEMA_INVALID, Severity.ERROR,
                            "manifest.json 解析失败：${error.message ?: error::class.java.simpleName}",
                        ),
                    ),
                )
            }
        if (spec.schemaVersion != SUPPORTED_SCHEMA) {
            issues += issue(
                ReasonCode.SCHEMA_INVALID, Severity.ERROR,
                "schema_version 必须是 $SUPPORTED_SCHEMA（这份是 ${spec.schemaVersion}）",
            )
        }
        if (spec.apiVersion / 1000 != SUPPORTED_API_MAJOR && spec.apiVersion != SUPPORTED_API_MAJOR) {
            // 两种写法都认：`1` 与 `1000` 这类「major 打头」的版本号。
            issues += issue(
                ReasonCode.API_VERSION_MISMATCH, Severity.ERROR,
                "api_version 的主版本必须是 $SUPPORTED_API_MAJOR（这份是 ${spec.apiVersion}）",
            )
        }

        // 2. id 格式 + 与目录名一致
        val idOk = ID_OFFICIAL.matches(spec.id) && spec.id.length <= MAX_ID_LEN
        if (!idOk) {
            issues += issue(
                ReasonCode.ID_MISMATCH, Severity.ERROR,
                // `\$` 不是多余的反斜杠：Kotlin 的字符串模板里 `$` 后面**紧跟反引号**会被当成
                // `` `标识符` `` 模板（反引号是合法的 Kotlin 标识符起始符），于是正则末尾的 `$`
                // 会把后面的中文一起吞掉，报成「Unresolved reference '（官方）…'」——编译错误里
                // 那个乱码就是这么来的，文件编码没问题。正则里的 `$` 一律写成 `\$`。
                "id「${spec.id}」不符合命名规则：`^[a-z][a-z0-9-]*\$`、总长 ≤$MAX_ID_LEN、" +
                    "**不含点号**（注册名要发给模型网关，网关只收字母数字下划线横杠）",
            )
        }
        if (spec.id != dirName) {
            issues += issue(
                ReasonCode.ID_MISMATCH, Severity.ERROR,
                "id「${spec.id}」与目录名「$dirName」不一致 —— 目录名是 id 的真源",
            )
        }

        // 3. 工具名 + 重名
        // 守卫型插件（能力全在 hooks 里，如 cmd-guard）允许 tools 为空：逼它挂 noop 占位工具只会
        // 让模型看到一个什么都不能做的工具。两者都空才是真的什么都没提供。
        if (spec.tools.isEmpty() && spec.hooks.isEmpty()) {
            issues += issue(
                ReasonCode.TOOL_NAME_INVALID, Severity.ERROR,
                "至少要声明一个工具或一条钩子（tools 与 hooks 都为空）",
            )
        }
        val seenTools = mutableSetOf<String>()
        for (tool in spec.tools) {
            if (!TOOL_NAME.matches(tool.name) || tool.name.length > MAX_TOOL_NAME_LEN) {
                issues += issue(
                    ReasonCode.TOOL_NAME_INVALID, Severity.ERROR,
                    "工具名「${tool.name}」不符合 `^[a-z][a-z0-9_]*\$` 或超过 $MAX_TOOL_NAME_LEN 字",
                )
            }
            if (!seenTools.add(tool.name)) {
                issues += issue(ReasonCode.NAME_CONFLICT, Severity.ERROR, "工具名「${tool.name}」在同一个插件里重复")
            }
            // 注册名分隔符是**下划线**（0.8.107）：注册名要发给模型网关，deepseek 网关只收
            // `^[a-zA-Z0-9_-]+$`，点号整批拒收（11152）。与 PluginRuntime 的拼接保持一致。
            val registered = "${spec.id}_${tool.name}"
            if (registered in builtinToolNames || registered in installedToolNames) {
                issues += issue(ReasonCode.NAME_CONFLICT, Severity.ERROR, "注册名「$registered」已被占用")
            }
        }

        // env 名格式
        for (env in spec.env) {
            if (!ENV_NAME.matches(env.name)) {
                issues += issue(
                    ReasonCode.SCHEMA_INVALID, Severity.ERROR,
                    "环境变量名「${env.name}」不符合 `^[A-Z][A-Z0-9_]*\$`",
                )
            }
            if (env.required && env.defaultValue == null) {
                // 安装可以过（白盒页会提示去填），但启用前必须填 —— 所以是 warning 不是 error。
                issues += issue(
                    ReasonCode.ENV_MISSING_REQUIRED, Severity.WARNING,
                    "必填环境变量「${env.name}」没有默认值，启用前需要在插件页填写",
                )
            }
        }

        // 4~6. 逐个 exec：模板变量闭环、shell 黑名单、http 必须 https
        val declaredEnv = spec.env.map { it.name }.toSet()
        val templates = mutableListOf<String>()
        var anyNeedsRoot = false
        for (tool in spec.tools) {
            val exec = tool.exec
            if (exec.type !in EXEC_TYPES) {
                issues += issue(
                    ReasonCode.EXEC_TYPE_UNSUPPORTED, Severity.ERROR,
                    "工具「${tool.name}」的 exec.type=「${exec.type}」v1 不支持（只有 http / shell）",
                )
                continue
            }
            val raw = execStrings(exec)
            templates += raw.map { "${tool.name} (${exec.type}): $it" }
            if (exec.needsRoot) anyNeedsRoot = true

            // 模板变量闭环
            // 声明的参数在 `parameters.properties` 里（标准 JSON Schema object），**不是**顶层 ——
            // 顶层那两个键是 type/properties，照顶层算会把每个 {{param}} 都判成未声明。
            // 没有 properties 时退回顶层键，兼容把参数写成一张平表的 manifest。
            val declaredParams = tool.parameters?.let { params ->
                (params["properties"] as? JsonObject)?.keys ?: params.keys
            }.orEmpty()
            for (text in raw) {
                for (match in TEMPLATE_VAR.findAll(text)) {
                    val name = match.groupValues[1]
                    val known = when {
                        name == "plugin_dir" -> true
                        name == "src" -> true // deps.install 专用
                        name.startsWith("env.") -> name.removePrefix("env.") in declaredEnv
                        else -> name in declaredParams
                    }
                    if (!known) {
                        issues += issue(
                            ReasonCode.UNRESOLVED_VAR, Severity.ERROR,
                            "工具「${tool.name}」用到未声明的变量 {{$name}}",
                        )
                    }
                }
            }
            // 声明了却没用到 → warning
            val usedInTool = raw.flatMap { text ->
                TEMPLATE_VAR.findAll(text).map { it.groupValues[1] }.toList()
            }.toSet()
            for (param in declaredParams) {
                if (param !in usedInTool) {
                    issues += issue(
                        ReasonCode.UNRESOLVED_VAR, Severity.WARNING,
                        "工具「${tool.name}」声明了参数「$param」但模板里没用到",
                    )
                }
            }

            if (exec.type == "http") {
                val url = exec.url
                if (url.isNullOrBlank()) {
                    issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "工具「${tool.name}」的 http 缺少 url")
                } else {
                    val method = (exec.method ?: "GET").uppercase()
                    if (method !in HTTP_METHODS) {
                        issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "工具「${tool.name}」的 method「${exec.method}」不认识")
                    }
                    // 模板变量可能让前缀在运行时才成形：只在字面量确定不是 https 时才拦。
                    if (!url.startsWith("https://") && !url.startsWith("{{") && !url.startsWith("http://127.0.0.1")) {
                        issues += issue(
                            ReasonCode.HTTPS_REQUIRED, Severity.ERROR,
                            "工具「${tool.name}」的 url 必须是 https://（本机例外 http://127.0.0.1）",
                        )
                    }
                }
            } else {
                val command = exec.command
                if (command.isNullOrBlank()) {
                    issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "工具「${tool.name}」的 shell 缺少 command")
                } else {
                    issues += dangerousPatterns(tool.name, command)
                }
            }
        }

        // 7. hooks
        for (hook in spec.hooks) {
            if (hook.on !in HOOK_EVENTS) {
                issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "hook 的 on=「${hook.on}」v1 只支持 tools/pre-execute")
            }
            if (hook.action !in HOOK_ACTIONS) {
                issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "hook 的 action=「${hook.action}」不认识（deny / require_confirm / log）")
            }
            val regex = hook.`when`.regex
            if (regex != null && runCatching { Regex(regex) }.isFailure) {
                issues += issue(ReasonCode.REGEX_INVALID, Severity.ERROR, "hook 的 regex 编译失败：$regex")
            }
        }

        // context
        for (context in spec.context) {
            if (context.source !in setOf("static", "template")) {
                issues += issue(ReasonCode.SCHEMA_INVALID, Severity.ERROR, "context.source=「${context.source}」只支持 static / template")
            }
            if (context.content.length > MAX_CONTEXT_CHARS) {
                issues += issue(
                    ReasonCode.SCHEMA_INVALID, Severity.ERROR,
                    "context 文本 ${context.content.length} 字，超过 $MAX_CONTEXT_CHARS 上限",
                )
            }
            if (context.source == "template") {
                for (match in TEMPLATE_VAR.findAll(context.content)) {
                    val name = match.groupValues[1]
                    if (name !in CONTEXT_VARS) {
                        issues += issue(
                            ReasonCode.UNRESOLVED_VAR, Severity.ERROR,
                            "context 模板变量 {{$name}} 不在白名单里（${CONTEXT_VARS.joinToString(" / ")}）",
                        )
                    }
                }
            }
        }

        // v1 忽略但要说出来
        if (spec.triggers.isNotEmpty()) {
            issues += issue(ReasonCode.TRIGGERS_IGNORED, Severity.WARNING, "triggers 在 v1 会被忽略（${spec.triggers.size} 条）")
        }
        if (spec.deps.isNotEmpty()) {
            issues += issue(
                ReasonCode.DEPS_DISABLED, Severity.WARNING,
                // 0.8.107 起 deps 不再禁用工具（闸门误杀自带产物的插件，如 video-frame）：
                // 文案必须与运行时一致，不再承诺「工具会被禁用」。
                "deps 在 v1 不自动安装（${spec.deps.size} 条）：请确认依赖产物已随插件附带，" +
                    "否则相关工具运行时会报依赖缺失",
            )
        }
        if (spec.enabledByDefault) {
            issues += issue(ReasonCode.SCHEMA_INVALID, Severity.WARNING, "enabled_by_default 在 v1 恒 false，已忽略")
        }

        // 8. L 档
        val level = when {
            spec.deps.isNotEmpty() -> PluginLevel.L4
            hasScripts || hasAssets -> PluginLevel.L3
            hasInstructions -> PluginLevel.L2
            else -> PluginLevel.L1
        }

        return InstallResult(
            spec = spec,
            level = level,
            summary = summary(spec, anyNeedsRoot),
            execTemplates = templates,
            issues = issues,
        )
    }

    /** 一次 exec 里所有**原文**模板串（未求值），白盒页要一字不改地展示。 */
    private fun execStrings(exec: ExecDecl): List<String> = buildList {
        exec.url?.let { add(it) }
        exec.command?.let { add(it) }
        exec.cwd?.let { add(it) }
        exec.body?.let { add(it) }
        for ((key, value) in exec.headers) add("$key: $value")
    }

    /** §4 第 5 条：shell 黑名单。`rm -rf` 单独判，因为它有安全前缀豁免。 */
    private fun dangerousPatterns(toolName: String, command: String): List<PluginIssue> {
        val found = mutableListOf<PluginIssue>()
        for (match in RM_FORCE.findAll(command)) {
            val target = match.groupValues[1]
            if (SAFE_RM_PREFIXES.none { target.startsWith(it) }) {
                found += issue(
                    ReasonCode.DANGEROUS_PATTERN, Severity.ERROR,
                    "工具「$toolName」的命令里有 rm -rf「$target」（只有 /sdcard、/storage、/data/local/tmp 前缀可豁免）",
                )
            }
        }
        for ((regex, label) in SHELL_BLACKLIST) {
            if (regex.containsMatchIn(command)) {
                found += issue(ReasonCode.DANGEROUS_PATTERN, Severity.ERROR, "工具「$toolName」的命令命中黑名单：$label")
            }
        }
        return found
    }

    /** 风险推断（§5）：http GET=low、http 其他=medium、shell=high、needsRoot 恒 high；decl.risk 只许调高。 */
    fun riskOf(tool: ToolDecl): String {
        val inferred = when {
            tool.exec.needsRoot -> "high"
            tool.exec.type == "shell" -> "high"
            (tool.exec.method ?: "GET").uppercase() == "GET" -> "low"
            else -> "medium"
        }
        val declared = tool.risk
        return if (declared != null && rank(declared) > rank(inferred)) declared else inferred
    }

    /** 风险档的高低（low < medium < high）。hook 的 `min_risk` 与 [riskOf] 共用这一把尺。 */
    internal fun rank(risk: String): Int = when (risk) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    private fun summary(spec: PluginSpec, anyNeedsRoot: Boolean): ManifestSummary = ManifestSummary(
        id = spec.id,
        version = spec.version,
        displayName = spec.displayName,
        description = spec.description,
        author = spec.author,
        homepage = spec.homepage,
        toolNames = spec.tools.map { "${spec.id}_${it.name}" },
        envNames = spec.env.map { it.name },
        hookCount = spec.hooks.size,
        contextCount = spec.context.size,
        risk = spec.tools.maxByOrNull { rank(riskOf(it)) }?.let { riskOf(it) } ?: "low",
    ).let { if (anyNeedsRoot && it.risk != "high") it.copy(risk = "high") else it }

    private fun issue(code: ReasonCode, severity: Severity, message: String) = PluginIssue(code, severity, message)

    /** 给 UI 用的简版：只回答「这份 manifest 能不能装」。 */
    fun isInstallable(result: InstallResult): Boolean = result.ok
}

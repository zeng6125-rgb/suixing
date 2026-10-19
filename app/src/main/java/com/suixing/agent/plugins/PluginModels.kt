package com.suixing.agent.plugins

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject

/**
 * 插件系统的数据模型（HOST-DEV §2）。
 *
 * **命名策略全局统一为 snake_case**：manifest 是 snake_case、Kotlin 是 camelCase，文档要求
 * 「用 namingStrategy 或逐字段 @SerialName，二选一不要混」。这里选 namingStrategy —— 一处配置
 * 管全部字段，新增字段时不会忘记加注解。所以本文件里**不要再写 `@SerialName("…")`**：
 * 混用会让同一份 JSON 里出现两种映射规则，下一个改字段的人必然猜错。
 *
 * `@OptIn(ExperimentalSerializationApi::class)`：`JsonNamingStrategy` 目前是实验 API。
 */
@OptIn(ExperimentalSerializationApi::class)
internal val PluginJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
    prettyPrint = true
}

@Serializable
data class I18nText(val zh: String, val en: String? = null)

@Serializable
data class EnvDecl(
    /** `^[A-Z][A-Z0-9_]*$` —— 校验器检查。 */
    val name: String,
    val required: Boolean = false,
    val defaultValue: String? = null,
    /** 白盒页打码；永不进 LLM payload（§0.5）。 */
    val secret: Boolean = true,
    val description: String? = null,
)

@Serializable
data class ResponseDecl(
    val format: String = "json",
    /** 点路径取子集，控制返回体积。 */
    val pick: String? = null,
    val maxChars: Int = 8000,
)

@Serializable
data class ExecDecl(
    /** http | shell（v1）；js | mcp → 拒装（EXEC_TYPE_UNSUPPORTED）。 */
    val type: String,
    val method: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutSeconds: Int = 15,
    val response: ResponseDecl? = null,
    val command: String? = null,
    /** 缺省 `{{plugin_dir}}`。 */
    val cwd: String? = null,
    /** true → root 通道，且强制 risk=high（开放问题#5 已拍板）。 */
    val needsRoot: Boolean = false,
    /**
     * 用哪个解释器跑 [command]：缺省/`auto` = 先试随包 bash，被 seccomp 拦下再退回系统 sh；
     * `sh` = 直接系统 sh，不试探。
     *
     * **为什么需要它**（用户实测 free-search 每次调用都卡）：那条命令本身全是 POSIX
     * （`case/esac` + 显式 `sh xxx.sh`），外层用 bash 没有收益，却要多付一次
     * 「起 bash → 被 SIGSYS 杀 → 再起 sh」。插件作者知道脚本是 POSIX 时写
     * `"shell": "sh"`，就能省掉这一次。
     */
    val shell: String? = null,
)

@Serializable
data class ToolDecl(
    /** 注册名 = `<id>.<name>`。 */
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
    /** low/medium/high；只可上调，不可下调（§5 风险推断）。 */
    val risk: String? = null,
    val exec: ExecDecl,
)

@Serializable
data class WhenDecl(
    val tool: String? = null,
    val regex: String? = null,
    val minRisk: String? = null,
)

@Serializable
data class HookDecl(
    /** v1 仅 `tools/pre-execute`。 */
    val on: String,
    val `when`: WhenDecl,
    /** deny | require_confirm | log。 */
    val action: String,
    val message: String? = null,
)

@Serializable
data class ContextWhen(val minBattery: Int? = null)

@Serializable
data class ContextDecl(
    /** static | template。 */
    val source: String,
    /** ≤2000 字符。 */
    val content: String,
    val `when`: ContextWhen? = null,
)

@Serializable
data class DepDecl(
    val id: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long? = null,
    val install: String,
    val remove: String? = null,
)

@Serializable
data class PluginSpec(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    /** major != 1 拒装（API_VERSION_MISMATCH）。 */
    val apiVersion: Int,
    val displayName: I18nText,
    val description: I18nText,
    val author: String? = null,
    val homepage: String? = null,
    /** v1 恒 false：装完是「未启用」，要用户在白盒页点头。 */
    val enabledByDefault: Boolean = false,
    val env: List<EnvDecl> = emptyList(),
    /** ≥1。 */
    val tools: List<ToolDecl>,
    val hooks: List<HookDecl> = emptyList(),
    val context: List<ContextDecl> = emptyList(),
    /** v1 警告并忽略。 */
    val triggers: List<JsonElement> = emptyList(),
    /** v1 警告 + 相关工具禁用。 */
    val deps: List<DepDecl> = emptyList(),
)

/** `state.json`：宿主自己写的启用位与安装时间，与包内容分开。 */
@Serializable
data class PluginState(
    val enabled: Boolean = false,
    val installedAt: Long = 0,
)

/** `.known_plugins.json`：列表缓存，**可重建、非真源**（真源是目录）。 */
@Serializable
data class KnownPlugins(
    val ids: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

/** 列表页一行。`issues` 非空时那一行要显示原因，而不是静默不出现。 */
data class PluginEntry(
    val id: String,
    val dir: java.io.File,
    val spec: PluginSpec?,
    val state: PluginState,
    val level: PluginLevel?,
    val issues: List<PluginIssue> = emptyList(),
)

enum class PluginLevel { L1, L2, L3, L4 }

enum class Severity { ERROR, WARNING }

/**
 * 机器可读的失败原因（§4）。UI 直接按它分支，`message` 只给人看 —— 所以两者都要有：
 * 只有 message 会让 UI 去匹配中文，只有 code 会让用户看不懂发生了什么。
 */
enum class ReasonCode {
    SCHEMA_INVALID,
    ID_MISMATCH,
    TOOL_NAME_INVALID,
    NAME_CONFLICT,
    UNRESOLVED_VAR,
    DANGEROUS_PATTERN,
    HTTPS_REQUIRED,
    REGEX_INVALID,
    ENV_MISSING_REQUIRED,
    API_VERSION_MISMATCH,
    EXEC_TYPE_UNSUPPORTED,
    TRIGGERS_IGNORED,
    DEPS_DISABLED,
}

data class PluginIssue(
    val code: ReasonCode,
    val severity: Severity,
    val message: String,
)

/** 白盒页要展示的摘要：让用户在点「安装」之前看清这东西要干什么。 */
data class ManifestSummary(
    val id: String,
    val version: String,
    val displayName: I18nText,
    val description: I18nText,
    val author: String?,
    val homepage: String?,
    val toolNames: List<String>,
    val envNames: List<String>,
    val hookCount: Int,
    val contextCount: Int,
    /** 所有工具里最高的那个 risk（含 needsRoot 强制 high）。 */
    val risk: String,
)

/**
 * 安装/校验的结果。
 *
 * [execTemplates] 是**全部 exec 模板原文**（不是渲染后的）：白盒页要把「这条工具到底会执行什么」
 * 原样摆给用户看，而任何加工过的版本都可能藏住真正的命令 —— 这正是白盒的意义。
 */
data class InstallResult(
    val spec: PluginSpec?,
    val level: PluginLevel?,
    val summary: ManifestSummary?,
    val execTemplates: List<String>,
    val issues: List<PluginIssue>,
) {
    val ok: Boolean get() = issues.none { it.severity == Severity.ERROR }
    val errors: List<PluginIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<PluginIssue> get() = issues.filter { it.severity == Severity.WARNING }
}

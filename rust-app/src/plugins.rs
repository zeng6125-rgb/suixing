//! Manifest validation and read-only discovery for the app-private plugin registry.
use regex::{Regex, RegexBuilder};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::{
    collections::{BTreeMap, HashSet},
    path::{Path, PathBuf},
    sync::LazyLock,
};
use suixing_search::rust_protocol::ToolDefinition;

pub const MANIFEST_FILE: &str = "manifest.json";
pub const STATE_FILE: &str = "state.json";
pub const ENV_FILE: &str = "env.json";
const MAX_MANIFEST_BYTES: usize = 128 * 1024;
const MAX_HOOK_PATTERN_BYTES: usize = 1024;
const MAX_HOOK_ARGUMENT_BYTES: usize = 1024 * 1024;
const MAX_HOOK_WORK: usize = 4 * 1024 * 1024;
const MAX_ISSUES: usize = 32;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct I18nText {
    pub zh: String,
    pub en: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EnvDecl {
    pub name: String,
    #[serde(default)]
    pub required: bool,
    pub default_value: Option<String>,
    #[serde(default = "default_secret")]
    pub secret: bool,
    pub description: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ResponseDecl {
    #[serde(default = "default_format")]
    pub format: String,
    pub pick: Option<String>,
    #[serde(default = "default_max_chars")]
    pub max_chars: i32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ExecDecl {
    #[serde(rename = "type")]
    pub kind: String,
    pub method: Option<String>,
    pub url: Option<String>,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    pub body: Option<String>,
    #[serde(default = "default_timeout_seconds")]
    pub timeout_seconds: i32,
    pub response: Option<ResponseDecl>,
    pub command: Option<String>,
    pub cwd: Option<String>,
    #[serde(default)]
    pub needs_root: bool,
    pub shell: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ToolDecl {
    pub name: String,
    pub description: String,
    pub parameters: Option<Map<String, Value>>,
    pub risk: Option<String>,
    pub exec: ExecDecl,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WhenDecl {
    pub tool: Option<String>,
    pub regex: Option<String>,
    pub min_risk: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HookDecl {
    pub on: String,
    pub r#when: WhenDecl,
    pub action: String,
    pub message: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ContextWhen {
    pub min_battery: Option<i32>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ContextDecl {
    pub source: String,
    pub content: String,
    pub r#when: Option<ContextWhen>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DepDecl {
    pub id: String,
    pub url: String,
    pub sha256: String,
    pub size_bytes: Option<i64>,
    pub install: String,
    pub remove: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PluginManifest {
    pub schema_version: i32,
    pub id: String,
    pub version: String,
    pub api_version: i32,
    pub display_name: I18nText,
    pub description: I18nText,
    pub author: Option<String>,
    pub homepage: Option<String>,
    #[serde(default)]
    pub enabled_by_default: bool,
    #[serde(default)]
    pub env: Vec<EnvDecl>,
    pub tools: Vec<ToolDecl>,
    #[serde(default)]
    pub hooks: Vec<HookDecl>,
    #[serde(default)]
    pub context: Vec<ContextDecl>,
    #[serde(default)]
    pub triggers: Vec<Value>,
    #[serde(default)]
    pub deps: Vec<DepDecl>,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct PluginState {
    pub enabled: bool,
    pub installed_at: i64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PluginLevel {
    L1,
    L2,
    L3,
    L4,
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum Risk {
    Low,
    Medium,
    High,
}

impl Risk {
    fn from_declaration(value: Option<&str>) -> Self {
        match value {
            Some("high") => Self::High,
            Some("medium") => Self::Medium,
            _ => Self::Low,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Severity {
    Error,
    Warning,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReasonCode {
    SchemaInvalid,
    IdMismatch,
    ToolNameInvalid,
    NameConflict,
    UnresolvedVar,
    DangerousPattern,
    HttpsRequired,
    RegexInvalid,
    EnvMissingRequired,
    ApiVersionMismatch,
    ExecTypeUnsupported,
    TriggersIgnored,
    DepsNotInstalled,
    UnsafePath,
    StateInvalid,
}

#[derive(Clone, Debug)]
pub struct PluginIssue {
    pub code: ReasonCode,
    pub severity: Severity,
    pub message: String,
}

#[derive(Clone, Debug)]
pub struct PluginEnvStatus {
    pub name: String,
    pub secret: bool,
    pub required: bool,
    pub configured: bool,
    pub uses_default: bool,
    pub unavailable: bool,
    pub description: Option<String>,
}

#[derive(Clone)]
pub struct PluginEntry {
    pub id: String,
    pub dir: PathBuf,
    pub manifest: Option<PluginManifest>,
    pub state: PluginState,
    pub level: Option<PluginLevel>,
    pub issues: Vec<PluginIssue>,
    /// Run-frozen values, filled by Store. Never included in inventory or wire metadata.
    pub environment: BTreeMap<String, String>,
}

impl std::fmt::Debug for PluginEntry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PluginEntry")
            .field("id", &self.id)
            .field("dir", &self.dir)
            .field("state", &self.state)
            .field("level", &self.level)
            .field("issues", &self.issues)
            .field("environment_names", &self.environment.keys())
            .finish_non_exhaustive()
    }
}

impl PluginEntry {
    pub fn is_valid(&self) -> bool {
        self.manifest.is_some()
            && self
                .issues
                .iter()
                .all(|issue| issue.severity != Severity::Error)
    }
}

fn default_secret() -> bool {
    true
}

fn default_format() -> String {
    "json".into()
}

fn default_max_chars() -> i32 {
    8000
}

fn default_timeout_seconds() -> i32 {
    15
}

pub fn registered_name(plugin_id: &str, tool_name: &str) -> String {
    format!("{plugin_id}_{tool_name}")
}

pub fn valid_id(value: &str) -> bool {
    valid_name(value, b'-')
}

fn valid_name(value: &str, separator: u8) -> bool {
    let bytes = value.as_bytes();
    bytes.len() <= 32
        && bytes.first().is_some_and(u8::is_ascii_lowercase)
        && bytes
            .iter()
            .all(|&byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == separator)
}

impl ToolDecl {
    pub fn risk(&self) -> Risk {
        let inferred = if self.exec.needs_root || self.exec.kind == "shell" {
            Risk::High
        } else if !self
            .exec
            .method
            .as_deref()
            .unwrap_or("GET")
            .eq_ignore_ascii_case("GET")
        {
            Risk::Medium
        } else {
            Risk::Low
        };
        inferred.max(Risk::from_declaration(self.risk.as_deref()))
    }

    pub fn definition(&self, plugin_id: &str) -> ToolDefinition {
        let mut schema = self.parameters.clone().unwrap_or_default();
        schema.insert("type".into(), Value::String("object".into()));
        if !schema.get("properties").is_some_and(Value::is_object) {
            schema.insert("properties".into(), Value::Object(Map::new()));
        }
        ToolDefinition {
            name: registered_name(plugin_id, &self.name),
            description: self.description.clone(),
            parameters: Value::Object(schema),
        }
    }
}

/// Validates package paths independently of the host platform's separator rules.
pub fn relative_path(value: &str) -> Result<PathBuf, String> {
    if value.is_empty()
        || value.len() > 4096
        || value.contains(['\\', ':'])
        || value.chars().any(char::is_control)
        || value
            .split('/')
            .any(|part| part.is_empty() || matches!(part, "." | ".."))
    {
        return Err("插件路径须为不含空段、绝对路径或上级目录的相对路径".into());
    }
    Ok(PathBuf::from(value))
}

/// Returns a plugin-relative directory. An empty path denotes the plugin directory.
pub fn shell_working_directory(cwd: Option<&str>) -> Result<PathBuf, String> {
    let mut path = cwd.unwrap_or_default().trim();
    if let Some(rest) = path.strip_prefix("{{")
        && let Some((name, suffix)) = rest.split_once("}}")
        && name.trim() == "plugin_dir"
    {
        path = match suffix {
            "" => "",
            suffix => suffix
                .strip_prefix('/')
                .ok_or("plugin_dir 后须为插件内相对路径")?,
        };
    }
    if path.is_empty() || path == "." {
        return Ok(PathBuf::new());
    }
    if path.contains(['{', '}']) {
        return Err("插件工作目录只支持固定相对路径和 plugin_dir".into());
    }
    relative_path(path)
}

static TEMPLATE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}").expect("valid plugin template pattern")
});
static SHELL_DENY: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"\bmkfs(\.[a-z0-9]+)?\b|\bdd\b[^\n]*\bof=/dev/|\|\s*(sh|bash)\b|",
        r"\bchmod\s+777\s+/|\$\([^)]*\$\([^)]*\)",
    ))
    .expect("valid plugin shell deny pattern")
});
static FORCE_REMOVE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+([^\s;&|]+)")
        .expect("valid plugin rm pattern")
});

fn add_issue(issues: &mut Vec<PluginIssue>, code: ReasonCode, severity: Severity, message: String) {
    if issues.len() == MAX_ISSUES {
        if severity != Severity::Error
            || issues.iter().any(|issue| issue.severity == Severity::Error)
        {
            return;
        }
        issues.pop();
    }
    issues.push(PluginIssue {
        code,
        severity,
        message,
    });
}

fn error(issues: &mut Vec<PluginIssue>, code: ReasonCode, message: impl Into<String>) {
    add_issue(issues, code, Severity::Error, message.into());
}

fn warning(issues: &mut Vec<PluginIssue>, code: ReasonCode, message: impl Into<String>) {
    add_issue(issues, code, Severity::Warning, message.into());
}

/// Parse and validate package metadata without resolving credentials or touching the filesystem.
/// A parsed manifest is retained even when it has issues so the UI can display its declaration.
pub fn validate_manifest(
    bytes: &[u8],
    dir_name: &str,
    reserved_tool_names: &HashSet<String>,
) -> (Option<PluginManifest>, Vec<PluginIssue>) {
    validate(bytes, Some(dir_name), reserved_tool_names)
}

/// Import validates before a directory exists. The returned ID is safe to use as its name.
pub fn parse_manifest(
    input: &str,
    reserved_tool_names: &HashSet<String>,
) -> Result<PluginManifest, String> {
    let (manifest, issues) = validate(input.as_bytes(), None, reserved_tool_names);
    if let Some(issue) = issues
        .into_iter()
        .find(|issue| issue.severity == Severity::Error)
    {
        return Err(issue.message);
    }
    manifest.ok_or_else(|| "插件清单无效".into())
}

fn validate(
    bytes: &[u8],
    dir_name: Option<&str>,
    reserved_tool_names: &HashSet<String>,
) -> (Option<PluginManifest>, Vec<PluginIssue>) {
    use ReasonCode::*;
    let mut issues = Vec::new();
    if bytes.len() > MAX_MANIFEST_BYTES {
        error(&mut issues, SchemaInvalid, "manifest.json 超过 128 KiB");
        return (None, issues);
    }
    let manifest: PluginManifest = match serde_json::from_slice(bytes) {
        Ok(manifest) => manifest,
        Err(err) => {
            error(
                &mut issues,
                SchemaInvalid,
                format!(
                    "manifest.json 第 {} 行第 {} 列无法解析",
                    err.line(),
                    err.column()
                ),
            );
            return (None, issues);
        }
    };
    if manifest.schema_version != 1 {
        error(&mut issues, SchemaInvalid, "schema_version 必须为 1");
    }
    if manifest.api_version != 1 && !(1000..2000).contains(&manifest.api_version) {
        error(
            &mut issues,
            ApiVersionMismatch,
            "api_version 主版本必须为 1",
        );
    }
    let valid_id = valid_id(&manifest.id);
    if !valid_id || dir_name.is_some_and(|name| manifest.id != name) {
        error(&mut issues, IdMismatch, "插件 ID 无效或与目录名不同");
    }
    if manifest.tools.is_empty() && manifest.hooks.is_empty() {
        error(&mut issues, ToolNameInvalid, "至少声明一个工具或一条钩子");
    }
    if manifest.tools.len() > 64
        || manifest.hooks.len() > 64
        || manifest.env.len() > 64
        || manifest.context.len() > 64
        || manifest.deps.len() > 64
    {
        error(
            &mut issues,
            SchemaInvalid,
            "每个插件的工具、钩子、环境变量、上下文和依赖各最多 64 条",
        );
        return (Some(manifest), issues);
    }

    let mut env_names = HashSet::new();
    for env in &manifest.env {
        let name = env.name.as_bytes();
        if name.len() > 128
            || !name.first().is_some_and(u8::is_ascii_uppercase)
            || !name
                .iter()
                .all(|&byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
            || !env_names.insert(env.name.as_str())
        {
            error(&mut issues, SchemaInvalid, "环境变量名称无效或重复");
        } else if env.required && env.default_value.is_none() {
            warning(
                &mut issues,
                EnvMissingRequired,
                format!("启用前需要填写环境变量 {}", env.name),
            );
        }
    }

    let mut tool_names = HashSet::new();
    for tool in &manifest.tools {
        if !valid_name(&tool.name, b'_') {
            error(
                &mut issues,
                ToolNameInvalid,
                "工具名须以小写字母开头，仅允许小写字母、数字和下划线，最多 32 字节",
            );
            continue;
        }
        if !tool_names.insert(&tool.name) {
            error(
                &mut issues,
                NameConflict,
                format!("工具名 {} 在插件内重复", tool.name),
            );
        } else if valid_id {
            let name = registered_name(&manifest.id, &tool.name);
            if reserved_tool_names.contains(&name) {
                error(&mut issues, NameConflict, format!("注册名 {name} 已被占用"));
            }
        }
        validate_exec(tool, &env_names, &mut issues);
    }

    for hook in &manifest.hooks {
        if hook.on != "tools/pre-execute"
            || !matches!(hook.action.as_str(), "deny" | "require_confirm" | "log")
        {
            error(&mut issues, SchemaInvalid, "钩子事件或动作不受支持");
        }
        if hook
            .r#when
            .min_risk
            .as_deref()
            .is_some_and(|risk| !matches!(risk, "low" | "medium" | "high"))
        {
            error(
                &mut issues,
                SchemaInvalid,
                "钩子最低风险须为 low、medium 或 high",
            );
        }
        if let Some(pattern) = &hook.r#when.regex
            && hook_regex(pattern).is_none()
        {
            error(
                &mut issues,
                RegexInvalid,
                "钩子正则不受支持、无效或超过编译预算",
            );
        }
    }
    for context in &manifest.context {
        if context
            .r#when
            .as_ref()
            .and_then(|rule| rule.min_battery)
            .is_some_and(|battery| !(0..=100).contains(&battery))
        {
            error(&mut issues, SchemaInvalid, "上下文最低电量须为 0 至 100");
        }
        if !matches!(context.source.as_str(), "static" | "template")
            || context.content.encode_utf16().count() > 2000
        {
            error(
                &mut issues,
                SchemaInvalid,
                "context 来源无效或正文超过 2000 个字符",
            );
        } else if context.source == "template"
            && TEMPLATE.captures_iter(&context.content).any(|capture| {
                !matches!(
                    &capture[1],
                    "device.model"
                        | "device.battery"
                        | "device.storage_free"
                        | "time.now"
                        | "foreground.app"
                )
            })
        {
            error(
                &mut issues,
                UnresolvedVar,
                "context 使用了不受支持的模板变量",
            );
        }
    }
    if manifest.enabled_by_default {
        warning(
            &mut issues,
            SchemaInvalid,
            "已忽略 enabled_by_default，启用由宿主状态决定",
        );
    }
    if !manifest.triggers.is_empty() {
        warning(&mut issues, TriggersIgnored, "v1 不执行 triggers");
    }
    if !manifest.deps.is_empty() {
        warning(
            &mut issues,
            DepsNotInstalled,
            "v1 不自动安装依赖，请确认依赖产物已随插件提供",
        );
    }
    (Some(manifest), issues)
}

fn validate_exec(tool: &ToolDecl, env_names: &HashSet<&str>, issues: &mut Vec<PluginIssue>) {
    use ReasonCode::*;
    let exec = &tool.exec;
    if tool
        .risk
        .as_deref()
        .is_some_and(|risk| !matches!(risk, "low" | "medium" | "high"))
    {
        error(issues, SchemaInvalid, "工具风险须为 low、medium 或 high");
    }
    match exec.kind.as_str() {
        "http" => {
            let method = exec.method.as_deref().unwrap_or("GET").to_ascii_uppercase();
            if !matches!(
                method.as_str(),
                "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD"
            ) {
                error(
                    issues,
                    SchemaInvalid,
                    format!("工具 {} 的 HTTP 方法无效", tool.name),
                );
            }
            match exec.url.as_deref().filter(|url| !url.trim().is_empty()) {
                None => error(
                    issues,
                    SchemaInvalid,
                    format!("工具 {} 缺少 HTTP URL", tool.name),
                ),
                Some(url) if !http_template_allowed(url) => {
                    error(
                        issues,
                        HttpsRequired,
                        format!("工具 {} 须使用 HTTPS 或本机 127.0.0.1", tool.name),
                    );
                }
                _ => {}
            }
        }
        "shell" => {
            match exec
                .command
                .as_deref()
                .filter(|command| !command.trim().is_empty() && !command.contains('\0'))
            {
                None => error(
                    issues,
                    SchemaInvalid,
                    format!("工具 {} 的 Shell 命令为空或含 NUL", tool.name),
                ),
                Some(command) if dangerous_shell(command) => {
                    error(
                        issues,
                        DangerousPattern,
                        format!("工具 {} 的 Shell 命令命中禁用模式", tool.name),
                    );
                }
                _ => {}
            }
            if let Err(detail) = shell_working_directory(exec.cwd.as_deref()) {
                error(issues, UnsafePath, format!("工具 {}：{detail}", tool.name));
            }
        }
        _ => {
            error(
                issues,
                ExecTypeUnsupported,
                format!("工具 {} 的 exec.type 只支持 http 或 shell", tool.name),
            );
            return;
        }
    }

    let parameters = tool.parameters.as_ref().map(|schema| {
        schema
            .get("properties")
            .and_then(Value::as_object)
            .unwrap_or(schema)
    });
    let mut used_parameters = HashSet::new();
    let templates = [&exec.url, &exec.command, &exec.cwd, &exec.body]
        .into_iter()
        .filter_map(Option::as_deref)
        .chain(
            exec.headers
                .iter()
                .flat_map(|(name, value)| [name.as_str(), value.as_str()]),
        );
    for text in templates {
        for capture in TEMPLATE.captures_iter(text) {
            let variable = capture.get(1).expect("template capture").as_str();
            let known = match variable {
                "plugin_dir" | "src" => true,
                _ => match variable.strip_prefix("env.") {
                    Some(env) => env_names.contains(env),
                    None => {
                        used_parameters.insert(variable);
                        parameters.is_some_and(|params| params.contains_key(variable))
                    }
                },
            };
            if !known {
                error(
                    issues,
                    UnresolvedVar,
                    format!("工具 {} 使用了未声明的模板变量", tool.name),
                );
            }
        }
    }
    if parameters.is_some_and(|params| {
        params
            .keys()
            .any(|key| !used_parameters.contains(key.as_str()))
    }) {
        warning(
            issues,
            UnresolvedVar,
            format!("工具 {} 声明了模板中未使用的参数", tool.name),
        );
    }
}

fn http_template_allowed(url: &str) -> bool {
    url.starts_with("https://")
        || url.starts_with("{{")
        || url
            .strip_prefix("http://127.0.0.1")
            .is_some_and(|tail| tail.is_empty() || tail.starts_with([':', '/', '?', '#']))
}

fn dangerous_shell(command: &str) -> bool {
    SHELL_DENY.is_match(command)
        || FORCE_REMOVE.captures_iter(command).any(|capture| {
            let target = &capture[1];
            target.split('/').any(|part| part == "..")
                || !["/sdcard", "/storage", "/data/local/tmp"]
                    .iter()
                    .any(|&prefix| {
                        target == prefix
                            || target
                                .strip_prefix(prefix)
                                .is_some_and(|tail| tail.starts_with('/'))
                    })
        })
}

fn hook_regex(pattern: &str) -> Option<Regex> {
    if pattern.len() > MAX_HOOK_PATTERN_BYTES {
        return None;
    }
    RegexBuilder::new(pattern)
        .size_limit(128 * 1024)
        .dfa_size_limit(128 * 1024)
        .build()
        .ok()
}

#[derive(Clone, Debug)]
pub struct HookLog {
    pub plugin_id: String,
    pub message: String,
}

#[derive(Clone, Debug, Default)]
pub struct HookVerdict {
    pub deny: bool,
    pub require_confirm: bool,
    pub message: Option<String>,
    pub logs: Vec<HookLog>,
}

impl HookVerdict {
    fn denied(mut self, message: String) -> Self {
        self.deny = true;
        self.message = Some(message);
        self
    }
}

pub fn evaluate_hooks(
    entries: &[PluginEntry],
    tool_name: &str,
    arguments: &str,
    risk: Option<Risk>,
) -> HookVerdict {
    let mut verdict = HookVerdict::default();
    let mut remaining = MAX_HOOK_WORK;
    for entry in entries.iter().filter(|entry| entry.state.enabled) {
        let Some(manifest) = &entry.manifest else {
            continue;
        };
        if !entry.is_valid() {
            if manifest
                .hooks
                .iter()
                .any(|hook| hook.on == "tools/pre-execute" && hook_applies(hook, tool_name, risk))
            {
                return verdict.denied("已启用的插件规则无效，本次调用未执行".into());
            }
            continue;
        }
        for hook in &manifest.hooks {
            if hook.on != "tools/pre-execute" || !hook_applies(hook, tool_name, risk) {
                continue;
            }
            if let Some(pattern) = &hook.r#when.regex {
                let cost = arguments.len().saturating_add(128 * 1024);
                if arguments.len() > MAX_HOOK_ARGUMENT_BYTES || cost > remaining {
                    return verdict.denied("插件规则匹配超过预算，本次调用未执行".into());
                }
                remaining -= cost;
                let Some(regex) = hook_regex(pattern) else {
                    return verdict.denied("插件规则无效，本次调用未执行".into());
                };
                if !regex.is_match(arguments) {
                    continue;
                }
            }
            match hook.action.as_str() {
                "deny" => {
                    return verdict.denied(
                        hook.message
                            .clone()
                            .unwrap_or_else(|| format!("插件 {} 禁止调用 {tool_name}", entry.id)),
                    );
                }
                "require_confirm" => verdict.require_confirm = true,
                "log" if verdict.logs.len() < 64 => verdict.logs.push(HookLog {
                    plugin_id: entry.id.clone(),
                    message: hook.message.clone().unwrap_or_else(|| tool_name.into()),
                }),
                _ => {}
            }
        }
    }
    verdict
}

fn hook_applies(hook: &HookDecl, tool_name: &str, risk: Option<Risk>) -> bool {
    hook.r#when
        .tool
        .as_ref()
        .is_none_or(|name| name.replace('.', "_") == tool_name)
        && hook.r#when.min_risk.as_deref().is_none_or(|minimum| {
            risk.is_some_and(|risk| risk >= Risk::from_declaration(Some(minimum)))
        })
}

#[derive(Clone, Debug)]
pub struct ResolvedContext {
    pub plugin_id: String,
    pub text: String,
}

#[derive(Default)]
pub struct ResolvedContexts {
    pub entries: Vec<ResolvedContext>,
    pub skipped: Vec<String>,
}

fn render_context(
    template: &str,
    variables: &BTreeMap<String, String>,
) -> Result<Option<String>, String> {
    const MAX_CONTEXT_TEXT: usize = 16 * 1024;
    let mut output = String::new();
    let mut cursor = 0;
    for capture in TEMPLATE.captures_iter(template) {
        let full = capture.get(0).expect("whole context match");
        let key = &capture[1];
        // The public platform map cannot make arbitrary host values available to a plugin.
        if !matches!(
            key,
            "device.model"
                | "device.battery"
                | "device.storage_free"
                | "time.now"
                | "foreground.app"
        ) {
            return Ok(None);
        }
        let Some(value) = variables.get(key) else {
            return Ok(None);
        };
        let literal = &template[cursor..full.start()];
        if output
            .len()
            .saturating_add(literal.len())
            .saturating_add(value.len())
            > MAX_CONTEXT_TEXT
        {
            return Err("插件上下文模板展开超过 16 KiB".into());
        }
        output.push_str(literal);
        output.push_str(value);
        cursor = full.end();
    }
    if output.len().saturating_add(template.len() - cursor) > MAX_CONTEXT_TEXT {
        return Err("插件上下文模板展开超过 16 KiB".into());
    }
    output.push_str(&template[cursor..]);
    Ok(Some(output))
}

/// Reads only declared context and public platform variables. Environment values are excluded.
pub fn contexts_for(
    entries: &[PluginEntry],
    variables: &BTreeMap<String, String>,
) -> Result<ResolvedContexts, String> {
    const MAX_CONTEXT_BYTES: usize = 128 * 1024;
    let mut resolved = ResolvedContexts::default();
    let mut bytes = 0usize;
    for entry in entries
        .iter()
        .filter(|entry| entry.state.enabled && entry.is_valid())
    {
        let Some(manifest) = &entry.manifest else {
            continue;
        };
        for context in &manifest.context {
            if let Some(minimum) = context.r#when.as_ref().and_then(|rule| rule.min_battery) {
                let battery = variables
                    .get("device.battery")
                    .and_then(|value| value.parse::<i32>().ok());
                if !battery.is_some_and(|battery| (minimum..=100).contains(&battery)) {
                    if resolved.skipped.len() < 64 {
                        resolved
                            .skipped
                            .push(format!("插件 {} 的上下文未满足电量条件", entry.id));
                    }
                    continue;
                }
            }
            let text = if context.source == "static" {
                context.content.clone()
            } else {
                let Some(text) = render_context(&context.content, variables)? else {
                    if resolved.skipped.len() < 64 {
                        resolved
                            .skipped
                            .push(format!("插件 {} 的上下文模板缺少平台信息", entry.id));
                    }
                    continue;
                };
                text
            };
            bytes = bytes.checked_add(text.len()).ok_or("插件上下文大小溢出")?;
            if text.len() > 16 * 1024 || bytes > MAX_CONTEXT_BYTES || resolved.entries.len() == 256
            {
                return Err("已启用插件的上下文超过运行预算".into());
            }
            resolved.entries.push(ResolvedContext {
                plugin_id: entry.id.clone(),
                text,
            });
        }
    }
    Ok(resolved)
}

/// `root` is the `plugins` child of the trusted app-private data directory.
/// The scan reads only manifest/state files and never loads the plugin's environment.
#[cfg(unix)]
pub fn discover(
    root: &Path,
    reserved_tool_names: &HashSet<String>,
) -> Result<Vec<PluginEntry>, String> {
    unix::discover(root, reserved_tool_names)
}

#[cfg(not(unix))]
pub fn discover(
    _root: &Path,
    _reserved_tool_names: &HashSet<String>,
) -> Result<Vec<PluginEntry>, String> {
    Err("当前平台未接入基于目录句柄的插件扫描".into())
}

#[cfg(unix)]
mod unix {
    use super::*;
    use std::{
        ffi::{CStr, CString, OsStr, OsString},
        fs::File,
        io::{self, Read},
        mem::MaybeUninit,
        os::{
            fd::{AsRawFd, FromRawFd, RawFd},
            unix::ffi::{OsStrExt, OsStringExt},
        },
    };

    const MAX_DIRECTORY_ENTRIES: usize = 4096;
    const MAX_PLUGINS: usize = 128;
    const MAX_STATE_BYTES: usize = 8 * 1024;
    const DIRECTORY_FLAGS: libc::c_int =
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC;

    pub(super) fn discover(
        root: &Path,
        reserved: &HashSet<String>,
    ) -> Result<Vec<PluginEntry>, String> {
        let directory = match Directory::root(root) {
            Ok(directory) => directory,
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(err) => return Err(format!("无法打开插件根目录：{err}")),
        };
        let mut entries = directory
            .plugin_names()?
            .into_iter()
            .map(|name| read_entry(&directory, root, &name, reserved))
            .collect::<Vec<_>>();
        entries.sort_by(|left, right| left.id.cmp(&right.id));
        Ok(entries)
    }

    fn read_entry(
        parent: &Directory,
        root: &Path,
        name: &OsStr,
        reserved: &HashSet<String>,
    ) -> PluginEntry {
        use ReasonCode::*;
        let mut entry = PluginEntry {
            id: name.to_string_lossy().into_owned(),
            dir: root.join(name),
            manifest: None,
            state: PluginState::default(),
            level: None,
            issues: Vec::new(),
            environment: BTreeMap::new(),
        };
        if !valid_id(&entry.id) {
            error(&mut entry.issues, IdMismatch, "插件目录名不符合 ID 规则");
            return entry;
        }
        let directory = match parent.child(name) {
            Ok(directory) => directory,
            Err(err) => {
                error(
                    &mut entry.issues,
                    UnsafePath,
                    format!("无法安全打开插件目录：{err}"),
                );
                return entry;
            }
        };
        match directory.read_file(MANIFEST_FILE, MAX_MANIFEST_BYTES) {
            Ok(Some(bytes)) => {
                (entry.manifest, entry.issues) = validate_manifest(&bytes, &entry.id, reserved)
            }
            Ok(None) => error(
                &mut entry.issues,
                SchemaInvalid,
                "插件目录缺少 manifest.json",
            ),
            Err(err) => error(
                &mut entry.issues,
                SchemaInvalid,
                format!("无法读取 manifest.json：{err}"),
            ),
        }
        match directory.read_file(STATE_FILE, MAX_STATE_BYTES) {
            Ok(Some(bytes)) => match serde_json::from_slice::<PluginState>(&bytes) {
                Ok(state) if state.installed_at >= 0 => entry.state = state,
                _ => error(
                    &mut entry.issues,
                    StateInvalid,
                    "state.json 无效，插件处于停用状态",
                ),
            },
            Ok(None) => (),
            Err(err) => error(
                &mut entry.issues,
                StateInvalid,
                format!("无法读取 state.json：{err}"),
            ),
        }

        let mut layout = |name: &str, expected| match directory.kind(OsStr::new(name)) {
            Ok(Some(kind)) if kind == libc::S_IFLNK => {
                error(
                    &mut entry.issues,
                    UnsafePath,
                    format!("插件路径 {name} 不能是符号链接"),
                );
                false
            }
            Ok(kind) => kind == Some(expected),
            Err(err) => {
                error(
                    &mut entry.issues,
                    UnsafePath,
                    format!("无法检查插件路径 {name}：{err}"),
                );
                false
            }
        };
        let scripts = layout("scripts", libc::S_IFDIR);
        let assets = layout("assets", libc::S_IFDIR);
        let instructions = layout("INSTRUCTIONS.md", libc::S_IFREG);
        entry.level = entry.manifest.as_ref().map(|manifest| {
            if !manifest.deps.is_empty() {
                PluginLevel::L4
            } else if scripts || assets {
                PluginLevel::L3
            } else if instructions {
                PluginLevel::L2
            } else {
                PluginLevel::L1
            }
        });
        entry
    }

    struct Directory(File);

    impl Directory {
        fn root(path: &Path) -> io::Result<Self> {
            // A final slash makes open(2) follow a symlink even with O_NOFOLLOW.
            let bytes = path.as_os_str().as_bytes();
            let trimmed = bytes
                .iter()
                .rposition(|&byte| byte != b'/')
                .map_or(bytes, |last| &bytes[..=last]);
            open_at(libc::AT_FDCWD, OsStr::from_bytes(trimmed), DIRECTORY_FLAGS).map(Self)
        }

        fn child(&self, name: &OsStr) -> io::Result<Self> {
            open_at(self.0.as_raw_fd(), name, DIRECTORY_FLAGS).map(Self)
        }

        fn kind(&self, name: &OsStr) -> io::Result<Option<libc::mode_t>> {
            let name = CString::new(name.as_bytes())?;
            let mut stat = MaybeUninit::<libc::stat>::uninit();
            // SAFETY: the directory descriptor and C string are live and stat points to writable storage.
            let result = unsafe {
                libc::fstatat(
                    self.0.as_raw_fd(),
                    name.as_ptr(),
                    stat.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            };
            if result == 0 {
                // SAFETY: fstatat initialized the structure on success.
                return Ok(Some(
                    (unsafe { stat.assume_init() }.st_mode as libc::mode_t) & libc::S_IFMT,
                ));
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::NotFound {
                Ok(None)
            } else {
                Err(err)
            }
        }

        fn read_file(&self, name: &str, limit: usize) -> io::Result<Option<Vec<u8>>> {
            let name = OsStr::new(name);
            match self.kind(name)? {
                None => return Ok(None),
                Some(libc::S_IFREG) => (),
                _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "仅支持普通文件")),
            }
            let file = open_at(
                self.0.as_raw_fd(),
                name,
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )?;
            let metadata = file.metadata()?;
            if !metadata.is_file() || metadata.len() > limit as u64 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "文件类型无效或超过大小上限",
                ));
            }
            let mut bytes = Vec::new();
            file.take(limit as u64 + 1).read_to_end(&mut bytes)?;
            if bytes.len() > limit {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "文件读取期间超过大小上限",
                ));
            }
            Ok(Some(bytes))
        }

        fn plugin_names(&self) -> Result<Vec<OsString>, String> {
            // SAFETY: self owns a live descriptor; fdopendir takes ownership of the duplicate.
            let fd = unsafe { libc::fcntl(self.0.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
            if fd < 0 {
                return Err(format!("无法读取插件目录：{}", io::Error::last_os_error()));
            }
            let stream = unsafe { libc::fdopendir(fd) };
            if stream.is_null() {
                let err = io::Error::last_os_error();
                unsafe { libc::close(fd) };
                return Err(format!("无法遍历插件目录：{err}"));
            }
            let stream = DirectoryStream(stream);
            let mut names = Vec::new();
            for _ in 0..MAX_DIRECTORY_ENTRIES {
                // SAFETY: errno is thread-local and readdir owns its result until the next call.
                let entry = unsafe {
                    *errno() = 0;
                    libc::readdir(stream.0)
                };
                if entry.is_null() {
                    if unsafe { *errno() } != 0 {
                        return Err(format!("无法遍历插件目录：{}", io::Error::last_os_error()));
                    }
                    return Ok(names);
                }
                let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) }.to_bytes();
                if name.starts_with(b".") {
                    continue;
                }
                let kind = unsafe { (*entry).d_type };
                if matches!(kind, libc::DT_DIR | libc::DT_LNK | libc::DT_UNKNOWN) {
                    // Some filesystems do not supply d_type. Preserve directory candidates on a stat failure.
                    if kind == libc::DT_UNKNOWN
                        && matches!(self.kind(OsStr::from_bytes(name)), Ok(Some(kind)) if !matches!(kind, libc::S_IFDIR | libc::S_IFLNK))
                    {
                        continue;
                    }
                    if names.len() == MAX_PLUGINS {
                        return Err(format!("插件目录超过 {MAX_PLUGINS} 个插件的扫描上限"));
                    }
                    names.push(OsString::from_vec(name.to_vec()));
                }
            }
            Err(format!(
                "插件根目录超过 {MAX_DIRECTORY_ENTRIES} 个目录项的扫描上限"
            ))
        }
    }

    struct DirectoryStream(*mut libc::DIR);

    impl Drop for DirectoryStream {
        fn drop(&mut self) {
            // SAFETY: this guard uniquely owns the open stream and its descriptor.
            unsafe { libc::closedir(self.0) };
        }
    }

    fn open_at(parent: RawFd, name: &OsStr, flags: libc::c_int) -> io::Result<File> {
        let name = CString::new(name.as_bytes())?;
        // SAFETY: name is NUL-terminated; flags do not create files.
        let fd = unsafe { libc::openat(parent, name.as_ptr(), flags) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: the successful call returned a new owned descriptor.
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    fn errno() -> *mut libc::c_int {
        #[cfg(target_os = "android")]
        unsafe {
            libc::__errno()
        }
        #[cfg(target_os = "linux")]
        unsafe {
            libc::__errno_location()
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        unsafe {
            libc::__error()
        }
    }
}

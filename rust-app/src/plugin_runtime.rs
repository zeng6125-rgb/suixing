//! Execution of approved manifest tools using the existing HTTP and app-UID shell boundaries.
use crate::{
    network::{self, CancelToken},
    plugins::{PluginEntry, ResponseDecl, ToolDecl, shell_working_directory},
    shell,
};
use regex::Regex;
use reqwest::{
    Method, Url,
    header::{CONTENT_TYPE, HeaderMap, HeaderName, HeaderValue},
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::Path,
    sync::{Arc, LazyLock},
    time::Duration,
};
use suixing_search::rust_protocol::ToolDefinition;

const MAX_TEMPLATE_OUTPUT: usize = 256 * 1024;
const MAX_BODY: usize = 256 * 1024;
static PLACEHOLDER: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}")
        .expect("constant plugin placeholder pattern")
});

enum Encoding {
    Plain,
    Shell,
    Url,
    Header,
    Json,
}

fn available(entries: &[PluginEntry]) -> impl Iterator<Item = (&PluginEntry, &ToolDecl)> {
    entries
        .iter()
        .filter(|entry| entry.state.enabled && entry.is_valid())
        .filter_map(|entry| entry.manifest.as_ref().map(|manifest| (entry, manifest)))
        .flat_map(|(entry, manifest)| manifest.tools.iter().map(move |tool| (entry, tool)))
        .filter(|(_, tool)| {
            let exec = &tool.exec;
            exec.kind == "http" || exec.kind == "shell"
        })
}

pub fn definitions(entries: &[PluginEntry]) -> impl Iterator<Item = ToolDefinition> {
    let mut definitions: Vec<_> = available(entries)
        .map(|(entry, tool)| tool.definition(&entry.id))
        .collect();
    definitions.sort_by(|left, right| left.name.cmp(&right.name));
    definitions.into_iter()
}

/// Validate a whole frozen catalog before starting a run. Do not silently drop tools.
pub fn validate_catalog(entries: &[PluginEntry]) -> Result<(), String> {
    let mut count = 0usize;
    let mut bytes = 0usize;
    for (entry, tool) in available(entries) {
        count += 1;
        let definition = tool.definition(&entry.id);
        let encoded =
            serde_json::to_vec(&definition.parameters).map_err(|_| "插件工具参数定义无法编码")?;
        bytes = bytes
            .checked_add(encoded.len())
            .and_then(|bytes| bytes.checked_add(definition.name.len()))
            .and_then(|bytes| bytes.checked_add(definition.description.len()))
            .ok_or("插件工具定义大小溢出")?;
        if count > 256 || bytes > 1024 * 1024 {
            return Err("已启用插件超过 256 个工具或 1 MiB 工具定义的运行预算".into());
        }
    }
    Ok(())
}

pub fn resolve<'a>(
    entries: &'a [PluginEntry],
    name: &str,
) -> Option<(&'a PluginEntry, &'a ToolDecl)> {
    available(entries)
        .find(|(entry, tool)| crate::plugins::registered_name(&entry.id, &tool.name) == name)
}

fn render(
    template: &str,
    vars: &HashMap<String, String>,
    encoding: Encoding,
) -> Result<String, String> {
    let mut result = String::new();
    let mut cursor = 0;
    let mut in_string = false;
    let mut escaped = false;
    for capture in PLACEHOLDER.captures_iter(template) {
        let full = capture.get(0).expect("whole match");
        let literal = &template[cursor..full.start()];
        if matches!(encoding, Encoding::Json) {
            for byte in literal.bytes() {
                if escaped {
                    escaped = false;
                } else if in_string && byte == b'\\' {
                    escaped = true;
                } else if byte == b'"' {
                    in_string = !in_string;
                }
            }
        }
        result.push_str(literal);
        let key = &capture[1];
        let value = vars
            .get(key)
            .ok_or_else(|| format!("插件缺少模板变量 {key}"))?;
        match encoding {
            Encoding::Plain => result.push_str(value),
            Encoding::Shell => {
                result.push('\'');
                result.push_str(&value.replace('\'', "'\\''"));
                result.push('\'');
            }
            Encoding::Url => result.push_str(&url_value(value)),
            Encoding::Header => result.push_str(&value.replace(['\r', '\n'], " ")),
            Encoding::Json if in_string => {
                let encoded = serde_json::to_string(value).map_err(|_| "插件变量编码失败")?;
                result.push_str(&encoded[1..encoded.len() - 1]);
            }
            Encoding::Json => result.push_str(value),
        }
        if result.len() > MAX_TEMPLATE_OUTPUT {
            return Err("插件模板展开超过 256 KiB".into());
        }
        cursor = full.end();
    }
    result.push_str(&template[cursor..]);
    if result.len() > MAX_TEMPLATE_OUTPUT || result.contains('\0') {
        return Err("插件模板展开超过上限或包含 NUL".into());
    }
    Ok(result)
}

fn url_value(value: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::new();
    for byte in value.bytes() {
        match byte {
            b' ' => encoded.push('+'),
            b if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'*') => {
                encoded.push(b.into())
            }
            b => {
                encoded.push('%');
                encoded.push(HEX[(b >> 4) as usize].into());
                encoded.push(HEX[(b & 15) as usize].into());
            }
        }
    }
    encoded
}

fn redact(mut text: String, secrets: &[String]) -> String {
    for secret in secrets {
        text = text.replace(secret, "[redacted]");
    }
    text
}

fn output(
    raw: &str,
    response: Option<&ResponseDecl>,
    secrets: &[String],
) -> Result<(String, bool), String> {
    let selected = if let Some(path) = response
        .and_then(|response| response.pick.as_deref())
        .filter(|path| !path.trim().is_empty())
    {
        let value: Value = serde_json::from_str(raw).map_err(|_| "插件响应不是可选取的 JSON")?;
        let mut current = &value;
        for key in path.split('.').map(str::trim).filter(|key| !key.is_empty()) {
            current = match current {
                Value::Object(object) => object.get(key),
                Value::Array(array) => key.parse::<usize>().ok().and_then(|index| array.get(index)),
                _ => None,
            }
            .ok_or("插件响应中找不到配置的 pick 路径")?;
        }
        current.to_string()
    } else {
        raw.to_owned()
    };
    let limit = response.map_or(8000, |response| response.max_chars.clamp(100, 16_000)) as usize;
    let redacted = redact(selected, secrets);
    let mut chars = redacted.chars();
    let text: String = chars.by_ref().take(limit).collect();
    Ok((text, chars.next().is_some()))
}

fn plugin_cwd(directory: &Path, cwd: Option<&str>) -> Result<std::path::PathBuf, String> {
    let relative = shell_working_directory(cwd)?;
    let mut path = directory.to_owned();
    for part in relative.components() {
        path.push(part);
        let metadata = std::fs::symlink_metadata(&path).map_err(|_| "插件工作目录不存在")?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err("插件工作目录必须是目录且不能经过符号链接".into());
        }
    }
    let canonical = path.canonicalize().map_err(|_| "插件工作目录不可用")?;
    if !canonical.starts_with(directory) {
        return Err("插件工作目录超出插件目录".into());
    }
    Ok(canonical)
}

pub(crate) struct Prepared<'a> {
    entry: &'a PluginEntry,
    tool: &'a ToolDecl,
    arguments: String,
    shell: Option<crate::tools::FrozenShell>,
    plugin_layout: Vec<crate::plugin_assets::Layout>,
    pub approval_summary: String,
}
pub(crate) fn prepare<'a>(
    entry: &'a PluginEntry,
    tool: &'a ToolDecl,
    call: &crate::state::ToolCall,
    cancel: &CancelToken,
) -> Result<Prepared<'a>, String> {
    if !entry.state.enabled || !entry.is_valid() || call.arguments.len() > 128 * 1024 {
        return Err("插件调用已停用、无效或超过参数预算".into());
    }
    let directory = entry.dir.parent().ok_or("插件缺少拥有目录")?;
    let relative = entry
        .dir
        .strip_prefix(directory)
        .map_err(|_| "插件目录越界")?;
    let plugin_layout = crate::plugin_assets::scan_tree(directory, relative, false, cancel)?.layout;
    let mut shell = None;
    let mut summary = format!("插件 {} / {}", entry.id, tool.name);
    if tool.exec.kind == "shell" {
        let arguments: Value =
            serde_json::from_str(&call.arguments).map_err(|_| "插件参数不是JSON")?;
        let mut variables = HashMap::new();
        for (key, value) in arguments.as_object().ok_or("插件参数不是对象")? {
            if !key.starts_with("env.") && key != "plugin_dir" {
                variables.insert(
                    key.clone(),
                    value
                        .as_str()
                        .map_or_else(|| value.to_string(), str::to_owned),
                );
            }
        }
        for variable in &entry.manifest.as_ref().ok_or("插件清单缺失")?.env {
            if let Some(value) = entry
                .environment
                .get(&variable.name)
                .filter(|value| !value.is_empty())
            {
                variables.insert(format!("env.{}", variable.name), value.clone());
            } else if variable.required {
                return Err(format!("插件环境变量 {} 尚未设置", variable.name));
            }
        }
        variables.insert(
            "plugin_dir".into(),
            entry.dir.to_string_lossy().into_owned(),
        );
        let command = render(
            tool.exec.command.as_deref().ok_or("插件缺少命令")?,
            &variables,
            Encoding::Shell,
        )?;
        let cwd = plugin_cwd(&entry.dir, tool.exec.cwd.as_deref())?;
        let owner = crate::controller::routing_owner()?;
        let selected_shell = tool
            .exec
            .shell
            .as_deref()
            .unwrap_or("auto")
            .trim()
            .to_ascii_lowercase();
        let selected_shell = if selected_shell.is_empty() {
            "auto"
        } else {
            &selected_shell
        };
        let synthetic = crate::state::ToolCall {
            id: call.id.clone(), name: "run_shell".into(),
            arguments: json!({"command":command,"mode":if tool.exec.needs_root {"root"} else {"local"},"shell":selected_shell,"observe_paths":[entry.dir]}).to_string(),
        };
        let frozen = owner.prepare_shell(
            &synthetic,
            format!("plugin-shell-{}", crate::controller::unique_id()),
        )?;
        let mut request = frozen.request;
        if tool.exec.needs_root && !matches!(request.route, crate::shell_runner::Route::Root { .. })
        {
            return Err("插件要求实际Root，但当前设置或实际授权未允许Root路线；未降级执行".into());
        }
        request.cwd = cwd;
        request.timeout_ms = (tool.exec.timeout_seconds.clamp(1, 600) as u64) * 1000;
        request.max_output_bytes = 256 * 1024;
        request.validate()?;
        let executor = if matches!(request.route, crate::shell_runner::Route::AppUid) {
            None
        } else {
            owner.context().privileged.clone()
        };
        summary.push_str(&format!(
            "；执行身份 {:?}；Shell {}；目录 {}",
            request.route,
            request.argv[0],
            request.cwd.display()
        ));
        shell = Some(crate::tools::FrozenShell {
            owner,
            revision: frozen.revision,
            request,
            executor,
            started: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        });
    }
    Ok(Prepared {
        entry,
        tool,
        arguments: call.arguments.clone(),
        shell,
        plugin_layout,
        approval_summary: summary,
    })
}
pub(crate) async fn execute_prepared(prepared: &Prepared<'_>, cancel: Arc<CancelToken>) -> Value {
    // A manifest Shell command uses the same device/shell exclusion as main
    // and child builtins, even when its registered tool has a custom name.
    let _shared_device = match crate::tool_execution::guard(prepared.shell.is_some(), &cancel) {
        Ok(guard) => guard,
        Err(error) => return json!({"ok":false,"started":false,"error":error}),
    };
    let result = (|| {
        let root = prepared.entry.dir.parent().ok_or("插件拥有目录缺失")?;
        crate::plugin_assets::verify_layout(root, &prepared.plugin_layout)?;
        Ok::<(), String>(())
    })();
    if let Err(error) = result {
        return json!({"ok":false,"started":false,"error":error});
    }
    execute_inner(
        prepared.entry,
        prepared.tool,
        &prepared.arguments,
        cancel,
        prepared.shell.as_ref(),
    )
    .await
    .unwrap_or_else(|error| json!({"ok":false,"error":error}))
}

pub async fn execute(
    entry: &PluginEntry,
    tool: &ToolDecl,
    arguments: &str,
    cancel: Arc<CancelToken>,
) -> Value {
    execute_inner(entry, tool, arguments, cancel, None)
        .await
        .unwrap_or_else(|error| json!({"ok":false,"error":error}))
}

async fn execute_inner(
    entry: &PluginEntry,
    tool: &ToolDecl,
    arguments: &str,
    cancel: Arc<CancelToken>,
    frozen: Option<&crate::tools::FrozenShell>,
) -> Result<Value, String> {
    if arguments.len() > 128 * 1024 {
        return Err("插件工具参数超过 128 KiB".into());
    }
    if !entry.state.enabled || !entry.is_valid() {
        return Err("插件未启用或清单无效，本次调用未执行".into());
    }
    let manifest = entry.manifest.as_ref().ok_or("插件清单不可用")?;
    let args: Value = serde_json::from_str(arguments).map_err(|_| "插件工具参数需要 JSON 对象")?;
    let mut vars = HashMap::new();
    for (key, value) in args.as_object().ok_or("插件工具参数需要 JSON 对象")? {
        if !key.starts_with("env.") && key != "plugin_dir" {
            vars.insert(
                key.clone(),
                value
                    .as_str()
                    .map_or_else(|| value.to_string(), str::to_owned),
            );
        }
    }
    let mut secrets = Vec::new();
    for variable in &manifest.env {
        let value = entry
            .environment
            .get(&variable.name)
            .filter(|value| !value.is_empty());
        if variable.required && value.is_none() {
            return Err(format!("插件环境变量 {} 尚未设置", variable.name));
        }
        if let Some(value) = value {
            vars.insert(format!("env.{}", variable.name), value.clone());
            if variable.secret {
                secrets.push(value.clone());
                secrets.push(url_value(value));
                if let Ok(escaped) = serde_json::to_string(value) {
                    secrets.push(escaped[1..escaped.len() - 1].into());
                }
            }
        }
    }
    secrets.sort_unstable_by_key(|secret| std::cmp::Reverse(secret.len()));
    secrets.dedup();
    vars.insert(
        "plugin_dir".into(),
        entry.dir.to_string_lossy().into_owned(),
    );
    let exec = &tool.exec;
    let timeout = Duration::from_secs(exec.timeout_seconds.clamp(1, 600) as u64);
    if cancel.is_cancelled() {
        return Ok(json!({"ok":false,"cancelled":true,"started":false}));
    }
    match exec.kind.as_str() {
        "shell" => {
            let frozen = frozen.ok_or("插件Shell没有审批前冻结请求，未执行")?;
            frozen
                .owner
                .validate_frozen(frozen.revision, frozen.request.route)?;
            use std::sync::atomic::Ordering;
            if frozen.started.swap(true, Ordering::AcqRel) {
                return Err("插件Shell已派发过，未重复执行".into());
            }
            let owner = frozen.owner.clone();
            let revision = frozen.revision;
            let route = frozen.request.route;
            let flag = crate::controller::cancellation::flag(
                cancel.clone(),
                Some(Arc::new(move || {
                    owner.validate_frozen(revision, route).is_ok()
                })),
            )?;
            let outcome =
                crate::shell_runner::execute(&frozen.request, flag, frozen.executor.as_deref());
            let mut result = json!({"ok":outcome.succeeded(),"started":outcome.started,"route":outcome.route,
                "identity_confirmed":outcome.identity_confirmed,"primary_exit_confirmed":outcome.primary_exit_confirmed,
                "group_cleanup_confirmed":outcome.group_cleanup_confirmed,"exit_code":outcome.exit_code,
                "cancel_requested":outcome.cancel_requested,"timed_out":outcome.timed_out,
                "stdout":outcome.stdout,"stderr":outcome.stderr,"stdout_bytes":outcome.stdout_bytes,"stderr_bytes":outcome.stderr_bytes,
                "truncated":outcome.truncated,"error":outcome.error,"file_activities":outcome.file_changes.activities,"notice":outcome.file_changes.notice});
            for key in ["stdout", "stderr"] {
                if let Some(raw) = result[key].as_str() {
                    let (text, truncated) = output(
                        raw,
                        exec.response
                            .as_ref()
                            .filter(|response| response.pick.is_none()),
                        &secrets,
                    )?;
                    result[key] = json!(text);
                    if truncated {
                        result["output_truncated"] = json!(true);
                    }
                }
            }
            Ok(result)
        }
        "http" => {
            let method = Method::from_bytes(
                exec.method
                    .as_deref()
                    .unwrap_or("GET")
                    .to_ascii_uppercase()
                    .as_bytes(),
            )
            .map_err(|_| "插件 HTTP 方法无效")?;
            let url = render(
                exec.url.as_deref().ok_or("插件缺少 HTTP URL")?,
                &vars,
                Encoding::Url,
            )?;
            let url = Url::parse(&url).map_err(|_| "插件 HTTP URL 无效")?;
            if !matches!(url.scheme(), "http" | "https")
                || url.host_str().is_none()
                || !url.username().is_empty()
                || url.password().is_some()
            {
                return Err("插件 URL 必须为不含内嵌凭据的 HTTP/HTTPS 地址".into());
            }
            if url.scheme() != "https" && url.host_str() != Some("127.0.0.1") {
                return Err("插件 HTTP 地址须使用 HTTPS 或本机 127.0.0.1，本次请求未发送".into());
            }
            let mut headers = HeaderMap::new();
            for (name, value) in &exec.headers {
                let name = render(name, &vars, Encoding::Plain)?;
                let value = render(value, &vars, Encoding::Header)?;
                headers.insert(
                    HeaderName::from_bytes(name.as_bytes()).map_err(|_| "插件 HTTP 头名称无效")?,
                    HeaderValue::from_str(&value).map_err(|_| "插件 HTTP 头值无效")?,
                );
            }
            let permits_body = matches!(
                method,
                Method::POST | Method::PUT | Method::PATCH | Method::DELETE
            );
            let body = permits_body
                .then(|| render(exec.body.as_deref().unwrap_or(""), &vars, Encoding::Json))
                .transpose()?;
            if permits_body && !headers.contains_key(CONTENT_TYPE) {
                headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
            }
            let client = network::client()?;
            network::ensure_network_permission(url.as_str())?;
            let mut request = client.request(method, url).headers(headers);
            if let Some(body) = body {
                request = request.body(body);
            }
            let operation = async {
                let mut response = request.send().await.map_err(|_| "插件 HTTP 请求失败")?;
                let status = response.status();
                if status.is_redirection() {
                    return Ok(
                        json!({"ok":false,"status":status.as_u16(),"error":"插件重定向需要另行审批","effects_may_have_occurred":true}),
                    );
                }
                let mut raw = Vec::new();
                let mut truncated = false;
                while let Some(chunk) = response
                    .chunk()
                    .await
                    .map_err(|_| "插件 HTTP 响应读取失败")?
                {
                    let room = MAX_BODY.saturating_sub(raw.len());
                    raw.extend_from_slice(&chunk[..room.min(chunk.len())]);
                    if chunk.len() > room {
                        truncated = true;
                        break;
                    }
                }
                let response = exec.response.as_ref().filter(|_| status.is_success());
                let (text, clipped) = match output(
                    &String::from_utf8_lossy(&raw),
                    response,
                    &secrets,
                ) {
                    Ok(value) => value,
                    Err(error) => {
                        return Ok(
                            json!({"ok":false,"status":status.as_u16(),"error":error,"effects_may_have_occurred":true}),
                        );
                    }
                };
                Ok::<Value, String>(
                    json!({"ok":status.is_success(),"status":status.as_u16(),"text":text,"output_truncated":truncated || clipped,"effects_may_have_occurred":true}),
                )
            };
            let result = tokio::select! {
                biased;
                _ = cancel.cancelled() => Ok(json!({"ok":false,"cancelled":true,"effects_may_have_occurred":true})),
                result = tokio::time::timeout(timeout, operation) => {
                    result.unwrap_or_else(|_| Ok(json!({"ok":false,"timed_out":true,"effects_may_have_occurred":true})))
                }
            };
            Ok(result.unwrap_or_else(
                |error| json!({"ok":false,"error":error,"effects_may_have_occurred":true}),
            ))
        }
        _ => Err("插件执行类型尚未接入".into()),
    }
}

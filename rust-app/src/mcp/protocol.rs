use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};

pub const PROTOCOL_VERSION: &str = "2024-11-05";
pub const MAX_MESSAGE: usize = 1024 * 1024;
pub const MAX_ARGUMENTS: usize = 128 * 1024;
pub const MAX_RESULT: usize = 256 * 1024;
pub const MAX_SERVERS: usize = 32;
pub const MAX_TOOLS: usize = 256;
pub const MAX_CATALOG: usize = 1024 * 1024;

fn default_transport() -> String {
    "stdio".into()
}
fn default_enabled() -> bool {
    true
}

/// Legacy mcp.json field names are preserved. Credential strings are memory-only until
/// the platform secret store is attached; metadata serialization cannot persist them.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerConfig {
    #[serde(default)]
    pub id: String,
    pub name: String,
    #[serde(default = "default_transport")]
    pub transport: String,
    #[serde(default)]
    pub command: String,
    #[serde(default)]
    pub url: String,
    #[serde(skip)]
    pub env: String,
    #[serde(skip)]
    pub headers: String,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            transport: default_transport(),
            command: String::new(),
            url: String::new(),
            env: String::new(),
            headers: String::new(),
            enabled: true,
        }
    }
}

impl std::fmt::Debug for ServerConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ServerConfig")
            .field("id", &self.id)
            .field("name", &self.name)
            .field("transport", &self.transport)
            .field("enabled", &self.enabled)
            .field("command", &"[redacted]")
            .field("url", &"[redacted]")
            .field("env", &"[redacted]")
            .field("headers", &"[redacted]")
            .finish()
    }
}

impl ServerConfig {
    pub fn metadata(&self) -> Self {
        let mut copy = self.clone();
        copy.env.clear();
        copy.headers.clear();
        copy
    }

    pub(super) fn fingerprint(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        for part in [
            &self.id,
            &self.name,
            &self.transport,
            &self.command,
            &self.url,
            &self.env,
            &self.headers,
        ] {
            hash.update((part.len() as u64).to_le_bytes());
            hash.update(part.as_bytes());
        }
        hash.update([u8::from(self.enabled)]);
        hash.finalize().into()
    }

    pub(super) fn secrets(&self) -> Vec<String> {
        let mut values = Vec::new();
        for value in parse_env(&self.env)
            .unwrap_or_default()
            .into_values()
            .chain(
                parse_headers(&self.headers)
                    .unwrap_or_default()
                    .into_values(),
            )
        {
            if !value.is_empty() {
                if let Some(token) = value
                    .strip_prefix("Bearer ")
                    .or_else(|| value.strip_prefix("bearer "))
                {
                    if !token.is_empty() {
                        values.push(token.to_owned());
                    }
                }
                if let Ok(encoded) = serde_json::to_string(&value) {
                    values.push(encoded[1..encoded.len() - 1].into());
                }
                values.push(value);
            }
        }
        values.sort_unstable_by_key(|value| std::cmp::Reverse(value.len()));
        values.dedup();
        values
    }
}

pub fn normalize_configs(mut configs: Vec<ServerConfig>) -> Result<Vec<ServerConfig>, String> {
    if configs.len() > MAX_SERVERS {
        return Err("MCP 配置超过 32 个服务器".into());
    }
    let mut ids = HashSet::new();
    let mut names = HashSet::new();
    let mut bytes = 0usize;
    for config in &mut configs {
        config.name = config.name.trim().to_owned();
        if config.id.is_empty() {
            config.id =
                format!("legacy-{:x}", Sha256::digest(config.name.as_bytes()))[..39].to_owned();
        }
        validate_config(config)?;
        if !ids.insert(config.id.clone()) || !names.insert(config.name.clone()) {
            return Err("MCP 服务器身份或名称重复".into());
        }
        bytes = bytes
            .saturating_add(config.command.len())
            .saturating_add(config.url.len())
            .saturating_add(config.env.len())
            .saturating_add(config.headers.len());
        if bytes > 1024 * 1024 {
            return Err("MCP 配置总量超过 1 MiB".into());
        }
    }
    Ok(configs)
}

pub fn validate_config(config: &ServerConfig) -> Result<(), String> {
    if config.name.trim().is_empty()
        || config.name.len() > 128
        || config.name.chars().any(char::is_control)
        || config.id.len() > 128
        || config.id.chars().any(char::is_control)
    {
        return Err("MCP 名称或身份为空、包含控制字符或超过 128 字节".into());
    }
    if config.env.len() > 64 * 1024 || config.headers.len() > 64 * 1024 {
        return Err("MCP 环境变量或请求头超过 64 KiB".into());
    }
    match config.transport.as_str() {
        "stdio" => {
            if config.command.len() > 64 * 1024 {
                return Err("MCP 命令超过 64 KiB".into());
            }
            split_command(&config.command)?;
            parse_env(&config.env)?;
        }
        "http" => {
            if config.url.len() > 8192 {
                return Err("MCP 地址超过 8 KiB".into());
            }
            let url = reqwest::Url::parse(config.url.trim()).map_err(|_| "MCP HTTP 地址无效")?;
            if !matches!(url.scheme(), "http" | "https")
                || !url.has_host()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.fragment().is_some()
            {
                return Err("MCP 地址须为无内嵌凭据或片段的 HTTP/HTTPS 地址".into());
            }
            parse_headers(&config.headers)?;
        }
        _ => return Err("MCP 传输类型只支持 stdio 和 http".into()),
    }
    Ok(())
}

pub(super) fn split_command(line: &str) -> Result<Vec<String>, String> {
    let mut result = Vec::new();
    let mut word = String::new();
    let mut quote = None;
    let mut escaped = false;
    let mut started = false;
    for ch in line.chars() {
        if ch == '\0' {
            return Err("MCP 命令包含 NUL".into());
        }
        if escaped {
            word.push(ch);
            escaped = false;
            started = true;
        } else if ch == '\\' && quote != Some('\'') {
            escaped = true;
            started = true;
        } else if quote == Some(ch) {
            quote = None;
        } else if quote.is_some() {
            word.push(ch);
        } else if ch == '\'' || ch == '"' {
            quote = Some(ch);
            started = true;
        } else if ch.is_whitespace() {
            if started {
                result.push(std::mem::take(&mut word));
                started = false;
            }
        } else {
            word.push(ch);
            started = true;
        }
        if result.len() > 128 {
            return Err("MCP 命令参数超过 128 个".into());
        }
    }
    if escaped || quote.is_some() {
        return Err("MCP 命令存在未闭合引号或转义".into());
    }
    if started {
        result.push(word);
    }
    if result.first().is_none_or(|value| value.is_empty()) || result.len() > 128 {
        return Err("MCP stdio 需要有效的启动命令".into());
    }
    Ok(result)
}

pub(super) fn parse_env(text: &str) -> Result<BTreeMap<String, String>, String> {
    parse_lines(
        text,
        '=',
        |key| {
            let mut chars = key.bytes();
            chars
                .next()
                .is_some_and(|ch| ch.is_ascii_alphabetic() || ch == b'_')
                && chars.all(|ch| ch.is_ascii_alphanumeric() || ch == b'_')
        },
        "环境变量",
    )
}

pub(super) fn parse_headers(text: &str) -> Result<BTreeMap<String, String>, String> {
    parse_lines(
        text,
        ':',
        |key| reqwest::header::HeaderName::from_bytes(key.as_bytes()).is_ok(),
        "请求头",
    )
}

fn parse_lines(
    text: &str,
    delimiter: char,
    valid: impl Fn(&str) -> bool,
    label: &str,
) -> Result<BTreeMap<String, String>, String> {
    if text.len() > 64 * 1024 || text.contains('\0') {
        return Err(format!("MCP {label}超过预算或包含 NUL"));
    }
    let mut result = BTreeMap::new();
    for line in text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        let (key, value) = line
            .split_once(delimiter)
            .ok_or_else(|| format!("MCP {label}格式无效"))?;
        let key = key.trim();
        let value = value.trim();
        if key.len() > 128 || value.len() > 8192 || !valid(key) || result.len() == 64 {
            return Err(format!("MCP {label}名称、单值或数量超过预算"));
        }
        result.insert(key.to_owned(), value.to_owned());
    }
    Ok(result)
}

pub(super) fn initialize(id: u64, version: &str) -> Value {
    json!({"jsonrpc":"2.0","id":id,"method":"initialize","params":{
        "protocolVersion":PROTOCOL_VERSION,"capabilities":{},"clientInfo":{"name":"JAgent","version":version}}})
}
pub(super) fn initialized() -> Value {
    json!({"jsonrpc":"2.0","method":"notifications/initialized"})
}
pub(super) fn list_tools(id: u64, cursor: Option<&str>) -> Value {
    let mut params = Map::new();
    if let Some(cursor) = cursor {
        params.insert("cursor".into(), json!(cursor));
    }
    json!({"jsonrpc":"2.0","id":id,"method":"tools/list","params":params})
}
pub(super) fn call_tool(id: u64, name: &str, arguments: Value) -> Value {
    json!({"jsonrpc":"2.0","id":id,"method":"tools/call","params":{"name":name,"arguments":arguments}})
}
pub(super) fn response_for(message: &Value, id: &Value) -> bool {
    message.is_object()
        && message.get("jsonrpc").and_then(Value::as_str) == Some("2.0")
        && message.get("id") == Some(id)
        && message.get("method").is_none()
        && (message.get("result").is_some() ^ message.get("error").is_some())
}
pub(super) fn result_of(response: Value, filter: &SecretFilter) -> Result<Value, String> {
    if let Some(error) = response.get("error") {
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("MCP 服务器返回错误");
        let clipped = clip(&filter.text(message.to_owned()), 4096);
        return Err(clipped);
    }
    response
        .get("result")
        .cloned()
        .ok_or_else(|| "MCP 响应缺少 result".into())
}

#[derive(Clone, Debug)]
pub(super) struct RemoteTool {
    pub name: String,
    pub description: String,
    pub schema: Value,
}
pub(super) fn parse_tools(result: &Value) -> Result<(Vec<RemoteTool>, Option<String>), String> {
    let tools = result
        .get("tools")
        .and_then(Value::as_array)
        .ok_or("MCP tools/list 缺少工具数组")?;
    if tools.len() > MAX_TOOLS {
        return Err("MCP 单页工具超过 256 个".into());
    }
    let mut names = HashSet::new();
    let mut parsed = Vec::new();
    let mut bytes = 0usize;
    for tool in tools {
        let name = tool
            .get("name")
            .and_then(Value::as_str)
            .filter(|name| {
                !name.is_empty() && name.len() <= 256 && !name.chars().any(char::is_control)
            })
            .ok_or("MCP 工具名无效")?;
        if !names.insert(name) {
            return Err("MCP 服务端返回重复工具名".into());
        }
        let description = tool
            .get("description")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let mut schema = tool
            .get("inputSchema")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        schema.insert("type".into(), json!("object"));
        if !schema.get("properties").is_some_and(Value::is_object) {
            schema.insert("properties".into(), json!({}));
        }
        let schema = Value::Object(schema);
        bytes = bytes
            .saturating_add(schema.to_string().len())
            .saturating_add(name.len())
            .saturating_add(description.len());
        if bytes > MAX_CATALOG || description.len() > 64 * 1024 {
            return Err("MCP 工具目录超过 1 MiB".into());
        }
        parsed.push(RemoteTool {
            name: name.into(),
            description: description.into(),
            schema,
        });
    }
    let cursor = match result.get("nextCursor") {
        None | Some(Value::Null) => None,
        Some(Value::String(cursor)) if cursor.len() <= 8192 && !cursor.is_empty() => {
            Some(cursor.clone())
        }
        _ => return Err("MCP 分页游标无效".into()),
    };
    Ok((parsed, cursor))
}

pub(super) fn parse_call_result(result: &Value, filter: &SecretFilter) -> Value {
    let Some(root) = result.as_object() else {
        return json!({"ok":false,"error":"MCP 工具返回了非对象结果","effects_may_have_occurred":true});
    };
    let ok = root.get("isError").and_then(Value::as_bool) != Some(true);
    let mut text = String::new();
    let mut truncated = false;
    if let Some(content) = root.get("content").and_then(Value::as_array) {
        for block in content {
            let rendered = match block.get("type").and_then(Value::as_str) {
                Some("text") => block
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
                Some("image") => "（MCP 服务器返回图片，当前前端尚未接图片附件显示）".into(),
                Some("resource") => block
                    .get("resource")
                    .map(Value::to_string)
                    .unwrap_or_default(),
                _ => block.to_string(),
            };
            let rendered = filter.text(rendered);
            let room = MAX_RESULT.saturating_sub(text.len());
            if room == 0 {
                truncated = true;
                break;
            }
            let mut end = room.min(rendered.len());
            while !rendered.is_char_boundary(end) {
                end -= 1;
            }
            text.push_str(&rendered[..end]);
            truncated |= end < rendered.len();
            if text.len() < MAX_RESULT {
                text.push('\n');
            } else {
                truncated = true;
            }
            if truncated {
                break;
            }
        }
    } else {
        let rendered = filter.text(result.to_string());
        truncated = rendered.len() > MAX_RESULT;
        text = clip(&rendered, MAX_RESULT);
    }
    text = text.trim().to_owned();
    if text.is_empty() {
        text = if ok {
            "（无输出）"
        } else {
            "工具执行失败（服务器未给出详情）"
        }
        .into();
    }
    // JSON escaping can enlarge the text. Bound the final stored tool result too.
    if text.len() > 32 * 1024 {
        text = clip(&text, 32 * 1024);
        truncated = true;
    }
    json!({"ok":ok,"text":text,"output_truncated":truncated,"effects_may_have_occurred":true})
}

pub(super) struct SecretFilter {
    regex: Option<regex::Regex>,
    required: bool,
}
impl SecretFilter {
    pub fn new(secrets: &[String]) -> Self {
        let mut patterns = Vec::new();
        let mut bytes = 0usize;
        for secret in secrets.iter().filter(|secret| !secret.is_empty()) {
            let pattern = regex::escape(secret);
            bytes = bytes.saturating_add(pattern.len());
            if bytes > 1024 * 1024 {
                return Self {
                    regex: None,
                    required: true,
                };
            }
            patterns.push(pattern);
        }
        if patterns.is_empty() {
            return Self {
                regex: None,
                required: false,
            };
        }
        let regex = regex::RegexBuilder::new(&patterns.join("|"))
            .size_limit(8 * 1024 * 1024)
            .dfa_size_limit(2 * 1024 * 1024)
            .build()
            .ok();
        Self {
            regex,
            required: true,
        }
    }
    pub fn text(&self, text: String) -> String {
        if text.is_empty() || !self.required {
            return text;
        }
        // One pass only; compile once per connection and never rescan replacement markers.
        self.regex
            .as_ref()
            .map(|regex| regex.replace_all(&text, "[redacted]").into_owned())
            .unwrap_or_else(|| "[redacted]".into())
    }
}
pub(super) fn clip(text: &str, limit: usize) -> String {
    let mut end = limit.min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text[..end].to_owned()
}

/// Preserve legacy names when they fit. Hash the original remote tool name when truncated.
pub(super) fn registered_name(server: &str, tool: &str) -> String {
    fn clean(value: &str) -> String {
        let text: String = value
            .chars()
            .map(|ch| {
                if ch.is_ascii_alphanumeric() || matches!(ch, '_' | '-') {
                    ch
                } else {
                    '_'
                }
            })
            .collect();
        if text.is_empty() { "_".into() } else { text }
    }
    let mut server = clean(server);
    if server.len() > 24 {
        let hash = format!("{:x}", Sha256::digest(server.as_bytes()));
        server = format!("{}_{}", &server[..15], &hash[..8]);
    }
    let prefix = format!("mcp__{server}__");
    let room = 64 - prefix.len();
    let cleaned = clean(tool);
    if cleaned.len() <= room {
        format!("{prefix}{cleaned}")
    } else {
        let hash = format!("{:x}", Sha256::digest(tool.as_bytes()));
        format!("{}{}_{}", prefix, &cleaned[..room - 9], &hash[..8])
    }
}

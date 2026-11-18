//! Original WebTools parameters and actual text/search/download implementations.
//! Each URL is approved separately; redirects never replay a request or a key.
mod body;
pub(crate) mod download;
mod text;
use crate::{
    network::{self, CancelToken},
    state::ToolCall,
};
use reqwest::{Response, Url, header};
use serde_json::{Map, Value, json};
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use suixing_search::rust_protocol::ToolDefinition;
const MAX_FETCH_BYTES: usize = 1024 * 1024;
const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

pub struct Secret(Vec<u8>);
impl Drop for Secret {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}
#[derive(Clone)]
pub enum SearchCredential {
    Absent,
    Available(Arc<Secret>),
    Unavailable,
}
impl std::fmt::Debug for SearchCredential {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::Absent => "Absent",
            Self::Available(_) => "Available(<redacted>)",
            Self::Unavailable => "Unavailable",
        })
    }
}
impl SearchCredential {
    /// Called once by the actor with Store.search_api_key(), never a provider key.
    /// An unreadable stored secret remains unavailable and cannot become anonymous.
    pub fn freeze(decrypted: Result<String, String>) -> Self {
        match decrypted {
            Err(_) => Self::Unavailable,
            Ok(key) => {
                let key = key.trim();
                if key.is_empty() {
                    Self::Absent
                } else if key.len() > 64 * 1024 || key.contains(['\0', '\r', '\n']) {
                    Self::Unavailable
                } else {
                    Self::Available(Arc::new(Secret(key.as_bytes().to_vec())))
                }
            }
        }
    }
    fn key(&self) -> Result<Option<&str>, String> {
        match self {
            Self::Absent => Ok(None),
            Self::Unavailable => {
                Err("搜索凭据无法解密或无效，请在搜索设置中重新填写；未发送匿名请求".into())
            }
            Self::Available(secret) => std::str::from_utf8(&secret.0)
                .map(Some)
                .map_err(|_| "搜索凭据编码无效".into()),
        }
    }
    fn redact(&self, value: &str) -> String {
        match self.key() {
            Ok(Some(key)) => {
                let mut escaped =
                    Url::parse("https://redaction.invalid/").expect("constant redaction URL");
                escaped.query_pairs_mut().append_pair("key", key);
                let encoded = escaped
                    .query()
                    .unwrap_or_default()
                    .strip_prefix("key=")
                    .unwrap_or_default();
                let redacted = value.replace(key, "<redacted-search-key>");
                if encoded.is_empty() || encoded == key {
                    redacted
                } else {
                    redacted.replace(encoded, "<redacted-search-key>")
                }
            }
            _ => value.to_owned(),
        }
    }
}
#[derive(Clone)]
pub struct Context {
    pub workspace: PathBuf,
    /// Parent includes its stable Rust state namespace (all generations). If it
    /// contains workspace, only that workspace subtree is the writable exception.
    pub protected_paths: Vec<PathBuf>,
    pub search_endpoint: String,
    pub search_credential: SearchCredential,
}
#[derive(Clone)]
enum Operation {
    Fetch {
        url: Url,
        max_chars: usize,
        raw: bool,
        max_bytes: Option<usize>,
    },
    Search {
        url: Url,
        query: String,
        count: usize,
        credential: SearchCredential,
        tavily: bool,
    },
    Download {
        url: Url,
        target: download::Target,
    },
}
#[derive(Clone)]
pub struct Prepared {
    pub approval_summary: String,
    operation: Operation,
    operation_id: String,
    started: Arc<AtomicBool>,
}
impl Prepared {
    pub fn is_download(&self) -> bool {
        matches!(self.operation, Operation::Download { .. })
    }
}
pub fn definitions() -> Vec<ToolDefinition> {
    let string = || json!({"type":"string"});
    let def = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        def(
            "web_fetch",
            "Fetch an HTTP/HTTPS URL. max_chars defaults to 12000 (500–60000); raw=true returns source, otherwise HTML is converted to plain text. Reads at most 1 MiB and detects declared/header or meta charset. Each redirect destination needs a separately prepared and approved call.",
            json!({"url":string(),"max_chars":{"type":"integer","default":12000,"minimum":500,"maximum":60000},"raw":{"type":"boolean","default":false},"max_bytes":{"type":"integer","minimum":1024,"maximum":32768}}),
            &["url"],
        ),
        def(
            "web_search",
            "Search for titles, links and snippets. count defaults to 5 (1–10). Uses the user's frozen Tavily-compatible search endpoint and search credential; when no endpoint is configured, uses DuckDuckGo's HTML endpoint. A credential decryption failure refuses the custom request.",
            json!({"query":string(),"count":{"type":"integer","default":5,"minimum":1,"maximum":10}}),
            &["query"],
        ),
        def(
            "web_download",
            "Stream an HTTP/HTTPS file into a local path, maximum 256 MiB. Relative paths and ~/ use the working directory; absolute paths use Android App UID access. Freezes the actual destination and existing file fingerprint, writes a unique adjacent part file, and commits by rename only after a complete checked download. Returns the actual file change activity. Each redirect destination needs a new approval.",
            json!({"url":string(),"path":string()}),
            &["url", "path"],
        ),
    ]
}
/// Compatibility entry for the earlier Rust registry; parent registers definitions().
pub fn definition() -> ToolDefinition {
    definitions().remove(0)
}
fn primitive(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        Value::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}
fn argument(args: &Map<String, Value>, key: &str) -> Option<String> {
    args.get(key).and_then(primitive)
}
fn legacy_integer(args: &Map<String, Value>, key: &str, default: i32) -> i32 {
    argument(args, key)
        .and_then(|value| value.parse::<i32>().ok())
        .unwrap_or(default)
}
fn legacy_boolean(args: &Map<String, Value>, key: &str, default: bool) -> bool {
    match argument(args, key).as_deref() {
        Some("true") => true,
        Some("false") => false,
        _ => default,
    }
}
fn url(raw: &str) -> Result<Url, String> {
    parse_url(raw, true)
}
fn parse_url(raw: &str, literal_prefix: bool) -> Result<Url, String> {
    let raw = raw.trim();
    if raw.len() > 8192
        || literal_prefix && !raw.starts_with("http://") && !raw.starts_with("https://")
    {
        return Err("url必须以http://或https://开头且不超过8192字节".into());
    }
    let mut url = Url::parse(raw).map_err(|_| "网页URL格式无效")?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("网页URL须为不含嵌入凭据的HTTP/HTTPS地址".into());
    }
    url.set_fragment(None);
    Ok(url)
}
pub fn prepare(
    call: &ToolCall,
    context: &Context,
    operation_id: String,
    cancel: &CancelToken,
) -> Result<Prepared, String> {
    if call.arguments.len() > 32 * 1024
        || operation_id.is_empty()
        || operation_id.len() > 128
        || operation_id.contains('\0')
    {
        return Err("网页参数或操作身份超限".into());
    }
    if cancel.is_cancelled() {
        return Err("网页工具准备已取消".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "网页工具参数不是JSON")?;
    let args = value.as_object().ok_or("网页参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "web_fetch" => &["url", "max_chars", "raw", "max_bytes"],
        "web_search" => &["query", "count"],
        "web_download" => &["url", "path"],
        _ => return Err("未知网络工具".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("网页工具含未支持的参数".into());
    }
    let operation = match call.name.as_str() {
        "web_fetch" => {
            let url = url(&argument(args, "url").ok_or("缺少url")?)?;
            let max_chars = legacy_integer(args, "max_chars", 12000).clamp(500, 60000) as usize;
            let raw = legacy_boolean(args, "raw", false);
            let max_bytes = args
                .get("max_bytes")
                .map(|value| {
                    value
                        .as_u64()
                        .filter(|value| (1024..=32768).contains(value))
                        .map(|value| value as usize)
                        .ok_or("max_bytes必须在1024–32768之间")
                })
                .transpose()?;
            Operation::Fetch {
                url,
                max_chars,
                raw,
                max_bytes,
            }
        }
        "web_search" => {
            let query = argument(args, "query")
                .unwrap_or_default()
                .trim()
                .to_owned();
            if query.is_empty() || query.len() > 16384 || query.contains('\0') {
                return Err("搜索query为空或超限".into());
            }
            let count = legacy_integer(args, "count", 5).clamp(1, 10) as usize;
            if context.search_endpoint.trim().is_empty() {
                let mut url = Url::parse("https://html.duckduckgo.com/html/")
                    .map_err(|_| "内置搜索地址无效")?;
                url.query_pairs_mut().append_pair("q", &query);
                Operation::Search {
                    url,
                    query,
                    count,
                    credential: SearchCredential::Absent,
                    tavily: false,
                }
            } else {
                let url = parse_url(&context.search_endpoint, false)?;
                let credential = context.search_credential.clone();
                if let Some(key) = credential.key()? {
                    header::HeaderValue::from_str(&format!("Bearer {key}"))
                        .map_err(|_| "搜索凭据不能用于HTTP授权头")?;
                }
                Operation::Search {
                    url,
                    query,
                    count,
                    credential,
                    tavily: true,
                }
            }
        }
        "web_download" => {
            let url = url(&argument(args, "url").ok_or("缺少url")?)?;
            let raw = argument(args, "path").ok_or("缺少path")?;
            let target = download::Target::prepare(
                &context.workspace,
                &raw,
                &context.protected_paths,
                cancel,
            )?;
            Operation::Download { url, target }
        }
        _ => unreachable!(),
    };
    let approval_summary = match &operation {
        Operation::Fetch {
            url,
            max_chars,
            raw,
            ..
        } => format!(
            "App UID网页请求：{url}；输出最多{max_chars}字符；{}",
            if *raw {
                "原始源码"
            } else {
                "HTML转文字"
            }
        ),
        Operation::Search {
            url, count, tavily, ..
        } => format!(
            "App UID搜索：{}；目标{}；最多{count}条结果；仅使用搜索专用凭据",
            if *tavily {
                "自定义搜索接口"
            } else {
                "DuckDuckGo"
            },
            url.origin().ascii_serialization()
        ),
        Operation::Download { url, target } => format!(
            "App UID下载：{url}；最终实际路径{}；上限256MiB，完整下载后替换",
            target.path.display()
        ),
    };
    Ok(Prepared {
        approval_summary,
        operation,
        operation_id,
        started: Arc::new(AtomicBool::new(false)),
    })
}

fn local_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(ip) => {
            ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || ip.is_unspecified()
                || ip.is_multicast()
        }
        std::net::IpAddr::V6(ip) => {
            ip.is_loopback()
                || ip.is_unique_local()
                || ip.is_unicast_link_local()
                || ip.is_unspecified()
                || ip.is_multicast()
                || ip.to_ipv4_mapped().is_some_and(|ip| local_ip(ip.into()))
        }
    }
}
pub(crate) async fn client_for(url: &Url, cancel: &CancelToken) -> Result<reqwest::Client, String> {
    network::ensure_network_permission(url.as_str())?;
    let host = url
        .host_str()
        .ok_or("网页主机缺失")?
        .trim_start_matches('[')
        .trim_end_matches(']');
    let port = url.port_or_known_default().ok_or("网页端口无效")?;
    let addresses = if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        vec![std::net::SocketAddr::new(ip, port)]
    } else {
        let mut addresses = Vec::new();
        let resolved = tokio::select! {biased;_=cancel.cancelled()=>return Err("网页DNS查询已取消".into()),result=tokio::net::lookup_host((host,port))=>result.map_err(|_|"网页DNS查询失败")?};
        for address in resolved {
            if !addresses.contains(&address) {
                addresses.push(address);
                if addresses.len() > 64 {
                    return Err("网页DNS地址集合超过64项预算".into());
                }
            }
        }
        if addresses.is_empty() {
            return Err("网页DNS没有返回可用地址".into());
        }
        addresses
    };
    if addresses.iter().any(|address| local_ip(address.ip()))
        && crate::platform::permissions::check(
            crate::platform::permissions::PermissionKind::LocalNetwork,
        ) != crate::platform::permissions::PermissionOutcome::Granted
    {
        return Err("实际目标地址属于局域网，需要系统本地网络权限；未连接目标".into());
    }
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|_| "网页TLS初始化失败")?
    .with_root_certificates(roots)
    .with_no_client_auth();
    let mut builder = reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .no_proxy()
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(45))
        .timeout(Duration::from_secs(90))
        .redirect(reqwest::redirect::Policy::none());
    if host.parse::<std::net::IpAddr>().is_err() {
        builder = builder.resolve_to_addrs(host, &addresses);
    }
    // DNS is resolved once, checked, and pinned into this one direct request.
    // Neither DNS rebinding nor environment proxies may replace the checked route.
    builder
        .build()
        .map_err(|_| "网页HTTPS客户端初始化失败".into())
}
fn basic_request(client: &reqwest::Client, url: Url) -> reqwest::RequestBuilder {
    client
        .get(url)
        .header(header::USER_AGENT, USER_AGENT)
        .header(
            header::ACCEPT,
            "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        )
        .header(header::ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
        .header(header::ACCEPT_ENCODING, "gzip")
}
fn redirect(response: &Response, credential: &SearchCredential) -> Option<Value> {
    if !response.status().is_redirection() {
        return None;
    }
    let raw_location = response
        .headers()
        .get(header::LOCATION)
        .and_then(|value| value.to_str().ok());
    let too_long = raw_location.is_some_and(|location| location.len() > 8192);
    let location = raw_location
        .filter(|location| location.len() <= 8192)
        .map(|location| credential.redact(location));
    let destination = location.as_ref().and_then(|location| {
        response
            .url()
            .join(location)
            .ok()
            .filter(|target| matches!(target.scheme(), "http" | "https"))
            .map(|target| credential.redact(target.as_str()))
    });
    Some(
        json!({"ok":false,"status":response.status().as_u16(),"redirect":location,"redirect_target":destination,"redirect_too_long":too_long,"not_replayed":true,"error":"未自动跟随重定向；请对真实目标地址另行准备并审批；搜索POST需更新搜索接口后重新审批，凭据不会自动搬运"}),
    )
}
struct Body {
    bytes: Vec<u8>,
    truncated: bool,
    content_type: String,
}
async fn body(response: &mut Response) -> Result<Body, String> {
    let content_type = response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_owned();
    let initial = response
        .content_length()
        .filter(|length| *length > 0 && *length <= MAX_FETCH_BYTES as u64)
        .map(|length| length as usize)
        .unwrap_or(16 * 1024);
    let mut bytes = Vec::new();
    let encoding = response
        .headers()
        .get(header::CONTENT_ENCODING)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    let mut stream = body::Stream::new(
        encoding,
        body::FetchSink::new(initial, MAX_FETCH_BYTES),
        8 * 1024 * 1024,
    )?;
    let mut truncated = false;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "网页正文连接中断，未返回不完整成功")?
    {
        if stream.push(&chunk).is_err() {
            if stream.sink().capped {
                bytes = std::mem::take(&mut stream.sink().bytes);
                truncated = true;
                break;
            }
            return Err("HTTP压缩正文损坏、超限或解码失败".into());
        }
    }
    if !truncated {
        bytes = stream
            .finish()
            .map_err(|_| "HTTP压缩正文未完整结束或校验失败")?
            .0
            .bytes;
    }
    bytes.truncate(MAX_FETCH_BYTES);
    Ok(Body {
        bytes,
        truncated,
        content_type,
    })
}
fn byte_prefix(text: &str, limit: usize) -> &str {
    let mut end = text.len().min(limit);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}
async fn fetch(
    url: &Url,
    max_chars: usize,
    raw: bool,
    max_bytes: Option<usize>,
    cancel: &CancelToken,
) -> Result<Value, String> {
    let client = client_for(url, cancel).await?;
    let mut response = basic_request(&client, url.clone())
        .send()
        .await
        .map_err(|_| "网页请求失败")?;
    if let Some(result) = redirect(&response, &SearchCredential::Absent) {
        return Ok(result);
    }
    let status = response.status();
    if !status.is_success() {
        return Err(format!("网页请求失败：HTTP{}", status.as_u16()));
    }
    let body = body(&mut response).await?;
    let decoded = text::decode(&body.bytes, &body.content_type);
    let mut decoded_text = decoded.text;
    if body.truncated {
        decoded_text.push_str("\n… (response too large; only the first 1 MB were read)");
    }
    let text = if raw {
        decoded_text
    } else {
        text::html_to_text(&decoded_text)?
    };
    let (head, total, char_truncated) = text::utf16_prefix(&text, max_chars);
    let head = max_bytes
        .map(|limit| byte_prefix(head, limit))
        .unwrap_or(head);
    let output_truncated = char_truncated || head.len() < text.len();
    let suffix = if output_truncated {
        format!("\n… ({total} characters in total, truncated)")
    } else {
        String::new()
    };
    Ok(
        json!({"ok":true,"status":status.as_u16(),"url":url.as_str(),"content_type":body.content_type,"charset":decoded.charset,"decoding_replacements":decoded.replacement,"text":format!("{head}{suffix}"),"output":format!("{url}\n\n{head}{suffix}"),"truncated":body.truncated||output_truncated,"response_truncated":body.truncated,"characters":total,"format":if raw{"source_text"}else{"plain_text"}}),
    )
}
static DDG_LINK: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
    regex::Regex::new(
        r#"(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>"#,
    )
    .expect("constant DDG link regex")
});
static DDG_SNIPPET: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
    regex::Regex::new(r#"(?is)<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>"#)
        .expect("constant DDG snippet regex")
});
fn clean_ddg(href: &str) -> String {
    let Some(index) = href.find("uddg=") else {
        return href.into();
    };
    let encoded = href[index + 5..].split('&').next().unwrap_or_default();
    text::decode_form(encoded).unwrap_or_else(|_| href.into())
}
fn search_hit(title: &str, url: &str, snippet: &str, snippet_limit: usize) -> Value {
    let (title, _, title_truncated) = text::utf16_prefix(title, 8192);
    let (snippet, _, _) = text::utf16_prefix(snippet, snippet_limit);
    let url_too_long = url.len() > 8192;
    let link = if url_too_long { None } else { Some(url) };
    let displayed = link.unwrap_or("(URL超过8192字节，未返回截断地址)");
    json!({"title":title,"url":link,"snippet":snippet,"title_truncated":title_truncated,"url_too_long":url_too_long,"text":format!("[{title}]\n{displayed}\n{snippet}")})
}
async fn search(
    url: &Url,
    query: &str,
    count: usize,
    credential: &SearchCredential,
    tavily: bool,
    cancel: &CancelToken,
) -> Result<Value, String> {
    let client = client_for(url, cancel).await?;
    let request = if tavily {
        let mut payload = json!({"query":query,"max_results":count,"search_depth":"basic"});
        let mut builder = client.post(url.clone());
        if let Some(key) = credential.key()? {
            payload["api_key"] = json!(key);
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {key}"));
        }
        builder
            .header(header::ACCEPT_ENCODING, "gzip")
            .json(&payload)
    } else {
        basic_request(&client, url.clone())
    };
    let mut response = request.send().await.map_err(|_| {
        if tavily {
            "搜索请求失败"
        } else {
            "内置搜索请求失败，可在设置中配置Tavily兼容接口"
        }
    })?;
    if let Some(result) = redirect(&response, credential) {
        return Ok(result);
    }
    let status = response.status();
    if !status.is_success() {
        return Err(format!(
            "{}：HTTP{}",
            if tavily {
                "搜索失败"
            } else {
                "内置搜索不可用，可在设置中配置Tavily兼容接口"
            },
            status.as_u16()
        ));
    }
    let body = body(&mut response).await?;
    let decoded = text::decode(&body.bytes, &body.content_type);
    let mut results = Vec::new();
    if tavily {
        if body.truncated {
            return Err("搜索JSON正文超过1MiB，未解析截断结果".into());
        }
        let document: Value =
            serde_json::from_str(&decoded.text).map_err(|_| "搜索接口未返回有效JSON")?;
        let object = document.as_object().ok_or("搜索接口JSON不是对象")?;
        if let Some(values) = object.get("results") {
            for value in values
                .as_array()
                .ok_or("搜索results不是数组")?
                .iter()
                .take(count)
            {
                let value = value.as_object().ok_or("搜索结果条目不是对象")?;
                let field = |key: &str| value.get(key).and_then(primitive).unwrap_or_default();
                results.push(search_hit(
                    &credential.redact(&field("title")),
                    &credential.redact(&field("url")),
                    &credential.redact(&field("content")),
                    400,
                ));
            }
        }
    } else {
        let snippets: Vec<_> = DDG_SNIPPET
            .captures_iter(&decoded.text)
            .take(count)
            .map(|capture| text::html_to_text(&capture[1]))
            .collect::<Result<_, _>>()?;
        for (index, capture) in DDG_LINK
            .captures_iter(&decoded.text)
            .take(count)
            .enumerate()
        {
            let href = text::entities(&capture[1])?;
            let title = text::html_to_text(&capture[2])?;
            let snippet = snippets.get(index).map(String::as_str).unwrap_or("");
            results.push(search_hit(&title, &clean_ddg(&href), snippet, 300));
        }
        if results.is_empty() {
            return Err("内置搜索没有解析到结果，可能被限流；可在设置中配置Tavily兼容接口".into());
        }
    }
    let output = if results.is_empty() {
        "No results found.".into()
    } else {
        results
            .iter()
            .filter_map(|result| result["text"].as_str())
            .collect::<Vec<_>>()
            .join("\n\n")
    };
    Ok(
        json!({"ok":true,"status":status.as_u16(),"results":results,"text":output,"truncated":body.truncated,"backend":if tavily{"tavily_compatible"}else{"duckduckgo_html"}}),
    )
}
async fn download(
    url: &Url,
    target: &download::Target,
    operation_id: &str,
    cancel: &CancelToken,
) -> Result<Value, String> {
    #[cfg(not(unix))]
    {
        let _ = (url, target, operation_id, cancel);
        Err("当前平台没有Unix原子下载提交".into())
    }
    #[cfg(unix)]
    {
        let client = client_for(url, cancel).await?;
        let mut response = basic_request(&client, url.clone())
            .send()
            .await
            .map_err(|_| "下载请求失败")?;
        if let Some(result) = redirect(&response, &SearchCredential::Absent) {
            return Ok(result);
        }
        if !response.status().is_success() {
            return Err(format!("下载失败：HTTP{}", response.status().as_u16()));
        }
        let declared = response.content_length();
        if declared.is_some_and(|length| length > download::MAX_BYTES) {
            return Err("下载声明长度超过256MiB，未写入目标".into());
        }
        let mut active = target.begin(cancel)?;
        let writer = active.writer()?;
        // Arc is a separate local, so the guard never borrows Active itself.
        let _guard = loop {
            if cancel.is_cancelled() {
                return Err("下载等待目标写入锁时已取消".into());
            }
            match writer.try_lock() {
                Ok(guard) => break guard,
                Err(std::sync::TryLockError::Poisoned(error)) => break error.into_inner(),
                Err(std::sync::TryLockError::WouldBlock) => (),
            }
            tokio::time::sleep(Duration::from_millis(16)).await;
        };
        active.start(operation_id, cancel)?;
        let encoding = response
            .headers()
            .get(header::CONTENT_ENCODING)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("");
        let mut stream = body::Stream::new(
            encoding,
            body::DownloadSink {
                active,
                cancel,
                written: 0,
            },
            download::MAX_BYTES,
        )?;
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| "下载正文连接中断；未提交不完整目标")?
        {
            stream
                .push(&chunk)
                .map_err(|_| "下载压缩正文损坏、超限或文件写入失败；未提交目标")?;
        }
        let (mut sink, wire_bytes) = stream
            .finish()
            .map_err(|_| "下载压缩正文未完整结束或校验失败；未提交目标")?;
        if declared.is_some_and(|length| length != wire_bytes) {
            return Err("下载实收长度与声明不一致，未提交目标".into());
        }
        sink.active.commit(cancel)
    }
}
pub async fn execute(prepared: &Prepared, cancel: Arc<CancelToken>) -> Value {
    if cancel.is_cancelled() {
        return json!({"ok":false,"not_started":true,"error":"网页操作在启动前已取消"});
    }
    if prepared
        .started
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return json!({"ok":false,"not_started":true,"error":"网页操作已派发过，未重放请求或下载"});
    }
    let operation = async {
        match &prepared.operation {
            Operation::Fetch {
                url,
                max_chars,
                raw,
                max_bytes,
            } => fetch(url, *max_chars, *raw, *max_bytes, &cancel).await,
            Operation::Search {
                url,
                query,
                count,
                credential,
                tavily,
            } => search(url, query, *count, credential, *tavily, &cancel).await,
            Operation::Download { url, target } => {
                download(url, target, &prepared.operation_id, &cancel).await
            }
        }
    };
    let result = tokio::select! {biased;_=cancel.cancelled()=>Err("网络操作已停止；未提交的自有下载part已回收".into()),result=tokio::time::timeout(Duration::from_secs(90),operation)=>result.unwrap_or_else(|_|Err("网络操作达到90秒上限；未自动重试".into()))};
    result.unwrap_or_else(|error: String| json!({"ok":false,"error":error}))
}

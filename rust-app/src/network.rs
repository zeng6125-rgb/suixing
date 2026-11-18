//! Real asynchronous HTTP for the Rust runtime. The UI receives only actual response deltas.
//! Errors never include request URLs, keys, or request/response dumps.

pub(crate) mod retry;
pub(crate) use retry::RetryAdvice;
mod endpoint;
use std::sync::{
    Arc, OnceLock,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

use reqwest::{Client, Response, Url, header};
use serde_json::{Value, json};
use suixing_search::rust_protocol::{
    self, DeltaKind, EventBatch, EventDecoder, Protocol, ProtocolError, RequestSettings, Turn,
    TurnAccumulator,
};
use tokio::sync::Notify;

use crate::state::{self, Settings};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const TURN_TIMEOUT: Duration = Duration::from_secs(600);
const MAX_RESPONSE_BYTES: usize = 64 * 1024 * 1024;
const MAX_JSON_BYTES: usize = 8 * 1024 * 1024;
const MAX_ERROR_BYTES: usize = 64 * 1024;
const DECODER_CHUNK_BYTES: usize = 8192;

/// Cancellation is shared by the UI/runtime and every HTTP await. Cancelling drops the
/// pending request or response instead of leaving a blocking socket reader behind.
#[derive(Default)]
pub struct CancelToken {
    cancelled: Arc<AtomicBool>,
    notify: Notify,
    deadline: OnceLock<Instant>,
}

impl CancelToken {
    pub(crate) fn cancellation_flag(&self) -> Arc<AtomicBool> {
        self.cancelled.clone()
    }
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        self.notify.notify_waiters();
    }

    pub fn is_cancelled(&self) -> bool {
        if self
            .deadline
            .get()
            .is_some_and(|deadline| Instant::now() >= *deadline)
        {
            self.cancel();
        }
        self.cancelled.load(Ordering::Acquire)
    }

    pub fn set_deadline(&self, duration: Duration) {
        let _ = self.deadline.set(Instant::now() + duration);
        self.notify.notify_waiters();
    }

    pub async fn cancelled(&self) {
        loop {
            let notification = self.notify.notified();
            tokio::pin!(notification);
            // Register before checking the flag so a concurrent notify_waiters cannot
            // fall between the check and registration and leave the task asleep.
            notification.as_mut().enable();
            if self.is_cancelled() {
                return;
            }
            if let Some(deadline) = self.deadline.get() {
                tokio::select! { _=notification=>(), _=tokio::time::sleep_until((*deadline).into())=>{self.cancel();return;} }
            } else {
                notification.await;
            }
        }
    }

    fn check(&self) -> Result<(), String> {
        if self.is_cancelled() {
            Err("已停止".into())
        } else {
            Ok(())
        }
    }
}

/// Counts admissions at the actual HTTP send branch. Cloned views share one
/// real counter; compression can use a lower ceiling to reserve the last chat.
#[derive(Clone)]
pub(crate) struct RequestBudget {
    used: Arc<std::sync::atomic::AtomicUsize>,
    limit: usize,
}
impl RequestBudget {
    pub fn new(limit: usize) -> Self {
        Self {
            used: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            limit,
        }
    }
    pub fn can_admit(&self) -> bool {
        self.used() < self.limit
    }
    pub fn used(&self) -> usize {
        self.used.load(Ordering::Acquire)
    }
    pub fn reserve_last(&self) -> Self {
        Self {
            used: self.used.clone(),
            limit: self.limit.saturating_sub(1),
        }
    }
    fn admit(&self) -> Result<(), String> {
        self.used
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |used| {
                (used < self.limit).then(|| used + 1)
            })
            .map(|_| ())
            .map_err(|_| "本次运行的实际模型/压缩请求预算已耗尽，未发送额外请求".into())
    }
}

pub(crate) fn client() -> Result<Client, String> {
    endpoint::strict_client()
}

pub(crate) fn ensure_network_permission(endpoint: &str) -> Result<(), String> {
    crate::platform::permissions::network_permission(endpoint)
        .map_err(|_| "此局域网接口需要系统本地网络权限，请在设置中授权后重试".into())
}
pub async fn models(settings: Settings, cancel: Arc<CancelToken>) -> Result<Vec<String>, String> {
    model_details(settings, cancel)
        .await
        .map(|models| models.into_iter().map(|model| model.id).collect())
}
pub(crate) async fn model_details(
    settings: Settings,
    cancel: Arc<CancelToken>,
) -> Result<Vec<rust_protocol::Model>, String> {
    ensure_network_permission(&settings.endpoint)?;
    let redaction = Redaction::new(&settings);
    let operation = async {
        let protocol = protocol(&settings);
        let mut base = Url::parse(settings.endpoint.trim()).map_err(|_| "模型列表接口URL无效")?;
        if !matches!(base.scheme(), "http" | "https")
            || base.host_str().is_none()
            || !base.username().is_empty()
            || base.password().is_some()
        {
            return Err("模型列表须使用不含嵌入凭据的HTTP/HTTPS地址".into());
        }
        let query = base.query().map(str::to_owned);
        base.set_query(None);
        base.set_fragment(None);
        let endpoint = rust_protocol::models_endpoint(base.as_str(), protocol)
            .map_err(|error| error.to_string())?;
        let mut url = Url::parse(&endpoint).map_err(|_| "模型列表接口地址构造失败")?;
        url.set_query(query.as_deref());
        let headers = configured_headers(&settings, protocol, false)?;
        let client = endpoint::model_client(&settings, &url, &headers)?;
        let mut models = Vec::new();
        let mut seen = std::collections::HashSet::new();
        let mut cursors = std::collections::HashSet::new();
        for _ in 0..10 {
            cancel.check()?;
            let request = client.get(url.clone()).headers(headers.clone());
            let response = request.send().await.map_err(transport_error)?;
            if !response.status().is_success() {
                return Err(http_error(response, &cancel, &redaction).await?);
            }
            let bytes = body(response, &cancel, 2 * 1024 * 1024).await?;
            let document: Value =
                serde_json::from_slice(&bytes).map_err(|_| "模型列表响应不是有效JSON")?;
            for model in
                rust_protocol::parse_models(&document).map_err(|error| error.to_string())?
            {
                if model.id.len() > 1024 || model.id.contains('\0') {
                    return Err("模型列表含过长或无效ID".into());
                }
                if seen.insert(model.id.clone()) {
                    models.push(model);
                }
                if models.len() > 1024 {
                    return Err("模型列表超过1024个上限，请使用手动模型配置".into());
                }
            }
            if document.get("has_more").and_then(Value::as_bool) != Some(true) {
                return Ok(models);
            }
            let cursor = document
                .get("last_id")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty() && value.len() <= 1024)
                .ok_or("分页模型列表缺少有效last_id")?;
            if !cursors.insert(cursor.to_owned()) {
                return Err("模型列表分页游标重复".into());
            }
            let pairs: Vec<_> = url
                .query_pairs()
                .filter(|(key, _)| key != "after_id")
                .map(|(key, value)| (key.into_owned(), value.into_owned()))
                .collect();
            url.set_query(None);
            url.query_pairs_mut()
                .extend_pairs(pairs)
                .append_pair("after_id", cursor);
        }
        Err("模型列表分页超过10页，未把部分结果冒充完整列表".into())
    };
    let result = tokio::select! {
        biased;
        _=cancel.cancelled()=>Err("模型列表请求已取消".into()),
        result=tokio::time::timeout(Duration::from_secs(60),operation)=>result.unwrap_or_else(|_|Err("模型列表请求达到60秒上限".into())),
    };
    result.map_err(|error| redaction.clean(error).chars().take(600).collect())
}

fn protocol(settings: &Settings) -> Protocol {
    match settings.protocol {
        state::Protocol::OpenAi => Protocol::OpenAi,
        state::Protocol::Anthropic => Protocol::Anthropic,
        state::Protocol::Responses => Protocol::Responses,
    }
}

/// Connection identity ignores a trailing path slash, but keeps path case
/// and query values. Credentials must not cross a different tenant path.
pub(crate) fn same_connection_endpoint(a: &str, b: &str) -> bool {
    let (a, b) = (a.trim(), b.trim());
    if a == b {
        return true;
    }
    let (Ok(a), Ok(b)) = (Url::parse(a), Url::parse(b)) else {
        return false;
    };
    a.scheme() == b.scheme()
        && a.host_str() == b.host_str()
        && a.port_or_known_default() == b.port_or_known_default()
        && a.username() == b.username()
        && a.password() == b.password()
        && a.path().trim_end_matches('/') == b.path().trim_end_matches('/')
        && a.query() == b.query()
}
fn request_url(base: &str, protocol: Protocol) -> Result<Url, String> {
    let mut url = Url::parse(base.trim()).map_err(|_| "接口地址不是有效 URL".to_owned())?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err("接口地址必须使用 http 或 https".into());
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("接口地址中的凭据请改填到 API Key".into());
    }
    let query = url.query().map(str::to_owned);
    url.set_query(None);
    url.set_fragment(None);
    // Append to the path first, then restore the query. A query token is not part of
    // the path and must not end up before an appended /chat/completions suffix.
    let endpoint =
        rust_protocol::endpoint(url.as_str(), protocol).map_err(|failure| failure.to_string())?;
    let mut url = Url::parse(&endpoint).map_err(|_| "接口地址无法构造请求路径".to_owned())?;
    url.set_query(query.as_deref());
    Ok(url)
}

fn configured_headers(
    settings: &Settings,
    protocol: Protocol,
    streaming: bool,
) -> Result<header::HeaderMap, String> {
    let mut headers = header::HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("application/json"),
    );
    headers.insert(
        header::ACCEPT,
        header::HeaderValue::from_static(if streaming {
            "text/event-stream, application/json"
        } else {
            "application/json"
        }),
    );
    if protocol == Protocol::Anthropic {
        headers.insert(
            "anthropic-version",
            header::HeaderValue::from_static("2023-06-01"),
        );
        if !settings.api_key.trim().is_empty() {
            headers.insert(
                "x-api-key",
                header::HeaderValue::from_str(&settings.api_key).map_err(|_| "授权请求头无效")?,
            );
        }
    } else if !settings.api_key.trim().is_empty() {
        headers.insert(
            header::AUTHORIZATION,
            header::HeaderValue::from_str(&format!("Bearer {}", settings.api_key))
                .map_err(|_| "授权请求头无效")?,
        );
    }
    if settings.request_headers.len() > 128 {
        return Err("自定义请求头超过预算".into());
    }
    for (name, value) in &settings.request_headers {
        if name.len() > 256 || value.len() > 16 * 1024 {
            return Err("自定义请求头超过预算".into());
        }
        let name =
            header::HeaderName::from_bytes(name.as_bytes()).map_err(|_| "自定义请求头名称无效")?;
        let value = header::HeaderValue::from_str(value).map_err(|_| "自定义请求头值无效")?;
        // The original host applies the user's headers last, replacing defaults by name.
        headers.insert(name, value);
    }
    Ok(headers)
}

struct Redaction {
    values: Vec<String>,
}
impl Redaction {
    fn new(settings: &Settings) -> Self {
        let mut values = vec![
            settings.api_key.clone(),
            settings.asr_key.clone(),
            settings.endpoint.clone(),
        ];
        values.extend(settings.request_headers.values().cloned());
        if let Ok(url) = Url::parse(settings.endpoint.trim()) {
            values.push(url.to_string());
            if let Some(query) = url.query() {
                values.push(query.into());
            }
            for (_, value) in url.query_pairs() {
                values.push(value.into_owned());
            }
        }
        values.retain(|value| !value.is_empty());
        // Replace the whole endpoint/key before any shorter contained query value.
        values.sort_by_key(|value| std::cmp::Reverse(value.len()));
        values.dedup();
        Self { values }
    }
    fn clean(&self, message: String) -> String {
        let mut sanitized = String::new();
        let mut rest = message.as_str();
        while !rest.is_empty() {
            if let Some(value) = self
                .values
                .iter()
                .find(|value| rest.starts_with(value.as_str()))
            {
                sanitized.push_str("[已隐藏]");
                rest = &rest[value.len()..];
            } else if self.values.iter().any(|value| value.starts_with(rest)) {
                // The capped HTTP error body may end in a partial echoed credential.
                sanitized.push_str("[已隐藏]");
                break;
            } else {
                let character = rest.chars().next().expect("nonempty suffix");
                sanitized.push(character);
                rest = &rest[character.len_utf8()..];
            }
        }
        // A server may echo a rewritten endpoint. Hide URL tokens as well, without
        // depending on byte-for-byte equality with the user's original address.
        sanitized
            .split_inclusive(char::is_whitespace)
            .map(|part| {
                if part.contains("http://") || part.contains("https://") {
                    "[接口地址已隐藏] ".into()
                } else {
                    part.to_owned()
                }
            })
            .collect()
    }
}

fn transport_error(error: reqwest::Error) -> String {
    if error.is_timeout() {
        "接口请求超时".into()
    } else if error.is_connect() {
        "无法连接接口，请检查网络、地址和 HTTPS 证书".into()
    } else if error.is_builder() {
        "请求配置无效，请检查接口地址和请求头".into()
    } else {
        format!("接口连接中断：{}", error.without_url())
    }
}

fn short_text(value: Option<&Value>) -> Option<String> {
    let value = value?;
    let text = value.as_str().map(str::to_owned).or_else(|| {
        if value.is_number() || value.is_boolean() {
            Some(value.to_string())
        } else {
            None
        }
    })?;
    if text.trim().is_empty() {
        return None;
    }
    // Truncate only after caller-level credential redaction, so a cut cannot leave
    // a prefix of a key that an exact full-key replacement would fail to recognize.
    Some(text)
}

fn api_error(event: &Value) -> Option<String> {
    let event_type = event.get("type").and_then(Value::as_str).unwrap_or("");
    let response = event.get("response").unwrap_or(event);
    let error = event
        .get("error")
        .filter(|value| !value.is_null())
        .or_else(|| response.get("error").filter(|value| !value.is_null()));
    let failed = matches!(
        event_type,
        "error" | "response.failed" | "response.cancelled"
    ) || matches!(
        response.get("status").and_then(Value::as_str),
        Some("failed" | "cancelled")
    );
    if error.is_none() && !failed {
        return None;
    }
    let detail = error
        .and_then(|value| short_text(value.get("message")))
        .or_else(|| short_text(error))
        .or_else(|| short_text(event.get("message")))
        .or_else(|| short_text(response.get("message")))
        .or_else(|| short_text(error.and_then(|value| value.get("code"))));
    Some(
        detail
            .map(|detail| format!("接口返回错误：{detail}"))
            .unwrap_or_else(|| "接口报告响应失败".into()),
    )
}

fn accept<F>(
    turn: &mut TurnAccumulator,
    protocol: Protocol,
    event: &Value,
    cancel: &CancelToken,
    emit: &mut F,
) -> Result<(), String>
where
    F: FnMut(bool, &str),
{
    cancel.check()?;
    if let Some(failure) = api_error(event) {
        return Err(failure);
    }
    turn.accept(protocol, event, |delta| {
        if cancel.is_cancelled() {
            return Err(ProtocolError::Cancelled);
        }
        emit(delta.kind == DeltaKind::Thinking, delta.text);
        Ok(())
    })
    .map_err(|failure| failure.to_string())
}

fn terminal_event(protocol: Protocol, event: &Value) -> bool {
    match (protocol, event.get("type").and_then(Value::as_str)) {
        (Protocol::Anthropic, Some("message_stop")) => true,
        (Protocol::Responses, Some("response.completed" | "response.incomplete")) => true,
        _ => false,
    }
}

fn accept_batch<F>(
    turn: &mut TurnAccumulator,
    protocol: Protocol,
    batch: EventBatch,
    cancel: &CancelToken,
    emit: &mut F,
) -> Result<bool, String>
where
    F: FnMut(bool, &str),
{
    let mut terminal = batch.done;
    for event in batch.events {
        accept(turn, protocol, &event, cancel, emit)?;
        terminal |= terminal_event(protocol, &event);
    }
    if let Some(failure) = batch.failure {
        return Err(failure.to_string());
    }
    Ok(terminal)
}

async fn chunk(
    response: &mut Response,
    cancel: &CancelToken,
) -> Result<Option<impl AsRef<[u8]>>, String> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err("已停止".into()),
        chunk = response.chunk() => chunk.map_err(transport_error),
    }
}

async fn body(
    mut response: Response,
    cancel: &CancelToken,
    limit: usize,
) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    while let Some(part) = chunk(&mut response, cancel).await? {
        let part = part.as_ref();
        if part.len() > limit.saturating_sub(bytes.len()) {
            return Err("接口响应超过大小上限".into());
        }
        bytes.extend_from_slice(part);
    }
    Ok(bytes)
}

async fn http_error(
    mut response: Response,
    cancel: &CancelToken,
    redaction: &Redaction,
) -> Result<String, String> {
    let status = response.status().as_u16();
    let mut bytes = Vec::new();
    while bytes.len() < MAX_ERROR_BYTES {
        let part = match chunk(&mut response, cancel).await {
            Ok(Some(part)) => part,
            Ok(None) => break,
            Err(_) if cancel.is_cancelled() => return Err("已停止".into()),
            Err(_) => break, // Keep the actual HTTP status even if its error body disconnects.
        };
        let part = part.as_ref();
        let count = part.len().min(MAX_ERROR_BYTES - bytes.len());
        bytes.extend_from_slice(&part[..count]);
        if count < part.len() {
            break;
        }
    }
    let body = redaction.clean(String::from_utf8_lossy(&bytes).into_owned());
    Ok(rust_protocol::friendly_http(status, &body))
}

fn completed(turn: &TurnAccumulator, saw_terminal: bool) -> Result<Turn, String> {
    if !saw_terminal
        && !turn
            .finish_reason()
            .is_some_and(|reason| !reason.trim().is_empty())
    {
        return Err("接口连接提前结束，响应尚未完成；已收到的内容已保留".into());
    }
    Ok(turn.snapshot())
}

async fn streaming<F>(
    mut response: Response,
    protocol: Protocol,
    cancel: &CancelToken,
    emit: &mut F,
    turn: &mut TurnAccumulator,
    retry: &mut Option<RetryAdvice>,
) -> Result<Turn, String>
where
    F: FnMut(bool, &str),
{
    let mut decoder = EventDecoder::new();
    let mut received = 0usize;
    loop {
        let incoming = match chunk(&mut response, cancel).await {
            Ok(part) => part,
            Err(error) => {
                if !cancel.is_cancelled() {
                    *retry = Some(RetryAdvice::Transport);
                }
                return Err(error);
            }
        };
        let Some(part) = incoming else {
            // `finish().done` means decoder EOF, not a server completion marker.
            // Capture only explicit markers from feed() or actual protocol finish fields.
            let batch = decoder.finish();
            for event in batch.events {
                accept(turn, protocol, &event, cancel, emit)?;
            }
            if let Some(failure) = batch.failure {
                return Err(failure.to_string());
            }
            cancel.check()?;
            let completed = completed(turn, decoder.saw_done());
            if completed.is_err() {
                *retry = Some(RetryAdvice::Cut);
            }
            return completed;
        };
        let bytes = part.as_ref();
        let permitted = bytes.len().min(MAX_RESPONSE_BYTES.saturating_sub(received));
        received += permitted;
        for bytes in bytes[..permitted].chunks(DECODER_CHUNK_BYTES) {
            cancel.check()?;
            let batch = decoder.feed(bytes);
            if accept_batch(turn, protocol, batch, cancel, emit)? {
                cancel.check()?;
                return completed(turn, true);
            }
        }
        if permitted < bytes.len() {
            return Err("接口响应超过 64 MiB 上限；已收到的内容已保留".into());
        }
    }
}

fn nonstreaming<F>(
    protocol: Protocol,
    response: &Value,
    cancel: &CancelToken,
    emit: &mut F,
    turn: &mut TurnAccumulator,
) -> Result<Turn, String>
where
    F: FnMut(bool, &str),
{
    cancel.check()?;
    if let Some(failure) = api_error(response) {
        return Err(failure);
    }
    let mut terminal = false;
    match protocol {
        Protocol::OpenAi => accept(turn, protocol, response, cancel, emit)?,
        Protocol::Anthropic => {
            let content = response
                .get("content")
                .and_then(Value::as_array)
                .ok_or_else(|| "Anthropic 返回里没有 content 内容块".to_owned())?;
            if let Some(usage) = response.get("usage") {
                accept(
                    turn,
                    protocol,
                    &json!({"type":"message_start","message":{"usage":usage}}),
                    cancel,
                    emit,
                )?;
            }
            for (index, block) in content.iter().enumerate() {
                if block.get("type").and_then(Value::as_str) == Some("thinking") {
                    accept(
                        turn,
                        protocol,
                        &json!({"type":"content_block_delta","index":index,"delta":{"type":"thinking_delta","thinking":block.get("thinking")}}),
                        cancel,
                        emit,
                    )?;
                } else {
                    accept(
                        turn,
                        protocol,
                        &json!({"type":"content_block_start","index":index,"content_block":block}),
                        cancel,
                        emit,
                    )?;
                }
            }
            if let Some(reason) = response
                .get("stop_reason")
                .filter(|reason| !reason.is_null())
            {
                accept(
                    turn,
                    protocol,
                    &json!({"type":"message_delta","delta":{"stop_reason":reason}}),
                    cancel,
                    emit,
                )?;
            }
        }
        Protocol::Responses => {
            let output = response
                .get("output")
                .and_then(Value::as_array)
                .ok_or_else(|| "Responses 返回里没有 output 内容".to_owned())?;
            for item in output {
                match item.get("type").and_then(Value::as_str) {
                    Some("function_call") => accept(
                        turn,
                        protocol,
                        &json!({"type":"response.output_item.added","item":item}),
                        cancel,
                        emit,
                    )?,
                    Some("reasoning") => {
                        for key in ["summary", "content"] {
                            if let Some(parts) = item.get(key).and_then(Value::as_array) {
                                for part in parts {
                                    if let Some(text) = part.get("text") {
                                        accept(
                                            turn,
                                            protocol,
                                            &json!({"type":"response.reasoning_text.delta","delta":text}),
                                            cancel,
                                            emit,
                                        )?;
                                    }
                                }
                            }
                        }
                    }
                    Some("message") => {
                        if let Some(parts) = item.get("content").and_then(Value::as_array) {
                            for part in parts {
                                if part.get("type").and_then(Value::as_str) == Some("output_text") {
                                    accept(
                                        turn,
                                        protocol,
                                        &json!({"type":"response.output_text.delta","delta":part.get("text")}),
                                        cancel,
                                        emit,
                                    )?;
                                }
                            }
                        }
                    }
                    _ => (),
                }
            }
            if let Some(status @ ("completed" | "incomplete")) =
                response.get("status").and_then(Value::as_str)
            {
                accept(
                    turn,
                    protocol,
                    &json!({"type":format!("response.{status}"),"response":response}),
                    cancel,
                    emit,
                )?;
                terminal = true;
            }
        }
    }
    cancel.check()?;
    completed(turn, terminal)
}

/// One immutable, fully encoded request body is shared by physical retry sends.
/// RequestBuilder::try_clone retains its Bytes body; no transcript is rebuilt or
/// tool checkpoint replayed between attempts.
pub(crate) struct PreparedChat {
    request: reqwest::RequestBuilder,
    address: String,
    protocol: Protocol,
    stream: bool,
    redaction: Redaction,
}
pub(crate) fn prepare_chat(
    settings: &Settings,
    messages: &[rust_protocol::Message],
    tools: &[rust_protocol::ToolDefinition],
) -> Result<PreparedChat, String> {
    if settings.model.trim().is_empty() {
        return Err("请先填写模型名称".into());
    }
    if settings.endpoint.len() > 16 * 1024
        || settings.api_key.len() > 16 * 1024
        || settings.asr_key.len() > 16 * 1024
    {
        return Err("接口地址或API Key超过长度上限".into());
    }
    let protocol = protocol(settings);
    let url = request_url(&settings.endpoint, protocol)?;
    let headers = configured_headers(settings, protocol, settings.stream)?;
    let request_settings = RequestSettings {
        model: settings.model.clone(),
        send_temperature: settings.temperature.is_some(),
        temperature: settings.temperature.unwrap_or(1.0),
        send_top_p: settings.top_p.is_some(),
        top_p: settings.top_p.unwrap_or(1.0),
        max_tokens: settings.max_tokens,
        reasoning_effort: settings.reasoning_effort.clone(),
    };
    let mut payload = rust_protocol::build_request(protocol, &request_settings, messages, tools)
        .map_err(|error| error.to_string())?;
    if !settings.stream {
        let object = payload.as_object_mut().ok_or("请求格式无法构造")?;
        object.insert("stream".into(), json!(false));
        object.remove("stream_options");
    }
    ensure_network_permission(url.as_str())?;
    let address = url.as_str().to_owned();
    let request = endpoint::model_client(settings, &url, &headers)?
        .post(url)
        .headers(headers)
        .json(&payload);
    if request.try_clone().is_none() {
        return Err("模型请求无法准备为可重试的固定请求体".into());
    }
    Ok(PreparedChat {
        request,
        address,
        protocol,
        stream: settings.stream,
        redaction: Redaction::new(settings),
    })
}
async fn chat_inner<F>(
    prepared: &PreparedChat,
    cancel: &CancelToken,
    emit: &mut F,
    turn: &mut TurnAccumulator,
    request_budget: Option<&RequestBudget>,
    retry: &mut Option<RetryAdvice>,
) -> Result<Turn, String>
where
    F: FnMut(bool, &str),
{
    cancel.check()?;
    ensure_network_permission(&prepared.address)?;
    let request = prepared
        .request
        .try_clone()
        .ok_or("固定请求体无法复制，未发送")?;
    let response = tokio::select! {
        biased;
        _=cancel.cancelled()=>return Err("已停止".into()),
        response=async { cancel.check()?; if let Some(budget)=request_budget {budget.admit()?;} request.send().await.map_err(|error| { *retry=Some(RetryAdvice::Transport); transport_error(error) }) } => response?,
    };
    if !response.status().is_success() {
        *retry = Some(RetryAdvice::Http {
            status: response.status().as_u16(),
            retry_after_seconds: retry::retry_after(
                response
                    .headers()
                    .get(header::RETRY_AFTER)
                    .and_then(|value| value.to_str().ok()),
            ),
        });
        return Err(http_error(response, cancel, &prepared.redaction).await?);
    }
    if response
        .content_length()
        .is_some_and(|bytes| bytes > MAX_RESPONSE_BYTES as u64)
    {
        return Err("接口响应超过64 MiB上限".into());
    }
    let content_type = response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    let media = content_type.split(';').next().unwrap_or("").trim();
    let sse = media == "text/event-stream";
    let json_response = media == "application/json" || media.ends_with("+json");
    if sse || prepared.stream && !json_response {
        streaming(response, prepared.protocol, cancel, emit, turn, retry).await
    } else {
        let bytes = body_attempt(response, cancel, MAX_JSON_BYTES, retry).await?;
        cancel.check()?;
        let response: Value =
            serde_json::from_slice(&bytes).map_err(|_| "接口返回的不是有效JSON")?;
        nonstreaming(prepared.protocol, &response, cancel, emit, turn)
    }
}
async fn body_attempt(
    mut response: Response,
    cancel: &CancelToken,
    limit: usize,
    retry: &mut Option<RetryAdvice>,
) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    loop {
        let part = match chunk(&mut response, cancel).await {
            Ok(part) => part,
            Err(error) => {
                if !cancel.is_cancelled() {
                    *retry = Some(RetryAdvice::Transport);
                }
                return Err(error);
            }
        };
        let Some(part) = part else {
            break;
        };
        let part = part.as_ref();
        if part.len() > limit.saturating_sub(bytes.len()) {
            return Err("接口响应超过大小上限".into());
        }
        bytes.extend_from_slice(part);
    }
    Ok(bytes)
}

pub struct ChatOutcome {
    pub turn: Turn,
    pub error: Option<String>,
    pub(crate) retry: Option<RetryAdvice>,
}

/// Send one real request. Failures keep deltas already emitted; the caller owns their
/// persistence and marks the message incomplete. No automatic retry duplicates a partial turn.
pub async fn chat(
    settings: Settings,
    messages: Vec<rust_protocol::Message>,
    tools: &[rust_protocol::ToolDefinition],
    cancel: Arc<CancelToken>,
    emit: impl FnMut(bool, &str),
) -> ChatOutcome {
    chat_with_budget(settings, messages, tools, cancel, emit, None).await
}
pub(crate) async fn chat_with_budget(
    settings: Settings,
    messages: Vec<rust_protocol::Message>,
    tools: &[rust_protocol::ToolDefinition],
    cancel: Arc<CancelToken>,
    emit: impl FnMut(bool, &str),
    request_budget: Option<Arc<RequestBudget>>,
) -> ChatOutcome {
    let prepared = match prepare_chat(&settings, &messages, tools) {
        Ok(prepared) => prepared,
        Err(error) => {
            return ChatOutcome {
                turn: TurnAccumulator::new().snapshot(),
                error: Some(Redaction::new(&settings).clean(error)),
                retry: None,
            };
        }
    };
    drop(messages);
    drop(settings);
    chat_prepared(&prepared, cancel, emit, request_budget).await
}
pub(crate) async fn chat_prepared(
    prepared: &PreparedChat,
    cancel: Arc<CancelToken>,
    mut emit: impl FnMut(bool, &str),
    request_budget: Option<Arc<RequestBudget>>,
) -> ChatOutcome {
    let mut turn = TurnAccumulator::new();
    let mut retry = None;
    if cancel.is_cancelled() {
        return ChatOutcome {
            turn: turn.snapshot(),
            error: Some("已停止".into()),
            retry: None,
        };
    }
    let result = tokio::select! {
        biased;
        _=cancel.cancelled()=>Err("已停止".into()),
        result=tokio::time::timeout(TURN_TIMEOUT,chat_inner(prepared,&cancel,&mut emit,&mut turn,request_budget.as_deref(),&mut retry))=> {
            match result {Ok(result)=>result,Err(_)=> {retry=Some(RetryAdvice::Transport);Err("接口请求超过10分钟，已结束连接；已收到的内容已保留".into())}}
        }
    };
    match result {
        Ok(turn) => ChatOutcome {
            turn,
            error: None,
            retry: None,
        },
        Err(error) => ChatOutcome {
            turn: turn.snapshot(),
            error: Some(prepared.redaction.clean(error).chars().take(600).collect()),
            retry: if cancel.is_cancelled() { None } else { retry },
        },
    }
}

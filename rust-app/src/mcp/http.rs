use super::protocol::{self, MAX_MESSAGE, ServerConfig};
use crate::network::{self, CancelToken};
use reqwest::{
    Client, Url,
    header::{ACCEPT, CONTENT_TYPE, HeaderMap, HeaderName, HeaderValue},
};
use serde_json::Value;
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

pub(super) struct HttpTransport {
    client: Client,
    url: Url,
    headers: HeaderMap,
    session: Mutex<Option<String>>,
    version: Mutex<String>,
    closed: Arc<CancelToken>,
}

impl HttpTransport {
    pub fn new(config: &ServerConfig) -> Result<Self, String> {
        let url = Url::parse(config.url.trim()).map_err(|_| "MCP HTTP 地址无效")?;
        let mut headers = HeaderMap::new();
        for (name, value) in protocol::parse_headers(&config.headers)? {
            let name = HeaderName::from_bytes(name.as_bytes()).map_err(|_| "MCP 请求头名称无效")?;
            let value = HeaderValue::from_str(&value).map_err(|_| "MCP 请求头值无效")?;
            if matches!(
                name.as_str(),
                "content-length" | "transfer-encoding" | "mcp-session-id" | "mcp-protocol-version"
            ) {
                return Err("MCP 会话及传输头由连接管理器维护".into());
            }
            headers.insert(name, value);
        }
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/json, text/event-stream"),
        );
        headers.insert(
            CONTENT_TYPE,
            HeaderValue::from_static("application/json; charset=utf-8"),
        );
        Ok(Self {
            client: network::client()?,
            url,
            headers,
            session: Mutex::new(None),
            version: Mutex::new(protocol::PROTOCOL_VERSION.into()),
            closed: Arc::new(CancelToken::new()),
        })
    }

    pub fn close(&self) {
        self.closed.cancel();
    }
    pub fn set_version(&self, version: &str) -> Result<(), String> {
        if version.len() > 128 || version.chars().any(char::is_control) {
            return Err("MCP 协议版本无效".into());
        }
        *self.version.lock().map_err(|_| "MCP 协议状态不可用")? = version.to_owned();
        Ok(())
    }

    pub async fn exchange(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
    ) -> Result<Value, String> {
        self.post(message, cancel, timeout, true).await
    }
    pub async fn notify(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
    ) -> Result<(), String> {
        self.post(message, cancel, timeout, false).await.map(|_| ())
    }

    async fn post(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
        reply: bool,
    ) -> Result<Value, String> {
        if cancel.is_cancelled() || self.closed.is_cancelled() {
            return Err("MCP 请求已取消".into());
        }
        network::ensure_network_permission(self.url.as_str())?;
        let bytes = serde_json::to_vec(&message).map_err(|_| "MCP 请求无法编码")?;
        if bytes.len() > MAX_MESSAGE {
            return Err("MCP 请求超过 1 MiB".into());
        }
        let id = message.get("id").cloned();
        if reply && id.is_none() {
            return Err("MCP 请求缺少 id".into());
        }
        let mut headers = self.headers.clone();
        if let Some(session) = self
            .session
            .lock()
            .map_err(|_| "MCP 会话状态不可用")?
            .as_ref()
        {
            headers.insert(
                "mcp-session-id",
                HeaderValue::from_str(session).map_err(|_| "MCP 会话身份无效")?,
            );
        }
        let version = self
            .version
            .lock()
            .map_err(|_| "MCP 协议状态不可用")?
            .clone();
        headers.insert(
            "mcp-protocol-version",
            HeaderValue::from_str(&version).map_err(|_| "MCP 协议版本无效")?,
        );
        let operation = async {
            let mut response = self
                .client
                .post(self.url.clone())
                .headers(headers)
                .body(bytes)
                .send()
                .await
                .map_err(|_| "MCP HTTP 请求失败")?;
            if response.status().is_redirection() {
                return Err("MCP HTTP 重定向未跟随，请更新服务器地址".into());
            }
            if !response.status().is_success() {
                return Err(format!(
                    "MCP 服务器返回 HTTP {}",
                    response.status().as_u16()
                ));
            }
            if let Some(value) = response.headers().get("mcp-session-id") {
                let value = value.to_str().map_err(|_| "MCP 会话身份无效")?;
                if value.is_empty() || value.len() > 256 {
                    return Err("MCP 会话身份超过预算".into());
                }
                let mut session = self.session.lock().map_err(|_| "MCP 会话状态不可用")?;
                if session.as_ref().is_some_and(|existing| existing != value) {
                    return Err("MCP 服务器在同一连接中更换了会话身份".into());
                }
                *session = Some(value.to_owned());
            }
            if !reply {
                return Ok(Value::Null);
            }
            let id = id.as_ref().ok_or("MCP 请求缺少 id")?;
            let content_type = response
                .headers()
                .get(CONTENT_TYPE)
                .and_then(|value| value.to_str().ok())
                .unwrap_or_default()
                .to_ascii_lowercase();
            let mut mode = if content_type.contains("text/event-stream") {
                Some(true)
            } else if content_type.contains("application/json") {
                Some(false)
            } else {
                None
            };
            let mut parser = SseParser::default();
            let mut json = Vec::new();
            let mut total = 0usize;
            while let Some(chunk) = response
                .chunk()
                .await
                .map_err(|_| "MCP HTTP 响应读取失败")?
            {
                total = total.saturating_add(chunk.len());
                if total > 16 * MAX_MESSAGE {
                    return Err("MCP SSE 响应超过 16 MiB".into());
                }
                if mode.is_none() {
                    json.extend_from_slice(&chunk);
                    if json.len() > MAX_MESSAGE {
                        return Err("MCP 响应前缀超过 1 MiB".into());
                    }
                    if let Some(first) = json
                        .iter()
                        .copied()
                        .find(|byte| !byte.is_ascii_whitespace())
                    {
                        mode = Some(!matches!(first, b'{' | b'['));
                        if mode == Some(true) {
                            if let Some(value) = parser.feed(&json, id)? {
                                return Ok(value);
                            }
                            json.clear();
                        }
                    }
                    continue;
                }
                if mode == Some(true) {
                    if let Some(value) = parser.feed(&chunk, id)? {
                        return Ok(value);
                    }
                } else {
                    if json.len().saturating_add(chunk.len()) > MAX_MESSAGE {
                        return Err("MCP JSON 响应超过 1 MiB".into());
                    }
                    json.extend_from_slice(&chunk);
                }
            }
            if mode == Some(true) {
                parser
                    .finish(id)?
                    .ok_or_else(|| "MCP SSE 流中没有匹配请求的响应".into())
            } else {
                let value: Value =
                    serde_json::from_slice(&json).map_err(|_| "MCP HTTP 响应不是有效 JSON")?;
                if !protocol::response_for(&value, id) {
                    return Err("MCP 响应 id 或 JSON-RPC 结构不匹配".into());
                }
                Ok(value)
            }
        };
        tokio::select! {biased;
            _=cancel.cancelled()=>Err("MCP 请求已取消".into()),
            _=self.closed.cancelled()=>Err("MCP 连接已关闭".into()),
            result=tokio::time::timeout(timeout,operation)=>result.map_err(|_|"MCP 响应超时".to_owned())?,
        }
    }
}

#[derive(Default)]
struct SseParser {
    line: Vec<u8>,
    data: Vec<u8>,
}
impl SseParser {
    fn feed(&mut self, bytes: &[u8], id: &Value) -> Result<Option<Value>, String> {
        for &byte in bytes {
            if byte == b'\n' {
                if let Some(value) = self.line(id)? {
                    return Ok(Some(value));
                }
            } else {
                if self.line.len() == MAX_MESSAGE {
                    return Err("MCP SSE 行超过 1 MiB".into());
                }
                self.line.push(byte);
            }
        }
        Ok(None)
    }
    fn line(&mut self, id: &Value) -> Result<Option<Value>, String> {
        if self.line.last() == Some(&b'\r') {
            self.line.pop();
        }
        if self.line.is_empty() {
            return self.event(id);
        }
        if let Some(data) = self.line.strip_prefix(b"data:") {
            let data = data.strip_prefix(b" ").unwrap_or(data);
            let separator = usize::from(!self.data.is_empty());
            if self
                .data
                .len()
                .saturating_add(separator)
                .saturating_add(data.len())
                > MAX_MESSAGE
            {
                return Err("MCP SSE 事件超过 1 MiB".into());
            }
            if separator != 0 {
                self.data.push(b'\n');
            }
            self.data.extend_from_slice(data);
        }
        self.line.clear();
        Ok(None)
    }
    fn event(&mut self, id: &Value) -> Result<Option<Value>, String> {
        self.line.clear();
        if self.data.is_empty() {
            return Ok(None);
        }
        let data = std::mem::take(&mut self.data);
        if data == b"[DONE]" {
            return Ok(None);
        }
        let message: Value =
            serde_json::from_slice(&data).map_err(|_| "MCP SSE 事件不是有效 JSON")?;
        Ok(protocol::response_for(&message, id).then_some(message))
    }
    fn finish(&mut self, id: &Value) -> Result<Option<Value>, String> {
        if !self.line.is_empty() {
            if let Some(value) = self.line(id)? {
                return Ok(Some(value));
            }
        }
        self.event(id)
    }
}

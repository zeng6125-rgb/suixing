//! Safe protocol API for a Rust-owned app/controller. No JNI, Android class or JVM state is used.
use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;
use std::io::{self, Write};

const MAX_FRAME_OPERATIONS: usize = 131_072;
const MAX_TURN_UNITS: usize = 16 * 1024 * 1024;
const MAX_CALLS: usize = 1024;
const MAX_DOCUMENT_NODES: usize = 131_072;
const MAX_DOCUMENT_DEPTH: usize = 128;
const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;
const MAX_OPERATION_BYTES: usize = 16 * 1024 * 1024;
const MAX_REQUEST_BYTES: usize = 64 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Protocol {
    #[default]
    OpenAi,
    Anthropic,
    Responses,
}
impl Protocol {
    pub fn from_name(value: &str) -> Self {
        match value {
            "anthropic" => Self::Anthropic,
            "openai-responses" | "responses" => Self::Responses,
            _ => Self::OpenAi,
        }
    }
}

#[derive(Clone, Debug)]
pub struct RequestSettings {
    pub model: String,
    pub max_tokens: i32,
    pub reasoning_effort: String,
    pub send_temperature: bool,
    pub temperature: f64,
    pub send_top_p: bool,
    pub top_p: f64,
}
impl Default for RequestSettings {
    fn default() -> Self {
        Self {
            model: String::new(),
            max_tokens: 0,
            reasoning_effort: String::new(),
            send_temperature: false,
            temperature: 1.0,
            send_top_p: false,
            top_p: 1.0,
        }
    }
}
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: String,
}
#[derive(Clone, Debug, Default)]
pub struct Message {
    pub role: String,
    pub text: String,
    pub tool_calls: Vec<ToolCall>,
    pub tool_call_id: Option<String>,
    pub images_base64: Vec<String>,
}
impl Message {
    pub fn system(text: impl Into<String>) -> Self {
        Self {
            role: "system".into(),
            text: text.into(),
            ..Self::default()
        }
    }
    pub fn user(text: impl Into<String>) -> Self {
        Self {
            role: "user".into(),
            text: text.into(),
            ..Self::default()
        }
    }
    pub fn assistant(text: impl Into<String>, tool_calls: Vec<ToolCall>) -> Self {
        Self {
            role: "assistant".into(),
            text: text.into(),
            tool_calls,
            ..Self::default()
        }
    }
    pub fn tool(id: impl Into<String>, text: impl Into<String>) -> Self {
        Self {
            role: "tool".into(),
            text: text.into(),
            tool_call_id: Some(id.into()),
            ..Self::default()
        }
    }
}
#[derive(Clone, Debug)]
pub struct ToolDefinition {
    pub name: String,
    pub description: String,
    pub parameters: Value,
}
#[derive(Clone, Debug, PartialEq)]
pub struct Model {
    pub id: String,
    pub context_length: Option<i32>,
}
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Usage {
    pub prompt_tokens: i32,
    pub completion_tokens: i32,
    pub reasoning_tokens: i32,
    pub cached_tokens: i32,
}
impl Usage {
    pub fn total_tokens(self) -> i32 {
        self.prompt_tokens.wrapping_add(self.completion_tokens)
    }
    pub fn add(self, other: Self) -> Self {
        Self {
            prompt_tokens: self.prompt_tokens.wrapping_add(other.prompt_tokens),
            completion_tokens: self.completion_tokens.wrapping_add(other.completion_tokens),
            reasoning_tokens: self.reasoning_tokens.wrapping_add(other.reasoning_tokens),
            cached_tokens: self.cached_tokens.wrapping_add(other.cached_tokens),
        }
    }
    fn merge_positive(self, other: Self) -> Self {
        let pick = |new, old| if new > 0 { new } else { old };
        Self {
            prompt_tokens: pick(other.prompt_tokens, self.prompt_tokens),
            completion_tokens: pick(other.completion_tokens, self.completion_tokens),
            reasoning_tokens: pick(other.reasoning_tokens, self.reasoning_tokens),
            cached_tokens: pick(other.cached_tokens, self.cached_tokens),
        }
    }
}
/// A missing or unreadable count stays `None`; an explicitly reported zero stays `Some(0)`.
/// Reasoning/cache are components of completion/prompt, not extra total tokens.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct UsageReport {
    pub prompt_tokens: Option<i32>,
    pub completion_tokens: Option<i32>,
    pub reasoning_tokens: Option<i32>,
    pub cached_tokens: Option<i32>,
}
impl UsageReport {
    pub fn any_reported(self) -> bool {
        [
            self.prompt_tokens,
            self.completion_tokens,
            self.reasoning_tokens,
            self.cached_tokens,
        ]
        .into_iter()
        .any(|count| count.is_some())
    }
    pub fn totals_reported(self) -> bool {
        self.prompt_tokens.is_some() && self.completion_tokens.is_some()
    }
    pub fn counts(self) -> Usage {
        Usage {
            prompt_tokens: self.prompt_tokens.unwrap_or(0),
            completion_tokens: self.completion_tokens.unwrap_or(0),
            reasoning_tokens: self.reasoning_tokens.unwrap_or(0),
            cached_tokens: self.cached_tokens.unwrap_or(0),
        }
    }
    fn merge(self, other: Self) -> Self {
        Self {
            prompt_tokens: other.prompt_tokens.or(self.prompt_tokens),
            completion_tokens: other.completion_tokens.or(self.completion_tokens),
            reasoning_tokens: other.reasoning_tokens.or(self.reasoning_tokens),
            cached_tokens: other.cached_tokens.or(self.cached_tokens),
        }
    }
}
#[derive(Clone, Debug, Default)]
pub struct Turn {
    pub text: String,
    pub reasoning: String,
    pub tool_calls: Vec<ToolCall>,
    pub finish_reason: Option<String>,
    pub usage: Option<Usage>,
    pub usage_report: UsageReport,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    InvalidDocument(String),
    TurnLimit,
    CallLimit,
    Cancelled,
}
impl fmt::Display for ProtocolError {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidDocument(message) => output.write_str(message),
            Self::TurnLimit => output.write_str("流式响应超过单轮 16 Mi UTF-16 单位上限"),
            Self::CallLimit => output.write_str("单轮工具调用数量超过 1024 上限"),
            Self::Cancelled => output.write_str("任务已停止"),
        }
    }
}
impl std::error::Error for ProtocolError {}
fn blank(value: &str) -> bool {
    value.chars().all(crate::java_whitespace)
}
fn trimmed(value: &str) -> &str {
    value.trim_matches(crate::java_whitespace)
}
fn primitive(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(value) => Some(value.clone()),
        Value::Bool(value) => Some(value.to_string()),
        Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}
fn strict_primitive(value: Option<&Value>, field: &str) -> Result<Option<String>, ProtocolError> {
    if matches!(value, Some(Value::Array(_) | Value::Object(_))) {
        return Err(ProtocolError::InvalidDocument(format!(
            "字段 {field} 必须是原始值"
        )));
    }
    Ok(primitive(value))
}
fn scalar_int(value: Option<&Value>) -> Option<i32> {
    primitive(value)?.parse().ok()
}
fn text_of(value: Option<&Value>) -> Option<String> {
    fn append(value: &Value, result: &mut String) {
        match value {
            Value::Array(values) => {
                for value in values {
                    append(value, result);
                }
            }
            Value::Object(values) => {
                if let Some(value) = values
                    .get("text")
                    .or_else(|| values.get("thinking"))
                    .or_else(|| values.get("content"))
                {
                    append(value, result);
                }
            }
            Value::String(value) => result.push_str(value),
            value => {
                if let Some(value) = primitive(Some(value)) {
                    result.push_str(&value);
                }
            }
        }
    }
    match value? {
        Value::Array(values) => {
            let mut result = String::new();
            for value in values {
                append(value, &mut result);
            }
            (!result.is_empty()).then_some(result)
        }
        Value::Object(values) => text_of(
            values
                .get("text")
                .or_else(|| values.get("thinking"))
                .or_else(|| values.get("content")),
        ),
        value => primitive(Some(value)),
    }
}
fn object(value: Option<&Value>) -> Option<&Map<String, Value>> {
    value?.as_object()
}
fn normalized_finish(value: String) -> String {
    match value.as_str() {
        "max_tokens" | "max_output_tokens" => "length".into(),
        "end_turn" => "stop".into(),
        "tool_use" => "tool_calls".into(),
        _ => value,
    }
}

struct DocumentBudget {
    bytes: usize,
    nodes: usize,
    limit: usize,
}
impl DocumentBudget {
    fn new(limit: usize) -> Self {
        Self {
            bytes: 0,
            nodes: 0,
            limit,
        }
    }
    fn charge(&mut self, bytes: usize) -> Result<(), ProtocolError> {
        if bytes > self.limit.saturating_sub(self.bytes) {
            return Err(ProtocolError::InvalidDocument(
                "协议文档超过字节上限".into(),
            ));
        }
        self.bytes += bytes;
        Ok(())
    }
    fn string(&mut self, value: &str) -> Result<(), ProtocolError> {
        // Compute escaped JSON size without allocating or serializing another String.
        self.charge(value.len().saturating_add(2))?;
        self.charge(
            value
                .bytes()
                .map(|byte| match byte {
                    b'"' | b'\\' | b'\n' | b'\r' | b'\t' | 8 | 12 => 1,
                    0..=31 => 5,
                    _ => 0,
                })
                .sum(),
        )
    }
    fn node(&mut self) -> Result<(), ProtocolError> {
        if self.nodes >= MAX_DOCUMENT_NODES {
            return Err(ProtocolError::InvalidDocument(
                "协议文档超过节点上限".into(),
            ));
        }
        self.nodes += 1;
        self.charge(8)
    }
    fn value(&mut self, value: &Value, depth: usize) -> Result<(), ProtocolError> {
        if depth > MAX_DOCUMENT_DEPTH {
            return Err(ProtocolError::InvalidDocument(
                "协议文档超过嵌套深度上限".into(),
            ));
        }
        self.node()?;
        match value {
            Value::Array(values) => {
                for value in values {
                    self.value(value, depth + 1)?;
                }
            }
            Value::Object(values) => {
                for (key, value) in values {
                    self.string(key)?;
                    self.value(value, depth + 1)?;
                }
            }
            Value::String(value) => self.string(value)?,
            Value::Number(number) => self.charge(number.to_string().len())?,
            Value::Bool(_) | Value::Null => (),
        }
        Ok(())
    }
}
fn bounded_document(value: &Value, limit: usize) -> Result<(), ProtocolError> {
    DocumentBudget::new(limit).value(value, 0)
}

struct CountingWriter {
    bytes: usize,
}
impl Write for CountingWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_REQUEST_BYTES.saturating_sub(self.bytes) {
            return Err(io::Error::other("请求 JSON 超过 64 MiB 上限"));
        }
        self.bytes += bytes.len();
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn bounded_request(
    settings: &RequestSettings,
    messages: &[Message],
    tools: &[ToolDefinition],
) -> Result<(), ProtocolError> {
    let mut budget = DocumentBudget::new(MAX_REQUEST_BYTES);
    budget.string(&settings.model)?;
    budget.string(&settings.reasoning_effort)?;
    for message in messages {
        budget.node()?;
        budget.string(&message.role)?;
        budget.string(&message.text)?;
        if let Some(id) = &message.tool_call_id {
            budget.string(id)?;
        }
        for image in &message.images_base64 {
            if image_media::media_type(image).is_none() {
                return Err(ProtocolError::InvalidDocument(
                    "无法识别原始图片格式；历史原文保留".into(),
                ));
            }
            budget.node()?;
            budget.string(image)?;
        }
        for call in &message.tool_calls {
            budget.node()?;
            budget.string(&call.id)?;
            budget.string(&call.name)?;
            budget.string(&call.arguments)?;
        }
    }
    for tool in tools {
        budget.node()?;
        budget.string(&tool.name)?;
        budget.string(&tool.description)?;
        budget.value(&tool.parameters, 0)?;
    }
    Ok(())
}

const PROMPT_PATHS: &[&str] = &["prompt_tokens", "input_tokens"];
const COMPLETION_PATHS: &[&str] = &["completion_tokens", "output_tokens"];
const REASONING_PATHS: &[&str] = &[
    "completion_tokens_details.reasoning_tokens",
    "completion_thinking_tokens",
    "reasoning_tokens",
    "thinking_tokens",
    "output_tokens_details.thinking_tokens",
];
const CACHED_PATHS: &[&str] = &[
    "prompt_tokens_details.cached_tokens",
    "prompt_cache_hit_tokens",
    "cache_read_input_tokens",
    "cached_tokens",
];

/// Reads only complete paths. Unlike the legacy count helper, a primitive parent of a
/// missing nested field is not mistaken for a reported leaf.
pub fn parse_usage_report(source: &Value) -> UsageReport {
    fn number(source: &Value, paths: &[&str]) -> Option<i32> {
        paths.iter().find_map(|path| {
            let leaf = path
                .split('.')
                .try_fold(source, |cursor, key| cursor.as_object()?.get(key))?;
            primitive(Some(leaf))
                .and_then(|text| text.parse::<f64>().ok())
                .filter(|number| number.is_finite())
                .map(|number| number as i32)
        })
    }
    UsageReport {
        prompt_tokens: number(source, PROMPT_PATHS),
        completion_tokens: number(source, COMPLETION_PATHS),
        reasoning_tokens: number(source, REASONING_PATHS),
        cached_tokens: number(source, CACHED_PATHS),
    }
}

pub fn parse_usage(source: &Value) -> Option<Usage> {
    fn number(source: &Value, paths: &[&str]) -> i32 {
        for path in paths {
            let mut cursor = source;
            for key in path.split('.') {
                let Some(next) = cursor.as_object().and_then(|object| object.get(key)) else {
                    break;
                };
                cursor = next;
            }
            if let Some(value) = primitive(Some(cursor)).and_then(|value| value.parse::<f64>().ok())
            {
                return value as i32;
            }
        }
        0
    }
    let result = Usage {
        prompt_tokens: number(source, PROMPT_PATHS),
        completion_tokens: number(source, COMPLETION_PATHS),
        reasoning_tokens: number(source, REASONING_PATHS),
        cached_tokens: number(source, CACHED_PATHS),
    };
    (result != Usage::default()).then_some(result)
}

pub fn parse_models(source: &Value) -> Result<Vec<Model>, ProtocolError> {
    bounded_document(source, MAX_FRAME_BYTES)?;
    let list = source
        .as_array()
        .or_else(|| {
            ["data", "models", "result"]
                .iter()
                .find_map(|key| source.get(key).and_then(Value::as_array))
        })
        .ok_or_else(|| ProtocolError::InvalidDocument("返回里没有 data / models 字段".into()))?;
    let mut models = Vec::new();
    let mut seen = HashSet::new();
    for value in list {
        let field = if value.is_object() {
            value
                .get("id")
                .or_else(|| value.get("name"))
                .or_else(|| value.get("model"))
        } else {
            Some(value)
        };
        let Some(id) = primitive(field).filter(|id| !blank(id)) else {
            continue;
        };
        if !seen.insert(id.clone()) {
            continue;
        }
        let context_length = [
            value.get("context_window"),
            value.get("context_length"),
            value
                .get("top_provider")
                .and_then(|provider| provider.get("context_length")),
            value.get("max_context_length"),
            value.get("max_model_len"),
            value.get("max_input_tokens"),
        ]
        .into_iter()
        .find_map(|value| scalar_int(value).filter(|&count| count > 0));
        models.push(Model { id, context_length });
    }
    if models.is_empty() {
        return Err(ProtocolError::InvalidDocument(
            "服务器没有返回任何模型".into(),
        ));
    }
    Ok(models)
}

fn normalized_schema(value: &Value) -> Value {
    let mut result = value.as_object().cloned().unwrap_or_default();
    if result.get("type").and_then(Value::as_str) == Some("object")
        && result.get("properties").is_some_and(Value::is_object)
    {
        return Value::Object(result);
    }
    let properties = result
        .remove("properties")
        .filter(Value::is_object)
        .unwrap_or_else(|| json!({}));
    result.remove("type");
    result.insert("type".into(), json!("object"));
    result.insert("properties".into(), properties);
    Value::Object(result)
}
fn safe_arguments(value: &str) -> Value {
    serde_json::from_str::<Value>(if blank(value) { "{}" } else { value })
        .ok()
        .filter(Value::is_object)
        .unwrap_or_else(|| json!({}))
}
fn arguments(value: &str) -> &str {
    if blank(value) { "{}" } else { value }
}
fn chat_message(message: &Message) -> Value {
    let mut result = Map::new();
    result.insert("role".into(), json!(message.role));
    let content = if message.images_base64.is_empty() || message.role == "tool" {
        json!(message.text)
    } else {
        let mut content = Vec::new();
        if !blank(&message.text) {
            content.push(json!({"type":"text", "text":message.text}));
        }
        for image in &message.images_base64 {
            content.push(json!({"type":"image_url", "image_url":{"url":format!("data:{};base64,{image}",image_media::media_type(image).unwrap_or("image/jpeg"))}}));
        }
        Value::Array(content)
    };
    result.insert("content".into(), content);
    if let Some(id) = &message.tool_call_id {
        result.insert("tool_call_id".into(), json!(id));
    }
    if !message.tool_calls.is_empty() {
        result.insert("tool_calls".into(), Value::Array(message.tool_calls.iter().map(|call| json!({"id":call.id,"type":"function","function":{"name":call.name,"arguments":arguments(&call.arguments)}})).collect()));
    }
    Value::Object(result)
}
fn anthropic_message(message: &Message) -> (String, Vec<Value>) {
    if message.role == "tool" {
        let content = if message.images_base64.is_empty() {
            json!(message.text)
        } else {
            let mut content = vec![json!({"type":"text","text":message.text})];
            for image in &message.images_base64 {
                content.push(json!({"type":"image","source":{"type":"base64","media_type":image_media::media_type(image).unwrap_or("image/jpeg"),"data":image}}));
            }
            Value::Array(content)
        };
        return (
            "user".into(),
            vec![
                json!({"type":"tool_result","tool_use_id":message.tool_call_id.as_deref().unwrap_or(""),"content":content}),
            ],
        );
    }
    let mut blocks = Vec::new();
    if !message.images_base64.is_empty() {
        if !blank(&message.text) {
            blocks.push(json!({"type":"text","text":message.text}));
        }
        for image in &message.images_base64 {
            blocks.push(json!({"type":"image","source":{"type":"base64","media_type":image_media::media_type(image).unwrap_or("image/jpeg"),"data":image}}));
        }
    } else if !message.text.is_empty() || message.tool_calls.is_empty() {
        blocks.push(json!({"type":"text","text":message.text}));
    }
    for call in &message.tool_calls {
        blocks.push(json!({"type":"tool_use","id":call.id,"name":call.name,"input":safe_arguments(&call.arguments)}));
    }
    (
        if message.role == "assistant" {
            "assistant".into()
        } else {
            "user".into()
        },
        blocks,
    )
}
fn response_items(message: &Message) -> Vec<Value> {
    if message.role == "tool" {
        return vec![
            json!({"type":"function_call_output","call_id":message.tool_call_id.as_deref().unwrap_or(""),"output":message.text}),
        ];
    }
    let mut result = Vec::new();
    if !message.images_base64.is_empty() {
        let mut content = Vec::new();
        if !blank(&message.text) {
            content.push(json!({"type":"input_text","text":message.text}));
        }
        for image in &message.images_base64 {
            content.push(
                json!({"type":"input_image","image_url":format!("data:{};base64,{image}",image_media::media_type(image).unwrap_or("image/jpeg"))}),
            );
        }
        result.push(json!({"role":message.role,"content":content}));
    } else if !message.text.is_empty() || message.tool_calls.is_empty() {
        result.push(json!({"role":message.role,"content":message.text}));
    }
    for call in &message.tool_calls {
        result.push(json!({"type":"function_call","call_id":call.id,"name":call.name,"arguments":arguments(&call.arguments)}));
    }
    result
}

pub fn build_request(
    protocol: Protocol,
    settings: &RequestSettings,
    messages: &[Message],
    tools: &[ToolDefinition],
) -> Result<Value, ProtocolError> {
    bounded_request(settings, messages, tools)?;
    let mut result = Map::new();
    result.insert("model".into(), json!(settings.model));
    result.insert("stream".into(), json!(true));
    if settings.send_temperature {
        if !settings.temperature.is_finite() {
            return Err(ProtocolError::InvalidDocument(
                "temperature 必须是有限数字".into(),
            ));
        }
        result.insert("temperature".into(), json!(settings.temperature));
    }
    if settings.send_top_p {
        if !settings.top_p.is_finite() {
            return Err(ProtocolError::InvalidDocument(
                "top_p 必须是有限数字".into(),
            ));
        }
        result.insert("top_p".into(), json!(settings.top_p));
    }
    match protocol {
        Protocol::OpenAi => {
            result.insert("messages".into(), Value::Array(chat_items(messages)));
            result.insert("stream_options".into(), json!({"include_usage":true}));
            if settings.max_tokens > 0 {
                result.insert("max_tokens".into(), json!(settings.max_tokens));
            }
            if !blank(&settings.reasoning_effort) {
                result.insert("reasoning_effort".into(), json!(settings.reasoning_effort));
            }
            if !tools.is_empty() {
                result.insert("tools".into(), Value::Array(tools.iter().map(|tool| json!({"type":"function","function":{"name":tool.name,"description":tool.description,"parameters":normalized_schema(&tool.parameters)}})).collect()));
                result.insert("tool_choice".into(), json!("auto"));
            }
        }
        Protocol::Anthropic => {
            result.insert(
                "max_tokens".into(),
                json!(if settings.max_tokens > 0 {
                    settings.max_tokens
                } else {
                    4096
                }),
            );
            let system = messages
                .iter()
                .filter(|message| message.role == "system")
                .map(|message| message.text.as_str())
                .collect::<Vec<_>>()
                .join("\n\n");
            if !trimmed(&system).is_empty() {
                result.insert("system".into(), json!(trimmed(&system)));
            }
            let mut merged = Vec::<(String, Vec<Value>)>::new();
            for message in messages.iter().filter(|message| message.role != "system") {
                let (role, content) = anthropic_message(message);
                if let Some((_, last_content)) = merged
                    .last_mut()
                    .filter(|(last_role, _)| last_role == &role)
                {
                    last_content.extend(content);
                } else {
                    merged.push((role, content));
                }
            }
            result.insert(
                "messages".into(),
                Value::Array(
                    merged
                        .into_iter()
                        .map(|(role, content)| json!({"role":role,"content":content}))
                        .collect(),
                ),
            );
            if !tools.is_empty() {
                result.insert("tools".into(), Value::Array(tools.iter().map(|tool| json!({"name":tool.name,"description":tool.description,"input_schema":normalized_schema(&tool.parameters)})).collect()));
                result.insert("tool_choice".into(), json!({"type":"auto"}));
            }
        }
        Protocol::Responses => {
            result.insert("store".into(), json!(false));
            if settings.max_tokens > 0 {
                result.insert("max_output_tokens".into(), json!(settings.max_tokens));
            }
            if !blank(&settings.reasoning_effort) {
                result.insert(
                    "reasoning".into(),
                    json!({"effort":settings.reasoning_effort}),
                );
            }
            let instructions = messages
                .iter()
                .filter(|message| message.role == "system")
                .map(|message| message.text.as_str())
                .collect::<Vec<_>>()
                .join("\n\n");
            if !trimmed(&instructions).is_empty() {
                result.insert("instructions".into(), json!(trimmed(&instructions)));
            }
            result.insert("input".into(), Value::Array(response_batch_items(messages)));
            if !tools.is_empty() {
                result.insert("tools".into(), Value::Array(tools.iter().map(|tool| json!({"type":"function","name":tool.name,"description":tool.description,"parameters":normalized_schema(&tool.parameters)})).collect()));
                result.insert("tool_choice".into(), json!("auto"));
            }
        }
    }
    let result = Value::Object(result);
    serde_json::to_writer(CountingWriter { bytes: 0 }, &result)
        .map_err(|failure| ProtocolError::InvalidDocument(failure.to_string()))?;
    Ok(result)
}

pub fn endpoint(base: &str, protocol: Protocol) -> Result<String, ProtocolError> {
    let base = trimmed(base).trim_end_matches('/');
    if base.is_empty() {
        return Err(ProtocolError::InvalidDocument(
            "请先在「设置」里填写接口地址".into(),
        ));
    }
    Ok(match protocol {
        Protocol::Anthropic if base.ends_with("/messages") => base.into(),
        Protocol::Anthropic if base.ends_with("/v1") => format!("{base}/messages"),
        Protocol::Anthropic => format!("{base}/v1/messages"),
        Protocol::Responses if base.ends_with("/responses") => base.into(),
        Protocol::Responses => format!("{base}/responses"),
        Protocol::OpenAi if base.ends_with("/chat/completions") => base.into(),
        Protocol::OpenAi => format!("{base}/chat/completions"),
    })
}
pub fn models_endpoint(base: &str, protocol: Protocol) -> Result<String, ProtocolError> {
    let base = trimmed(base).trim_end_matches('/');
    if base.is_empty() {
        return Err(ProtocolError::InvalidDocument(
            "请先在「设置」里填写接口地址".into(),
        ));
    }
    if protocol == Protocol::Anthropic {
        let base = if let Some(base) = base.strip_suffix("/messages") {
            base.to_owned()
        } else if base.ends_with("/v1") {
            base.into()
        } else {
            format!("{base}/v1")
        };
        return Ok(format!("{base}/models"));
    }
    if base.ends_with("/models") {
        return Ok(base.into());
    }
    let root = base
        .strip_suffix("/chat/completions")
        .or_else(|| base.strip_suffix("/responses"))
        .unwrap_or(base);
    Ok(format!("{root}/models"))
}

#[derive(Clone, Debug)]
pub enum Operation {
    Text(String),
    Thinking(String),
    Call {
        index: i32,
        id: Option<String>,
        name: Option<String>,
        arguments: Option<String>,
    },
    ItemCall {
        item: String,
        id: Option<String>,
        name: Option<String>,
        arguments: Option<String>,
    },
    Finish(String),
    NormalizeFinish(String),
    StopIfUnset,
    ResponseFinish(Option<String>),
    Usage(Option<Usage>),
    MergeUsage(Option<Usage>),
    UsageReport(UsageReport),
    MergeUsageReport(UsageReport),
}
#[derive(Debug)]
pub struct FrameBatch {
    pub operations: Vec<Operation>,
    pub failure: Option<ProtocolError>,
}
#[derive(Default)]
struct PendingOperations {
    operations: Vec<Operation>,
    bytes: usize,
}
fn push(operations: &mut PendingOperations, value: Operation) -> Result<(), ProtocolError> {
    if operations.operations.len() >= MAX_FRAME_OPERATIONS {
        return Err(ProtocolError::InvalidDocument(
            "单帧操作超过 131072 上限".into(),
        ));
    }
    let size = match &value {
        Operation::Text(text)
        | Operation::Thinking(text)
        | Operation::Finish(text)
        | Operation::NormalizeFinish(text) => text.len(),
        Operation::Call {
            id,
            name,
            arguments,
            ..
        } => [id, name, arguments]
            .into_iter()
            .flatten()
            .map(String::len)
            .sum(),
        Operation::ItemCall {
            item,
            id,
            name,
            arguments,
        } => {
            item.len()
                + [id, name, arguments]
                    .into_iter()
                    .flatten()
                    .map(String::len)
                    .sum::<usize>()
        }
        Operation::ResponseFinish(reason) => reason.as_ref().map_or(0, String::len),
        _ => 0,
    } + std::mem::size_of::<Operation>();
    if size > MAX_OPERATION_BYTES.saturating_sub(operations.bytes) {
        return Err(ProtocolError::InvalidDocument(
            "单帧操作超过 16 MiB 内存上限".into(),
        ));
    }
    operations.bytes += size;
    operations.operations.push(value);
    Ok(())
}
fn push_text(
    operations: &mut PendingOperations,
    value: Option<String>,
    thinking: bool,
) -> Result<(), ProtocolError> {
    if let Some(value) = value.filter(|value| !value.is_empty() && (!thinking || !blank(value))) {
        push(
            operations,
            if thinking {
                Operation::Thinking(value)
            } else {
                Operation::Text(value)
            },
        )?;
    }
    Ok(())
}
pub fn normalize_frame(protocol: Protocol, source: &Value) -> FrameBatch {
    let mut operations = PendingOperations::default();
    let result = (|| {
        bounded_document(source, MAX_FRAME_BYTES)?;
        let source = source
            .as_object()
            .ok_or_else(|| ProtocolError::InvalidDocument("流式事件必须是对象".into()))?;
        match protocol {
            Protocol::OpenAi => {
                if let Some(usage) = source.get("usage").filter(|value| value.is_object()) {
                    push(&mut operations, Operation::Usage(parse_usage(usage)))?;
                    push(
                        &mut operations,
                        Operation::UsageReport(parse_usage_report(usage)),
                    )?;
                }
                let Some(choice) = source
                    .get("choices")
                    .and_then(Value::as_array)
                    .and_then(|values| values.first())
                    .and_then(Value::as_object)
                else {
                    return Ok(());
                };
                if let Some(reason) =
                    strict_primitive(choice.get("finish_reason"), "finish_reason")?
                {
                    push(&mut operations, Operation::Finish(reason))?;
                }
                let delta = object(choice.get("delta"));
                let message = object(choice.get("message"));
                let mut reasoning = None;
                for object in [delta, message, Some(choice), Some(source)] {
                    for key in ["reasoning_content", "reasoning"] {
                        if let Some(value) = text_of(object.and_then(|object| object.get(key)))
                            .filter(|value| !blank(value))
                        {
                            reasoning = Some(value);
                            break;
                        }
                    }
                    if reasoning.is_some() {
                        break;
                    }
                }
                push_text(&mut operations, reasoning, true)?;
                push_text(
                    &mut operations,
                    text_of(
                        delta
                            .and_then(|value| value.get("content"))
                            .or_else(|| message.and_then(|value| value.get("content"))),
                    ),
                    false,
                )?;
                if let Some(calls) = delta
                    .and_then(|value| value.get("tool_calls"))
                    .or_else(|| message.and_then(|value| value.get("tool_calls")))
                    .and_then(Value::as_array)
                {
                    for call in calls {
                        let Some(call) = call.as_object() else {
                            continue;
                        };
                        let index = strict_primitive(call.get("index"), "index")?
                            .and_then(|value| value.parse().ok())
                            .unwrap_or(0);
                        let Some(function) = object(call.get("function")) else {
                            continue;
                        };
                        push(
                            &mut operations,
                            Operation::Call {
                                index,
                                id: primitive(call.get("id")),
                                name: primitive(function.get("name")),
                                arguments: primitive(function.get("arguments")),
                            },
                        )?;
                    }
                }
            }
            Protocol::Anthropic => match primitive(source.get("type")).as_deref() {
                Some("message_start") => {
                    if let Some(block) = source
                        .get("message")
                        .and_then(|value| value.get("usage"))
                        .filter(|value| value.is_object())
                    {
                        if let Some(usage) = parse_usage(block) {
                            push(&mut operations, Operation::Usage(Some(usage)))?;
                        }
                        push(
                            &mut operations,
                            Operation::MergeUsageReport(parse_usage_report(block)),
                        )?;
                    }
                }
                Some("content_block_start") => {
                    let Some(block) = object(source.get("content_block")) else {
                        return Ok(());
                    };
                    if primitive(block.get("type")).as_deref() == Some("tool_use") {
                        push(
                            &mut operations,
                            Operation::Call {
                                index: scalar_int(source.get("index")).unwrap_or(0),
                                id: primitive(block.get("id")),
                                name: primitive(block.get("name")),
                                arguments: block
                                    .get("input")
                                    .filter(|value| !value.is_null())
                                    .map(Value::to_string)
                                    .filter(|value| value != "{}"),
                            },
                        )?;
                    }
                    push_text(&mut operations, primitive(block.get("text")), false)?;
                }
                Some("content_block_delta") => {
                    let Some(delta) = object(source.get("delta")) else {
                        return Ok(());
                    };
                    match primitive(delta.get("type")).as_deref() {
                        Some("text_delta") => {
                            push_text(&mut operations, primitive(delta.get("text")), false)?
                        }
                        Some("thinking_delta") => {
                            push_text(&mut operations, primitive(delta.get("thinking")), true)?
                        }
                        Some("input_json_delta") => push(
                            &mut operations,
                            Operation::Call {
                                index: scalar_int(source.get("index")).unwrap_or(0),
                                id: None,
                                name: None,
                                arguments: primitive(delta.get("partial_json")),
                            },
                        )?,
                        _ => (),
                    }
                }
                Some("message_delta") => {
                    if let Some(reason) = primitive(
                        object(source.get("delta")).and_then(|value| value.get("stop_reason")),
                    ) {
                        push(&mut operations, Operation::NormalizeFinish(reason))?;
                    }
                    if let Some(usage) = source.get("usage").filter(|value| value.is_object()) {
                        push(&mut operations, Operation::MergeUsage(parse_usage(usage)))?;
                        push(
                            &mut operations,
                            Operation::MergeUsageReport(parse_usage_report(usage)),
                        )?;
                    }
                }
                Some("message_stop") => push(&mut operations, Operation::StopIfUnset)?,
                _ => (),
            },
            Protocol::Responses => match primitive(source.get("type")).as_deref() {
                Some("response.output_item.added") => {
                    let Some(item) = object(source.get("item")) else {
                        return Ok(());
                    };
                    if primitive(item.get("type")).as_deref() != Some("function_call") {
                        return Ok(());
                    }
                    let Some(item_id) =
                        primitive(item.get("id")).or_else(|| primitive(item.get("call_id")))
                    else {
                        return Ok(());
                    };
                    push(
                        &mut operations,
                        Operation::ItemCall {
                            item: item_id.clone(),
                            id: Some(primitive(item.get("call_id")).unwrap_or(item_id)),
                            name: primitive(item.get("name")),
                            arguments: primitive(item.get("arguments")),
                        },
                    )?;
                }
                Some("response.function_call_arguments.delta") => {
                    if let Some(item) = primitive(source.get("item_id")) {
                        push(
                            &mut operations,
                            Operation::ItemCall {
                                item,
                                id: None,
                                name: None,
                                arguments: primitive(source.get("delta")),
                            },
                        )?;
                    }
                }
                Some("response.output_text.delta") => {
                    push_text(&mut operations, primitive(source.get("delta")), false)?
                }
                Some("response.reasoning_summary_text.delta" | "response.reasoning_text.delta") => {
                    push_text(&mut operations, primitive(source.get("delta")), true)?
                }
                Some("response.completed" | "response.incomplete") => {
                    let response = source.get("response");
                    if let Some(block) = response
                        .and_then(|value| value.get("usage"))
                        .filter(|value| value.is_object())
                    {
                        if let Some(usage) = parse_usage(block) {
                            push(&mut operations, Operation::Usage(Some(usage)))?;
                        }
                        push(
                            &mut operations,
                            Operation::MergeUsageReport(parse_usage_report(block)),
                        )?;
                    }
                    push(
                        &mut operations,
                        Operation::ResponseFinish(primitive(
                            response
                                .and_then(|value| value.get("incomplete_details"))
                                .and_then(|value| value.get("reason")),
                        )),
                    )?;
                }
                _ => (),
            },
        }
        Ok(())
    })();
    FrameBatch {
        operations: operations.operations,
        failure: result.err(),
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DeltaKind {
    Text,
    Thinking,
}
#[derive(Debug)]
pub struct Delta<'a> {
    pub kind: DeltaKind,
    pub text: &'a str,
}
#[derive(Default)]
struct BuildingCall {
    id: String,
    name: String,
    arguments: String,
}
#[derive(Default)]
pub struct TurnAccumulator {
    text: String,
    reasoning: String,
    calls: BTreeMap<i32, BuildingCall>,
    items: HashMap<String, i32>,
    units: usize,
    item_units: usize,
    finish_reason: Option<String>,
    usage: Option<Usage>,
    usage_report: UsageReport,
}
impl TurnAccumulator {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn has_tool_calls(&self) -> bool {
        self.calls.values().any(|call| !blank(&call.name))
    }
    pub fn finish_reason(&self) -> Option<&str> {
        self.finish_reason.as_deref()
    }
    pub fn usage(&self) -> Option<Usage> {
        self.usage
    }
    pub fn usage_report(&self) -> UsageReport {
        self.usage_report
    }
    pub fn text(&self) -> &str {
        &self.text
    }
    pub fn reasoning(&self) -> &str {
        &self.reasoning
    }
    fn reserve(&mut self, count: usize) -> Result<(), ProtocolError> {
        if count > MAX_TURN_UNITS - self.units {
            return Err(ProtocolError::TurnLimit);
        }
        self.units += count;
        Ok(())
    }
    fn append_call(
        &mut self,
        index: i32,
        id: Option<String>,
        name: Option<String>,
        arguments: Option<String>,
    ) -> Result<(), ProtocolError> {
        if !self.calls.contains_key(&index) && self.calls.len() == MAX_CALLS {
            return Err(ProtocolError::CallLimit);
        }
        let count = [&id, &name, &arguments]
            .into_iter()
            .flatten()
            .map(|value| value.encode_utf16().count())
            .sum();
        self.reserve(count)?;
        let call = self.calls.entry(index).or_default();
        if let Some(id) = id.filter(|value| !blank(value)) {
            call.id = id;
        }
        if let Some(name) = name.filter(|value| !blank(value)) {
            call.name = name;
        }
        if let Some(arguments) = arguments {
            call.arguments.push_str(&arguments);
        }
        Ok(())
    }
    pub fn accept<F>(
        &mut self,
        protocol: Protocol,
        source: &Value,
        mut emit: F,
    ) -> Result<(), ProtocolError>
    where
        F: FnMut(Delta<'_>) -> Result<(), ProtocolError>,
    {
        let batch = normalize_frame(protocol, source);
        for operation in batch.operations {
            match operation {
                Operation::Text(text) => {
                    self.reserve(text.encode_utf16().count())?;
                    self.text.push_str(&text);
                    emit(Delta {
                        kind: DeltaKind::Text,
                        text: &text,
                    })?;
                }
                Operation::Thinking(text) => {
                    self.reserve(text.encode_utf16().count())?;
                    self.reasoning.push_str(&text);
                    emit(Delta {
                        kind: DeltaKind::Thinking,
                        text: &text,
                    })?;
                }
                Operation::Call {
                    index,
                    id,
                    name,
                    arguments,
                } => self.append_call(index, id, name, arguments)?,
                Operation::ItemCall {
                    item,
                    id,
                    name,
                    arguments,
                } => {
                    let index = if let Some(&index) = self.items.get(&item) {
                        index
                    } else {
                        let units = item.encode_utf16().count();
                        if self.items.len() >= MAX_CALLS {
                            return Err(ProtocolError::CallLimit);
                        }
                        if units > MAX_TURN_UNITS - self.item_units {
                            return Err(ProtocolError::TurnLimit);
                        }
                        self.item_units += units;
                        let index = self.calls.len() as i32;
                        self.items.insert(item, index);
                        index
                    };
                    self.append_call(index, id, name, arguments)?;
                }
                Operation::Finish(reason) => {
                    self.reserve(reason.encode_utf16().count())?;
                    self.finish_reason = Some(reason);
                }
                Operation::NormalizeFinish(reason) => {
                    self.reserve(reason.encode_utf16().count())?;
                    self.finish_reason = Some(normalized_finish(reason))
                }
                Operation::StopIfUnset => {
                    if self.finish_reason.is_none() {
                        self.finish_reason = Some("stop".into());
                    }
                }
                Operation::ResponseFinish(reason) => {
                    self.reserve(
                        reason
                            .as_ref()
                            .map_or(0, |value| value.encode_utf16().count()),
                    )?;
                    self.finish_reason = Some(reason.map(normalized_finish).unwrap_or_else(|| {
                        if self.has_tool_calls() {
                            "tool_calls".into()
                        } else {
                            "stop".into()
                        }
                    }))
                }
                Operation::Usage(usage) => self.usage = usage,
                Operation::MergeUsage(usage) => {
                    if let Some(usage) = usage {
                        self.usage = Some(
                            self.usage
                                .map(|old| old.merge_positive(usage))
                                .unwrap_or(usage),
                        );
                    }
                }
                Operation::UsageReport(report) => self.usage_report = report,
                Operation::MergeUsageReport(report) => {
                    self.usage_report = self.usage_report.merge(report)
                }
            }
        }
        if let Some(failure) = batch.failure {
            return Err(failure);
        }
        Ok(())
    }
    pub fn snapshot(&self) -> Turn {
        let mut calls = Vec::new();
        for call in self.calls.values().filter(|call| !blank(&call.name)) {
            calls.push(ToolCall {
                id: if blank(&call.id) {
                    format!("call_{}", calls.len())
                } else {
                    call.id.clone()
                },
                name: call.name.clone(),
                arguments: if blank(&call.arguments) {
                    "{}".into()
                } else {
                    call.arguments.clone()
                },
            });
        }
        Turn {
            text: self.text.clone(),
            reasoning: self.reasoning.clone(),
            tool_calls: calls,
            finish_reason: self.finish_reason.clone(),
            usage: self.usage,
            usage_report: self.usage_report,
        }
    }
}

fn take_units(value: &str, max: usize) -> String {
    let mut units = 0;
    value
        .chars()
        .take_while(|value| {
            units += value.len_utf16();
            units <= max
        })
        .collect()
}
pub fn extract_api_error(body: &str) -> String {
    let body = trimmed(body);
    if body.is_empty() {
        return String::new();
    }
    if body.len() <= MAX_FRAME_BYTES
        && let Ok(Value::Object(parsed)) = serde_json::from_str::<Value>(body)
    {
        let detail = match parsed.get("error") {
            Some(Value::Object(error)) => {
                primitive(error.get("message")).filter(|value| !blank(value))
            }
            value => primitive(value),
        }
        .or_else(|| primitive(parsed.get("message")).filter(|value| !blank(value)))
        .or_else(|| primitive(parsed.get("detail")).filter(|value| !blank(value)))
        .or_else(|| primitive(parsed.get("msg")).filter(|value| !blank(value)));
        if let Some(detail) = detail.filter(|value| !blank(value)) {
            return take_units(&detail, 300);
        }
    }
    let mut result = String::new();
    let mut space = false;
    for character in take_units(body, 200).chars() {
        if matches!(
            character,
            ' ' | '\t' | '\n' | '\r' | '\u{000b}' | '\u{000c}'
        ) {
            if !space {
                result.push(' ');
            }
            space = true;
        } else {
            result.push(character);
            space = false;
        }
    }
    result
}
pub fn friendly_http(code: u16, body: &str) -> String {
    let hint = match code {
        400 => "服务器说请求格式不对",
        401 => "API Key 不对或已失效",
        403 => "服务器拒绝了这个 Key（可能是地区或权限限制）",
        404 => "路径不对：接口地址要以 /v1 结尾，而不是完整的 /chat/completions",
        408 => "服务器等超时了",
        413 => "请求体太大（多半是截图太多）",
        429 => "请求太频繁或额度用完，稍后再试",
        500..=599 => "服务器内部出错",
        _ => "请求失败",
    };
    let detail = extract_api_error(body);
    format!(
        "{hint}（HTTP {code}）{}",
        if blank(&detail) {
            String::new()
        } else {
            format!("：{detail}")
        }
    )
}
pub fn retry_after(header: Option<&str>) -> Option<u64> {
    trimmed(header?)
        .parse::<i32>()
        .ok()
        .filter(|&value| value >= 0)
        .map(|value| value.clamp(1, 60) as u64)
}
pub fn should_retry(
    status: Option<u16>,
    attempt: u32,
    max_retries: u32,
    received: bool,
    cancelled: bool,
    mid_stream: bool,
) -> bool {
    !cancelled
        && (!received || mid_stream)
        && attempt <= max_retries
        && status.is_none_or(|status| status == 429 || (500..=599).contains(&status))
}

const MAX_SSE_LINE_BYTES: usize = 3 * 1024 * 1024 + 16;
const MAX_SSE_FRAME_UNITS: usize = 1024 * 1024;

/// Successfully decoded events precede a later failure from the same network chunk.
/// Apply `events` in order before returning `failure` to the retry/controller layer.
#[derive(Debug, Default)]
pub struct EventBatch {
    pub events: Vec<Value>,
    pub failure: Option<ProtocolError>,
    pub done: bool,
}

#[derive(Default)]
struct JsonFrame {
    depth: i32,
    in_string: bool,
    escaped: bool,
}
impl JsonFrame {
    fn feed(&mut self, data: &str) -> Result<bool, ProtocolError> {
        for byte in data.bytes() {
            if self.escaped {
                self.escaped = false;
            } else if self.in_string && byte == b'\\' {
                self.escaped = true;
            } else if byte == b'"' {
                self.in_string = !self.in_string;
            } else if !self.in_string {
                match byte {
                    b'{' | b'[' => self.depth += 1,
                    b'}' | b']' => self.depth -= 1,
                    _ => (),
                }
                if self.depth as usize > MAX_DOCUMENT_DEPTH && self.depth > 0 {
                    return Err(ProtocolError::InvalidDocument(
                        "SSE JSON 超过嵌套深度上限".into(),
                    ));
                }
            }
        }
        Ok(self.depth <= 0 && !self.in_string)
    }
}

/// Rust-owned byte framing. UTF-8 is decoded only after a physical line completes, so
/// split multibyte characters and a split BOM survive arbitrary socket chunk boundaries.
/// Complete JSON data lines are delivered immediately; gateways splitting a JSON object
/// over several data lines are joined with LF until its brackets/string are balanced.
#[derive(Default)]
pub struct EventDecoder {
    line: Vec<u8>,
    pending: String,
    pending_units: usize,
    frame: JsonFrame,
    first_line_seen: bool,
    skip_lf: bool,
    done: bool,
    saw_done: bool,
    failed: bool,
}
impl EventDecoder {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn is_done(&self) -> bool {
        self.done
    }
    /// Unlike `is_done`, this becomes true only for the server's `[DONE]` sentinel.
    pub fn saw_done(&self) -> bool {
        self.saw_done
    }
    fn stop_with(&mut self, batch: &mut EventBatch, failure: ProtocolError) {
        self.line.clear();
        self.pending.clear();
        self.pending_units = 0;
        self.frame = JsonFrame::default();
        self.failed = true;
        batch.failure = Some(failure);
    }
    fn flush(&mut self) -> Result<Option<Value>, ProtocolError> {
        if self.pending.is_empty() {
            return Ok(None);
        }
        let payload = std::mem::take(&mut self.pending);
        self.pending_units = 0;
        self.frame = JsonFrame::default();
        let value: Value = serde_json::from_str(&payload).map_err(|failure| {
            ProtocolError::InvalidDocument(format!("SSE 返回的不是完整 JSON：{failure}"))
        })?;
        if !value.is_object() {
            return Err(ProtocolError::InvalidDocument(
                "SSE JSON 事件必须是对象".into(),
            ));
        }
        bounded_document(&value, MAX_FRAME_BYTES)?;
        Ok(Some(value))
    }
    fn accept_line(&mut self) -> Result<Option<Value>, ProtocolError> {
        let mut bytes = std::mem::take(&mut self.line);
        let result = {
            let content = if !self.first_line_seen {
                self.first_line_seen = true;
                bytes.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(&bytes)
            } else {
                &bytes
            };
            let decoded = String::from_utf8_lossy(content);
            self.accept_text(&decoded)
        };
        bytes.clear();
        self.line = bytes;
        result
    }
    fn accept_text(&mut self, line: &str) -> Result<Option<Value>, ProtocolError> {
        if line.is_empty() {
            return self.flush();
        }
        let Some(data) = line
            .strip_prefix("data:")
            .or_else(|| (line == "data").then_some(""))
        else {
            return Ok(None);
        };
        let data = data.strip_prefix(' ').unwrap_or(data);
        if data.is_empty() {
            return Ok(None);
        }
        let units = data.encode_utf16().count();
        let separator = usize::from(!self.pending.is_empty());
        if units + separator > MAX_SSE_FRAME_UNITS.saturating_sub(self.pending_units) {
            return Err(ProtocolError::InvalidDocument(
                "SSE 单帧超过 1 Mi UTF-16 单位上限".into(),
            ));
        }
        if trimmed(data) == "[DONE]" {
            let event = self.flush()?;
            self.done = true;
            self.saw_done = true;
            return Ok(event);
        }
        if separator != 0 {
            self.pending.push('\n');
            self.frame.feed("\n")?;
        }
        self.pending.push_str(data);
        self.pending_units += units + separator;
        if self.frame.feed(data)? {
            self.flush()
        } else {
            Ok(None)
        }
    }
    /// Feed the next socket chunk. After `done` or a terminal failure, further input is ignored.
    pub fn feed(&mut self, bytes: &[u8]) -> EventBatch {
        let mut batch = EventBatch {
            done: self.done,
            ..EventBatch::default()
        };
        if self.done || self.failed {
            return batch;
        }
        let mut event_bytes = 0usize;
        for &byte in bytes {
            if self.skip_lf {
                self.skip_lf = false;
                if byte == b'\n' {
                    continue;
                }
            }
            if matches!(byte, b'\r' | b'\n') {
                self.skip_lf = byte == b'\r';
                match self.accept_line() {
                    Ok(Some(event)) => {
                        let mut budget =
                            DocumentBudget::new(MAX_OPERATION_BYTES.saturating_sub(event_bytes));
                        if batch.events.len() >= MAX_FRAME_OPERATIONS {
                            self.stop_with(
                                &mut batch,
                                ProtocolError::InvalidDocument(
                                    "单次 SSE 输入超过事件数量上限".into(),
                                ),
                            );
                            break;
                        }
                        if let Err(failure) = budget.value(&event, 0) {
                            self.stop_with(&mut batch, failure);
                            break;
                        }
                        event_bytes += budget.bytes;
                        batch.events.push(event);
                    }
                    Ok(None) => (),
                    Err(failure) => {
                        self.stop_with(&mut batch, failure);
                        break;
                    }
                }
                if self.done {
                    break;
                }
            } else {
                if self.line.len() >= MAX_SSE_LINE_BYTES {
                    self.stop_with(
                        &mut batch,
                        ProtocolError::InvalidDocument("SSE 物理行超过字节上限".into()),
                    );
                    break;
                }
                self.line.push(byte);
            }
        }
        batch.done = self.done;
        batch
    }
    /// Finish at transport EOF. A final line need not end in CR/LF; an incomplete pending
    /// JSON reports a failure after any preceding completed event.
    pub fn finish(&mut self) -> EventBatch {
        let mut batch = EventBatch {
            done: self.done,
            ..EventBatch::default()
        };
        if self.done || self.failed {
            return batch;
        }
        if !self.line.is_empty() {
            match self.accept_line() {
                Ok(Some(event)) => batch.events.push(event),
                Ok(None) => (),
                Err(failure) => {
                    self.stop_with(&mut batch, failure);
                    return batch;
                }
            }
        }
        if !self.done {
            match self.flush() {
                Ok(Some(event)) => batch.events.push(event),
                Ok(None) => (),
                Err(failure) => {
                    self.stop_with(&mut batch, failure);
                    return batch;
                }
            }
        }
        self.done = true;
        batch.done = true;
        batch
    }
}

// Tool-result image notes follow the complete contiguous result batch. This
// reuses the existing user image encoders, without inventing persisted user turns.
mod image_media;
fn tool_image_note(message: &Message, responses: bool) -> Value {
    let mut content = vec![json!({"type":if responses {"input_text"} else {"text"},
        "text":format!("Image returned by tool call {}.",message.tool_call_id.as_deref().unwrap_or(""))})];
    for image in &message.images_base64 {
        let url = format!(
            "data:{};base64,{image}",
            image_media::media_type(image).unwrap_or("image/jpeg")
        );
        content.push(if responses {
            json!({"type":"input_image","image_url":url})
        } else {
            json!({"type":"image_url","image_url":{"url":url}})
        });
    }
    json!({"role":"user","content":content})
}
fn chat_items(messages: &[Message]) -> Vec<Value> {
    let mut result = Vec::new();
    let mut notes = Vec::new();
    for message in messages {
        if message.role != "tool" {
            result.append(&mut notes);
        }
        result.push(chat_message(message));
        if message.role == "tool" && !message.images_base64.is_empty() {
            notes.push(tool_image_note(message, false));
        }
    }
    result.append(&mut notes);
    result
}
fn response_batch_items(messages: &[Message]) -> Vec<Value> {
    let mut result = Vec::new();
    let mut notes = Vec::new();
    for message in messages.iter().filter(|message| message.role != "system") {
        if message.role != "tool" {
            result.append(&mut notes);
        }
        result.extend(response_items(message));
        if message.role == "tool" && !message.images_base64.is_empty() {
            notes.push(tool_image_note(message, true));
        }
    }
    result.append(&mut notes);
    result
}

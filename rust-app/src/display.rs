//! Bounded projections for presentation. No display window is a writable transcript.
use crate::{
    chat::FileActivityKind,
    state::{Conversation, Message},
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fmt,
    io::Write,
};
use suixing_search::data_plan;
pub mod turn_navigation;

pub const MAX_ROWS: usize = 131_072;
pub const MAX_WINDOW_ROWS: usize = 96;
pub const MAX_WINDOW_BYTES: usize = 1024 * 1024;
pub const PREVIEW_BYTES: usize = 2048;
pub const MAX_BODY_PAGE_BYTES: usize = 64 * 1024;
pub const MAX_BODY_TEXT_BYTES: usize = 20 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ViewSource {
    Main {
        conversation_id: String,
    },
    Child {
        conversation_id: String,
        parent_run_id: String,
        child_id: String,
    },
}
impl ViewSource {
    pub fn estimated_bytes(&self) -> usize {
        128 + 6 * match self {
            Self::Main { conversation_id } => conversation_id.len(),
            Self::Child {
                conversation_id,
                parent_run_id,
                child_id,
            } => conversation_id.len() + parent_run_id.len() + child_id.len(),
        }
    }
    pub fn conversation_id(&self) -> &str {
        match self {
            Self::Main { conversation_id }
            | Self::Child {
                conversation_id, ..
            } => conversation_id,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct SourceStamp {
    /// Identity of one pinned committed manifest or actor snapshot, not a filesystem mtime.
    pub generation: String,
    /// Monotonic owning actor revision; never derive this counter from a display page.
    pub revision: u64,
    /// Changes when choices or destructive edits change the selected row sequence.
    pub projection_revision: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DisplayError {
    Cancelled,
    Stale,
    Missing,
    Budget,
    Invalid(String),
}
impl fmt::Display for DisplayError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Cancelled => "历史读取已取消",
            Self::Stale => "历史来源已变化，请从阅读锚点重新读取",
            Self::Missing => "历史消息已不存在",
            Self::Budget => "历史显示请求超过预算",
            Self::Invalid(text) => text,
        })
    }
}
impl std::error::Error for DisplayError {}
fn check(cancelled: &impl Fn() -> bool) -> Result<(), DisplayError> {
    if cancelled() {
        Err(DisplayError::Cancelled)
    } else {
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum BodyKind {
    Text,
    Thinking,
    ToolOutput,
    ToolArguments(u16),
    ToolMetadataArguments,
    FileActivityDescription(u16),
    TraceNote,
    RowHeader,
    FooterHeader,
    ImageBlock(u16),
}
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct BodyRef {
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub message_id: String,
    /// Canonical message fingerprint. Body requests verify it, including messages
    /// outside the current display window. Actor publishers cache it per changed row.
    pub message_hash: String,
    pub kind: BodyKind,
    pub bytes: u64,
}
impl BodyRef {
    pub fn estimated_bytes(&self) -> usize {
        256 + self.source.estimated_bytes()
            + 6 * (self.stamp.generation.len() + self.message_id.len() + self.message_hash.len())
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ImageRef {
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub message_id: String,
    pub message_hash: String,
    pub image_index: u16,
}
impl ImageRef {
    pub fn estimated_bytes(&self) -> usize {
        256 + self.source.estimated_bytes()
            + 6 * (self.stamp.generation.len() + self.message_id.len() + self.message_hash.len())
    }
}
#[derive(Clone, Debug)]
pub struct ImageRequest {
    pub request_id: String,
    pub image: ImageRef,
    pub max_side: u32,
}
#[derive(Clone, Debug)]
pub struct BodyPageRequest {
    pub request_id: String,
    pub body: BodyRef,
    pub from_byte: u64,
    pub max_bytes: usize,
}
#[derive(Clone, Debug)]
pub struct BodyPage {
    pub request_id: String,
    pub body: BodyRef,
    pub from_byte: u64,
    pub to_byte: u64,
    pub text: String,
    pub previous_byte: Option<u64>,
    pub next_byte: Option<u64>,
}
impl BodyPage {
    pub fn estimated_bytes(&self) -> usize {
        256 + 6 * (self.request_id.len() + self.text.len()) + self.body.estimated_bytes()
    }
}

impl<'de> Deserialize<'de> for TextMetric {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Visitor;
        impl<'de> serde::de::Visitor<'de> for Visitor {
            type Value = TextMetric;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("a string or null")
            }
            fn visit_str<E: serde::de::Error>(self, text: &str) -> Result<Self::Value, E> {
                Ok(TextMetric::from_text(text))
            }
            fn visit_string<E: serde::de::Error>(self, text: String) -> Result<Self::Value, E> {
                self.visit_str(&text)
            }
            fn visit_none<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(TextMetric::default())
            }
            fn visit_unit<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(TextMetric::default())
            }
        }
        deserializer.deserialize_any(Visitor)
    }
}
#[derive(Clone, Debug, Default)]
pub struct TextMetric {
    pub bytes: u64,
    pub utf16_units: usize,
    pub non_blank: bool,
    pub present: bool,
}
impl TextMetric {
    fn from_text(text: &str) -> Self {
        Self {
            bytes: text.len() as u64,
            utf16_units: if text.is_ascii() {
                text.len()
            } else {
                text.encode_utf16().count()
            },
            non_blank: !text.trim().is_empty(),
            present: true,
        }
    }
    fn from_optional_text(text: Option<&str>) -> Self {
        text.map_or_else(Self::default, Self::from_text)
    }
}
#[derive(Clone, Debug, Deserialize)]
pub struct CallHeader {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub arguments: TextMetric,
}
#[derive(Clone, Debug, Default)]
pub struct Count(pub usize);
impl<'de> Deserialize<'de> for Count {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Visitor;
        impl<'de> serde::de::Visitor<'de> for Visitor {
            type Value = Count;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("a bounded array")
            }
            fn visit_seq<A: serde::de::SeqAccess<'de>>(
                self,
                mut access: A,
            ) -> Result<Count, A::Error> {
                let mut count = 0usize;
                while access.next_element::<serde::de::IgnoredAny>()?.is_some() {
                    count += 1;
                    if count > 4096 {
                        return Err(serde::de::Error::custom("array header budget"));
                    }
                }
                Ok(Count(count))
            }
        }
        deserializer.deserialize_seq(Visitor)
    }
}
/// Deserialization ignores large payload values rather than retaining their text.
/// One source line is still bounded by the archive's 20 MiB message budget.
#[derive(Clone, Debug, Deserialize)]
pub struct MessageHeader {
    pub id: String,
    #[serde(default, rename = "rustLegacyOriginalMessageId")]
    pub legacy_original_id: Option<String>,
    pub role: String,
    pub complete: bool,
    #[serde(default)]
    pub text: TextMetric,
    #[serde(default)]
    pub thinking: TextMetric,
    #[serde(default, alias = "toolOutput")]
    pub tool_output: TextMetric,
    #[serde(default, alias = "toolArgs")]
    pub tool_args: TextMetric,
    #[serde(default, alias = "runId")]
    pub run_id: Option<String>,
    #[serde(default, alias = "toolName")]
    pub tool_name: Option<String>,
    #[serde(default)]
    pub steer: bool,
    #[serde(default)]
    pub tool_calls: Vec<CallHeader>,
    #[serde(default)]
    pub tool_call_id: Option<String>,
    #[serde(default, alias = "imagesBase64")]
    pub images_base64: Count,
    #[serde(default, alias = "imageBase64")]
    pub image_base64: TextMetric,
    #[serde(default, alias = "fileActivities")]
    pub file_activities: Count,
    #[serde(default, alias = "toolOk")]
    pub tool_ok: Option<bool>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default, alias = "promptTokens")]
    pub prompt_tokens: Option<i64>,
    #[serde(default, alias = "completionTokens")]
    pub completion_tokens: Option<i64>,
    #[serde(default, alias = "cachedTokens")]
    pub cached_tokens: Option<i64>,
    #[serde(default, alias = "elapsedMs")]
    pub elapsed_ms: i64,
    #[serde(default, alias = "runElapsedMs")]
    pub run_elapsed_ms: Option<i64>,
    #[serde(default, alias = "firstTokenMs")]
    pub first_token_ms: i64,
    #[serde(default)]
    pub edited: bool,
    #[serde(default)]
    pub timestamp: i64,
    #[serde(
        default,
        rename = "rustFailedModelAttempt",
        deserialize_with = "failed_attempt_flag"
    )]
    pub failed_model_attempt: bool,
    #[serde(default, alias = "traceNote")]
    pub trace_note: TextMetric,
    #[serde(skip)]
    pub message_hash: String,
}
impl MessageHeader {
    pub fn from_message(message: &Message, message_hash: String) -> Self {
        Self {
            id: message.id.clone(),
            legacy_original_id: message.metadata.legacy_original_id().map(str::to_owned),
            role: message.role.clone(),
            complete: message.complete,
            text: TextMetric::from_text(&message.text),
            thinking: TextMetric::from_text(&message.thinking),
            tool_output: TextMetric::from_optional_text(message.metadata.tool_output.as_deref()),
            tool_args: TextMetric::from_optional_text(message.metadata.tool_args.as_deref()),
            run_id: message.metadata.run_id.clone(),
            tool_name: message.metadata.tool_name.clone(),
            steer: message.metadata.steer,
            tool_calls: message
                .tool_calls
                .iter()
                .map(|call| CallHeader {
                    id: call.id.clone(),
                    name: call.name.clone(),
                    arguments: TextMetric::from_text(&call.arguments),
                })
                .collect(),
            tool_call_id: message.tool_call_id.clone(),
            images_base64: Count(message.metadata.images_base64.len()),
            image_base64: TextMetric::from_optional_text(message.metadata.image_base64.as_deref()),
            file_activities: Count(message.metadata.file_activities.len()),
            tool_ok: message.metadata.tool_ok,
            model: message.metadata.model.clone(),
            prompt_tokens: message.metadata.prompt_tokens,
            completion_tokens: message.metadata.completion_tokens,
            cached_tokens: message.metadata.cached_tokens,
            elapsed_ms: message.metadata.elapsed_ms,
            run_elapsed_ms: message
                .metadata
                .extra
                .get("runElapsedMs")
                .and_then(serde_json::Value::as_i64),
            first_token_ms: message.metadata.first_token_ms,
            edited: message.metadata.edited,
            timestamp: message.metadata.timestamp,
            failed_model_attempt: crate::agent::retry_runtime::failed(message),
            trace_note: TextMetric::from_optional_text(message.metadata.trace_note.as_deref()),
            message_hash,
        }
    }
    pub fn image_count(&self) -> usize {
        if self.images_base64.0 > 0 {
            self.images_base64.0
        } else {
            usize::from(self.image_base64.bytes > 0)
        }
    }
}
#[derive(Clone, Debug)]
pub struct DisplayCall {
    pub id: String,
    pub name: String,
    pub arguments: BodyRef,
}
#[derive(Clone, Debug)]
pub struct DisplayRow {
    pub id: String,
    pub source_index: u32,
    pub role: String,
    pub complete: bool,
    pub steer: bool,
    pub turn_id: Option<String>,
    pub run_id: Option<String>,
    pub tool_name: Option<String>,
    pub tool_call_id: Option<String>,
    pub text: BodyRef,
    pub thinking: BodyRef,
    pub tool_output: Option<BodyRef>,
    pub tool_arguments: Option<BodyRef>,
    pub tool_calls: Vec<DisplayCall>,
    pub preview: Option<BodyPage>,
    pub images: Vec<ImageRef>,
    pub file_activity_count: usize,
    /// The declarer identity is retained even if a page begins in a long turn.
    pub batch_id: Option<String>,
    pub info: DisplayInfo,
}
#[derive(Clone, Debug, Default)]
pub struct DisplayInfo {
    pub tool_ok: Option<bool>,
    pub model: Option<String>,
    pub prompt_tokens: Option<i64>,
    pub completion_tokens: Option<i64>,
    pub cached_tokens: Option<i64>,
    pub elapsed_ms: i64,
    pub first_token_ms: i64,
    pub edited: bool,
    pub timestamp: i64,
    pub failed_model_attempt: bool,
    pub timestamp_label: Option<String>,
    pub trace_note: Option<BodyRef>,
    pub reply: ReplyDetails,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ReplySummary {
    pub elapsed_ms: i64,
    pub thinking: usize,
    pub tools: usize,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ReplyDetails {
    pub summary: Option<ReplySummary>,
    pub footer: bool,
    pub run_elapsed_ms: i64,
}
impl DisplayRow {
    pub fn estimated_bytes(&self) -> usize {
        512 + self
            .info
            .timestamp_label
            .as_ref()
            .map_or(0, |label| label.len().saturating_mul(6))
            + 6 * (self.id.len()
                + self.role.len()
                + self.turn_id.as_ref().map_or(0, String::len)
                + self.run_id.as_ref().map_or(0, String::len)
                + self.tool_name.as_ref().map_or(0, String::len)
                + self.tool_call_id.as_ref().map_or(0, String::len)
                + self.batch_id.as_ref().map_or(0, String::len))
            + self.text.estimated_bytes()
            + self.thinking.estimated_bytes()
            + self
                .tool_output
                .as_ref()
                .map_or(0, BodyRef::estimated_bytes)
            + self
                .tool_arguments
                .as_ref()
                .map_or(0, BodyRef::estimated_bytes)
            + self
                .tool_calls
                .iter()
                .map(|call| {
                    128 + 6 * (call.id.len() + call.name.len()) + call.arguments.estimated_bytes()
                })
                .sum::<usize>()
            + self.preview.as_ref().map_or(0, BodyPage::estimated_bytes)
            + self
                .images
                .iter()
                .map(ImageRef::estimated_bytes)
                .sum::<usize>()
            + 256
            + 6 * self.info.model.as_ref().map_or(0, String::len)
            + self
                .info
                .trace_note
                .as_ref()
                .map_or(0, BodyRef::estimated_bytes)
    }
}
#[derive(Clone, Debug)]
pub struct CandidateNavigation {
    pub turn_id: String,
    pub selected_run: String,
    pub position: usize,
    pub total: usize,
    pub previous: Option<String>,
    pub next: Option<String>,
}
impl CandidateNavigation {
    pub fn estimated_bytes(&self) -> usize {
        256 + 6
            * (self.turn_id.len()
                + self.selected_run.len()
                + self.previous.as_ref().map_or(0, String::len)
                + self.next.as_ref().map_or(0, String::len))
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WindowCursor {
    pub stamp: SourceStamp,
    pub boundary_id: String,
}
#[derive(Clone, Debug)]
pub enum WindowAt {
    Tail,
    Before(WindowCursor),
    After(WindowCursor),
    Around { message_id: String },
    Turn(turn_navigation::TurnTarget),
}
#[derive(Clone, Debug)]
pub struct WindowRequest {
    pub request_id: String,
    pub source: ViewSource,
    pub at: WindowAt,
    pub max_rows: usize,
    pub previews: bool,
}
#[derive(Clone, Debug)]
pub struct DisplayWindow {
    pub request_id: String,
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub rows: Vec<DisplayRow>,
    pub total_source_rows: usize,
    pub total_selected_rows: usize,
    pub before: Option<WindowCursor>,
    pub after: Option<WindowCursor>,
    pub candidates: Vec<CandidateNavigation>,
    pub anchor_found: bool,
}
impl DisplayWindow {
    pub fn estimated_bytes(&self) -> usize {
        2048 + self.source.estimated_bytes()
            + 6 * (self.request_id.len() + self.stamp.generation.len())
            + self
                .rows
                .iter()
                .map(DisplayRow::estimated_bytes)
                .sum::<usize>()
            + self
                .candidates
                .iter()
                .map(CandidateNavigation::estimated_bytes)
                .sum::<usize>()
            + self.before.as_ref().map_or(0, |cursor| {
                128 + 6 * (cursor.boundary_id.len() + cursor.stamp.generation.len())
            })
            + self.after.as_ref().map_or(0, |cursor| {
                128 + 6 * (cursor.boundary_id.len() + cursor.stamp.generation.len())
            })
    }
}

pub trait HistorySource {
    fn source(&self) -> &ViewSource;
    fn stamp(&self) -> &SourceStamp;
    fn headers(&self) -> &[MessageHeader];
    /// Read from the snapshot that produced the owning header. A committed segment
    /// is verified before caching; its row fingerprint identifies the exact JSON line.
    /// Only the storage/actor owns the message. Never return it as a UI event.
    fn with_message<R>(
        &self,
        index: usize,
        cancelled: &impl Fn() -> bool,
        read: impl FnOnce(&Message) -> Result<R, DisplayError>,
    ) -> Result<R, DisplayError>;
}
pub struct MemoryHistory<'a> {
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub messages: &'a [Message],
    pub headers: &'a [MessageHeader],
}
impl HistorySource for MemoryHistory<'_> {
    fn source(&self) -> &ViewSource {
        &self.source
    }
    fn stamp(&self) -> &SourceStamp {
        &self.stamp
    }
    fn headers(&self) -> &[MessageHeader] {
        self.headers
    }
    fn with_message<R>(
        &self,
        index: usize,
        cancelled: &impl Fn() -> bool,
        read: impl FnOnce(&Message) -> Result<R, DisplayError>,
    ) -> Result<R, DisplayError> {
        check(cancelled)?;
        let message = self.messages.get(index).ok_or(DisplayError::Missing)?;
        if message_fingerprint(message)?
            != self
                .headers
                .get(index)
                .ok_or(DisplayError::Stale)?
                .message_hash
        {
            return Err(DisplayError::Stale);
        }
        check(cancelled)?;
        read(message)
    }
}
/// Construct this once per topology/choice update; append/live updates can reuse
/// unchanged metadata and message fingerprints instead of rescanning 512 MiB bodies.
pub struct ProjectionIndex {
    selected: Vec<usize>,
    turns: Vec<Option<String>>,
    batch: Vec<Option<String>>,
    boundaries: Vec<bool>,
    candidates: Vec<CandidateNavigation>,
    ids: HashMap<String, usize>,
    turn_index: turn_navigation::TurnIndex,
    reply_details: HashMap<usize, ReplyDetails>,
}
impl ProjectionIndex {
    pub fn new(
        headers: &[MessageHeader],
        choices: &BTreeMap<String, String>,
        pending: Option<&str>,
        cancelled: impl Fn() -> bool,
    ) -> Result<Self, DisplayError> {
        if headers.len() > MAX_ROWS {
            return Err(DisplayError::Budget);
        }
        let mut ids = HashMap::new();
        for (index, header) in headers.iter().enumerate() {
            check(&cancelled)?;
            if header.id.is_empty() || ids.insert(header.id.clone(), index).is_some() {
                return Err(DisplayError::Invalid("历史身份为空或重复".into()));
            }
        }
        let metadata: Vec<_> = headers
            .iter()
            .map(|header| data_plan::TranscriptRow {
                id: &header.id,
                is_user: header.role == "user",
                steer: header.steer,
                run_id: header.run_id.as_deref(),
            })
            .collect();
        let projection = data_plan::project_transcript(&metadata, choices, pending, &cancelled)
            .map_err(|error| DisplayError::Invalid(error.to_string()))?;
        let chosen: HashMap<_, _> = projection.chosen.iter().copied().collect();
        let candidates = projection
            .candidates
            .iter()
            .map(|group| {
                let chosen = chosen.get(group.turn_id).copied().unwrap_or("");
                let position = group
                    .runs
                    .iter()
                    .position(|run| *run == chosen)
                    .unwrap_or(group.runs.len());
                CandidateNavigation {
                    turn_id: group.turn_id.into(),
                    selected_run: chosen.into(),
                    position,
                    total: group.runs.len(),
                    previous: position
                        .checked_sub(1)
                        .and_then(|index| group.runs.get(index))
                        .map(|run| (*run).into()),
                    next: group.runs.get(position + 1).map(|run| (*run).into()),
                }
            })
            .collect();
        let turns: Vec<_> = projection
            .turn_of
            .iter()
            .map(|turn| turn.map(str::to_owned))
            .collect();
        let selected = projection.visible_indices;
        let mut boundaries = vec![true];
        let mut batch = vec![None; headers.len()];
        let mut pending_calls = HashSet::new();
        let mut current = None;
        for &index in &selected {
            check(&cancelled)?;
            let header = &headers[index];
            if header.image_count() > 256 {
                return Err(DisplayError::Budget);
            }
            if header.role != "tool" && !pending_calls.is_empty() {
                return Err(DisplayError::Invalid(
                    "选中历史含未确认工具批次，请先恢复".into(),
                ));
            }
            if !header.tool_calls.is_empty() {
                if header.role != "assistant" || header.tool_calls.len() > 16 {
                    return Err(DisplayError::Invalid("工具声明结构无效".into()));
                }
                current = Some(header.id.clone());
                for call in &header.tool_calls {
                    if call.id.is_empty() || !pending_calls.insert(call.id.as_str()) {
                        return Err(DisplayError::Invalid("工具声明身份重复".into()));
                    }
                }
            }
            batch[index] = current.clone();
            if header.role == "tool" {
                let id = header
                    .tool_call_id
                    .as_deref()
                    .ok_or_else(|| DisplayError::Invalid("工具结果身份缺失".into()))?;
                if !pending_calls.remove(id) {
                    return Err(DisplayError::Invalid("工具结果没有对应声明".into()));
                }
            }
            boundaries.push(pending_calls.is_empty());
            if pending_calls.is_empty() {
                current = None;
            }
        }
        // A live tail may contain only recorded declarations/partial results.
        // It remains one display atom; context separately requires confirmed results.
        if let Some(tail) = boundaries.last_mut() {
            *tail = true;
        }
        let turn_index = turn_navigation::TurnIndex::new(headers, &selected, &cancelled)?;
        let mut reply_details = HashMap::<usize, ReplyDetails>::new();
        // Summarize the selected turn before slicing display windows. A tool batch and
        // the final reply can appear on different pages.
        for turn in selected.chunk_by(|&left, &right| {
            turns[left] == turns[right]
                && (turns[left].is_some() || headers[left].run_id == headers[right].run_id)
        }) {
            let mut first = None;
            let mut last_reply = None;
            let mut summary = ReplySummary::default();
            let mut run_elapsed_ms = None;
            for &index in turn {
                check(&cancelled)?;
                let header = &headers[index];
                if header.role != "assistant" {
                    continue;
                }
                summary.thinking += usize::from(header.thinking.bytes > 0);
                summary.tools += header.tool_calls.len();
                summary.elapsed_ms = summary.elapsed_ms.saturating_add(header.elapsed_ms.max(0));
                if let Some(elapsed) = header.run_elapsed_ms.filter(|elapsed| *elapsed > 0) {
                    run_elapsed_ms = Some(elapsed);
                }
                if first.is_none()
                    && (header.text.bytes > 0
                        || header.thinking.bytes > 0
                        || header.trace_note.bytes > 0
                        || !header.tool_calls.is_empty()
                        || header.prompt_tokens.unwrap_or(0) > 0
                        || header.completion_tokens.unwrap_or(0) > 0
                        || !header.complete)
                {
                    first = Some(index);
                }
                if header.complete && header.tool_calls.is_empty() {
                    last_reply = Some(index);
                }
            }
            if let Some(first) = first {
                summary.elapsed_ms = run_elapsed_ms.unwrap_or(summary.elapsed_ms);
                reply_details.entry(first).or_default().summary = Some(summary);
            }
            if let Some(last_reply) = last_reply {
                let details = reply_details.entry(last_reply).or_default();
                details.footer = true;
                if headers[last_reply]
                    .run_id
                    .as_deref()
                    .is_some_and(|run| !run.is_empty())
                {
                    details.run_elapsed_ms = summary.elapsed_ms;
                }
            }
        }
        Ok(Self {
            selected,
            turns,
            batch,
            boundaries,
            candidates,
            ids,
            turn_index,
            reply_details,
        })
    }
    pub fn selected_indices(&self) -> &[usize] {
        &self.selected
    }
    pub fn source_index(&self, id: &str) -> Option<usize> {
        self.ids.get(id).copied()
    }
    pub fn turn_page(
        &self,
        source: &impl HistorySource,
        request: &turn_navigation::TurnRequest,
        cancelled: impl Fn() -> bool,
    ) -> Result<turn_navigation::TurnPage, DisplayError> {
        self.turn_index.page(
            source,
            request,
            self.ids.get(&request.reading_message_id).copied(),
            &cancelled,
        )
    }
    /// The footer belongs to the final plain reply of the selected candidate.
    /// Aggregate known fields in storage callbacks, returning only one bounded
    /// page. Raw tool records, descriptions and unknown fields stay in storage.
    pub fn reply_footer(
        &self,
        source: &impl HistorySource,
        stamp: &SourceStamp,
        message_id: &str,
        hash: &str,
        from: usize,
        running: bool,
        cancelled: impl Fn() -> bool,
    ) -> Result<FooterPage, DisplayError> {
        check(&cancelled)?;
        if source.stamp() != stamp {
            return Err(DisplayError::Stale);
        }
        let reply = *self.ids.get(message_id).ok_or(DisplayError::Missing)?;
        let position = self
            .selected
            .binary_search(&reply)
            .map_err(|_| DisplayError::Missing)?;
        let header = source.headers().get(reply).ok_or(DisplayError::Stale)?;
        if header.message_hash != hash {
            return Err(DisplayError::Stale);
        }
        let empty = || FooterPage {
            source: source.source().clone(),
            stamp: stamp.clone(),
            message_hash: hash.into(),
            message_id: message_id.into(),
            from: 0,
            total: 0,
            activities: Vec::new(),
            next: None,
        };
        if running
            || !header.complete
            || header.role != "assistant"
            || !header.tool_calls.is_empty()
        {
            return Ok(empty());
        }
        let turn = &self.turns[reply];
        let run = header.run_id.as_deref().unwrap_or("");
        for &index in &self.selected[position + 1..] {
            check(&cancelled)?;
            if &self.turns[index] != turn {
                break;
            }
            let later = source.headers().get(index).ok_or(DisplayError::Stale)?;
            if later.role == "assistant"
                && later.tool_calls.is_empty()
                && later.run_id.as_deref().unwrap_or("") == run
            {
                return Ok(empty());
            }
        }
        source.with_message(reply, &cancelled, |_| Ok(()))?;
        let mut entries: Vec<Option<DisplayFileActivity>> = Vec::new();
        let mut paths: HashMap<String, usize> = HashMap::new();
        let mut bytes = 0usize;
        let first = (0..=position)
            .rev()
            .find(|&at| {
                let row = &source.headers()[self.selected[at]];
                row.role == "user" && !row.steer
            })
            .unwrap_or(0);
        for &index in &self.selected[first..=position] {
            check(&cancelled)?;
            let row = source.headers().get(index).ok_or(DisplayError::Stale)?;
            if row.file_activities.0 == 0
                || (row.role != "user" && row.run_id.as_deref().unwrap_or("") != run)
            {
                continue;
            }
            source.with_message(index, &cancelled, |message| {
                for slot in 0..message.metadata.file_activities.len() {
                    check(&cancelled)?;
                    let next = display_activity(source, message, slot, &row.message_hash)?;
                    let moved = if next.kind == FileActivityKind::Moved {
                        next.previous_path
                            .as_ref()
                            .and_then(|path| paths.remove(path))
                            .and_then(|at| entries[at].take())
                    } else {
                        None
                    };
                    if let Some(previous) = &moved {
                        bytes = bytes.saturating_sub(previous.estimated_bytes());
                    }
                    if let Some(&at) = paths.get(&next.path) {
                        let previous = entries[at].take().ok_or(DisplayError::Stale)?;
                        bytes = bytes.saturating_sub(previous.estimated_bytes());
                        let merged = merge_display_activity(&previous, &next);
                        bytes = bytes
                            .checked_add(merged.estimated_bytes())
                            .ok_or(DisplayError::Budget)?;
                        entries[at] = Some(merged);
                    } else {
                        let next = if let Some(previous) = moved {
                            merge_display_activity(&previous, &next)
                        } else {
                            next
                        };
                        bytes = bytes
                            .checked_add(next.estimated_bytes())
                            .ok_or(DisplayError::Budget)?;
                        paths.insert(next.path.clone(), entries.len());
                        entries.push(Some(next));
                    }
                    if bytes > 32 * 1024 * 1024 || entries.len() > MAX_ROWS {
                        return Err(DisplayError::Budget);
                    }
                }
                Ok(())
            })?;
        }
        let total = paths.len();
        if from > total {
            return Err(DisplayError::Invalid("文件活动页偏移无效".into()));
        }
        let mut page = empty();
        page.from = from;
        page.total = total;
        let mut weight = page.estimated_bytes();
        for activity in entries.into_iter().flatten().skip(from).take(64) {
            let added = activity.estimated_bytes();
            if weight + added > MAX_BODY_PAGE_BYTES {
                break;
            }
            weight += added;
            page.activities.push(activity);
        }
        let next = from + page.activities.len();
        if next == from && next < total {
            return Err(DisplayError::Budget);
        }
        page.next = (next < total).then_some(next);
        Ok(page)
    }
    pub fn window(
        &self,
        source: &impl HistorySource,
        request: &WindowRequest,
        cancelled: impl Fn() -> bool,
    ) -> Result<DisplayWindow, DisplayError> {
        check(&cancelled)?;
        if source.source() != &request.source {
            return Err(DisplayError::Invalid("窗口归属不匹配".into()));
        }
        let max = request.max_rows.clamp(1, MAX_WINDOW_ROWS);
        let count = self.selected.len();
        let locate = |id: &str| {
            self.ids
                .get(id)
                .and_then(|index| self.selected.binary_search(index).ok())
        };
        let mut anchor_found = true;
        let (mut start, mut end) = match &request.at {
            WindowAt::Tail => (count.saturating_sub(max), count),
            WindowAt::Before(cursor) => {
                if &cursor.stamp != source.stamp() {
                    return Err(DisplayError::Stale);
                }
                let end = locate(&cursor.boundary_id).ok_or(DisplayError::Stale)?;
                (end.saturating_sub(max), end)
            }
            WindowAt::After(cursor) => {
                if &cursor.stamp != source.stamp() {
                    return Err(DisplayError::Stale);
                }
                let start = locate(&cursor.boundary_id).ok_or(DisplayError::Stale)? + 1;
                (start, (start + max).min(count))
            }
            WindowAt::Around { message_id } => {
                let middle = locate(message_id).unwrap_or_else(|| {
                    anchor_found = false;
                    count.saturating_sub(1)
                });
                let start = middle.saturating_sub(max / 2);
                (start, (start + max).min(count))
            }
            WindowAt::Turn(target) => {
                self.turn_index.validate(source, target)?;
                let middle = locate(&target.message_id).ok_or(DisplayError::Stale)?;
                let start = middle.saturating_sub(max / 2);
                (start, (start + max).min(count))
            }
        };
        while start > 0 && !self.boundaries[start] {
            start -= 1;
        }
        while end < count && !self.boundaries[end] {
            end += 1;
        }
        let retained_anchor = match &request.at {
            WindowAt::Around { message_id } => {
                locate(message_id).unwrap_or(count.saturating_sub(1))
            }
            WindowAt::Turn(target) => locate(&target.message_id).ok_or(DisplayError::Stale)?,
            WindowAt::After(_) => start,
            _ => end.saturating_sub(1),
        };
        // Account with borrowed header strings before cloning refs/labels. Legal
        // source strings may be much larger than their display page budget.
        let header_weight = |index: usize| -> Result<usize, DisplayError> {
            let header = source.headers().get(index).ok_or(DisplayError::Stale)?;
            let reference = 256
                + request.source.estimated_bytes()
                + 6 * (source.stamp().generation.len()
                    + header.id.len()
                    + header.message_hash.len());
            Ok(512
                + 256
                + 6 * header.model.as_ref().map_or(0, String::len)
                + 6 * (header.id.len()
                    + header.role.len()
                    + self.turns[index].as_ref().map_or(0, String::len)
                    + header.run_id.as_ref().map_or(0, String::len)
                    + header.tool_name.as_ref().map_or(0, String::len)
                    + header.tool_call_id.as_ref().map_or(0, String::len)
                    + self.batch[index].as_ref().map_or(0, String::len))
                + reference
                    * (2 + usize::from(header.text.bytes == 0 && header.tool_output.bytes > 0)
                        + usize::from(header.tool_args.bytes > 0)
                        + usize::from(header.trace_note.bytes > 0)
                        + header.tool_calls.len()
                        + header.image_count())
                + header
                    .tool_calls
                    .iter()
                    .map(|call| 128 + 6 * (call.id.len() + call.name.len()))
                    .sum::<usize>())
        };
        loop {
            check(&cancelled)?;
            let mut weight = 2048
                + request.source.estimated_bytes()
                + 6 * (request.request_id.len() + source.stamp().generation.len());
            let mut visible_turns = HashSet::new();
            for &index in &self.selected[start..end] {
                weight = weight
                    .checked_add(header_weight(index)?)
                    .ok_or(DisplayError::Budget)?;
                if let Some(turn) = self.turns[index].as_deref() {
                    visible_turns.insert(turn);
                }
            }
            weight += self
                .candidates
                .iter()
                .filter(|candidate| visible_turns.contains(candidate.turn_id.as_str()))
                .map(CandidateNavigation::estimated_bytes)
                .sum::<usize>();
            if start > 0 {
                weight += 128
                    + 6 * (source.headers()[self.selected[start]].id.len()
                        + source.stamp().generation.len());
            }
            if end < count && end > 0 {
                weight += 128
                    + 6 * (source.headers()[self.selected[end - 1]].id.len()
                        + source.stamp().generation.len());
            }
            if weight <= MAX_WINDOW_BYTES {
                break;
            }
            let mut next_start = start + usize::from(start < end);
            while next_start < end && !self.boundaries[next_start] {
                next_start += 1;
            }
            let mut next_end = end.saturating_sub(1);
            while next_end > start && !self.boundaries[next_end] {
                next_end -= 1;
            }
            let can_left = next_start <= retained_anchor && next_start < end;
            let can_right = next_end > retained_anchor && next_end > start;
            let left = match &request.at {
                WindowAt::After(_) => false,
                WindowAt::Around { .. } | WindowAt::Turn(_) => {
                    retained_anchor - start >= end - retained_anchor - 1
                }
                _ => true,
            };
            if can_left && (left || !can_right) {
                start = next_start;
            } else if can_right {
                end = next_end;
            } else {
                return Err(DisplayError::Budget);
            }
        }
        let mut rows = Vec::new();
        for &index in &self.selected[start..end] {
            check(&cancelled)?;
            let header = source.headers().get(index).ok_or(DisplayError::Stale)?;
            let body = |kind: BodyKind, size: u64| BodyRef {
                source: request.source.clone(),
                stamp: source.stamp().clone(),
                message_id: header.id.clone(),
                message_hash: header.message_hash.clone(),
                kind,
                bytes: size,
            };
            let text = body(BodyKind::Text, header.text.bytes);
            let row = DisplayRow {
                id: header.id.clone(),
                source_index: index as u32,
                role: header.role.clone(),
                complete: header.complete,
                steer: header.steer,
                turn_id: self.turns[index].clone(),
                run_id: header.run_id.clone(),
                tool_name: header.tool_name.clone(),
                tool_call_id: header.tool_call_id.clone(),
                text,
                thinking: body(BodyKind::Thinking, header.thinking.bytes),
                tool_output: (header.text.bytes == 0 && header.tool_output.bytes > 0)
                    .then(|| body(BodyKind::ToolOutput, header.tool_output.bytes)),
                tool_arguments: (header.tool_args.bytes > 0)
                    .then(|| body(BodyKind::ToolMetadataArguments, header.tool_args.bytes)),
                tool_calls: header
                    .tool_calls
                    .iter()
                    .enumerate()
                    .map(|(slot, call)| DisplayCall {
                        id: call.id.clone(),
                        name: call.name.clone(),
                        arguments: body(BodyKind::ToolArguments(slot as u16), call.arguments.bytes),
                    })
                    .collect(),
                preview: None,
                images: (0..header.image_count())
                    .map(|slot| ImageRef {
                        source: request.source.clone(),
                        stamp: source.stamp().clone(),
                        message_id: header.id.clone(),
                        message_hash: header.message_hash.clone(),
                        image_index: slot as u16,
                    })
                    .collect(),
                file_activity_count: header.file_activities.0,
                batch_id: self.batch[index].clone(),
                info: DisplayInfo {
                    tool_ok: header.tool_ok,
                    model: header.model.clone(),
                    prompt_tokens: header.prompt_tokens,
                    completion_tokens: header.completion_tokens,
                    cached_tokens: header.cached_tokens,
                    elapsed_ms: header.elapsed_ms,
                    first_token_ms: header.first_token_ms,
                    edited: header.edited,
                    timestamp: header.timestamp,
                    failed_model_attempt: header.failed_model_attempt,
                    timestamp_label: None,
                    trace_note: (header.trace_note.bytes > 0)
                        .then(|| body(BodyKind::TraceNote, header.trace_note.bytes)),
                    reply: self.reply_details.get(&index).copied().unwrap_or_default(),
                },
            };
            rows.push(row);
        }
        let visible_turns: HashSet<_> = rows
            .iter()
            .filter_map(|row| row.turn_id.as_deref())
            .collect();
        let candidates = self
            .candidates
            .iter()
            .filter(|candidate| visible_turns.contains(candidate.turn_id.as_str()))
            .cloned()
            .collect();
        let before = (start > 0).then(|| WindowCursor {
            stamp: source.stamp().clone(),
            boundary_id: source.headers()[self.selected[start]].id.clone(),
        });
        let after = (end < count && end > 0).then(|| WindowCursor {
            stamp: source.stamp().clone(),
            boundary_id: source.headers()[self.selected[end - 1]].id.clone(),
        });
        let mut result = DisplayWindow {
            request_id: request.request_id.clone(),
            source: request.source.clone(),
            stamp: source.stamp().clone(),
            rows,
            total_source_rows: source.headers().len(),
            total_selected_rows: count,
            before,
            after,
            candidates,
            anchor_found,
        };
        // Trim whole recorded tool batches before decoding any bodies. The
        // requested anchor atom always survives, even in a turn spanning pages.
        while result.estimated_bytes() > MAX_WINDOW_BYTES {
            check(&cancelled)?;
            let mut next_start = start + usize::from(start < end);
            while next_start < end && !self.boundaries[next_start] {
                next_start += 1;
            }
            let mut next_end = end.saturating_sub(1);
            while next_end > start && !self.boundaries[next_end] {
                next_end -= 1;
            }
            let can_left = next_start <= retained_anchor && next_start < end;
            let can_right = next_end > retained_anchor && next_end > start;
            let left = match &request.at {
                WindowAt::After(_) => false,
                WindowAt::Around { .. } | WindowAt::Turn(_) => {
                    retained_anchor - start >= end - retained_anchor - 1
                }
                _ => true,
            };
            if can_left && (left || !can_right) {
                result.rows.drain(..next_start - start);
                start = next_start;
            } else if can_right {
                result.rows.truncate(next_end - start);
                end = next_end;
            } else {
                return Err(DisplayError::Budget);
            }
            let visible: HashSet<_> = result
                .rows
                .iter()
                .filter_map(|row| row.turn_id.as_deref())
                .collect();
            result
                .candidates
                .retain(|candidate| visible.contains(candidate.turn_id.as_str()));
            result.before = (start > 0).then(|| WindowCursor {
                stamp: source.stamp().clone(),
                boundary_id: source.headers()[self.selected[start]].id.clone(),
            });
            result.after = (end < count && end > 0).then(|| WindowCursor {
                stamp: source.stamp().clone(),
                boundary_id: source.headers()[self.selected[end - 1]].id.clone(),
            });
        }
        let mut remaining = MAX_WINDOW_BYTES - result.estimated_bytes();
        if request.previews {
            for row in &mut result.rows {
                check(&cancelled)?;
                let overhead = 256 + 6 * request.request_id.len() + row.text.estimated_bytes();
                let allowed = remaining.saturating_sub(overhead) / 6;
                if allowed == 0 || row.text.bytes == 0 {
                    continue;
                }
                let page = read_body_page(
                    self,
                    source,
                    &BodyPageRequest {
                        request_id: request.request_id.clone(),
                        body: row.text.clone(),
                        from_byte: 0,
                        max_bytes: allowed.min(PREVIEW_BYTES),
                    },
                    &cancelled,
                )?;
                let weight = page.estimated_bytes();
                if weight <= remaining {
                    remaining -= weight;
                    row.preview = Some(page);
                }
            }
        }
        Ok(result)
    }
}
pub fn message_fingerprint(message: &Message) -> Result<String, DisplayError> {
    struct Sink(Sha256);
    impl Write for Sink {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.update(bytes);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut sink = Sink(Sha256::new());
    serde_json::to_writer(&mut sink, message)
        .map_err(|_| DisplayError::Invalid("消息来源指纹编码失败".into()))?;
    Ok(format!("{:x}", sink.0.finalize()))
}
fn with_verified_message<R>(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    stamp: &SourceStamp,
    id: &str,
    hash: &str,
    cancelled: &impl Fn() -> bool,
    read: impl FnOnce(&Message) -> Result<R, DisplayError>,
) -> Result<R, DisplayError> {
    check(cancelled)?;
    if source.stamp() != stamp {
        return Err(DisplayError::Stale);
    }
    let index = projection.source_index(id).ok_or(DisplayError::Missing)?;
    let header = source.headers().get(index).ok_or(DisplayError::Stale)?;
    if header.id != id || header.message_hash != hash {
        return Err(DisplayError::Stale);
    }
    source.with_message(index, cancelled, |message| {
        if message.id != id {
            return Err(DisplayError::Stale);
        }
        check(cancelled)?;
        read(message)
    })
}
fn with_body_text<R>(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    body: &BodyRef,
    cancelled: &impl Fn() -> bool,
    read: impl FnOnce(&str) -> Result<R, DisplayError>,
) -> Result<R, DisplayError> {
    if source.source() != &body.source {
        return Err(DisplayError::Invalid("正文页归属不匹配".into()));
    }
    with_verified_message(
        projection,
        source,
        &body.stamp,
        &body.message_id,
        &body.message_hash,
        cancelled,
        |message| {
            let text = match body.kind {
                BodyKind::Text => message.text.as_str(),
                BodyKind::Thinking => message.thinking.as_str(),
                BodyKind::ToolOutput => message.metadata.tool_output.as_deref().unwrap_or(""),
                BodyKind::ToolMetadataArguments => {
                    message.metadata.tool_args.as_deref().unwrap_or("")
                }
                BodyKind::ToolArguments(slot) => message
                    .tool_calls
                    .get(usize::from(slot))
                    .ok_or(DisplayError::Missing)?
                    .arguments
                    .as_str(),
                BodyKind::FileActivityDescription(slot) => message
                    .metadata
                    .file_activities
                    .get(usize::from(slot))
                    .ok_or(DisplayError::Missing)?
                    .description
                    .as_str(),
                BodyKind::TraceNote => message.metadata.trace_note.as_deref().unwrap_or(""),
                BodyKind::RowHeader | BodyKind::FooterHeader | BodyKind::ImageBlock(_) => "",
            };
            if text.len() as u64 != body.bytes {
                return Err(DisplayError::Stale);
            }
            read(text)
        },
    )
}

pub fn read_body_page(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    request: &BodyPageRequest,
    cancelled: impl Fn() -> bool,
) -> Result<BodyPage, DisplayError> {
    with_body_text(projection, source, &request.body, &cancelled, |text| {
        let from = usize::try_from(request.from_byte)
            .map_err(|_| DisplayError::Invalid("正文偏移无效".into()))?;
        if from > text.len() || !text.is_char_boundary(from) {
            return Err(DisplayError::Invalid("正文偏移不是UTF-8边界".into()));
        }
        let bytes = request.max_bytes.clamp(4, MAX_BODY_PAGE_BYTES);
        let mut to = from.saturating_add(bytes).min(text.len());
        while !text.is_char_boundary(to) {
            to -= 1;
        }
        let mut previous = from.saturating_sub(bytes);
        while !text.is_char_boundary(previous) {
            previous += 1;
        }
        check(&cancelled)?;
        Ok(BodyPage {
            request_id: request.request_id.clone(),
            body: request.body.clone(),
            from_byte: from as u64,
            to_byte: to as u64,
            text: text[from..to].to_owned(),
            previous_byte: (from > 0).then_some(previous as u64),
            next_byte: (to < text.len()).then_some(to as u64),
        })
    })
}

pub fn read_body_text(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    body: &BodyRef,
) -> Result<String, DisplayError> {
    with_body_text(projection, source, body, &|| false, |text| {
        if text.len() > MAX_BODY_TEXT_BYTES {
            return Err(DisplayError::Invalid(
                "完整正文超过20MiB预算，请使用分页复制".into(),
            ));
        }
        Ok(text.to_owned())
    })
}
/// Return only to the decode worker; never send this owned base64 through a UI mailbox.
pub fn read_image(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    image: &ImageRef,
    cancelled: impl Fn() -> bool,
) -> Result<String, DisplayError> {
    if source.source() != &image.source {
        return Err(DisplayError::Invalid("图像归属不匹配".into()));
    }
    with_verified_message(
        projection,
        source,
        &image.stamp,
        &image.message_id,
        &image.message_hash,
        &cancelled,
        |message| {
            let image = message
                .metadata
                .all_images()
                .get(usize::from(image.image_index))
                .ok_or(DisplayError::Missing)?;
            if image.len() > 16 * 1024 * 1024 {
                return Err(DisplayError::Budget);
            }
            Ok(image.clone())
        },
    )
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ReadPosition {
    pub source: ViewSource,
    pub message_id: String,
    /// A footer description can be owned by an earlier tool record while the
    /// reading anchor remains the final reply displaying that footer.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body_message_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub footer_from: Option<usize>,
    pub body_kind: BodyKind,
    pub byte_offset: u64,
    pub pixel_offset: f32,
    pub follow_tail: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub legacy: Option<Box<crate::legacy_reading::Bookmark>>,
}
impl ReadPosition {
    pub fn validate(&self) -> Result<(), DisplayError> {
        if let Some(bookmark) = &self.legacy {
            bookmark.validate().map_err(DisplayError::Invalid)?;
            if bookmark.conversation_id != self.source.conversation_id() {
                return Err(DisplayError::Invalid("旧阅读书签与当前源不匹配".into()));
            }
        }
        if self.message_id.len() > 128
            || self
                .body_message_id
                .as_ref()
                .is_some_and(|id| id.len() > 128)
            || self.footer_from.is_some_and(|from| from > MAX_ROWS)
            || !self.pixel_offset.is_finite()
            || self.pixel_offset.abs() > 10_000_000.0
        {
            Err(DisplayError::Invalid("阅读位置无效".into()))
        } else {
            Ok(())
        }
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct ConversationSummary {
    pub id: String,
    pub title: String,
    pub updated_at: i64,
    pub assistant_id: Option<String>,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    pub approval_mode: Option<crate::subagent_configuration::ApprovalMode>,
    pub pinned: bool,
    pub archived: bool,
    pub checkpoint_count: usize,
}
impl From<&Conversation> for ConversationSummary {
    fn from(conversation: &Conversation) -> Self {
        Self {
            id: conversation.id.clone(),
            title: conversation.title.clone(),
            updated_at: conversation.updated_at,
            assistant_id: conversation.assistant_id.clone(),
            provider_id: conversation.provider_id.clone(),
            model: conversation.model.clone(),
            approval_mode: conversation.metadata.approval_mode,
            pinned: conversation.metadata.pinned,
            archived: conversation.metadata.archived,
            checkpoint_count: conversation.metadata.checkpoints.len(),
        }
    }
}
#[derive(Clone, Debug)]
pub struct ChildSummary {
    pub source: ViewSource,
    pub name: String,
    pub status: crate::subagents::Status,
    pub detail: String,
    pub message_count: usize,
    pub config: Option<ChildConfigSummary>,
}
#[derive(Clone, Debug)]
pub struct ChildConfigSummary {
    pub profile_name: String,
    pub assistant_name: String,
    pub assistant_avatar: String,
    pub assistant_avatar_path: String,
    pub model: String,
    pub approval_mode: crate::subagent_configuration::ApprovalMode,
    pub timeout_seconds: u32,
    pub max_tool_rounds: u32,
    pub allowed_tools: Vec<String>,
    pub allowed_actions: BTreeMap<String, Vec<String>>,
    pub tools_count: usize,
    pub actions_count: usize,
    pub truncated: bool,
}
impl ChildSummary {
    pub fn id(&self) -> &str {
        match &self.source {
            ViewSource::Child { child_id, .. } => child_id,
            ViewSource::Main { .. } => "",
        }
    }
    pub fn from_record(
        conversation_id: &str,
        parent_run_id: &str,
        record: &crate::subagents::Record,
    ) -> Self {
        fn short(text: &str, max: usize) -> String {
            let mut end = text.len().min(max);
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            text[..end].to_owned()
        }
        let config = record.config.as_ref().map(|config| {
            let mut bytes = 0usize;
            let mut truncated = config.profile_name.len() > 256
                || config.assistant_name.len() > 256
                || config.model.len() > 256;
            let mut tools = Vec::new();
            let mut actions = BTreeMap::new();
            for tool in &config.allowed_tools {
                let weight = 64 + 6 * tool.len();
                if bytes + weight > 8192 || tools.len() >= 128 {
                    truncated = true;
                    break;
                }
                bytes += weight;
                tools.push(tool.clone());
            }
            let mut actions_count = 0usize;
            for (tool, values) in &config.allowed_actions {
                actions_count = actions_count.saturating_add(values.len());
                let weight = 256
                    + 6 * tool.len()
                    + values
                        .iter()
                        .map(|value| 64 + 6 * value.len())
                        .sum::<usize>();
                if bytes + weight > 16 * 1024 {
                    truncated = true;
                    continue;
                }
                bytes += weight;
                actions.insert(tool.clone(), values.clone());
            }
            ChildConfigSummary {
                profile_name: short(&config.profile_name, 256),
                assistant_name: short(&config.assistant_name, 256),
                assistant_avatar: short(&config.assistant_avatar, 128),
                assistant_avatar_path: short(&config.assistant_avatar_path, 1024),
                model: short(&config.model, 256),
                approval_mode: config.approval_mode,
                timeout_seconds: config.timeout_seconds,
                max_tool_rounds: config.max_tool_rounds,
                allowed_tools: tools,
                allowed_actions: actions,
                tools_count: config.allowed_tools.len(),
                actions_count,
                truncated,
            }
        });
        Self {
            source: ViewSource::Child {
                conversation_id: conversation_id.into(),
                parent_run_id: parent_run_id.into(),
                child_id: record.id.clone(),
            },
            name: short(&record.name, 512),
            status: record.status,
            detail: short(&record.detail, 2048),
            message_count: record.messages.len(),
            config,
        }
    }
}
#[derive(Clone, Debug)]
pub struct DisplayFileActivity {
    pub path: String,
    pub kind: FileActivityKind,
    pub added_lines: Option<i64>,
    pub removed_lines: Option<i64>,
    pub size_bytes: Option<i64>,
    pub previous_path: Option<String>,
    pub description_preview: String,
    pub description: Option<BodyRef>,
    description_nonblank: bool,
}
impl DisplayFileActivity {
    pub fn estimated_bytes(&self) -> usize {
        512 + 6
            * (self.path.len()
                + self.previous_path.as_ref().map_or(0, String::len)
                + self.description_preview.len())
            + self
                .description
                .as_ref()
                .map_or(0, BodyRef::estimated_bytes)
    }
}
fn merge_display_activity(
    previous: &DisplayFileActivity,
    next: &DisplayFileActivity,
) -> DisplayFileActivity {
    let known = |value: &DisplayFileActivity| crate::chat::FileActivity {
        path: value.path.clone(),
        kind: value.kind.clone(),
        added_lines: value.added_lines,
        removed_lines: value.removed_lines,
        size_bytes: value.size_bytes,
        description: String::new(),
        previous_path: value.previous_path.clone(),
        extra: BTreeMap::new(),
    };
    // Reuse the original kind/line/path/size algorithm with payload-free values.
    let merged = crate::chat::merge_file_activity(&known(previous), &known(next));
    let description = if next.description_nonblank {
        next
    } else {
        previous
    };
    DisplayFileActivity {
        path: merged.path,
        kind: merged.kind,
        added_lines: merged.added_lines,
        removed_lines: merged.removed_lines,
        size_bytes: merged.size_bytes,
        previous_path: merged.previous_path,
        description_preview: description.description_preview.clone(),
        description: description.description.clone(),
        description_nonblank: description.description_nonblank,
    }
}
#[derive(Clone, Debug)]
pub struct FooterPage {
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub message_hash: String,
    pub message_id: String,
    pub from: usize,
    pub total: usize,
    pub activities: Vec<DisplayFileActivity>,
    pub next: Option<usize>,
}
#[derive(Clone, Debug)]
pub struct FooterRequest {
    pub request_id: String,
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub message_id: String,
    pub message_hash: String,
    pub from: usize,
}
impl FooterPage {
    pub fn estimated_bytes(&self) -> usize {
        512 + self.source.estimated_bytes()
            + 6 * (self.message_id.len() + self.message_hash.len() + self.stamp.generation.len())
            + self
                .activities
                .iter()
                .map(DisplayFileActivity::estimated_bytes)
                .sum::<usize>()
    }
}
fn display_activity(
    source: &impl HistorySource,
    message: &Message,
    slot: usize,
    hash: &str,
) -> Result<DisplayFileActivity, DisplayError> {
    let activity = message
        .metadata
        .file_activities
        .get(slot)
        .ok_or(DisplayError::Missing)?;
    let mut end = activity.description.len().min(PREVIEW_BYTES);
    while !activity.description.is_char_boundary(end) {
        end -= 1;
    }
    let description = (!activity.description.is_empty()).then(|| BodyRef {
        source: source.source().clone(),
        stamp: source.stamp().clone(),
        message_id: message.id.clone(),
        message_hash: hash.into(),
        kind: BodyKind::FileActivityDescription(slot as u16),
        bytes: activity.description.len() as u64,
    });
    if slot > u16::MAX as usize {
        return Err(DisplayError::Budget);
    }
    Ok(DisplayFileActivity {
        path: activity.path.clone(),
        kind: activity.kind.clone(),
        added_lines: activity.added_lines,
        removed_lines: activity.removed_lines,
        size_bytes: activity.size_bytes,
        previous_path: activity.previous_path.clone(),
        description_preview: activity.description[..end].to_owned(),
        description,
        description_nonblank: !activity.description.trim().is_empty(),
    })
}
pub fn read_footer(
    projection: &ProjectionIndex,
    source: &impl HistorySource,
    stamp: &SourceStamp,
    message_id: &str,
    hash: &str,
    from: usize,
    cancelled: impl Fn() -> bool,
) -> Result<FooterPage, DisplayError> {
    with_verified_message(projection, source, stamp, message_id, hash, &cancelled, |message| {
        let total = message.metadata.file_activities.len();
        if from > total {
            return Err(DisplayError::Invalid("文件活动页偏移无效".into()));
        }
        let mut activities = Vec::new();
        let mut bytes = 512
            + source.source().estimated_bytes()
            + 6 * (message_id.len() + hash.len() + stamp.generation.len());
        for (slot, _) in message
            .metadata
            .file_activities
            .iter()
            .enumerate()
            .skip(from)
            .take(64)
        {
            check(&cancelled)?;
            let activity = display_activity(source, message, slot, hash)?;
            let weight = activity.estimated_bytes();
            if bytes + weight > MAX_BODY_PAGE_BYTES {
                break;
            }
            bytes += weight;
            activities.push(activity);
        }
        let next = from + activities.len();
        if next == from && next < total {
            return Err(DisplayError::Budget);
        }
        Ok(FooterPage {
            source: source.source().clone(),
            stamp: stamp.clone(),
            message_hash: hash.into(),
            message_id: message_id.into(),
            from,
            total,
            activities,
            next: (next < total).then_some(next),
        })
    })
}

fn failed_attempt_flag<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<bool, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(value.as_bool().unwrap_or(!value.is_null()))
}

#[cfg(test)]
mod reply_summary_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn summary_uses_the_selected_run_and_survives_history_pagination() {
        let rows = json!([
            {"id":"u1","role":"user","text":"question"},
            {"id":"old","role":"assistant","text":"unselected","runId":"r0","elapsedMs":99000},
            {"id":"a1","role":"assistant","thinking":"plan","runId":"r1","elapsedMs":1000,
             "tool_calls":[{"id":"t1","name":"clock","arguments":"{}"}]},
            {"id":"t1","role":"tool","tool_call_id":"t1","text":"time","runId":"r1"},
            {"id":"a2","role":"assistant","text":"answer","runId":"r1","elapsedMs":2500,
             "runElapsedMs":7000,"completionTokens":50},
            {"id":"u2","role":"user","text":"older format"},
            {"id":"a3","role":"assistant","text":"legacy answer","elapsedMs":2800}
        ]);
        let messages: Vec<Message> = rows
            .as_array()
            .unwrap()
            .iter()
            .cloned()
            .map(|mut row| {
                row["text"] = row.get("text").cloned().unwrap_or(json!(""));
                row["thinking"] = row.get("thinking").cloned().unwrap_or(json!(""));
                row["complete"] = json!(true);
                serde_json::from_value(row).unwrap()
            })
            .collect();
        let headers: Vec<_> = messages
            .iter()
            .map(|message| {
                MessageHeader::from_message(message, message_fingerprint(message).unwrap())
            })
            .collect();
        let source = MemoryHistory {
            source: ViewSource::Main {
                conversation_id: "c1".into(),
            },
            stamp: SourceStamp {
                generation: "g1".into(),
                revision: 1,
                projection_revision: 1,
            },
            messages: &messages,
            headers: &headers,
        };
        let choices = BTreeMap::from([("u1".into(), "r1".into())]);
        let projection = ProjectionIndex::new(&headers, &choices, None, || false).unwrap();
        let mut request = WindowRequest {
            request_id: "test".into(),
            source: source.source.clone(),
            at: WindowAt::Tail,
            max_rows: 96,
            previews: true,
        };
        let page = projection.window(&source, &request, || false).unwrap();
        assert!(page.rows.iter().all(|row| row.id != "old"));
        let row = |id: &str| page.rows.iter().find(|row| row.id == id).unwrap();
        assert_eq!(row("a2").preview.as_ref().unwrap().text, "answer");
        assert_eq!(
            row("a1").info.reply.summary,
            Some(ReplySummary {
                elapsed_ms: 7000,
                thinking: 1,
                tools: 1,
            })
        );
        assert_eq!(row("a2").info.reply.summary, None);
        assert!(row("a2").info.reply.footer);
        assert_eq!(row("a2").info.reply.run_elapsed_ms, 7000);
        assert_eq!(row("a3").info.reply.summary.unwrap().elapsed_ms, 2800);
        assert_eq!(row("a3").info.reply.run_elapsed_ms, 0);

        request.at = WindowAt::Before(WindowCursor {
            stamp: source.stamp.clone(),
            boundary_id: "u2".into(),
        });
        request.max_rows = 1;
        let page = projection.window(&source, &request, || false).unwrap();
        assert_eq!(page.rows.len(), 1);
        assert_eq!(page.rows[0].id, "a2");
        assert_eq!(page.rows[0].info.reply.run_elapsed_ms, 7000);

        request.at = WindowAt::Around {
            message_id: "old".into(),
        };
        let page = projection.window(&source, &request, || false).unwrap();
        assert!(!page.anchor_found);
        assert_eq!(page.rows[0].id, "a3");

        use crate::ui::history::HistoryViews;
        let key = &source.source;
        let mut views = HistoryViews::default();
        views.open(key.clone(), None);
        let apply_page = |views: &mut HistoryViews, at| {
            let mut request = views.begin_window(key, at).unwrap();
            request.max_rows = 1;
            let page = projection.window(&source, &request, || false).unwrap();
            views.apply_window(page).unwrap();
        };
        apply_page(
            &mut views,
            WindowAt::Around {
                message_id: "a2".into(),
            },
        );
        let before = views.view(key).unwrap().before.clone().unwrap();
        apply_page(&mut views, WindowAt::Before(before));
        let after = views.view(key).unwrap().after.clone().unwrap();
        apply_page(&mut views, WindowAt::After(after));
        let ids: Vec<_> = views
            .view(key)
            .unwrap()
            .rows
            .iter()
            .map(|row| row.id.as_str())
            .collect();
        assert_eq!(ids, ["a1", "t1", "a2", "u2"]);
    }

    #[test]
    fn full_body_matches_adjacent_utf8_pages_and_rejects_stale_references() {
        let text = format!("{}🙂尾", "x".repeat(MAX_BODY_PAGE_BYTES - 1));
        let message: Message = serde_json::from_value(json!({
            "id": "a1", "role": "assistant", "text": text, "thinking": "", "complete": true
        }))
        .unwrap();
        let hash = message_fingerprint(&message).unwrap();
        let header = MessageHeader::from_message(&message, hash.clone());
        let source = MemoryHistory {
            source: ViewSource::Main {
                conversation_id: "c1".into(),
            },
            stamp: SourceStamp {
                generation: "g1".into(),
                revision: 1,
                projection_revision: 1,
            },
            messages: std::slice::from_ref(&message),
            headers: std::slice::from_ref(&header),
        };
        let projection =
            ProjectionIndex::new(source.headers, &BTreeMap::new(), None, || false).unwrap();
        let mut request = BodyPageRequest {
            request_id: "read".into(),
            body: BodyRef {
                source: source.source.clone(),
                stamp: source.stamp.clone(),
                message_id: message.id.clone(),
                message_hash: hash,
                kind: BodyKind::Text,
                bytes: message.text.len() as u64,
            },
            from_byte: 0,
            max_bytes: MAX_BODY_PAGE_BYTES,
        };
        let mut first = read_body_page(&projection, &source, &request, || false).unwrap();
        request.from_byte = first.next_byte.unwrap();
        assert_eq!(request.from_byte, (MAX_BODY_PAGE_BYTES - 1) as u64);
        let second = read_body_page(&projection, &source, &request, || false).unwrap();
        assert_eq!(second.text, "🙂尾");
        assert_eq!(second.next_byte, None);
        first.text.push_str(&second.text);
        assert_eq!(
            read_body_text(&projection, &source, &request.body).unwrap(),
            first.text
        );

        request.body.stamp.revision += 1;
        assert!(matches!(
            read_body_text(&projection, &source, &request.body),
            Err(DisplayError::Stale)
        ));
    }
}

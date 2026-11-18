//! Durable chat metadata and transcript projections. Original messages remain on disk.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct MessageMetadata {
    #[serde(alias = "runId", skip_serializing_if = "Option::is_none")]
    pub run_id: Option<String>,
    #[serde(alias = "toolName", skip_serializing_if = "Option::is_none")]
    pub tool_name: Option<String>,
    #[serde(alias = "toolArgs", skip_serializing_if = "Option::is_none")]
    pub tool_args: Option<String>,
    #[serde(alias = "toolOutput", skip_serializing_if = "Option::is_none")]
    pub tool_output: Option<String>,
    #[serde(alias = "toolOk", skip_serializing_if = "Option::is_none")]
    pub tool_ok: Option<bool>,
    #[serde(alias = "imageBase64", skip_serializing_if = "Option::is_none")]
    pub image_base64: Option<String>,
    #[serde(alias = "imagesBase64", skip_serializing_if = "Vec::is_empty")]
    pub images_base64: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(alias = "promptTokens", skip_serializing_if = "Option::is_none")]
    pub prompt_tokens: Option<i64>,
    #[serde(alias = "completionTokens", skip_serializing_if = "Option::is_none")]
    pub completion_tokens: Option<i64>,
    #[serde(alias = "reasoningTokens", skip_serializing_if = "Option::is_none")]
    pub reasoning_tokens: Option<i64>,
    #[serde(alias = "cachedTokens", skip_serializing_if = "Option::is_none")]
    pub cached_tokens: Option<i64>,
    #[serde(alias = "elapsedMs")]
    pub elapsed_ms: i64,
    #[serde(alias = "firstTokenMs")]
    pub first_token_ms: i64,
    pub edited: bool,
    #[serde(alias = "traceNote", skip_serializing_if = "Option::is_none")]
    pub trace_note: Option<String>,
    pub steer: bool,
    pub timestamp: i64,
    #[serde(alias = "fileActivities", skip_serializing_if = "Vec::is_empty")]
    pub file_activities: Vec<FileActivity>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

pub(crate) const LEGACY_ORIGINAL_ID: &str = "rustLegacyOriginalMessageId";

impl MessageMetadata {
    pub(crate) fn legacy_original_id(&self) -> Option<&str> {
        self.extra
            .get(LEGACY_ORIGINAL_ID)
            .and_then(serde_json::Value::as_str)
    }

    pub fn all_images(&self) -> &[String] {
        if !self.images_base64.is_empty() {
            &self.images_base64
        } else {
            self.image_base64.as_ref().map_or(&[], std::slice::from_ref)
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FileActivityKind {
    Created,
    Modified,
    Downloaded,
    Delivered,
    Deleted,
    Moved,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct FileActivity {
    pub path: String,
    pub kind: FileActivityKind,
    #[serde(default, alias = "addedLines")]
    pub added_lines: Option<i64>,
    #[serde(default, alias = "removedLines")]
    pub removed_lines: Option<i64>,
    #[serde(default, alias = "sizeBytes")]
    pub size_bytes: Option<i64>,
    #[serde(default)]
    pub description: String,
    #[serde(default, alias = "previousPath")]
    pub previous_path: Option<String>,
    #[serde(default, flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct ContextCheckpoint {
    pub id: String,
    pub at: i64,
    #[serde(alias = "fromMessageId")]
    pub from_message_id: String,
    #[serde(alias = "toMessageId")]
    pub to_message_id: String,
    pub summary: String,
    pub messages: usize,
    #[serde(alias = "promptTokens")]
    pub prompt_tokens: i64,
    #[serde(alias = "anchorId")]
    pub anchor_id: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct ConversationMetadata {
    #[serde(alias = "createdAt")]
    pub created_at: i64,
    pub pinned: bool,
    pub archived: bool,
    #[serde(alias = "activeRuns")]
    pub active_runs: BTreeMap<String, String>,
    #[serde(alias = "reasoningEffort")]
    pub reasoning_effort: Option<String>,
    #[serde(alias = "approvalMode")]
    pub approval_mode: Option<crate::subagent_configuration::ApprovalMode>,
    pub checkpoints: Vec<ContextCheckpoint>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

#[derive(Clone, Debug)]
pub enum Mutation {
    Rename(String),
    Pin(bool),
    Archive(bool),
    SelectCandidate { user_id: String, run_id: String },
    EditMessage { message_id: String, text: String },
}

pub fn apply_mutation(
    conversation: &mut crate::state::Conversation,
    messages: &mut [crate::state::Message],
    mutation: Mutation,
) -> Result<(), String> {
    match mutation {
        Mutation::Rename(title) => {
            if title.trim().is_empty() || title.len() > 1024 {
                return Err("会话标题为空或过长".into());
            }
            conversation.title = title.trim().into();
        }
        Mutation::Pin(value) => conversation.metadata.pinned = value,
        Mutation::Archive(value) => conversation.metadata.archived = value,
        Mutation::SelectCandidate { user_id, run_id } => {
            let start = messages
                .iter()
                .position(|message| {
                    message.id == user_id && message.role == "user" && !message.metadata.steer
                })
                .ok_or("没有找到原提问")?;
            let end = (start + 1..messages.len())
                .find(|&i| messages[i].role == "user" && !messages[i].metadata.steer)
                .unwrap_or(messages.len());
            if !messages[start + 1..end].iter().any(|message| {
                message.role != "user" && message.metadata.run_id.as_deref().unwrap_or("") == run_id
            }) {
                return Err("该回答不属于所选提问".into());
            }
            conversation.metadata.active_runs.insert(user_id, run_id);
        }
        Mutation::EditMessage { message_id, text } => {
            if text.len() > 1024 * 1024 {
                return Err("编辑正文超过1 MiB".into());
            }
            let index = messages
                .iter()
                .position(|message| message.id == message_id)
                .ok_or("消息已不存在")?;
            if !["user", "assistant"].contains(&messages[index].role.as_str()) {
                return Err("此消息不能修改正文".into());
            }
            messages[index].text = text;
            messages[index].metadata.edited = true;
            for checkpoint in &mut conversation.metadata.checkpoints {
                let last = messages
                    .iter()
                    .position(|message| message.id == checkpoint.to_message_id);
                // Later checkpoints may summarize an earlier checkpoint that covered
                // this edit. Invalidate the complete dependency suffix before reuse.
                if last.is_none_or(|last| last >= index) {
                    checkpoint
                        .extra
                        .insert("invalidated".into(), serde_json::Value::Bool(true));
                }
            }
        }
    }
    Ok(())
}

pub fn file_activities_for_reply(
    messages: &[crate::state::Message],
    reply_index: usize,
) -> Vec<FileActivity> {
    let Some(reply) = messages
        .get(reply_index)
        .filter(|message| message.role == "assistant" && message.tool_calls.is_empty())
    else {
        return Vec::new();
    };
    let start = (0..=reply_index)
        .rev()
        .find(|&i| messages[i].role == "user" && !messages[i].metadata.steer)
        .unwrap_or(0);
    let run = reply.metadata.run_id.as_deref().unwrap_or("");
    // The footer belongs to the last plain assistant response of its candidate.
    let end = (reply_index + 1..messages.len())
        .find(|&i| messages[i].role == "user" && !messages[i].metadata.steer)
        .unwrap_or(messages.len());
    if messages[reply_index + 1..end].iter().any(|message| {
        message.role == "assistant"
            && message.tool_calls.is_empty()
            && message.metadata.run_id.as_deref().unwrap_or("") == run
    }) {
        return Vec::new();
    }
    let mut files: Vec<FileActivity> = Vec::new();
    for message in &messages[start..=reply_index] {
        if message.role != "user" && message.metadata.run_id.as_deref().unwrap_or("") != run {
            continue;
        }
        for activity in &message.metadata.file_activities {
            let moved = if activity.kind == FileActivityKind::Moved {
                activity
                    .previous_path
                    .as_ref()
                    .and_then(|path| files.iter().position(|file| &file.path == path))
                    .map(|index| files.remove(index))
            } else {
                None
            };
            if let Some(index) = files.iter().position(|file| file.path == activity.path) {
                files[index] = merge_file_activity(&files[index], activity);
            } else if let Some(previous) = moved {
                files.push(merge_file_activity(&previous, activity));
            } else {
                files.push(activity.clone());
            }
        }
    }
    files
}

pub(crate) fn merge_file_activity(previous: &FileActivity, next: &FileActivity) -> FileActivity {
    use FileActivityKind::*;
    let change = |kind: &FileActivityKind| matches!(kind, Created | Modified | Deleted);
    let total = |a: Option<i64>, b: Option<i64>| {
        a.zip(b)
            .map(|(a, b)| a.saturating_add(b).min(i32::MAX as i64))
    };
    let added_lines = if !change(&next.kind) {
        previous.added_lines
    } else if !change(&previous.kind) {
        next.added_lines
    } else {
        total(previous.added_lines, next.added_lines)
    };
    let removed_lines = if !change(&next.kind) {
        previous.removed_lines
    } else if !change(&previous.kind) {
        next.removed_lines
    } else {
        total(previous.removed_lines, next.removed_lines)
    };
    let kind = if next.kind == Deleted {
        Deleted
    } else if next.kind == Moved {
        if change(&previous.kind) {
            previous.kind.clone()
        } else {
            Moved
        }
    } else if previous.kind == Deleted && change(&next.kind) {
        next.kind.clone()
    } else if previous.kind == Moved {
        if change(&next.kind) {
            next.kind.clone()
        } else {
            Moved
        }
    } else if previous.kind == Created || next.kind == Created {
        Created
    } else if change(&previous.kind) || change(&next.kind) {
        Modified
    } else if previous.kind == Downloaded || next.kind == Downloaded {
        Downloaded
    } else {
        Delivered
    };
    let mut merged = previous.clone();
    merged.path = next.path.clone();
    merged.kind = kind;
    merged.added_lines = added_lines;
    merged.removed_lines = removed_lines;
    merged.size_bytes = if next.kind == Deleted {
        None
    } else {
        next.size_bytes.or(previous.size_bytes)
    };
    if !next.description.trim().is_empty() {
        merged.description = next.description.clone();
    }
    merged.previous_path = next
        .previous_path
        .clone()
        .or_else(|| previous.previous_path.clone());
    merged
}

/// Pick one candidate per user turn without deleting alternative runs. Historical
/// messages with no run identity stay visible for backward compatibility.
pub fn projected_indices(
    messages: &[crate::state::Message],
    selected: &BTreeMap<String, String>,
) -> Vec<usize> {
    let mut result = Vec::new();
    let mut start = 0;
    while start < messages.len() {
        if messages[start].role != "user" || messages[start].metadata.steer {
            result.push(start);
            start += 1;
            continue;
        }
        let end = (start + 1..messages.len())
            .find(|&i| messages[i].role == "user" && !messages[i].metadata.steer)
            .unwrap_or(messages.len());
        result.push(start);
        let runs: Vec<&str> = messages[start + 1..end]
            .iter()
            .filter(|message| message.role != "user")
            .map(|m| m.metadata.run_id.as_deref().unwrap_or(""))
            .collect();
        let chosen = selected
            .get(&messages[start].id)
            .map(String::as_str)
            .filter(|id| runs.contains(id))
            .or_else(|| runs.iter().rev().copied().find(|id| !id.is_empty()))
            .or_else(|| runs.last().copied());
        result.extend((start + 1..end).filter(|&i| {
            messages[i].role == "user"
                || Some(messages[i].metadata.run_id.as_deref().unwrap_or("")) == chosen
        }));
        start = end;
    }
    result
}

pub fn validate_message(metadata: &MessageMetadata) -> Result<(), String> {
    if metadata
        .run_id
        .as_ref()
        .is_some_and(|id| id.len() > 128 || id.contains('\0'))
        || metadata
            .model
            .as_ref()
            .is_some_and(|model| model.len() > 16 * 1024)
        || metadata
            .tool_name
            .as_ref()
            .is_some_and(|name| name.len() > 1024)
        || metadata
            .tool_args
            .as_ref()
            .is_some_and(|args| args.len() > 1024 * 1024)
        || metadata
            .tool_output
            .as_ref()
            .is_some_and(|text| text.len() > 4 * 1024 * 1024)
        || metadata
            .trace_note
            .as_ref()
            .is_some_and(|text| text.len() > 64 * 1024)
        || metadata.elapsed_ms < 0
        || metadata.first_token_ms < 0
        || metadata.timestamp < 0
        || [
            metadata.prompt_tokens,
            metadata.completion_tokens,
            metadata.reasoning_tokens,
            metadata.cached_tokens,
        ]
        .iter()
        .any(|n| n.is_some_and(|n| n < 0))
        || metadata.file_activities.len() > 4096
    {
        return Err("聊天消息元数据无效或超过预算".into());
    }
    let images = metadata.all_images();
    if images.len() > 256
        || images.iter().any(|image| image.len() > 16 * 1024 * 1024)
        || images.iter().map(String::len).sum::<usize>() > 32 * 1024 * 1024
    {
        return Err("聊天图片超过预算".into());
    }
    for activity in &metadata.file_activities {
        if activity.path.is_empty()
            || activity.path.len() > 4096
            || activity.path.contains('\0')
            || activity.description.len() > 8192
            || activity
                .previous_path
                .as_ref()
                .is_some_and(|path| path.len() > 4096 || path.contains('\0'))
            || [
                activity.added_lines,
                activity.removed_lines,
                activity.size_bytes,
            ]
            .iter()
            .any(|n| n.is_some_and(|n| n < 0))
        {
            return Err("文件活动元数据无效".into());
        }
    }
    Ok(())
}

pub fn validate_conversation(
    metadata: &ConversationMetadata,
    messages: &[crate::state::Message],
) -> Result<(), String> {
    if metadata.created_at < 0
        || metadata.active_runs.len() > 32768
        || metadata.checkpoints.len() > 4096
    {
        return Err("会话元数据超过预算".into());
    }
    for (id, run) in &metadata.active_runs {
        if id.is_empty() || id.len() > 128 || run.len() > 128 {
            return Err("候选回答身份无效".into());
        }
    }
    if metadata.reasoning_effort.as_ref().is_some_and(|effort| {
        !["", "none", "minimal", "low", "medium", "high", "xhigh"].contains(&effort.as_str())
    }) {
        return Err("会话思考等级无效".into());
    }
    let mut ids = HashSet::new();
    for checkpoint in &metadata.checkpoints {
        if checkpoint.id.is_empty()
            || checkpoint.id.len() > 128
            || !ids.insert(&checkpoint.id)
            || checkpoint.at < 0
            || checkpoint.summary.len() > 1024 * 1024
            || checkpoint.prompt_tokens < 0
        {
            return Err("上下文检查点无效或重复".into());
        }
        // Removed or edited spans remain archival evidence; only valid checkpoints
        // are eligible for request reuse, evaluated against the selected projection.
        let _ = messages;
    }
    Ok(())
}

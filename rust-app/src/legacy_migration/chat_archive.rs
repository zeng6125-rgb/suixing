use super::{GenerationFile, digest};
use crate::state::{Conversation, Message, QueuedTurn};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    path::{Path, PathBuf},
};

const SEGMENT_BYTES: usize = 8 * 1024 * 1024;
// A legitimate old 16 MiB message can expand slightly while normalizing fields.
const MAX_MESSAGE_BYTES: usize = 20 * 1024 * 1024;
const MAX_TRANSCRIPT_BYTES: u64 = 512 * 1024 * 1024;
const MAX_MESSAGES: usize = 131_072;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChatSegment {
    pub path: PathBuf,
    pub sha256: String,
    pub bytes: u64,
    pub messages: u64,
    pub first_id: String,
    pub last_id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChatArchive {
    pub version: u32,
    pub conversation: Conversation,
    pub queued_turns: Vec<QueuedTurn>,
    pub message_count: u64,
    pub segments: Vec<ChatSegment>,
}

pub fn encode_chat_archive(
    conversation: &Conversation,
    messages: &[Message],
    queued_turns: &[QueuedTurn],
) -> Result<Vec<GenerationFile>, String> {
    identity(&conversation.id)?;
    if messages.len() > MAX_MESSAGES || queued_turns.len() > MAX_MESSAGES {
        return Err("迁移会话条数超过分段预算，拒绝部分写入".into());
    }
    let mut files = Vec::new();
    let mut segments = Vec::new();
    let mut data = Vec::new();
    let mut first = String::new();
    let mut last = String::new();
    let mut count = 0u64;
    let mut total = 0u64;
    let mut ids = BTreeSet::new();
    for message in messages {
        identity(&message.id)?;
        if !ids.insert(&message.id) {
            return Err("迁移会话包含重复消息身份".into());
        }
        let encoded = serde_json::to_vec(message).map_err(|_| "迁移消息编码失败")?;
        if encoded.len() > MAX_MESSAGE_BYTES {
            return Err("迁移消息超过20 MiB独立段预算，原源保留".into());
        }
        total = total
            .checked_add(encoded.len() as u64 + 1)
            .ok_or("迁移历史长度溢出")?;
        if total > MAX_TRANSCRIPT_BYTES {
            return Err("迁移历史超过512 MiB分段预算，拒绝截断".into());
        }
        if !data.is_empty() && data.len().saturating_add(encoded.len() + 1) > SEGMENT_BYTES {
            flush(
                &conversation.id,
                &mut files,
                &mut segments,
                &mut data,
                &first,
                &last,
                count,
            );
            count = 0;
        }
        if count == 0 {
            first = message.id.clone();
        }
        last = message.id.clone();
        data.extend_from_slice(&encoded);
        data.push(b'\n');
        count += 1;
        if data.len() >= SEGMENT_BYTES {
            flush(
                &conversation.id,
                &mut files,
                &mut segments,
                &mut data,
                &first,
                &last,
                count,
            );
            count = 0;
        }
    }
    if !data.is_empty() {
        flush(
            &conversation.id,
            &mut files,
            &mut segments,
            &mut data,
            &first,
            &last,
            count,
        );
    }
    let archive = ChatArchive {
        version: 1,
        conversation: conversation.clone(),
        queued_turns: queued_turns.to_vec(),
        message_count: messages.len() as u64,
        segments,
    };
    let bytes = serde_json::to_vec(&archive).map_err(|_| "迁移历史索引编码失败")?;
    if bytes.len() > SEGMENT_BYTES {
        return Err("迁移历史索引超过8 MiB预算".into());
    }
    files.push(GenerationFile {
        path: PathBuf::from(format!("conversations/{}.segments.json", conversation.id)),
        bytes,
    });
    Ok(files)
}
fn flush(
    id: &str,
    files: &mut Vec<GenerationFile>,
    segments: &mut Vec<ChatSegment>,
    data: &mut Vec<u8>,
    first: &str,
    last: &str,
    count: u64,
) {
    let bytes = std::mem::take(data);
    let hash = digest(&bytes);
    let path = PathBuf::from(format!(
        "conversation-segments/{id}/{:08}-{hash}.jsonl",
        segments.len()
    ));
    segments.push(ChatSegment {
        path: path.clone(),
        sha256: hash,
        bytes: bytes.len() as u64,
        messages: count,
        first_id: first.into(),
        last_id: last.into(),
    });
    files.push(GenerationFile { path, bytes });
}

pub fn read_chat_archive(
    root: &Path,
    id: &str,
) -> Result<(Conversation, Vec<Message>, Vec<QueuedTurn>), String> {
    identity(id)?;
    let bytes = super::transaction::read_regular(
        root,
        &PathBuf::from(format!("conversations/{id}.segments.json")),
        SEGMENT_BYTES as u64,
    )?;
    let archive: ChatArchive = serde_json::from_slice(&bytes).map_err(|_| "迁移历史索引损坏")?;
    if archive.version != 1
        || archive.conversation.id != id
        || archive.message_count > MAX_MESSAGES as u64
        || archive.segments.len() > MAX_MESSAGES
    {
        return Err("迁移历史索引身份或预算无效".into());
    }
    let mut messages = Vec::new();
    let mut seen_paths = BTreeSet::new();
    let mut seen_ids = BTreeSet::new();
    let mut total = 0u64;
    for segment in &archive.segments {
        let prefix = PathBuf::from(format!("conversation-segments/{id}"));
        if !segment.path.starts_with(&prefix)
            || !seen_paths.insert(&segment.path)
            || segment.messages == 0
            || segment.messages > MAX_MESSAGES as u64
            || segment.bytes > MAX_MESSAGE_BYTES as u64 + 1
        {
            return Err("迁移历史分段路径或预算无效".into());
        }
        let bytes =
            super::transaction::read_regular(root, &segment.path, MAX_MESSAGE_BYTES as u64 + 1)?;
        total = total
            .checked_add(bytes.len() as u64)
            .ok_or("历史段长度溢出")?;
        if total > MAX_TRANSCRIPT_BYTES
            || bytes.len() as u64 != segment.bytes
            || digest(&bytes) != segment.sha256
            || bytes.last() != Some(&b'\n')
        {
            return Err("迁移历史分段hash、长度或边界校验失败".into());
        }
        let start = messages.len();
        for line in bytes[..bytes.len() - 1].split(|b| *b == b'\n') {
            if line.is_empty() || line.len() > MAX_MESSAGE_BYTES || messages.len() >= MAX_MESSAGES {
                return Err("迁移历史分段消息预算无效，未返回截断历史".into());
            }
            let message: Message =
                serde_json::from_slice(line).map_err(|_| "迁移历史消息JSON损坏")?;
            identity(&message.id)?;
            if !seen_ids.insert(message.id.clone()) {
                return Err("迁移历史分段重复消息ID".into());
            }
            messages.push(message);
        }
        if (messages.len() - start) as u64 != segment.messages
            || messages[start].id != segment.first_id
            || messages.last().map(|m| &m.id) != Some(&segment.last_id)
        {
            return Err("迁移历史分段身份边界不一致".into());
        }
    }
    if messages.len() as u64 != archive.message_count {
        return Err("迁移历史总消息数不一致".into());
    }
    Ok((archive.conversation, messages, archive.queued_turns))
}
pub(crate) fn identity(id: &str) -> Result<(), String> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
    {
        Err("迁移记录身份不是安全的128字节文件标识".into())
    } else {
        Ok(())
    }
}

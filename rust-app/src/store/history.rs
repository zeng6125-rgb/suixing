//! Pinned manifest reader for bounded display/body requests. Never writes history.
use super::{MAX_CHAT_BYTES, MAX_CHILD_BYTES, Store, validate_id};
use crate::{
    display::{
        DisplayError, HistorySource, MessageHeader, SourceStamp, ViewSource, message_fingerprint,
    },
    legacy_migration::{ChatArchive, ChatSegment},
    state::{Conversation, Message, QueuedTurn},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

const INDEX_BYTES: usize = 8 * 1024 * 1024;
const MESSAGE_BYTES: usize = 20 * 1024 * 1024;
const TRANSCRIPT_BYTES: u64 = 512 * 1024 * 1024;
#[derive(Clone)]
struct Location {
    segment: usize,
    from: usize,
    to: usize,
}
enum Bodies {
    Segments {
        segments: Vec<ChatSegment>,
        locations: Vec<Location>,
    },
    Memory(Vec<Arc<Message>>),
}
pub struct CommittedHistory {
    source: ViewSource,
    stamp: SourceStamp,
    headers: Vec<MessageHeader>,
    root: PathBuf,
    bodies: Bodies,
    cache: Mutex<Option<(usize, Arc<Vec<u8>>)>>,
    pub conversation: Conversation,
    pub queued_turns: Vec<QueuedTurn>,
}
impl CommittedHistory {
    pub(crate) fn set_owner_stamp(&mut self, stamp: SourceStamp) {
        self.stamp = stamp;
    }
}
fn cancelled(check: &impl Fn() -> bool) -> Result<(), DisplayError> {
    if check() {
        Err(DisplayError::Cancelled)
    } else {
        Ok(())
    }
}
fn failure(error: impl std::fmt::Display) -> DisplayError {
    DisplayError::Invalid(error.to_string())
}
fn read_file(
    root: &Path,
    path: &Path,
    max: usize,
    check: &impl Fn() -> bool,
) -> Result<Vec<u8>, DisplayError> {
    cancelled(check)?;
    let relative = path
        .strip_prefix(root)
        .map_err(|_| DisplayError::Invalid("历史路径越界".into()))?;
    let mut current = root.to_owned();
    for component in relative.components() {
        let std::path::Component::Normal(name) = component else {
            return Err(DisplayError::Invalid("历史路径必须是正常相对路径".into()));
        };
        current.push(name);
        let metadata = fs::symlink_metadata(&current).map_err(failure)?;
        if metadata.file_type().is_symlink() || (!metadata.is_file() && !metadata.is_dir()) {
            return Err(DisplayError::Invalid("历史路径含符号链接或特殊文件".into()));
        }
    }
    let named = fs::symlink_metadata(path).map_err(failure)?;
    if !named.is_file() || named.file_type().is_symlink() || named.len() > max as u64 {
        return Err(DisplayError::Budget);
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let mut file = options.open(path).map_err(failure)?;
    let opened = file.metadata().map_err(failure)?;
    if !opened.is_file() || !super::same_file(&named, &opened) || named.len() != opened.len() {
        return Err(DisplayError::Stale);
    }
    let mut bytes = Vec::new();
    let mut buffer = [0u8; 16 * 1024];
    loop {
        cancelled(check)?;
        let count = file.read(&mut buffer).map_err(failure)?;
        if count == 0 {
            break;
        }
        if count > max.saturating_sub(bytes.len()) {
            return Err(DisplayError::Budget);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    let after = file.metadata().map_err(failure)?;
    let named_after = fs::symlink_metadata(path).map_err(failure)?;
    if named_after.file_type().is_symlink()
        || bytes.len() as u64 != opened.len()
        || after.len() != opened.len()
        || named_after.len() != opened.len()
        || opened.modified().ok() != after.modified().ok()
        || after.modified().ok() != named_after.modified().ok()
        || !super::same_file(&opened, &after)
        || !super::same_file(&after, &named_after)
        || fs::canonicalize(path).map_err(failure)? != path
    {
        return Err(DisplayError::Stale);
    }
    Ok(bytes)
}

/// A frozen read-only tail job: created cheaply by the actor, consumed on one
/// worker. It never opens Store, writes history or scans the entire transcript.
pub(crate) struct ConsoleTailRequest {
    root: PathBuf,
    directory: PathBuf,
    id: String,
}
struct ConsoleText {
    text: String,
    blank: bool,
    truncated: bool,
}
impl Default for ConsoleText {
    fn default() -> Self {
        Self {
            text: String::new(),
            blank: true,
            truncated: false,
        }
    }
}
impl<'de> Deserialize<'de> for ConsoleText {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct TextVisitor;
        impl<'de> serde::de::Visitor<'de> for TextVisitor {
            type Value = ConsoleText;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("message text")
            }
            fn visit_str<E: serde::de::Error>(self, value: &str) -> Result<Self::Value, E> {
                let text = value.chars().take(1024).collect();
                Ok(ConsoleText {
                    text,
                    blank: value.trim().is_empty(),
                    truncated: value.chars().take(1025).count() > 1024,
                })
            }
            fn visit_string<E: serde::de::Error>(self, value: String) -> Result<Self::Value, E> {
                self.visit_str(&value)
            }
        }
        deserializer.deserialize_string(TextVisitor)
    }
}
#[derive(Deserialize)]
struct ConsoleMessage {
    id: String,
    role: String,
    #[serde(default)]
    text: ConsoleText,
    #[serde(default, alias = "toolName")]
    tool_name: Option<String>,
}
impl ConsoleMessage {
    fn render(&self, text: &mut String) {
        text.push_str(match self.role.as_str() {
            "user" => "你：",
            "tool" => "工具：",
            _ => "JAgent：",
        });
        if crate::transcript_redaction::sensitive_result(self.tool_name.as_deref().unwrap_or("")) {
            text.push_str(crate::transcript_redaction::PLACEHOLDER);
        } else {
            text.extend(
                self.text
                    .text
                    .trim()
                    .chars()
                    .map(|c| if c == '\n' || c == '\r' { ' ' } else { c }),
            );
            if self.text.truncated {
                text.push('…');
            }
        }
        text.push_str("\n\n");
    }
    fn valid(&self) -> Result<(), DisplayError> {
        validate_id(&self.id).map_err(failure)?;
        if self.role.len() > 128 || self.tool_name.as_ref().is_some_and(|v| v.len() > 256) {
            return Err(DisplayError::Budget);
        }
        Ok(())
    }
}
impl ConsoleTailRequest {
    pub(crate) fn read(self, check: impl Fn() -> bool) -> Result<String, DisplayError> {
        use std::collections::VecDeque;
        const TAIL_IO: u64 = 64 * 1024 * 1024;
        const TAIL_ROWS: usize = 512;
        const TAIL_SEGMENTS: usize = 8;
        let manifest = self.directory.join(format!("{}.segments.json", self.id));
        let present = match fs::symlink_metadata(&manifest) {
            Ok(_) => true,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(error) => return Err(failure(error)),
        };
        let mut recent = VecDeque::new();
        let mut partial = false;
        if present {
            let index = read_file(&self.root, &manifest, INDEX_BYTES, &check)?;
            let archive: ChatArchive = serde_json::from_slice(&index).map_err(failure)?;
            if archive.version != 1
                || archive.conversation.id != self.id
                || archive.message_count > 131_072
                || archive.segments.len() > 131_072
            {
                return Err(DisplayError::Invalid("悬浮历史索引身份或条数无效".into()));
            }
            let mut io_bytes = index.len() as u64 + INDEX_BYTES as u64;
            let mut rows = 0usize;
            let mut seen = HashSet::new();
            let mut reached = false;
            for (count, segment) in archive.segments.iter().rev().enumerate() {
                cancelled(&check)?;
                if count >= TAIL_SEGMENTS || io_bytes.saturating_add(segment.bytes) > TAIL_IO {
                    partial = true;
                    break;
                }
                let prefix = PathBuf::from(format!("conversation-segments/{}", self.id));
                if !segment.path.starts_with(&prefix)
                    || segment
                        .path
                        .components()
                        .any(|part| !matches!(part, std::path::Component::Normal(_)))
                    || !seen.insert(&segment.path)
                    || segment.messages == 0
                    || segment.messages > 131_072
                    || segment.bytes > MESSAGE_BYTES as u64 + 1
                {
                    return Err(DisplayError::Invalid("悬浮历史段身份或预算无效".into()));
                }
                let bytes = read_file(
                    &self.root,
                    &self.root.join(&segment.path),
                    MESSAGE_BYTES + 1,
                    &check,
                )?;
                io_bytes = io_bytes.saturating_add(bytes.len() as u64);
                if bytes.len() as u64 != segment.bytes
                    || crate::legacy_migration::digest(&bytes) != segment.sha256
                    || bytes.last() != Some(&b'\n')
                {
                    return Err(DisplayError::Stale);
                }
                let lines = bytes[..bytes.len() - 1]
                    .split(|&byte| byte == b'\n')
                    .collect::<Vec<_>>();
                if lines.len() as u64 != segment.messages
                    || lines
                        .iter()
                        .any(|line| line.is_empty() || line.len() > MESSAGE_BYTES)
                {
                    return Err(DisplayError::Stale);
                }
                #[derive(Deserialize)]
                struct Identity {
                    id: String,
                }
                let first: Identity =
                    serde_json::from_slice(lines.first().ok_or(DisplayError::Missing)?)
                        .map_err(failure)?;
                let last: Identity =
                    serde_json::from_slice(lines.last().ok_or(DisplayError::Missing)?)
                        .map_err(failure)?;
                if first.id != segment.first_id || last.id != segment.last_id {
                    return Err(DisplayError::Stale);
                }
                for line in lines.into_iter().rev() {
                    cancelled(&check)?;
                    if rows == TAIL_ROWS {
                        partial = true;
                        reached = true;
                        break;
                    }
                    rows += 1;
                    let message: ConsoleMessage = serde_json::from_slice(line).map_err(failure)?;
                    message.valid()?;
                    if !message.text.blank {
                        recent.push_front(message);
                        if recent.len() == 6 {
                            reached = true;
                            break;
                        }
                    }
                }
                if reached {
                    break;
                }
            }
            // Pin the exact index throughout the tail read, independently of
            // the actor's owning revision check when receiving the result.
            let after = read_file(&self.root, &manifest, INDEX_BYTES, &check)?;
            if crate::legacy_migration::digest(&index) != crate::legacy_migration::digest(&after) {
                return Err(DisplayError::Stale);
            }
        } else {
            let path = self.directory.join(format!("{}.json", self.id));
            let bytes = read_file(&self.root, &path, MAX_CHAT_BYTES as usize, &check)?;
            struct Recent(VecDeque<ConsoleMessage>);
            impl<'de> Deserialize<'de> for Recent {
                fn deserialize<D: serde::Deserializer<'de>>(
                    deserializer: D,
                ) -> Result<Self, D::Error> {
                    struct Rows;
                    impl<'de> serde::de::Visitor<'de> for Rows {
                        type Value = Recent;
                        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                            f.write_str("bounded historical message array")
                        }
                        fn visit_seq<A: serde::de::SeqAccess<'de>>(
                            self,
                            mut access: A,
                        ) -> Result<Self::Value, A::Error> {
                            let mut rows = VecDeque::new();
                            let mut count = 0usize;
                            while let Some(message) = access.next_element::<ConsoleMessage>()? {
                                count += 1;
                                if count > 131_072 {
                                    return Err(serde::de::Error::custom("悬浮历史条数超过预算"));
                                }
                                message.valid().map_err(serde::de::Error::custom)?;
                                if !message.text.blank {
                                    rows.push_back(message);
                                    if rows.len() > 6 {
                                        rows.pop_front();
                                    }
                                }
                            }
                            Ok(Recent(rows))
                        }
                    }
                    deserializer.deserialize_seq(Rows)
                }
            }
            #[derive(Deserialize)]
            struct Identity {
                id: String,
            }
            #[derive(Deserialize)]
            struct Document {
                version: u32,
                conversation: Identity,
                messages: Recent,
            }
            let document: Document = serde_json::from_slice(&bytes).map_err(failure)?;
            cancelled(&check)?;
            if document.version != 1 || document.conversation.id != self.id {
                return Err(DisplayError::Stale);
            }
            recent = document.messages.0;
        }
        let mut text = String::new();
        for message in recent {
            message.render(&mut text);
        }
        if partial {
            text.push_str("较早内容超出悬浮预览读取范围，可打开会话继续阅读。\n");
        }
        Ok(text)
    }
}
impl Store {
    pub(crate) fn console_tail_request(
        &self,
        id: &str,
    ) -> Result<ConsoleTailRequest, DisplayError> {
        self.conversation(id).map_err(failure)?;
        validate_id(id).map_err(failure)?;
        Ok(ConsoleTailRequest {
            root: self.root.clone(),
            directory: self.chat_directory.clone(),
            id: id.into(),
        })
    }
    pub(crate) fn latest_console_conversation(&self) -> Option<&Conversation> {
        self.conversations
            .iter()
            .filter(|conversation| !conversation.metadata.archived)
            .min_by(|a, b| {
                b.metadata.pinned.cmp(&a.metadata.pinned).then_with(|| {
                    b.updated_at
                        .cmp(&a.updated_at)
                        .then_with(|| a.id.cmp(&b.id))
                })
            })
    }
    pub fn child_source(
        &self,
        conversation_id: &str,
        child_id: &str,
    ) -> Result<ViewSource, DisplayError> {
        validate_id(conversation_id).map_err(failure)?;
        validate_id(child_id).map_err(failure)?;
        let path = self
            .child_directory
            .join(conversation_id)
            .join(format!("{child_id}.json"));
        let bytes = read_file(&self.root, &path, MAX_CHILD_BYTES as usize, &|| false)?;
        #[derive(Deserialize)]
        struct RecordId {
            id: String,
        }
        #[derive(Deserialize)]
        struct Document {
            version: u32,
            parent_chat: String,
            parent_run: String,
            record: RecordId,
        }
        let document: Document = serde_json::from_slice(&bytes).map_err(failure)?;
        if document.version != 1
            || document.parent_chat != conversation_id
            || document.record.id != child_id
        {
            return Err(DisplayError::Invalid("子历史归属无效".into()));
        }
        validate_id(&document.parent_run).map_err(failure)?;
        Ok(ViewSource::Child {
            conversation_id: conversation_id.into(),
            parent_run_id: document.parent_run,
            child_id: child_id.into(),
        })
    }
    /// The caller supplies the owning revision clock. A new manifest pins a new
    /// source generation; do not synthesize this stamp from a paged UI snapshot.
    pub fn open_history(
        &self,
        source: ViewSource,
        revision: u64,
        projection_revision: u64,
        check: impl Fn() -> bool,
    ) -> Result<CommittedHistory, DisplayError> {
        cancelled(&check)?;
        validate_id(source.conversation_id()).map_err(failure)?;
        let conversation = self
            .conversation(source.conversation_id())
            .map_err(failure)?
            .clone();
        match &source {
            ViewSource::Main { conversation_id } => {
                let manifest = self
                    .chat_directory
                    .join(format!("{conversation_id}.segments.json"));
                let manifest_present = match fs::symlink_metadata(&manifest) {
                    Ok(_) => true,
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
                    Err(error) => return Err(failure(error)),
                };
                if manifest_present {
                    let bytes = read_file(&self.root, &manifest, INDEX_BYTES, &check)?;
                    let generation = crate::legacy_migration::digest(&bytes);
                    let archive: ChatArchive = serde_json::from_slice(&bytes).map_err(failure)?;
                    if archive.version != 1
                        || archive.conversation.id != *conversation_id
                        || archive.message_count > 131_072
                        || archive.segments.len() > 131_072
                    {
                        return Err(DisplayError::Invalid("历史索引身份或预算无效".into()));
                    }
                    let mut headers = Vec::new();
                    let mut locations = Vec::new();
                    let mut total = 0u64;
                    let mut paths = HashSet::new();
                    let mut ids = HashSet::new();
                    let mut cached = None;
                    for (slot, segment) in archive.segments.iter().enumerate() {
                        cancelled(&check)?;
                        let prefix =
                            PathBuf::from(format!("conversation-segments/{conversation_id}"));
                        if !segment.path.starts_with(&prefix)
                            || segment
                                .path
                                .components()
                                .any(|part| !matches!(part, std::path::Component::Normal(_)))
                            || !paths.insert(&segment.path)
                            || segment.messages == 0
                            || segment.bytes > MESSAGE_BYTES as u64 + 1
                        {
                            return Err(DisplayError::Invalid("历史段路径或预算无效".into()));
                        }
                        let bytes = read_file(
                            &self.root,
                            &self.root.join(&segment.path),
                            MESSAGE_BYTES + 1,
                            &check,
                        )?;
                        total = total
                            .checked_add(bytes.len() as u64)
                            .ok_or(DisplayError::Budget)?;
                        if total > TRANSCRIPT_BYTES
                            || bytes.len() as u64 != segment.bytes
                            || crate::legacy_migration::digest(&bytes) != segment.sha256
                            || bytes.last() != Some(&b'\n')
                        {
                            return Err(DisplayError::Invalid(
                                "历史段长度、hash或行边界无效".into(),
                            ));
                        }
                        let before = headers.len();
                        let mut from = 0usize;
                        for line in bytes[..bytes.len() - 1].split(|&byte| byte == b'\n') {
                            cancelled(&check)?;
                            if line.is_empty()
                                || line.len() > MESSAGE_BYTES
                                || headers.len() >= 131_072
                            {
                                return Err(DisplayError::Budget);
                            }
                            let mut header: MessageHeader =
                                serde_json::from_slice(line).map_err(failure)?;
                            validate_id(&header.id).map_err(failure)?;
                            if !ids.insert(header.id.clone())
                                || header.tool_calls.len() > 16
                                || header.image_count() > 256
                            {
                                return Err(DisplayError::Invalid(
                                    "历史消息身份或附属数量无效".into(),
                                ));
                            }
                            header.message_hash = crate::legacy_migration::digest(line);
                            locations.push(Location {
                                segment: slot,
                                from,
                                to: from + line.len(),
                            });
                            headers.push(header);
                            from += line.len() + 1;
                        }
                        if headers.len() - before != segment.messages as usize
                            || headers.get(before).map(|header| &header.id)
                                != Some(&segment.first_id)
                            || headers.last().map(|header| &header.id) != Some(&segment.last_id)
                        {
                            return Err(DisplayError::Invalid("历史段消息身份边界不一致".into()));
                        }
                        cached = Some((slot, Arc::new(bytes)));
                    }
                    if headers.len() as u64 != archive.message_count {
                        return Err(DisplayError::Invalid("历史总消息数不一致".into()));
                    }
                    Ok(CommittedHistory {
                        source,
                        stamp: SourceStamp {
                            generation,
                            revision,
                            projection_revision,
                        },
                        headers,
                        root: self.root.clone(),
                        bodies: Bodies::Segments {
                            segments: archive.segments,
                            locations,
                        },
                        cache: Mutex::new(cached),
                        conversation: archive.conversation,
                        queued_turns: archive.queued_turns,
                    })
                } else {
                    let path = self.chat_directory.join(format!("{conversation_id}.json"));
                    let bytes = read_file(&self.root, &path, MAX_CHAT_BYTES as usize, &check)?;
                    #[derive(Deserialize)]
                    struct Document {
                        version: u32,
                        conversation: Conversation,
                        messages: Vec<Message>,
                        #[serde(default)]
                        queued_turns: Vec<QueuedTurn>,
                    }
                    let document: Document = serde_json::from_slice(&bytes).map_err(failure)?;
                    if document.version != 1 || document.conversation.id != *conversation_id {
                        return Err(DisplayError::Invalid("旧历史文档身份无效".into()));
                    }
                    Self::memory_history(
                        source,
                        SourceStamp {
                            generation: crate::legacy_migration::digest(&bytes),
                            revision,
                            projection_revision,
                        },
                        document.conversation,
                        document.messages,
                        document.queued_turns,
                        self.root.clone(),
                        &check,
                    )
                }
            }
            ViewSource::Child {
                conversation_id,
                parent_run_id,
                child_id,
            } => {
                validate_id(parent_run_id).map_err(failure)?;
                validate_id(child_id).map_err(failure)?;
                let path = self
                    .child_directory
                    .join(conversation_id)
                    .join(format!("{child_id}.json"));
                let bytes = read_file(&self.root, &path, MAX_CHILD_BYTES as usize, &check)?;
                #[derive(Deserialize)]
                struct Document {
                    version: u32,
                    parent_chat: String,
                    parent_run: String,
                    record: crate::subagents::Record,
                }
                let document: Document = serde_json::from_slice(&bytes).map_err(failure)?;
                if document.version != 1
                    || document.parent_chat != *conversation_id
                    || document.parent_run != *parent_run_id
                    || document.record.id != *child_id
                {
                    return Err(DisplayError::Invalid("子历史归属无效".into()));
                }
                Self::memory_history(
                    source,
                    SourceStamp {
                        generation: crate::legacy_migration::digest(&bytes),
                        revision,
                        projection_revision,
                    },
                    conversation,
                    document.record.messages,
                    Vec::new(),
                    self.root.clone(),
                    &check,
                )
            }
        }
    }
    fn memory_history(
        source: ViewSource,
        stamp: SourceStamp,
        conversation: Conversation,
        messages: Vec<Message>,
        queued_turns: Vec<QueuedTurn>,
        root: PathBuf,
        check: &impl Fn() -> bool,
    ) -> Result<CommittedHistory, DisplayError> {
        if messages.len() > 131_072 {
            return Err(DisplayError::Budget);
        }
        let mut headers = Vec::new();
        let mut bodies = Vec::new();
        let mut ids = HashSet::new();
        for message in messages {
            cancelled(check)?;
            if !ids.insert(message.id.clone()) {
                return Err(DisplayError::Invalid("历史身份重复".into()));
            }
            headers.push(MessageHeader::from_message(
                &message,
                message_fingerprint(&message)?,
            ));
            bodies.push(Arc::new(message));
        }
        Ok(CommittedHistory {
            source,
            stamp,
            headers,
            root,
            bodies: Bodies::Memory(bodies),
            cache: Mutex::new(None),
            conversation,
            queued_turns,
        })
    }
}
impl HistorySource for CommittedHistory {
    fn source(&self) -> &ViewSource {
        &self.source
    }
    fn stamp(&self) -> &SourceStamp {
        &self.stamp
    }
    fn headers(&self) -> &[MessageHeader] {
        &self.headers
    }
    fn with_message<R>(
        &self,
        index: usize,
        check: &impl Fn() -> bool,
        read: impl FnOnce(&Message) -> Result<R, DisplayError>,
    ) -> Result<R, DisplayError> {
        cancelled(check)?;
        match &self.bodies {
            Bodies::Memory(messages) => {
                let message = messages.get(index).ok_or(DisplayError::Missing)?;
                if message_fingerprint(message)?
                    != self
                        .headers
                        .get(index)
                        .ok_or(DisplayError::Stale)?
                        .message_hash
                {
                    return Err(DisplayError::Stale);
                }
                cancelled(check)?;
                read(message)
            }
            Bodies::Segments {
                segments,
                locations,
            } => {
                let location = locations.get(index).ok_or(DisplayError::Missing)?;
                let segment = &segments[location.segment];
                let cached = self
                    .cache
                    .lock()
                    .map_err(|_| DisplayError::Invalid("历史缓存不可用".into()))?
                    .as_ref()
                    .filter(|(slot, _)| *slot == location.segment)
                    .map(|(_, bytes)| bytes.clone());
                let bytes = if let Some(bytes) = cached {
                    bytes
                } else {
                    let bytes = read_file(
                        &self.root,
                        &self.root.join(&segment.path),
                        MESSAGE_BYTES + 1,
                        check,
                    )?;
                    if bytes.len() as u64 != segment.bytes
                        || crate::legacy_migration::digest(&bytes) != segment.sha256
                    {
                        return Err(DisplayError::Stale);
                    }
                    let bytes = Arc::new(bytes);
                    *self
                        .cache
                        .lock()
                        .map_err(|_| DisplayError::Invalid("历史缓存不可用".into()))? =
                        Some((location.segment, bytes.clone()));
                    bytes
                };
                let line = bytes
                    .get(location.from..location.to)
                    .ok_or(DisplayError::Stale)?;
                if crate::legacy_migration::digest(line) != self.headers[index].message_hash {
                    return Err(DisplayError::Stale);
                }
                let message: Message = serde_json::from_slice(line).map_err(failure)?;
                if message.id != self.headers[index].id {
                    return Err(DisplayError::Stale);
                }
                cancelled(check)?;
                read(&message)
            }
        }
    }
}

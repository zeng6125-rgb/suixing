//! Bounded conversation diagnostics. The actor freezes identity; one worker reads files.
use crate::{network::CancelToken, state::Conversation, store::Store};
use serde::{
    Deserialize, Deserializer,
    de::{IgnoredAny, SeqAccess, Visitor},
};
use std::{
    collections::HashSet,
    fmt,
    fs::{self, Metadata, OpenOptions},
    io::Read,
    path::{Component, Path, PathBuf},
    sync::{
        Arc,
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    time::{Duration, Instant, UNIX_EPOCH},
};

pub const LOG_BYTES: usize = 32 * 1024;
const INDEX_BYTES: usize = 8 * 1024 * 1024;
const DOCUMENT_BYTES: usize = 32 * 1024 * 1024;
const SEGMENT_BYTES: usize = 20 * 1024 * 1024 + 1;

#[derive(Clone)]
pub struct Target {
    pub root: PathBuf,
    pub document: PathBuf,
    pub manifest: PathBuf,
    pub conversation: Conversation,
}
pub enum Request {
    Open {
        request_id: String,
        conversation_id: String,
    },
    Cancel {
        request_id: String,
    },
}
impl Request {
    pub fn identity(&self) -> &str {
        match self {
            Self::Open { request_id, .. } | Self::Cancel { request_id } => request_id,
        }
    }
}
#[derive(Clone, Debug)]
pub struct FileInfo {
    pub path: String,
    pub bytes: u64,
    pub modified_at: Option<u64>,
}
#[derive(Clone, Debug)]
pub struct Info {
    pub conversation_id: String,
    pub message_count: u64,
    pub total_bytes: u64,
    pub segmented: bool,
    pub index: FileInfo,
    pub segment_count: usize,
    pub log_path: String,
    pub log_from: u64,
    pub log: String,
}
#[derive(Clone, Debug)]
pub enum Update {
    Ready { request_id: String, info: Info },
    Failed { request_id: String, error: String },
}
impl Update {
    pub fn bytes(&self) -> usize {
        match self {
            Self::Ready { request_id, info } => {
                request_id.len()
                    + info.index.path.len()
                    + info.log_path.len()
                    + info.log.len()
                    + 2048
            }
            Self::Failed { request_id, error } => request_id.len() + error.len() + 512,
        }
    }
}
struct Job {
    request_id: String,
    target: Target,
    cancel: Arc<CancelToken>,
}
struct Completion {
    request_id: String,
    root: PathBuf,
    conversation_id: String,
    result: Result<Info, String>,
}
pub struct Runtime {
    send: SyncSender<Job>,
    receive: Receiver<Completion>,
    pending: Option<(String, Arc<CancelToken>)>,
}
impl Runtime {
    pub fn boot() -> Result<Self, String> {
        let (send, work) = mpsc::sync_channel::<Job>(2);
        // Only the actor can enqueue work and poll its result. At most two accepted
        // jobs plus one running job exist; each result contains one 32 KiB log window.
        let (complete, receive) = mpsc::channel::<Completion>();
        std::thread::Builder::new()
            .name("rust-chat-info".into())
            .spawn(move || {
                while let Ok(job) = work.recv() {
                    let result = inspect(&job.target, &job.cancel);
                    if complete
                        .send(Completion {
                            request_id: job.request_id,
                            root: job.target.root,
                            conversation_id: job.target.conversation.id,
                            result,
                        })
                        .is_err()
                    {
                        break;
                    }
                }
            })
            .map_err(|_| "无法创建会话详情线程")?;
        Ok(Self {
            send,
            receive,
            pending: None,
        })
    }
    pub fn dispatch(&mut self, request: Request, store: &Store) -> Result<(), String> {
        let id = request.identity();
        if id.is_empty() || id.len() > 128 || id.contains('\0') {
            return Err("会话详情请求ID无效".into());
        }
        match request {
            Request::Cancel { request_id } => {
                if self
                    .pending
                    .as_ref()
                    .is_some_and(|(id, _)| id == &request_id)
                {
                    if let Some((_, cancel)) = self.pending.take() {
                        cancel.cancel();
                    }
                }
                Ok(())
            }
            Request::Open {
                request_id,
                conversation_id,
            } => {
                let target = store.conversation_info_target(&conversation_id)?;
                let cancel = Arc::new(CancelToken::new());
                let job = Job {
                    request_id: request_id.clone(),
                    target,
                    cancel: cancel.clone(),
                };
                self.send
                    .try_send(job)
                    .map_err(|_| "会话详情读取仍在处理，请稍后重试")?;
                if let Some((_, old)) = self.pending.replace((request_id, cancel)) {
                    old.cancel();
                }
                Ok(())
            }
        }
    }
    pub fn poll(&self, store: &Store) -> Vec<Update> {
        let mut updates = Vec::new();
        for _ in 0..4 {
            let completed = match self.receive.try_recv() {
                Ok(value) => value,
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            };
            if self
                .pending
                .as_ref()
                .is_none_or(|(id, cancel)| id != &completed.request_id || cancel.is_cancelled())
            {
                continue;
            }
            let result = store
                .conversation_info_target(&completed.conversation_id)
                .and_then(|current| {
                    if current.root != completed.root {
                        Err("会话存储代已切换，请重新读取详情".into())
                    } else {
                        completed.result
                    }
                });
            updates.push(match result {
                Ok(info) => Update::Ready {
                    request_id: completed.request_id,
                    info,
                },
                Err(error) => Update::Failed {
                    request_id: completed.request_id,
                    error,
                },
            });
        }
        updates
    }
}
impl Drop for Runtime {
    fn drop(&mut self) {
        if let Some((_, cancel)) = self.pending.take() {
            cancel.cancel();
        }
    }
}

fn check(cancel: &CancelToken, deadline: Instant) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err("会话详情读取已取消".into())
    } else if Instant::now() >= deadline {
        Err("会话详情读取超时".into())
    } else {
        Ok(())
    }
}
fn regular(root: &Path, path: &Path) -> Result<Metadata, String> {
    let relative = path.strip_prefix(root).map_err(|_| "会话详情路径越界")?;
    if relative.as_os_str().is_empty() {
        return Err("会话详情文件路径为空".into());
    }
    let mut cursor = root.to_owned();
    let root_metadata = fs::symlink_metadata(root).map_err(|_| "会话状态目录无法读取")?;
    if !root_metadata.is_dir()
        || root_metadata.file_type().is_symlink()
        || fs::canonicalize(root).map_err(|_| "会话状态目录解析失败")? != root
    {
        return Err("会话状态目录身份无效".into());
    }
    let mut last = None;
    for component in relative.components() {
        let Component::Normal(name) = component else {
            return Err("会话详情路径包含异常分量".into());
        };
        cursor.push(name);
        let metadata = fs::symlink_metadata(&cursor).map_err(|_| "会话详情文件或目录缺失")?;
        if metadata.file_type().is_symlink() || !(metadata.is_dir() || metadata.is_file()) {
            return Err("会话详情路径包含链接或特殊文件".into());
        }
        last = Some(metadata);
    }
    let metadata = last.ok_or("会话详情文件不存在")?;
    if !metadata.is_file() || fs::canonicalize(path).map_err(|_| "会话详情路径解析失败")? != path
    {
        return Err("会话详情目标不是原普通文件".into());
    }
    Ok(metadata)
}
fn same(a: &Metadata, b: &Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.size() == b.size()
            && a.mtime() == b.mtime()
            && a.mtime_nsec() == b.mtime_nsec()
            && a.ctime() == b.ctime()
            && a.ctime_nsec() == b.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        a.len() == b.len() && a.modified().ok() == b.modified().ok()
    }
}
fn read(
    root: &Path,
    path: &Path,
    max: usize,
    cancel: &CancelToken,
    deadline: Instant,
) -> Result<(Vec<u8>, Metadata), String> {
    check(cancel, deadline)?;
    let named = regular(root, path)?;
    if named.len() > max as u64 {
        return Err("会话详情文件超过读取预算".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let mut file = options.open(path).map_err(|_| "会话详情文件打开失败")?;
    let opened = file.metadata().map_err(|_| "会话详情文件状态读取失败")?;
    if !same(&named, &opened) {
        return Err("会话详情文件在打开时变化".into());
    }
    let mut bytes = Vec::with_capacity((opened.len() as usize).min(max));
    let mut buffer = [0u8; 65536];
    loop {
        check(cancel, deadline)?;
        let n = file.read(&mut buffer).map_err(|_| "会话详情文件读取失败")?;
        if n == 0 {
            break;
        }
        if bytes.len() + n > max {
            return Err("会话详情文件读取预算已用完".into());
        }
        bytes.extend_from_slice(&buffer[..n]);
    }
    let after = file.metadata().map_err(|_| "会话详情文件复核失败")?;
    let named_after = regular(root, path)?;
    if bytes.len() as u64 != opened.len() || !same(&opened, &after) || !same(&after, &named_after) {
        return Err("会话详情文件在读取期间变化，请重试".into());
    }
    Ok((bytes, after))
}
fn file_info(path: &Path, metadata: &Metadata) -> FileInfo {
    FileInfo {
        path: path.display().to_string(),
        bytes: metadata.len(),
        modified_at: metadata
            .modified()
            .ok()
            .and_then(|at| at.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis().min(u64::MAX as u128) as u64),
    }
}
#[derive(Deserialize)]
struct Identity {
    id: String,
}
#[derive(Deserialize)]
struct Index {
    version: u32,
    conversation: Identity,
    message_count: u64,
    segments: Vec<crate::legacy_migration::ChatSegment>,
}
struct Count(u64);
impl<'de> Deserialize<'de> for Count {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Counter;
        impl<'de> Visitor<'de> for Counter {
            type Value = Count;
            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("完整消息数组")
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut values: A) -> Result<Count, A::Error> {
                let mut count = 0u64;
                while values.next_element::<IgnoredAny>()?.is_some() {
                    count += 1;
                    if count > 131072 {
                        return Err(serde::de::Error::custom("会话消息超过预算"));
                    }
                }
                Ok(Count(count))
            }
        }
        deserializer.deserialize_seq(Counter)
    }
}
#[derive(Deserialize)]
struct Document {
    version: u32,
    conversation: Identity,
    messages: Count,
}
fn inspect(target: &Target, cancel: &CancelToken) -> Result<Info, String> {
    let deadline = Instant::now() + Duration::from_secs(30);
    if target.manifest.extension().is_some_and(|ext| ext == "meta") {
        let (bytes, metadata) = read(
            &target.root,
            &target.manifest,
            INDEX_BYTES,
            cancel,
            deadline,
        )?;
        let value: serde_json::Value =
            serde_json::from_slice(&bytes).map_err(|_| "会话元数据无法解析")?;
        let (path, from, log) = crate::store::shared_chat::log_tail(
            &target.root,
            &target.conversation.id,
            LOG_BYTES as u64,
        )?;
        let (after, _) = read(
            &target.root,
            &target.manifest,
            INDEX_BYTES,
            cancel,
            deadline,
        )?;
        if bytes != after {
            return Err("读取期间会话已提交新内容，请重新读取详情".into());
        }
        let start = log
            .iter()
            .position(|byte| byte & 0b1100_0000 != 0b1000_0000)
            .unwrap_or(log.len());
        let text = std::str::from_utf8(&log[start..])
            .map_err(|_| "会话日志不是有效UTF-8")?
            .to_owned();
        return Ok(Info {
            conversation_id: target.conversation.id.clone(),
            message_count: value["messageCount"].as_u64().unwrap_or(0),
            total_bytes: metadata.len() + value["logBytes"].as_u64().unwrap_or(0),
            segmented: false,
            index: file_info(&target.manifest, &metadata),
            segment_count: 1,
            log_path: path.display().to_string(),
            log_from: from + start as u64,
            log: text,
        });
    }
    let segmented = match fs::symlink_metadata(&target.manifest) {
        Ok(_) => true,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(_) => return Err("无法检查会话历史索引".into()),
    };
    let index_path = if segmented {
        &target.manifest
    } else {
        &target.document
    };
    let (bytes, metadata) = read(
        &target.root,
        index_path,
        if segmented {
            INDEX_BYTES
        } else {
            DOCUMENT_BYTES
        },
        cancel,
        deadline,
    )?;
    let index_hash = crate::legacy_migration::digest(&bytes);
    let (count, total, segment_count, log_path, log_bytes) = if segmented {
        let index: Index = serde_json::from_slice(&bytes).map_err(|_| "会话索引无法解析")?;
        if index.version != 1
            || index.conversation.id != target.conversation.id
            || index.message_count > 131072
            || index.segments.len() > 131072
        {
            return Err("会话索引身份或条数预算无效".into());
        }
        let mut total = metadata.len();
        let mut messages = 0u64;
        let mut names = HashSet::new();
        for segment in &index.segments {
            check(cancel, deadline)?;
            let prefix = PathBuf::from(format!("conversation-segments/{}", target.conversation.id));
            if !segment.path.starts_with(prefix)
                || segment
                    .path
                    .components()
                    .any(|part| !matches!(part, Component::Normal(_)))
                || !names.insert(segment.path.clone())
                || segment.bytes > SEGMENT_BYTES as u64
                || segment.messages == 0
            {
                return Err("会话历史段路径或预算无效".into());
            }
            let segment_metadata = regular(&target.root, &target.root.join(&segment.path))?;
            if segment_metadata.len() != segment.bytes {
                return Err("会话段的实际大小与索引不一致".into());
            }
            total = total.checked_add(segment.bytes).ok_or("会话历史大小溢出")?;
            messages = messages
                .checked_add(segment.messages)
                .ok_or("会话消息条数溢出")?;
            if total > 512 * 1024 * 1024 + INDEX_BYTES as u64 {
                return Err("会话历史总大小超过预算".into());
            }
        }
        if messages != index.message_count {
            return Err("会话索引消息计数不一致".into());
        }
        let (path, tail) = if let Some(last) = index.segments.last() {
            let path = target.root.join(&last.path);
            let (data, _) = read(&target.root, &path, SEGMENT_BYTES, cancel, deadline)?;
            if crate::legacy_migration::digest(&data) != last.sha256 {
                return Err("会话最后一段的内容hash与提交索引不一致".into());
            }
            (path, data)
        } else {
            (index_path.clone(), bytes.clone())
        };
        (index.message_count, total, index.segments.len(), path, tail)
    } else {
        let document: Document = serde_json::from_slice(&bytes).map_err(|_| "会话文档无法解析")?;
        if document.version != 1 || document.conversation.id != target.conversation.id {
            return Err("会话文档身份无效".into());
        }
        (
            document.messages.0,
            metadata.len(),
            0,
            index_path.clone(),
            bytes.clone(),
        )
    };
    let (rechecked, _) = read(
        &target.root,
        index_path,
        if segmented {
            INDEX_BYTES
        } else {
            DOCUMENT_BYTES
        },
        cancel,
        deadline,
    )?;
    if crate::legacy_migration::digest(&rechecked) != index_hash {
        return Err("读取期间会话已提交新内容，请重新读取详情".into());
    }
    let from = log_bytes.len().saturating_sub(LOG_BYTES);
    let mut start = from;
    while start < log_bytes.len() && (log_bytes[start] & 0b1100_0000) == 0b1000_0000 {
        start += 1;
    }
    let log = std::str::from_utf8(&log_bytes[start..])
        .map_err(|_| "会话日志不是有效UTF-8")?
        .to_owned();
    Ok(Info {
        conversation_id: target.conversation.id.clone(),
        message_count: count,
        total_bytes: total,
        segmented,
        index: file_info(index_path, &metadata),
        segment_count,
        log_path: log_path.display().to_string(),
        log_from: start as u64,
        log,
    })
}

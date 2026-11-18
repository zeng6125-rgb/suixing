//! Read-only paging of immutable legacy process evidence in an active migration.
//! No method creates a runnable process, grants a task or interprets old PIDs.
use crate::legacy_processes::{JobArtifact, LogArchive, Phase, TerminalArtifact};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File, Metadata, OpenOptions},
    io::{Read, Seek, SeekFrom},
    path::{Component, Path, PathBuf},
    sync::{Arc, Mutex},
};
const MAX_MANIFEST: u64 = 64 * 1024 * 1024;
const MAX_RECORD: u64 = 512 * 1024;
const MAX_PAGE_BYTES: usize = 64 * 1024;
const MAX_PAGE_ROWS: usize = 16;
const MAX_WINDOW: usize = 64 * 1024;
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Generation {
    pub id: String,
    pub manifest_sha256: String,
    pub ui_token: String,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum Kind {
    Job,
    Terminal,
}
impl Kind {
    fn directory(self) -> &'static str {
        match self {
            Self::Job => "legacy-jobs",
            Self::Terminal => "legacy-terminals",
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Key {
    pub generation: Generation,
    pub kind: Kind,
    pub id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PageCursor {
    pub generation: Generation,
    pub kind: Kind,
    pub after_id: String,
}
#[derive(Clone, Debug)]
pub enum Request {
    List {
        request_id: String,
        generation: Generation,
        kind: Kind,
        cursor: Option<PageCursor>,
        max_rows: usize,
    },
    Detail {
        request_id: String,
        key: Key,
    },
    Log {
        request_id: String,
        key: Key,
        cursor_domain: String,
        since: u64,
        max_bytes: usize,
    },
}
#[derive(Clone, Debug)]
pub enum Update {
    List {
        request_id: String,
        generation: Generation,
        kind: Kind,
        result: Result<Page, String>,
    },
    Detail {
        request_id: String,
        key: Key,
        result: Result<Detail, String>,
    },
    Log {
        request_id: String,
        key: Key,
        result: Result<Window, String>,
    },
}
#[derive(Clone, Debug, Serialize)]
pub struct Row {
    pub key: Key,
    pub source_id: String,
    pub title: String,
    pub phase: Phase,
    pub exit_code: Option<i32>,
    pub owner_chat_id: Option<String>,
    pub has_log: bool,
    pub cursor_domain: String,
    pub retained_from: u64,
    pub end: u64,
    pub earlier_history_lost: Option<bool>,
    pub summary_truncated: bool,
}
#[derive(Clone, Debug, Serialize)]
pub struct Page {
    pub rows: Vec<Row>,
    pub total: usize,
    pub next: Option<PageCursor>,
}
#[derive(Clone, Debug, Serialize)]
pub enum Artifact {
    Job(JobArtifact),
    Terminal(TerminalArtifact),
}
#[derive(Clone, Debug, Serialize)]
pub struct Detail {
    pub key: Key,
    pub artifact: Artifact,
    pub document: Value,
}
#[derive(Clone, Debug)]
pub struct Window {
    pub bytes: Vec<u8>,
    pub from: u64,
    pub next_offset: u64,
    pub retained_from: u64,
    pub end: u64,
    pub has_more: bool,
    pub cursor_domain: String,
    pub earlier_history_lost: Option<bool>,
}
#[derive(Deserialize)]
struct Pointer {
    version: u32,
    id: String,
    manifest_sha256: String,
}
#[derive(Deserialize)]
struct Manifest {
    version: u32,
    id: String,
    files: Vec<Proof>,
}
#[derive(Clone, Deserialize)]
struct Proof {
    path: PathBuf,
    bytes: u64,
    sha256: String,
}
struct Cached {
    file: File,
    stamp: Metadata,
}
struct Cache {
    entries: BTreeMap<PathBuf, (Arc<Mutex<Cached>>, u64)>,
    tick: u64,
}
pub struct Repository {
    namespace_path: PathBuf,
    root: File,
    state: File,
    generation: Generation,
    complete_stamp: Metadata,
    proofs: BTreeMap<PathBuf, Proof>,
    jobs: Vec<String>,
    terminals: Vec<String>,
    cache: Mutex<Cache>,
}
impl Repository {
    /// Invoke on an existing bounded worker. This reads only pointer/manifest
    /// and archive evidence, not all active mutable state documents or old roots.
    pub fn open(namespace_path: PathBuf, ui_token: String) -> Result<Self, String> {
        id(&ui_token)?;
        let root = open_root(&namespace_path)?;
        let namespace_path =
            fs::canonicalize(&namespace_path).map_err(|_| "归档namespace不可解析")?;
        let pointer = pointer(&root)?;
        let (generation_dir, state) = open_generation(&root, &pointer.id)?;
        let complete = open_file(&generation_dir, "complete.json")?;
        let complete_stamp = complete.metadata().map_err(|_| "归档完成清单信息不可读")?;
        let bytes = read_bounded(complete, MAX_MANIFEST)?;
        if hash(&bytes) != pointer.manifest_sha256 {
            return Err("归档完成清单hash不匹配".into());
        }
        let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|_| "归档完成清单损坏")?;
        if manifest.version != 1 || manifest.id != pointer.id || manifest.files.len() > 262144 {
            return Err("归档完成清单身份或预算无效".into());
        }
        let mut proofs = BTreeMap::new();
        let mut jobs = Vec::new();
        let mut terminals = Vec::new();
        for mut proof in manifest.files {
            let Ok(relative) = proof.path.strip_prefix("state") else {
                continue;
            };
            let kind = if relative.starts_with("legacy-jobs") {
                Kind::Job
            } else if relative.starts_with("legacy-terminals") {
                Kind::Terminal
            } else {
                continue;
            };
            let parts: Vec<_> = relative.components().collect();
            if parts.len() != 2
                || parts
                    .iter()
                    .any(|part| !matches!(part, Component::Normal(_)))
                || !hex(&proof.sha256)
            {
                return Err("归档目标清单路径或hash无效".into());
            }
            let name = relative
                .file_name()
                .and_then(|name| name.to_str())
                .ok_or("归档文件名无效")?;
            let metadata = name.ends_with(".json");
            if metadata {
                let record_id = name.strip_suffix(".json").ok_or("归档记录后缀无效")?;
                record_identity(record_id)?;
                if proof.bytes > MAX_RECORD {
                    return Err("归档单条记录超过512 KiB".into());
                }
                match kind {
                    Kind::Job => jobs.push(record_id.into()),
                    Kind::Terminal => terminals.push(record_id.into()),
                }
            } else {
                let valid = match kind {
                    Kind::Job => {
                        name.strip_suffix(".log")
                            .is_some_and(|name| record_identity(name).is_ok())
                            && proof.bytes <= 32 * 1024 * 1024
                    }
                    Kind::Terminal => {
                        name.strip_suffix(".0.raw")
                            .or_else(|| name.strip_suffix(".1.raw"))
                            .is_some_and(|name| record_identity(name).is_ok())
                            && proof.bytes <= 1024 * 1024
                    }
                };
                if !valid {
                    return Err("归档日志名称或原日志段预算无效".into());
                }
            }
            proof.path = relative.to_path_buf();
            if proofs.insert(proof.path.clone(), proof).is_some() {
                return Err("归档完成清单有重复文件".into());
            }
            if proofs.len() > 640 {
                return Err("归档文件清单超过640个有界条目".into());
            }
        }
        if jobs.len() > 128 || terminals.len() > 128 {
            return Err("旧Job或Terminal归档各超过128条预算；未静默省略记录".into());
        }
        jobs.sort();
        terminals.sort();
        let generation = Generation {
            id: pointer.id,
            manifest_sha256: pointer.manifest_sha256,
            ui_token,
        };
        let repository = Self {
            namespace_path,
            root,
            state,
            generation,
            complete_stamp,
            proofs,
            jobs,
            terminals,
            cache: Mutex::new(Cache {
                entries: BTreeMap::new(),
                tick: 0,
            }),
        };
        repository.current()?;
        Ok(repository)
    }
    pub fn generation(&self) -> Generation {
        self.generation.clone()
    }
    /// Every result echoes both request and generation identities. Root/UI must
    /// also discard a late result after changing its own ui_token or selection.
    pub fn dispatch(&self, request: Request) -> Update {
        match request {
            Request::List {
                request_id,
                generation,
                kind,
                cursor,
                max_rows,
            } => {
                let result = id(&request_id)
                    .and_then(|()| self.check(&generation))
                    .and_then(|()| self.page(kind, cursor, max_rows));
                Update::List {
                    request_id,
                    generation,
                    kind,
                    result,
                }
            }
            Request::Detail { request_id, key } => {
                let result = id(&request_id).and_then(|()| self.detail(&key));
                Update::Detail {
                    request_id,
                    key,
                    result,
                }
            }
            Request::Log {
                request_id,
                key,
                cursor_domain,
                since,
                max_bytes,
            } => {
                let result =
                    id(&request_id).and_then(|()| self.log(&key, &cursor_domain, since, max_bytes));
                Update::Log {
                    request_id,
                    key,
                    result,
                }
            }
        }
    }
    fn check(&self, generation: &Generation) -> Result<(), String> {
        if generation != &self.generation {
            return Err("归档请求属于旧UI或另一迁移代，未读取新代文件".into());
        }
        self.current()
    }
    fn current(&self) -> Result<(), String> {
        let root_now =
            fs::symlink_metadata(&self.namespace_path).map_err(|_| "归档namespace路径已失效")?;
        if !root_now.is_dir()
            || root_now.file_type().is_symlink()
            || !same_identity(
                &root_now,
                &self
                    .root
                    .metadata()
                    .map_err(|_| "归档namespace句柄不可读")?,
            )
        {
            return Err("归档namespace目录身份已变化".into());
        }
        let pointer = pointer(&self.root)?;
        if pointer.id != self.generation.id
            || pointer.manifest_sha256 != self.generation.manifest_sha256
        {
            return Err("活动迁移代已变化，归档结果失效".into());
        }
        let (generation, state_now) = open_generation(&self.root, &pointer.id)?;
        if !same_identity(
            &state_now.metadata().map_err(|_| "归档状态目录信息不可读")?,
            &self.state.metadata().map_err(|_| "归档状态句柄不可读")?,
        ) {
            return Err("活动归档状态目录身份已变化".into());
        }
        let complete = open_file(&generation, "complete.json")?
            .metadata()
            .map_err(|_| "归档完成清单信息不可读")?;
        if !same_file(&complete, &self.complete_stamp) {
            return Err("归档完成清单读取边界已变化".into());
        }
        Ok(())
    }
    pub fn page(
        &self,
        kind: Kind,
        cursor: Option<PageCursor>,
        max_rows: usize,
    ) -> Result<Page, String> {
        self.current()?;
        if !(1..=MAX_PAGE_ROWS).contains(&max_rows) {
            return Err("归档分页最多16条".into());
        }
        let ids = match kind {
            Kind::Job => &self.jobs,
            Kind::Terminal => &self.terminals,
        };
        let start = match cursor {
            None => 0,
            Some(cursor) => {
                if cursor.generation != self.generation || cursor.kind != kind {
                    return Err("归档分页游标属于另一代或类型".into());
                }
                record_identity(&cursor.after_id)?;
                ids.binary_search(&cursor.after_id)
                    .map_err(|_| "归档分页游标记录不再存在")?
                    + 1
            }
        };
        let mut rows = Vec::new();
        let mut next_index = start;
        for record_id in ids.iter().skip(start).take(max_rows) {
            let key = Key {
                generation: self.generation.clone(),
                kind,
                id: record_id.clone(),
            };
            let detail = self.detail(&key)?;
            let row = summary(&detail);
            rows.push(row);
            if crate::composer::json_bytes(&rows, MAX_PAGE_BYTES).is_err() {
                rows.pop();
                break;
            }
            next_index += 1;
        }
        if rows.is_empty() && start < ids.len() {
            return Err("归档摘要单行超过64 KiB预算".into());
        }
        self.current()?;
        let next = if next_index < ids.len() {
            rows.last().map(|row| PageCursor {
                generation: self.generation.clone(),
                kind,
                after_id: row.key.id.clone(),
            })
        } else {
            None
        };
        Ok(Page {
            rows,
            total: ids.len(),
            next,
        })
    }
    pub fn detail(&self, key: &Key) -> Result<Detail, String> {
        self.check(&key.generation)?;
        record_identity(&key.id)?;
        let path = PathBuf::from(format!("{}/{}.json", key.kind.directory(), key.id));
        let file = self.verified(&path)?;
        let mut file = file.lock().unwrap_or_else(|error| error.into_inner());
        file.file
            .seek(SeekFrom::Start(0))
            .map_err(|_| "归档记录定位失败")?;
        let bytes = read_bounded(
            file.file.try_clone().map_err(|_| "归档记录句柄复制失败")?,
            MAX_RECORD,
        )?;
        let after = file
            .file
            .metadata()
            .map_err(|_| "归档记录读取后信息不可读")?;
        if !same_file(&file.stamp, &after) {
            return Err("归档记录在读取期间变化".into());
        }
        drop(file);
        self.unchanged(&path, &after)?;
        let document: Value = serde_json::from_slice(&bytes).map_err(|_| "归档记录JSON损坏")?;
        let artifact = match key.kind {
            Kind::Job => {
                let artifact: JobArtifact =
                    serde_json::from_value(document.clone()).map_err(|_| "旧Job归档结构无效")?;
                if artifact.id != key.id
                    || artifact.phase != Phase::InterruptedUnknown
                    || artifact.command.is_some()
                    || artifact.started.is_some()
                    || artifact.exit_code.is_some()
                    || artifact.owner_chat_id.is_some()
                {
                    return Err("旧JobStore只有日志，不能恢复未持久化的命令、状态或所属任务".into());
                }
                Artifact::Job(artifact)
            }
            Kind::Terminal => {
                let artifact: TerminalArtifact = serde_json::from_value(document.clone())
                    .map_err(|_| "旧Terminal归档结构无效")?;
                if artifact.id != key.id || artifact.pid_restored {
                    return Err("旧终端归档身份无效或试图恢复PID".into());
                }
                Artifact::Terminal(artifact)
            }
        };
        let result = Detail {
            key: key.clone(),
            artifact,
            document,
        };
        crate::composer::json_bytes(&result, 2 * 1024 * 1024)?;
        self.current()?;
        Ok(result)
    }
    pub fn log(&self, key: &Key, domain: &str, since: u64, max: usize) -> Result<Window, String> {
        let detail = self.detail(key)?;
        let log = match &detail.artifact {
            Artifact::Job(artifact) => &artifact.log,
            Artifact::Terminal(artifact) => &artifact.log,
        };
        validate_log(key, log)?;
        if domain != log.cursor_domain
            || !(1..=MAX_WINDOW).contains(&max)
            || since < log.retained_from
            || since > log.end
        {
            return Err("旧日志域或字节游标越界，未复用实时UTF-16游标".into());
        }
        let mut bytes = Vec::new();
        let mut cursor = since;
        for segment in &log.segments {
            let end = segment
                .from
                .checked_add(segment.bytes)
                .ok_or("旧日志段边界溢出")?;
            if cursor >= end {
                continue;
            }
            if cursor < segment.from {
                return Err("旧日志归档存在未声明间隙".into());
            }
            let proof = self
                .proofs
                .get(&segment.path)
                .ok_or("旧日志段没有本迁移代的完成证明")?;
            if proof.bytes != segment.bytes || proof.sha256 != segment.source.copied_sha256 {
                return Err("旧日志段与来源复制证明不一致".into());
            }
            let reader = self.verified(&segment.path)?;
            let mut reader = reader.lock().unwrap_or_else(|error| error.into_inner());
            reader
                .file
                .seek(SeekFrom::Start(cursor - segment.from))
                .map_err(|_| "旧日志窗口定位失败")?;
            let count = (end - cursor).min((max - bytes.len()) as u64) as usize;
            let start = bytes.len();
            bytes.resize(start + count, 0);
            reader
                .file
                .read_exact(&mut bytes[start..])
                .map_err(|_| "旧日志窗口读取失败")?;
            let after = reader
                .file
                .metadata()
                .map_err(|_| "旧日志窗口读取后信息不可读")?;
            if !same_file(&reader.stamp, &after) {
                return Err("旧日志文件在窗口读取期间变化".into());
            }
            self.unchanged(&segment.path, &after)?;
            cursor += count as u64;
            if bytes.len() == max {
                break;
            }
        }
        self.current()?;
        Ok(Window {
            bytes,
            from: since,
            next_offset: cursor,
            retained_from: log.retained_from,
            end: log.end,
            has_more: cursor < log.end,
            cursor_domain: domain.into(),
            earlier_history_lost: log.earlier_history_lost,
        })
    }
    fn unchanged(&self, path: &Path, expected: &Metadata) -> Result<(), String> {
        let proof = self.proofs.get(path).ok_or("归档读取路径无完成证明")?;
        let parent = open_directory(
            &self.state,
            path.parent()
                .and_then(Path::to_str)
                .ok_or("归档读取父目录无效")?,
        )?;
        let file = open_file(
            &parent,
            path.file_name()
                .and_then(|name| name.to_str())
                .ok_or("归档读取文件名无效")?,
        )?;
        let actual = file.metadata().map_err(|_| "归档读取后文件信息不可读")?;
        if actual.len() != proof.bytes || !same_file(expected, &actual) {
            return Err("归档文件路径在读取期间被替换或改成链接".into());
        }
        Ok(())
    }
    fn verified(&self, path: &Path) -> Result<Arc<Mutex<Cached>>, String> {
        let proof = self
            .proofs
            .get(path)
            .ok_or("归档文件不在已验证完成清单中")?;
        let parts: Vec<_> = path.components().collect();
        if parts.len() != 2
            || parts
                .iter()
                .any(|part| !matches!(part, Component::Normal(_)))
        {
            return Err("归档文件路径越界".into());
        }
        let parent = open_directory(
            &self.state,
            parts[0].as_os_str().to_str().ok_or("归档目录不是UTF-8")?,
        )?;
        let mut file = open_file(
            &parent,
            parts[1].as_os_str().to_str().ok_or("归档文件不是UTF-8")?,
        )?;
        let before = file.metadata().map_err(|_| "归档文件信息不可读")?;
        if !before.is_file() || before.len() != proof.bytes {
            return Err("归档文件不是已提交的普通文件或长度变化".into());
        }
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        cache.tick = cache.tick.wrapping_add(1);
        let tick = cache.tick;
        if let Some((cached, used)) = cache.entries.get_mut(path) {
            let stamp = cached
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .stamp
                .clone();
            if same_file(&stamp, &before) {
                *used = tick;
                return Ok(cached.clone());
            }
        }
        drop(cache);
        let mut hash_state = Sha256::new();
        let mut count = 0u64;
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let read = file.read(&mut buffer).map_err(|_| "归档文件hash读取失败")?;
            if read == 0 {
                break;
            }
            count = count
                .checked_add(read as u64)
                .filter(|count| *count <= proof.bytes)
                .ok_or("归档文件hash读取超出完成边界")?;
            hash_state.update(&buffer[..read]);
        }
        let after = file
            .metadata()
            .map_err(|_| "归档文件hash读取后信息不可读")?;
        if count != proof.bytes
            || format!("{:x}", hash_state.finalize()) != proof.sha256
            || !same_file(&before, &after)
        {
            return Err("归档文件hash、身份或长度不一致".into());
        }
        let cached = Arc::new(Mutex::new(Cached { file, stamp: after }));
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        cache
            .entries
            .insert(path.to_path_buf(), (cached.clone(), tick));
        while cache.entries.len() > 16 {
            if let Some(path) = cache
                .entries
                .iter()
                .min_by_key(|(_, (_, used))| *used)
                .map(|(path, _)| path.clone())
            {
                cache.entries.remove(&path);
            }
        }
        Ok(cached)
    }
}
fn summary(detail: &Detail) -> Row {
    let (source, title, phase, exit, owner, log) = match &detail.artifact {
        Artifact::Job(artifact) => (
            &artifact.source_id,
            artifact.source_id.as_str(),
            artifact.phase,
            artifact.exit_code,
            &artifact.owner_chat_id,
            &artifact.log,
        ),
        Artifact::Terminal(artifact) => (
            &artifact.source_id,
            artifact.name.as_deref().unwrap_or(&artifact.source_id),
            artifact.phase,
            artifact.exit_code,
            &artifact.owner_chat_id,
            &artifact.log,
        ),
    };
    let title = truncate(title, 1024);
    let source_id = truncate(source, 256);
    let owner_chat_id = owner.as_ref().map(|owner| truncate(owner, 256));
    let summary_truncated = title.len()
        < match &detail.artifact {
            Artifact::Job(artifact) => artifact.source_id.len(),
            Artifact::Terminal(artifact) => artifact
                .name
                .as_deref()
                .unwrap_or(&artifact.source_id)
                .len(),
        }
        || source_id.len() < source.len()
        || owner
            .as_ref()
            .zip(owner_chat_id.as_ref())
            .is_some_and(|(old, new)| old.len() != new.len());
    Row {
        key: detail.key.clone(),
        source_id,
        title,
        phase,
        exit_code: exit,
        owner_chat_id,
        has_log: log.end > log.retained_from,
        cursor_domain: log.cursor_domain.clone(),
        retained_from: log.retained_from,
        end: log.end,
        earlier_history_lost: log.earlier_history_lost,
        summary_truncated,
    }
}
fn validate_log(key: &Key, log: &LogArchive) -> Result<(), String> {
    if log.original_cursor_reusable || log.retained_from > log.end || log.segments.len() > 2 {
        return Err("旧日志域或段清单无效".into());
    }
    let mut expected = log.retained_from;
    for segment in &log.segments {
        let name = segment
            .path
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or("旧日志段文件名无效")?;
        let valid = match key.kind {
            Kind::Job => name == format!("{}.log", key.id),
            Kind::Terminal => {
                name == format!("{}.0.raw", key.id) || name == format!("{}.1.raw", key.id)
            }
        };
        if !valid
            || segment.path.parent() != Some(Path::new(key.kind.directory()))
            || segment.from != expected
            || segment.source.copied_to < segment.source.copied_from
            || segment.source.copied_to > segment.source.observed_bytes
            || segment.source.copied_to - segment.source.copied_from != segment.bytes
        {
            return Err("旧日志段越过本记录、来源或连续范围".into());
        }
        expected = expected
            .checked_add(segment.bytes)
            .ok_or("旧日志归档范围溢出")?;
    }
    if expected != log.end {
        return Err("旧日志段清单与归档末尾不一致".into());
    }
    Ok(())
}
fn id(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 128 || value.contains('\0') {
        Err("归档请求或UI身份无效".into())
    } else {
        Ok(())
    }
}
fn record_identity(value: &str) -> Result<(), String> {
    if value.len() != 65 || !value.starts_with('l') || !hex(&value[1..]) {
        Err("旧归档记录身份无效".into())
    } else {
        Ok(())
    }
}
fn hex(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| matches!(byte,b'0'..=b'9'|b'a'..=b'f'))
}
fn truncate(value: &str, max: usize) -> String {
    let mut end = value.len().min(max);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].into()
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn pointer(root: &File) -> Result<Pointer, String> {
    let bytes = read_bounded(open_file(root, "active-generation.json")?, 256 * 1024)?;
    let pointer: Pointer = serde_json::from_slice(&bytes).map_err(|_| "归档活动指针损坏")?;
    if pointer.version != 1
        || pointer.id.is_empty()
        || pointer.id.len() > 128
        || !pointer
            .id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
        || !hex(&pointer.manifest_sha256)
    {
        return Err("归档活动迁移代身份无效".into());
    }
    Ok(pointer)
}
fn read_bounded(mut file: File, max: u64) -> Result<Vec<u8>, String> {
    let before = file.metadata().map_err(|_| "归档普通文件信息不可读")?;
    if !before.is_file() || before.len() > max {
        return Err("归档文件不是有界普通文件".into());
    }
    let mut bytes = Vec::new();
    (&mut file)
        .take(before.len() + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "归档普通文件读取失败")?;
    if bytes.len() as u64 != before.len()
        || !same_file(
            &before,
            &file.metadata().map_err(|_| "归档文件读取后信息不可读")?,
        )
    {
        return Err("归档普通文件读取边界变化".into());
    }
    Ok(bytes)
}
fn open_generation(root: &File, id: &str) -> Result<(File, File), String> {
    let migrations = open_directory(root, "migrations")?;
    let generations = open_directory(&migrations, "generations")?;
    let generation = open_directory(&generations, id)?;
    let state = open_directory(&generation, "state")?;
    Ok((generation, state))
}
fn open_root(path: &Path) -> Result<File, String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let before = fs::symlink_metadata(path).map_err(|_| "归档namespace不存在")?;
        if !path.is_absolute() || !before.is_dir() || before.file_type().is_symlink() {
            return Err("归档namespace必须是明确普通绝对目录".into());
        }
        let directory = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path)
            .map_err(|_| "归档namespace不能安全打开")?;
        let opened = directory
            .metadata()
            .map_err(|_| "归档namespace信息不可读")?;
        let after = fs::symlink_metadata(path).map_err(|_| "归档namespace已被移除")?;
        if !opened.is_dir()
            || !after.is_dir()
            || !same_identity(&before, &opened)
            || !same_identity(&opened, &after)
        {
            return Err("归档namespace在打开期间被替换".into());
        }
        Ok(directory)
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Err("当前平台没有归档不跟随链接目录读取".into())
    }
}
fn open_directory(parent: &File, name: &str) -> Result<File, String> {
    open_at(parent, name, true)
}
fn open_file(parent: &File, name: &str) -> Result<File, String> {
    open_at(parent, name, false)
}
fn open_at(parent: &File, name: &str, directory: bool) -> Result<File, String> {
    #[cfg(unix)]
    {
        use std::{
            ffi::CString,
            os::fd::{AsRawFd, FromRawFd},
        };
        if name.is_empty() || name.contains(['/', '\\', '\0']) || name == "." || name == ".." {
            return Err("归档目录成员名称无效".into());
        }
        let name = CString::new(name).map_err(|_| "归档名称包含NUL")?;
        let flags = libc::O_RDONLY
            | libc::O_NOFOLLOW
            | libc::O_CLOEXEC
            | libc::O_NONBLOCK
            | if directory { libc::O_DIRECTORY } else { 0 };
        let fd = unsafe { libc::openat(parent.as_raw_fd(), name.as_ptr(), flags) };
        if fd < 0 {
            return Err("归档成员缺失、为链接或不可安全读取".into());
        }
        let file = unsafe { File::from_raw_fd(fd) };
        let metadata = file.metadata().map_err(|_| "归档成员信息不可读")?;
        if if directory {
            !metadata.is_dir()
        } else {
            !metadata.is_file()
        } {
            return Err("归档成员类型无效".into());
        }
        Ok(file)
    }
    #[cfg(not(unix))]
    {
        let _ = (parent, name, directory);
        Err("当前平台没有归档成员安全打开操作".into())
    }
}
fn same_identity(a: &Metadata, b: &Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        let _ = (a, b);
        false
    }
}
fn same_file(a: &Metadata, b: &Metadata) -> bool {
    let basic = a.is_file()
        && b.is_file()
        && same_identity(a, b)
        && a.len() == b.len()
        && a.modified().ok() == b.modified().ok();
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        basic && a.ctime() == b.ctime() && a.ctime_nsec() == b.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        basic
    }
}

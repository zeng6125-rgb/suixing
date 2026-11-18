//! Typed process archives prepared from the caller's verified legacy snapshot.
//! An archived log never supplies a runnable process, privilege or chat grant.
use crate::{
    legacy_import::{LegacyBlob, LegacyDocumentKind, LegacySnapshot},
    legacy_migration::GenerationFile,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path, PathBuf},
};
mod atomic_file;
mod prepare;
pub use atomic_file::{AtomicCandidate, scan_terminal_metadata};
pub use prepare::prepare;
const MAX_ROWS: usize = 128;
const MAX_FILE_BYTES: usize = 32 * 1024 * 1024;
const MAX_ARCHIVE_BYTES: usize = 128 * 1024 * 1024;
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct SourceProof {
    pub owning_root: PathBuf,
    pub relative_path: String,
    pub sha256: String,
    pub observed_bytes: u64,
    pub copied_from: u64,
    pub copied_to: u64,
    pub copied_sha256: String,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    InterruptedUnknown,
    Exited,
    Failed,
    Unknown,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LogSegment {
    pub path: PathBuf,
    pub from: u64,
    pub bytes: u64,
    pub source: SourceProof,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LogArchive {
    pub cursor_domain: String,
    pub retained_from: u64,
    pub end: u64,
    pub segments: Vec<LogSegment>,
    pub original_cursor_reusable: bool,
    pub earlier_history_lost: Option<bool>,
    pub notice: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct JobArtifact {
    pub id: String,
    pub source_id: String,
    pub phase: Phase,
    pub command: Option<String>,
    pub started: Option<bool>,
    pub exit_code: Option<i32>,
    pub owner_chat_id: Option<String>,
    pub log: LogArchive,
    pub notice: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TerminalArtifact {
    pub id: String,
    pub source_id: String,
    pub source_row: Option<Value>,
    pub metadata_source: Option<SourceProof>,
    pub name: Option<String>,
    pub kind: Option<String>,
    pub shell_label: Option<String>,
    pub cwd: Option<String>,
    pub phase: Phase,
    pub original_phase: Option<String>,
    pub exit_code: Option<i32>,
    pub source_owner_chat_id: Option<String>,
    pub owner_chat_id: Option<String>,
    pub pid_restored: bool,
    pub log: LogArchive,
    pub notice: String,
}
pub struct Draft {
    pub jobs: Vec<JobArtifact>,
    pub terminals: Vec<TerminalArtifact>,
    pub files: Vec<GenerationFile>,
    pub warnings: Vec<String>,
    pub archive_bytes: usize,
}
impl std::fmt::Debug for Draft {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LegacyProcessDraft")
            .field("jobs", &self.jobs.len())
            .field("terminals", &self.terminals.len())
            .field("files", &self.files.len())
            .field("archive_bytes", &self.archive_bytes)
            .finish()
    }
}
/// Root supplies its already resolved old-chat -> current-chat ID mapping.
/// A missing map entry retains source ownership as evidence and grants no task.
pub type OwnerMap = BTreeMap<String, String>;
#[derive(Default)]
pub struct CurrentIds {
    pub jobs: BTreeSet<String>,
    pub terminals: BTreeSet<String>,
}
pub fn merge_into(
    current: &mut BTreeMap<PathBuf, Vec<u8>>,
    draft: Draft,
    ids: &CurrentIds,
) -> Result<(), String> {
    let imported_paths: BTreeSet<_> = draft.files.iter().map(|file| file.path.clone()).collect();
    for job in &draft.jobs {
        let path = PathBuf::from(format!("legacy-jobs/{}.json", job.id));
        if ids.jobs.contains(&job.id) && !current.contains_key(&path) {
            return Err(format!("旧作业ID{}与当前原生记录冲突，未覆盖", job.id));
        }
    }
    for terminal in &draft.terminals {
        let path = PathBuf::from(format!("legacy-terminals/{}.json", terminal.id));
        if ids.terminals.contains(&terminal.id) && !current.contains_key(&path) {
            return Err(format!("旧终端ID{}与当前原生记录冲突，未覆盖", terminal.id));
        }
    }
    if imported_paths.len() != draft.files.len() {
        return Err("旧进程导入候选存在重名文件".into());
    }
    for file in &draft.files {
        relative(&file.path)?;
        if let Some(old) = current.get(&file.path) {
            if old != &file.bytes {
                return Err(format!(
                    "旧进程导入与当前{}内容冲突，未覆盖",
                    file.path.display()
                ));
            }
        }
    }
    for file in draft.files {
        current.entry(file.path).or_insert(file.bytes);
    }
    Ok(())
}
fn relative(path: &Path) -> Result<(), String> {
    if path.as_os_str().is_empty()
        || path
            .components()
            .any(|part| !matches!(part, Component::Normal(_)))
    {
        return Err("旧进程归档目标路径无效".into());
    }
    Ok(())
}
fn identity(domain: &str, root: &Path, old_id: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(domain.as_bytes());
    hash.update([0]);
    hash.update(root.as_os_str().to_string_lossy().as_bytes());
    hash.update([0]);
    hash.update(old_id.as_bytes());
    format!("l{:x}", hash.finalize())
}
fn proof(blob: &LegacyBlob, from: usize, to: usize) -> Result<SourceProof, String> {
    if !blob.owning_root.is_absolute() || from > to || to > blob.original_bytes.len() {
        return Err("旧进程来源或字节边界无效".into());
    }
    relative(Path::new(&blob.relative_path))?;
    if format!("{:x}", Sha256::digest(&blob.original_bytes)) != blob.sha256 {
        return Err("旧进程源快照哈希不匹配".into());
    }
    Ok(SourceProof {
        owning_root: blob.owning_root.clone(),
        relative_path: blob.relative_path.clone(),
        sha256: blob.sha256.clone(),
        observed_bytes: blob.original_bytes.len() as u64,
        copied_from: from as u64,
        copied_to: to as u64,
        copied_sha256: format!("{:x}", Sha256::digest(&blob.original_bytes[from..to])),
    })
}
#[derive(Clone, Debug, Serialize)]
pub struct ReadWindow {
    pub bytes: Vec<u8>,
    pub from: u64,
    pub next_offset: u64,
    pub retained_from: u64,
    pub end: u64,
    pub has_more: bool,
    pub cursor_domain: String,
}
/// Read from validated generation bytes, not from a mutable legacy source path.
pub fn read_window(
    archive: &LogArchive,
    files: &BTreeMap<PathBuf, Vec<u8>>,
    domain: &str,
    since: u64,
    max: usize,
) -> Result<ReadWindow, String> {
    if domain != archive.cursor_domain {
        return Err("旧日志游标属于另一归档域，未复用旧实时游标".into());
    }
    if !(1..=256 * 1024).contains(&max) || since < archive.retained_from || since > archive.end {
        return Err("旧日志游标或窗口超出已保留边界".into());
    }
    if archive.retained_from > archive.end || archive.segments.len() > 2 {
        return Err("旧日志归档范围或段数无效".into());
    }
    let mut expected = archive.retained_from;
    for segment in &archive.segments {
        relative(&segment.path)?;
        if segment.from != expected
            || segment.source.copied_to < segment.source.copied_from
            || segment.source.copied_to > segment.source.observed_bytes
            || segment.source.copied_to - segment.source.copied_from != segment.bytes
            || segment.bytes > MAX_FILE_BYTES as u64
        {
            return Err("旧日志归档段不连续或来源范围无效".into());
        }
        expected = expected
            .checked_add(segment.bytes)
            .ok_or("旧日志归档范围溢出")?;
    }
    if expected != archive.end {
        return Err("旧日志归档末尾与段清单不一致".into());
    }
    let mut bytes = Vec::new();
    let mut cursor = since;
    for segment in &archive.segments {
        let end = segment
            .from
            .checked_add(segment.bytes)
            .ok_or("旧日志段游标溢出")?;
        if cursor >= end {
            continue;
        }
        if cursor < segment.from {
            return Err("旧日志归档存在未声明间隙".into());
        }
        let source = files.get(&segment.path).ok_or("旧日志归档段缺失")?;
        if source.len() as u64 != segment.bytes
            || format!("{:x}", Sha256::digest(source)) != segment.source.copied_sha256
        {
            return Err("旧日志归档段长度或哈希不匹配".into());
        }
        let count = (end - cursor).min((max - bytes.len()) as u64) as usize;
        let from = (cursor - segment.from) as usize;
        bytes.extend_from_slice(&source[from..from + count]);
        cursor += count as u64;
        if bytes.len() == max {
            break;
        }
    }
    Ok(ReadWindow {
        bytes,
        from: since,
        next_offset: cursor,
        retained_from: archive.retained_from,
        end: archive.end,
        has_more: cursor < archive.end,
        cursor_domain: domain.into(),
    })
}

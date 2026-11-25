//! Read-only candidates for the original Android application's durable formats.
//!
//! This module never creates, renames, repairs, decrypts, or deletes a source file. In
//! particular, a chat's `.meta` names its generation and committed byte boundary;
//! bytes beyond that boundary are not read or offered for import. Raw JSON, raw
//! preference blobs and original bytes are retained alongside normalized candidates.
//! The caller owns any later, separately validated migration transaction.

use std::{
    borrow::Cow,
    collections::{BTreeMap, BTreeSet},
    fmt,
    fs::{self, Metadata, OpenOptions},
    io::Read,
    path::{Component, Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

const MAX_FILE_BYTES: u64 = 128 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 131_072;
const MAX_MESSAGE_BYTES: usize = 16 * 1024 * 1024;
const MAX_DEPTH: usize = 32;
const TERMINAL_METADATA: &[&str] = &[
    "terminal-sessions.json",
    "terminal-sessions.json.bak",
    "terminal-sessions.json.new",
];
const INTERNAL_WORKSPACE_EXCLUSIONS: &[&str] = &[
    "chats",
    "rust-state",
    "datastore",
    "jobs",
    "plugins",
    "assistants",
    "linux",
    "subagent_records",
    "deleted-usage",
    "toolout",
    "shots",
    "databases",
    "shared_prefs",
    "memory.json",
    "goal.json",
    "todos.json",
    "schedules.json",
    "mcp.json",
    "usage.json",
    "profile.md",
    "profile.md.bak",
    "profile.state.json",
    "perf.json",
    "skill-state.json",
    "skill-origin.json",
    "drawer-order",
    "http-dump-full",
    "last-http-error.json",
    "plugin-trace.log",
    "plugin-trace.log.1",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum LegacyDocumentKind {
    ConversationMetadata,
    ConversationJson,
    Settings,
    Memory,
    ProfileState,
    Goal,
    Todos,
    Schedules,
    UsageCache,
    DeletedUsage,
    ChildRecord,
    Mcp,
    SkillState,
    SkillOrigin,
    PluginManifest,
    PluginState,
    PluginEnvironment,
    DrawerOrder,
    Viewport,
    LiveDraft,
    LiveMessages,
    TerminalSessions,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyBlob {
    /// The explicitly supplied root that owned these bytes at scan time.
    pub owning_root: PathBuf,
    /// Relative to the explicitly supplied owning root, never an inferred package.
    pub relative_path: String,
    pub original_bytes: Vec<u8>,
    pub sha256: String,
}

impl fmt::Debug for LegacyBlob {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LegacyBlob")
            .field("relative_path", &self.relative_path)
            .field("bytes", &self.original_bytes.len())
            .finish()
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyDocument {
    pub kind: LegacyDocumentKind,
    pub source: LegacyBlob,
    /// Complete source value, including fields unknown to the current Rust UI.
    pub value: Value,
}

impl fmt::Debug for LegacyDocument {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LegacyDocument")
            .field("kind", &self.kind)
            .field("source", &self.source)
            .finish()
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyLiveDraft {
    pub manifest: LegacyDocument,
    pub message_payload: Option<LegacyDocument>,
    /// Resolved, original messages; the manifest's pointer is also retained.
    pub messages: Vec<Value>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyConversation {
    pub id: String,
    pub metadata: LegacyDocument,
    pub generation: Option<u64>,
    pub committed_log_bytes: u64,
    pub observed_log_bytes: u64,
    /// For generation stores, contains exactly the committed prefix, not the tail.
    pub committed_source: Option<LegacyBlob>,
    pub messages: Vec<Value>,
    /// Original unknown fields survive. Rust field spellings/defaults and repeated
    /// message identities are normalized; credentials and usage counts stay intact.
    pub normalized_messages: Vec<Value>,
    pub live_draft: Option<LegacyLiveDraft>,
}

impl fmt::Debug for LegacyConversation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LegacyConversation")
            .field("messages", &self.messages.len())
            .field("generation", &self.generation)
            .field("committed_log_bytes", &self.committed_log_bytes)
            .field("observed_log_bytes", &self.observed_log_bytes)
            .field("has_live_draft", &self.live_draft.is_some())
            .finish()
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub enum LegacyPreferenceValue {
    String(String),
    StringSet(Vec<String>),
    /// Other oneof kinds stay available as exact entry bytes in `raw_entry`.
    Other {
        field_number: Option<u32>,
    },
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyPreferenceEntry {
    pub key: String,
    pub value: LegacyPreferenceValue,
    /// Includes unknown fields and duplicate/overwritten values in this map entry.
    pub raw_entry: Vec<u8>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacyPreferences {
    pub source: LegacyBlob,
    /// Wire order is retained, including duplicate map keys. Protobuf's last map
    /// entry wins when producing the string/string-set projections below.
    pub entries: Vec<LegacyPreferenceEntry>,
    pub strings: BTreeMap<String, String>,
    pub string_sets: BTreeMap<String, Vec<String>>,
}

impl fmt::Debug for LegacyPreferences {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LegacyPreferences")
            .field("source", &self.source)
            .field("entries", &self.entries.len())
            .finish()
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct LegacySnapshot {
    pub source_root: PathBuf,
    pub external_files_root: Option<PathBuf>,
    pub no_backup_root: Option<PathBuf>,
    pub conversations: Vec<LegacyConversation>,
    pub documents: Vec<LegacyDocument>,
    pub preference_stores: Vec<LegacyPreferences>,
    /// Text/binary assets are preserved, never interpreted as running processes.
    pub assets: Vec<LegacyBlob>,
    /// Files and directories in the explicitly supplied old user workspace.
    /// Directory records preserve empty directories and changes to its inventory.
    #[serde(default)]
    pub workspace_sources: Vec<LegacyWorkspaceSource>,
    pub plugin_sources: Vec<LegacyWorkspaceSource>,
    pub warnings: Vec<String>,
}
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct LegacyWorkspaceSource {
    pub owning_root: PathBuf,
    pub relative_path: String,
    pub directory: bool,
    #[serde(default)]
    pub unix_mode: Option<u32>,
}

impl fmt::Debug for LegacySnapshot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LegacySnapshot")
            .field("conversations", &self.conversations.len())
            .field("documents", &self.documents.len())
            .field("preference_stores", &self.preference_stores.len())
            .field("assets", &self.assets.len())
            .field("warnings", &self.warnings)
            .finish()
    }
}

/// Probe only the supplied application roots. Extra paths must already belong
/// to the old application, as proved by the platform's owning Context.
pub fn has_legacy_data(
    source_root: &Path,
    no_backup_root: Option<&Path>,
    external_files_root: Option<&Path>,
) -> Result<bool, String> {
    let root = canonical_root(source_root)?;
    let no_backup = no_backup_root.map(canonical_root).transpose()?;
    let external = external_files_root.map(canonical_root).transpose()?;
    for relative in [
        "chats/store",
        "datastore/Suixing_settings.preferences_pb",
        "memory.json",
        "profile.md",
        "goal.json",
        "todos.json",
        "schedules.json",
        "deleted-usage",
        "subagent_records",
        "skill-state.json",
        "skill-origin.json",
    ] {
        if checked_existing(&root, Path::new(relative))?.is_some() {
            return Ok(true);
        }
    }
    // Old single-document conversations may exist without a store directory.
    if let Some(directory) = checked_existing(&root, Path::new("chats"))? {
        for entry in sorted_entries(&directory)? {
            let name = file_name(&entry)?;
            if name.ends_with(".json") && !name.ends_with(".live.json") {
                return Ok(true);
            }
        }
    }
    // These names overlap earlier Rust formats: identify the actual old shape.
    for (relative, list_is_old) in [("mcp.json", true), ("usage.json", false)] {
        if let Some(path) = checked_existing(&root, Path::new(relative))? {
            let mut budget = Budget::default();
            let blob = read_blob(&root, &path, None, &mut budget)?;
            let value = parse_json(&blob)?;
            if (list_is_old && value.is_array())
                || (!list_is_old && value.get("schemaVersion").is_some())
            {
                return Ok(true);
            }
        }
    }
    let mut budget = Budget::default();
    if let Some(no_backup) = &no_backup {
        for relative in TERMINAL_METADATA {
            if let Some(path) = checked_existing(no_backup, Path::new(relative))? {
                let metadata = fs::symlink_metadata(path).map_err(|_| "无法检查旧终端元数据")?;
                if !metadata.is_file() {
                    return Err("旧终端元数据不是普通文件".into());
                }
                if metadata.len() != 0 {
                    return Ok(true);
                }
            }
        }
        if let Some(logs) = checked_existing(no_backup, Path::new("terminal-logs"))? {
            if contains_workspace_file(no_backup, &logs, 0, &mut budget, &[])? {
                return Ok(true);
            }
        }
    }
    if let Some(external) = external.as_ref().filter(|external| **external != root) {
        return contains_workspace_file(external, external, 0, &mut budget, &[]);
    }
    // An explicit old Android root can use filesDir as its workspace if external
    // storage is unavailable. Exclude every private owner the full scan excludes.
    if no_backup.is_some() {
        return contains_workspace_file(
            &root,
            &root,
            0,
            &mut budget,
            INTERNAL_WORKSPACE_EXCLUSIONS,
        );
    }
    Ok(false)
}

/// Presence only. Empty directory trees do not trigger onboarding, and file
/// contents are left for the separately requested fingerprinted preview.
fn contains_workspace_file(
    root: &Path,
    directory: &Path,
    depth: usize,
    budget: &mut Budget,
    exclude_top_level: &[&str],
) -> Result<bool, String> {
    if depth > MAX_DEPTH {
        return Err("旧工作区目录深度超出候选预算".into());
    }
    for entry in fs::read_dir(directory).map_err(|_| "无法检查旧工作区目录")? {
        budget.entry()?;
        let path = entry.map_err(|_| "无法检查旧工作区项目")?.path();
        let name = file_name(&path)?;
        if depth == 0 && exclude_top_level.contains(&name.as_str()) {
            continue;
        }
        let relative = relative_name(root, &path)?;
        let path =
            checked_existing(root, Path::new(&relative))?.ok_or("旧工作区项目在检查期间消失")?;
        let metadata = fs::symlink_metadata(&path).map_err(|_| "无法检查旧工作区项目类型")?;
        if metadata.is_file() {
            return Ok(true);
        }
        if !metadata.is_dir() {
            return Err("旧工作区含非普通文件，无法确认完整候选".into());
        }
        if contains_workspace_file(root, &path, depth + 1, budget, &[])? {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Extra owning roots must be supplied explicitly by the platform/caller. This
/// parser never derives another package's private directory from an application ID.
pub fn scan_legacy_with_roots(
    source_root: &Path,
    no_backup_root: Option<&Path>,
    external_files_root: Option<&Path>,
) -> Result<LegacySnapshot, String> {
    let root = canonical_root(source_root)?;
    let no_backup = no_backup_root.map(canonical_root).transpose()?;
    let external = external_files_root.map(canonical_root).transpose()?;
    let mut snapshot = LegacySnapshot {
        source_root: root.clone(),
        external_files_root: external.clone(),
        no_backup_root: no_backup.clone(),
        conversations: Vec::new(),
        documents: Vec::new(),
        preference_stores: Vec::new(),
        assets: Vec::new(),
        workspace_sources: Vec::new(),
        plugin_sources: Vec::new(),
        warnings: Vec::new(),
    };
    let mut budget = Budget::default();
    scan_chats(&root, &mut snapshot, &mut budget)?;
    for (relative, kind, shape) in [
        ("memory.json", LegacyDocumentKind::Memory, Shape::Object),
        (
            "profile.state.json",
            LegacyDocumentKind::ProfileState,
            Shape::Object,
        ),
        ("goal.json", LegacyDocumentKind::Goal, Shape::Object),
        ("todos.json", LegacyDocumentKind::Todos, Shape::Array),
        (
            "schedules.json",
            LegacyDocumentKind::Schedules,
            Shape::Array,
        ),
        ("usage.json", LegacyDocumentKind::UsageCache, Shape::Object),
        ("mcp.json", LegacyDocumentKind::Mcp, Shape::Array),
        (
            "skill-state.json",
            LegacyDocumentKind::SkillState,
            Shape::Array,
        ),
        (
            "skill-origin.json",
            LegacyDocumentKind::SkillOrigin,
            Shape::Object,
        ),
    ] {
        if let Some(path) = checked_existing(&root, Path::new(relative))? {
            let document = read_document(&root, &path, kind, &mut budget)?;
            shape.require(&document.value, relative)?;
            snapshot.documents.push(document);
        }
    }
    for relative in [
        "profile.md",
        "profile.md.bak",
        "perf.json",
        "chats/last-opened",
    ] {
        if let Some(path) = checked_existing(&root, Path::new(relative))? {
            snapshot
                .assets
                .push(read_blob(&root, &path, None, &mut budget)?);
        }
    }
    scan_json_directory(
        &root,
        "deleted-usage",
        LegacyDocumentKind::DeletedUsage,
        0,
        &mut snapshot,
        &mut budget,
    )?;
    scan_json_directory(
        &root,
        "subagent_records",
        LegacyDocumentKind::ChildRecord,
        1,
        &mut snapshot,
        &mut budget,
    )?;
    scan_preferences(&root, &mut snapshot, &mut budget)?;
    scan_plugins(&root, &mut snapshot, &mut budget)?;
    let mut chosen_order = None;
    let mut invalid_order = false;
    for relative in ["drawer-order", "drawer-order.tmp"] {
        if let Some(path) = checked_existing(&root, Path::new(relative))? {
            let source = read_blob(&root, &path, None, &mut budget)?;
            let candidate = parse_json(&source)
                .ok()
                .filter(|value| value.is_object())
                .filter(|value| {
                    serde_json::from_value::<crate::conversation_preferences::Order>(value.clone())
                        .ok()
                        .is_some_and(|order| {
                            order.pinned.len() <= 8192 && order.normal.len() <= 8192
                        })
                });
            if chosen_order.is_none() {
                if let Some(value) = candidate {
                    chosen_order = Some(LegacyDocument {
                        kind: LegacyDocumentKind::DrawerOrder,
                        source,
                        value,
                    });
                    continue;
                } else {
                    invalid_order = true;
                }
            }
            // Keep rejected main files and unused temporary backups as exact raw assets.
            snapshot.assets.push(source);
        }
    }
    if let Some(document) = chosen_order {
        if invalid_order {
            snapshot
                .warnings
                .push("旧抽屉顺序主文件无效，采用有效临时快照；原文件均保留。".into());
        }
        snapshot.documents.push(document);
    }
    scan_assets(&root, "assistants", 0, &mut snapshot.assets, &mut budget)?;
    scan_assets(&root, "jobs", 0, &mut snapshot.assets, &mut budget)?;
    scan_assets(&root, "toolout", 0, &mut snapshot.assets, &mut budget)?;
    scan_assets(&root, "shots", 0, &mut snapshot.assets, &mut budget)?;
    if let Some(external) = external.as_ref().filter(|external| **external != root) {
        // The original tools and terminal use all externalFilesDir as workspace.
        scan_workspace(external, external, 0, &mut snapshot, &mut budget, &[])?;
    } else {
        // Internal-storage fallback shares the root with private application
        // state. Those known owners are archived by their dedicated parsers.
        scan_workspace(
            &root,
            &root,
            0,
            &mut snapshot,
            &mut budget,
            INTERNAL_WORKSPACE_EXCLUSIONS,
        )?;
        snapshot.warnings.push(
            "未提供原应用 externalFilesDir；仅覆盖明确提供的内部工作区后备目录，未探测其它包目录"
                .into(),
        );
    }
    crate::plugin_assets::scan_source(&mut snapshot)?;
    snapshot.plugin_sources.sort();
    snapshot.workspace_sources.sort();
    if let Some(ref no_backup) = no_backup {
        let mut candidates = BTreeMap::new();
        for &name in TERMINAL_METADATA {
            if let Some(path) = checked_existing(no_backup, Path::new(name))? {
                let source = read_blob(no_backup, &path, None, &mut budget)?;
                snapshot.assets.push(source.clone());
                candidates.insert(name, source);
            }
        }
        if let Some(source) = candidates
            .remove("terminal-sessions.json.bak")
            .or_else(|| candidates.remove("terminal-sessions.json"))
        {
            let value = serde_json::from_slice(&source.original_bytes)
                .map_err(|_| "旧终端已提交AtomicFile元数据不是有效JSON")?;
            Shape::Array.require(&value, &source.relative_path)?;
            snapshot.documents.push(LegacyDocument {
                kind: LegacyDocumentKind::TerminalSessions,
                source,
                value,
            });
        }
        if candidates.contains_key("terminal-sessions.json.new") {
            snapshot
                .warnings
                .push("旧终端未提交.new仅封存原文；未恢复为活动会话".into());
        }
        scan_assets(
            no_backup,
            "terminal-logs",
            0,
            &mut snapshot.assets,
            &mut budget,
        )?;
    } else {
        snapshot.warnings.push(
            "未提供原应用 noBackupFilesDir；终端历史尚未覆盖，旧 PID/PTY 不能恢复为活跃进程".into(),
        );
    }
    if checked_existing(&root, Path::new("linux"))?.is_some() {
        snapshot.warnings.push(
            "发现原 Linux 数据目录；同包ShellEnvironment/LinuxManager原位复用并验证ready，不在通用资产迁移中复制或删除rootfs"
                .into(),
        );
    }
    snapshot
        .warnings
        .push("候选只读且未经迁移提交；enc1 密文保持原样，Keystore 凭据尚未接续".into());
    Ok(snapshot)
}

fn scan_chats(
    root: &Path,
    snapshot: &mut LegacySnapshot,
    budget: &mut Budget,
) -> Result<(), String> {
    let mut known = BTreeSet::new();
    if let Some(directory) = checked_existing(root, Path::new("chats/store"))? {
        for path in sorted_entries(&directory)? {
            budget.entry()?;
            if path.extension().and_then(|s| s.to_str()) != Some("meta") {
                continue;
            }
            let metadata = read_document(
                root,
                &path,
                LegacyDocumentKind::ConversationMetadata,
                budget,
            )?;
            Shape::Object.require(&metadata.value, &metadata.source.relative_path)?;
            let id = required_string(&metadata.value, "id", &metadata.source.relative_path)?;
            safe_id(&id)?;
            if file_name(&path)? != format!("{id}.meta") || !known.insert(id.clone()) {
                return Err("旧会话 metadata 身份或文件名冲突，原文件已保留".into());
            }
            let generation = optional_u64(&metadata.value, "gen", 0, &id)?;
            let committed = optional_u64(&metadata.value, "logBytes", 0, &id)?;
            let relative = format!("chats/store/{id}.{generation}.jsonl");
            let log = checked_existing(root, Path::new(&relative))?
                .ok_or_else(|| format!("旧会话缺少 meta 指定日志：{relative}"))?;
            let observed = fs::metadata(&log)
                .map_err(|_| format!("无法读取旧日志元数据：{relative}"))?
                .len();
            let committed_source = read_blob(root, &log, Some(committed), budget)?;
            if fs::metadata(&log).map_err(|_| "旧日志在读取后消失")?.len() != observed {
                return Err(format!("旧会话 {id} 的日志在读取期间改变，请重新读取"));
            }
            let messages = jsonl_messages(&committed_source)?;
            let expected = optional_u64(&metadata.value, "messageCount", 0, &id)?;
            if expected != messages.len() as u64 {
                return Err(format!(
                    "旧会话 {id} 的提交消息数与 meta 不一致，拒绝部分导入"
                ));
            }
            if let Some(last) = messages.last() {
                let expected_id = required_string(&metadata.value, "lastMessageId", &id)?;
                if last.get("id").and_then(Value::as_str) != Some(expected_id.as_str()) {
                    return Err(format!("旧会话 {id} 的提交末消息身份不一致"));
                }
            }
            let normalized_messages = normalize_messages(&messages)?;
            let live_draft = read_live(root, &id, budget)?;
            if observed > committed {
                snapshot.warnings.push(format!(
                    "会话 {id} 存在未提交日志尾部，候选仅含 meta.logBytes 内字节"
                ));
            }
            // Re-read the small transaction pointer after all referenced files.
            if read_blob(root, &path, None, budget)?.original_bytes
                != metadata.source.original_bytes
            {
                return Err(format!(
                    "旧会话 {id} 在读取期间提交了新 metadata，请稍后重新读取"
                ));
            }
            snapshot.conversations.push(LegacyConversation {
                id,
                metadata,
                generation: Some(generation),
                committed_log_bytes: committed,
                observed_log_bytes: observed,
                committed_source: Some(committed_source),
                messages,
                normalized_messages,
                live_draft,
            });
        }
    }
    if let Some(directory) = checked_existing(root, Path::new("chats"))? {
        for path in sorted_entries(&directory)? {
            budget.entry()?;
            let name = file_name(&path)?;
            if name.ends_with(".live.json") {
                let id = name.trim_end_matches(".live.json");
                safe_id(id)?;
                if !known.contains(id) {
                    if let Some(live) = read_live(root, id, budget)? {
                        snapshot.documents.push(live.manifest);
                        if let Some(payload) = live.message_payload {
                            snapshot.documents.push(payload);
                        }
                        snapshot
                            .warnings
                            .push(format!("恢复草稿 {id} 没有已发现的会话，保留为独立候选"));
                    }
                }
                continue;
            }
            if !name.ends_with(".json") || name.ends_with(".corrupt.json") {
                continue;
            }
            let metadata =
                read_document(root, &path, LegacyDocumentKind::ConversationJson, budget)?;
            Shape::Object.require(&metadata.value, &name)?;
            let id = required_string(&metadata.value, "id", &name)?;
            safe_id(&id)?;
            let messages = required_array(&metadata.value, "messages", &name)?.clone();
            let normalized_messages = normalize_messages(&messages)?;
            if known.contains(&id) {
                // Preserve the old copy as evidence without silently merging generations.
                snapshot.documents.push(metadata);
                snapshot.warnings.push(format!(
                    "会话 {id} 同时有旧 JSON 副本和已提交 store；副本原文保留，不重复计入"
                ));
                continue;
            }
            if name != format!("{id}.json") || !known.insert(id.clone()) {
                return Err("旧 JSON 会话身份或文件名冲突".into());
            }
            let live_draft = read_live(root, &id, budget)?;
            snapshot.conversations.push(LegacyConversation {
                id,
                metadata,
                generation: None,
                committed_log_bytes: 0,
                observed_log_bytes: 0,
                committed_source: None,
                messages,
                normalized_messages,
                live_draft,
            });
        }
    }
    Ok(())
}

fn read_live(
    root: &Path,
    id: &str,
    budget: &mut Budget,
) -> Result<Option<LegacyLiveDraft>, String> {
    let relative = format!("chats/{id}.live.json");
    let Some(path) = checked_existing(root, Path::new(&relative))? else {
        return Ok(None);
    };
    let manifest = read_document(root, &path, LegacyDocumentKind::LiveDraft, budget)?;
    if required_string(&manifest.value, "chatId", &relative)? != id {
        return Err("旧恢复草稿所属会话不一致".into());
    }
    required_string(&manifest.value, "runId", &relative)?;
    let (message_payload, messages) = match manifest.value.get("messageSnapshot") {
        None | Some(Value::Null) => (
            None,
            manifest.value.get("messages").map_or(Ok(Vec::new()), |v| {
                v.as_array()
                    .cloned()
                    .ok_or("旧恢复草稿 messages 不是列表".to_string())
            })?,
        ),
        Some(Value::String(name)) => {
            if !name.starts_with(&format!("{id}.live-"))
                || !name.ends_with(".messages")
                || !single_component(name)
            {
                return Err("旧恢复草稿包含无效 payload 路径".into());
            }
            let relative = format!("chats/{name}");
            let payload_path = checked_existing(root, Path::new(&relative))?
                .ok_or("旧恢复草稿引用 payload 缺失")?;
            let payload = read_document(
                root,
                &payload_path,
                LegacyDocumentKind::LiveMessages,
                budget,
            )?;
            let messages = payload
                .value
                .as_array()
                .cloned()
                .ok_or("旧恢复草稿 payload 不是消息列表")?;
            (Some(payload), messages)
        }
        Some(_) => return Err("旧恢复草稿 messageSnapshot 类型无效".into()),
    };
    normalize_messages(&messages)?;
    if read_blob(root, &path, None, budget)?.original_bytes != manifest.source.original_bytes {
        return Err("旧恢复草稿在读取期间更新，请重新读取".into());
    }
    Ok(Some(LegacyLiveDraft {
        manifest,
        message_payload,
        messages,
    }))
}

fn jsonl_messages(source: &LegacyBlob) -> Result<Vec<Value>, String> {
    let mut messages = Vec::new();
    for (line, bytes) in source.original_bytes.split(|&b| b == b'\n').enumerate() {
        if bytes.is_empty() {
            continue;
        }
        if bytes.len() > MAX_MESSAGE_BYTES || messages.len() >= MAX_ENTRIES {
            return Err("旧聊天提交范围超出只读候选预算；拒绝截断导入，需分段迁移".into());
        }
        let message: Value = serde_json::from_slice(bytes).map_err(|_| {
            format!(
                "旧日志 {} 提交范围第 {} 行损坏",
                source.relative_path,
                line + 1
            )
        })?;
        messages.push(message);
    }
    Ok(messages)
}

/// Both the native transcript and archived usage membership use the same IDs.
/// Count from the newest end, as the old reader did for duplicate row keys.
pub(crate) fn message_occurrence_ids(messages: &[Value]) -> Result<Vec<Cow<'_, str>>, String> {
    if messages.len() > MAX_ENTRIES {
        return Err("旧消息数超出候选预算，拒绝截断".into());
    }
    let original = messages
        .iter()
        .map(|message| {
            message
                .get("id")
                .and_then(Value::as_str)
                .filter(|id| !id.is_empty() && id.len() <= 128)
                .ok_or("旧消息缺少有效ID")
        })
        .collect::<Result<Vec<_>, _>>()?;
    let mut occupied: BTreeSet<Cow<'_, str>> =
        original.iter().copied().map(Cow::Borrowed).collect();
    let mut seen = BTreeMap::<&str, u32>::new();
    let mut resolved = Vec::with_capacity(messages.len());
    for id in original.into_iter().rev() {
        let count = seen.entry(id).or_default();
        let mapped = if *count == 0 {
            Cow::Borrowed(id)
        } else {
            let mut hash = Sha256::new();
            hash.update(id.as_bytes());
            hash.update(count.to_be_bytes());
            let mapped = format!("legacy-duplicate-{:x}", hash.finalize());
            if !occupied.insert(Cow::Owned(mapped.clone())) {
                return Err("旧消息重复身份的生成ID发生冲突，原消息未覆盖".into());
            }
            Cow::Owned(mapped)
        };
        *count += 1;
        resolved.push(mapped);
    }
    resolved.reverse();
    Ok(resolved)
}

pub fn normalize_messages(messages: &[Value]) -> Result<Vec<Value>, String> {
    let identities = message_occurrence_ids(messages)?;
    let mut normalized = Vec::with_capacity(messages.len());
    for (message, id) in messages.iter().zip(identities) {
        let role = required_string(message, "role", "旧消息")?;
        let mut value = message.clone();
        let object = value.as_object_mut().ok_or("旧消息不是对象")?;
        if object.contains_key(crate::chat::LEGACY_ORIGINAL_ID) {
            return Err("旧消息使用了迁移身份保留字段，原消息未覆盖".into());
        }
        if let Cow::Owned(id) = id {
            let original = object
                .insert("id".into(), Value::String(id))
                .ok_or("旧消息缺少ID")?;
            object.insert(crate::chat::LEGACY_ORIGINAL_ID.into(), original);
        }
        let mut text = match object.get("text") {
            None => "",
            Some(Value::String(s)) => s,
            _ => return Err("旧消息正文类型无效".into()),
        }
        .to_owned();
        // Original StoredMessage tool rows deliberately have text="" and keep
        // their already-redacted transcript output in toolOutput. Rust's wire
        // replay consumes text; retain toolOutput as metadata as well as raw JSON.
        if role == "tool" && text.is_empty() {
            match object
                .get("toolOutput")
                .or_else(|| object.get("tool_output"))
            {
                None | Some(Value::Null) => {}
                Some(Value::String(output)) => text = output.clone(),
                Some(_) => return Err("旧工具消息 toolOutput 类型无效".into()),
            }
        }
        let thinking = match object.get("thinking").or_else(|| object.get("reasoning")) {
            None | Some(Value::Null) => String::new(),
            Some(Value::String(s)) => s.clone(),
            _ => return Err("旧消息 reasoning 类型无效".into()),
        };
        // Remove known aliases from the normalized copy only: serde rejects a field
        // supplied under both its canonical spelling and its alias. Raw is untouched.
        object.remove("reasoning");
        object.insert("text".into(), Value::String(text));
        object.insert("thinking".into(), Value::String(thinking));
        object.entry("complete").or_insert(Value::Bool(true));
        if object.get("complete").and_then(Value::as_bool).is_none() {
            return Err("旧消息 complete 类型无效".into());
        }
        for (old, new) in [("toolCalls", "tool_calls"), ("toolCallId", "tool_call_id")] {
            if object.contains_key(new) && object.contains_key(old) {
                return Err("旧消息同时包含冲突的工具字段拼写".into());
            }
            if let Some(old_value) = object.remove(old) {
                object.insert(new.into(), old_value);
            }
        }
        normalized.push(value);
    }
    Ok(normalized)
}

fn scan_json_directory(
    root: &Path,
    relative: &str,
    kind: LegacyDocumentKind,
    nested: usize,
    snapshot: &mut LegacySnapshot,
    budget: &mut Budget,
) -> Result<(), String> {
    let Some(directory) = checked_existing(root, Path::new(relative))? else {
        return Ok(());
    };
    for path in sorted_entries(&directory)? {
        budget.entry()?;
        let metadata = fs::symlink_metadata(&path).map_err(|_| "旧数据目录项目无法读取")?;
        if metadata.file_type().is_symlink() {
            return Err("旧数据目录包含符号链接，拒绝跨目录扫描".into());
        }
        if metadata.is_dir() && nested > 0 {
            let child = relative_name(root, &path)?;
            scan_json_directory(root, &child, kind, nested - 1, snapshot, budget)?;
        } else if metadata.is_file() && path.extension().and_then(|s| s.to_str()) == Some("json") {
            let document = read_document(root, &path, kind, budget)?;
            Shape::Object.require(&document.value, &document.source.relative_path)?;
            snapshot.documents.push(document);
        }
    }
    Ok(())
}

fn scan_plugins(
    root: &Path,
    snapshot: &mut LegacySnapshot,
    budget: &mut Budget,
) -> Result<(), String> {
    let Some(directory) = checked_existing(root, Path::new("plugins"))? else {
        return Ok(());
    };
    for path in sorted_entries(&directory)? {
        budget.entry()?;
        let id = file_name(&path)?;
        if id.starts_with('.') {
            continue;
        }
        let relative = format!("plugins/{id}");
        let Some(path) = checked_existing(root, Path::new(&relative))? else {
            continue;
        };
        if !path.is_dir() {
            return Err("旧插件根目录包含意外文件，未忽略用户数据".into());
        }
        for (name, kind) in [
            ("manifest.json", LegacyDocumentKind::PluginManifest),
            ("state.json", LegacyDocumentKind::PluginState),
            ("env.json", LegacyDocumentKind::PluginEnvironment),
        ] {
            let file = format!("{relative}/{name}");
            if let Some(path) = checked_existing(root, Path::new(&file))? {
                let document = read_document(root, &path, kind, budget)?;
                Shape::Object.require(&document.value, &file)?;
                snapshot.documents.push(document);
            } else if name == "manifest.json" {
                snapshot.warnings.push(format!(
                    "旧插件 {id} 缺少manifest；完整目录仍保留，Rust发现页将报告清单错误而不执行"
                ));
            }
        }
        scan_assets_except(
            root,
            &relative,
            0,
            &mut snapshot.assets,
            budget,
            &["manifest.json", "state.json", "env.json"],
        )?;
    }
    Ok(())
}

fn scan_assets(
    root: &Path,
    relative: &str,
    depth: usize,
    assets: &mut Vec<LegacyBlob>,
    budget: &mut Budget,
) -> Result<(), String> {
    scan_assets_except(root, relative, depth, assets, budget, &[])
}
fn scan_workspace(
    owning_root: &Path,
    directory: &Path,
    depth: usize,
    snapshot: &mut LegacySnapshot,
    budget: &mut Budget,
    exclude_top_level: &[&str],
) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Err("旧工作区目录深度超出预算，拒绝部分扫描".into());
    }
    for path in sorted_entries(directory)? {
        let name = file_name(&path)?;
        if depth == 0 && exclude_top_level.contains(&name.as_str()) {
            continue;
        }
        budget.entry()?;
        let relative_path = relative_name(owning_root, &path)?;
        let path = checked_existing(owning_root, Path::new(&relative_path))?
            .ok_or("旧工作区项目在扫描期间消失")?;
        let metadata = fs::symlink_metadata(&path).map_err(|_| "旧工作区项目不可读取")?;
        if !metadata.is_file() && !metadata.is_dir() {
            return Err("旧工作区含非普通文件，不能作为完整副本迁移".into());
        }
        snapshot.workspace_sources.push(LegacyWorkspaceSource {
            owning_root: owning_root.to_owned(),
            relative_path,
            directory: metadata.is_dir(),
            unix_mode: ordinary_mode(&metadata),
        });
        if metadata.is_dir() {
            scan_workspace(owning_root, &path, depth + 1, snapshot, budget, &[])?;
        } else {
            snapshot
                .assets
                .push(read_blob(owning_root, &path, None, budget)?);
        }
    }
    Ok(())
}
fn ordinary_mode(metadata: &Metadata) -> Option<u32> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        Some(metadata.permissions().mode() & 0o777)
    }
    #[cfg(not(unix))]
    {
        let _ = metadata;
        None
    }
}

fn scan_assets_except(
    root: &Path,
    relative: &str,
    depth: usize,
    assets: &mut Vec<LegacyBlob>,
    budget: &mut Budget,
    exclude: &[&str],
) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Err("旧资产目录深度超出候选预算，拒绝部分扫描".into());
    }
    let Some(directory) = checked_existing(root, Path::new(relative))? else {
        return Ok(());
    };
    for path in sorted_entries(&directory)? {
        budget.entry()?;
        let name = file_name(&path)?;
        if depth == 0 && exclude.contains(&name.as_str()) {
            continue;
        }
        let child = relative_name(root, &path)?;
        let Some(path) = checked_existing(root, Path::new(&child))? else {
            return Err("旧资产在扫描期间消失".into());
        };
        let metadata = fs::symlink_metadata(&path).map_err(|_| "无法读取旧资产类型")?;
        if metadata.is_dir() {
            scan_assets_except(root, &child, depth + 1, assets, budget, &[])?;
        } else if metadata.is_file() {
            assets.push(read_blob(root, &path, None, budget)?);
        } else {
            return Err("旧资产包含非普通文件，不能作为完整文件副本导入".into());
        }
    }
    Ok(())
}

fn scan_preferences(
    root: &Path,
    snapshot: &mut LegacySnapshot,
    budget: &mut Budget,
) -> Result<(), String> {
    for name in [
        "Suixing_settings",
        "Suixing_conversation_viewports",
        "Suixing_conversation_reads",
    ] {
        let relative = format!("datastore/{name}.preferences_pb");
        let Some(path) = checked_existing(root, Path::new(&relative))? else {
            continue;
        };
        let source = read_blob(root, &path, None, budget)?;
        let preferences = decode_preferences(source)?;
        for (key, text) in &preferences.strings {
            let kind = if name == "Suixing_settings" && key == "settings_json" {
                Some(LegacyDocumentKind::Settings)
            } else if name == "Suixing_conversation_viewports" && key.starts_with("position/") {
                Some(LegacyDocumentKind::Viewport)
            } else {
                None
            };
            if let Some(kind) = kind {
                let source =
                    blob_from_bytes(root, format!("{relative}#{key}"), text.as_bytes().to_vec());
                let value = parse_json(&source)?;
                Shape::Object.require(&value, &source.relative_path)?;
                snapshot.documents.push(LegacyDocument {
                    kind,
                    source,
                    value,
                });
            }
        }
        if name == "Suixing_settings"
            && preferences
                .entries
                .iter()
                .any(|entry| entry.key == "settings_json")
            && !preferences.strings.contains_key("settings_json")
        {
            return Err("旧 DataStore 的 settings_json 不是字符串，原 blob 已保留在源目录".into());
        }
        snapshot.preference_stores.push(preferences);
    }
    Ok(())
}

/// Field numbers were verified from the locally resolved AndroidX 1.3.0 JARs:
/// PreferencesProto.PreferenceMap.preferences=1; MapEntryLite key=1/string,
/// value=2/message; Value.string=5/string_set=6; StringSet.strings=1. No platform
/// DataStore implementation or new protobuf dependency is required for this reader.
pub fn decode_preferences(source: LegacyBlob) -> Result<LegacyPreferences, String> {
    if source.original_bytes.len() as u64 > MAX_FILE_BYTES {
        return Err("旧 preferences blob 超过读取预算，拒绝部分解析".into());
    }
    let mut reader = ProtoReader::new(&source.original_bytes);
    let mut entries = Vec::new();
    let mut strings = BTreeMap::new();
    let mut string_sets = BTreeMap::new();
    while let Some(field) = reader.next()? {
        if field.number != 1 || field.wire != 2 {
            continue;
        }
        if entries.len() >= MAX_ENTRIES {
            return Err("旧 preferences 条目超出预算，拒绝截断".into());
        }
        let entry = decode_preference_entry(field.payload)?;
        strings.remove(&entry.key);
        string_sets.remove(&entry.key);
        match &entry.value {
            LegacyPreferenceValue::String(value) => {
                strings.insert(entry.key.clone(), value.clone());
            }
            LegacyPreferenceValue::StringSet(value) => {
                string_sets.insert(entry.key.clone(), value.clone());
            }
            LegacyPreferenceValue::Other { .. } => {}
        }
        entries.push(entry);
    }
    Ok(LegacyPreferences {
        source,
        entries,
        strings,
        string_sets,
    })
}

pub(crate) fn settings_preferences(
    bytes: &[u8],
) -> Result<(Option<String>, Option<String>), String> {
    let mut compose = None;
    let mut rust = None;
    let mut reader = ProtoReader::new(bytes);
    while let Some(field) = reader.next()? {
        if (field.number, field.wire) != (1, 2) {
            continue;
        }
        let entry = decode_preference_entry(field.payload)?;
        let target = match entry.key.as_str() {
            "settings_json" => &mut compose,
            "rust_settings_json" => &mut rust,
            _ => continue,
        };
        let LegacyPreferenceValue::String(text) = entry.value else {
            return Err("应用设置存储中的设置项不是文本".into());
        };
        *target = Some(text);
    }
    Ok((compose, rust))
}

pub(crate) fn set_settings_preferences(
    bytes: &[u8],
    compose: &str,
    rust: &str,
) -> Result<Vec<u8>, String> {
    let mut output = Vec::new();
    let mut reader = ProtoReader::new(bytes);
    let mut start = 0;
    while let Some(field) = reader.next()? {
        let owned = (field.number, field.wire) == (1, 2)
            && matches!(
                decode_preference_entry(field.payload)?.key.as_str(),
                "settings_json" | "rust_settings_json"
            );
        if !owned {
            output.extend_from_slice(&bytes[start..reader.at]);
        }
        start = reader.at;
    }
    for (key, text) in [("settings_json", compose), ("rust_settings_json", rust)] {
        let mut value = Vec::new();
        push_proto_bytes(&mut value, 5, text.as_bytes());
        let mut entry = Vec::new();
        push_proto_bytes(&mut entry, 1, key.as_bytes());
        push_proto_bytes(&mut entry, 2, &value);
        push_proto_bytes(&mut output, 1, &entry);
    }
    Ok(output)
}

pub(crate) fn edit_preference(
    bytes: &[u8],
    key: &str,
    value: Option<&LegacyPreferenceValue>,
) -> Result<Vec<u8>, String> {
    let mut output = Vec::new();
    let mut reader = ProtoReader::new(bytes);
    let mut start = 0;
    while let Some(field) = reader.next()? {
        let owned = (field.number, field.wire) == (1, 2)
            && decode_preference_entry(field.payload)?.key == key;
        if !owned {
            output.extend_from_slice(&bytes[start..reader.at]);
        }
        start = reader.at;
    }
    let Some(value) = value else { return Ok(output); };
    let mut payload = Vec::new();
    match value {
        LegacyPreferenceValue::String(value) => push_proto_bytes(&mut payload, 5, value.as_bytes()),
        LegacyPreferenceValue::StringSet(values) => {
            let mut set = Vec::new();
            for value in values {
                push_proto_bytes(&mut set, 1, value.as_bytes());
            }
            push_proto_bytes(&mut payload, 6, &set);
        }
        LegacyPreferenceValue::Other { .. } => return Err("不支持写入此偏好值".into()),
    }
    let mut entry = Vec::new();
    push_proto_bytes(&mut entry, 1, key.as_bytes());
    push_proto_bytes(&mut entry, 2, &payload);
    push_proto_bytes(&mut output, 1, &entry);
    Ok(output)
}

fn push_proto_bytes(output: &mut Vec<u8>, field: u8, bytes: &[u8]) {
    output.push(field * 8 + 2);
    let mut len = bytes.len();
    while len >= 128 {
        output.push((len & 0x7f) as u8 | 0x80);
        len >>= 7;
    }
    output.push(len as u8);
    output.extend_from_slice(bytes);
}

fn decode_preference_entry(bytes: &[u8]) -> Result<LegacyPreferenceEntry, String> {
    let mut reader = ProtoReader::new(bytes);
    let mut key = String::new();
    let mut selected = LegacyPreferenceValue::Other { field_number: None };
    while let Some(field) = reader.next()? {
        if field.number == 1 && field.wire == 2 {
            key = utf8(field.payload, "preferences key")?.to_owned();
        }
        if field.number == 2 && field.wire == 2 {
            // Multiple value messages merge in encounter order; an unknown field
            // does not clear the previous known oneof value.
            let mut values = ProtoReader::new(field.payload);
            while let Some(value) = values.next()? {
                match (value.number, value.wire) {
                    (5, 2) => {
                        selected = LegacyPreferenceValue::String(
                            utf8(value.payload, "preferences string")?.to_owned(),
                        )
                    }
                    (6, 2) => {
                        let mut items = Vec::new();
                        let mut set = ProtoReader::new(value.payload);
                        while let Some(item) = set.next()? {
                            if item.number == 1 && item.wire == 2 {
                                if items.len() >= MAX_ENTRIES {
                                    return Err("旧 string-set 超出预算".into());
                                }
                                items
                                    .push(utf8(item.payload, "preferences string-set")?.to_owned());
                            }
                        }
                        if let LegacyPreferenceValue::StringSet(previous) = &mut selected {
                            if previous
                                .len()
                                .checked_add(items.len())
                                .is_none_or(|n| n > MAX_ENTRIES)
                            {
                                return Err("旧合并 string-set 超出预算".into());
                            }
                            previous.extend(items);
                        } else {
                            selected = LegacyPreferenceValue::StringSet(items);
                        }
                    }
                    (1 | 3 | 4, 0) | (2, 5) | (7, 1) | (8, 2) => {
                        selected = LegacyPreferenceValue::Other {
                            field_number: Some(value.number),
                        }
                    }
                    _ => {}
                }
            }
        }
    }
    Ok(LegacyPreferenceEntry {
        key,
        value: selected,
        raw_entry: bytes.to_vec(),
    })
}

struct ProtoField<'a> {
    number: u32,
    wire: u8,
    payload: &'a [u8],
}
struct ProtoReader<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> ProtoReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, at: 0 }
    }
    fn varint(&mut self) -> Result<u64, String> {
        let mut result = 0u64;
        for index in 0..10 {
            let byte = *self.bytes.get(self.at).ok_or("旧 protobuf varint 被截断")?;
            self.at += 1;
            if index == 9 && byte > 1 {
                return Err("旧 protobuf varint 溢出".into());
            }
            result |= u64::from(byte & 0x7f) << (index * 7);
            if byte & 0x80 == 0 {
                return Ok(result);
            }
        }
        Err("旧 protobuf varint 无终止符".into())
    }
    fn advance(&mut self, count: usize) -> Result<&'a [u8], String> {
        let end = self.at.checked_add(count).ok_or("旧 protobuf 长度溢出")?;
        let slice = self
            .bytes
            .get(self.at..end)
            .ok_or("旧 protobuf 内容被截断")?;
        self.at = end;
        Ok(slice)
    }
    fn next(&mut self) -> Result<Option<ProtoField<'a>>, String> {
        self.next_depth(0)
    }
    fn next_depth(&mut self, depth: usize) -> Result<Option<ProtoField<'a>>, String> {
        if self.at == self.bytes.len() {
            return Ok(None);
        }
        if depth > MAX_DEPTH {
            return Err("旧 protobuf group 嵌套过深".into());
        }
        let tag = self.varint()?;
        let number = u32::try_from(tag >> 3).map_err(|_| "旧 protobuf 字段号溢出")?;
        if number == 0 || number > 0x1fff_ffff {
            return Err("旧 protobuf 字段号无效".into());
        }
        let wire = (tag & 7) as u8;
        let payload = match wire {
            0 => {
                let start = self.at;
                self.varint()?;
                &self.bytes[start..self.at]
            }
            1 => self.advance(8)?,
            5 => self.advance(4)?,
            2 => {
                let length = usize::try_from(self.varint()?).map_err(|_| "旧 protobuf 长度溢出")?;
                self.advance(length)?
            }
            3 => {
                let start = self.at;
                loop {
                    let before = self.at;
                    let inner = self.varint()?;
                    if inner & 7 == 4 {
                        if inner >> 3 != u64::from(number) {
                            return Err("旧 protobuf group 结束字段不匹配".into());
                        }
                        break;
                    }
                    self.at = before;
                    self.next_depth(depth + 1)?
                        .ok_or("旧 protobuf group 缺少结束符")?;
                }
                &self.bytes[start..self.at]
            }
            _ => return Err("旧 protobuf wire 类型无效或 group 结束符不匹配".into()),
        };
        Ok(Some(ProtoField {
            number,
            wire,
            payload,
        }))
    }
}

#[derive(Default)]
struct Budget {
    bytes: u64,
    entries: usize,
}
impl Budget {
    fn entry(&mut self) -> Result<(), String> {
        self.entries = self.entries.checked_add(1).ok_or("旧数据项目数溢出")?;
        if self.entries > MAX_ENTRIES {
            return Err("旧数据项目超出候选预算，拒绝截断扫描".into());
        }
        Ok(())
    }
    fn bytes(&mut self, length: u64) -> Result<(), String> {
        if length > MAX_FILE_BYTES {
            return Err("旧文件超过128 MiB，需分段迁移；原文件未修改".into());
        }
        self.bytes = self.bytes.checked_add(length).ok_or("旧数据字节预算溢出")?;
        if self.bytes > MAX_TOTAL_BYTES {
            return Err("旧数据超过256 MiB候选预算，需分段迁移；拒绝部分导入".into());
        }
        self.entry()
    }
}

#[derive(Clone, Copy)]
enum Shape {
    Object,
    Array,
}
impl Shape {
    fn require(self, value: &Value, relative: &str) -> Result<(), String> {
        if matches!(self, Self::Object) && value.is_object()
            || matches!(self, Self::Array) && value.is_array()
        {
            Ok(())
        } else {
            Err(format!(
                "旧数据 {relative} 的顶层格式不匹配，拒绝默认空值替代"
            ))
        }
    }
}

fn read_document(
    root: &Path,
    path: &Path,
    kind: LegacyDocumentKind,
    budget: &mut Budget,
) -> Result<LegacyDocument, String> {
    let source = read_blob(root, path, None, budget)?;
    let value = parse_json(&source)?;
    Ok(LegacyDocument {
        kind,
        source,
        value,
    })
}
fn parse_json(source: &LegacyBlob) -> Result<Value, String> {
    serde_json::from_slice(&source.original_bytes).map_err(|error| {
        format!(
            "旧数据 {} 的 JSON 损坏（{}:{}），原文件未修改",
            source.relative_path,
            error.line(),
            error.column()
        )
    })
}
fn read_blob(
    root: &Path,
    path: &Path,
    prefix: Option<u64>,
    budget: &mut Budget,
) -> Result<LegacyBlob, String> {
    let relative = relative_name(root, path)?;
    let path = checked_existing(root, Path::new(&relative))?.ok_or("旧数据文件在读取前消失")?;
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        // A path swapped for a FIFO between validation and open must not block
        // the runtime actor. fstat below still admits regular files only.
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let file = options
        .open(&path)
        .map_err(|_| format!("无法打开旧数据：{relative}"))?;
    let before = file.metadata().map_err(|_| "无法读取旧文件状态")?;
    if !before.is_file() {
        return Err(format!("旧数据不是普通文件：{relative}"));
    }
    let reopened_path = fs::canonicalize(&path).map_err(|_| "旧文件路径在打开期间改变")?;
    let linked_before = fs::symlink_metadata(&path).map_err(|_| "旧文件路径在打开期间消失")?;
    if reopened_path != path
        || !reopened_path.starts_with(root)
        || !same_file(&before, &linked_before)
    {
        return Err("旧文件在打开期间更换或路径越界，拒绝读取".into());
    }
    let length = prefix.unwrap_or(before.len());
    if length > before.len() {
        return Err(format!("旧日志 {relative} 短于 meta 提交边界，拒绝截断"));
    }
    budget.bytes(length)?;
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(usize::try_from(length).map_err(|_| "旧文件长度超出平台寻址范围")?)
        .map_err(|_| "旧文件候选内存不足，拒绝部分读取")?;
    (&file)
        .take(length)
        .read_to_end(&mut bytes)
        .map_err(|_| format!("读取旧文件失败：{relative}"))?;
    if bytes.len() as u64 != length {
        return Err(format!("旧文件 {relative} 在读取期间缩短"));
    }
    let after = file.metadata().map_err(|_| "无法复核旧文件状态")?;
    let linked = fs::symlink_metadata(&path).map_err(|_| "旧文件在读取期间消失")?;
    if !same_file(&before, &after) || !same_file(&after, &linked) {
        return Err(format!("旧文件 {relative} 在读取期间改变，请重新读取"));
    }
    Ok(blob_from_bytes(root, relative, bytes))
}
fn blob_from_bytes(root: &Path, relative_path: String, original_bytes: Vec<u8>) -> LegacyBlob {
    let sha256 = Sha256::digest(&original_bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    LegacyBlob {
        owning_root: root.to_owned(),
        relative_path,
        original_bytes,
        sha256,
    }
}
fn same_file(left: &Metadata, right: &Metadata) -> bool {
    if !right.is_file()
        || right.file_type().is_symlink()
        || left.len() != right.len()
        || left.modified().ok() != right.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if left.dev() != right.dev() || left.ino() != right.ino() {
            return false;
        }
    }
    true
}
fn canonical_root(root: &Path) -> Result<PathBuf, String> {
    let root = fs::canonicalize(root).map_err(|_| "无法读取明确提供的旧应用数据根目录")?;
    if !root.is_dir() {
        return Err("旧应用数据根不是目录".into());
    }
    Ok(root)
}
fn checked_existing(root: &Path, relative: &Path) -> Result<Option<PathBuf>, String> {
    if relative.as_os_str().is_empty()
        || relative
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err("旧数据候选路径越界".into());
    }
    let mut path = root.to_path_buf();
    for component in relative.components() {
        path.push(component);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err("旧数据路径不可读取".into()),
        };
        if metadata.file_type().is_symlink() {
            return Err("旧数据路径含符号链接，拒绝扫描其它目录".into());
        }
    }
    let canonical = fs::canonicalize(&path).map_err(|_| "无法解析旧数据路径")?;
    if !canonical.starts_with(root) {
        return Err("旧数据路径逃逸出明确提供的数据根".into());
    }
    Ok(Some(canonical))
}
fn sorted_entries(directory: &Path) -> Result<Vec<PathBuf>, String> {
    let mut entries = Vec::new();
    for entry in fs::read_dir(directory).map_err(|_| "无法读取旧数据目录")? {
        if entries.len() >= MAX_ENTRIES {
            return Err("旧目录项目超出预算，拒绝部分扫描".into());
        }
        entries.push(entry.map_err(|_| "旧目录项目不可读取")?.path());
    }
    entries.sort();
    Ok(entries)
}
fn relative_name(root: &Path, path: &Path) -> Result<String, String> {
    let relative = path
        .strip_prefix(root)
        .map_err(|_| "旧文件不属于明确提供的根目录")?;
    Ok(relative
        .components()
        .map(|c| {
            c.as_os_str()
                .to_str()
                .ok_or("旧数据文件名不是 UTF-8".to_string())
        })
        .collect::<Result<Vec<_>, _>>()?
        .join("/"))
}
fn file_name(path: &Path) -> Result<String, String> {
    path.file_name()
        .and_then(|s| s.to_str())
        .map(str::to_owned)
        .ok_or("旧数据文件名无效".into())
}
fn single_component(value: &str) -> bool {
    !value.is_empty() && ![".", ".."].contains(&value) && !value.contains(['/', '\\', ':', '\0'])
}
fn safe_id(value: &str) -> Result<(), String> {
    if !single_component(value) || value.len() > 256 {
        Err("旧数据身份不能用作安全文件名".into())
    } else {
        Ok(())
    }
}
fn required_string(value: &Value, field: &str, relative: &str) -> Result<String, String> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| format!("旧数据 {relative} 缺少有效 {field}"))
}
fn required_array<'a>(
    value: &'a Value,
    field: &str,
    relative: &str,
) -> Result<&'a Vec<Value>, String> {
    value
        .get(field)
        .and_then(Value::as_array)
        .ok_or_else(|| format!("旧数据 {relative} 的 {field} 不是列表"))
}
fn optional_u64(value: &Value, field: &str, default: u64, relative: &str) -> Result<u64, String> {
    value.get(field).map_or(Ok(default), |v| {
        v.as_u64()
            .ok_or_else(|| format!("旧数据 {relative} 的 {field} 不是非负整数"))
    })
}
fn utf8<'a>(bytes: &'a [u8], kind: &str) -> Result<&'a str, String> {
    std::str::from_utf8(bytes).map_err(|_| format!("旧 {kind} 含无效 UTF-8"))
}

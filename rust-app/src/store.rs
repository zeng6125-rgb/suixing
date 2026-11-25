//! Actor-owned storage for the independent Rust application.
//!
//! Production Android reads and writes Compose's settings, memory and committed chat logs.
//! Independent previews retain their own documents. Settings serialization omits plaintext keys.
//! Usage is a separate run-ID ledger: conversation deletion retains it. There is no atomic
//! transaction between this ledger and the conversation documents.
//! Pending records survive process death unchanged; only an explicit final result closes them.

mod avatars;
mod conversation_preferences;
mod history;
mod legacy;
mod performance;
mod profile;
mod profile_turns;
mod queued_commands;
mod reading;
mod schedule_runtime;
mod settings_policy;
mod settings_transaction;
pub(crate) mod shared_chat;
mod shared_children;
mod shared_goal;
mod shared_mcp;
mod shared_memory;
mod shared_plugins;
mod shared_preferences;
mod shared_schedules;
mod shared_settings;
mod shared_skills;
#[cfg(test)]
mod shared_tests;
mod shared_todos;
mod shared_usage;
mod steering_commands;
pub(crate) mod usage;
pub(crate) mod workspace;
pub use history::CommittedHistory;
pub use schedule_runtime::{FirePhase, FireRecord, FireSummary, ScheduleExecution, ScheduleStatus};

use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs::{self, File, Metadata, OpenOptions},
    io::{self, BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize, de::DeserializeOwned};

use crate::platform::secrets::{
    SecretBox, SecretCipher, SecretEdit, SettingsSecretEdits, SettingsSecretTarget,
    StoredMcpSecrets, StoredSecret, StoredSettingsSecrets, prepare_fields, prepare_secret,
};
use crate::state::{Conversation, FileEntry, Memory, Message, QueuedTurn, Settings, UsageRecord};

const VERSION: u32 = 1;
const MAX_CONVERSATIONS: usize = 8_192;
const MAX_MESSAGES: usize = 131_072;
const MAX_MEMORIES: usize = 4_096;
// The snapshot ledger is bounded. Reaching a limit rejects the new entry without evicting
// history; expanding beyond these limits requires a segmented ledger, not silent pruning.
const MAX_USAGE_RECORDS: usize = 65_536;
const MAX_DIRECTORY_ENTRIES: usize = 8_192;
const MAX_SETTINGS_BYTES: u64 = 256 * 1_024;
const MAX_CHAT_BYTES: u64 = 32 * 1_024 * 1_024;
pub(crate) const MAX_CHILD_BYTES: u64 = 8 * 1024 * 1024;
const VISIBLE_CHILDREN: usize = 32;
const MAX_MEMORY_BYTES: u64 = 16 * 1_024 * 1_024;
const MAX_USAGE_BYTES: u64 = 32 * 1_024 * 1_024;
const MAX_CHAT_TEXT_BYTES: usize = 512 * 1_024 * 1_024;
const MAX_MEMORY_TEXT_BYTES: usize = 4 * 1_024 * 1_024;
const MAX_USAGE_TEXT_BYTES: usize = 8 * 1_024 * 1_024;
const MAX_PREVIEW_BYTES: usize = 128 * 1_024;
const MAX_PLUGIN_ENV_BYTES: u64 = 256 * 1024;
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

pub struct Store {
    pub settings: Settings,
    settings_revision: u64,
    settings_secrets: StoredSettingsSecrets,
    mcp_secrets: BTreeMap<String, StoredMcpSecrets>,
    locked_mcp: HashSet<String>,
    secret_warnings: Vec<String>,
    root: PathBuf,
    namespace_root: PathBuf,
    legacy_source_root: PathBuf,
    shared_files_root: Option<PathBuf>,
    chat_directory: PathBuf,
    child_directory: PathBuf,
    plugin_directory: PathBuf,
    /// Never serialized. Android Keystore persistence is a separate migration item.
    child_usage_warning: Option<String>,
    mcp_servers: Vec<crate::mcp::ServerConfig>,
    mcp_extra: shared_mcp::ExtraFields,
    workspace: PathBuf,
    conversations: Vec<Conversation>,
    conversation_preferences: crate::conversation_preferences::Document,
    conversation_preferences_dirty: bool,
    conversation_preferences_confirmed: bool,
    memories: Vec<Memory>,
    todos: crate::todos::Board,
    usage: Vec<UsageRecord>,
    skills: Vec<crate::skills::Skill>,
    skills_revision: u64,
    schedules: Vec<crate::schedules::Schedule>,
    schedule_execution: ScheduleExecution,
    history_generation: String,
    history_revision: u64,
    history_revisions: HashMap<(String, Option<String>), u64>,
    reading_positions: Vec<crate::display::ReadPosition>,
    workspace_mappings: workspace::WorkspaceMappings,
    jobs: Option<std::sync::Arc<crate::jobs::Manager>>,
    goal: crate::goal::Goal,
    /// A rename followed by a failed directory sync has an uncertain durability outcome.
    /// Further writes must not overwrite that visible document using an older actor snapshot.
    write_blocked: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SettingsDocument {
    version: u32,
    settings: Settings,
    #[serde(default)]
    secrets: StoredSettingsSecrets,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct MemoryDocument {
    version: u32,
    memories: Vec<Memory>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SkillDocument {
    version: u32,
    skills: Vec<crate::skills::Skill>,
    #[serde(default)]
    revision_clock: u64,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct GoalDocument {
    version: u32,
    goal: crate::goal::Goal,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct McpDocument {
    version: u32,
    servers: Vec<crate::mcp::ServerConfig>,
    #[serde(default)]
    secrets: BTreeMap<String, StoredMcpSecrets>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ScheduleDocument {
    version: u32,
    schedules: Vec<crate::schedules::Schedule>,
    #[serde(default)]
    execution: ScheduleExecution,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ChildDocument {
    version: u32,
    parent_chat: String,
    parent_run: String,
    record: crate::subagents::Record,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct UsageDocument {
    version: u32,
    records: Vec<UsageRecord>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ChatDocument {
    version: u32,
    conversation: Conversation,
    messages: Vec<Message>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    queued_turns: Vec<QueuedTurn>,
}

impl Store {
    pub fn open(data_directory: PathBuf) -> Result<Self, String> {
        Self::open_with_shared_files(data_directory, None)
    }

    pub(crate) fn open_with_shared_files(
        data_directory: PathBuf,
        shared_files_root: Option<PathBuf>,
    ) -> Result<Self, String> {
        let shared_files_root = shared_files_root
            .map(|path| {
                fs::canonicalize(&path).map_err(|error| fs_error("解析共用数据目录", &path, error))
            })
            .transpose()?;
        fs::create_dir_all(&data_directory)
            .map_err(|error| fs_error("创建数据目录", &data_directory, error))?;
        let namespace_root = fs::canonicalize(&data_directory)
            .map_err(|error| fs_error("解析数据目录", &data_directory, error))?;
        let root = crate::legacy_migration::resolve_active(&namespace_root)?
            .unwrap_or_else(|| namespace_root.clone());
        let chat_directory = owned_directory(&root, "conversations")?;
        let child_directory = owned_directory(&root, "subagents")?;
        let plugin_directory = shared_plugins::directory(&root, shared_files_root.as_deref())?;
        let workspace = workspace::select_workspace(
            owned_directory(&namespace_root, "workspace")?,
            shared_files_root.as_deref(),
        )?;
        let mut secret_warnings = Vec::new();
        let preference_result = optional_json::<crate::conversation_preferences::Document>(
            &root,
            &root.join("conversation-preferences.json"),
            4 * 1024 * 1024,
        )
        .and_then(|document| {
            let document = match document {
                Some(document) => document,
                None => crate::conversation_preferences::from_archived(&namespace_root)?
                    .unwrap_or_default(),
            };
            document.validate()?;
            Ok(document)
        });
        let conversation_preferences_confirmed = preference_result.is_ok();
        let mut conversation_preferences = preference_result.unwrap_or_default();
        let mut reading_positions = optional_json::<reading::ReadingDocument>(
            &root,
            &root.join("reading-positions.json"),
            crate::legacy_reading::DOCUMENT_BYTES,
        )?
        .map(|v| v.positions)
        .unwrap_or_default();
        reading::validate(&reading_positions)?;
        let workspace_mappings = optional_json::<workspace::WorkspaceMappings>(
            &root,
            &root.join("workspace-mappings.json"),
            32 * 1024 * 1024,
        )?
        .unwrap_or_default();
        workspace_mappings.validate()?;
        let shared_mcp = shared_files_root.as_deref().map(shared_mcp::read).transpose()?.flatten();
        let (mcp, mcp_extra) = if let Some(shared) = shared_mcp {
            (Some(shared.document), shared.unknown)
        } else {
            let mut private = optional_json::<McpDocument>(
                &root, &root.join("mcp.json"), shared_mcp::MAX_BYTES,
            )?;
            if let (Some(files), Some(document)) = (shared_files_root.as_deref(), &mut private)
                && !document.servers.is_empty()
            {
                version(document.version)?;
                shared_mcp::write(files, &document.servers, &mut document.secrets, &BTreeMap::new())
                    .map_err(|failure| failure.message)?;
            }
            (private, BTreeMap::new())
        };
        let (mut mcp_servers, mcp_secrets) = match mcp {
            Some(document) => {
                version(document.version)?;
                (crate::mcp::normalize_configs(document.servers)?, document.secrets)
            }
            None => (Vec::new(), BTreeMap::new()),
        };
        let locked_mcp = hydrate_mcp_secrets(&mut mcp_servers, &mcp_secrets, &mut secret_warnings);

        let (compose_settings, rust_settings) = shared_files_root
            .as_deref()
            .map(shared_settings::read)
            .transpose()?
            .unwrap_or_default();
        let rust_settings = match rust_settings {
            Some(settings) => Some(settings),
            None => optional_json(&root, &root.join("settings.json"), MAX_SETTINGS_BYTES)?,
        };
        let (settings, settings_secrets, raw_settings) = match rust_settings {
            Some(raw) => {
                let document: SettingsDocument =
                    serde_json::from_value(raw.clone()).map_err(|_| "设置文档格式无法读取")?;
                version(document.version)?;
                validate_settings(&document.settings)?;
                (
                    document.settings,
                    document.secrets,
                    raw.get("settings")
                        .cloned()
                        .unwrap_or(serde_json::json!({})),
                )
            }
            None => (
                Settings::default(),
                StoredSettingsSecrets::default(),
                serde_json::json!({}),
            ),
        };
        let memories = if let Some(files) = shared_files_root.as_deref() {
            shared_memory::read(files)?
        } else {
            match optional_json::<MemoryDocument>(
                &root,
                &root.join("memories.json"),
                MAX_MEMORY_BYTES,
            )? {
                Some(document) => {
                    version(document.version)?;
                    validate_memories(&document.memories)?;
                    document.memories
                }
                None => Vec::new(),
            }
        };
        let shared_todo_items = shared_files_root
            .as_deref()
            .map(shared_todos::read)
            .transpose()?
            .flatten();
        let todos = if let Some(items) = shared_todo_items {
            crate::todos::Board {
                items,
                ..Default::default()
            }
        } else {
            let board = match optional_json::<crate::todos::Board>(
                &root,
                &root.join("todos.json"),
                3 * 1024 * 1024,
            )? {
                Some(board) => board,
                None => {
                    let legacy = optional_json::<Vec<crate::legacy_migration::ImportedTodo>>(
                        &root,
                        &root.join("legacy-todos.json"),
                        3 * 1024 * 1024,
                    )?
                    .unwrap_or_default();
                    let mut items: Vec<_> = legacy
                        .into_iter()
                        .map(|item| crate::todos::Item {
                            id: item.id,
                            subject: item.text,
                            status: item.status,
                            extra: item.extra,
                        })
                        .collect();
                    crate::todos::normalize(&mut items)?;
                    crate::todos::Board {
                        items,
                        ..Default::default()
                    }
                }
            };
            crate::todos::validate(&board)?;
            if let Some(files) = shared_files_root
                .as_deref()
                .filter(|_| !board.items.is_empty())
            {
                shared_todos::write(files, &board).map_err(|failure| failure.message)?;
            }
            board
        };
        let usage =
            match optional_json::<UsageDocument>(&root, &root.join("usage.json"), MAX_USAGE_BYTES)?
            {
                Some(document) => {
                    version(document.version)?;
                    validate_usage(&document.records)?;
                    // A pending row is evidence of an attempted run, not proof of completion.
                    // Reload preserves it and all unknown token values without inference.
                    document.records
                }
                None => Vec::new(),
            };

        let (schedules, schedule_execution) =
            schedule_runtime::load(&root, shared_files_root.as_deref())?;
        let (mut skills, mut skills_revision) = match optional_json::<SkillDocument>(
            &root,
            &root.join("skills.json"),
            128 * 1024 * 1024,
        )? {
            Some(document) => {
                version(document.version)?;
                crate::skills::validate_all(&document.skills)?;
                let revision = document.revision_clock.max(
                    document
                        .skills
                        .iter()
                        .map(|item| item.revision)
                        .max()
                        .unwrap_or(0),
                );
                (document.skills, revision)
            }
            None => (Vec::new(), 0),
        };
        if let Some(files) = shared_files_root.as_deref() {
            let common = shared_skills::read(files, &workspace)?;
            skills.retain(|skill| {
                !shared_skills::is_common(skill) && !common.iter().any(|shared| shared.id == skill.id)
            });
            for mut skill in common {
                skills_revision = skills_revision.checked_add(1).ok_or("技能版本已耗尽")?;
                skill.revision = skills_revision;
                skills.push(skill);
            }
        }
        let shared_goal = shared_files_root.as_deref().map(shared_goal::read).transpose()?.flatten();
        let seed_goal = shared_goal.is_none();
        let mut goal = if let Some(goal) = shared_goal {
            goal
        } else {
            match optional_json::<GoalDocument>(&root, &root.join("goal.json"), shared_goal::LIMIT)? {
                Some(document) => {
                    version(document.version)?;
                    document.goal
                }
                None => crate::goal::Goal::default(),
            }
        };
        goal.validate()?;
        if goal.status == crate::goal::Status::Active {
            goal.status = crate::goal::Status::Paused;
            goal.wake_at = 0;
            goal.detail = "上次运行已中断，请明确继续后恢复".into();
        }
        if seed_goal && !goal.text.is_empty()
            && let Some(files) = shared_files_root.as_deref()
        {
            shared_goal::write(files, &goal).map_err(|failure| failure.message)?;
        }
        let mut conversations = shared_files_root
            .as_deref()
            .map(shared_chat::list)
            .transpose()?
            .unwrap_or_default();
        if shared_files_root.is_none() {
            let mut ids = HashSet::new();
            let entries = fs::read_dir(&chat_directory)
                .map_err(|error| fs_error("读取会话目录", &chat_directory, error))?;
            for (index, entry) in entries.enumerate() {
                if index >= MAX_DIRECTORY_ENTRIES {
                    return Err("会话目录条目超过读取预算，未加载或覆盖任何会话".into());
                }
                let entry =
                    entry.map_err(|error| fs_error("读取会话目录条目", &chat_directory, error))?;
                let name = entry.file_name();
                let name = name.to_str().ok_or("会话文件名不是 UTF-8，无法安全加载")?;
                // An interrupted pre-rename write may leave its uniquely named temporary file.
                if is_temporary_name(name) {
                    continue;
                }
                let segmented = name.ends_with(".segments.json");
                let id = name
                    .strip_suffix(if segmented { ".segments.json" } else { ".json" })
                    .ok_or("会话目录包含未知文件，无法安全加载")?;
                validate_id(id)?;
                if !segmented && chat_directory.join(format!("{id}.segments.json")).exists() {
                    // The committed index supersedes a retained pre-segmentation document.
                    continue;
                }
                if conversations.len() >= MAX_CONVERSATIONS {
                    return Err("会话数量超过读取预算，未覆盖现有数据".into());
                }
                let document: ChatDocument = if segmented {
                    let (conversation, messages, queued_turns) =
                        crate::legacy_migration::read_chat_archive(&root, id)?;
                    ChatDocument {
                        version: VERSION,
                        conversation,
                        messages,
                        queued_turns,
                    }
                } else {
                    required_json(&root, &entry.path(), MAX_CHAT_BYTES)?
                };
                validate_chat(&document, id)?;
                if !ids.insert(document.conversation.id.clone()) {
                    return Err("会话记录存在重复 ID".into());
                }
                conversations.push(document.conversation);
            }
        }

        if let Some(order) = shared_files_root.as_deref().and_then(conversation_preferences::shared_order) {
            let known = conversations.iter().map(|chat| chat.id.clone()).collect();
            conversation_preferences.order = order.pruned(&known);
        }
        if let Some(files) = shared_files_root.as_deref() {
            match shared_preferences::read(files, shared_preferences::READS) {
                Ok(Some(mut preferences)) => {
                    conversation_preferences.completed_unread = preferences.string_sets
                        .remove("completed_unread").unwrap_or_default().into_iter()
                        .filter(|id| crate::conversation_preferences::id_valid(id)).collect();
                }
                Ok(None) => {}
                Err(error) => secret_warnings.push(format!("读取共用未读状态失败：{error}")),
            }
            match reading::shared_positions(files) {
                Ok(Some(positions)) => {
                    let shared_sources: HashSet<_> = positions.iter().map(|position| &position.source).collect();
                    reading_positions.retain(|position| !shared_sources.contains(&position.source));
                    reading_positions.extend(positions);
                }
                Ok(None) => {}
                Err(error) => secret_warnings.push(format!("读取共用阅读位置失败：{error}")),
            }
        }

        let mut store = Self {
            settings_revision: 1,
            settings,
            settings_secrets,
            mcp_secrets,
            locked_mcp,
            secret_warnings,
            legacy_source_root: root.clone(),
            shared_files_root,
            root,
            namespace_root,
            chat_directory,
            child_directory,
            plugin_directory,
            child_usage_warning: None,
            mcp_servers,
            mcp_extra,
            workspace,
            conversations,
            conversation_preferences,
            conversation_preferences_dirty: false,
            conversation_preferences_confirmed,
            memories,
            todos,
            usage,
            skills,
            skills_revision,
            schedules,
            schedule_execution,
            history_generation: crate::controller::unique_id(),
            history_revision: crate::controller::next_display_revision(),
            history_revisions: HashMap::new(),
            reading_positions,
            workspace_mappings,
            jobs: None,
            goal,
            write_blocked: None,
        };
        store.reconcile_behavior_document(&raw_settings)?;
        if let Some(raw) = compose_settings {
            shared_settings::apply(&raw, &mut store.settings, &mut store.settings_secrets)?;
        }
        hydrate_settings_secrets(
            &mut store.settings,
            &store.settings_secrets,
            &mut store.secret_warnings,
        );
        store.settings.seed_legacy_provider();
        match crate::legacy_reading::from_archived(&store.namespace_root) {
            Ok(imported) => {
                let mut sources = store
                    .reading_positions
                    .iter()
                    .map(|position| position.source.clone())
                    .collect::<HashSet<_>>();
                let mut candidate = store.reading_positions.clone();
                for position in imported {
                    if store
                        .conversation(position.source.conversation_id())
                        .is_ok()
                        && sources.insert(position.source.clone())
                    {
                        candidate.push(position);
                    }
                }
                if let Err(error) = reading::validate(&candidate) {
                    store
                        .secret_warnings
                        .push(format!("旧阅读位置转换尚未完成：{error}"));
                } else {
                    store.reading_positions = candidate;
                }
            }
            Err(error) => store
                .secret_warnings
                .push(format!("旧阅读位置归档转换尚未完成：{error}")),
        }
        Ok(store)
    }

    pub(crate) fn conversation_info_target(
        &self,
        id: &str,
    ) -> Result<crate::conversation_manager::Target, String> {
        validate_id(id)?;
        let conversation = self.conversation(id)?;
        if let Some(files) = self.shared_files_root.as_deref() {
            return Ok(crate::conversation_manager::Target {
                root: files.to_owned(),
                document: files.join(format!("chats/store/{id}.meta")),
                manifest: files.join(format!("chats/store/{id}.meta")),
                conversation: conversation.clone(),
            });
        }
        Ok(crate::conversation_manager::Target {
            root: self.root.clone(),
            document: self.chat_directory.join(format!("{id}.json")),
            manifest: self.chat_directory.join(format!("{id}.segments.json")),
            conversation: conversation.clone(),
        })
    }

    pub fn conversations(&self) -> Vec<Conversation> {
        let mut conversations = self.conversations.clone();
        conversations.sort_by(|a, b| {
            b.updated_at
                .cmp(&a.updated_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        conversations
    }
    pub fn conversation_summaries(&self) -> Vec<crate::display::ConversationSummary> {
        let mut summaries: Vec<_> = self
            .conversations
            .iter()
            .map(crate::display::ConversationSummary::from)
            .collect();
        summaries.sort_by(|a, b| {
            b.updated_at
                .cmp(&a.updated_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        summaries
    }
    pub fn jobs(&self) -> Option<std::sync::Arc<crate::jobs::Manager>> {
        self.jobs.clone()
    }
    pub fn initialize_jobs(&mut self) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            let paths = crate::platform::android::paths().ok_or("Android路径尚未初始化")?;
            let environment = crate::shell_environment::ShellEnvironment::new(
                crate::shell_environment::FrozenPaths::from_platform(
                    &paths,
                    self.workspace.clone(),
                ),
            )?;
            self.jobs = Some(crate::jobs::Manager::open(crate::jobs::Config::new(
                self.namespace_root.join("jobs"),
                environment,
            ))?);
        }
        Ok(())
    }

    pub fn memories(&self) -> Vec<Memory> {
        let mut memories = self.memories.clone();
        memories.sort_by(|a, b| {
            b.updated_at
                .cmp(&a.updated_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        memories
    }

    pub fn todos(&self) -> &crate::todos::Board {
        &self.todos
    }
    pub fn writes_blocked(&self) -> bool {
        self.write_blocked.is_some()
    }

    pub fn mutate_todos(
        &mut self,
        operation: crate::todos::Operation,
    ) -> Result<serde_json::Value, String> {
        let (offset, limit) = match operation {
            crate::todos::Operation::Read { offset, limit } => (offset, limit),
            _ => (0, 200),
        };
        if matches!(operation, crate::todos::Operation::Read { .. }) {
            return Ok(crate::todos::result(&self.todos, offset, limit, false));
        }
        let mut next = crate::todos::apply(&self.todos, operation)?;
        if let Some(files) = self.shared_files_root.as_deref() {
            self.writable()?;
            shared_todos::preserve_item_fields(&self.todos, &mut next);
            if let Err(failure) = shared_todos::write(files, &next) {
                if failure.replaced {
                    self.write_blocked = Some(failure.message.clone());
                }
                return Err(failure.message);
            }
        } else {
            self.persist(&self.root.join("todos.json"), &next, 3 * 1024 * 1024)?;
        }
        self.todos = next;
        Ok(crate::todos::result(&self.todos, offset, limit, true))
    }

    pub fn set_legacy_source_root(&mut self, root: PathBuf) {
        self.legacy_source_root = root;
    }
    pub(crate) fn state_namespace_root(&self) -> &Path {
        &self.namespace_root
    }
    pub(crate) fn active_namespace(&self) -> String {
        self.root.to_string_lossy().into_owned()
    }
    pub fn legacy_source_root(&self) -> &Path {
        &self.legacy_source_root
    }

    pub fn skills(&self) -> &[crate::skills::Skill] {
        &self.skills
    }

    pub fn schedules(&self) -> &[crate::schedules::Schedule] {
        &self.schedules
    }
    pub fn mutate_schedule(
        &mut self,
        operation: crate::schedules::Operation,
    ) -> Result<crate::schedules::Mutation, String> {
        self.writable()?;
        let result = crate::schedules::apply(
            &self.schedules,
            &operation,
            now()?,
            &crate::schedules::SystemLocalClock,
        )?;
        if result.changed {
            self.save_schedule_state(result.schedules.clone(), self.schedule_execution.clone())?;
        }
        Ok(result)
    }

    pub fn goal(&self) -> &crate::goal::Goal {
        &self.goal
    }

    fn save_goal(&mut self, mut goal: crate::goal::Goal) -> Result<(), String> {
        goal.updated_at = now()?.max(self.goal.updated_at);
        goal.validate()?;
        if let Some(files) = self.shared_files_root.as_deref() {
            self.writable()?;
            if let Err(failure) = shared_goal::write(files, &goal) {
                if failure.replaced {
                    self.write_blocked = Some(failure.message.clone());
                }
                return Err(failure.message);
            }
            self.goal = goal;
            return Ok(());
        }
        let document = GoalDocument {
            version: VERSION,
            goal,
        };
        self.persist(&self.root.join("goal.json"), &document, shared_goal::LIMIT)?;
        self.goal = document.goal;
        Ok(())
    }

    pub fn activate_goal(
        &mut self,
        chat_id: &str,
        run_id: &str,
        text: Option<&str>,
        expected_id: Option<&str>,
    ) -> Result<(), String> {
        use crate::goal::{Goal, Status};
        self.conversation(chat_id)?;
        if !self.goal.text.is_empty()
            && !self.goal.chat_id.is_empty()
            && self.goal.chat_id != chat_id
            && self.goal.status != Status::Completed
        {
            return Err("另一个会话拥有尚未结束的目标，请先清除该目标".into());
        }
        let goal = if let Some(text) = text {
            Goal {
                id: run_id.into(),
                chat_id: chat_id.into(),
                text: text.trim().into(),
                status: Status::Active,
                ..Default::default()
            }
        } else {
            if expected_id != Some(self.goal.id.as_str())
                || !self.goal.visible_in(Some(chat_id))
                || !matches!(self.goal.status, Status::Paused | Status::Blocked)
            {
                return Err("该目标已变化或当前不可继续".into());
            }
            let mut goal = self.goal.clone();
            goal.id = run_id.into();
            goal.chat_id = chat_id.into();
            goal.status = Status::Active;
            goal.detail.clear();
            goal.wake_at = 0;
            goal
        };
        self.save_goal(goal)
    }

    pub fn clear_goal(&mut self, expected_id: &str) -> Result<(), String> {
        if self.goal.id != expected_id {
            return Err("目标已变化，未清除新目标".into());
        }
        self.save_goal(crate::goal::Goal::default())
    }

    pub fn pause_goal(&mut self, run_id: &str, detail: &str) -> Result<(), String> {
        if self.goal.id != run_id || self.goal.status != crate::goal::Status::Active {
            return Ok(());
        }
        let mut goal = self.goal.clone();
        goal.status = crate::goal::Status::Paused;
        goal.detail = detail.chars().take(2000).collect();
        goal.wake_at = 0;
        self.save_goal(goal)
    }

    pub fn goal_command(
        &mut self,
        chat_id: &str,
        run_id: &str,
        command: crate::goal::Command,
    ) -> Result<Option<crate::goal::Goal>, String> {
        use crate::goal::{Command, Status};
        if matches!(command, Command::Read) {
            return Ok(self.goal.visible_in(Some(chat_id)).then(|| self.goal.clone()));
        }
        if self.goal.id != run_id || self.goal.chat_id != chat_id || self.goal.text.is_empty() {
            return Err("当前运行不再拥有该目标".into());
        }
        if matches!(command, Command::OwnedSnapshot) {
            return Ok(Some(self.goal.clone()));
        }
        if self.goal.status != Status::Active {
            return Err("当前目标已结束或暂停".into());
        }
        let mut goal = self.goal.clone();
        match command {
            Command::Report {
                status,
                note,
                wait_seconds,
            } => {
                if wait_seconds > 60
                    || wait_seconds > 0 && status != Status::Active
                    || status == Status::Paused
                {
                    return Err("目标报告状态或等待时间无效".into());
                }
                if note != goal.progress {
                    goal.revision = goal.revision.checked_add(1).ok_or("目标进度版本已耗尽")?;
                }
                goal.status = status;
                goal.progress = note;
                goal.detail.clear();
                goal.wake_at = if wait_seconds > 0 {
                    now()?
                        .checked_add((wait_seconds * 1000) as i64)
                        .ok_or("目标唤醒时间溢出")?
                } else {
                    0
                };
            }
            Command::ConsumeWake { wake_at } => {
                if goal.wake_at != wake_at {
                    return Err("目标等待状态已变化".into());
                }
                goal.wake_at = 0;
            }
            Command::Block { detail } => {
                goal.status = Status::Blocked;
                goal.detail = detail;
                goal.wake_at = 0;
            }
            Command::Read | Command::OwnedSnapshot => unreachable!(),
        }
        self.save_goal(goal)?;
        Ok(Some(self.goal.clone()))
    }

    pub fn save_skill(
        &mut self,
        skill: crate::skills::Skill,
    ) -> Result<crate::skills::Skill, String> {
        self.save_skill_with_copy(skill, None)
    }
    pub fn import_skill_document(
        &mut self,
        id: String,
        raw: String,
    ) -> Result<crate::skills::Skill, String> {
        self.writable()?;
        let skill = crate::skills::import_document(id, raw.clone())?;
        if self.skills.iter().any(|existing| existing.id == skill.id) {
            return Err("导入ID已存在，请使用新ID或在编辑器中修改现有技能".into());
        }
        self.save_skill_with_copy(skill, Some(raw))
    }
    fn save_skill_with_copy(
        &mut self,
        mut skill: crate::skills::Skill,
        raw: Option<String>,
    ) -> Result<crate::skills::Skill, String> {
        self.writable()?;
        skill.validate()?;
        let existing = self.skills.iter().position(|item| item.id == skill.id);
        let revision = existing
            .map(|index| self.skills[index].revision)
            .unwrap_or(0);
        if skill.revision != revision {
            return Err("技能已被修改，请重新读取后再保存".into());
        }
        skill.revision = self
            .skills_revision
            .checked_add(1)
            .ok_or("技能版本已耗尽")?;
        skill.updated_at = now()?.max(
            existing
                .map(|index| self.skills[index].updated_at)
                .unwrap_or(0),
        );
        if let Some(files) = self.shared_files_root.clone()
            && existing.is_none_or(|index| shared_skills::is_common(&self.skills[index]))
        {
            return self.save_shared_skill(&files, skill, raw.as_deref(), existing);
        }
        if skill.enabled
            && let Some(index) = existing
        {
            crate::skill_import::verify_workspace_document(&self.workspace(), &self.skills[index])?;
        }
        let needs_copy = raw.is_some()
            || skill.active_document_path().is_empty()
            || existing.is_some_and(|index| {
                let previous = &self.skills[index];
                previous.name != skill.name
                    || previous.description != skill.description
                    || previous.content != skill.content
            });
        let copy = if needs_copy {
            let staged = crate::skill_import::write_document_copy(
                &self.workspace(),
                &skill,
                raw.as_deref(),
            )?;
            if skill.document_path.is_empty() {
                skill.document_path = staged.path.clone();
                skill.files_path = staged.directory.clone();
            } else {
                skill.edited_document_path = staged.path.clone();
            }
            Some(staged)
        } else {
            None
        };
        let previous_edit = existing
            .map(|index| self.skills[index].edited_document_path.clone())
            .unwrap_or_default();
        let mut skills = self.skills.clone();
        match existing {
            Some(index) => skills[index] = skill.clone(),
            None => skills.push(skill.clone()),
        };
        let validation = crate::skills::validate_all(&skills);
        let document = SkillDocument {
            version: VERSION,
            skills,
            revision_clock: skill.revision,
        };
        let committed = validation.and_then(|_| {
            self.persist(&self.root.join("skills.json"), &document, 128 * 1024 * 1024)
        });
        if let Err(error) = committed {
            if let Some(copy) = &copy {
                let cleanup = crate::skill_import::finish_document_copy(
                    &self.workspace(),
                    copy,
                    false,
                    self.writes_blocked(),
                );
                return Err(match cleanup {
                    Ok(()) => error,
                    Err(cleanup_error) => format!("{error}；技能文档副本保留：{cleanup_error}"),
                });
            }
            return Err(error);
        }
        self.skills = document.skills;
        crate::skill_import::metadata_changed();
        self.skills_revision = document.revision_clock;
        if let Some(copy) = &copy {
            // Confirmed metadata protects the copy even if its Saved marker fails.
            let _ = crate::skill_import::finish_document_copy(&self.workspace(), copy, true, false);
        }
        if !previous_edit.is_empty() && previous_edit != skill.edited_document_path {
            let mut previous = skill.clone();
            previous.document_path = previous_edit;
            previous.edited_document_path.clear();
            if let Some(parent) = Path::new(&previous.document_path).parent() {
                previous.files_path = parent.to_string_lossy().into_owned();
                let _ = crate::skill_import::delete_installed_copy(
                    &self.workspace(),
                    &previous,
                    &self.skills,
                );
            }
        }
        Ok(skill)
    }

    pub(crate) fn reconcile_workspace_skills(
        &mut self,
        workspace: &Path,
        epoch: u64,
        incoming: Vec<crate::skills::Skill>,
    ) -> Result<String, String> {
        use crate::skill_import::is_live_workspace_skill;
        self.writable()?;
        if self.workspace != workspace
            || epoch == u64::MAX
            || crate::skill_import::metadata_epoch() != epoch
        {
            return Err("工作区或技能库在扫描后已变化，请重新扫描".into());
        }
        if let Some(files) = self.shared_files_root.clone() {
            return self.refresh_shared_skills(&files);
        }
        let mut skills = self.skills.clone();
        let mut revision = self.skills_revision;
        let mut updated = 0usize;
        let mut missing = 0usize;
        let mut conflicts = 0usize;
        let mut known_paths = std::collections::BTreeSet::new();
        let now = now()?;
        for mut candidate in incoming {
            known_paths.insert(candidate.document_path.clone());
            if let Some(index) = skills.iter().position(|item| item.id == candidate.id) {
                let old = &skills[index];
                if old.source == candidate.source && old.document_path == candidate.document_path {
                    if !is_live_workspace_skill(old) {
                        continue;
                    }
                } else {
                    conflicts += 1;
                    continue;
                }
                if old.name == candidate.name
                    && old.description == candidate.description
                    && old.content == candidate.content
                {
                    continue;
                }
                revision = revision.checked_add(1).ok_or("技能版本已耗尽")?;
                candidate.revision = revision;
                candidate.updated_at = now.max(old.updated_at);
                skills[index] = candidate;
            } else {
                revision = revision.checked_add(1).ok_or("技能版本已耗尽")?;
                candidate.revision = revision;
                candidate.updated_at = now;
                skills.push(candidate);
            }
            updated += 1;
        }
        for skill in &mut skills {
            if skill.enabled
                && is_live_workspace_skill(skill)
                && !known_paths.contains(&skill.document_path)
            {
                revision = revision.checked_add(1).ok_or("技能版本已耗尽")?;
                skill.enabled = false;
                skill.revision = revision;
                skill.updated_at = now.max(skill.updated_at);
                missing += 1;
            }
        }
        if revision != self.skills_revision {
            crate::skills::validate_all(&skills)?;
            let document = SkillDocument {
                version: VERSION,
                skills,
                revision_clock: revision,
            };
            self.persist(&self.root.join("skills.json"), &document, 128 * 1024 * 1024)?;
            self.skills = document.skills;
            self.skills_revision = revision;
            crate::skill_import::metadata_changed();
        }
        Ok(format!(
            "工作区 skills/ 扫描完成：{updated} 份新建或修改待审阅，{missing} 份原文件缺失已停用，{conflicts} 份同名记录已保留。"
        ))
    }

    /// One metadata transaction admits the complete immutable external collection.
    pub(crate) fn commit_imported_skills(
        &mut self,
        workspace: &Path,
        mut incoming: Vec<crate::skills::Skill>,
    ) -> Result<Vec<String>, String> {
        self.writable()?;
        if incoming.is_empty() {
            return Err("不完整技能候选不能提交空集合".into());
        }
        if self.workspace() != workspace {
            return Err("技能导入来源工作区已改变，未提交旧候选".into());
        }
        let mut skills = self.skills.clone();
        let mut revision = self.skills_revision;
        let mut ids = Vec::new();
        for skill in &mut incoming {
            if skills.iter().any(|s| s.id == skill.id) {
                return Err(format!(
                    "技能ID {} 已存在，未覆盖原技能或提交部分集合",
                    skill.id
                ));
            }
            skill.validate()?;
            if skill.enabled || skill.document_path.is_empty() || skill.files_path.is_empty() {
                return Err("外部技能必须关闭且绑定已保留的原文件目录".into());
            }
            for relative in [&skill.document_path, &skill.files_path] {
                let path = workspace.join(relative);
                if !path.exists()
                    || path.canonicalize().map_err(|_| "技能原文件目录无法核对")? != path
                {
                    return Err("技能导入原文件目录已变化，未提交".into());
                }
            }
            revision = revision.checked_add(1).ok_or("技能版本已耗尽")?;
            skill.revision = revision;
            skill.updated_at = now()?;
            ids.push(skill.id.clone());
            skills.push(skill.clone());
        }
        crate::skills::validate_all(&skills)?;
        let document = SkillDocument {
            version: VERSION,
            skills,
            revision_clock: revision,
        };
        self.persist(&self.root.join("skills.json"), &document, 128 * 1024 * 1024)?;
        self.skills = document.skills;
        crate::skill_import::metadata_changed();
        self.skills_revision = revision;
        Ok(ids)
    }

    pub fn delete_skill(
        &mut self,
        id: &str,
        expected_revision: u64,
    ) -> Result<Option<String>, String> {
        self.writable()?;
        let index = self
            .skills
            .iter()
            .position(|item| item.id == id)
            .ok_or("技能已不存在")?;
        if self.skills[index].revision != expected_revision {
            return Err("技能已被修改，请重新读取后再删除".into());
        }
        if let Some(files) = self.shared_files_root.clone()
            && shared_skills::is_common(&self.skills[index])
        {
            return self.delete_shared_skill(&files, index);
        }
        let mut skills = self.skills.clone();
        let removed = skills.remove(index);
        let document = SkillDocument {
            version: VERSION,
            skills,
            revision_clock: self.skills_revision,
        };
        self.persist(&self.root.join("skills.json"), &document, 128 * 1024 * 1024)?;
        self.skills = document.skills;
        crate::skill_import::metadata_changed();
        Ok(
            match crate::skill_import::delete_installed_copy(
                &self.workspace(),
                &removed,
                &self.skills,
            ) {
                Ok(false) => None,
                Ok(true) => Some("技能已删除；仍被其它技能引用的共享原文或资源已保留。".into()),
                Err(error) => Some(format!("技能已从技能库删除；原文或资源未完全回收：{error}")),
            },
        )
    }

    pub fn execute_skill(&mut self, operation: crate::skills::Operation) -> serde_json::Value {
        use crate::skills::Operation;
        use serde_json::json;
        let result: Result<serde_json::Value, String> = (|| {
            Ok(match operation {
                Operation::PrepareDownload { url, nonce } => {
                    self.writable()?;
                    let parsed = reqwest::Url::parse(&url).map_err(|_| "技能下载URL无效")?;
                    if !matches!(parsed.scheme(), "http" | "https")
                        || !parsed.username().is_empty()
                        || parsed.password().is_some()
                    {
                        return Err("技能下载URL必须为无内嵌凭据的HTTP/HTTPS".into());
                    }
                    json!({"ok":true,"prepared":crate::skills::DownloadTarget{url,workspace:self.workspace(),namespace:self.active_namespace(),nonce}})
                }
                Operation::Download { .. } | Operation::FrozenDownload(_) => {
                    return Err("技能下载必须使用实际任务冻结的审批请求".into());
                }
                Operation::ImportDownloaded { target, mut skills } => {
                    if self.active_namespace() != target.namespace {
                        return Err("技能下载工作区代次已改变，未提交旧调用".into());
                    }
                    let mut taken: std::collections::HashSet<String> =
                        self.skills.iter().map(|s| s.id.clone()).collect();
                    for skill in &mut skills {
                        let base = skill.id.clone();
                        let mut suffix = 2;
                        while taken.contains(&skill.id) {
                            let tail = format!("-{suffix}");
                            let mut end = (96 - tail.len()).min(base.len());
                            while end > 0 && !base.is_char_boundary(end) {
                                end -= 1;
                            }
                            skill.id = format!("{}{}", &base[..end], tail);
                            suffix += 1;
                        }
                        taken.insert(skill.id.clone());
                        skill.enabled = false;
                        skill.source = "downloaded".into();
                        skill.source_url = target.url.clone();
                    }
                    let paths: Vec<_> = skills.iter().map(|s| s.document_path.clone()).collect();
                    let committed = self.commit_imported_skills(&target.workspace, skills);
                    crate::skill_import::download_receipt(
                        &target.workspace,
                        &paths,
                        committed.is_ok(),
                        committed.is_err() && self.writes_blocked(),
                    );
                    let ids = committed?;
                    json!({"ok":true,"committed":true,"enabled":false,"ids":ids,"document_paths":paths,"notice":"Saved but disabled. Ask the user to review and enable in Skills before use."})
                }
                Operation::List => {
                    json!({"ok":true,"skills":self.skills.iter().filter(|item|item.enabled).map(|item|json!({"id":item.id,"name":item.name,"description":item.description,"revision":item.revision})).collect::<Vec<_>>()})
                }
                Operation::Read {
                    name,
                    offset,
                    max_bytes,
                } => {
                    let matches: Vec<_> = if let Some(exact) = self
                        .skills
                        .iter()
                        .find(|item| item.enabled && item.id == name)
                    {
                        vec![exact]
                    } else {
                        self.skills
                            .iter()
                            .filter(|item| {
                                item.enabled
                                    && (item.id.eq_ignore_ascii_case(&name)
                                        || item.name.eq_ignore_ascii_case(&name))
                            })
                            .collect()
                    };
                    if matches.len() != 1 {
                        return Err(
                            "技能未启用、不存在或名称有歧义，请使用skill_list返回的唯一ID".into(),
                        );
                    }
                    let skill = matches[0];
                    if self.shared_files_root.is_some() && shared_skills::is_common(skill) {
                        self.verify_shared_skill(skill)?;
                    } else {
                        crate::skill_import::verify_workspace_document(&self.workspace(), skill)?;
                    }
                    if offset > skill.content.len() || !skill.content.is_char_boundary(offset) {
                        return Err("技能分页位置无效，请从offset=0开始".into());
                    }
                    let mut end = offset
                        .saturating_add(max_bytes.min(32768))
                        .min(skill.content.len());
                    while end > offset && !skill.content.is_char_boundary(end) {
                        end -= 1;
                    }
                    if end == offset && offset < skill.content.len() {
                        return Err("分页字节预算无法容纳下一个UTF8字符".into());
                    }
                    json!({"ok":true,"id":skill.id,"name":skill.name,"content":&skill.content[offset..end],"revision":skill.revision,"offset":offset,"next_offset":(end<skill.content.len()).then_some(end),"total_bytes":skill.content.len(),"resource_path":if skill.files_path.is_empty(){None}else{Some(self.workspace().join(&skill.files_path))},"document_path":if skill.active_document_path().is_empty(){None}else{Some(self.workspace().join(skill.active_document_path()))},"original_document_path":if skill.document_path.is_empty(){None}else{Some(self.workspace().join(&skill.document_path))}})
                }
                Operation::Save {
                    name,
                    description,
                    content,
                    expected_revision,
                } => {
                    let existing = self.skills.iter().find(|item| item.id == name);
                    if existing.map(|item| item.revision) != expected_revision {
                        return Err("技能已存在或版本已变化；读取后提供expected_revision，新技能须省略该字段".into());
                    }
                    // Disabled user content cannot be re-enabled through a model tool.
                    let enabled = existing.is_none_or(|item| item.enabled);
                    let display_name = existing
                        .map(|item| item.name.clone())
                        .unwrap_or_else(|| name.clone());
                    let source = existing
                        .map(|item| item.source.clone())
                        .unwrap_or_else(|| "created_in_chat".into());
                    let skill = self.save_skill(crate::skills::Skill {
                        id: name.clone(),
                        name: display_name,
                        description,
                        content,
                        enabled,
                        source,
                        revision: expected_revision.unwrap_or(0),
                        updated_at: 0,
                        document_path: existing
                            .map(|s| s.document_path.clone())
                            .unwrap_or_default(),
                        files_path: existing.map(|s| s.files_path.clone()).unwrap_or_default(),
                        source_url: existing.map(|s| s.source_url.clone()).unwrap_or_default(),
                        edited_document_path: existing
                            .map(|s| s.edited_document_path.clone())
                            .unwrap_or_default(),
                    })?;
                    json!({"ok":true,"committed":true,"id":skill.id,"enabled":skill.enabled,"revision":skill.revision})
                }
            })
        })();
        let value = result.unwrap_or_else(
            |error| json!({"ok":false,"error":error,"effect_unknown":self.write_blocked.is_some()}),
        );
        if value.to_string().len() > 256 * 1024 {
            json!({"ok":false,"error":"技能结果超过256 KiB，请缩短技能简介或正文"})
        } else {
            value
        }
    }

    pub fn usage(&self) -> Vec<UsageRecord> {
        let mut usage: Vec<_> = self
            .usage
            .iter()
            .filter(|record| !record.summary)
            .cloned()
            .collect();
        usage.sort_by(|a, b| b.at.cmp(&a.at).then_with(|| a.id.cmp(&b.id)));
        usage
    }

    /// A final result is immutable. Pending checkpoints and one pending -> final transition
    /// preserve the same run identity; retries cannot count twice or change a closed record.
    pub fn save_usage(&mut self, record: UsageRecord) -> Result<(), String> {
        self.writable()?;
        validate_usage_record(&record)?;
        let existing_index = self
            .usage
            .iter()
            .position(|existing| existing.id == record.id);
        if let Some(index) = existing_index {
            let existing = &self.usage[index];
            if same_usage(existing, &record) {
                return Ok(());
            }
            if !(existing.pending && same_usage_identity(existing, &record)) {
                return Err("运行 ID 已有不同用量记录，未覆盖原账本条目".into());
            }
        } else if self.usage.len() >= MAX_USAGE_RECORDS {
            return Err("用量记录已达到存储预算；历史记录保留，未写入新条目".into());
        }
        let mut records = self.usage.clone();
        match existing_index {
            Some(index) => records[index] = record,
            None => records.push(record),
        }
        validate_usage(&records)?;
        let document = UsageDocument {
            version: VERSION,
            records,
        };
        self.persist(&self.root.join("usage.json"), &document, MAX_USAGE_BYTES)?;
        self.usage = document.records;
        Ok(())
    }

    pub fn messages(&self, id: &str) -> Result<Vec<Message>, String> {
        Ok(self.read_chat(id)?.messages)
    }

    /// Reserve the serialized envelope, retained metadata, and future queue growth
    /// before dispatch. The returned capacity is a conservative plain-text budget.
    pub fn reserve_result(
        &self,
        id: &str,
        messages: &[Message],
        result_bytes: usize,
    ) -> Result<usize, String> {
        self.writable()?;
        let document = self.read_chat(id)?;
        #[derive(Serialize)]
        struct BorrowedDocument<'a> {
            version: u32,
            conversation: &'a Conversation,
            messages: &'a [Message],
            queued_turns: &'a [QueuedTurn],
        }
        struct Counter(usize);
        impl Write for Counter {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                self.0 = self.0.saturating_add(bytes.len());
                if self.0 > MAX_CHAT_TEXT_BYTES {
                    return Err(io::Error::other("storage budget"));
                }
                Ok(bytes.len())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let mut counter = Counter(0);
        serde_json::to_writer(
            &mut counter,
            &BorrowedDocument {
                version: VERSION,
                conversation: &document.conversation,
                messages,
                queued_turns: &document.queued_turns,
            },
        )
        .map_err(|_| "完整会话文档已超过存储预算，工具未执行")?;
        let queue_used: usize = document
            .queued_turns
            .iter()
            .map(|turn| turn.text.len())
            .sum();
        let queue_growth = (1024 * 1024usize)
            .saturating_sub(queue_used)
            .saturating_mul(6)
            .saturating_add(16 * 1024);
        let required = counter
            .0
            .saturating_add(queue_growth)
            .saturating_add(result_bytes.saturating_mul(3))
            .saturating_add(1024 * 1024);
        if required >= MAX_CHAT_TEXT_BYTES {
            return Err("完整会话没有空间保留工具结果与排队消息，未执行".into());
        }
        Ok(((MAX_CHAT_TEXT_BYTES - required) / 6).min(8 * 1024 * 1024))
    }

    pub fn mutate_chat(
        &mut self,
        id: &str,
        mutation: crate::chat::Mutation,
    ) -> Result<Vec<Message>, String> {
        let pin_change = match &mutation {
            crate::chat::Mutation::Pin(value) => Some(*value),
            _ => None,
        };
        self.update_transcript(id, |document| {
            crate::chat::apply_mutation(
                &mut document.conversation,
                &mut document.messages,
                mutation,
            )?;
            validate_chat(document, id)?;
            Ok(())
        })
        .map(|document| {
            if let Some(value) = pin_change {
                self.preferences_pin(id, value);
            }
            document.messages
        })
    }

    pub fn queued_turns(&self, id: &str) -> Result<Vec<QueuedTurn>, String> {
        if self.shared_files_root.is_some() {
            return Ok(self.read_chat(id)?.queued_turns);
        }
        if self
            .chat_directory
            .join(format!("{id}.segments.json"))
            .is_file()
        {
            let archive: crate::legacy_migration::ChatArchive = required_json(
                &self.root,
                &self.chat_directory.join(format!("{id}.segments.json")),
                8 * 1024 * 1024,
            )?;
            if archive.conversation.id != id {
                return Err("排队历史归属不匹配".into());
            }
            validate_queue(&archive.queued_turns)?;
            return Ok(archive.queued_turns);
        }
        Ok(self.read_chat(id)?.queued_turns)
    }

    pub fn save_child(
        &mut self,
        parent_chat: &str,
        parent_run: &str,
        mut record: crate::subagents::Record,
    ) -> Result<(), String> {
        crate::transcript_redaction::redact_messages(&mut record.messages);
        self.conversation(parent_chat)?;
        validate_id(parent_chat)?;
        validate_id(parent_run)?;
        validate_id(&record.id)?;
        bounded_text(&record.name, 256, "子任务名称")?;
        bounded_text(&record.detail, 8192, "子任务状态说明")?;
        validate_messages(&record.messages)?;
        validate_saved_child_config(record.config.as_ref())?;
        if let Some(config) = &record.config {
            crate::avatars::retain(
                &self.namespace_root,
                [config.assistant_avatar_path.as_str()],
            )?;
        }
        self.validate_child_usage(parent_chat, &record)?;
        let filename = format!("{}.json", record.id);
        let previous = if let Some(files) = self.shared_files_root.as_deref() {
            let path = shared_children::directory(files, parent_chat).join(&filename);
            shared_children::read(files, &path)?.map(|(document, _)| document)
        } else {
            let path = self.child_directory.join(parent_chat).join(&filename);
            optional_json::<ChildDocument>(&self.root, &path, MAX_CHILD_BYTES)?
        };
        if let Some(previous) = previous {
            version(previous.version)?;
            if previous.parent_chat != parent_chat
                || previous.parent_run != parent_run
                || previous.record.id != record.id
            {
                return Err("子任务记录归属不匹配".into());
            }
            if previous.record.status.terminal() {
                let old =
                    serde_json::to_vec(&previous.record).map_err(|_| "无法核对已有子任务终态")?;
                let next = serde_json::to_vec(&record).map_err(|_| "无法核对子任务终态")?;
                if old != next {
                    return Err("子任务终态已保存，不能重新写成运行中或替换结果".into());
                }
                return self.sync_child_usage(parent_chat, &record);
            } else {
                validate_child_history_extension(&previous.record.messages, &record.messages)?;
            }
            if previous.record.config != record.config {
                return Err("子任务不能替换创建时的冻结配置".into());
            }
        }
        let document = ChildDocument {
            version: VERSION,
            parent_chat: parent_chat.into(),
            parent_run: parent_run.into(),
            record,
        };
        if self.shared_files_root.is_some() {
            self.write_shared_child(&document)?;
        } else {
            let directory = owned_directory(&self.child_directory, parent_chat)?;
            self.persist(&directory.join(filename), &document, MAX_CHILD_BYTES)?;
        }
        // The child document is authoritative. A crash between documents is repaired
        // when it is reopened; a ledger failure stops further child tool dispatch.
        self.sync_child_usage(parent_chat, &document.record)
    }

    fn write_shared_child(&mut self, document: &ChildDocument) -> Result<(), String> {
        self.writable()?;
        let files = self.shared_files_root.as_deref().expect("shared child store");
        shared_children::write(files, document).map_err(|failure| {
            if failure.replaced {
                self.write_blocked = Some(failure.message.clone());
            }
            failure.message
        })?;
        self.history_revisions.insert(
            (document.parent_chat.clone(), Some(document.record.id.clone())),
            crate::controller::next_display_revision(),
        );
        Ok(())
    }

    fn validate_child_usage(
        &self,
        parent_chat: &str,
        record: &crate::subagents::Record,
    ) -> Result<(), String> {
        if record.usage.len() > 25 {
            return Err("子任务请求用量数量超过预算".into());
        }
        let new_entries = record
            .usage
            .iter()
            .filter(|usage| !self.usage.iter().any(|existing| existing.id == usage.id))
            .count();
        if !record.status.terminal()
            && self.usage.len().saturating_add(new_entries) > MAX_USAGE_RECORDS
        {
            return Err("用量账本已满，子模型请求未发送；已有历史保留".into());
        }
        for (index, usage) in record.usage.iter().enumerate() {
            if usage.id != format!("{}-usage-{index}", record.id)
                || !(usage.conversation_id.is_empty() || usage.conversation_id == parent_chat)
                || (record.status.terminal() && usage.pending)
            {
                return Err("子任务用量的请求归属或终态无效".into());
            }
            let mut normalized = usage.clone();
            normalized.conversation_id = parent_chat.into();
            validate_usage_record(&normalized)?;
            if let Some(previous) = self.usage.iter().find(|item| item.id == normalized.id)
                && !same_usage(previous, &normalized)
                && !(previous.pending && same_usage_identity(previous, &normalized))
            {
                return Err("子任务不能覆盖其它请求或已完成的用量".into());
            }
        }
        Ok(())
    }

    fn sync_child_usage(
        &mut self,
        parent_chat: &str,
        record: &crate::subagents::Record,
    ) -> Result<(), String> {
        for usage in &record.usage {
            let mut normalized = usage.clone();
            normalized.conversation_id = parent_chat.into();
            self.save_usage(normalized)?;
        }
        Ok(())
    }

    pub fn plugins(&self) -> Result<Vec<crate::plugins::PluginEntry>, String> {
        let mut entries =
            crate::plugins::discover(&self.plugin_directory, &reserved_plugin_names())?;
        for entry in &mut entries {
            match self.plugin_environment(entry) {
                Ok(environment) => entry.environment = environment,
                Err(_) => entry.issues.push(crate::plugins::PluginIssue {
                    code: crate::plugins::ReasonCode::StateInvalid,
                    severity: crate::plugins::Severity::Error,
                    message: "环境变量文件格式无效或不兼容，请重新设置；未加载其中的值".into(),
                }),
            }
        }
        Ok(entries)
    }

    pub fn plugin_inventory(&self) -> Result<Vec<crate::plugins::PluginEntry>, String> {
        let mut entries = self.plugins()?;
        for entry in &mut entries {
            entry.environment.clear();
        }
        for manifest in entries
            .iter_mut()
            .filter_map(|entry| entry.manifest.as_mut())
        {
            for variable in &mut manifest.env {
                variable.default_value = None;
            }
        }
        Ok(entries)
    }

    fn plugin_environment(
        &self,
        entry: &crate::plugins::PluginEntry,
    ) -> Result<BTreeMap<String, String>, String> {
        let Some(manifest) = &entry.manifest else {
            return Ok(BTreeMap::new());
        };
        let stored = self.read_plugin_environment(entry)?;
        let mut resolved = BTreeMap::new();
        for declaration in &manifest.env {
            let value = stored
                .get(&declaration.name)
                .or(declaration.default_value.as_ref());
            if let Some(value) = value {
                bounded_text(value, 16 * 1024, "插件环境变量")?;
                resolved.insert(declaration.name.clone(), value.clone());
            }
        }
        Ok(resolved)
    }

    fn read_plugin_environment(
        &self,
        entry: &crate::plugins::PluginEntry,
    ) -> Result<BTreeMap<String, String>, String> {
        self.read_plugin_stored_environment(entry)?
            .into_iter()
            .map(|(name, value)| {
                let plain = if value.starts_with("enc1:") {
                    let cipher = StoredSecret::from_ciphertext(value).map_err(|e| e.to_string())?;
                    SecretBox.decrypt(&cipher).map_err(|e| e.to_string())?
                } else {
                    value
                };
                Ok((name, plain))
            })
            .collect()
    }

    fn read_plugin_stored_environment(
        &self,
        entry: &crate::plugins::PluginEntry,
    ) -> Result<BTreeMap<String, String>, String> {
        let stored = optional_json::<BTreeMap<String, String>>(
            &self.plugin_directory,
            &entry.dir.join(crate::plugins::ENV_FILE),
            MAX_PLUGIN_ENV_BYTES,
        )?
        .unwrap_or_default();
        if stored.len() > 128 {
            return Err("插件环境变量数量超过上限".into());
        }
        for value in stored.values() {
            bounded_text(value, 32 * 1024 + 128, "插件环境变量密文")?;
        }
        Ok(stored)
    }

    pub fn plugin_env_summary(
        &self,
        id: &str,
    ) -> Result<Vec<crate::plugins::PluginEnvStatus>, String> {
        let entry = self
            .plugins()?
            .into_iter()
            .find(|entry| entry.id == id)
            .ok_or("插件已移除")?;
        let manifest = entry.manifest.as_ref().ok_or("插件清单无效")?;
        let stored = self.read_plugin_stored_environment(&entry)?;
        Ok(manifest
            .env
            .iter()
            .map(|variable| {
                let effective = stored
                    .get(&variable.name)
                    .or(variable.default_value.as_ref());
                let plain = effective.map(|value| {
                    if stored.contains_key(&variable.name) && value.starts_with("enc1:") {
                        StoredSecret::from_ciphertext(value.clone())
                            .and_then(|value| SecretBox.decrypt(&value))
                    } else {
                        Ok(value.clone())
                    }
                });
                crate::plugins::PluginEnvStatus {
                    name: variable.name.clone(),
                    secret: variable.secret,
                    required: variable.required,
                    configured: plain
                        .as_ref()
                        .is_some_and(|value| value.as_ref().is_ok_and(|value| !value.is_empty())),
                    uses_default: !stored.contains_key(&variable.name)
                        && variable
                            .default_value
                            .as_ref()
                            .is_some_and(|value| !value.is_empty()),
                    unavailable: plain.as_ref().is_some_and(Result::is_err),
                    description: variable.description.clone(),
                }
            })
            .collect())
    }

    pub fn save_plugin_environment(
        &mut self,
        id: &str,
        changes: BTreeMap<String, Option<String>>,
    ) -> Result<(), String> {
        self.writable()?;
        if changes.len() > 128 {
            return Err("插件环境变量数量超过上限".into());
        }
        let entry = crate::plugins::discover(&self.plugin_directory, &reserved_plugin_names())?
            .into_iter()
            .find(|entry| entry.id == id)
            .ok_or("插件已移除")?;
        let manifest = entry.manifest.as_ref().ok_or("插件清单无效")?;
        let raw = self.read_plugin_stored_environment(&entry)?;
        let mut previous = BTreeMap::new();
        let mut edits = BTreeMap::new();
        for (name, value) in raw {
            if changes
                .get(&name)
                .is_some_and(|value| value.as_ref().is_none_or(|value| !value.is_empty()))
            {
                continue;
            }
            if value.starts_with("enc1:") {
                previous.insert(
                    name,
                    StoredSecret::preserve_ciphertext(value).map_err(|e| e.to_string())?,
                );
            } else if !value.is_empty() {
                edits.insert(name, SecretEdit::Set(value));
            }
        }
        for (name, value) in changes {
            manifest
                .env
                .iter()
                .find(|declaration| declaration.name == name)
                .ok_or("环境变量未在插件清单中声明")?;
            if let Some(value) = &value {
                bounded_text(value, 16 * 1024, "插件环境变量")?;
            }
            match value {
                Some(value) if !value.is_empty() => {
                    edits.insert(name, SecretEdit::Set(value));
                }
                Some(_) => {}
                None => {
                    edits.insert(name, SecretEdit::Clear);
                }
            }
        }
        let stored = prepare_fields(&SecretBox, &previous, &edits).map_err(|e| e.to_string())?;
        self.persist(
            &entry.dir.join(crate::plugins::ENV_FILE),
            &stored,
            MAX_PLUGIN_ENV_BYTES,
        )?;
        Ok(())
    }

    pub(crate) fn admit_plugin_write(&self) -> Result<(), String> {
        self.writable()
    }
    pub(crate) fn suspend_plugin_writes(&mut self, root: &Path, reason: String) {
        if self.plugin_directory == root {
            self.write_blocked.get_or_insert(reason);
        }
    }
    pub(crate) fn plugin_directory(&self) -> PathBuf {
        self.plugin_directory.clone()
    }
    pub fn uninstall_plugin(&mut self, id: &str) -> Result<(), String> {
        self.uninstall_plugin_with_wipe(id, false)
    }
    pub(crate) fn uninstall_plugin_with_wipe(
        &mut self,
        id: &str,
        wipe: bool,
    ) -> Result<(), String> {
        self.writable()?;
        crate::plugin_assets::trash(
            &self.plugin_directory,
            id,
            wipe,
            &crate::network::CancelToken::new(),
        )
    }

    pub fn preview_plugin(&self, source: &str) -> Result<crate::plugins::PluginManifest, String> {
        let mut manifest = crate::plugins::parse_manifest(source, &reserved_plugin_names())?;
        for variable in &mut manifest.env {
            variable.default_value = None;
        }
        Ok(manifest)
    }

    pub fn install_plugin(&mut self, source: &str) -> Result<(), String> {
        self.writable()?;
        let manifest = crate::plugins::parse_manifest(source, &reserved_plugin_names())?;
        let value: serde_json::Value =
            serde_json::from_str(source).map_err(|_| "无法保存插件清单")?;
        let directory = self.plugin_directory.clone();
        let target = directory.join(&manifest.id);
        match fs::symlink_metadata(&target) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Ok(_) => return Err("同 ID 插件已存在，未覆盖现有文件".into()),
            Err(error) => return Err(fs_error("检查插件安装目录", &target, error)),
        }
        let stage = owned_directory(
            &directory,
            &format!(".install-{}", crate::controller::unique_id()),
        )?;
        let state = serde_json::json!({"enabled":false,"installed_at":now()?});
        self.persist(&stage.join("manifest.json"), &value, 256 * 1024)?;
        self.persist(&stage.join("state.json"), &state, 16 * 1024)?;
        fs::rename(&stage, &target).map_err(|error| fs_error("提交插件安装", &target, error))?;
        if let Err(error) = sync_directory(&directory) {
            let message = fs_error("同步插件安装目录，插件可能已安装", &directory, error);
            self.write_blocked = Some(message.clone());
            return Err(message);
        }
        Ok(())
    }

    pub fn set_plugin_enabled(&mut self, id: &str, enabled: bool) -> Result<(), String> {
        self.writable()?;
        let plugin = self
            .plugins()?
            .into_iter()
            .find(|plugin| plugin.id == id)
            .ok_or("插件已移除")?;
        if enabled && !plugin.is_valid() {
            return Err("请先修复插件清单中的错误".into());
        }
        if !plugin.dir.starts_with(&self.plugin_directory) {
            return Err("插件目录超出允许范围".into());
        }
        let state =
            serde_json::json!({"enabled":enabled,"installed_at":plugin.state.installed_at.max(1)});
        self.persist(&plugin.dir.join("state.json"), &state, 16 * 1024)
    }

    pub fn children_for(
        &mut self,
        parent_chat: &str,
        active_run: Option<&str>,
    ) -> Result<Vec<crate::display::ChildSummary>, String> {
        self.conversation(parent_chat)?;
        validate_id(parent_chat)?;
        let mut sources = vec![(self.child_directory.join(parent_chat), false)];
        if let Some(files) = self.shared_files_root.as_deref() {
            sources.push((shared_children::directory(files, parent_chat), true));
        }
        let mut recent = BTreeMap::new();
        for (directory, common) in sources {
            let entries = match fs::read_dir(&directory) {
                Ok(entries) => entries,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(fs_error("读取子任务目录", &directory, error)),
            };
            for (index, entry) in entries.enumerate() {
                if index >= MAX_DIRECTORY_ENTRIES {
                    return Err("子任务目录超过读取预算".into());
                }
                let entry = entry.map_err(|error| fs_error("读取子任务记录", &directory, error))?;
                let name = entry.file_name();
                let Some(id) = name.to_str().and_then(|name| name.strip_suffix(".json")) else {
                    continue;
                };
                validate_id(id)?;
                recent.insert(id.to_owned(), (entry.path(), common));
                if recent.len() > VISIBLE_CHILDREN {
                    recent.pop_first();
                }
            }
        }
        recent
            .into_iter()
            .rev()
            .map(|(id, (path, common))| {
                let (mut document, owned) = if common {
                    let files = self.shared_files_root.as_deref().expect("shared child store");
                    shared_children::read(files, &path)?.ok_or("共用子任务已移除")?
                } else {
                    (required_json(&self.root, &path, MAX_CHILD_BYTES)?, true)
                };
                version(document.version)?;
                if document.parent_chat != parent_chat || document.record.id != id {
                    return Err("子任务记录归属不匹配".into());
                }
                validate_messages(&document.record.messages)?;
                if owned {
                    validate_saved_child_config(document.record.config.as_ref())?;
                }
                if !document.record.status.terminal()
                    && active_run != Some(document.parent_run.as_str())
                {
                    document.record.status = crate::subagents::Status::Cancelled;
                    document.record.detail = "原运行已中断，子任务不会自动重新发送".into();
                    for usage in &mut document.record.usage {
                        if usage.pending {
                            usage.pending = false;
                            usage.error = Some("原子请求已中断，未报告的用量保持未知".into());
                        }
                    }
                    crate::agent::reconcile_history(&mut document.record.messages);
                    validate_messages(&document.record.messages)?;
                    self.validate_child_usage(parent_chat, &document.record)?;
                    if common && owned {
                        self.write_shared_child(&document)?;
                    } else if !common {
                        self.persist(&path, &document, MAX_CHILD_BYTES)?;
                    }
                }
                self.validate_child_usage(parent_chat, &document.record)?;
                if let Err(error) = self.sync_child_usage(parent_chat, &document.record) {
                    self.child_usage_warning =
                        Some(format!("子任务记录已读取，用量同步尚未完成：{error}"));
                }
                Ok(crate::display::ChildSummary::from_record(
                    parent_chat,
                    &document.parent_run,
                    &document.record,
                ))
            })
            .collect()
    }

    pub fn take_child_usage_warning(&mut self) -> Option<String> {
        self.child_usage_warning.take()
    }

    pub fn mcp_configs(&self) -> Vec<crate::mcp::ServerConfig> {
        self.mcp_servers
            .iter()
            .cloned()
            .map(|mut server| {
                if self.locked_mcp.contains(&server.id) {
                    server.enabled = false;
                }
                server
            })
            .collect()
    }

    pub fn mcp_metadata(&self) -> Vec<crate::state::McpConfigSummary> {
        self.mcp_servers
            .iter()
            .map(|server| crate::state::McpConfigSummary {
                config: server.metadata(),
                env_configured: self
                    .mcp_secrets
                    .get(&server.id)
                    .is_some_and(|s| !s.env.is_empty())
                    || !server.env.is_empty(),
                headers_configured: self
                    .mcp_secrets
                    .get(&server.id)
                    .is_some_and(|s| !s.headers.is_empty())
                    || !server.headers.is_empty(),
            })
            .collect()
    }

    pub fn prepare_mcp_config(
        &self,
        config: crate::mcp::ServerConfig,
        env_change: Option<String>,
        headers_change: Option<String>,
    ) -> Result<crate::mcp::ServerConfig, String> {
        if self.locked_mcp.contains(&config.id) {
            if let Some(secret) = self.mcp_secrets.get(&config.id) {
                if SecretBox.decrypt(&secret.env).is_err()
                    && env_change.as_ref().is_none_or(|v| v.trim().is_empty())
                {
                    return Err("原 MCP 环境凭据无法解密，请重新填写或明确清除后再测试".into());
                }
                if SecretBox.decrypt(&secret.headers).is_err()
                    && headers_change.as_ref().is_none_or(|v| v.trim().is_empty())
                {
                    return Err("原 MCP 请求头无法解密，请重新填写或明确清除后再测试".into());
                }
            }
        }
        self.prepare_mcp_config_for_save(config, env_change, headers_change)
    }

    fn prepare_mcp_config_for_save(
        &self,
        mut config: crate::mcp::ServerConfig,
        env_change: Option<String>,
        headers_change: Option<String>,
    ) -> Result<crate::mcp::ServerConfig, String> {
        let previous = self
            .mcp_servers
            .iter()
            .find(|server| server.id == config.id);
        config.env = env_change
            .filter(|value| !value.is_empty())
            .or_else(|| previous.map(|server| server.env.clone()))
            .unwrap_or_default();
        config.headers = headers_change
            .filter(|value| !value.is_empty())
            .or_else(|| previous.map(|server| server.headers.clone()))
            .unwrap_or_default();
        crate::mcp::validate_config(&config)?;
        Ok(config)
    }

    pub fn mcp_credentials_ready(&self, id: &str) -> Result<(), String> {
        if !self.mcp_servers.iter().any(|server| server.id == id) {
            return Err("MCP 配置已不存在".into());
        }
        if self.locked_mcp.contains(id) {
            return Err("MCP 凭据无法解密，连接未执行；请重新填写或明确清除".into());
        }
        Ok(())
    }

    pub fn save_mcp_server(
        &mut self,
        config: crate::mcp::ServerConfig,
        env_change: Option<String>,
        headers_change: Option<String>,
    ) -> Result<String, String> {
        let env_edit = env_change
            .filter(|v| !v.is_empty())
            .map(SecretEdit::Set)
            .unwrap_or(SecretEdit::Keep);
        let headers_edit = headers_change
            .filter(|v| !v.is_empty())
            .map(SecretEdit::Set)
            .unwrap_or(SecretEdit::Keep);
        self.save_mcp_server_with_secret_edits(config, env_edit, headers_edit)
    }

    pub fn save_mcp_server_with_secret_edits(
        &mut self,
        config: crate::mcp::ServerConfig,
        env_edit: SecretEdit,
        headers_edit: SecretEdit,
    ) -> Result<String, String> {
        self.writable()?;
        let previous = self
            .mcp_secrets
            .get(&config.id)
            .cloned()
            .unwrap_or_default();
        let cipher = SecretBox;
        let secret = StoredMcpSecrets {
            env: prepare_secret(&cipher, &previous.env, &env_edit).map_err(|e| e.to_string())?,
            headers: prepare_secret(&cipher, &previous.headers, &headers_edit)
                .map_err(|e| e.to_string())?,
        };
        let edit_text = |edit: &SecretEdit| match edit {
            SecretEdit::Keep => None,
            SecretEdit::Clear => Some(String::new()),
            SecretEdit::Set(value) => Some(value.clone()),
        };
        let mut config = self.prepare_mcp_config_for_save(
            config,
            edit_text(&env_edit),
            edit_text(&headers_edit),
        )?;
        if matches!(env_edit, SecretEdit::Clear) {
            config.env.clear();
        }
        if matches!(headers_edit, SecretEdit::Clear) {
            config.headers.clear();
        }
        let mut servers = self.mcp_servers.clone();
        if config.id.is_empty() {
            config.id = fresh_id("mcp")?;
            servers.push(config.clone());
        } else {
            let previous = servers
                .iter_mut()
                .find(|server| server.id == config.id)
                .ok_or("待编辑 MCP 配置已不存在")?;
            *previous = config.clone();
        }
        let mut servers = crate::mcp::normalize_configs(servers)?;
        let mut secrets = self.mcp_secrets.clone();
        secrets.insert(config.id.clone(), secret);
        self.persist_mcp(&servers, &mut secrets)?;
        self.locked_mcp = hydrate_mcp_secrets(&mut servers, &secrets, &mut self.secret_warnings);
        self.mcp_secrets = secrets;
        self.mcp_servers = servers;
        Ok(config.id)
    }

    pub fn set_mcp_enabled(&mut self, id: &str, enabled: bool) -> Result<(), String> {
        let mut server = self
            .mcp_servers
            .iter()
            .find(|server| server.id == id)
            .cloned()
            .ok_or("MCP 配置已不存在")?;
        server.enabled = enabled;
        self.save_mcp_server(server, None, None).map(|_| ())
    }

    pub fn delete_mcp_server(&mut self, id: &str) -> Result<(), String> {
        self.writable()?;
        let mut servers = self.mcp_servers.clone();
        let index = servers
            .iter()
            .position(|server| server.id == id)
            .ok_or("MCP 配置已不存在")?;
        servers.remove(index);
        let mut secrets = self.mcp_secrets.clone();
        secrets.remove(id);
        self.persist_mcp(&servers, &mut secrets)?;
        self.mcp_servers = servers;
        self.mcp_secrets = secrets;
        self.mcp_extra.remove(id);
        self.locked_mcp.remove(id);
        Ok(())
    }

    fn persist_mcp(
        &mut self,
        servers: &[crate::mcp::ServerConfig],
        secrets: &mut BTreeMap<String, StoredMcpSecrets>,
    ) -> Result<(), String> {
        if let Some(files) = self.shared_files_root.as_deref() {
            if let Err(failure) = shared_mcp::write(files, servers, secrets, &self.mcp_extra) {
                if failure.replaced {
                    self.write_blocked = Some(failure.message.clone());
                }
                return Err(failure.message);
            }
            return Ok(());
        }
        self.persist(
            &self.root.join("mcp.json"),
            &McpDocument {
                version: VERSION,
                servers: servers.to_vec(),
                secrets: secrets.clone(),
            },
            shared_mcp::MAX_BYTES,
        )
    }

    pub fn messages_and_queue(&self, id: &str) -> Result<(Vec<Message>, Vec<QueuedTurn>), String> {
        let document = self.read_chat(id)?;
        Ok((document.messages, document.queued_turns))
    }

    fn read_chat(&self, id: &str) -> Result<ChatDocument, String> {
        let path = self.chat_path(id)?;
        self.conversation(id)?;
        if let Some(files) = self.shared_files_root.as_deref() {
            let document = shared_chat::read(files, id)?;
            validate_chat(&document, id)?;
            return Ok(document);
        }
        let document: ChatDocument = if self
            .chat_directory
            .join(format!("{id}.segments.json"))
            .exists()
        {
            let (conversation, messages, queued_turns) =
                crate::legacy_migration::read_chat_archive(&self.root, id)?;
            ChatDocument {
                version: VERSION,
                conversation,
                messages,
                queued_turns,
            }
        } else {
            required_json(&self.root, &path, MAX_CHAT_BYTES)?
        };
        validate_chat(&document, id)?;
        Ok(document)
    }

    pub fn edit_queue(
        &mut self,
        id: &str,
        edit: impl FnOnce(&mut Vec<QueuedTurn>) -> Result<(), String>,
    ) -> Result<Vec<QueuedTurn>, String> {
        let mut document = self.read_chat(id)?;
        edit(&mut document.queued_turns)?;
        validate_queue(&document.queued_turns)?;
        self.persist_chat(&document)?;
        Ok(document.queued_turns)
    }

    pub fn new_conversation(&mut self, title: &str) -> Result<Conversation, String> {
        self.writable()?;
        if self.conversations.len() >= MAX_CONVERSATIONS {
            return Err("会话数量已达到存储预算".into());
        }
        let title = if title.trim().is_empty() {
            "新会话"
        } else {
            title.trim()
        };
        bounded_text(title, 1_024, "会话标题")?;
        let id = self.fresh_conversation_id()?;
        let conversation = Conversation {
            id,
            title: title.to_owned(),
            updated_at: now()?,
            assistant_id: self.settings.active_assistant_id.clone(),
            provider_id: self.settings.active_provider_id.clone(),
            model: self
                .settings
                .active_provider_id
                .as_ref()
                .and_then(|id| {
                    self.settings
                        .providers
                        .iter()
                        .find(|provider| &provider.id == id)
                })
                .map(|provider| provider.selected_model.clone())
                .or_else(|| {
                    self.settings
                        .active_provider_id
                        .is_none()
                        .then(|| self.settings.model.clone())
                })
                .filter(|model| !model.is_empty()),
            metadata: crate::chat::ConversationMetadata {
                created_at: now()?,
                ..Default::default()
            },
        };
        let document = ChatDocument {
            version: VERSION,
            conversation: conversation.clone(),
            messages: Vec::new(),
            queued_turns: Vec::new(),
        };
        self.persist_chat(&document)?;
        self.conversations.push(conversation.clone());
        Ok(conversation)
    }

    pub fn save_messages(&mut self, id: &str, messages: Vec<Message>) -> Result<(), String> {
        self.save_turn(id, messages, None).map(|_| ())
    }

    pub fn save_compaction(
        &mut self,
        id: &str,
        checkpoint: crate::chat::ContextCheckpoint,
    ) -> Result<(), String> {
        self.update_transcript(id, |document| {
            if checkpoint.id.is_empty()
                || checkpoint.summary.is_empty()
                || checkpoint.summary.encode_utf16().count() > 32_000
                || document.conversation.metadata.checkpoints.len() >= 4096
            {
                return Err("摘要检查点超过预算或无效".into());
            }
            let from = document
                .messages
                .iter()
                .position(|message| message.id == checkpoint.from_message_id)
                .ok_or("摘要起点已变化")?;
            let to = document
                .messages
                .iter()
                .position(|message| message.id == checkpoint.to_message_id)
                .ok_or("摘要终点已变化")?;
            if from > to
                || document
                    .conversation
                    .metadata
                    .checkpoints
                    .iter()
                    .any(|old| old.id == checkpoint.id)
            {
                return Err("摘要边界或身份无效".into());
            }
            document.conversation.metadata.checkpoints.push(checkpoint);
            Ok(())
        })
        .map(|_| ())
    }

    pub fn save_rewrite(
        &mut self,
        id: &str,
        plan: &crate::context::RewritePlan,
    ) -> Result<(), String> {
        self.update_transcript(id, |document| {
            document.messages = plan.messages.clone();
            document.conversation.metadata.active_runs = plan.active_runs.clone();
            document.conversation.metadata.checkpoints = plan.checkpoints.clone();
            Ok(())
        })
        .map(|_| ())
    }

    pub fn restore_empty_candidate(
        &mut self,
        id: &str,
        run: &str,
        question: &str,
        previous: Option<&str>,
    ) -> Result<(), String> {
        self.update_transcript(id, |document| {
            if document
                .conversation
                .metadata
                .active_runs
                .get(question)
                .map(String::as_str)
                != Some(run)
            {
                return Ok(());
            }
            let has_output = document
                .messages
                .iter()
                .filter(|message| message.metadata.run_id.as_deref() == Some(run))
                .any(|message| {
                    !message.text.is_empty()
                        || !message.thinking.is_empty()
                        || !message.tool_calls.is_empty()
                        || message.role == "tool"
                        || !message.metadata.all_images().is_empty()
                        || !message.metadata.file_activities.is_empty()
                });
            if !has_output {
                if let Some(previous) = previous {
                    document
                        .conversation
                        .metadata
                        .active_runs
                        .insert(question.into(), previous.into());
                } else {
                    document.conversation.metadata.active_runs.remove(question);
                }
            }
            Ok(())
        })
        .map(|_| ())
    }

    pub fn save_turn(
        &mut self,
        id: &str,
        messages: Vec<Message>,
        consume: Option<&QueuedTurn>,
    ) -> Result<Vec<QueuedTurn>, String> {
        self.update_transcript(id, |document| {
            if let Some(expected) = consume {
                let Some(head) = document.queued_turns.first() else {
                    return Err("排队消息已移除".into());
                };
                if head.id != expected.id
                    || head.text != expected.text
                    || head.attachments != expected.attachments
                    || head.transient != expected.transient
                {
                    return Err("排队消息已变化，请重新选择".into());
                }
                document.queued_turns.remove(0);
            }
            document.messages = messages;
            Ok(())
        })
        .map(|document| document.queued_turns)
    }

    pub fn save_steering(
        &mut self,
        id: &str,
        mut messages: Vec<Message>,
        steers: &[String],
        mut assistant: Message,
    ) -> Result<(Vec<Message>, Vec<QueuedTurn>), String> {
        let run_id = messages
            .iter()
            .rev()
            .find_map(|message| message.metadata.run_id.clone());
        assistant.metadata.run_id = run_id.clone();
        let workspace = self.workspace();
        self.update_transcript(id, |document| {
            if messages.last().is_some_and(|last| {
                last.role == "assistant"
                    && !last.complete
                    && last.text.is_empty()
                    && last.thinking.is_empty()
                    && last.tool_calls.is_empty()
            }) {
                messages.pop();
            }
            for steer in steers {
                let index = document
                    .queued_turns
                    .iter()
                    .position(|turn| &turn.id == steer)
                    .ok_or("立即插话的排队记录已变化")?;
                let turn = document.queued_turns.remove(index);
                if turn.transient.is_some() || crate::composer::slash::parse(&turn.text).is_some() {
                    return Err("斜杠指令保留在队列，不能作为普通插话执行".into());
                }
                let prepared = crate::controller::prepare_queue_input(&workspace, &turn)?;
                messages.push(Message {
                    id: turn.id,
                    role: "user".into(),
                    text: prepared.text,
                    thinking: String::new(),
                    complete: true,
                    tool_calls: Vec::new(),
                    tool_call_id: None,
                    metadata: crate::chat::MessageMetadata {
                        timestamp: now()?,
                        steer: true,
                        run_id: run_id.clone(),
                        ..prepared.metadata
                    },
                });
            }
            messages.push(assistant);
            document.messages = messages;
            Ok(())
        })
        .map(|document| (document.messages, document.queued_turns))
    }

    fn update_transcript(
        &mut self,
        id: &str,
        update: impl FnOnce(&mut ChatDocument) -> Result<(), String>,
    ) -> Result<ChatDocument, String> {
        self.writable()?;
        let index = self
            .conversations
            .iter()
            .position(|conversation| conversation.id == id)
            .ok_or("会话不存在，不能保存消息")?;
        // A missing or damaged existing record is an error, rather than an empty replacement.
        let mut document = self.read_chat(id)?;
        update(&mut document)?;
        validate_messages(&document.messages)?;
        document.conversation.updated_at = now()?.max(document.conversation.updated_at);
        self.persist_chat(&document)?;
        let preference_activity =
            document.conversation.updated_at > self.conversations[index].updated_at;
        self.conversations[index] = document.conversation.clone();
        if preference_activity {
            self.preferences_activity(id);
        }
        Ok(document)
    }

    pub fn conversation(&self, id: &str) -> Result<&Conversation, String> {
        self.conversations
            .iter()
            .find(|item| item.id == id)
            .ok_or_else(|| "会话不存在".into())
    }

    pub fn set_conversation_assistant(
        &mut self,
        id: &str,
        assistant_id: Option<String>,
    ) -> Result<(), String> {
        self.writable()?;
        if assistant_id
            .as_ref()
            .is_some_and(|id| !self.settings.assistants.iter().any(|item| &item.id == id))
        {
            return Err("助手不存在，未修改会话".into());
        }
        let index = self
            .conversations
            .iter()
            .position(|item| item.id == id)
            .ok_or("会话不存在")?;
        let mut document = self.read_chat(id)?;
        document.conversation.assistant_id = assistant_id;
        document.conversation.updated_at = now()?.max(document.conversation.updated_at);
        self.persist_chat(&document)?;
        let preference_activity =
            document.conversation.updated_at > self.conversations[index].updated_at;
        self.conversations[index] = document.conversation;
        if preference_activity {
            self.preferences_activity(id);
        }
        Ok(())
    }

    pub fn set_conversation_model(
        &mut self,
        id: &str,
        provider_id: Option<String>,
        model: String,
    ) -> Result<(), String> {
        self.writable()?;
        if model.trim().is_empty() {
            return Err("请选择已启用的模型".into());
        }
        self.settings.connection_for(
            None,
            provider_id.as_deref(),
            (!model.is_empty()).then_some(model.as_str()),
        )?;
        let index = self
            .conversations
            .iter()
            .position(|conversation| conversation.id == id)
            .ok_or("会话已不存在")?;
        let mut document = self.read_chat(id)?;
        document.conversation.provider_id = provider_id;
        document.conversation.model = (!model.is_empty()).then_some(model);
        document.conversation.updated_at = now()?.max(document.conversation.updated_at);
        self.persist_chat(&document)?;
        let preference_activity =
            document.conversation.updated_at > self.conversations[index].updated_at;
        self.conversations[index] = document.conversation;
        if preference_activity {
            self.preferences_activity(id);
        }
        Ok(())
    }

    pub fn set_conversation_approval(
        &mut self,
        id: &str,
        mode: crate::subagent_configuration::ApprovalMode,
    ) -> Result<(), String> {
        let index = self
            .conversations
            .iter()
            .position(|conversation| conversation.id == id)
            .ok_or("会话不存在")?;
        if let Some(files) = self.shared_files_root.as_deref() {
            use crate::subagent_configuration::ApprovalMode;

            self.writable()?;
            let path = shared_chat::meta(files, id);
            let mut metadata: serde_json::Value = required_json(files, &path, MAX_SETTINGS_BYTES)?;
            let mut conversation = shared_chat::conversation(metadata.clone())?;
            if conversation.id != id {
                return Err("会话文件名与记录 ID 不一致".into());
            }
            conversation.updated_at = now()?.max(conversation.updated_at);
            conversation.metadata.approval_mode = Some(mode);
            metadata["updatedAt"] = serde_json::json!(conversation.updated_at);
            metadata["approvalMode"] = serde_json::json!(match mode {
                ApprovalMode::Auto => "AUTO",
                ApprovalMode::Ask => "ASK",
                ApprovalMode::Deny => "DENY",
            });
            self.persist(&path, &metadata, MAX_SETTINGS_BYTES)?;
            let preference_activity = conversation.updated_at > self.conversations[index].updated_at;
            self.conversations[index] = conversation;
            if preference_activity {
                self.preferences_activity(id);
            }
            return Ok(());
        }
        let manifest = self.chat_directory.join(format!("{id}.segments.json"));
        if let Some(mut archive) = optional_json::<crate::legacy_migration::ChatArchive>(
            &self.root,
            &manifest,
            8 * 1024 * 1024,
        )? {
            archive.conversation.metadata.approval_mode = Some(mode);
            self.persist(&manifest, &archive, 8 * 1024 * 1024)?;
        } else {
            let path = self.chat_path(id)?;
            let mut document = self.read_chat(id)?;
            document.conversation.metadata.approval_mode = Some(mode);
            self.persist(&path, &document, MAX_CHAT_BYTES)?;
        }
        self.conversations[index].metadata.approval_mode = Some(mode);
        Ok(())
    }

    /// The conversation is authoritative for this selection. If updating the
    /// new-conversation default fails, retain and report the committed route.
    pub fn select_model(
        &mut self,
        conversation_id: Option<&str>,
        provider_id: Option<String>,
        model: String,
    ) -> Result<(), String> {
        if model.trim().is_empty() {
            return Err("请选择已启用的模型".into());
        }
        self.settings
            .connection_for(None, provider_id.as_deref(), Some(&model))?;
        let mut settings = self.settings.clone();
        settings.active_provider_id = provider_id.clone();
        if let Some(id) = &provider_id {
            let provider = settings
                .providers
                .iter_mut()
                .find(|provider| &provider.id == id)
                .ok_or("供应商已不存在")?;
            provider.selected_model = model.clone();
        } else {
            settings.model = model.clone();
        }
        validate_settings(&settings)?;
        if let Some(id) = conversation_id {
            self.set_conversation_model(id, provider_id, model)?;
            self.save_settings(settings).map_err(|error| {
                format!("当前会话的模型选择已保存；新会话默认模型更新失败：{error}")
            })
        } else {
            self.save_settings(settings)
        }
    }

    pub fn delete_conversation(&mut self, id: &str) -> Result<(), String> {
        self.writable()?;
        if !self.goal.text.is_empty() && self.goal.chat_id == id {
            return Err("此会话仍保存目标，请先清除目标再删除会话".into());
        }
        validate_id(id)?;
        let index = self
            .conversations
            .iter()
            .position(|conversation| conversation.id == id)
            .ok_or("会话已不存在")?;
        let path = self.chat_path(id)?;
        // Prepare final usage evidence durably before removing any source.
        let deletion = self.prepare_usage_deletion(id)?;
        // Confirm the owned document before unlinking. Usage is deliberately independent.
        let document = self.read_chat(id)?;
        validate_chat(&document, id)?;
        if let Some(files) = self.shared_files_root.clone() {
            shared_chat::delete(&files, id)?;
            self.conversations.remove(index);
            self.preferences_forget(id);
            self.reading_positions.retain(|position| position.source.conversation_id() != id);
            self.commit_usage_deletion(deletion)?;
            return shared_children::delete(&files, id).and_then(|()| {
                shared_preferences::save(&files, shared_preferences::VIEWPORTS, &format!("position/{id}"), None)
            }).map_err(|error| format!("会话已删除；清理共用附属记录失败：{error}"));
        }
        if contained_path(&self.root, &self.chat_directory)? != self.chat_directory {
            return Err("删除前会话目录发生变化".into());
        }
        if path.exists() {
            fs::remove_file(&path).map_err(|error| fs_error("删除会话文件", &path, error))?;
        }
        let archive = self.chat_directory.join(format!("{id}.segments.json"));
        if archive.exists() {
            fs::remove_file(&archive).map_err(|error| fs_error("删除会话索引", &archive, error))?;
        }
        if let Err(error) = sync_directory(&self.chat_directory) {
            let message = format!(
                "{}；文件已删除但同步未完成，已暂停写入，请重新打开应用核对数据",
                fs_error("同步会话目录", &self.chat_directory, error)
            );
            self.write_blocked = Some(message.clone());
            return Err(message);
        }
        self.conversations.remove(index);
        self.preferences_forget(id);
        // If confirmation cannot be saved, Prepared remains unknown; deletion
        // is never inferred from the now-missing conversation document.
        self.commit_usage_deletion(deletion)
            .map_err(|error| format!("会话已删除；用量删除回执尚未确认，删除状态保持未知：{error}"))
    }

    pub fn save_settings(&mut self, settings: Settings) -> Result<(), String> {
        self.save_settings_clearing(settings, &[], false)
    }
    /// Clear explicitly alongside inferred updates in the same atomic Settings
    /// document write. Ordinary empty UI fields still retain stored secrets.
    pub(crate) fn save_settings_clearing(
        &mut self,
        mut settings: Settings,
        clear_secrets: &[SettingsSecretTarget],
        clear_connection_headers: bool,
    ) -> Result<(), String> {
        settings.seed_legacy_provider();
        settings.execution.root_available = self.settings.execution.root_available;
        settings.execution.root_checked_at = self.settings.execution.root_checked_at;
        let mut edits = SettingsSecretEdits::default();
        edits.api_key = inferred_secret_edit(
            &settings.api_key,
            &self.settings.api_key,
            &self.settings_secrets.api_key,
        );
        edits.asr_api_key = inferred_secret_edit(
            &settings.asr_key,
            &self.settings.asr_key,
            &self.settings_secrets.asr_api_key,
        );
        edits.search_api_key = inferred_secret_edit(
            &settings.search_key,
            &self.settings.search_key,
            &self.settings_secrets.search_api_key,
        );
        for provider in &settings.providers {
            let prior = self
                .settings
                .providers
                .iter()
                .find(|p| p.id == provider.id)
                .map(|p| p.api_key.as_str())
                .unwrap_or("");
            let stored = self
                .settings_secrets
                .provider_keys
                .get(&provider.id)
                .cloned()
                .unwrap_or_default();
            edits.provider_keys.insert(
                provider.id.clone(),
                inferred_secret_edit(&provider.api_key, prior, &stored),
            );
        }
        for (name, value) in &settings.request_headers {
            let prior = self
                .settings
                .request_headers
                .get(name)
                .map(String::as_str)
                .unwrap_or("");
            let stored = self
                .settings_secrets
                .request_headers
                .get(name)
                .cloned()
                .unwrap_or_default();
            edits
                .request_headers
                .insert(name.clone(), inferred_secret_edit(value, prior, &stored));
        }
        if clear_secrets.len() > 2 {
            return Err("引导清除凭据数量超过预算".into());
        }
        if clear_connection_headers {
            settings.request_headers.clear();
            edits.request_headers = self
                .settings_secrets
                .request_headers
                .keys()
                .map(|name| (name.clone(), SecretEdit::Clear))
                .collect();
        }
        for target in clear_secrets {
            match target {
                SettingsSecretTarget::ApiKey if settings.api_key.is_empty() => {
                    edits.api_key = SecretEdit::Clear
                }
                SettingsSecretTarget::ProviderApiKey(id)
                    if settings
                        .providers
                        .iter()
                        .any(|provider| &provider.id == id && provider.api_key.is_empty()) =>
                {
                    edits.provider_keys.insert(id.clone(), SecretEdit::Clear);
                }
                _ => return Err("引导清除凭据目标无效或该字段已填写新值".into()),
            }
        }
        self.save_settings_with_secret_edits(settings, edits)
    }

    pub fn save_settings_with_secret_edits(
        &mut self,
        mut settings: Settings,
        edits: SettingsSecretEdits,
    ) -> Result<(), String> {
        self.writable()?;
        let next_settings_revision = self
            .settings_revision
            .checked_add(1)
            .ok_or("设置版本已耗尽，请重新打开应用")?;
        validate_settings(&settings)?;
        crate::avatars::retain(
            &self.namespace_root,
            settings
                .assistants
                .iter()
                .map(|assistant| assistant.avatar_path.as_str()),
        )?;
        if self
            .conversations
            .iter()
            .filter_map(|item| item.assistant_id.as_ref())
            .any(|id| !settings.assistants.iter().any(|item| &item.id == id))
        {
            return Err("助手仍被已有会话使用，请先切换这些会话的助手".into());
        }
        let cipher = SecretBox;
        let mut secrets = self.settings_secrets.clone();
        secrets.api_key =
            prepare_secret(&cipher, &secrets.api_key, &edits.api_key).map_err(|e| e.to_string())?;
        secrets.asr_api_key = prepare_secret(&cipher, &secrets.asr_api_key, &edits.asr_api_key)
            .map_err(|e| e.to_string())?;
        secrets.search_api_key =
            prepare_secret(&cipher, &secrets.search_api_key, &edits.search_api_key)
                .map_err(|e| e.to_string())?;
        secrets.provider_keys =
            prepare_fields(&cipher, &secrets.provider_keys, &edits.provider_keys)
                .map_err(|e| e.to_string())?;
        secrets
            .provider_keys
            .retain(|id, _| settings.providers.iter().any(|p| &p.id == id));
        secrets.request_headers =
            prepare_fields(&cipher, &secrets.request_headers, &edits.request_headers)
                .map_err(|e| e.to_string())?;
        let document = SettingsDocument {
            version: VERSION,
            settings: settings.clone(),
            secrets: secrets.clone(),
        };
        self.publish_assistant_avatars(&settings)?;
        if let Some(files) = self.shared_files_root.as_deref() {
            if let Err(failure) =
                shared_settings::save(files, document, (&self.settings, &self.settings_secrets))
            {
                if failure.replaced {
                    self.write_blocked = Some(failure.message.clone());
                }
                return Err(failure.message);
            }
        } else {
            self.persist(
                &self.root.join("settings.json"),
                &document,
                MAX_SETTINGS_BYTES,
            )?;
        }
        self.settings_secrets = secrets;
        hydrate_settings_secrets(
            &mut settings,
            &self.settings_secrets,
            &mut self.secret_warnings,
        );
        self.settings = settings;
        self.settings_revision = next_settings_revision;
        Ok(())
    }

    pub fn clear_settings_secret(&mut self, target: SettingsSecretTarget) -> Result<(), String> {
        let mut settings = self.settings.clone();
        let mut edits = SettingsSecretEdits::default();
        match target {
            SettingsSecretTarget::ApiKey => {
                settings.api_key.clear();
                edits.api_key = SecretEdit::Clear;
            }
            SettingsSecretTarget::ProviderApiKey(id) => {
                settings
                    .providers
                    .iter_mut()
                    .find(|p| p.id == id)
                    .ok_or("供应商已不存在")?
                    .api_key
                    .clear();
                edits.provider_keys.insert(id, SecretEdit::Clear);
            }
            SettingsSecretTarget::AsrApiKey => {
                settings.asr_key.clear();
                edits.asr_api_key = SecretEdit::Clear;
            }
            SettingsSecretTarget::SearchApiKey => {
                settings.search_key.clear();
                edits.search_api_key = SecretEdit::Clear;
            }
            SettingsSecretTarget::RequestHeader(name) => {
                settings.request_headers.remove(&name);
                edits.request_headers.insert(name, SecretEdit::Clear);
            }
        }
        self.save_settings_with_secret_edits(settings, edits)
    }

    pub fn take_secret_warnings(&mut self) -> Vec<String> {
        std::mem::take(&mut self.secret_warnings)
    }

    pub fn search_api_key(&self) -> Result<String, String> {
        SecretBox
            .decrypt(&self.settings_secrets.search_api_key)
            .map_err(|e| e.to_string())
    }

    /// Empty ID creates a memory; a nonempty ID must refer to an existing record.
    pub fn save_memory(&mut self, memory: Memory) -> Result<Memory, String> {
        self.save_memory_inner(memory, false)
    }
    pub(crate) fn memory_id_for_key(&self, key: &str) -> String {
        if self.shared_files_root.is_some() {
            key.to_owned()
        } else {
            crate::system_tools::memory_id(key)
        }
    }

    pub(crate) fn memory_text_for_key(&self, key: &str, value: &str) -> String {
        if self.shared_files_root.is_some() {
            value.to_owned()
        } else {
            format!("{key}: {value}")
        }
    }

    pub(crate) fn memory_value_for_key<'a>(&self, key: &str, text: &'a str) -> &'a str {
        if self.shared_files_root.is_some() {
            text
        } else {
            text.strip_prefix(&format!("{key}: ")).unwrap_or(text)
        }
    }

    pub(crate) fn save_keyed_memory(&mut self, key: &str, text: String) -> Result<Memory, String> {
        self.save_memory_inner(
            Memory {
                id: self.memory_id_for_key(key),
                text,
                updated_at: 0,
            },
            true,
        )
    }
    fn save_memory_inner(
        &mut self,
        mut memory: Memory,
        allow_new_id: bool,
    ) -> Result<Memory, String> {
        self.writable()?;
        let shared = self.shared_files_root.is_some();
        if shared && memory.text.encode_utf16().count() > 4000 {
            return Err("记忆内容不能超过 4000 个字符".into());
        }
        if !shared && memory.text.trim().is_empty() {
            return Err("记忆内容不能为空".into());
        }
        bounded_text(&memory.text, 256 * 1_024, "记忆内容")?;
        let existing = if memory.id.is_empty() {
            if self.memories.len() >= MAX_MEMORIES {
                return Err("记忆数量已达到存储预算".into());
            }
            memory.id = self.fresh_memory_id()?;
            None
        } else {
            if !shared {
                validate_id(&memory.id)?;
            }
            let position = self
                .memories
                .iter()
                .position(|previous| previous.id == memory.id);
            if position.is_none() && !allow_new_id {
                return Err("待编辑的记忆已不存在".into());
            }
            if position.is_none() && self.memories.len() >= MAX_MEMORIES {
                return Err("记忆数量已达到存储预算".into());
            }
            position
        };
        memory.updated_at = now()?.max(
            existing
                .map(|index| self.memories[index].updated_at)
                .unwrap_or(0),
        );
        let mut memories = self.memories.clone();
        match existing {
            Some(index) => memories[index] = memory.clone(),
            None => memories.push(memory.clone()),
        }
        self.persist_memories(memories)?;
        Ok(memory)
    }

    pub fn delete_memory(&mut self, id: &str) -> Result<(), String> {
        self.writable()?;
        let index = self
            .memories
            .iter()
            .position(|memory| memory.id == id)
            .ok_or("记忆已不存在")?;
        let mut memories = self.memories.clone();
        memories.remove(index);
        self.persist_memories(memories)?;
        Ok(())
    }

    fn persist_memories(&mut self, memories: Vec<Memory>) -> Result<(), String> {
        if let Some(files) = self.shared_files_root.as_deref() {
            if let Err(failure) = shared_memory::write(files, &memories) {
                if failure.replaced {
                    self.write_blocked = Some(failure.message.clone());
                }
                return Err(failure.message);
            }
            self.memories = memories;
        } else {
            validate_memories(&memories)?;
            let document = MemoryDocument {
                version: VERSION,
                memories,
            };
            self.persist(
                &self.root.join("memories.json"),
                &document,
                MAX_MEMORY_BYTES,
            )?;
            self.memories = document.memories;
        }
        Ok(())
    }

    pub fn history_generation_token(&self) -> String {
        self.history_generation.clone()
    }

    pub fn terminal_directory(&self) -> PathBuf {
        self.namespace_root.join("terminals")
    }
    pub fn automation_preferences(
        &self,
    ) -> Result<crate::platform_tools::automation_page::Preferences, String> {
        optional_json(
            &self.root,
            &self.root.join("automation-preferences.json"),
            64 * 1024,
        )
        .map(|preferences| preferences.unwrap_or_default())
    }
    pub fn save_automation_preferences(
        &mut self,
        preferences: &crate::platform_tools::automation_page::Preferences,
    ) -> Result<(), String> {
        let path = self.root.join("automation-preferences.json");
        self.persist(&path, preferences, 64 * 1024)
    }
    pub fn save_system_tool_receipt(
        &mut self,
        receipt: &crate::controller::system_tools::Receipt,
    ) -> Result<(), String> {
        self.writable()?;
        receipt.operation.validate()?;
        validate_id(&receipt.operation.id)?;
        receipt.run.validate()?;
        let directory = owned_directory(&self.root, "tool-receipts")?;
        let path = directory.join(format!("{}.json", receipt.operation.id));
        let value = serde_json::json!({"version":1,"operation":{"id":receipt.operation.id,
            "conversation_id":receipt.operation.conversation_id,"generation":receipt.operation.generation},
            "run":receipt.run,"child_id":receipt.child_id,"call":receipt.call,
            "result":receipt.value,"reply_delivered":receipt.reply_delivered});
        crate::composer::json_bytes(&value, 2 * 1024 * 1024)?;
        if let Some(previous) =
            optional_json::<serde_json::Value>(&self.root, &path, 2 * 1024 * 1024)?
        {
            if previous != value {
                return Err("系统工具回执身份已有不同结果，未覆盖".into());
            }
            return Ok(());
        }
        self.persist(&path, &value, 2 * 1024 * 1024)
    }
    pub fn linux_operations_directory(&self) -> PathBuf {
        self.namespace_root.join("linux-operations")
    }
    pub fn shell_environment(&self) -> Result<crate::shell_environment::ShellEnvironment, String> {
        #[cfg(target_os = "android")]
        {
            let paths = crate::platform::android::paths().ok_or("Android路径尚未初始化")?;
            crate::shell_environment::ShellEnvironment::new(
                crate::shell_environment::FrozenPaths::from_platform(&paths, self.workspace()),
            )
        }
        #[cfg(not(target_os = "android"))]
        {
            Err("当前平台没有Android Shell路径".into())
        }
    }

    pub fn asr_connection(&self) -> Result<crate::platform::voice::AsrConnection, String> {
        crate::platform::voice::prepare_asr(
            &self.settings,
            &self.settings.asr_path,
            !self.settings_secrets.asr_api_key.is_empty(),
        )
    }

    pub fn workspace(&self) -> PathBuf {
        self.workspace.clone()
    }

    pub(crate) fn private_workspace(&self) -> PathBuf {
        self.namespace_root.join("workspace")
    }

    pub fn list_files(&self, directory: PathBuf) -> Result<(PathBuf, Vec<FileEntry>), String> {
        let directory = self.workspace_path(&directory)?;
        if !directory.is_dir() {
            return Err("文件浏览目标不是目录".into());
        }
        let reader =
            fs::read_dir(&directory).map_err(|error| fs_error("读取目录", &directory, error))?;
        let mut entries = Vec::new();
        for (index, entry) in reader.enumerate() {
            if index >= MAX_DIRECTORY_ENTRIES {
                return Err("目录条目超过浏览预算，请选择更小的目录".into());
            }
            let entry = entry.map_err(|error| fs_error("读取目录条目", &directory, error))?;
            let path = self.workspace_path(&entry.path())?;
            let metadata =
                fs::metadata(&path).map_err(|error| fs_error("读取文件信息", &path, error))?;
            if !metadata.is_dir() && !metadata.is_file() {
                return Err("工作区包含无法浏览的特殊文件".into());
            }
            entries.push(FileEntry {
                path,
                name: entry.file_name().to_string_lossy().into_owned(),
                is_dir: metadata.is_dir(),
                size: metadata.len(),
            });
        }
        let private = self.private_workspace();
        if private != self.workspace {
            if directory == self.workspace
                && fs::read_dir(&private).is_ok_and(|mut entries| entries.next().is_some())
            {
                entries.push(FileEntry {
                    path: private,
                    name: "原 Rust 工作区".into(),
                    is_dir: true,
                    size: 0,
                });
            } else if directory.starts_with(&private) {
                entries.push(FileEntry {
                    path: if directory == private {
                        self.workspace.clone()
                    } else {
                        directory.parent().unwrap().to_owned()
                    },
                    name: "上一级".into(),
                    is_dir: true,
                    size: 0,
                });
            }
        }
        entries.sort_by(|a, b| {
            b.is_dir
                .cmp(&a.is_dir)
                .then_with(|| a.name.cmp(&b.name))
                .then_with(|| a.path.cmp(&b.path))
        });
        Ok((directory, entries))
    }

    pub fn preview_file(&self, path: PathBuf) -> Result<(PathBuf, String, bool), String> {
        let path = self.workspace_path(&path)?;
        let file = checked_file(&self.workspace_root_for(&path), &path)?;
        let mut bytes = Vec::new();
        file.take((MAX_PREVIEW_BYTES + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|error| fs_error("读取文件预览", &path, error))?;
        let truncated = bytes.len() > MAX_PREVIEW_BYTES;
        let bytes = &bytes[..bytes.len().min(MAX_PREVIEW_BYTES)];
        if bytes.contains(&0) {
            return Err("此文件不是可预览的 UTF-8 文本".into());
        }
        let text = match std::str::from_utf8(bytes) {
            Ok(text) => text,
            Err(error) if truncated && error.error_len().is_none() => {
                std::str::from_utf8(&bytes[..error.valid_up_to()])
                    .map_err(|_| "文件不是 UTF-8 文本")?
            }
            Err(_) => return Err("此文件不是 UTF-8 文本，无法预览".into()),
        };
        Ok((path, text.to_owned(), truncated))
    }

    fn workspace_path(&self, requested: &Path) -> Result<PathBuf, String> {
        let path = if requested.as_os_str().is_empty() {
            self.workspace.clone()
        } else if requested.is_absolute() {
            requested.to_path_buf()
        } else {
            self.workspace.join(requested)
        };
        contained_path(&self.workspace_root_for(&path), &path)
    }

    fn workspace_root_for(&self, path: &Path) -> PathBuf {
        let private = self.private_workspace();
        if path.starts_with(&private) {
            private
        } else {
            self.workspace.clone()
        }
    }

    fn chat_path(&self, id: &str) -> Result<PathBuf, String> {
        validate_id(id)?;
        Ok(self.chat_directory.join(format!("{id}.json")))
    }

    fn fresh_conversation_id(&self) -> Result<String, String> {
        for _ in 0..128 {
            let id = fresh_id("c")?;
            let path = self.chat_path(&id)?;
            match fs::symlink_metadata(&path) {
                Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(id),
                Ok(_) => continue,
                Err(error) => return Err(fs_error("检查新会话路径", &path, error)),
            }
        }
        Err("无法生成唯一会话 ID".into())
    }

    fn fresh_memory_id(&self) -> Result<String, String> {
        for _ in 0..128 {
            let id = fresh_id("m")?;
            if !self.memories.iter().any(|memory| memory.id == id) {
                return Ok(id);
            }
        }
        Err("无法生成唯一记忆 ID".into())
    }

    fn writable(&self) -> Result<(), String> {
        match &self.write_blocked {
            Some(error) => Err(error.clone()),
            None => Ok(()),
        }
    }

    fn persist_chat(&mut self, document: &ChatDocument) -> Result<(), String> {
        self.writable()?;
        validate_chat(document, &document.conversation.id)?;
        if let Some(files) = self.shared_files_root.as_deref() {
            let path = files.join(format!("chats/store/{}.meta", document.conversation.id));
            match shared_chat::write(files, document) {
                Ok(()) => {
                    self.advance_history_path(&path);
                    return Ok(());
                }
                Err(failure) => {
                    if failure.replaced {
                        self.write_blocked = Some(failure.message.clone());
                    }
                    return Err(failure.message);
                }
            }
        }
        let files = crate::legacy_migration::encode_chat_archive(
            &document.conversation,
            &document.messages,
            &document.queued_turns,
        )?;
        // Immutable content-addressed segments land first. Only the atomic index
        // commits a new transcript; interrupted writes remain unreferenced.
        for file in &files[..files.len().saturating_sub(1)] {
            let relative_parent = file.path.parent().ok_or("历史段缺少父目录")?;
            let mut parent = self.root.clone();
            for part in relative_parent.components() {
                let std::path::Component::Normal(name) = part else {
                    return Err("历史段目录无效".into());
                };
                parent = owned_directory(&parent, name.to_str().ok_or("历史段目录不是UTF-8")?)?;
            }
            let target = self.root.join(&file.path);
            if target.exists() {
                let original = fs::symlink_metadata(&target).map_err(|_| "无法检查已存历史段")?;
                if !original.is_file() || original.file_type().is_symlink() {
                    return Err("历史段不是普通文件".into());
                }
                if original.len() != file.bytes.len() as u64 {
                    return Err("已有历史段长度不匹配".into());
                }
                let mut opened = checked_file(&self.root, &target)?;
                let mut bytes = Vec::with_capacity(file.bytes.len());
                Read::by_ref(&mut opened)
                    .take(file.bytes.len() as u64 + 1)
                    .read_to_end(&mut bytes)
                    .map_err(|_| "无法读取已有历史段")?;
                if bytes != file.bytes {
                    return Err("已有历史段内容校验失败，未覆盖".into());
                }
            } else if let Err(failure) = atomic_bytes(&self.root, &target, &file.bytes) {
                if failure.replaced {
                    // A visible immutable segment is not referenced until index
                    // publication, so retrying this write cannot revert a transcript.
                    sync_directory(target.parent().ok_or("历史段父目录缺失")?)
                        .map_err(|_| "历史段目录同步失败，索引未提交")?;
                }
                return Err(failure.message);
            }
            sync_directory(&parent).map_err(|_| "历史段父目录同步失败，索引未提交")?;
        }
        let index = files.last().ok_or("历史索引缺失")?;
        let archive: crate::legacy_migration::ChatArchive =
            serde_json::from_slice(&index.bytes).map_err(|_| "历史索引编码无效")?;
        self.persist(&self.root.join(&index.path), &archive, 8 * 1024 * 1024)
    }

    fn persist<T: Serialize>(&mut self, path: &Path, value: &T, limit: u64) -> Result<(), String> {
        self.writable()?;
        let root = if path.starts_with(&self.plugin_directory) {
            &self.plugin_directory
        } else if path.starts_with(&self.root) {
            &self.root
        } else {
            self.shared_files_root.as_deref().unwrap_or(&self.root)
        };
        match atomic_json(root, path, value, limit) {
            Ok(()) => {
                self.advance_history_path(path);
                Ok(())
            }
            Err(failure) => {
                if failure.replaced {
                    let message = format!(
                        "{}；文件已替换但同步未完成，已暂停写入，请重新打开应用核对数据",
                        failure.message
                    );
                    self.write_blocked = Some(message.clone());
                    Err(message)
                } else {
                    Err(failure.message)
                }
            }
        }
    }
}

fn validate_settings(settings: &Settings) -> Result<(), String> {
    crate::configuration::validate(settings)?;
    settings.behavior.validate()?;
    settings.execution.validate()?;
    for value in [
        &settings.endpoint,
        &settings.model,
        &settings.asr_endpoint,
        &settings.asr_model,
        &settings.api_key,
        &settings.asr_key,
        &settings.search_key,
    ] {
        bounded_text(value, 16 * 1_024, "设置字段")?;
    }
    if settings.temperature.is_some_and(|value| !value.is_finite()) {
        return Err("采样温度必须是有限数字".into());
    }
    Ok(())
}

pub(crate) fn reserved_plugin_names() -> HashSet<String> {
    crate::tools::definitions()
        .into_iter()
        .chain(crate::goal::definitions())
        .chain(crate::subagents::definitions())
        .map(|tool| tool.name)
        .collect()
}

fn validate_saved_child_config(
    config: Option<&crate::subagent_configuration::ChildRunConfig>,
) -> Result<(), String> {
    let Some(config) = config else {
        return Ok(());
    };
    // Historical frozen choices need structural checks, not today's catalog.
    for value in [
        &config.profile_id,
        &config.assistant_id,
        &config.provider_id,
    ] {
        bounded_text(value, 128, "子任务配置ID")?;
    }
    for value in [&config.profile_name, &config.assistant_name, &config.model] {
        bounded_text(value, 1024, "子任务配置名称或模型")?;
    }
    bounded_text(&config.extra_instructions, 64 * 1024, "子任务补充要求")?;
    bounded_text(&config.assistant_avatar, 128, "子任务头像emoji")?;
    crate::avatars::validate_path(&config.assistant_avatar_path)?;
    if !(10..=600).contains(&config.timeout_seconds)
        || config.allowed_tools.len() > 1024
        || config.allowed_actions.len() > 1024
    {
        return Err("子任务冻结配置超过预算或时长无效".into());
    }
    let mut names = HashSet::new();
    for name in &config.allowed_tools {
        bounded_text(name, 128, "子任务工具名称")?;
        if name.is_empty() || !names.insert(name) {
            return Err("子任务工具名称为空或重复".into());
        }
    }
    let mut bytes = 0usize;
    for (name, values) in &config.allowed_actions {
        if !names.contains(name) || values.len() > 1024 {
            return Err("子任务动作限制必须属于允许的工具".into());
        }
        bytes = bytes.saturating_add(name.len());
        let mut unique = HashSet::new();
        for value in values {
            bounded_text(value, 128, "子任务动作名称")?;
            if value.is_empty() || !unique.insert(value) {
                return Err("子任务动作名称为空或重复".into());
            }
            bytes = bytes.saturating_add(value.len());
        }
    }
    if bytes > 128 * 1024 {
        return Err("子任务动作限制超过128 KiB".into());
    }
    Ok(())
}

fn validate_child_history_extension(previous: &[Message], next: &[Message]) -> Result<(), String> {
    for (index, old) in previous.iter().enumerate() {
        let Some(new) = next.get(index) else {
            // A cancelled request can discard its final, still empty assistant placeholder.
            if index + 1 == previous.len()
                && old.role == "assistant"
                && !old.complete
                && old.text.is_empty()
                && old.thinking.is_empty()
                && old.tool_calls.is_empty()
                && old.tool_call_id.is_none()
            {
                continue;
            }
            return Err("子任务不能删除已保存的消息".into());
        };
        let calls_preserved = old.tool_calls.len() <= new.tool_calls.len()
            && old
                .tool_calls
                .iter()
                .zip(&new.tool_calls)
                .all(|(a, b)| a.id == b.id && a.name == b.name && a.arguments == b.arguments);
        let identity_preserved =
            old.id == new.id && old.role == new.role && old.tool_call_id == new.tool_call_id;
        let body_preserved = if old.complete {
            new.complete
                && old.text == new.text
                && old.thinking == new.thinking
                && old.tool_calls.len() == new.tool_calls.len()
        } else {
            new.text.starts_with(&old.text) && new.thinking.starts_with(&old.thinking)
        };
        if !identity_preserved || !body_preserved || !calls_preserved {
            return Err("子任务不能改写已保存的消息或工具执行结果".into());
        }
    }
    Ok(())
}

fn validate_chat(document: &ChatDocument, id: &str) -> Result<(), String> {
    version(document.version)?;
    validate_id(&document.conversation.id)?;
    if document.conversation.id != id {
        return Err("会话文件名与记录 ID 不一致".into());
    }
    bounded_text(&document.conversation.title, 1_024, "会话标题")?;
    if let Some(id) = &document.conversation.assistant_id {
        bounded_text(id, 128, "会话助手ID")?;
        if id.is_empty() {
            return Err("会话助手ID不能为空".into());
        }
    }
    if let Some(id) = &document.conversation.provider_id {
        bounded_text(id, 128, "会话供应商ID")?;
        if id.is_empty() {
            return Err("会话供应商ID不能为空".into());
        }
    }
    if let Some(model) = &document.conversation.model {
        bounded_text(model, 1024, "会话模型")?;
        if model.trim().is_empty() {
            return Err("会话模型不能为空".into());
        }
    }
    if document.conversation.title.trim().is_empty() || document.conversation.updated_at < 0 {
        return Err("会话标题或更新时间无效".into());
    }
    crate::chat::validate_conversation(&document.conversation.metadata, &document.messages)?;
    validate_queue(&document.queued_turns)?;
    validate_messages(&document.messages)
}

fn validate_queue(turns: &[QueuedTurn]) -> Result<(), String> {
    crate::composer::json_bytes(turns, 1024 * 1024)?;
    if turns.len() > 32 {
        return Err("排队消息超过 32 条或 1 MiB 上限".into());
    }
    let mut ids = HashSet::new();
    for turn in turns {
        validate_id(&turn.id)?;
        if let Some(reference) = &turn.transient {
            reference.validate()?;
            let command = crate::composer::slash::parse(&turn.text)
                .filter(|command| crate::composer::slash::sensitive_arguments(&command.name))
                .ok_or("敏感排队指令的占位文本无效")?;
            if !turn.attachments.is_empty()
                || crate::composer::slash::transcript_text(&turn.text, &command.name) != turn.text
            {
                return Err("敏感排队指令包含附件或未脱敏参数".into());
            }
        }
        if (turn.text.trim().is_empty() && turn.attachments.is_empty())
            || turn.text.contains('\0')
            || !ids.insert(&turn.id)
        {
            return Err("排队消息为空、包含 NUL 或标识重复".into());
        }
        if turn.attachments.len() > crate::composer::MAX_ATTACHMENTS {
            return Err("排队附件数量超过预算".into());
        }
        for attachment in &turn.attachments {
            attachment.validate()?;
        }
        crate::composer::validate_refs(&turn.attachments)?;
        crate::composer::input_json_bytes(&turn.text, &turn.attachments)?;
    }
    Ok(())
}

fn validate_messages(messages: &[Message]) -> Result<(), String> {
    if messages.len() > MAX_MESSAGES {
        return Err("会话消息数量超过预算".into());
    }
    let mut ids = HashSet::new();
    let mut text_bytes = 0usize;
    let mut declared_calls = HashSet::new();

    for message in messages {
        crate::chat::validate_message(&message.metadata)?;
        if message.role != "tool" {
            declared_calls.clear();
        }
        validate_id(&message.id)?;
        if !ids.insert(message.id.as_str()) {
            return Err("会话消息存在重复 ID".into());
        }
        if message.role.is_empty()
            || message.role.len() > 32
            || !message
                .role
                .bytes()
                .all(|byte| byte.is_ascii_alphabetic() || byte == b'_' || byte == b'-')
        {
            return Err("会话消息角色无效".into());
        }
        text_bytes = text_bytes
            .checked_add(message.text.len())
            .and_then(|value| value.checked_add(message.thinking.len()))
            .ok_or("会话文本长度溢出")?;
        if message.tool_calls.len() > 16
            || (!message.tool_calls.is_empty() && message.role != "assistant")
            || (message.tool_call_id.is_some() && message.role != "tool")
        {
            return Err("会话工具消息结构无效".into());
        }
        let mut call_ids = HashSet::new();
        for call in &message.tool_calls {
            if call.id.is_empty()
                || call.id.len() > 512
                || !call_ids.insert(&call.id)
                || !declared_calls.insert(&call.id)
                || call.name.is_empty()
                || call.name.len() > 128
                || call.arguments.len() > 1024 * 1024
            {
                return Err("会话工具调用元数据无效".into());
            }
            text_bytes = text_bytes
                .checked_add(call.id.len() + call.name.len() + call.arguments.len())
                .ok_or("工具元数据长度溢出")?;
        }
        if message
            .tool_call_id
            .as_ref()
            .is_some_and(|id| id.is_empty() || id.len() > 512)
        {
            return Err("工具结果标识无效".into());
        }
        if message.role == "tool" && message.tool_call_id.is_none() {
            return Err("工具结果缺少调用标识".into());
        }
        if let Some(call_id) = &message.tool_call_id {
            if !declared_calls.remove(call_id) {
                return Err("工具结果没有对应调用或重复".into());
            }
        }
        if text_bytes > MAX_CHAT_TEXT_BYTES {
            return Err("会话文本超过存储预算".into());
        }
    }
    Ok(())
}

fn validate_memories(memories: &[Memory]) -> Result<(), String> {
    if memories.len() > MAX_MEMORIES {
        return Err("记忆数量超过预算".into());
    }
    let mut ids = HashSet::new();
    let mut text_bytes = 0usize;
    for memory in memories {
        validate_id(&memory.id)?;
        if !ids.insert(memory.id.as_str()) {
            return Err("记忆存在重复 ID".into());
        }
        bounded_text(&memory.text, 256 * 1_024, "记忆内容")?;
        if memory.text.trim().is_empty() || memory.updated_at < 0 {
            return Err("记忆内容或更新时间无效".into());
        }
        text_bytes = text_bytes
            .checked_add(memory.text.len())
            .ok_or("记忆文本长度溢出")?;
        if text_bytes > MAX_MEMORY_TEXT_BYTES {
            return Err("记忆文本超过存储预算".into());
        }
    }
    Ok(())
}

fn validate_usage(records: &[UsageRecord]) -> Result<(), String> {
    if records.len() > MAX_USAGE_RECORDS {
        return Err("用量账本条目超过读取预算".into());
    }
    let mut ids = HashSet::new();
    let mut text_bytes = 0usize;
    for record in records {
        validate_usage_record(record)?;
        if !ids.insert(record.id.as_str()) {
            return Err("用量账本存在重复运行 ID".into());
        }
        for text in [&record.id, &record.conversation_id, &record.model] {
            text_bytes = text_bytes
                .checked_add(text.len())
                .ok_or("用量文本长度溢出")?;
        }
        text_bytes = text_bytes
            .checked_add(record.error.as_ref().map_or(0, String::len))
            .ok_or("用量文本长度溢出")?;
        if text_bytes > MAX_USAGE_TEXT_BYTES {
            return Err("用量账本文本超过存储预算；原有记录保留".into());
        }
    }
    Ok(())
}

fn validate_usage_record(record: &UsageRecord) -> Result<(), String> {
    validate_id(&record.id)?;
    validate_id(&record.conversation_id)?;
    bounded_text(&record.model, 16 * 1_024, "用量模型名")?;
    if let Some(error) = &record.error {
        bounded_text(error, 16 * 1_024, "用量错误信息")?;
    }
    if record.at < 0 {
        return Err("用量记录时间无效".into());
    }
    for count in [
        record.prompt_tokens,
        record.completion_tokens,
        record.reasoning_tokens,
        record.cached_tokens,
    ] {
        if count.is_some_and(|count| count < 0) {
            return Err("用量 token 数不能为负数".into());
        }
    }
    Ok(())
}

fn same_usage(first: &UsageRecord, second: &UsageRecord) -> bool {
    same_usage_identity(first, second)
        && first.prompt_tokens == second.prompt_tokens
        && first.completion_tokens == second.completion_tokens
        && first.reasoning_tokens == second.reasoning_tokens
        && first.cached_tokens == second.cached_tokens
        && first.error == second.error
        && first.pending == second.pending
}

fn same_usage_identity(first: &UsageRecord, second: &UsageRecord) -> bool {
    first.id == second.id
        && first.conversation_id == second.conversation_id
        && first.model == second.model
        && first.at == second.at
        && first.summary == second.summary
}

fn bounded_text(value: &str, limit: usize, label: &str) -> Result<(), String> {
    if value.len() > limit {
        Err(format!("{label}超过存储预算"))
    } else {
        Ok(())
    }
}

fn validate_id(id: &str) -> Result<(), String> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        Err("记录 ID 无效".into())
    } else {
        Ok(())
    }
}

fn version(found: u32) -> Result<(), String> {
    if found != VERSION {
        Err(format!("不支持的数据版本 {found}，未覆盖现有数据"))
    } else {
        Ok(())
    }
}

fn now() -> Result<i64, String> {
    let milliseconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "系统时间早于 Unix epoch，无法生成记录时间")?
        .as_millis();
    i64::try_from(milliseconds).map_err(|_| "系统时间超出记录范围".into())
}

fn fresh_id(kind: &str) -> Result<String, String> {
    Ok(format!(
        "{kind}-{:016x}-{:08x}-{:016x}",
        now()?,
        std::process::id(),
        NEXT_ID.fetch_add(1, Ordering::Relaxed)
    ))
}

fn is_temporary_name(name: &str) -> bool {
    name.strip_prefix(".sx-write-")
        .and_then(|name| name.strip_suffix(".tmp"))
        .is_some_and(|id| validate_id(id).is_ok())
}

fn owned_directory(root: &Path, name: &str) -> Result<PathBuf, String> {
    let path = root.join(name);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {}
        Ok(_) => return Err(format!("数据子目录 {name} 不是普通目录")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(&path).map_err(|error| fs_error("创建数据子目录", &path, error))?;
            sync_directory(root).map_err(|error| fs_error("同步数据目录", root, error))?;
        }
        Err(error) => return Err(fs_error("读取数据子目录", &path, error)),
    }
    contained_path(root, &path)
}

fn contained_path(root: &Path, path: &Path) -> Result<PathBuf, String> {
    let canonical =
        fs::canonicalize(path).map_err(|error| fs_error("解析文件路径", path, error))?;
    if !canonical.starts_with(root) {
        return Err("文件路径超出允许的工作区".into());
    }
    Ok(canonical)
}

fn checked_file(root: &Path, path: &Path) -> Result<File, String> {
    let canonical = contained_path(root, path)?;
    let expected = fs::symlink_metadata(&canonical)
        .map_err(|error| fs_error("读取文件信息", &canonical, error))?;
    if !expected.is_file() || expected.file_type().is_symlink() {
        return Err("目标不是可读取的普通文件".into());
    }
    let file = File::open(&canonical).map_err(|error| fs_error("打开文件", &canonical, error))?;
    let opened = file
        .metadata()
        .map_err(|error| fs_error("检查已打开文件", &canonical, error))?;
    if !opened.is_file()
        || !same_file(&expected, &opened)
        || contained_path(root, &canonical)? != canonical
    {
        return Err("读取期间文件路径发生变化，请重试".into());
    }
    Ok(file)
}

#[cfg(unix)]
fn same_file(expected: &Metadata, opened: &Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;
    expected.dev() == opened.dev() && expected.ino() == opened.ino()
}

#[cfg(not(unix))]
fn same_file(expected: &Metadata, opened: &Metadata) -> bool {
    expected.len() == opened.len() && expected.modified().ok() == opened.modified().ok()
}

fn optional_json<T: DeserializeOwned>(
    root: &Path,
    path: &Path,
    limit: u64,
) -> Result<Option<T>, String> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(fs_error("读取存储文件信息", path, error)),
        Ok(metadata) if metadata.file_type().is_symlink() => {
            return Err("存储文件不能是符号链接".into());
        }
        Ok(_) => {}
    }
    required_json(root, path, limit).map(Some)
}

fn required_json<T: DeserializeOwned>(root: &Path, path: &Path, limit: u64) -> Result<T, String> {
    let metadata =
        fs::symlink_metadata(path).map_err(|error| fs_error("读取存储文件信息", path, error))?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err("存储记录不是普通文件".into());
    }
    let file = checked_file(root, path)?;
    if file
        .metadata()
        .map_err(|error| fs_error("检查存储文件大小", path, error))?
        .len()
        > limit
    {
        return Err(format!("存储文件 {} 超过读取预算", path.display()));
    }
    let mut bytes = Vec::new();
    BufReader::new(file)
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| fs_error("读取存储文件", path, error))?;
    if bytes.len() as u64 > limit {
        return Err(format!("存储文件 {} 在读取期间超过预算", path.display()));
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("存储文件 {} 损坏或格式不兼容：{error}", path.display()))
}

struct CommitFailure {
    message: String,
    replaced: bool,
}

impl From<String> for CommitFailure {
    fn from(message: String) -> Self {
        Self {
            message,
            replaced: false,
        }
    }
}

struct TemporaryFile {
    path: PathBuf,
    active: bool,
}

impl Drop for TemporaryFile {
    fn drop(&mut self) {
        if self.active {
            let _ = fs::remove_file(&self.path);
        }
    }
}

struct LimitedWriter<W> {
    inner: W,
    remaining: u64,
}

impl<W: Write> Write for LimitedWriter<W> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() as u64 > self.remaining {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "序列化数据超过写入预算",
            ));
        }
        let written = self.inner.write(bytes)?;
        self.remaining -= written as u64;
        Ok(written)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

fn atomic_json<T: Serialize>(
    root: &Path,
    target: &Path,
    value: &T,
    limit: u64,
) -> Result<(), CommitFailure> {
    atomic_document(root, target, limit, |writer| {
        serde_json::to_writer(writer, value)
            .map_err(|error| format!("写入存储文件 {} 失败：{error}", target.display()))
    })
}

fn atomic_bytes(root: &Path, target: &Path, bytes: &[u8]) -> Result<(), CommitFailure> {
    atomic_document(root, target, bytes.len() as u64, |writer| {
        writer.write_all(bytes).map_err(|_| "历史段写入失败".into())
    })
}

fn atomic_document(
    root: &Path,
    target: &Path,
    limit: u64,
    write: impl FnOnce(&mut LimitedWriter<BufWriter<File>>) -> Result<(), String>,
) -> Result<(), CommitFailure> {
    let parent = target.parent().ok_or("存储目标缺少父目录".to_owned())?;
    if contained_path(root, parent)? != parent {
        return Err("存储父目录发生变化".to_owned().into());
    }
    let temporary_path = parent.join(format!(".sx-write-{}.tmp", fresh_id("w")?));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options
        .open(&temporary_path)
        .map_err(|error| fs_error("创建临时存储文件", &temporary_path, error))?;
    // Cleanup starts only after create_new confirms ownership of this temporary file.
    let mut temporary = TemporaryFile {
        path: temporary_path,
        active: true,
    };
    let mut writer = LimitedWriter {
        inner: BufWriter::new(file),
        remaining: limit,
    };
    write(&mut writer)?;
    writer
        .flush()
        .map_err(|error| fs_error("刷新存储文件", &temporary.path, error))?;
    writer
        .inner
        .get_ref()
        .sync_all()
        .map_err(|error| fs_error("同步存储文件", &temporary.path, error))?;
    drop(writer);
    if contained_path(root, parent)? != parent {
        return Err("提交前存储父目录发生变化".to_owned().into());
    }
    fs::rename(&temporary.path, target)
        .map_err(|error| fs_error("原子替换存储文件", target, error))?;
    temporary.active = false;
    sync_directory(parent).map_err(|error| CommitFailure {
        message: fs_error("同步存储父目录", parent, error),
        replaced: true,
    })?;
    Ok(())
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> io::Result<()> {
    File::open(directory)?.sync_all()
}

// Android uses the Unix implementation above. Other hosts still sync the temporary file;
// the standard library does not provide a portable directory metadata sync operation.
#[cfg(not(unix))]
fn sync_directory(_directory: &Path) -> io::Result<()> {
    Ok(())
}

fn fs_error(operation: &str, path: &Path, error: io::Error) -> String {
    format!("{operation} {} 失败：{error}", path.display())
}

fn inferred_secret_edit(value: &str, previous_plain: &str, stored: &StoredSecret) -> SecretEdit {
    if value.is_empty() || value == previous_plain && !stored.is_empty() {
        SecretEdit::Keep
    } else {
        SecretEdit::Set(value.to_owned())
    }
}
fn secret_warning(warnings: &mut Vec<String>, message: &str) {
    if warnings.len() < 64 && !warnings.iter().any(|v| v == message) {
        warnings.push(message.into());
    }
}
fn read_secret(stored: &StoredSecret, warnings: &mut Vec<String>, label: &str) -> String {
    match SecretBox.decrypt(stored) {
        Ok(value) => value,
        Err(_) => {
            secret_warning(warnings, label);
            String::new()
        }
    }
}
fn hydrate_settings_secrets(
    settings: &mut Settings,
    secrets: &StoredSettingsSecrets,
    warnings: &mut Vec<String>,
) {
    settings.api_key = read_secret(
        &secrets.api_key,
        warnings,
        "主模型凭据无法解密；原密文保留，请重新填写",
    );
    settings.asr_key = read_secret(
        &secrets.asr_api_key,
        warnings,
        "转写凭据无法解密；原密文保留，请重新填写",
    );
    settings.search_key = read_secret(
        &secrets.search_api_key,
        warnings,
        "搜索凭据无法解密；原密文保留，请重新填写",
    );
    for provider in &mut settings.providers {
        provider.api_key = secrets
            .provider_keys
            .get(&provider.id)
            .map(|value| {
                read_secret(
                    value,
                    warnings,
                    "部分供应商凭据无法解密；原密文保留，请重新填写",
                )
            })
            .unwrap_or_default();
    }
    settings.request_headers = secrets
        .request_headers
        .iter()
        .filter_map(|(name, value)| {
            let plain = read_secret(
                value,
                warnings,
                "部分请求头无法解密；原密文保留，请重新填写",
            );
            (!plain.is_empty()).then(|| (name.clone(), plain))
        })
        .collect();
    if !secrets.search_api_key.is_empty() && SecretBox.decrypt(&secrets.search_api_key).is_err() {
        secret_warning(warnings, "搜索凭据无法解密；原密文保留，请重新填写");
    }
}
fn hydrate_mcp_secrets(
    servers: &mut [crate::mcp::ServerConfig],
    secrets: &BTreeMap<String, StoredMcpSecrets>,
    warnings: &mut Vec<String>,
) -> HashSet<String> {
    let mut locked = HashSet::new();
    for server in servers {
        if let Some(secret) = secrets.get(&server.id) {
            let env = if secret.env.is_empty() { Ok(std::mem::take(&mut server.env)) } else { SecretBox.decrypt(&secret.env) };
            let headers = if secret.headers.is_empty() { Ok(std::mem::take(&mut server.headers)) } else { SecretBox.decrypt(&secret.headers) };
            if env.is_err() || headers.is_err() {
                locked.insert(server.id.clone());
                secret_warning(
                    warnings,
                    "部分 MCP 凭据无法解密，连接已阻止；原密文保留，请重新填写",
                );
            }
            server.env = env.unwrap_or_default();
            server.headers = headers.unwrap_or_default();
        }
    }
    locked
}

//! Explicit, lossless legacy migration. The actor owns exclusion and merges current
//! Rust documents before staging. Source files are never modified or deleted.
use crate::{
    legacy_import::LegacySnapshot,
    platform::secrets::{SecretCipher, StoredMcpSecrets, StoredSettingsSecrets},
    state::{Conversation, Memory, Message, QueuedTurn, Settings},
};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, path::PathBuf};

mod chat_archive;
mod conversion;
mod transaction;
mod workspace_assets;
pub use chat_archive::{ChatArchive, ChatSegment, encode_chat_archive, read_chat_archive};
pub(crate) use conversion::read_archived_blob_bounded;
pub(crate) use conversion::remap_conversation_references;
pub(crate) use conversion::settings as decode_shared_settings;
pub use conversion::{prepare, read_archived_blob};
pub(crate) use transaction::read_regular as read_owned_regular;
pub use transaction::{
    ArchiveCatalog, CommitReceipt, RecoveryEntry, RecoveryState, StagedMigration,
    already_committed, archive_catalog, commit, recover, resolve_active, snapshot_fingerprint,
    stage, verify_snapshot,
};
pub use workspace_assets::{
    prepare_workspace_assets, resolve_workspace_path, workspace_source_proofs,
};

/// A complete candidate file relative to the new generation's state directory.
/// Callers must supply every current document/asset they intend to preserve.
pub struct GenerationFile {
    pub path: PathBuf,
    pub bytes: Vec<u8>,
}
impl std::fmt::Debug for GenerationFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GenerationFile")
            .field("path", &self.path)
            .field("bytes", &self.bytes.len())
            .finish()
    }
}

pub struct MigrationPlan {
    pub source_fingerprint: String,
    pub conversations: Vec<ImportedConversation>,
    pub settings: Option<ImportedSettings>,
    pub mcp: Vec<ImportedMcp>,
    pub memories: Vec<Memory>,
    pub goal: Option<crate::goal::Goal>,
    pub schedules: Vec<crate::schedules::Schedule>,
    pub skills: Vec<crate::skills::Skill>,
    pub children: Vec<ImportedChild>,
    pub usage: ImportedUsage,
    pub performance: Option<crate::performance::Document>,
    pub conversation_preferences: Option<crate::conversation_preferences::Document>,
    pub reading_positions: Vec<crate::display::ReadPosition>,
    pub todos: Vec<ImportedTodo>,
    /// Original source paths map to encrypted, exact byte archives. Unsupported
    /// feature data is retained here rather than declared operational.
    pub assets: Vec<AssetMapping>,
    pub workspace_directories: Vec<WorkspaceDirectoryMapping>,
    pub(crate) plugin_layout: Vec<crate::plugin_assets::Layout>,
    pub(crate) plugin_files: Vec<GenerationFile>,
    pub warnings: Vec<String>,
    pub retained_features: Vec<String>,
    pub(crate) archives: Vec<GenerationFile>,
}

pub struct ImportedConversation {
    pub conversation: Conversation,
    pub messages: Vec<Message>,
    pub queued_turns: Vec<QueuedTurn>,
    /// A crashed live run remains a recovery candidate, never an active process.
    pub recovery: Option<ImportedRecovery>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ImportedRecovery {
    pub run_id: String,
    pub messages: Vec<Message>,
    pub live_text: String,
    pub live_reasoning: String,
    pub interrupted: bool,
}
pub struct ImportedSettings {
    pub settings: Settings,
    pub secrets: StoredSettingsSecrets,
}
pub struct ImportedMcp {
    pub config: crate::mcp::ServerConfig,
    pub secrets: StoredMcpSecrets,
}
pub struct ImportedChild {
    pub parent_chat: String,
    pub parent_run: String,
    pub record: crate::subagents::Record,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ImportedTodo {
    pub id: String,
    pub text: String,
    pub status: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// Aggregate legacy summaries are separate from individual Rust requests; no
/// synthetic request rows or double counting a cache and its source transcript.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ImportedUsage {
    pub schema_version: i64,
    pub zone_id: String,
    pub deleted: BTreeMap<String, LegacyChatUsage>,
    pub cache: BTreeMap<String, LegacyChatUsage>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct LegacyTokens {
    pub prompt: i64,
    pub completion: i64,
    pub reasoning: i64,
    pub cached: i64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct LegacyUsageBucket {
    pub tokens: LegacyTokens,
    pub reported: i64,
    pub unreported: i64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct LegacyChatUsage {
    pub days: BTreeMap<String, LegacyTokens>,
    pub models: BTreeMap<String, LegacyTokens>,
    pub scanned: i64,
    #[serde(rename = "scannedUpTo")]
    pub scanned_up_to: i64,
    #[serde(rename = "scannedBytes")]
    pub scanned_bytes: i64,
    #[serde(rename = "scannedGen")]
    pub scanned_gen: i64,
    #[serde(rename = "dailyModels")]
    pub daily_models: BTreeMap<String, BTreeMap<String, LegacyUsageBucket>>,
    #[serde(rename = "sourceStamp")]
    pub source_stamp: String,
    #[serde(rename = "deletedAt")]
    pub deleted_at: i64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AssetMapping {
    pub owning_root: PathBuf,
    pub relative_path: String,
    pub sha256: String,
    pub bytes: u64,
    /// Relative to generation, outside its state directory.
    pub archive_path: PathBuf,
    /// Relative to the fixed namespace workspace. Only user workspace assets
    /// have this destination; private raw state remains sealed in its archive.
    #[serde(default)]
    pub workspace_path: Option<PathBuf>,
    #[serde(default)]
    pub workspace_mode: Option<u32>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WorkspaceDirectoryMapping {
    pub owning_root: PathBuf,
    pub relative_path: String,
    pub workspace_path: PathBuf,
    #[serde(default)]
    pub unix_mode: Option<u32>,
}

pub(crate) fn digest(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(bytes))
}

pub(crate) fn source_blobs(snapshot: &LegacySnapshot) -> Vec<&crate::legacy_import::LegacyBlob> {
    let mut blobs = Vec::new();
    for chat in &snapshot.conversations {
        blobs.push(&chat.metadata.source);
        if let Some(source) = &chat.committed_source {
            blobs.push(source);
        }
        if let Some(live) = &chat.live_draft {
            blobs.push(&live.manifest.source);
            if let Some(payload) = &live.message_payload {
                blobs.push(&payload.source);
            }
        }
    }
    blobs.extend(snapshot.documents.iter().map(|d| &d.source));
    blobs.extend(snapshot.preference_stores.iter().map(|p| &p.source));
    blobs.extend(&snapshot.assets);
    blobs
}

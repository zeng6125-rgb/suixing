//! Read-only usage inputs are captured by the owning actor, then projected on
//! one bounded worker. No renderer cache or live run draft proves membership.
use super::*;
use crate::legacy_usage::{self, CommittedSource, LegacyChat, LegacyDocument, RequestSource};
use std::collections::BTreeSet;

const RECEIPT_BYTES: u64 = 32 * 1024 * 1024;
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipts {
    version: u32,
    entries: BTreeMap<String, Deletion>,
}
impl Default for Receipts {
    fn default() -> Self {
        Self {
            version: 1,
            entries: BTreeMap::new(),
        }
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Deletion {
    id: String,
    prepared_at: i64,
    committed_at: Option<i64>,
    fingerprint: String,
    legacy: Vec<LegacyChat>,
    request_ids: BTreeSet<String>,
}
#[derive(Clone)]
pub(crate) struct UsageInput {
    namespace: PathBuf,
    root: PathBuf,
    legacy_root: PathBuf,
    chats: PathBuf,
    children: PathBuf,
    conversations: BTreeSet<String>,
    records: Vec<UsageRecord>,
    receipt_uncertain: bool,
    shared_files: Option<PathBuf>,
}
pub(crate) struct Projection {
    pub legacy: LegacyDocument,
    pub requests: Vec<RequestSource>,
}
impl Store {
    pub(crate) fn usage_input(&self) -> UsageInput {
        UsageInput {
            namespace: self.namespace_root.clone(),
            root: self.root.clone(),
            legacy_root: self.legacy_source_root.clone(),
            chats: self.chat_directory.clone(),
            children: self.child_directory.clone(),
            conversations: self
                .conversations
                .iter()
                .map(|chat| chat.id.clone())
                .collect(),
            records: self.usage(),
            receipt_uncertain: self.write_blocked.is_some(),
            shared_files: self.shared_files_root.clone(),
        }
    }
    pub(super) fn prepare_usage_deletion(&mut self, id: &str) -> Result<Deletion, String> {
        let (mut legacy, fingerprint) = if self.shared_files_root.is_some() {
            // The shared deletion writes Compose's durable summary before removing its log.
            (Vec::new(), String::new())
        } else {
            let zone = if crate::legacy_migration::archive_catalog(&self.namespace_root)?.is_some()
            {
                crate::platform::calendar::zone_id()?
            } else {
                String::new()
            };
            let projection = self.usage_input().project(&zone)?;
            let child_prefix = format!("child/{id}/");
            let summaries = projection
                .legacy
                .chats
                .into_values()
                .filter(|source| source.id == id || source.id.starts_with(&child_prefix))
                .collect::<Vec<_>>();
            (summaries, projection.legacy.source_fingerprint)
        };
        // These are final token contributions, never transcript retention.
        let at = now()?;
        for source in &mut legacy {
            source.deleted = true;
            source.deleted_at = at;
        }
        let deletion = Deletion {
            id: id.into(),
            prepared_at: at,
            committed_at: None,
            fingerprint,
            legacy,
            request_ids: self
                .usage
                .iter()
                .filter(|row| row.conversation_id == id)
                .map(|row| row.id.clone())
                .collect(),
        };
        let mut receipts = read_receipts(&self.root)?;
        if receipts
            .entries
            .get(id)
            .is_some_and(|entry| entry.committed_at.is_some())
        {
            return Err("此身份已有已提交删除回执，未覆盖".into());
        }
        receipts.entries.insert(id.into(), deletion.clone());
        validate_receipts(&receipts)?;
        self.persist(
            &self.root.join("usage-deletions.json"),
            &receipts,
            RECEIPT_BYTES,
        )?;
        Ok(deletion)
    }
    pub(super) fn commit_usage_deletion(&mut self, mut deletion: Deletion) -> Result<(), String> {
        // Caller has removed every authoritative chat entry and synced its directory.
        let mut receipts = read_receipts(&self.root)?;
        let prepared = receipts
            .entries
            .get(&deletion.id)
            .ok_or("删除准备回执已不存在")?;
        if prepared.prepared_at != deletion.prepared_at || prepared.committed_at.is_some() {
            return Err("删除准备回执身份已变化".into());
        }
        deletion.committed_at = Some(now()?);
        receipts.entries.insert(deletion.id.clone(), deletion);
        validate_receipts(&receipts)?;
        self.persist(
            &self.root.join("usage-deletions.json"),
            &receipts,
            RECEIPT_BYTES,
        )
    }
}
fn read_receipts(root: &Path) -> Result<Receipts, String> {
    let value = optional_owned_json(root, &root.join("usage-deletions.json"), RECEIPT_BYTES)?
        .unwrap_or_default();
    validate_receipts(&value)?;
    Ok(value)
}
fn validate_receipts(receipts: &Receipts) -> Result<(), String> {
    if receipts.version != 1 || receipts.entries.len() > MAX_CONVERSATIONS {
        return Err("删除用量回执版本或数量无效".into());
    }
    let mut sources = 0usize;
    let mut requests = 0usize;
    for (id, receipt) in &receipts.entries {
        validate_id(id)?;
        if &receipt.id != id
            || receipt.prepared_at <= 0
            || receipt
                .committed_at
                .is_some_and(|at| at < receipt.prepared_at)
        {
            return Err("删除用量回执身份或时间无效".into());
        }
        sources += receipt.legacy.len();
        requests += receipt.request_ids.len();
        if sources > 8192 || requests > MAX_USAGE_RECORDS {
            return Err("删除用量回执超过预算".into());
        }
        let document = LegacyDocument {
            version: 1,
            schema_version: 3,
            zone_id: String::new(),
            source_fingerprint: receipt.fingerprint.clone(),
            chats: receipt
                .legacy
                .iter()
                .map(|chat| (chat.id.clone(), chat.clone()))
                .collect(),
            warnings: Vec::new(),
        };
        legacy_usage::validate(&document)?;
        if document.chats.len() != receipt.legacy.len()
            || receipt.legacy.iter().any(|source| {
                !source.deleted
                    || source.deleted_at <= 0
                    || source.id != *id && !source.id.starts_with(&format!("child/{id}/"))
            })
        {
            return Err("删除用量回执来源家族不匹配".into());
        }
        for request in &receipt.request_ids {
            validate_id(request)?;
        }
    }
    Ok(())
}
impl UsageInput {
    pub(crate) fn namespace(&self) -> &Path {
        &self.root
    }
    fn check_namespace(&self) -> Result<(), String> {
        let active = crate::legacy_migration::resolve_active(&self.namespace)?
            .unwrap_or_else(|| self.namespace.clone());
        if active != self.root {
            return Err("统计期间活动存储已切换，请重新查询".into());
        }
        Ok(())
    }
    fn chat(&self, id: &str) -> Result<ChatDocument, String> {
        validate_id(id)?;
        if !self.conversations.contains(id) {
            return Err("统计源未由Store会话metadata确认".into());
        }
        if let Some(files) = self.shared_files.as_deref() {
            return super::shared_chat::read(files, id);
        }
        let document = if self.chats.join(format!("{id}.segments.json")).exists() {
            let (conversation, messages, queued_turns) =
                crate::legacy_migration::read_chat_archive(&self.root, id)?;
            ChatDocument {
                version: VERSION,
                conversation,
                messages,
                queued_turns,
            }
        } else {
            owned_json(
                &self.root,
                &self.chats.join(format!("{id}.json")),
                MAX_CHAT_BYTES,
            )?
        };
        validate_chat(&document, id)?;
        Ok(document)
    }
    fn child(&self, parent: &str, id: &str) -> Result<Option<ChildDocument>, String> {
        validate_id(parent)?;
        validate_id(id)?;
        let path = self.children.join(parent).join(format!("{id}.json"));
        let Some(document) =
            optional_owned_json::<ChildDocument>(&self.root, &path, MAX_CHILD_BYTES)?
        else {
            return Ok(None);
        };
        version(document.version)?;
        if document.parent_chat != parent || document.record.id != id {
            return Err("统计子来源归属不匹配".into());
        }
        validate_messages(&document.record.messages)?;
        validate_saved_child_config(document.record.config.as_ref())?;
        Ok(Some(document))
    }
    pub(crate) fn project(&self, chosen_zone: &str) -> Result<Projection, String> {
        self.check_namespace()?;
        let receipts = read_receipts(&self.root)?;
        let receipt_bytes = serde_json::to_vec(&receipts).map_err(|_| "删除回执无法编码")?;
        let shared = self.shared_files.as_deref();
        let catalog = if shared.is_none() {
            crate::legacy_migration::archive_catalog(&self.namespace)?
        } else {
            None
        };
        let mut legacy = if let Some(files) = shared {
            super::shared_usage::read(files, &self.conversations, chosen_zone, |at, zone| {
                let stamp = crate::platform::calendar::day_for(at, zone)?;
                Ok(legacy_usage::DayStamp {
                    zone_id: stamp.zone_id,
                    day: stamp.day,
                })
            })?
        } else {
            legacy_usage::empty()
        };
        let mut alive = if shared.is_some() {
            self.conversations.clone()
        } else {
            BTreeSet::new()
        };
        if let Some(catalog) = &catalog {
            if catalog.generation_root.join("state") != self.root {
                return Err("归档来源不是当前Store活动代".into());
            }
            let inputs = legacy_usage::archives::load(catalog, &self.legacy_root)?;
            legacy = legacy_usage::from_archived(&inputs.documents, &catalog.source_fingerprint)?;
            let mut replacements = Vec::new();
            let mut count = 0usize;
            for source in inputs.sources {
                if legacy
                    .chats
                    .get(&source.id)
                    .is_some_and(|chat| chat.deleted)
                {
                    continue;
                }
                let (rows, committed) = if self.conversations.contains(&source.parent) {
                    if let Some(child) = &source.child {
                        match self.child(&source.parent, child)? {
                            Some(document) => (
                                document
                                    .record
                                    .messages
                                    .iter()
                                    .filter(|row| source.members.contains(&row.id))
                                    .map(legacy_usage::archives::current_row)
                                    .collect(),
                                true,
                            ),
                            None => (source.rows, false),
                        }
                    } else {
                        let document = self.chat(&source.parent)?;
                        alive.insert(source.parent.clone());
                        (
                            document
                                .messages
                                .iter()
                                .filter(|row| source.members.contains(&row.id))
                                .map(legacy_usage::archives::current_row)
                                .collect(),
                            true,
                        )
                    }
                } else {
                    (source.rows, false)
                };
                count += rows.len();
                if count > 262_144 {
                    return Err("用量已提交元数据超过预算".into());
                }
                let zone = legacy
                    .chats
                    .get(&source.id)
                    .map(|chat| chat.zone_id.as_str())
                    .filter(|zone| !zone.is_empty())
                    .unwrap_or(chosen_zone)
                    .to_owned();
                if legacy
                    .chats
                    .get(&source.id)
                    .is_none_or(|chat| chat.zone_id.is_empty())
                {
                    legacy.warnings.push(format!(
                        "来源{}未保存日历时区，已提交时间戳按查询时区{}恢复",
                        source.id, chosen_zone
                    ));
                }
                replacements.push((
                    CommittedSource {
                        id: source.id,
                        zone_id: zone,
                        rows,
                    },
                    committed,
                ));
            }
            let unproven: Vec<_> = replacements
                .iter()
                .filter(|(_, live)| !live)
                .map(|(source, _)| source.id.clone())
                .collect();
            legacy_usage::replace_committed(
                &mut legacy,
                &replacements
                    .into_iter()
                    .map(|(source, _)| source)
                    .collect::<Vec<_>>(),
                |at, zone| {
                    let stamp = crate::platform::calendar::day_for(at, zone)?;
                    Ok(legacy_usage::DayStamp {
                        zone_id: stamp.zone_id,
                        day: stamp.day,
                    })
                },
            )?;
            // Archived original membership proves token rows, not present-day existence.
            for id in unproven {
                if let Some(source) = legacy.chats.get_mut(&id) {
                    source.rebuilt_from_committed = false;
                }
            }
            catalog.recheck()?;
        }
        for receipt in receipts.entries.values() {
            // A failed confirmation retains final prepared token evidence while
            // its deletion flag remains unknown. A still-live source is read anew.
            if self.conversations.contains(&receipt.id) && receipt.committed_at.is_none() {
                continue;
            }
            if shared.is_none()
                && legacy.source_fingerprint.is_empty()
                && !receipt.legacy.is_empty()
            {
                legacy.source_fingerprint = receipt.fingerprint.clone();
            }
            if shared.is_none()
                && !receipt.legacy.is_empty()
                && legacy.source_fingerprint != receipt.fingerprint
            {
                return Err("删除用量回执来自另一旧来源指纹".into());
            }
            for source in &receipt.legacy {
                if shared.is_some() && legacy.chats.contains_key(&source.id) {
                    continue;
                }
                let mut final_source = source.clone();
                if receipt.committed_at.is_none() || self.receipt_uncertain {
                    final_source.deleted = false;
                    final_source.deleted_at = 0;
                    final_source.rebuilt_from_committed = false;
                }
                legacy.chats.insert(source.id.clone(), final_source);
            }
        }
        let mut requests = Vec::new();
        for record in &self.records {
            let receipt = receipts
                .entries
                .get(&record.conversation_id)
                .filter(|receipt| receipt.request_ids.contains(&record.id));
            let deleted = if receipt.is_some_and(|receipt| receipt.committed_at.is_some())
                && !self.receipt_uncertain
            {
                Some(true)
            } else if self.conversations.contains(&record.conversation_id) {
                if !alive.contains(&record.conversation_id) {
                    self.chat(&record.conversation_id)?;
                    alive.insert(record.conversation_id.clone());
                }
                Some(false)
            } else if receipt.is_some() {
                None
            } else if legacy
                .chats
                .get(&record.conversation_id)
                .is_some_and(|source| source.deleted && source.deleted_at > 0)
            {
                Some(true)
            } else {
                None
            };
            requests.push(RequestSource {
                record: record.clone(),
                deleted,
            });
        }
        if serde_json::to_vec(&read_receipts(&self.root)?).map_err(|_| "删除回执无法编码")?
            != receipt_bytes
        {
            return Err("统计期间删除回执变化，请重新查询".into());
        }
        self.check_namespace()?;
        if self.receipt_uncertain {
            legacy
                .warnings
                .push("当前Store有未确认存储同步，删除回执保持未知，请重开后核对".into());
        }
        legacy_usage::validate(&legacy)?;
        Ok(Projection { legacy, requests })
    }
}
fn owned_json<T: DeserializeOwned>(root: &Path, path: &Path, limit: u64) -> Result<T, String> {
    let relative = path.strip_prefix(root).map_err(|_| "用量存储来源越界")?;
    let bytes = crate::legacy_migration::read_owned_regular(root, relative, limit)?;
    serde_json::from_slice(&bytes).map_err(|_| "用量存储记录损坏".into())
}
fn optional_owned_json<T: DeserializeOwned>(
    root: &Path,
    path: &Path,
    limit: u64,
) -> Result<Option<T>, String> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err("用量存储来源状态无法读取".into()),
        Ok(_) => owned_json(root, path, limit).map(Some),
    }
}

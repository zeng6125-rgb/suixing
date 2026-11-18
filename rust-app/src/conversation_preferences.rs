//! View preferences remain independent of transcripts and exports.
use crate::{
    display::ConversationSummary,
    legacy_import::{LegacyDocumentKind, LegacySnapshot},
    runtime_host::Publisher,
    state::Event,
    store::Store,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    time::{Duration, Instant},
};
const MAX_IDS: usize = 8192;
const DOCUMENT_BYTES: usize = 4 * 1024 * 1024;
pub(crate) fn id_valid(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Order {
    pub pinned: Vec<String>,
    pub normal: Vec<String>,
}
impl Order {
    pub fn indices(&self, conversations: &[ConversationSummary]) -> Vec<usize> {
        let pinned_ids = self
            .pinned
            .iter()
            .map(String::as_str)
            .collect::<HashSet<_>>();
        let normal_ids = self
            .normal
            .iter()
            .map(String::as_str)
            .collect::<HashSet<_>>();
        let mut by_id = HashMap::new();
        let mut unlisted_pinned = Vec::new();
        let mut unlisted_normal = Vec::new();
        for (index, conversation) in conversations.iter().enumerate() {
            if conversation.archived {
                continue;
            }
            by_id.insert(conversation.id.as_str(), index);
            if conversation.pinned {
                if !pinned_ids.contains(conversation.id.as_str()) {
                    unlisted_pinned.push(index);
                }
            } else if !normal_ids.contains(conversation.id.as_str()) {
                unlisted_normal.push(index);
            }
        }
        let sort = |list: &mut Vec<usize>| {
            list.sort_by(|&a, &b| {
                conversations[b]
                    .updated_at
                    .cmp(&conversations[a].updated_at)
                    .then_with(|| conversations[a].id.cmp(&conversations[b].id))
            })
        };
        sort(&mut unlisted_pinned);
        sort(&mut unlisted_normal);
        let mut result = Vec::new();
        let mut included = HashSet::new();
        for id in &self.pinned {
            if let Some(&index) = by_id.get(id.as_str()) {
                if conversations[index].pinned && included.insert(index) {
                    result.push(index);
                }
            }
        }
        for index in unlisted_pinned {
            if included.insert(index) {
                result.push(index);
            }
        }
        for index in unlisted_normal {
            if included.insert(index) {
                result.push(index);
            }
        }
        for id in &self.normal {
            if let Some(&index) = by_id.get(id.as_str()) {
                if !conversations[index].pinned && included.insert(index) {
                    result.push(index);
                }
            }
        }
        result
    }
    fn validate(&self) -> Result<(), String> {
        for ids in [&self.pinned, &self.normal] {
            if ids.len() > MAX_IDS {
                return Err("会话顺序超过8192个ID".into());
            }
            let mut unique = HashSet::new();
            for id in ids {
                if !id_valid(id) || !unique.insert(id) {
                    return Err("会话顺序包含无效或重复ID".into());
                }
            }
        }
        Ok(())
    }
    pub fn pruned(&self, known: &HashSet<String>) -> Self {
        let clean = |ids: &[String]| {
            let mut unique = HashSet::new();
            ids.iter()
                .filter(|id| known.contains(*id) && unique.insert(id.as_str()))
                .cloned()
                .collect()
        };
        Self {
            pinned: clean(&self.pinned),
            normal: clean(&self.normal),
        }
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Document {
    pub version: u32,
    pub revision: u64,
    pub order: Order,
    pub completed_unread: BTreeSet<String>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
impl Default for Document {
    fn default() -> Self {
        Self {
            version: 1,
            revision: 0,
            order: Order::default(),
            completed_unread: BTreeSet::new(),
            extra: BTreeMap::new(),
        }
    }
}
impl Document {
    pub fn validate(&self) -> Result<(), String> {
        if self.version != 1 || self.completed_unread.len() > MAX_IDS {
            return Err("会话查看偏好版本或ID数量无效".into());
        }
        self.order.validate()?;
        if self.completed_unread.iter().any(|id| !id_valid(id)) {
            return Err("完成未读偏好包含无效ID".into());
        }
        struct Counter(usize);
        impl std::io::Write for Counter {
            fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
                self.0 = self.0.saturating_add(bytes.len());
                if self.0 > DOCUMENT_BYTES {
                    return Err(std::io::Error::other("preference budget"));
                }
                Ok(bytes.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        serde_json::to_writer(Counter(0), self).map_err(|_| "会话查看偏好超过4MiB预算".into())
    }
    pub(crate) fn advance(&mut self) -> bool {
        if let Some(next) = self.revision.checked_add(1) {
            self.revision = next;
            true
        } else {
            false
        }
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Stamp {
    pub generation: String,
    pub revision: u64,
}
#[derive(Clone, Debug, Default, Serialize)]
pub struct Snapshot {
    pub stamp: Stamp,
    pub order: Order,
    pub completed_unread: BTreeSet<String>,
    pub confirmed: bool,
}
#[derive(Clone, Debug)]
pub enum Request {
    Move {
        request_id: String,
        expected: Stamp,
        source: String,
        target: String,
        after: bool,
    },
    Viewing {
        observer_id: u64,
        sequence: u64,
        generation: String,
        conversation_id: Option<String>,
        history_stamp: Option<crate::display::SourceStamp>,
    },
}
struct Viewing {
    sequence: u64,
    conversation_id: Option<String>,
    history_stamp: Option<crate::display::SourceStamp>,
    generation: String,
    touched: Instant,
}
#[derive(Default)]
pub struct Runtime {
    views: HashMap<u64, Viewing>,
    published: Option<(String, u64, bool)>,
    last_flush: Option<Instant>,
}
fn foreground() -> bool {
    #[cfg(target_os = "android")]
    {
        crate::platform::android::activity_visible()
    }
    #[cfg(not(target_os = "android"))]
    {
        true
    }
}
impl Runtime {
    pub fn dispatch(&mut self, request: Request, store: &mut Store, publish: &Publisher) {
        match request {
            Request::Move {
                request_id,
                expected,
                source,
                target,
                after,
            } => {
                let result =
                    store.preferences_move(&request_id, &expected, &source, &target, after);
                publish.emit(Event::ConversationPreferences(store.preference_snapshot()));
                publish.emit(Event::ConversationOrderFinished { request_id, result });
            }
            Request::Viewing {
                observer_id,
                sequence,
                generation,
                conversation_id,
                history_stamp,
            } => {
                if observer_id == 0
                    || sequence == 0
                    || !publish.ui_observer_alive(observer_id)
                    || generation != store.history_generation_token()
                {
                    return;
                }
                if conversation_id
                    .as_ref()
                    .is_some_and(|id| !id_valid(id) || store.conversation(id).is_err())
                {
                    return;
                }
                if self
                    .views
                    .get(&observer_id)
                    .is_some_and(|old| sequence <= old.sequence)
                {
                    return;
                }
                if self.views.len() >= 8 && !self.views.contains_key(&observer_id) {
                    self.views.retain(|id, view| {
                        publish.ui_observer_alive(*id)
                            && view.touched.elapsed() < Duration::from_secs(2)
                    });
                    if self.views.len() >= 8 {
                        return;
                    }
                }
                let conversation_id = if foreground() { conversation_id } else { None };
                if let Some(id) = conversation_id.as_deref() {
                    let source = crate::display::ViewSource::Main {
                        conversation_id: id.to_owned(),
                    };
                    if history_stamp.as_ref() != Some(&store.history_stamp_for(&source)) {
                        return;
                    }
                    let changed = store.preferences_unread(id);
                    store.preferences_seen(id);
                    if changed {
                        let _ = store.flush_preferences();
                    }
                }
                self.views.insert(
                    observer_id,
                    Viewing {
                        sequence,
                        conversation_id,
                        history_stamp,
                        generation,
                        touched: Instant::now(),
                    },
                );
                self.publish(store, publish);
            }
        }
    }
    fn viewing(&self, id: &str, store: &Store, publish: &Publisher) -> bool {
        foreground()
            && self.views.iter().any(|(observer, view)| {
                view.conversation_id.as_deref() == Some(id)
                    && view.generation == store.history_generation_token()
                    && view.history_stamp.as_ref()
                        == Some(&store.history_stamp_for(&crate::display::ViewSource::Main {
                            conversation_id: id.to_owned(),
                        }))
                    && view.touched.elapsed() < Duration::from_secs(2)
                    && publish.ui_observer_alive(*observer)
            })
    }
    pub fn completed(&mut self, id: &str, store: &mut Store, publish: &Publisher) {
        if !self.viewing(id, store, publish) {
            let changed = !store.preferences_unread(id);
            store.preferences_completed(id);
            if changed {
                let _ = store.flush_preferences();
            }
        }
        self.publish(store, publish);
    }
    fn publish(&mut self, store: &Store, publish: &Publisher) {
        let identity = store.preference_identity();
        if self.published.as_ref() != Some(&identity) {
            self.published = Some(identity);
            publish.emit(Event::ConversationPreferences(store.preference_snapshot()));
        }
    }
    pub fn tick(&mut self, store: &mut Store, publish: &Publisher) {
        self.views.retain(|observer, view| {
            view.generation == store.history_generation_token()
                && view.touched.elapsed() < Duration::from_secs(2)
                && publish.ui_observer_alive(*observer)
        });
        if store.preferences_dirty()
            && self
                .last_flush
                .is_none_or(|at| at.elapsed() >= Duration::from_millis(1500))
        {
            self.last_flush = Some(Instant::now());
            let _ = store.flush_preferences();
        }
        self.publish(store, publish);
    }
}
pub fn from_legacy(snapshot: &LegacySnapshot) -> Result<Option<Document>, String> {
    let mut result = None;
    let mut seen_order = false;
    let mut seen_reads = false;
    for document in &snapshot.documents {
        if document.kind == LegacyDocumentKind::DrawerOrder {
            if seen_order {
                return Err("旧抽屉顺序来源重复，未选择性覆盖".into());
            }
            seen_order = true;
            let mut order: Order = serde_json::from_value(document.value.clone())
                .map_err(|_| "旧抽屉顺序字段无法转换")?;
            if order.pinned.len() > MAX_IDS || order.normal.len() > MAX_IDS {
                return Err("旧会话顺序超过ID预算，原文保留".into());
            }
            for ids in [&mut order.pinned, &mut order.normal] {
                let mut unique = HashSet::new();
                ids.retain(|id| id_valid(id) && unique.insert(id.clone()));
            }
            result.get_or_insert_with(Document::default).order = order;
        }
    }
    for preferences in &snapshot.preference_stores {
        if preferences.source.relative_path == "datastore/Suixing_conversation_reads.preferences_pb"
        {
            if seen_reads {
                return Err("旧完成未读偏好来源重复".into());
            }
            seen_reads = true;
            let entries = preferences.string_sets.get("completed_unread");
            if preferences
                .entries
                .iter()
                .any(|entry| entry.key == "completed_unread")
                && entries.is_none()
            {
                return Err("旧completed_unread不是string-set，原字段未丢弃".into());
            }
            if let Some(entries) = entries {
                if entries.len() > MAX_IDS {
                    return Err("旧完成未读集合超过预算".into());
                }
                let document = result.get_or_insert_with(Document::default);
                for id in entries {
                    if id_valid(id) {
                        document.completed_unread.insert(id.clone());
                    }
                }
            }
        }
    }
    if let Some(document) = &result {
        document.validate()?;
    }
    Ok(result)
}
pub fn merge(
    current: &Document,
    imported: &Document,
    current_ids: &HashSet<String>,
    known: &HashSet<String>,
) -> Result<Document, String> {
    let mut result = current.clone();
    let append = |base: &mut Vec<String>, incoming: &[String]| {
        let mut seen = base.iter().cloned().collect::<HashSet<_>>();
        for id in incoming {
            if seen.insert(id.clone()) {
                base.push(id.clone());
            }
        }
    };
    append(&mut result.order.pinned, &imported.order.pinned);
    append(&mut result.order.normal, &imported.order.normal);
    result.order = result.order.pruned(known);
    result.completed_unread.retain(|id| known.contains(id));
    result.completed_unread.extend(
        imported
            .completed_unread
            .iter()
            .filter(|id| known.contains(*id) && !current_ids.contains(*id))
            .cloned(),
    );
    for (key, value) in &imported.extra {
        result
            .extra
            .entry(key.clone())
            .or_insert_with(|| value.clone());
    }
    if !result.advance() {
        return Err("会话查看偏好版本已用完，原状态未覆盖".into());
    }
    result.validate()?;
    Ok(result)
}

/// Feature adapters can be introduced after a migration has already committed.
/// A missing native preference document reads the exact sealed original sources.
pub(crate) fn from_archived(namespace: &std::path::Path) -> Result<Option<Document>, String> {
    let Some(catalog) = crate::legacy_migration::archive_catalog(namespace)? else {
        return Ok(None);
    };
    let mut document = Document::default();
    let mut any = false;
    let mut chosen_order = false;
    for relative in ["drawer-order", "drawer-order.tmp"] {
        let mappings = catalog
            .assets
            .iter()
            .filter(|mapping| mapping.relative_path == relative)
            .collect::<Vec<_>>();
        if mappings.len() > 1 {
            return Err("旧会话顺序归档来源有歧义".into());
        }
        if let Some(mapping) = mappings.first() {
            if mapping.bytes > 4 * 1024 * 1024 {
                return Err("旧会话顺序归档超过4MiB预算".into());
            }
            let bytes = crate::legacy_migration::read_archived_blob_bounded(
                &catalog.generation_root,
                mapping,
                &crate::platform::secrets::SecretBox,
                4 * 1024 * 1024,
            )?;
            if !chosen_order {
                if let Ok(mut order) = serde_json::from_slice::<Order>(&bytes) {
                    if order.pinned.len() <= MAX_IDS && order.normal.len() <= MAX_IDS {
                        for ids in [&mut order.pinned, &mut order.normal] {
                            let mut unique = HashSet::new();
                            ids.retain(|id| id_valid(id) && unique.insert(id.clone()));
                        }
                        document.order = order;
                        chosen_order = true;
                        any = true;
                    }
                }
            }
        }
    }
    let mappings = catalog
        .assets
        .iter()
        .filter(|mapping| {
            mapping.relative_path == "datastore/Suixing_conversation_reads.preferences_pb"
        })
        .collect::<Vec<_>>();
    if mappings.len() > 1 {
        return Err("旧完成未读归档来源有歧义".into());
    }
    if let Some(mapping) = mappings.first() {
        if mapping.bytes > 4 * 1024 * 1024 {
            return Err("旧完成未读归档超过4MiB预算".into());
        }
        let bytes = crate::legacy_migration::read_archived_blob_bounded(
            &catalog.generation_root,
            mapping,
            &crate::platform::secrets::SecretBox,
            4 * 1024 * 1024,
        )?;
        let preferences =
            crate::legacy_import::decode_preferences(crate::legacy_import::LegacyBlob {
                owning_root: mapping.owning_root.clone(),
                relative_path: mapping.relative_path.clone(),
                original_bytes: bytes,
                sha256: mapping.sha256.clone(),
            })?;
        if let Some(entries) = preferences.string_sets.get("completed_unread") {
            if entries.len() > MAX_IDS {
                return Err("旧完成未读集合超过预算".into());
            }
            document
                .completed_unread
                .extend(entries.iter().filter(|id| id_valid(id)).cloned());
            any = true;
        } else if preferences
            .entries
            .iter()
            .any(|entry| entry.key == "completed_unread")
        {
            return Err("旧完成未读归档字段不是string-set".into());
        }
    }
    catalog.recheck()?;
    if any {
        document.extra.insert(
            "legacy_archive_source".into(),
            serde_json::Value::String(catalog.source_fingerprint),
        );
        document.validate()?;
        Ok(Some(document))
    } else {
        Ok(None)
    }
}

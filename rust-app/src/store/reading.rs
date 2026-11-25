use super::*;
use crate::display::{HistorySource, ReadPosition, SourceStamp, ViewSource};
#[derive(Serialize, Deserialize)]
pub(super) struct ReadingDocument {
    pub positions: Vec<ReadPosition>,
}

pub(super) fn shared_positions(files: &Path) -> Result<Option<Vec<ReadPosition>>, String> {
    let Some(preferences) = shared_preferences::read(files, shared_preferences::VIEWPORTS)? else {
        return Ok(None);
    };
    let mut positions = Vec::new();
    for (key, text) in preferences.strings {
        let Some(id) = key.strip_prefix("position/") else {
            continue;
        };
        let Ok(mut bookmark) = serde_json::from_str::<crate::legacy_reading::Bookmark>(&text)
        else {
            continue;
        };
        if bookmark.conversation_id != id {
            continue;
        }
        let source = ViewSource::Main {
            conversation_id: id.into(),
        };
        let native = bookmark
            .extra
            .remove("rustPosition")
            .and_then(|value| serde_json::from_value::<ReadPosition>(value).ok())
            .filter(|position| position.source == source && position.validate().is_ok());
        if let Some(position) = native.or_else(|| bookmark.position().ok()) {
            positions.push(position);
        }
    }
    Ok(Some(positions))
}
pub(super) fn validate(positions: &[ReadPosition]) -> Result<(), String> {
    if positions.len() > crate::legacy_reading::MAX_POSITIONS {
        return Err("阅读位置超过8192条，已有位置保留".into());
    }
    let mut sources = HashSet::new();
    for position in positions {
        position.validate().map_err(|e| e.to_string())?;
        if !sources.insert(&position.source) {
            return Err("阅读位置来源重复".into());
        }
    }
    Ok(())
}
impl Store {
    pub fn history_stamp_for(&self, source: &ViewSource) -> SourceStamp {
        let key = match source {
            ViewSource::Main { conversation_id } => (conversation_id.clone(), None),
            ViewSource::Child {
                conversation_id,
                child_id,
                ..
            } => (conversation_id.clone(), Some(child_id.clone())),
        };
        let revision = self
            .history_revisions
            .get(&key)
            .copied()
            .unwrap_or(self.history_revision);
        SourceStamp {
            generation: self.history_generation.clone(),
            revision,
            projection_revision: revision,
        }
    }
    pub(super) fn advance_history_path(&mut self, path: &Path) {
        let Ok(relative) = path.strip_prefix(&self.root).or_else(|_| {
            path.strip_prefix(self.shared_files_root.as_deref().unwrap_or(&self.root))
        }) else {
            return;
        };
        let parts = relative
            .components()
            .filter_map(|p| p.as_os_str().to_str())
            .collect::<Vec<_>>();
        let key = match parts.as_slice() {
            ["chats", "store", file] => file.strip_suffix(".meta").map(|id| (id.to_owned(), None)),
            ["conversations", file] => file
                .strip_suffix(".segments.json")
                .or_else(|| file.strip_suffix(".json"))
                .map(|id| (id.to_owned(), None)),
            ["subagents", chat, file] => file
                .strip_suffix(".json")
                .map(|id| ((*chat).to_owned(), Some(id.to_owned()))),
            _ => None,
        };
        if let Some(key) = key {
            self.history_revisions
                .insert(key, crate::controller::next_display_revision());
        }
    }
    pub fn read_position(&self, source: &ViewSource) -> Option<ReadPosition> {
        let mut position = self
            .reading_positions
            .iter()
            .find(|p| &p.source == source)
            .cloned()?;
        if position.legacy.is_some() {
            let stamp = self.history_stamp_for(source);
            if let Ok(history) = self.open_history(
                source.clone(),
                stamp.revision,
                stamp.projection_revision,
                || false,
            ) {
                let empty = BTreeMap::new();
                let choices = self
                    .conversation(source.conversation_id())
                    .map(|chat| &chat.metadata.active_runs)
                    .unwrap_or(&empty);
                if let Ok(projection) =
                    crate::display::ProjectionIndex::new(history.headers(), choices, None, || false)
                {
                    let _ = crate::legacy_reading::resolve(
                        &mut position,
                        &history,
                        projection.selected_indices(),
                    );
                }
            }
        }
        Some(position)
    }
    pub fn save_read_position(&mut self, position: ReadPosition) -> Result<(), String> {
        self.writable()?;
        position.validate().map_err(|e| e.to_string())?;
        let mut positions = self.reading_positions.clone();
        positions.retain(|p| p.source != position.source);
        if positions.len() >= crate::legacy_reading::MAX_POSITIONS {
            return Err("阅读位置已达8192条上限，已有位置未删除".into());
        }
        if let ViewSource::Main { conversation_id } = &position.source
            && let Some(files) = self.shared_files_root.as_deref()
        {
            let key = format!("position/{conversation_id}");
            let mut bookmark = if let Some(legacy) = &position.legacy {
                serde_json::to_value(legacy).map_err(|error| error.to_string())?
            } else {
                serde_json::json!({
                    "conversationId":conversation_id, "messageId":(!position.message_id.is_empty()).then_some(&position.message_id),
                    "followLatest":position.follow_tail, "topAnchored":true,
                })
            };
            bookmark["rustPosition"] =
                serde_json::to_value(&position).map_err(|error| error.to_string())?;
            let value = crate::legacy_import::LegacyPreferenceValue::String(bookmark.to_string());
            shared_preferences::save(files, shared_preferences::VIEWPORTS, &key, Some(&value))?;
            positions.push(position);
            self.reading_positions = positions;
            return Ok(());
        }
        positions.push(position);
        validate(&positions)?;
        let document = ReadingDocument { positions };
        // Reading does not change the owning history clock or invalidate display pages.
        self.persist(
            &self.root.join("reading-positions.json"),
            &document,
            crate::legacy_reading::DOCUMENT_BYTES,
        )?;
        self.reading_positions = document.positions;
        Ok(())
    }
}

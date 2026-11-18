use super::*;
use crate::display::{HistorySource, ReadPosition, SourceStamp, ViewSource};
#[derive(Serialize, Deserialize)]
pub(super) struct ReadingDocument {
    pub positions: Vec<ReadPosition>,
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
        let Ok(relative) = path.strip_prefix(&self.root) else {
            return;
        };
        let parts = relative
            .components()
            .filter_map(|p| p.as_os_str().to_str())
            .collect::<Vec<_>>();
        let key = match parts.as_slice() {
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

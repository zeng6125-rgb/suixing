use super::*;
use crate::conversation_preferences::{Document, Order, Snapshot, Stamp};

const LIMIT: u64 = 4 * 1024 * 1024;

pub(super) fn shared_order(files: &Path) -> Option<Order> {
    ["drawer-order", "drawer-order.tmp"]
        .into_iter()
        .find_map(|name| {
            optional_json(files, &files.join(name), LIMIT)
                .ok()
                .flatten()
        })
}

impl Store {
    pub(crate) fn preference_identity(&self) -> (String, u64, bool) {
        (
            self.history_generation_token(),
            self.conversation_preferences.revision,
            self.conversation_preferences_confirmed,
        )
    }
    pub(crate) fn preference_snapshot(&self) -> Snapshot {
        let known = self
            .conversations
            .iter()
            .map(|conversation| conversation.id.clone())
            .collect::<HashSet<_>>();
        Snapshot {
            stamp: Stamp {
                generation: self.history_generation_token(),
                revision: self.conversation_preferences.revision,
            },
            order: self.conversation_preferences.order.pruned(&known),
            completed_unread: self
                .conversation_preferences
                .completed_unread
                .iter()
                .filter(|id| known.contains(*id))
                .cloned()
                .collect(),
            confirmed: self.conversation_preferences_confirmed,
        }
    }
    pub(crate) fn preferences_unread(&self, id: &str) -> bool {
        self.conversation_preferences.completed_unread.contains(id)
    }
    pub(crate) fn preferences_dirty(&self) -> bool {
        self.conversation_preferences_dirty
    }
    pub(crate) fn flush_preferences(&mut self) -> Result<(), String> {
        self.writable()?;
        self.conversation_preferences.validate()?;
        let mut private = self.conversation_preferences.clone();
        if let Some(files) = self.shared_files_root.as_deref() {
            atomic_json(files, &files.join("drawer-order"), &private.order, LIMIT).map_err(
                |error| {
                    self.conversation_preferences_confirmed = false;
                    error.message
                },
            )?;
            private.order = Order::default();
            let unread = crate::legacy_import::LegacyPreferenceValue::StringSet(
                private.completed_unread.iter().cloned().collect(),
            );
            shared_preferences::save(
                files,
                shared_preferences::READS,
                "completed_unread",
                Some(&unread),
            )
            .inspect_err(|_| self.conversation_preferences_confirmed = false)?;
            private.completed_unread.clear();
        }
        // View preference failures never reinterpret a transcript commit or poison
        // its owner. The sole preference writer retains the latest exact snapshot.
        match atomic_json(
            &self.root,
            &self.root.join("conversation-preferences.json"),
            &private,
            LIMIT,
        ) {
            Ok(()) => {
                self.conversation_preferences_dirty = false;
                self.conversation_preferences_confirmed = true;
                Ok(())
            }
            Err(error) => {
                self.conversation_preferences_confirmed = false;
                Err(error.message)
            }
        }
    }
    fn preferences_change(&mut self, mut document: Document) -> Result<bool, String> {
        if document.order == self.conversation_preferences.order
            && document.completed_unread == self.conversation_preferences.completed_unread
        {
            return Ok(false);
        }
        if !document.advance() {
            return Err("会话查看偏好版本已用完".into());
        }
        document.validate()?;
        self.conversation_preferences = document;
        self.conversation_preferences_dirty = true;
        self.conversation_preferences_confirmed = false;
        Ok(true)
    }
    pub(crate) fn preferences_seen(&mut self, id: &str) {
        if !self.conversation_preferences.completed_unread.contains(id) {
            return;
        }
        let mut document = self.conversation_preferences.clone();
        document.completed_unread.remove(id);
        let _ = self.preferences_change(document);
    }
    pub(crate) fn preferences_completed(&mut self, id: &str) {
        if self.conversation(id).is_err()
            || self.conversation_preferences.completed_unread.contains(id)
        {
            return;
        }
        let mut document = self.conversation_preferences.clone();
        document.completed_unread.insert(id.into());
        let _ = self.preferences_change(document);
    }
    pub(crate) fn preferences_activity(&mut self, id: &str) {
        if !self
            .conversation_preferences
            .order
            .normal
            .iter()
            .any(|old| old == id)
        {
            return;
        }
        let mut document = self.conversation_preferences.clone();
        document.order.normal.retain(|old| old != id);
        let _ = self.preferences_change(document);
    }
    pub(crate) fn preferences_pin(&mut self, id: &str, pinned: bool) {
        let mut document = self.conversation_preferences.clone();
        document.order.pinned.retain(|old| old != id);
        if pinned {
            document.order.pinned.push(id.into());
            document.order.normal.retain(|old| old != id);
        }
        let _ = self.preferences_change(document);
    }
    pub(crate) fn preferences_forget(&mut self, id: &str) {
        let mut document = self.conversation_preferences.clone();
        document.order.pinned.retain(|old| old != id);
        document.order.normal.retain(|old| old != id);
        document.completed_unread.remove(id);
        let _ = self.preferences_change(document);
    }
    pub(crate) fn preferences_move(
        &mut self,
        request_id: &str,
        expected: &Stamp,
        source: &str,
        target: &str,
        after: bool,
    ) -> Result<(), String> {
        self.writable()?;
        validate_id(request_id)?;
        validate_id(source)?;
        validate_id(target)?;
        if source == target {
            return Ok(());
        }
        if expected.generation != self.history_generation_token()
            || expected.revision != self.conversation_preferences.revision
        {
            return Err("列表或顺序已变化，本次拖动未覆盖新顺序，请重新拖动".into());
        }
        let source_chat = self.conversation(source)?;
        let target_chat = self.conversation(target)?;
        if source_chat.metadata.archived
            || target_chat.metadata.archived
            || source_chat.metadata.pinned != target_chat.metadata.pinned
        {
            return Err("排序只能在同一置顶或普通分组内进行".into());
        }
        let pinned = source_chat.metadata.pinned;
        let summaries = self.conversation_summaries();
        let mut group = self
            .conversation_preferences
            .order
            .indices(&summaries)
            .into_iter()
            .filter_map(|index| summaries.get(index))
            .filter(|chat| chat.pinned == pinned)
            .map(|chat| chat.id.clone())
            .collect::<Vec<_>>();
        group.retain(|id| id != source);
        let position = group
            .iter()
            .position(|id| id == target)
            .ok_or("排序目标已不存在")?
            + usize::from(after);
        group.insert(position, source.into());
        let mut document = self.conversation_preferences.clone();
        let known = summaries
            .iter()
            .map(|chat| chat.id.clone())
            .collect::<HashSet<_>>();
        document.order = document.order.pruned(&known);
        if pinned {
            document.order.pinned = group;
        } else {
            document.order.normal = group;
        }
        self.preferences_change(document)?;
        self.flush_preferences()
            .map_err(|_| "顺序已在当前界面应用，写入尚未确认；请稍后重试".into())
    }
}

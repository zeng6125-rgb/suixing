//! Bounded, metadata-only pages of the actual selected conversation turns.
use super::{DisplayError, HistorySource, MessageHeader, SourceStamp, ViewSource};

pub const MAX_TURN_PAGE: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TurnTarget {
    pub source: ViewSource,
    pub stamp: SourceStamp,
    /// One based global ordinal, never a cached display-row index.
    pub ordinal: usize,
    pub message_id: String,
    pub run_id: Option<String>,
}
impl TurnTarget {
    pub fn estimated_bytes(&self) -> usize {
        256 + self.source.estimated_bytes()
            + 6 * (self.message_id.len()
                + self.stamp.generation.len()
                + self.run_id.as_ref().map_or(0, String::len))
    }
}

#[derive(Clone, Debug)]
pub struct TurnRequest {
    pub request_id: String,
    pub source: ViewSource,
    pub stamp: SourceStamp,
    /// None centres the page on the actual reading message, or the tail.
    pub first: Option<usize>,
    pub reading_message_id: String,
    pub max_turns: usize,
}

#[derive(Clone, Debug)]
pub struct TurnPage {
    pub request_id: String,
    pub source: ViewSource,
    pub stamp: SourceStamp,
    pub total: usize,
    pub current: usize,
    pub first: usize,
    pub entries: Vec<TurnTarget>,
}
impl TurnPage {
    pub fn estimated_bytes(&self) -> usize {
        512 + self.source.estimated_bytes()
            + 6 * (self.request_id.len() + self.stamp.generation.len())
            + self
                .entries
                .iter()
                .map(TurnTarget::estimated_bytes)
                .sum::<usize>()
    }
}

#[derive(Default)]
pub(super) struct TurnIndex {
    // At most MAX_ROWS pairs of integer source indices; no bodies or duplicate IDs.
    rows: Vec<(usize, Option<usize>)>,
}
impl TurnIndex {
    pub(super) fn new(
        headers: &[MessageHeader],
        selected: &[usize],
        cancelled: &impl Fn() -> bool,
    ) -> Result<Self, DisplayError> {
        let mut rows: Vec<(usize, Option<usize>)> = Vec::new();
        for &index in selected {
            super::check(cancelled)?;
            let row = headers.get(index).ok_or(DisplayError::Stale)?;
            if row.role == "user" && !row.steer {
                rows.push((index, None));
            }
            if row.role != "user" && row.run_id.is_some() {
                if let Some((_, run)) = rows.last_mut() {
                    if run.is_none() {
                        *run = Some(index);
                    }
                }
            }
        }
        Ok(Self { rows })
    }
    fn target(
        &self,
        source: &impl HistorySource,
        ordinal: usize,
    ) -> Result<TurnTarget, DisplayError> {
        let &(index, run) = self
            .rows
            .get(ordinal.checked_sub(1).ok_or(DisplayError::Missing)?)
            .ok_or(DisplayError::Missing)?;
        let row = source.headers().get(index).ok_or(DisplayError::Stale)?;
        Ok(TurnTarget {
            source: source.source().clone(),
            stamp: source.stamp().clone(),
            ordinal,
            message_id: row.id.clone(),
            run_id: run
                .and_then(|index| source.headers().get(index))
                .and_then(|row| row.run_id.clone()),
        })
    }
    pub(super) fn page(
        &self,
        source: &impl HistorySource,
        request: &TurnRequest,
        reading_index: Option<usize>,
        cancelled: &impl Fn() -> bool,
    ) -> Result<TurnPage, DisplayError> {
        super::check(cancelled)?;
        if &request.source != source.source()
            || request.stamp.generation != source.stamp().generation
        {
            return Err(DisplayError::Stale);
        }
        let total = self.rows.len();
        let current = if request.reading_message_id.is_empty() {
            total
        } else if let Some(index) = reading_index {
            self.rows
                .partition_point(|&(start, _)| start <= index)
                .max(usize::from(total > 0))
        } else {
            return Err(DisplayError::Stale);
        };
        let size = request.max_turns.clamp(1, MAX_TURN_PAGE).min(total);
        let first = if total == 0 {
            0
        } else {
            request
                .first
                .unwrap_or_else(|| current.saturating_sub(size / 2))
                .clamp(1, total - size + 1)
        };
        let mut entries = Vec::with_capacity(size);
        for ordinal in first..first + size {
            super::check(cancelled)?;
            entries.push(self.target(source, ordinal)?);
        }
        let page = TurnPage {
            request_id: request.request_id.clone(),
            source: request.source.clone(),
            stamp: source.stamp().clone(),
            total,
            current,
            first,
            entries,
        };
        if page.estimated_bytes() > super::MAX_WINDOW_BYTES {
            return Err(DisplayError::Budget);
        }
        Ok(page)
    }
    pub(super) fn validate(
        &self,
        source: &impl HistorySource,
        target: &TurnTarget,
    ) -> Result<(), DisplayError> {
        let current = self.target(source, target.ordinal)?;
        if current.source != target.source
            || current.stamp.generation != target.stamp.generation
            || current.message_id != target.message_id
            || current.run_id != target.run_id
        {
            return Err(DisplayError::Stale);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::display::MemoryHistory;

    fn source<'a>() -> MemoryHistory<'a> {
        MemoryHistory {
            source: ViewSource::Main {
                conversation_id: "chat".into(),
            },
            stamp: SourceStamp {
                generation: "live-run".into(),
                revision: 1,
                projection_revision: 1,
            },
            messages: &[],
            headers: &[],
        }
    }

    fn headers() -> Vec<MessageHeader> {
        [
            serde_json::json!({"id":"question", "role":"user", "complete":true}),
            serde_json::json!({"id":"reply", "role":"assistant", "complete":false, "runId":"reply-run"}),
        ].into_iter().map(|value| serde_json::from_value(value).unwrap()).collect()
    }

    #[test]
    fn metadata_navigation_survives_later_streaming_fragments() {
        let headers = headers();
        let mut source = source();
        source.headers = &headers;
        let index = TurnIndex::new(&headers, &[0, 1], &|| false).unwrap();
        let target = index.target(&source, 1).unwrap();
        let request = TurnRequest {
            request_id: "directory".into(),
            source: source.source.clone(),
            stamp: source.stamp.clone(),
            first: None,
            reading_message_id: String::new(),
            max_turns: MAX_TURN_PAGE,
        };
        source.stamp.revision = 50;
        source.stamp.projection_revision = 50;
        index.validate(&source, &target).unwrap();
        let page = index.page(&source, &request, None, &|| false).unwrap();
        assert_eq!(page.stamp, source.stamp);
        assert_eq!(page.entries[0].message_id, "question");
    }

    #[test]
    fn metadata_navigation_rejects_changed_turn_identity() {
        let headers = headers();
        let mut source = source();
        source.headers = &headers;
        let index = TurnIndex::new(&headers, &[0, 1], &|| false).unwrap();
        let target = index.target(&source, 1).unwrap();
        let mut changed = target.clone();
        changed.source = ViewSource::Main {
            conversation_id: "other-chat".into(),
        };
        assert_eq!(index.validate(&source, &changed), Err(DisplayError::Stale));
        changed = target.clone();
        changed.stamp.generation = "other-run".into();
        assert_eq!(index.validate(&source, &changed), Err(DisplayError::Stale));
        changed = target.clone();
        changed.message_id = "replacement-question".into();
        assert_eq!(index.validate(&source, &changed), Err(DisplayError::Stale));
        changed = target.clone();
        changed.run_id = Some("other-reply".into());
        assert_eq!(index.validate(&source, &changed), Err(DisplayError::Stale));
    }
}

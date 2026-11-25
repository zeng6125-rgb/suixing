//! Bounded, live-page-only text reveal. Historical/remounted content starts at
//! its existing length; only newly appended prose animates after admission.
use crate::display::{BodyKind, ViewSource};
use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

const NANOS_PER_CHAR: u128 = 12_500_000;
type Key = (ViewSource, String, BodyKind, u64);
struct Reveal {
    text: String,
    shown: usize,
    last: Instant,
    touched: u64,
}
#[derive(Default)]
pub(super) struct Typewriter {
    pages: HashMap<Key, Reveal>,
    clock: u64,
}
impl Typewriter {
    pub(super) fn finish(
        &mut self,
        source: &ViewSource,
        message_id: &str,
        kind: &BodyKind,
        from_byte: u64,
    ) {
        if !self.pages.is_empty() {
            self.pages.retain(|key, _| {
                !(key.0 == *source && key.1 == message_id && key.2 == *kind && key.3 == from_byte)
            });
        }
    }

    pub(super) fn prefix(&mut self, key: Key, text: &str, animate: bool, now: Instant) -> usize {
        self.clock = self.clock.saturating_add(1);
        if text.len() > 128 * 1024 {
            return text.len();
        }
        if !self.pages.contains_key(&key) && self.pages.len() >= 64 {
            if let Some(old) = self
                .pages
                .iter()
                .min_by_key(|(_, page)| page.touched)
                .map(|(key, _)| key.clone())
            {
                self.pages.remove(&old);
            }
        }
        let page = self.pages.entry(key).or_insert_with(|| Reveal {
            text: text.into(),
            shown: text.len(),
            last: now,
            touched: self.clock,
        });
        page.touched = self.clock;
        if let Some(append) = text.strip_prefix(page.text.as_str()) {
            page.text.push_str(append);
        } else {
            page.text.clear();
            page.text.push_str(text);
            page.shown = text.len();
        }
        if !animate || page.shown == text.len() {
            page.shown = text.len();
            page.last = now;
            return page.shown;
        }
        let elapsed = now.duration_since(page.last).as_nanos();
        let count = (elapsed / NANOS_PER_CHAR) as usize;
        if count > 0 {
            let suffix = &text[page.shown..];
            page.shown += suffix
                .char_indices()
                .nth(count)
                .map_or(suffix.len(), |(offset, _)| offset);
            page.last = if page.shown == text.len() {
                now
            } else {
                now - Duration::from_nanos((elapsed % NANOS_PER_CHAR) as u64)
            };
        }
        page.shown
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> Key {
        (
            ViewSource::Main {
                conversation_id: "conversation".into(),
            },
            "message".into(),
            BodyKind::Text,
            0,
        )
    }

    #[test]
    fn fractional_character_time_survives_each_frame() {
        let start = Instant::now();
        let mut animation = Typewriter::default();
        let text = "字".repeat(100);
        animation.prefix(key(), "", true, start);
        for frame in 1..=120 {
            let now = start + Duration::from_nanos(frame * 1_000_000_000 / 120);
            let expected_chars = now.duration_since(start).as_nanos() / 12_500_000;
            assert_eq!(
                animation.prefix(key(), &text, true, now),
                expected_chars as usize * "字".len(),
                "frame {frame}",
            );
        }
    }

    #[test]
    fn replacement_shows_immediately_and_append_keeps_the_visible_prefix() {
        let start = Instant::now();
        let mut animation = Typewriter::default();
        assert_eq!(animation.prefix(key(), "字", true, start), "字".len());
        assert_eq!(animation.prefix(key(), "字句", true, start), "字".len());
        assert_eq!(
            animation.prefix(key(), "新内容", true, start),
            "新内容".len()
        );
        assert_eq!(
            animation.prefix(key(), "新内容🙂", true, start + Duration::from_millis(13)),
            "新内容🙂".len(),
        );
    }

    #[test]
    fn finish_clears_active_entry_without_retaining_settled_pages() {
        let start = Instant::now();
        let mut animation = Typewriter::default();
        let (source, message_id, kind, from_byte) = key();
        animation.finish(&source, &message_id, &kind, from_byte);
        assert!(animation.pages.is_empty());

        animation.prefix(key(), "alpha", true, start);
        assert_eq!(animation.pages.len(), 1);
        animation.finish(&source, &message_id, &kind, from_byte);
        assert!(animation.pages.is_empty());
    }
}

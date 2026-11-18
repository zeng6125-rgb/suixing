//! Bounded, live-page-only text reveal. Historical/remounted content starts at
//! its existing length; only newly appended prose animates after admission.
use crate::display::{BodyKind, ViewSource};
use std::{collections::HashMap, time::Instant};
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
    pub(super) fn prefix(&mut self, key: Key, text: &str, animate: bool) -> usize {
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
        let now = Instant::now();
        let page = self.pages.entry(key).or_insert_with(|| Reveal {
            text: text.into(),
            shown: text.len(),
            last: now,
            touched: self.clock,
        });
        page.touched = self.clock;
        if !animate || !text.starts_with(&page.text) {
            page.shown = text.len();
            page.last = now;
        }
        if page.text != text {
            page.text.clear();
            page.text.push_str(text);
        }
        if page.shown < text.len() {
            let count = (now.duration_since(page.last).as_secs_f64() * 80.0).floor() as usize;
            if count > 0 {
                let suffix = &text[page.shown..];
                let bytes = suffix
                    .char_indices()
                    .nth(count)
                    .map_or(suffix.len(), |(offset, _)| offset);
                page.shown += bytes;
                page.last = now;
            }
        } else {
            page.last = now;
        }
        page.shown
    }
}

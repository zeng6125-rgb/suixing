//! Incremental UTF-8 decoding, with legacy monotonic UTF-16 tool cursors.
use serde::Serialize;
#[derive(Default)]
pub(super) struct Ring {
    text: String,
    base: u64,
    pending: Vec<u8>,
}
#[derive(Clone, Debug, Serialize)]
pub struct ReadWindow {
    pub output: String,
    pub cursor: u64,
    pub truncated: bool,
    pub has_more: bool,
}
impl Ring {
    pub fn append(&mut self, bytes: &[u8], end: bool) {
        self.pending.extend_from_slice(bytes);
        let mut at = 0;
        while at < self.pending.len() {
            match std::str::from_utf8(&self.pending[at..]) {
                Ok(text) => {
                    self.text.push_str(text);
                    at = self.pending.len();
                }
                Err(error) => {
                    let valid = error.valid_up_to();
                    if valid > 0 {
                        self.text.push_str(
                            std::str::from_utf8(&self.pending[at..at + valid])
                                .expect("validated prefix"),
                        );
                        at += valid;
                    }
                    if let Some(length) = error.error_len() {
                        self.text.push('\u{fffd}');
                        at += length;
                    } else if end {
                        self.text.push('\u{fffd}');
                        at = self.pending.len();
                    } else {
                        break;
                    }
                }
            }
        }
        self.pending.drain(..at);
        let units: usize = self.text.chars().map(char::len_utf16).sum();
        if units > 256 * 1024 {
            let mut dropped = 0;
            let mut stop = 0;
            for (index, value) in self.text.char_indices() {
                dropped += value.len_utf16();
                stop = index + value.len_utf8();
                if dropped >= units - 256 * 1024 {
                    break;
                }
            }
            self.text.drain(..stop);
            self.base = self.base.saturating_add(dropped as u64);
        }
    }
    pub fn clear(&mut self) {
        self.base = self
            .base
            .saturating_add(self.text.chars().map(|c| c.len_utf16() as u64).sum::<u64>());
        self.text.clear();
    }
    pub fn read(&self, cursor: Option<u64>, max_bytes: usize) -> Result<ReadWindow, String> {
        if !(1..=16 * 1024).contains(&max_bytes) {
            return Err("终端读取必须在1–16 KiB内".into());
        }
        let end = self
            .base
            .saturating_add(self.text.chars().map(|c| c.len_utf16() as u64).sum::<u64>());
        if let Some(cursor) = cursor {
            let target = cursor.clamp(self.base, end);
            let mut units = self.base;
            let mut start = self.text.len();
            for (index, value) in self.text.char_indices() {
                if units >= target {
                    start = index;
                    break;
                }
                units += value.len_utf16() as u64;
            }
            let mut stop = start;
            let mut count = 0;
            let mut next = units;
            for (offset, value) in self.text[start..].char_indices() {
                if count + value.len_utf8() > max_bytes {
                    break;
                }
                count += value.len_utf8();
                stop = start + offset + value.len_utf8();
                next += value.len_utf16() as u64;
            }
            Ok(ReadWindow {
                output: self.text[start..stop].into(),
                cursor: next,
                truncated: cursor < self.base,
                has_more: stop < self.text.len(),
            })
        } else {
            let mut start = self.text.len();
            let mut count = 0;
            for (index, value) in self.text.char_indices().rev() {
                if count + value.len_utf8() > max_bytes {
                    break;
                }
                count += value.len_utf8();
                start = index;
            }
            Ok(ReadWindow {
                output: self.text[start..].into(),
                cursor: end,
                truncated: self.base > 0 || start > 0,
                has_more: false,
            })
        }
    }
}
